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
import android.view.*
import android.view.ViewConfiguration
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import androidx.core.view.ViewCompat
import com.infiniteclipboard.ClipboardApplication
import com.infiniteclipboard.R
import com.infiniteclipboard.ui.TapRecordActivity
import com.infiniteclipboard.utils.LogUtils
import kotlinx.coroutines.*

class ClipboardMonitorService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var clipboardManager: ClipboardManager
    private val repository by lazy { (application as ClipboardApplication).repository }
    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }

    private var lastClipboardContent: String? = null
    private var isPaused: Boolean = false

    private lateinit var wm: WindowManager
    private var floatBtn: View? = null
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

        if (prefs.getBoolean("edge_bar_enabled", false)) {
            ensureEdgeBar()
        }

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
        serviceScope.cancel()
    }

    private fun handleClipboardChange() {
        try {
            val clip = clipboardManager.primaryClip
            val label = try { clip?.description?.label?.toString() } catch (_: Throwable) { null }
            if (label == "com.infiniteclipboard") {
                LogUtils.d("ClipboardService", "内部写入，跳过采集")
                return
            }
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
            } catch (e: Exception) { e.printStackTrace() }
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

    private fun ensureEdgeBar() {
        if (floatBtn != null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            LogUtils.d("ClipboardService", "缺少悬浮窗权限，无法创建悬浮按钮")
            return
        }

        val sizeDp = 52
        val sizePx = (resources.displayMetrics.density * sizeDp).toInt()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            WindowManager.LayoutParams.TYPE_PHONE

        val flags = (WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)

        val params = WindowManager.LayoutParams(
            sizePx, sizePx, type, flags, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            x = (resources.displayMetrics.widthPixels * 0.05f).toInt()
            y = 0
        }

        val btn = ImageView(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x66000000)
                setStroke((resources.displayMetrics.density * 1.5f).toInt(), Color.WHITE)
            }
            setImageResource(R.drawable.ic_clipboard)
            setPadding(sizePx / 5, sizePx / 5, sizePx / 5, sizePx / 5)
            ViewCompat.setElevation(this, 12f)

            setOnClickListener {
                try {
                    val it = Intent(this@ClipboardMonitorService, TapRecordActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    }
                    startActivity(it)
                    LogUtils.d("ClipboardService", "悬浮按钮点击，已拉起前台读取")
                } catch (t: Throwable) {
                    LogUtils.e("ClipboardService", "拉起前台读取失败", t)
                }
            }
        }

        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var downTime = 0L

        btn.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    startX = params.x
                    startY = params.y
                    downTime = SystemClock.uptimeMillis()
                    true
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
                    val totalDx = kotlin.math.abs(ev.rawX - downX)
                    val totalDy = kotlin.math.abs(ev.rawY - downY)
                    val duration = SystemClock.uptimeMillis() - downTime
                    val isClick = (totalDx <= touchSlop && totalDy <= touchSlop && duration < 300)
                    if (isClick) v.performClick()
                    true
                }
                else -> false
            }
        }

        try {
            wm.addView(btn, params)
            floatBtn = btn
            floatParams = params
            LogUtils.d("ClipboardService", "悬浮按钮已创建")
        } catch (t: Throwable) {
            LogUtils.e("ClipboardService", "添加悬浮按钮失败", t)
        }
    }

    private fun removeEdgeBar() {
        val v = floatBtn ?: return
        try { wm.removeView(v) } catch (_: Throwable) { }
        floatBtn = null
        floatParams = null
        LogUtils.d("ClipboardService", "悬浮按钮已移除")
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