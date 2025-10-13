// 文件: app/src/main/java/com/infiniteclipboard/ui/TestProbeActivity.kt
// 测试探针页：点击“开始探测”后，按序尝试多种读取方案，立刻给出成功/失败与命中方法
package com.infiniteclipboard.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.infiniteclipboard.databinding.ActivityTestProbeBinding
import com.infiniteclipboard.utils.ClipboardUtils
import com.infiniteclipboard.utils.LogUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TestProbeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTestProbeBinding
    private lateinit var cm: ClipboardManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTestProbeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.title = "剪贴板探针"
        cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        binding.btnStart.setOnClickListener { runProbe() }
        binding.btnStop.setOnClickListener { finish() }
    }

    private fun runProbe() {
        binding.tvResult.text = ""
        log("开始探测：请确保已在系统剪贴板复制好要测试的内容")

        lifecycleScope.launch(Dispatchers.IO) {
            var hitMethod: String? = null
            var got: String? = null

            // 方法1：直接读取（ClipboardManager + coerceToText）
            val direct = try {
                val clip = cm.primaryClip
                val txt = if (clip != null && clip.itemCount > 0)
                    clip.getItemAt(0).coerceToText(this@TestProbeActivity)?.toString()
                else null
                txt
            } catch (e: Throwable) {
                null
            }
            if (!direct.isNullOrEmpty()) {
                hitMethod = "直接读取"
                got = direct
                log("方法1 命中：直接读取")
            } else {
                log("方法1 失败：直接读取为空/异常")
            }

            // 方法2：鲁棒读取（coerceToText + 多item合并）
            if (hitMethod == null) {
                val robust = try { ClipboardUtils.getClipboardTextRobust(this@TestProbeActivity) } catch (_: Throwable) { null }
                if (!robust.isNullOrEmpty()) {
                    hitMethod = "鲁棒读取"
                    got = robust
                    log("方法2 命中：鲁棒读取")
                } else {
                    log("方法2 失败：鲁棒读取为空/异常")
                }
            }

            // 方法3：短延迟重试读取
            if (hitMethod == null) {
                val retry = try { ClipboardUtils.getClipboardTextWithRetries(this@TestProbeActivity, attempts = 4, intervalMs = 120L) } catch (_: Throwable) { null }
                if (!retry.isNullOrEmpty()) {
                    hitMethod = "重试读取"
                    got = retry
                    log("方法3 命中：重试读取")
                } else {
                    log("方法3 失败：重试读取为空/异常")
                }
            }

            // 方法4：瞬时前台读取（拉起透明页，读取后立即关闭）
            if (hitMethod == null) {
                try {
                    withContext(Dispatchers.Main) {
                        val it = Intent(this@TestProbeActivity, TapRecordActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                        startActivity(it)
                    }
                    delay(280L) // 等待透明页完成读取窗口
                    val front = try { ClipboardUtils.getClipboardTextRobust(this@TestProbeActivity) } catch (_: Throwable) { null }
                    if (!front.isNullOrEmpty()) {
                        hitMethod = "瞬时前台"
                        got = front
                        log("方法4 命中：瞬时前台")
                    } else {
                        log("方法4 失败：瞬时前台读取为空/异常")
                    }
                } catch (e: Throwable) {
                    log("方法4 异常：${e.message ?: e.javaClass.simpleName}")
                }
            }

            // 汇总结果
            withContext(Dispatchers.Main) {
                val sb = StringBuilder()
                if (hitMethod != null) {
                    sb.append("结果：成功\n\n")
                    sb.append("命中方法：").append(hitMethod).append("\n\n")
                    sb.append("内容（前80字符）：\n").append(got?.take(80) ?: "(空)").append("\n")
                } else {
                    sb.append("结果：失败\n\n")
                    sb.append("所有方法均未获取到剪贴板内容。\n")
                }
                binding.tvResult.text = sb.toString()
                LogUtils.d("TestProbe", sb.toString())
            }
        }
    }

    private fun log(msg: String) {
        val line = msg
        LogUtils.d("TestProbe", line)
        val old = binding.tvResult.text?.toString().orEmpty()
        val cur = if (old.isEmpty()) line else old + "\n" + line
        binding.tvResult.text = cur
    }
}