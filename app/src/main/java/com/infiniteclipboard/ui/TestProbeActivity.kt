// 文件: app/src/main/java/com/infiniteclipboard/ui/TestProbeActivity.kt
package com.infiniteclipboard.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.infiniteclipboard.R
import com.infiniteclipboard.utils.LogUtils
import kotlinx.coroutines.*
import rikka.shizuku.Shizuku
import rikka.shizuku.SystemServiceHelper
import com.infiniteclipboard.IClipboardUserService
import com.infiniteclipboard.service.ClipboardUserService
import android.content.ComponentName
import android.os.IBinder
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * 逐步测试 Shizuku 方案的探测器：
 * - 一次启动后循环跑“策略序列”，逐个尝试，哪个能读到就记录；
 * - 你保持剪贴板里有内容，我会把每轮结果写到屏幕与日志；
 * - 不弹 UI、不抢焦点；点击“停止”结束探测。
 */
class TestProbeActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var runningJob: Job? = null

    private lateinit var tvResult: TextView
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    private val REQ_CODE = 24680

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_test_probe)

        tvResult = findViewById(R.id.tvResult)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)

        LogUtils.init(this)

        btnStart.setOnClickListener { startProbing() }
        btnStop.setOnClickListener { stopProbing() }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopProbing()
    }

    private fun startProbing() {
        if (runningJob?.isActive == true) return

        appendLine("开始探测：将连续尝试多种 Shizuku/系统路径，每轮都会依次测试全部方法。")
        ensureShizukuPermission { granted ->
            if (!granted) {
                appendLine("Shizuku 未授权，部分策略不可用。仍会尝试非 Shizuku 路径。")
            } else {
                appendLine("Shizuku 已授权，开始全链路探测。")
            }

            runningJob = scope.launch {
                var round = 1
                while (isActive) {
                    runCatching {
                        appendLine("—— 第 $round 轮 ——")
                        val results = mutableListOf<String>()

                        // 1) :shizuku 用户服务 AIDL 直读 IClipboard
                        results += tryStrategy("AIDL_UserService") { tryAidlUserServiceOnce() }

                        // 2) shell 子进程：cmd clipboard get
                        results += tryStrategy("Shell_cmd_clipboard_get") { tryShellCmdOnce() }

                        // 3) shell 子进程：service call clipboard（旧系统兜底，事务号尝试）
                        results += tryStrategy("Shell_service_call_clipboard") { tryServiceCallOnce() }

                        // 4) 应用进程直接 IClipboard 反射（预期多为 SecurityException，用于验证）
                        results += tryStrategy("App_IClipboard_Reflect") { tryIClipboardReflectOnce() }

                        // 5) 标准 ClipboardManager（前台/近期 app 可能成功）
                        results += tryStrategy("ClipboardManager") { tryClipboardManagerOnce() }

                        withContext(Dispatchers.Main) {
                            results.forEach { appendLine(it) }
                            appendLine("")
                        }
                    }.onFailure { e ->
                        appendLine("本轮异常：${e.javaClass.simpleName} ${e.message}")
                    }

                    round++
                    delay(1200) // 每轮间隔，给系统留出喘息和你更换内容的时间
                }
            }
        }
    }

    private fun stopProbing() {
        runningJob?.cancel()
        runningJob = null
        appendLine("探测已停止。")
    }

    // =============== 策略实现 ===============

    private suspend fun tryAidlUserServiceOnce(timeoutMs: Long = 5000): String? = withContext(Dispatchers.IO) {
        if (!safePing() || !hasShizukuPermission()) return@withContext null

        val args = Shizuku.UserServiceArgs(ComponentName(this@TestProbeActivity, ClipboardUserService::class.java))
            .processNameSuffix("shizuku")
            .daemon(true)
            .tag("probe")
            .version(1)

        var svc: IClipboardUserService? = null
        val connection = object : android.content.ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                svc = IClipboardUserService.Stub.asInterface(service)
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                svc = null
            }
        }
        try {
            suspendCoroutine<Unit> { cont ->
                try {
                    Shizuku.bindUserService(args, connection)
                    // 简易等待：轮询 svc 是否非空或超时
                    val start = SystemClock.elapsedRealtime()
                    scope.launch {
                        while (SystemClock.elapsedRealtime() - start < timeoutMs && svc == null) {
                            delay(50)
                        }
                        cont.resume(Unit)
                    }
                } catch (t: Throwable) {
                    cont.resumeWithException(t)
                }
            }
            val text = svc?.getClipboardText()
            return@withContext text
        } catch (_: Throwable) {
            return@withContext null
        } finally {
            try { Shizuku.unbindUserService(args, connection, true) } catch (_: Throwable) {}
        }
    }

    private suspend fun tryShellCmdOnce(): String? = withContext(Dispatchers.IO) {
        if (!safePing() || !hasShizukuPermission()) return@withContext null
        val cmds = listOf(
            arrayOf("/system/bin/cmd", "clipboard", "get"),
            arrayOf("cmd", "clipboard", "get")
        )
        for (argv in cmds) {
            val out = execShell(argv, 1500)
            val parsed = parseCmdOutput(out)
            if (!parsed.isNullOrEmpty()) return@withContext parsed
            if (out != null && out.contains("No primary clip", true)) return@withContext null
        }
        null
    }

    private suspend fun tryServiceCallOnce(): String? = withContext(Dispatchers.IO) {
        if (!safePing() || !hasShizukuPermission()) return@withContext null
        // 事务号在不同版本不同；简单尝试几个“常见/瞎猜”的编号以观测设备行为
        val candidates = listOf("1", "2", "3", "13")
        for (code in candidates) {
            val out = execShell(arrayOf("service", "call", "clipboard", code), 1200)
            val parsed = parseServiceCallOutput(out)
            if (!parsed.isNullOrEmpty()) return@withContext parsed
        }
        null
    }

    private suspend fun tryIClipboardReflectOnce(): String? = withContext(Dispatchers.IO) {
        try {
            val binder = SystemServiceHelper.getSystemService("clipboard") as? IBinder ?: return@withContext null
            val stub = Class.forName("android.content.IClipboard\$Stub")
            val asInterface = stub.getMethod("asInterface", IBinder::class.java)
            val proxy = asInterface.invoke(null, binder)

            val userId = myUserId() ?: 0
            val clazz = proxy.javaClass

            // 优先 (String, Int)
            clazz.methods.firstOrNull {
                it.name.equals("getPrimaryClip", true) &&
                        it.parameterCount == 2 &&
                        it.parameterTypes[0] == String::class.java
            }?.let { m ->
                val clip = invokeClip(m, proxy, packageName, userId)
                val text = coerceClipToText(clip)
                if (!text.isNullOrEmpty()) return@withContext text
            }

            // 次选 ()
            clazz.methods.firstOrNull {
                it.name.equals("getPrimaryClip", true) && it.parameterCount == 0
            }?.let { m ->
                val clip = invokeClip(m, proxy)
                val text = coerceClipToText(clip)
                if (!text.isNullOrEmpty()) return@withContext text
            }
        } catch (_: Throwable) {
            // 多数设备此路径会抛 SecurityException：Package XXX does not belong to YYY
        }
        null
    }

    private suspend fun tryClipboardManagerOnce(): String? = withContext(Dispatchers.IO) {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip ?: return@withContext null
            return@withContext collectTextFromClip(clip)
        } catch (_: Throwable) { null }
    }

    // =============== Shell 执行与解析 ===============

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
                Thread.sleep(15)
            }
            try { proc.destroy() } catch (_: Throwable) {}
            out.toString().trim().ifEmpty { null }
        } catch (_: Throwable) {
            null
        }
    }

    private fun parseCmdOutput(raw: String?): String? {
        if (raw.isNullOrEmpty()) return null
        val txt = raw.trim()
        if (txt.isEmpty()) return null
        if (txt.contains("No primary clip", true)) return null

        val items = mutableListOf<String>()
        Regex("""(?i)Text:\s*'(.+?)'""").findAll(txt).forEach { items += it.groupValues[1] }
        Regex("""(?i)text\s*=\s*(['"])(.*?)\1""").findAll(txt).forEach { items += it.groupValues[2] }
        if (items.isEmpty() && !txt.startsWith("usage:", true) && !txt.startsWith("error", true)) {
            items += txt
        }
        val merged = items.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n").trim()
        return merged.ifEmpty { null }
    }

    private fun parseServiceCallOutput(raw: String?): String? {
        if (raw.isNullOrEmpty()) return null
        val txt = raw.trim()
        // 粗糙解析：提取引号或 0xXXXX 序列，尽力而为
        val quoted = Regex("""['"](.+?)['"]""").find(txt)?.groupValues?.getOrNull(1)
        if (!quoted.isNullOrEmpty()) return quoted
        val hexes = Regex("""0x[0-9a-fA-F]+""").findAll(txt).map { it.value }.toList()
        if (hexes.isNotEmpty()) {
            return try {
                val bytes = hexes.map { it.removePrefix("0x").toInt(16).toByte() }.toByteArray()
                String(bytes)
            } catch (_: Throwable) { null }
        }
        return null
    }

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
        } catch (_: Throwable) {
            null
        }
    }

    // =============== 工具方法 ===============

    private suspend fun tryStrategy(name: String, block: suspend () -> String?): String {
        return try {
            val text = withTimeout(4000L) { block() }
            if (!text.isNullOrEmpty()) {
                LogUtils.clipboard(name, text)
                "$name => 成功：${preview(text)}"
            } else {
                "$name => 失败（空或不可读）"
            }
        } catch (t: Throwable) {
            "$name => 异常：${t.javaClass.simpleName} ${t.message}"
        }
    }

    private fun preview(s: String, len: Int = 60): String =
        s.replace("\n", "\\n").let { if (it.length > len) it.take(len) + "…" else it }

    private fun collectTextFromClip(clip: ClipData): String? {
        if (clip.itemCount <= 0) return null
        val sb = StringBuilder()
        for (i in 0 until clip.itemCount) {
            val item = clip.getItemAt(i)
            val v = item.coerceToText(this)?.toString() ?: item.text?.toString()
            if (!v.isNullOrBlank()) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(v)
            }
        }
        return sb.toString().trim().ifEmpty { null }
    }

    private fun invokeClip(m: Method, target: Any, vararg args: Any?): ClipData? {
        return try { m.invoke(target, *args) as? ClipData } catch (_: Throwable) { null }
    }

    private fun coerceClipToText(clip: ClipData?): String? {
        if (clip == null || clip.itemCount <= 0) return null
        val sb = StringBuilder()
        for (i in 0 until clip.itemCount) {
            val t = clip.getItemAt(i).coerceToText(this)?.toString()
            if (!t.isNullOrBlank()) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(t)
            }
        }
        return sb.toString().trim().ifEmpty { null }
    }

    private fun myUserId(): Int? = try {
        val uh = Class.forName("android.os.UserHandle")
        val m = uh.getMethod("myUserId")
        m.invoke(null) as? Int
    } catch (_: Throwable) { null }

    private fun safePing(): Boolean = try { Shizuku.pingBinder() } catch (_: Throwable) { false }
    private fun hasShizukuPermission(): Boolean = try {
        Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) { false }

    private fun ensureShizukuPermission(onResult: (Boolean) -> Unit) {
        if (hasShizukuPermission()) { onResult(true); return }
        if (safePing()) {
            Shizuku.requestPermission(REQ_CODE)
        } else {
            Shizuku.addBinderReceivedListener(object : Shizuku.OnBinderReceivedListener {
                override fun onBinderReceived() {
                    Shizuku.removeBinderReceivedListener(this)
                    Shizuku.requestPermission(REQ_CODE)
                }
            })
        }
        Shizuku.addRequestPermissionResultListener { _, res ->
            onResult(res == android.content.pm.PackageManager.PERMISSION_GRANTED)
        }
    }

    private fun appendLine(s: String) = runOnUiThread {
        tvResult.append(s + "\n")
    }
}