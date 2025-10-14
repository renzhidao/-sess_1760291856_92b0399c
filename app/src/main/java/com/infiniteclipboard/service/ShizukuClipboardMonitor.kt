// 文件: app/src/main/java/com/infiniteclipboard/service/ShizukuClipboardMonitor.kt
package com.infiniteclipboard.service

import android.content.Context
import android.os.SystemClock
import com.infiniteclipboard.ClipboardApplication
import com.infiniteclipboard.utils.LogUtils
import kotlinx.coroutines.*
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicLong

/**
 * 后台“无感知”剪贴板读取（最终兜底方案）：
 * - 不再依赖 bindUserService（跨应用绑定在部分 ROM 会被限制导致超时）
 * - 不在应用进程做 IClipboard 反射（会命中 checkPackage 校验）
 * - 直接用 Shizuku 以 shell 身份执行系统命令：
 *      1) /system/bin/cmd clipboard get           (Android 10+ 常见)
 *      2) 失败时尝试 toybox 兼容路径               (部分 ROM PATH 差异)
 *      3) 解析输出中的文本项（支持多 item→合并换行）
 *
 * 行为：
 * - start(context) 后每 500ms 轮询一次
 * - onPrimaryClipChanged() 触发 6 次短 burst（120ms 间隔），覆盖“回调先到、内容后到”的窗口
 * - 无 UI、无前台打扰、不可见，不影响用户操作
 */
object ShizukuClipboardMonitor {

    private const val TAG = "ShizukuMonitor"
    private const val REQ_CODE = 10086

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var pollJob: Job? = null
    @Volatile private var running = false

    private val lastSavedHash = AtomicLong(Long.MIN_VALUE)

    fun init(context: Context) {
        LogUtils.d(TAG, "init: binderReady=${safePing()}")
        Shizuku.addBinderReceivedListener {
            LogUtils.d(TAG, "BINDER_RECEIVED")
            if (hasPermission()) start(context)
        }
        Shizuku.addBinderDeadListener {
            LogUtils.d(TAG, "BINDER_DEAD")
            stop()
            // Binder 恢复后会在 init 的监听里再次 start
        }
        Shizuku.addRequestPermissionResultListener { _, result ->
            val granted = result == android.content.pm.PackageManager.PERMISSION_GRANTED
            LogUtils.d(TAG, "PERM_RESULT=$granted")
            if (granted) start(context)
        }
    }

    private fun safePing(): Boolean = try { Shizuku.pingBinder() } catch (_: Throwable) { false }
    private fun isAvailable(): Boolean = safePing()
    fun hasPermission(): Boolean = try {
        Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) { false }

    fun ensurePermission(context: Context, onResult: (Boolean) -> Unit) {
        if (hasPermission()) { onResult(true); return }
        val req = { Shizuku.requestPermission(REQ_CODE) }
        if (isAvailable()) {
            req()
        } else {
            Shizuku.addBinderReceivedListener(object : Shizuku.OnBinderReceivedListener {
                override fun onBinderReceived() {
                    Shizuku.removeBinderReceivedListener(this)
                    req()
                }
            })
        }
        // 简单给个延时回调
        CoroutineScope(Dispatchers.Main).launch {
            delay(1000)
            onResult(hasPermission())
        }
    }

    fun start(context: Context) {
        val avail = isAvailable()
        val perm = hasPermission()
        LogUtils.d(TAG, "START: avail=$avail perm=$perm running=$running")
        if (!avail || !perm) return
        if (running) return
        running = true
        startPolling()
    }

    fun stop() {
        LogUtils.d(TAG, "STOP")
        pollJob?.cancel()
        pollJob = null
        running = false
    }

    // 剪贴板广播回调时触发一小段突发读取，抢时间窗口
    fun onPrimaryClipChanged() {
        if (!running) return
        scope.launch {
            repeat(6) {
                if (tryReadOnce()) return@launch
                delay(120)
            }
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                tryReadOnce()
                delay(500)
            }
        }
    }

    private suspend fun tryReadOnce(): Boolean {
        // 仅使用“命令兜底”（shell 身份），不再做应用进程反射
        val text = readClipboardViaShellCmd()
        if (!text.isNullOrEmpty()) {
            val h = text.hashCode().toLong()
            if (h != lastSavedHash.get()) {
                lastSavedHash.set(h)
                val id = ClipboardApplication.instance.repository.insertItem(text)
                LogUtils.clipboard("FALLBACK_CMD", text)
                LogUtils.d(TAG, "SAVE_OK id=$id")
            }
            return true
        }
        return false
    }

    // ============== Shell 命令实现（通过 Shizuku 以 shell UID 执行） ==============

    private fun readClipboardViaShellCmd(timeoutMs: Long = 1500): String? {
        if (!isAvailable() || !hasPermission()) return null

        // 优先 /system/bin/cmd 路径；再退回 PATH 的 cmd
        val candidates = listOf(
            arrayOf("/system/bin/cmd", "clipboard", "get"),
            arrayOf("cmd", "clipboard", "get")
        )

        for (argv in candidates) {
            val out = execShell(argv, timeoutMs)
            val parsed = parseCmdClipboardOutput(out)
            if (!parsed.isNullOrEmpty()) return parsed
            if (out != null && out.contains("No primary clip", true)) return null
            if (out != null && out.contains("empty", true) && out.contains("clip", true)) return null
        }
        return null
    }

    // 通过 Shizuku 启动 shell 子进程并读取 stdout（带超时）
    private fun execShell(argv: Array<String>, timeoutMs: Long): String? {
        return try {
            val proc = Shizuku.newProcess(argv, null, null)
            // 为兼容 API 24，不用 waitFor(timeout)。用“available+超时”策略读取。
            val out = StringBuilder()
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            val errReader = BufferedReader(InputStreamReader(proc.errorStream))
            val start = SystemClock.elapsedRealtime()

            while (SystemClock.elapsedRealtime() - start < timeoutMs) {
                // 读取标准输出
                while (reader.ready()) {
                    out.appendLine(reader.readLine())
                }
                // 读取错误输出（避免缓冲阻塞）
                while (errReader.ready()) {
                    errReader.readLine() // 丢弃错误输出内容
                }
                // 简单认为进程已结束且没有更多输出
                if (!reader.ready() && !errReader.ready()) {
                    // 再检查 50ms，确保尾部输出 flush 完成
                    Thread.sleep(50)
                    if (!reader.ready() && !errReader.ready()) break
                }
                Thread.sleep(20)
            }
            try { proc.destroy() } catch (_: Throwable) { }
            out.toString().trim().ifEmpty { null }
        } catch (t: Throwable) {
            LogUtils.e(TAG, "execShell failed: ${argv.joinToString(" ")}", t)
            null
        }
    }

    // 解析 “cmd clipboard get” 的输出，尽可能提取文本内容（支持多 item）
    private fun parseCmdClipboardOutput(raw: String?): String? {
        if (raw.isNullOrEmpty()) return null
        val txt = raw.trim()
        if (txt.isEmpty()) return null
        // 常见空/无的提示
        if (txt.contains("No primary clip", true)) return null
        if (txt.contains("Primary clip is empty", true)) return null
        if (txt.startsWith("usage:", true)) return null
        if (txt.startsWith("Error:", true)) return null

        // 常见格式：ClipData { text="..." } / Item { text="..." } / Text: '...'
        val items = mutableListOf<String>()

        // 1) 匹配 Text: '...'
        Regex("""(?i)Text:\s*'(.+?)'""")
            .findAll(txt)
            .forEach { m -> items.add(m.groupValues.getOrNull(1)?.trim().orEmpty()) }

        // 2) 匹配 text="..."/text='...'
        Regex("""(?i)text\s*=\s*(['"])(.*?)\1""")
            .findAll(txt)
            .forEach { m -> items.add(m.groupValues.getOrNull(2)?.trim().orEmpty()) }

        // 3) 若包含 ClipData 但未命中以上，尽力截取 text= 后到下一个逗号/花括号
        if (items.isEmpty() && txt.contains("ClipData")) {
            Regex("""(?i)text\s*=\s*([^,}]+)""")
                .findAll(txt)
                .forEach { m -> items.add(m.groupValues.getOrNull(1)?.trim()?.trim('"', '\'').orEmpty()) }
        }

        // 4) 都没命中，直接用整段输出（有些 ROM 直接输出纯文本）
        if (items.isEmpty() && !txt.contains("ClipData", true)) {
            val cleaned = txt.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("usage:", true) && !it.startsWith("error", true) }
                .joinToString("\n")
                .trim()
            if (cleaned.isNotEmpty()) items.add(cleaned)
        }

        // 汇总清洗
        val merged = items
            .map { it.replace("\r", "").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .trim()

        return merged.ifEmpty { null }
    }

    fun isRunning(): Boolean = running
}