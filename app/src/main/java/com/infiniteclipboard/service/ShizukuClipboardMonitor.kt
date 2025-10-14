// 文件: app/src/main/java/com/infiniteclipboard/service/ShizukuClipboardMonitor.kt
package com.infiniteclipboard.service

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.infiniteclipboard.ClipboardApplication
import com.infiniteclipboard.IClipboardUserService
import com.infiniteclipboard.utils.LogUtils
import kotlinx.coroutines.*
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 仅限 Shizuku 的“接近完美”实现：
 * - 优先：绑定 :shizuku 用户服务（shell UID）直读 IClipboard
 * - 兜底：以 shell 身份反射调用 Shizuku.newProcess（反射）执行 "cmd clipboard get"
 * - 自恢复：Binder Received/Dead 监听、指数回退重连；轮询+突发读取，无 UI 打扰
 */
object ShizukuClipboardMonitor {

    private const val TAG = "ShizukuMonitor"
    private const val REQ_CODE = 10086
    private const val BIND_TIMEOUT_MS = 8000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var pollJob: Job? = null
    @Volatile private var running = false
    @Volatile private var binding = false
    @Volatile private var userService: IClipboardUserService? = null

    private lateinit var userServiceArgs: Shizuku.UserServiceArgs
    private val retryAttempt = AtomicInteger(0)
    private val lastSavedHash = AtomicLong(Long.MIN_VALUE)

    private val connection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            userService = IClipboardUserService.Stub.asInterface(service)
            binding = false
            retryAttempt.set(0)
            cancelBindTimeout()
            LogUtils.d(TAG, "CONNECTED: ${name?.flattenToShortString()}")
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            userService = null
            LogUtils.d(TAG, "DISCONNECTED: ${name?.flattenToShortString()}")
            scheduleReconnect("SERVICE_DISCONNECTED")
        }
    }

    fun init(context: Context) {
        LogUtils.d(TAG, "init: binderReady=${safePing()}")
        Shizuku.addBinderReceivedListener {
            LogUtils.d(TAG, "BINDER_RECEIVED")
            if (hasPermission()) start(context)
        }
        Shizuku.addBinderDeadListener {
            LogUtils.d(TAG, "BINDER_DEAD")
            // 不 stop，交给自恢复逻辑重连
            scheduleReconnect("BINDER_DEAD", context)
        }
        Shizuku.addRequestPermissionResultListener { _, result ->
            val ok = result == android.content.pm.PackageManager.PERMISSION_GRANTED
            LogUtils.d(TAG, "PERM_RESULT=$ok")
            if (ok) start(context)
        }
    }

    private fun safePing(): Boolean = try { Shizuku.pingBinder() } catch (_: Throwable) { false }
    private fun isAvailable(): Boolean = safePing()
    fun hasPermission(): Boolean = try {
        Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) { false }
    fun isRunning(): Boolean = running

    fun ensurePermission(context: Context, onResult: (Boolean) -> Unit) {
        if (hasPermission()) { onResult(true); return }
        val req = { Shizuku.requestPermission(REQ_CODE) }
        if (isAvailable()) mainHandler.post { req() } else {
            Shizuku.addBinderReceivedListener(object : Shizuku.OnBinderReceivedListener {
                override fun onBinderReceived() {
                    Shizuku.removeBinderReceivedListener(this)
                    mainHandler.post { req() }
                }
            })
        }
        mainHandler.postDelayed({ onResult(hasPermission()) }, 1000)
    }

    fun start(context: Context) {
        val avail = isAvailable()
        val perm = hasPermission()
        LogUtils.d(TAG, "START: avail=$avail perm=$perm running=$running binding=$binding")
        if (!avail || !perm) return

        // 确保轮询常驻（即便绑定未成功也先启动，兜底命令可工作）
        if (!running) {
            running = true
            startPolling()
        }

        // 准备并尝试绑定用户服务（不阻塞轮询）
        if (!::userServiceArgs.isInitialized) {
            userServiceArgs = Shizuku.UserServiceArgs(ComponentName(context, ClipboardUserService::class.java))
                .processNameSuffix("shizuku")
                .daemon(true)
                .tag("clipboard")
                .version(1)
        }
        if (!binding && userService == null) {
            try {
                binding = true
                Shizuku.bindUserService(userServiceArgs, connection)
                scheduleBindTimeout(context)
                LogUtils.d(TAG, "BIND_START args={suffix=shizuku, daemon=true, tag=clipboard, version=1}")
            } catch (t: Throwable) {
                binding = false
                LogUtils.e(TAG, "BIND_EXCEPTION", t)
                scheduleReconnect("BIND_EXCEPTION", context)
            }
        }
    }

    fun stop() {
        LogUtils.d(TAG, "STOP")
        pollJob?.cancel()
        pollJob = null
        running = false
        binding = false
        cancelBindTimeout()
        try {
            userService?.destroy()
            if (::userServiceArgs.isInitialized) Shizuku.unbindUserService(userServiceArgs, connection, true)
        } catch (_: Throwable) {}
        userService = null
    }

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
        // 1) 优先：远程用户服务（shell UID）直读
        userService?.let { svc ->
            try {
                val text = svc.getClipboardText()
                if (handleTextIfAny(text, "ShizukuUserService")) return true
            } catch (t: Throwable) {
                LogUtils.e(TAG, "READ_FAIL_VIA_USERSERVICE", t)
            }
        }
        // 2) 兜底：以 shell 身份执行 cmd clipboard get（反射 newProcess）
        val text = readClipboardViaShellCmd()
        return handleTextIfAny(text, "FALLBACK_CMD")
    }

    private suspend fun handleTextIfAny(text: String?, source: String): Boolean {
        if (!text.isNullOrEmpty()) {
            val h = text.hashCode().toLong()
            if (h != lastSavedHash.get()) {
                lastSavedHash.set(h)
                val id = ClipboardApplication.instance.repository.insertItem(text)
                LogUtils.clipboard(source, text)
                LogUtils.d(TAG, "SAVE_OK id=$id")
            }
            return true
        }
        return false
    }

    @Volatile private var bindTimeoutRunnable: Runnable? = null
    private fun scheduleBindTimeout(context: Context, timeoutMs: Long = BIND_TIMEOUT_MS) {
        cancelBindTimeout()
        bindTimeoutRunnable = Runnable {
            if (userService == null && binding) {
                binding = false
                LogUtils.d(TAG, "BIND_TIMEOUT after ${timeoutMs}ms")
                scheduleReconnect("BIND_TIMEOUT", context)
            }
        }
        mainHandler.postDelayed(bindTimeoutRunnable!!, timeoutMs)
    }
    private fun cancelBindTimeout() {
        bindTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        bindTimeoutRunnable = null
    }

    private fun scheduleReconnect(reason: String, context: Context? = null) {
        val backoff = computeBackoff()
        LogUtils.d(TAG, "RECONNECT reason=$reason in ${backoff}ms")
        scope.launch {
            delay(backoff)
            withContext(Dispatchers.Main) {
                context?.let { start(it) }
            }
        }
    }
    private fun computeBackoff(): Long {
        val a = retryAttempt.getAndIncrement()
        val shift = if (a < 4) a else 4
        return (700L shl shift).coerceAtMost(10000L)
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
                while (errReader.ready()) errReader.readLine()
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

    // 通过反射调用 Shizuku.newProcess（避免编译期访问 private API 失败）
    private fun newProcessCompat(argv: Array<String>): Process? {
        return try {
            val clazz = Class.forName("rikka.shizuku.Shizuku")
            runCatching {
                val m = clazz.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                )
                m.isAccessible = true
                m.invoke(null, argv, null, null) as? Process
            }.getOrNull() ?: runCatching {
                val m = clazz.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    java.io.File::class.java
                )
                m.isAccessible = true
                m.invoke(null, argv, null, null) as? Process
            }.getOrNull() ?: runCatching {
                val m = clazz.getDeclaredMethod("newProcess", Array<String>::class.java)
                m.isAccessible = true
                m.invoke(null, argv) as? Process
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
        Regex("""(?i)Text:\s*'(.+?)'""").findAll(txt).forEach { m ->
            items.add(m.groupValues.getOrNull(1)?.trim().orEmpty())
        }
        Regex("""(?i)text\s*=\s*(['"])(.*?)\1""").findAll(txt).forEach { m ->
            items.add(m.groupValues.getOrNull(2)?.trim().orEmpty())
        }
        if (items.isEmpty() && txt.contains("ClipData", true)) {
            Regex("""(?i)text\s*=\s*([^,}]+)""").findAll(txt).forEach { m ->
                items.add(m.groupValues.getOrNull(1)?.trim()?.trim('"', '\'').orEmpty())
            }
        }
        if (items.isEmpty() && !txt.contains("ClipData", true)) {
            val cleaned = txt.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("usage:", true) && !it.startsWith("error", true) }
                .joinToString("\n")
                .trim()
            if (cleaned.isNotEmpty()) items.add(cleaned)
        }
        val merged = items.map { it.replace("\r", "").trim() }.filter { it.isNotEmpty() }.joinToString("\n").trim()
        return merged.ifEmpty { null }
    }
}