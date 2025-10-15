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
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.infiniteclipboard.ClipboardApplication
import com.infiniteclipboard.R
import com.infiniteclipboard.ui.ClipboardWindowActivity
import com.infiniteclipboard.ui.TapRecordActivity
import com.infiniteclipboard.utils.ClipboardUtils
import com.infiniteclipboard.utils.LogUtils
import kotlinx.coroutines.*
import kotlin.math.abs
import kotlin.math.min

class ClipboardMonitorService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var clipboardManager: ClipboardManager
    private val repository by lazy { (application as ClipboardApplication).repository }
    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }

    private var lastClipboardContent: String? = null
    private var isPaused: Boolean = false

    // 边缘小条（文本按钮、透明背景、可上下/任意拖动、四周吸附）
    private lateinit var wm: WindowManager
    private var barView: LinearLayout? = null
    private var barLp: WindowManager.LayoutParams? = null

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

        ensureEdgeBar() // 显示边缘小条

        if (prefs.getBoolean("shizuku_enabled", false)) {
            ShizukuClipboardMonitor.start(this)
        }

        LogUtils.d("ClipboardService", "服务已启动，监听器已注册 + 边缘小条已显示")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> togglePause()
            ACTION_CLEAR_ALL -> clearAll()
            ACTION_SHIZUKU_START -> ShizukuClipboardMonitor.start(this)
            ACTION_SHIZUKU_STOP -> ShizukuClipboardMonitor.stop()
        }
        updateNotification()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try { clipboardManager.removePrimaryClipChangedListener(clipboardListener) } catch (_: Throwable) {}
        removeEdgeBar()
        ShizukuClipboardMonitor.stop()
        serviceScope.cancel()
    }

    // 外部应用写入：可选瞬时前台兜底；自家写入（带标签）跳过
    private fun handleClipboardChange() {
        try {
            val clip = clipboardManager.primaryClip
            val label = try { clip?.description?.label?.toString() } catch (_: Throwable) { null }
            if (label == "com.infiniteclipboard") {
                LogUtils.d("ClipboardService", "内部写入，跳过采集")
                return
            }
            val enableShizuku = prefs.getBoolean("shizuku_enabled", false)
            if (!enableShizuku) {
                // 瞬时前台兜底
                val it = Intent(this, TapRecordActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                }
                startActivity(it)
            }
        } catch (e: Exception) {
            LogUtils.e("ClipboardService", "处理剪切板变化失败", e)
        }
    }

    // 测试要求方法
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

    // ========== 边缘小条（文字按钮、透明背景、可拖动、四周吸附） ==========

    private fun dp(v: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics
    ).toInt()

    private fun ensureEdgeBar() {
        if (barView != null) return

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

        // 用 TOP|START + (x,y) 绝对坐标，便于四周吸附
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - dp(56f) // 右侧起始
            y = resources.displayMetrics.heightPixels / 3
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL // 默认在右侧时竖排
            setBackgroundColor(Color.TRANSPARENT) // 透明背景
            val pad = dp(2f)
            setPadding(pad, pad, pad, pad)
        }

        // 文字按钮工厂：透明背景、白字
        fun makeTextBtn(label: String, onClick: () -> Unit): TextView {
            return TextView(this).apply {
                text = label
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(dp(8f), dp(4f), dp(8f), dp(4f))
                setBackgroundColor(Color.TRANSPARENT)
                isClickable = true
                isFocusable = false
                val params = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(6f)
                    marginStart = dp(6f)
                    marginEnd = dp(6f)
                }
                layoutParams = params
                setOnClickListener { onClick() }
            }
        }

        // 三个按钮：剪切/复制/粘贴（通过无障碍操作，不切屏）
        val btnCut = makeTextBtn("剪切") {
            serviceScope.launch(Dispatchers.IO) {
                val text = ClipboardAccessibilityService.captureCut()
                LogUtils.clipboard("边条-剪切", text)
                if (!text.isNullOrEmpty()) {
                    try { repository.insertItem(text) } catch (_: Throwable) {}
                }
            }
        }
        val btnCopy = makeTextBtn("复制") {
            serviceScope.launch(Dispatchers.IO) {
                val text = ClipboardAccessibilityService.captureCopy()
                LogUtils.clipboard("边条-复制", text)
                if (!text.isNullOrEmpty()) {
                    try { repository.insertItem(text) } catch (_: Throwable) {}
                }
            }
        }
        val btnPaste = makeTextBtn("粘贴") {
            serviceScope.launch(Dispatchers.IO) {
                // 不强制使用历史，默认粘贴系统当前内容（null → performPaste 内自行取剪贴板）
                ClipboardAccessibilityService.performPaste(null)
                LogUtils.d("ClipboardService", "边条-粘贴已触发")
            }
        }

        container.addView(btnCut)
        container.addView(btnCopy)
        container.addView(btnPaste)

        // 拖动 + 四周吸附
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        var downTime = 0L

        container.setOnTouchListener { v, e ->
            val screenW = resources.displayMetrics.widthPixels
            val screenH = resources.displayMetrics.heightPixels
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = lp.x
                    startY = lp.y
                    downTime = SystemClock.uptimeMillis()
                    dragging = false
                    false // 不拦截，让子控件还能点
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt()
                    val dy = (e.rawY - downY).toInt()
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) dragging = true
                    if (dragging) {
                        lp.x = (startX + dx).coerceIn(0, screenW - v.width)
                        lp.y = (startY + dy).coerceIn(0, screenH - v.height)
                        try { wm.updateViewLayout(v, lp) } catch (_: Throwable) {}
                        true
                    } else false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        // 计算与四边距离，吸附到最近边
                        val distLeft = lp.x
                        val distRight = screenW - (lp.x + v.width)
                        val distTop = lp.y
                        val distBottom = screenH - (lp.y + v.height)
                        val minDist = min(min(distLeft, distRight), min(distTop, distBottom))
                        when (minDist) {
                            distLeft -> { lp.x = 0;   setBarOrientationForEdge(container, edge = Edge.LEFT) }
                            distRight -> { lp.x = screenW - v.width; setBarOrientationForEdge(container, edge = Edge.RIGHT) }
                            distTop -> { lp.y = 0;    setBarOrientationForEdge(container, edge = Edge.TOP) }
                            else -> { lp.y = screenH - v.height; setBarOrientationForEdge(container, edge = Edge.BOTTOM) }
                        }
                        try { wm.updateViewLayout(v, lp) } catch (_: Throwable) {}
                        true
                    } else {
                        // 认为是点击穿透，交由子控件处理
                        false
                    }
                }
                else -> false
            }
        }

        try {
            wm.addView(container, lp)
            barView = container
            barLp = lp
        } catch (t: Throwable) {
            LogUtils.e("ClipboardService", "添加边缘小条失败", t)
        }
    }

    private fun setBarOrientationForEdge(container: LinearLayout, edge: Edge) {
        // 左/右：竖排；上/下：横排
        container.orientation = when (edge) {
            Edge.LEFT, Edge.RIGHT -> LinearLayout.VERTICAL
            Edge.TOP, Edge.BOTTOM -> LinearLayout.HORIZONTAL
        }
        // 按“剪切/复制/粘贴”的顺序保持不变；仅改变排列方向
    }

    private fun removeEdgeBar() {
        val v = barView ?: return
        try { wm.removeViewImmediate(v) } catch (_: Throwable) {}
        barView = null
        barLp = null
    }

    // ========== 通知 ==========

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
        val openIntent = Intent(this, ClipboardWindowActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleIntent = Intent(this, ClipboardMonitorService::class.java).apply { action = ACTION_TOGGLE }
        val togglePendingIntent = PendingIntent.getService(
            this, 1, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val clearIntent = Intent(this, ClipboardMonitorService::class.java).apply { action = ACTION_CLEAR_ALL }
        val clearPendingIntent = PendingIntent.getService(
            this, 2, clearIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val toggleTitle = if (isPaused) getString(R.string.notification_action_resume) else getString(R.string.notification_action_pause)
        val toggleIcon = if (isPaused) R.drawable.ic_play else R.drawable.ic_pause
        val contentText = if (isPaused) getString(R.string.notification_paused) else getString(R.string.notification_content)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(toggleIcon, toggleTitle, togglePendingIntent)
            .addAction(R.drawable.ic_clear_all, getString(R.string.notification_action_clear_all), clearPendingIntent)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, createNotification())
    }

    companion object {
        private const val CHANNEL_ID = "clipboard_monitor_channel"
        private const val NOTIFICATION_ID = 1001

        private const val ACTION_TOGGLE = "com.infiniteclipboard.action.TOGGLE"
        private const val ACTION_CLEAR_ALL = "com.infiniteclipboard.action.CLEAR_ALL"
        const val ACTION_SHIZUKU_START = "com.infiniteclipboard.action.SHIZUKU_START"
        const val ACTION_SHIZUKU_STOP = "com.infiniteclipboard.action.SHIZUKU_STOP"

        fun start(context: Context) {
            val intent = Intent(context, ClipboardMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, ClipboardMonitorService::class.java)
            context.stopService(intent)
        }
    }

    private enum class Edge { LEFT, RIGHT, TOP, BOTTOM }
}