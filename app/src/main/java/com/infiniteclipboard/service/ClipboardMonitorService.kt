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
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
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

    // 悬浮边缘小条
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

        if (prefs.getBoolean("edge_bar_enabled", false)) {
            ensureEdgeBar()
        } else {
            removeEdgeBar()
        }

        if (prefs.getBoolean("shizuku_enabled", false)) {
            ShizukuClipboardMonitor.start(this)
        }

        LogUtils.d("ClipboardService", "服务已启动，监听器已注册 + 边缘小条状态=${prefs.getBoolean("edge_bar_enabled", false)}")
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

    // ========== 边缘小条（蓝色文字按钮、强按压高亮、可拖动、四周吸附） ==========

    private fun dp(v: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics
    ).toInt()

    private fun ensureEdgeBar() {
        if (barView != null) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            LogUtils.d("ClipboardService", "无悬浮窗权限，跳过显示边缘小条")
            return
        }

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

        // 蓝色主题色
        val accent = ContextCompat.getColor(this, R.color.accent)
        val pressBg = makePressableBackground(accent, radiusDp = 10f, strokeDp = 2f)

        // 文字按钮工厂：蓝色、加粗、按下高亮（描边+浅色填充）
        fun makeTextBtn(label: String, onClick: () -> Unit): TextView {
            return TextView(this).apply {
                text = label
                setTextColor(accent)
                typeface = Typeface.DEFAULT_BOLD
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dp(10f), dp(6f), dp(10f), dp(6f))
                background = pressBg
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

        // 三个功能 + 记录：复制 / 剪切 / 粘贴 / 记录（“记录”改为拉起透明Activity，造成前台闪烁后读取）
        val btnCopy = makeTextBtn("复制") {
            serviceScope.launch(Dispatchers.IO) {
                val text = ClipboardAccessibilityService.captureCopy()
                LogUtils.clipboard("边条-复制", text)
                if (!text.isNullOrEmpty()) {
                    try { repository.insertItem(text) } catch (_: Throwable) {}
                }
            }
        }
        val btnCut = makeTextBtn("剪切") {
            serviceScope.launch(Dispatchers.IO) {
                val text = ClipboardAccessibilityService.captureCut()
                LogUtils.clipboard("边条-剪切", text)
                if (!text.isNullOrEmpty()) {
                    try { repository.insertItem(text) } catch (_: Throwable) {}
                }
            }
        }
        val btnPaste = makeTextBtn("粘贴") {
            serviceScope.launch(Dispatchers.IO) {
                ClipboardAccessibilityService.performPaste(null)
                LogUtils.d("ClipboardService", "边条-粘贴已触发")
            }
        }
        val btnRecord = makeTextBtn("记录") {
            try {
                // 核心：拉起“完全透明、无动画”的 TapRecordActivity 瞬时前台读取
                val it = Intent(this, TapRecordActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_MULTIPLE_TASK or // 独立任务，避免带起主界面
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                    )
                }
                startActivity(it)
            } catch (t: Throwable) {
                LogUtils.e("ClipboardService", "拉起瞬时前台读取失败", t)
            }
        }

        container.addView(btnCopy)
        container.addView(btnCut)
        container.addView(btnPaste)
        container.addView(btnRecord)

        // 拖动 + 四周吸附
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false

        container.setOnTouchListener { v, e ->
            val screenW = resources.displayMetrics.widthPixels
            val screenH = resources.displayMetrics.heightPixels
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = lp.x
                    startY = lp.y
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
                        false // 点击事件交由子控件
                    }
                }
                else -> false
            }
        }

        try {
            wm.addView(container, lp)
            barView = container
            barLp = lp
            LogUtils.d("ClipboardService", "边缘小条已显示")
        } catch (t: Throwable) {
            LogUtils.e("ClipboardService", "添加边缘小条失败", t)
        }
    }

    // 更高亮的按压背景：按下时有描边+浅色填充；正常透明
    private fun makePressableBackground(accent: Int, radiusDp: Float, strokeDp: Float): StateListDrawable {
        val rPx = dp(radiusDp).toFloat()
        val strokePx = dp(strokeDp)

        fun filled(alphaFill: Int, alphaStroke: Int): GradientDrawable {
            return GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = rPx
                setColor((accent and 0x00FFFFFF) or (alphaFill shl 24)) // 半透明填充
                setStroke(strokePx, (accent and 0x00FFFFFF) or (alphaStroke shl 24)) // 更显眼描边
            }
        }
        val normal = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = rPx
            setColor(Color.TRANSPARENT)
        }
        val pressed = filled(alphaFill = 0x33, alphaStroke = 0xFF)  // 明显高亮
        val focused = filled(alphaFill = 0x18, alphaStroke = 0x88)  // 轻微高亮

        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(), normal)
        }
    }

    private fun setBarOrientationForEdge(container: LinearLayout, edge: Edge) {
        // 左/右：竖排；上/下：横排
        container.orientation = when (edge) {
            Edge.LEFT, Edge.RIGHT -> LinearLayout.VERTICAL
            Edge.TOP, Edge.BOTTOM -> LinearLayout.HORIZONTAL
        }
    }

    private fun removeEdgeBar() {
        val v = barView ?: return
        try { wm.removeViewImmediate(v) } catch (_: Throwable) {}
        barView = null
        barLp = null
        LogUtils.d("ClipboardService", "边缘小条已移除")
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
        const val ACTION_EDGE_BAR_ENABLE = "com.infiniteclipboard.action.EDGE_BAR_ENABLE"
        const val ACTION_EDGE_BAR_DISABLE = "com.infiniteclipboard.action.EDGE_BAR_DISABLE"

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