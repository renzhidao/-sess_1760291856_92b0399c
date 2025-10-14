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
 * 后台“无感知”剪贴板读取（最终兜底方案），移除对 Shizuku.newProcess 的直接调用（该 API 在当前依赖版本是 private），
 * 改为通过反射调用以保持在 shell UID 下执行命令的能力，从而修复编译错误。
 *
 * 核心点：
 * - 不依赖 bindUserService（部分 ROM 跨应用绑定受限）
 * - 不在应用进程反射 IClipboard（避免 checkPackage 拦截）
 * - 以 shell 身份执行系统命令：
 *      1) /system/bin/cmd clipboard get（Android 10+ 常见）
 *      2) 回退到 PATH 的 cmd clipboard get
 * - 解析命令输出，合并多 item
 * - 无 UI、无打扰
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

    // ============== Shell 命令实现（通过 Shizuku 以 shell UID 执行；用反射调用 newProcess 以避免编译错误） ==============

    private fun readClipboardViaShellCmd(timeoutMs: Long = 1500): String? {
        if (!isAvailable() || !hasPermission()) return null

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

    private fun execShell(argv: Array<String>, timeoutMs: Long): String? {
        return try {
            val proc = newProcessCompat(argv) ?: return null
            val out = StringBuilder()
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            val errReader = BufferedReader(InputStreamReader(proc.errorStream))
            val start = SystemClock.elapsedRealtime()

            while (SystemClock.elapsedRealtime() - start < timeoutMs) {
                while (reader.ready()) out.appendLine(reader.readLine())
                while (errReader.ready()) errReader.readLine() // 丢弃错误输出，避免阻塞
                if (!reader.ready() && !errReader.ready()) {
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

    // 通过反射调用 Shizuku.newProcess，适配当前依赖版本中该 API 为 private 的情况
    private fun newProcessCompat(argv: Array<String>): Process? {
        return try {
            val clazz = Class.forName("rikka.shizuku.Shizuku")

            // 优先尝试 (String[], String[], String)
            runCatching {
                val m = clazz.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                )
                m.isAccessible = true
                (m.invoke(null, argv, null, null) as? Process)
            }.getOrNull()
                ?: runCatching {
                    // 兼容 (String[], String[], File)
                    val m = clazz.getDeclaredMethod(
                        "newProcess",
                        Array<String>::class.java,
                        Array<String>::class.java,
                        java.io.File::class.java
                    )
                    m.isAccessible = true
                    (m.invoke(null, argv, null, null) as? Process)
                }.getOrNull()
                ?: runCatching {
                    // 退回 (String[])
                    val m = clazz.getDeclaredMethod("newProcess", Array<String>::class.java)
                    m.isAccessible = true
                    (m.invoke(null, argv) as? Process)
                }.getOrNull()
        } catch (t: Throwable) {
            LogUtils.e(TAG, "newProcessCompat failure", t)
            null
        }
    }

    // 解析 “cmd clipboard get” 输出
    private fun parseCmdClipboardOutput(raw: String?): String? {
        if (raw.isNullOrEmpty()) return null
        val txt = raw.trim()
        if (txt.isEmpty()) return null
        if (txt.contains("No primary clip", true)) return null
        if (txt.contains("Primary clip is empty", true)) return null
        if (txt.startsWith("usage:", true)) return null
        if (txt.startsWith("Error:", true)) return null

        val items = mutableListOf<String>()

        // Text: '...'
        Regex("""(?i)Text:\s*'(.+?)'""")
            .findAll(txt)
            .forEach { m -> items.add(m.groupValues.getOrNull(1)?.trim().orEmpty()) }

        // text="..."/text='...'
        Regex("""(?i)text\s*=\s*(['"])(.*?)\1""")
            .findAll(txt)
            .forEach { m -> items.add(m.groupValues.getOrNull(2)?.trim().orEmpty()) }

        // ClipData {... text=...}
        if (items.isEmpty() && txt.contains("ClipData", true)) {
            Regex("""(?i)text\s*=\s*([^,}]+)""")
                .findAll(txt)
                .forEach { m ->
                    items.add(m.groupValues.getOrNull(1)?.trim()?.trim('"', '\'').orEmpty())
                }
        }

        // 纯文本输出（部分 ROM）
        if (items.isEmpty() && !txt.contains("ClipData", true)) {
            val cleaned = txt.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("usage:", true) && !it.startsWith("error", true) }
                .joinToString("\n")
                .trim()
            if (cleaned.isNotEmpty()) items.add(cleaned)
        }

        val merged = items
            .map { it.replace("\r", "").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
            .trim()

        return merged.ifEmpty { null }
    }

    fun isRunning(): Boolean = running
}