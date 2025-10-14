// 文件: app/src/main/java/com/infiniteclipboard/service/ShizukuClipboardMonitor.kt
package com.infiniteclipboard.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.infiniteclipboard.ClipboardApplication
import com.infiniteclipboard.R
import com.infiniteclipboard.utils.LogUtils
import kotlinx.coroutines.*
import rikka.shizuku.Shizuku
import rikka.shizuku.SystemServiceHelper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicLong

/**
 * Shizuku 后台无感知读取 + 背景探测链
 * - 常驻轮询（shell 命令主路径）不变
 * - 新增：startProbeChain(context) 在应用退到后台后启动“多方案顺序测试”，每方案 2 秒，结束后发通知汇总
 */
object ShizukuClipboardMonitor {

    private const val TAG = "ShizukuMonitor"
    private const val REQ_CODE = 10086
    private const val BIND_TIMEOUT_MS = 8000L
    private const val PROBE_NOTIFY_ID = 12002
    private const val CHANNEL_ID = "clipboard_monitor_channel"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var pollJob: Job? = null
    @Volatile private var running = false
    @Volatile private var binding = false
    @Volatile private var userService: com.infiniteclipboard.IClipboardUserService? = null
    @Volatile private var probeJob: Job? = null

    private val lastSavedHash = AtomicLong(Long.MIN_VALUE)

    // --- 现有初始化（保留） ---
    fun init(context: Context) {
        LogUtils.d(TAG, "init: binderReady=${safePing()}")
        Shizuku.addBinderReceivedListener { if (hasPermission()) start(context) }
        Shizuku.addBinderDeadListener { stop() }
        Shizuku.addRequestPermissionResultListener { _, result ->
            if (result == android.content.pm.PackageManager.PERMISSION_GRANTED) start(context)
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
        if (isAvailable()) req() else {
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

    // --- 常驻轮询主路径：shell 命令 ---
    fun start(context: Context) {
        val avail = isAvailable()
        val perm = hasPermission()
        LogUtils.d(TAG, "START: avail=$avail perm=$perm running=$running binding=$binding")
        if (!avail || !perm) return

        if (!running) {
            running = true
            startPolling()
        }

        // 绑定用户服务仅作增益（可选），不依赖它
        if (!binding && userService == null) {
            try {
                binding = true
                val args = Shizuku.UserServiceArgs(
                    android.content.ComponentName(context, ClipboardUserService::class.java)
                ).processNameSuffix("shizuku").daemon(true).tag("clipboard").version(1)
                Shizuku.bindUserService(args, connection)
                scheduleBindTimeout(context, BIND_TIMEOUT_MS)
                LogUtils.d(TAG, "BIND_START args={suffix=shizuku, daemon=true, tag=clipboard, version=1}")
            } catch (t: Throwable) {
                binding = false
                LogUtils.e(TAG, "BIND_EXCEPTION", t)
            }
        }
    }

    fun stop() {
        LogUtils.d(TAG, "STOP")
        pollJob?.cancel()
        pollJob = null
        running = false
        binding = false
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
        // 优先：远端用户服务（若已连上）
        userService?.let { svc ->
            try {
                val text = svc.getClipboardText()
                if (handleTextIfAny(text, "ShizukuUserService")) return true
            } catch (t: Throwable) {
                LogUtils.e(TAG, "READ_FAIL_VIA_USERSERVICE", t)
            }
        }
        // 兜底：shell 命令
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

    private val connection = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
            userService = com.infiniteclipboard.IClipboardUserService.Stub.asInterface(service)
            binding = false
            LogUtils.d(TAG, "CONNECTED: ${name?.flattenToShortString()}")
        }
        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            userService = null
            LogUtils.d(TAG, "DISCONNECTED: ${name?.flattenToShortString()}")
        }
    }

    @Volatile private var bindTimeoutRunnable: Runnable? = null
    private fun scheduleBindTimeout(context: Context, timeoutMs: Long) {
        cancelBindTimeout()
        bindTimeoutRunnable = Runnable {
            if (userService == null && binding) {
                binding = false
                LogUtils.d(TAG, "BIND_TIMEOUT after ${timeoutMs}ms")
            }
        }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(bindTimeoutRunnable!!, timeoutMs)
    }
    private fun cancelBindTimeout() {
        bindTimeoutRunnable?.let { android.os.Handler(android.os.Looper.getMainLooper()).removeCallbacks(it) }
        bindTimeoutRunnable = null
    }

    // ============== Shell 命令（通过 Shizuku 以 shell UID 执行；newProcess 反射调用） ==============

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

    private fun newProcessCompat(argv: Array<String>): Process? {
        return try {
            val clazz = Class.forName("rikka.shizuku.Shizuku")
            runCatching {
                val m = clazz.getDeclaredMethod(
                    "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
                )
                m.isAccessible = true
                m.invoke(null, argv, null, null) as? Process
            }.getOrNull() ?: runCatching {
                val m = clazz.getDeclaredMethod(
                    "newProcess", Array<String>::class.java, Array<String>::class.java, java.io.File::class.java
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

    private fun parseCmdClipboardOutput(raw: String?): String? {
        if (raw.isNullOrEmpty()) return null
        val txt = raw.trim()
        if (txt.isEmpty()) return null
        if (txt.contains("No primary clip", true)) return null
        if (txt.startsWith("usage:", true)) return null
        if (txt.startsWith("Error:", true)) return null

        val items = mutableListOf<String>()
        Regex("""(?i)Text:\s*'(.+?)'""").findAll(txt).forEach { items.add(it.groupValues[1]) }
        Regex("""(?i)text\s*=\s*(['"])(.*?)\1""").findAll(txt).forEach { items.add(it.groupValues[2]) }
        if (items.isEmpty() && !txt.contains("ClipData", true)) items.add(txt)

        val merged = items.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n").trim()
        return merged.ifEmpty { null }
    }

    // ============== 背景探测链：每方案 2 秒，全部跑完后发通知 ==============

    fun startProbeChain(context: Context, perStepTimeoutMs: Long = 2000L) {
        if (probeJob?.isActive == true) return
        val app = context.applicationContext
        probeJob = scope.launch {
            val results = mutableListOf<String>()

            fun add(name: String, text: String?, err: Throwable? = null) {
                when {
                    !text.isNullOrEmpty() -> {
                        LogUtils.clipboard(name, text)
                        results += "$name => 成功：${text.take(60).replace("\n", "\\n")}"
                    }
                    err != null -> results += "$name => 异常：${err.javaClass.simpleName} ${err.message}"
                    else -> results += "$name => 失败（空或不可读）"
                }
            }

            // 顺序 1：AIDL 用户服务直读（如已连上）
            runCatching {
                withTimeout(perStepTimeoutMs) { tryAidlOnce() }
            }.onSuccess { add("AIDL_UserService", it) }
             .onFailure { add("AIDL_UserService", null, it) }

            // 顺序 2：shell cmd clipboard get
            runCatching {
                withTimeout(perStepTimeoutMs) { readClipboardViaShellCmd() }
            }.onSuccess { add("Shell_cmd_get", it) }
             .onFailure { add("Shell_cmd_get", null, it) }

            // 顺序 3：service call clipboard（旧式事务号粗试）
            runCatching {
                withTimeout(perStepTimeoutMs) { readViaServiceCall() }
            }.onSuccess { add("Shell_service_call", it) }
             .onFailure { add("Shell_service_call", null, it) }

            // 顺序 4：ClipboardManager（可能命中前台/近期来源）
            runCatching {
                withTimeout(perStepTimeoutMs) { readViaClipboardManager(app) }
            }.onSuccess { add("ClipboardManager", it) }
             .onFailure { add("ClipboardManager", null, it) }

            sendProbeNotification(app, results)
        }
    }

    private suspend fun tryAidlOnce(): String? = withContext(Dispatchers.IO) {
        try { userService?.getClipboardText() } catch (_: Throwable) { null }
    }

    private fun readViaServiceCall(): String? {
        if (!isAvailable() || !hasPermission()) return null
        val candidates = listOf("1", "2", "3", "13")
        for (code in candidates) {
            val out = execShell(arrayOf("service", "call", "clipboard", code), 1200)
            val parsed = parseServiceCallOutput(out)
            if (!parsed.isNullOrEmpty()) return parsed
        }
        return null
    }

    private fun parseServiceCallOutput(raw: String?): String? {
        if (raw.isNullOrEmpty()) return null
        val txt = raw.trim()
        Regex("""['"](.+?)['"]""").find(txt)?.groupValues?.getOrNull(1)?.let { if (it.isNotEmpty()) return it }
        val hexes = Regex("""0x[0-9a-fA-F]+""").findAll(txt).map { it.value }.toList()
        if (hexes.isNotEmpty()) {
            return try {
                val bytes = hexes.map { it.removePrefix("0x").toInt(16).toByte() }.toByteArray()
                val s = String(bytes).trim()
                if (s.isNotEmpty()) s else null
            } catch (_: Throwable) { null }
        }
        return null
    }

    private fun readViaClipboardManager(ctx: Context): String? {
        return try {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip ?: return null
            clipDataToText(ctx, clip)
        } catch (_: Throwable) { null }
    }

    private fun clipDataToText(ctx: Context, clip: ClipData?): String? {
        if (clip == null || clip.itemCount <= 0) return null
        val sb = StringBuilder()
        for (i in 0 until clip.itemCount) {
            val item = clip.getItemAt(i)
            val piece = try { item.coerceToText(ctx)?.toString() } catch (_: Throwable) { item.text?.toString() }
            val clean = piece?.trim()
            if (!clean.isNullOrEmpty()) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(clean)
            }
        }
        return sb.toString().trim().ifEmpty { null }
    }

    private fun sendProbeNotification(context: Context, lines: List<String>) {
        ensureNotificationChannel(context)
        val content = if (lines.isEmpty()) "无结果" else lines.joinToString("\n")
        val first = lines.firstOrNull() ?: "探测完成"
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("后台探测完成")
            .setContentText(first)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .build()
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(PROBE_NOTIFY_ID, notif)
    }

    private fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java)
            val exist = nm.getNotificationChannel(CHANNEL_ID)
            if (exist == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = context.getString(R.string.notification_channel_desc) }
                nm.createNotificationChannel(ch)
            }
        }
    }
}