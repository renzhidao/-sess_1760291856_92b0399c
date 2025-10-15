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

    // 悬浮容器（同时放两个按钮：前台读取 + 静默读取）
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
            .setContentIntent(contentPendingIntent) // 测试要求：通知点击事件
            .addAction(toggleIcon, toggleTitle, togglePendingIntent)
            .addAction(R.drawable.ic_clear_all, getString(R.string.notification_action_clear_all), clearPendingIntent)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, createNotification())
    }

    // ============ 悬浮容器：同时包含“前台读取（恢复旧按钮）”与“静默读取（当前按钮）” ============
    private fun ensureEdgeBar() {
        if (floatContainer != null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            LogUtils.d("ClipboardService", "缺少悬浮窗权限，无法创建悬浮按钮")
            return
        }

        val dp = resources.displayMetrics.density
        val sizePx = (dp * 48).toInt()
        val spacing = (dp * 6).toInt()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE

        val flags = (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            sizePx,
            type,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            x = (resources.displayMetrics.widthPixels * 0.05f).toInt()
            y = 0
        }

        // 圆形背景构造器
        fun roundBg(color: Int): GradientDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            setStroke((dp * 1.5f).toInt(), Color.WHITE)
        }

        // 子按钮工厂
        fun makeBtn(iconRes: Int, desc: String, onClick: () -> Unit): ImageView {
            return ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).apply {
                    rightMargin = spacing
                }
                background = roundBg(0x66000000)
                setImageResource(iconRes)
                contentDescription = desc
                setPadding(sizePx / 5, sizePx / 5, sizePx / 5, sizePx / 5)
                ViewCompat.setElevation(this, 12f)
                setOnClickListener { onClick() }
            }
        }

        // 两个功能按钮：
        // 1) 恢复旧按钮：拉起透明前台 TapRecordActivity（独立任务，不把主页带前台）
        val btnFront = makeBtn(R.drawable.ic_clipboard, "前台读取") {
            try {
                val it = Intent(this, TapRecordActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                    )
                }
                startActivity(it)
                LogUtils.d("ClipboardService", "前台读取按钮：已拉起 TapRecordActivity（独立任务）")
            } catch (t: Throwable) {
                LogUtils.e("ClipboardService", "拉起前台读取失败", t)
            }
        }

        // 2) 当前按钮：不切屏，服务内静默读取入库
        val btnSilent = makeBtn(R.drawable.ic_copy, "静默读取") {
            recordClipboardInService()
        }

        // 横向容器
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(btnFront)
            addView(btnSilent.apply {
                // 去掉最后一个的右边距
                (layoutParams as LinearLayout.LayoutParams).rightMargin = 0
            })
        }

        // 拖拽逻辑（作用于容器，不拦截点击）
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
                    false // 不拦截，子 View 还能收到点击
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - downX).toInt()
                    val dy = (ev.rawY - downY).toInt()
                    params.x = startX + dx
                    params.y = startY + dy
                    try { wm.updateViewLayout(v, params) } catch (_: Throwable) { }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val totalDx = abs(ev.rawX - downX)
                    val totalDy = abs(ev.rawY - downY)
                    val duration = SystemClock.uptimeMillis() - downTime
                    // 小位移不拦截，交给子 View 处理点击
                    (totalDx > touchSlop || totalDy > touchSlop || duration >= 300)
                }
                else -> false
            }
        }

        try {
            wm.addView(container, params)
            floatContainer = container
            floatParams = params
            LogUtils.d("ClipboardService", "悬浮容器（前台+静默）已创建")
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
                try {
                    val id = repository.insertItem(text)
                    LogUtils.d("ClipboardService", "静默入库成功 id=$id")
                } catch (e: Throwable) {
                    LogUtils.e("ClipboardService", "静默入库失败", e)
                }
            } else {
                LogUtils.d("ClipboardService", "静默读取失败：内容为空")
            }
        }
    }

    private fun removeEdgeBar() {
        val v = floatContainer ?: return
        try { wm.removeView(v) } catch (_: Throwable) { }
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