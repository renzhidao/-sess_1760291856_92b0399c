// 文件: app/src/main/java/com/infiniteclipboard/service/ClipboardMonitorService.kt
package com.infiniteclipboard.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.app.NotificationCompat
import androidx.core.view.ViewCompat
import com.infiniteclipboard.ClipboardApplication
import com.infiniteclipboard.R
import com.infiniteclipboard.ui.TapRecordActivity
import com.infiniteclipboard.utils.ClipboardUtils
import com.infiniteclipboard.utils.LogUtils
import kotlinx.coroutines.*
import kotlin.math.abs

class ClipboardMonitorService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var clipboardManager: ClipboardManager
    private val repository by lazy { (application as ClipboardApplication).repository }
    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }

    private var lastClipboardContent: String? = null
    private var isPaused: Boolean = false

    // 悬浮容器（同时放“复制/剪切/粘贴”三连按钮 + “静默记录”按钮）
    private lateinit var wm: WindowManager
    private var floatContainer: View? = null
    private var floatParams: WindowManager.LayoutParams? = null

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (!isPaused) handleClipboardChange()
    }

    override fun onCreate() {
        super.onCreate()
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        clipboardManager.addPrimaryClipChangedListener(clipboardListener)

        val enableShizuku = prefs.getBoolean("shizuku_enabled", false)
        if (enableShizuku) ShizukuClipboardMonitor.start(this)

        if (prefs.getBoolean("edge_bar_enabled", false)) ensureEdgeBar()

        LogUtils.d("ClipboardService", "服务已启动，监听器已注册")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> togglePause()
            ACTION_CLEAR_ALL -> clearAll()
            ACTION_SHIZUKU_START -> ShizukuClipboardMonitor.start(this)
            ACTION_SHIZUKU_STOP -> ShizukuClipboardMonitor.stop()
            ACTION_EDGE_BAR_ENABLE -> {
                prefs.edit().putBoolean("edge_bar_enabled", true).apply()
                ensureEdgeBar()
            }
            ACTION_EDGE_BAR_DISABLE -> {
                prefs.edit().putBoolean("edge_bar_enabled", false).apply()
                removeEdgeBar()
            }
        }
        updateNotification()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try { clipboardManager.removePrimaryClipChangedListener(clipboardListener) } catch (_: Throwable) { }
        removeEdgeBar()
        ShizukuClipboardMonitor.stop()
        serviceScope.cancel()
    }

    private fun handleClipboardChange() {
        try {
            val clip = clipboardManager.primaryClip
            val label = try { clip?.description?.label?.toString() } catch (_: Throwable) { null }
            if (label == "com.infiniteclipboard") return
            val shizukuEnabled = prefs.getBoolean("shizuku_enabled", false)
            if (shizukuEnabled) {
                ShizukuClipboardMonitor.onPrimaryClipChanged()
                LogUtils.d("ClipboardService", "Shizuku已运行，已触发后台突发读取")
            } else {
                LogUtils.d("ClipboardService", "Shizuku未运行；保持静默")
            }
        } catch (e: Exception) {
            LogUtils.e("ClipboardService", "处理剪切板变化失败", e)
        }
    }

    // 供测试使用的方法名
    private fun saveClipboardContent(content: String) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val id = repository.insertItem(content)
                LogUtils.d("ClipboardService", "保存成功，ID: $id")
            } catch (e: Exception) {
                LogUtils.e("ClipboardService", "保存失败", e)
            }
        }
    }

    private fun clearAll() {
        serviceScope.launch(Dispatchers.IO) {
            try {
                repository.deleteAll()
                lastClipboardContent = null
            } catch (_: Exception) { }
        }
    }

    private fun togglePause() { isPaused = !isPaused }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val toggleIntent = Intent(this, ClipboardMonitorService::class.java).apply { action = ACTION_TOGGLE }
        val togglePendingIntent = PendingIntent.getService(
            this, 1, toggleIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val clearIntent = Intent(this, ClipboardMonitorService::class.java).apply { action = ACTION_CLEAR_ALL }
        val clearPendingIntent = PendingIntent.getService(
            this, 2, clearIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentPendingIntent = togglePendingIntent

        val toggleTitle = if (isPaused) getString(R.string.notification_action_resume) else getString(R.string.notification_action_pause)
        val toggleIcon = if (isPaused) R.drawable.ic_play else R.drawable.ic_pause
        val contentText = if (isPaused) getString(R.string.notification_paused) else getString(R.string.notification_content)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentPendingIntent)
            .addAction(toggleIcon, toggleTitle, togglePendingIntent)
            .addAction(R.drawable.ic_clear_all, getString(R.string.notification_action_clear_all), clearPendingIntent)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, createNotification())
    }

    // ============ 悬浮容器：恢复“复制/剪切/粘贴”三连 + 保留“静默记录” ============
    private fun ensureEdgeBar() {
        if (floatContainer != null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            LogUtils.d("ClipboardService", "缺少悬浮窗权限，无法创建悬浮按钮")
            return
        }

        val dp = resources.displayMetrics.density
        val size = (dp * 48).toInt()
        val spacing = (dp * 6).toInt()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE

        val flags = (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            size,
            type,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            x = (resources.displayMetrics.widthPixels * 0.05f).toInt()
            y = 0
        }

        fun roundBg(color: Int) = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke((dp * 1.5f).toInt(), Color.WHITE)
        }

        fun makeBtn(iconRes: Int, desc: String, onClick: () -> Unit): ImageView {
            return ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply { rightMargin = spacing }
                background = roundBg(0x66000000)
                setImageResource(iconRes)
                contentDescription = desc
                setPadding(size / 5, size / 5, size / 5, size / 5)
                ViewCompat.setElevation(this, 12f)
                setOnClickListener { onClick() }
            }
        }

        // 1) 复制：通过无障碍对焦点进行复制；有选区复制选区，无选区全选复制；入库
        val btnCopy = makeBtn(R.drawable.ic_copy, "复制") {
            val text = ClipboardAccessibilityService.captureCopy()
            LogUtils.clipboard("悬浮复制", text)
            if (!text.isNullOrEmpty()) serviceScope.launch(Dispatchers.IO) {
                try { repository.insertItem(text) } catch (_: Throwable) {}
            }
        }

        // 2) 剪切：对焦点进行剪切；失败时用 SET_TEXT 兜底；入库
        val btnCut = makeBtn(R.drawable.ic_delete, "剪切") { // 没有 ic_cut，用 ic_delete 代替
            val text = ClipboardAccessibilityService.captureCut()
            LogUtils.clipboard("悬浮剪切", text)
            if (!text.isNullOrEmpty()) serviceScope.launch(Dispatchers.IO) {
                try { repository.insertItem(text) } catch (_: Throwable) {}
            }
        }

        // 3) 粘贴：优先系统 ACTION_PASTE；失败用 SET_TEXT；使用当前剪贴板内容
        val btnPaste = makeBtn(R.drawable.ic_paste, "粘贴") {
            val ok = ClipboardAccessibilityService.performPaste(null)
            LogUtils.d("ClipboardService", "悬浮粘贴执行结果=$ok")
        }

        // 4) 静默记录：不切屏，从系统剪贴板读取入库
        val btnSilent = makeBtn(R.drawable.ic_clipboard, "静默记录") {
            recordClipboardInService()
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).rightMargin = 0
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btnCopy)
            addView(btnCut)
            addView(btnPaste)
            addView(btnSilent)
        }

        // 拖拽不拦截点击
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var downTime = 0L

        container.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    startX = params.x
                    startY = params.y
                    downTime = SystemClock.uptimeMillis()
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - downX).toInt()
                    val dy = (ev.rawY - downY).toInt()
                    params.x = startX + dx
                    params.y = startY + dy
                    try { wm.updateViewLayout(v, params) } catch (_: Throwable) {}
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val totalDx = abs(ev.rawX - downX)
                    val totalDy = abs(ev.rawY - downY)
                    val duration = SystemClock.uptimeMillis() - downTime
                    (totalDx > touchSlop || totalDy > touchSlop || duration >= 300)
                }
                else -> false
            }
        }

        try {
            wm.addView(container, params)
            floatContainer = container
            floatParams = params
            LogUtils.d("ClipboardService", "悬浮容器（三连+静默）已创建")
        } catch (t: Throwable) {
            LogUtils.e("ClipboardService", "添加悬浮容器失败", t)
        }
    }

    private fun recordClipboardInService() {
        serviceScope.launch(Dispatchers.IO) {
            val text = ClipboardUtils.getClipboardTextWithRetries(
                this@ClipboardMonitorService, attempts = 6, intervalMs = 150L
            )
            LogUtils.clipboard("悬浮静默", text)
            if (!text.isNullOrEmpty()) {
                try { repository.insertItem(text) } catch (e: Throwable) {
                    LogUtils.e("ClipboardService", "静默入库失败", e)
                }
            } else {
                LogUtils.d("ClipboardService", "静默读取失败：内容为空")
            }
        }
    }

    private fun removeEdgeBar() {
        val v = floatContainer ?: return
        try { wm.removeView(v) } catch (_: Throwable) {}
        floatContainer = null
        floatParams = null
        LogUtils.d("ClipboardService", "悬浮容器已移除")
    }

    companion object {
        private const val CHANNEL_ID = "clipboard_monitor_channel"
        private const val NOTIFICATION_ID = 1001

        private const val ACTION_TOGGLE = "com.infiniteclipboard.action.TOGGLE"
        private const val ACTION_CLEAR_ALL = "com.infiniteclipboard.action.CLEAR_ALL"
        const val ACTION_SHIZUKU_START = "com.infiniteclipboard.action.SHIZUKU_START"
        const val ACTION_SHIZUKU_STOP = "com.infiniteclipboard.action.SHIZUKU_STOP"
        const val ACTION_EDGE_BAR_ENABLE = "com.infiniteclipboard.action.EDGE_BAR_ENABLE"
        const val ACTION_EDGE_BAR_DISABLE = "com.infiniteclipboard.action.EDGE_BAR_DISABLE"

        fun start(context: Context) {
            val intent = Intent(context, ClipboardMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ClipboardMonitorService::class.java)
            context.stopService(intent)
        }
    }
}