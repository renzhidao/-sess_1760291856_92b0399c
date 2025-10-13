// 文件: app/src/main/java/com/infiniteclipboard/service/ShizukuClipboardMonitor.kt
// Shizuku 全局剪贴板监听（shell 权限）：更强日志 + 多用户/输出兼容 + 心跳自检
package com.infiniteclipboard.service

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.infiniteclipboard.ClipboardApplication
import com.infiniteclipboard.utils.LogUtils
import kotlinx.coroutines.*
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicLong

object ShizukuClipboardMonitor {

    private const val TAG = "ShizukuMonitor"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var monitorJob: Job? = null
    @Volatile private var heartbeatJob: Job? = null
    @Volatile private var monitorProc: Process? = null
    @Volatile private var binderReady: Boolean = false

    // 监控行日志的最后时间，用于判断“事件没到”
    private val lastMonitorLineAt = AtomicLong(0L)

    private const val REQ_CODE = 10086

    fun init(context: Context) {
        binderReady = safePing()
        LogUtils.d(TAG, "init: binderReady=$binderReady sdk=${Build.VERSION.SDK_INT}")

        Shizuku.addBinderReceivedListener {
            binderReady = true
            LogUtils.d(TAG, "Binder received")
            val enabled = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean("shizuku_enabled", false)
            if (enabled) start(context)
        }
        Shizuku.addBinderDeadListener {
            binderReady = false
            LogUtils.d(TAG, "Binder dead")
        }
        Shizuku.addRequestPermissionResultListener { _, grantResult ->
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            LogUtils.d(TAG, "permission result: $granted")
            if (granted) start(context)
        }
    }

    private fun safePing(): Boolean = try { Shizuku.pingBinder() } catch (_: Throwable) { false }

    fun isAvailable(): Boolean = binderReady || safePing()

    fun hasPermission(): Boolean {
        return try { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED } catch (_: Throwable) { false }
    }

    // 在主线程发起权限请求；若 Binder 未就绪，将等待 Binder 收到后再请求
    fun ensurePermission(context: Context, onResult: (Boolean) -> Unit) {
        if (hasPermission()) {
            onResult(true); return
        }
        val post = { Shizuku.requestPermission(REQ_CODE) }
        if (isAvailable()) {
            Handler(Looper.getMainLooper()).post { post() }
        } else {
            Shizuku.addBinderReceivedListener(object : Shizuku.OnBinderReceivedListener {
                override fun onBinderReceived() {
                    Shizuku.removeBinderReceivedListener(this)
                    Handler(Looper.getMainLooper()).post { post() }
                }
            })
        }
        Handler(Looper.getMainLooper()).postDelayed({
            onResult(hasPermission())
        }, 1200L)
    }

    fun start(context: Context) {
        val avail = isAvailable()
        val perm = hasPermission()
        LogUtils.d(TAG, "start(): available=$avail permission=$perm jobActive=${monitorJob?.isActive==true}")
        if (!avail || !perm) {
            LogUtils.d(TAG, "不可用或未授权，start 跳过")
            return
        }
        if (monitorJob != null) {
            LogUtils.d(TAG, "已在运行，跳过")
            return
        }

        val appCtx = context.applicationContext
        monitorJob = scope.launch {
            var attempt = 0
            while (isActive) {
                try {
                    // 优先尝试指定 user，再回退
                    val monitorCandidates = listOf(
                        "cmd clipboard monitor --user 0",
                        "cmd clipboard monitor"
                    )
                    var proc: Process? = null
                    var usedCmd: String? = null
                    for (cmdStr in monitorCandidates) {
                        LogUtils.d(TAG, "尝试启动 monitor: $cmdStr")
                        proc = newProcessCompat(arrayOf("sh", "-c", cmdStr))
                        if (proc != null) { usedCmd = cmdStr; break }
                        LogUtils.d(TAG, "newProcess 失败: $cmdStr")
                    }
                    val p = proc ?: throw IllegalStateException("newProcess 返回 null（monitor）")
                    monitorProc = p
                    lastMonitorLineAt.set(System.currentTimeMillis())
                    LogUtils.d(TAG, "monitor 启动成功，cmd=[$usedCmd]")

                    // 并行读取 stdout/stderr 的行，任何一侧有输出都认作“变化线索”
                    val stdoutReader = BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8))
                    val stderrReader = BufferedReader(InputStreamReader(p.errorStream, Charsets.UTF_8))

                    val readLoop = { src: String, br: BufferedReader ->
                        try {
                            while (isActive) {
                                val ln = br.readLine() ?: break
                                if (ln.isBlank()) continue
                                lastMonitorLineAt.set(System.currentTimeMillis())
                                LogUtils.d(TAG, "[$src] 变化线索: ${snippet(ln)}")
                                // 每次线索出现都读取一次内容（多策略）
                                val text = tryGetClipboardTextCandidatesWithLog()
                                if (!text.isNullOrBlank()) {
                                    try {
                                        val repo = (appCtx as ClipboardApplication).repository
                                        val id = repo.insertItem(text)
                                        LogUtils.d(TAG, "入库成功 id=$id, 内容前50: ${snippet(text, 50)}")
                                    } catch (e: Throwable) {
                                        LogUtils.e(TAG, "入库失败", e)
                                    }
                                } else {
                                    LogUtils.d(TAG, "get 返回空（可能无主剪贴板/非文本）")
                                }
                            }
                        } catch (t: Throwable) {
                            LogUtils.e(TAG, "读取 $src 出错", t)
                        }
                    }

                    val j1 = launch { readLoop("stdout", stdoutReader) }
                    val j2 = launch { readLoop("stderr", stderrReader) }

                    // 启动心跳：持续汇报“是否没有任何事件”
                    heartbeatJob?.cancel()
                    heartbeatJob = launch {
                        while (isActive) {
                            delay(2000)
                            val since = System.currentTimeMillis() - lastMonitorLineAt.get()
                            val alive = monitorProc != null
                            LogUtils.d(
                                TAG,
                                "heartbeat: silent=${since}ms alive=$alive binderReady=$binderReady avail=${isAvailable()} perm=${hasPermission()} job=${monitorJob?.isActive==true}"
                            )
                            // 若 monitor 长时间静默，做一次“仅日志”的兜底探测（不入库）
                            if (since > 5000) {
                                val probe = tryGetClipboardTextCandidatesWithLog(logPrefix = "fallback-probe", insert = false)
                                LogUtils.d(TAG, "fallback-probe 结果: ${snippet(probe, 80)}")
                            }
                        }
                    }

                    // 等待读取结束
                    j1.join(); j2.join()
                    LogUtils.d(TAG, "monitor 进程结束（读取循环退出）")
                } catch (e: Throwable) {
                    LogUtils.e(TAG, "monitor 异常", e)
                } finally {
                    try { monitorProc?.destroy() } catch (_: Throwable) { }
                    monitorProc = null
                }
                attempt++
                val backoff = (500L * attempt).coerceAtMost(5000L)
                delay(backoff)
            }
        }
    }

    fun stop() {
        LogUtils.d(TAG, "stop()")
        try { monitorProc?.destroy() } catch (_: Throwable) { }
        monitorProc = null
        monitorJob?.cancel()
        monitorJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        LogUtils.d(TAG, "停止 monitor")
    }

    // 读取剪贴板文本：尝试多种命令与解析，并输出详细日志；可选择是否入库调用场景
    private fun tryGetClipboardTextCandidatesWithLog(
        logPrefix: String = "get",
        insert: Boolean = true // 当前仅用于日志，插入由调用方决定
    ): String? {
        val attempts = listOf(
            "cmd clipboard get --user 0",
            "cmd clipboard get"
        )
        for (cmd in attempts) {
            val raw = runShellOnce(cmd) ?: run {
                LogUtils.d(TAG, "$logPrefix: $cmd 无输出或执行失败")
                continue
            }
            LogUtils.d(TAG, "$logPrefix: $cmd 原始: ${snippet(raw)}")
            val parsed = parseGetOutput(raw)
            LogUtils.d(TAG, "$logPrefix: $cmd 解析: ${snippet(parsed)}")
            if (!parsed.isNullOrBlank()) return parsed
        }
        return null
    }

    // 原 tryGetClipboardText，保留但改为走多策略（向后兼容）
    private fun tryGetClipboardText(): String? {
        return tryGetClipboardTextCandidatesWithLog()
    }

    // 解析 cmd clipboard get 输出为纯文本
    private fun parseGetOutput(out: String?): String? {
        if (out.isNullOrBlank()) return null
        val s = out.trim()
        if (s.equals("null", true)) return null
        if (s.contains("No primary clip", true)) return null
        if (s.contains("Warning:", true)) return null
        // 常见格式：ClipData { text/plain T:xxx }
        val tIdx = s.indexOf(" T:")
        if (tIdx >= 0) {
            val start = tIdx + 3
            // 截到右大括号/末尾
            val end = s.indexOf('}', start).let { if (it >= 0) it else s.length }
            val payload = s.substring(start, end).trim()
            if (payload.isNotEmpty()) return payload
        }
        return s
    }

    // 单次执行 shell 并返回 stdout 全文（UTF-8）
    private fun runShellOnce(cmd: String): String? {
        return try {
            val p = newProcessCompat(arrayOf("sh", "-c", cmd)) ?: return null
            val out = BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8)).use { br ->
                val sb = StringBuilder()
                var line: String?
                while (true) {
                    line = br.readLine() ?: break
                    sb.append(line).append('\n')
                }
                sb.toString().trim()
            }
            // 读一下错误流，避免阻塞（只截断日志）
            try {
                val err = BufferedReader(InputStreamReader(p.errorStream, Charsets.UTF_8)).use { it.readLine() }
                if (!err.isNullOrBlank()) LogUtils.d(TAG, "shell err: ${snippet(err)}")
            } catch (_: Throwable) { }
            try { p.destroy() } catch (_: Throwable) { }
            out
        } catch (e: Throwable) {
            LogUtils.e(TAG, "runShellOnce 失败: $cmd", e)
            null
        }
    }

    // 兼容 API 变动：通过反射调用 Shizuku.newProcess(String[], String[]?, String?)
    private fun newProcessCompat(cmd: Array<String>): Process? {
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            val p = method.invoke(null, cmd, null, null) as? Process
            if (p == null) LogUtils.d(TAG, "newProcess 返回 null, cmd=${cmd.joinToString(" ")}")
            p
        } catch (t: Throwable) {
            LogUtils.e(TAG, "newProcess 反射失败, cmd=${cmd.joinToString(" ")}", t)
            null
        }
    }

    private fun snippet(s: String?, max: Int = 120): String {
        if (s == null) return "null"
        val oneLine = s.replace("\r", "\\r").replace("\n", "\\n")
        return if (oneLine.length <= max) oneLine else oneLine.substring(0, max) + "…"
    }
}