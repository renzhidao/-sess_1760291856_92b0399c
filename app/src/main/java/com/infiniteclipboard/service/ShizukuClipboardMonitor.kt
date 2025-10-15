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
Shizuku 后台无感知读取 + 轮询主路径（保留）
按你的要求：后台探测“通知栏”取消；startProbeChain 改为 no-op。
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
        userService?.let { svc ->
            try {
                val text = svc.getClipboardText()
                if (handleTextIfAny(text, "ShizukuUserService")) return true
            } catch (t: Throwable) {
                LogUtils.e(TAG, "READ_FAIL_VIA_USERSERVICE", t)
            }
        }
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

    // ============== 背景探测链：按需禁用（no-op） ==============
    fun startProbeChain(context: Context, perStepTimeoutMs: Long = 2000L) {
        // 用户要求取消此通知与探测：这里直接 no-op
        LogUtils.d(TAG, "startProbeChain() ignored as per user request")
    }

    // 仍需 Room 工具方法（用于非探测路径）
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

    // 探测通知彻底取消（no-op）
    private fun sendProbeNotification(context: Context, lines: List<String>) {
        // no-op
        LogUtils.d(TAG, "sendProbeNotification() suppressed")
    }

    // 确保通知通道（保留，但探测不会用到）
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