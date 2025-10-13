// 文件: app/src/main/java/com/infiniteclipboard/ui/TestProbeActivity.kt
// 测试探针页：一键开始 -> 多路探测剪贴板变化（监听/轮询/瞬时前台）直到命中，显示命中路径
package com.infiniteclipboard.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.infiniteclipboard.databinding.ActivityTestProbeBinding
import com.infiniteclipboard.service.ClipboardMonitorService
import com.infiniteclipboard.utils.ClipboardUtils
import com.infiniteclipboard.utils.LogUtils
import kotlinx.coroutines.*

class TestProbeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTestProbeBinding
    private lateinit var cm: ClipboardManager

    private var baseline: String? = null
    private var changedText: String? = null

    private var listenerHit = false
    private var pollHit = false
    private var frontHit = false

    private var running = false
    private var probeJob: Job? = null

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        listenerHit = true
        lifecycleScope.launch(Dispatchers.IO) {
            val now = ClipboardUtils.getClipboardTextRobust(this@TestProbeActivity)
            if (!now.isNullOrEmpty() && now != baseline) {
                changedText = now
            }
        }
        logLine("监听器触发: OnPrimaryClipChanged")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTestProbeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.title = "剪贴板探针"
        cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        binding.btnStart.setOnClickListener { startProbe() }
        binding.btnStop.setOnClickListener { stopProbe() }

        // 确保前台服务常驻（避免进程被杀影响探测）
        ClipboardMonitorService.start(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopProbe()
    }

    private fun startProbe() {
        if (running) return
        running = true
        binding.tvResult.text = ""
        baseline = ClipboardUtils.getClipboardTextRobust(this)
        changedText = null
        listenerHit = false
        pollHit = false
        frontHit = false

        logLine("基线文本: ${baseline?.take(80)}")
        try { cm.addPrimaryClipChangedListener(clipListener) } catch (_: Throwable) { }

        // 同时启用：轮询 + 定期瞬时前台采集
        probeJob = lifecycleScope.launch(Dispatchers.IO) {
            val startTs = System.currentTimeMillis()
            var lastFrontAt = 0L

            while (running) {
                // 轮询检测
                val now = ClipboardUtils.getClipboardTextRobust(this@TestProbeActivity)
                if (!now.isNullOrEmpty() && now != baseline) {
                    pollHit = true
                    changedText = now
                }

                // 周期性（每800ms）触发一次瞬时前台读取（若尚未命中）
                val nowTs = System.currentTimeMillis()
                if (changedText == null && nowTs - lastFrontAt >= 800L) {
                    try {
                        val it = Intent(this@TestProbeActivity, TapRecordActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                        withContext(Dispatchers.Main) { startActivity(it) }
                        // 等待采集完成
                        delay(260L)
                        val fr = ClipboardUtils.getClipboardTextRobust(this@TestProbeActivity)
                        if (!fr.isNullOrEmpty() && fr != baseline) {
                            frontHit = true
                            changedText = fr
                        }
                    } catch (_: Throwable) { }
                    lastFrontAt = nowTs
                }

                // 终止条件：任一路命中变化
                if (!changedText.isNullOrEmpty()) {
                    withContext(Dispatchers.Main) {
                        showResult()
                    }
                    break
                }

                // 超时保护（30秒）
                if (nowTs - startTs > 30_000L) {
                    withContext(Dispatchers.Main) {
                        logLine("超时未检测到变更，请确认目标App是否写入系统剪贴板")
                        showResult(timeout = true)
                    }
                    break
                }

                delay(150L)
            }

            stopProbe()
        }

        logLine("开始探测：请在目标App执行复制/剪切，然后返回查看结果")
    }

    private fun stopProbe() {
        running = false
        try { cm.removePrimaryClipChangedListener(clipListener) } catch (_: Throwable) { }
        probeJob?.cancel()
        probeJob = null
    }

    private fun showResult(timeout: Boolean = false) {
        val sb = StringBuilder()
        if (timeout) sb.append("结果：超时（未检测到变化）\n\n")
        else sb.append("结果：已检测到剪贴板变化\n\n")

        sb.append("命中路径：\n")
        sb.append(" - 监听器 OnPrimaryClipChanged: ").append(if (listenerHit) "是" else "否").append("\n")
        sb.append(" - 轮询读取 Poll: ").append(if (pollHit) "是" else "否").append("\n")
        sb.append(" - 瞬时前台 Front: ").append(if (frontHit) "是" else "否").append("\n\n")

        sb.append("变化内容（前80字符）：\n")
        val txt = changedText ?: "(空)"
        sb.append(txt.take(80)).append("\n")

        binding.tvResult.text = sb.toString()
        LogUtils.d("TestProbe", sb.toString())
    }

    private fun logLine(line: String) {
        val old = binding.tvResult.text?.toString().orEmpty()
        val cur = if (old.isEmpty()) line else old + "\n" + line
        binding.tvResult.text = cur
        LogUtils.d("TestProbe", line)
    }
}