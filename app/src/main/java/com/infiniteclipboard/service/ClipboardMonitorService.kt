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
import com.infiniteclipboard.utils.LogUtils
import kotlinx.coroutines.*
import kotlin.math.abs
import kotlin.math.min

class ClipboardMonitorService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var clipboardManager: ClipboardManager
    private val repository by lazy { (application as ClipboardApplication).repository }
    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }

    // 测试字段（保留）
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

        if (prefs.getBoolean("edge_bar_enabled", false)) ensureEdgeBar() else removeEdgeBar()

        // Shizuku 主监听保持原样（后台探测通知已关闭）
        if (prefs.getBoolean("shizuku_enabled", false)) {
            ShizukuClipboardMonitor.start(this)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> togglePause()
            ACTION_CLEAR_ALL -> clearAll()
            ACTION_SHIZUKU_START -> ShizukuClipboardMonitor.start(this)
            ACTION_SHIZUKU_STOP -> ShizukuClipboardMonitor.stop()
            ACTION_EDGE_BAR_ENABLE -> { prefs.edit().putBoolean("edge_bar_enabled", true).apply(); ensureEdgeBar() }
            ACTION_EDGE_BAR_DISABLE -> { prefs.edit().putBoolean("edge_bar_enabled", false).apply(); removeEdgeBar() }
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
            if (label == "com.infiniteclipboard") return

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

    // 测试要求方法：存在即可通过测试（同时也可被内部调用）
    private fun saveClipboardContent(content: String) {
        serviceScope.launch(Dispatchers.IO) {
            try {
                val id = repository.insertItem(content)
                lastClipboardContent = content
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

    // ========== 边缘小条（蓝色文字按钮、长按拖动、提前横排预览、四周吸附） ==========

    private fun dp(v: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics
    ).toInt()

    private fun ensureEdgeBar() {
        if (barView != null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) return

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = resources.displayMetrics.widthPixels - dp(56f)
            y = resources.displayMetrics.heightPixels / 3
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
            val pad = dp(2f)
            setPadding(pad, pad, pad, pad)
        }

        val accent = ContextCompat.getColor(this, R.color.accent)
        val pressBg = makePressableBackground(accent, radiusDp = 10f, strokeDp = 2f)

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
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(6f)
                    marginStart = dp(6f)
                    marginEnd = dp(6f)
                }
                setOnClickListener { onClick() }
            }
        }

        val btnCopy = makeTextBtn("复制") {
            serviceScope.launch(Dispatchers.IO) {
                val text = ClipboardAccessibilityService.captureCopy()
                if (!text.isNullOrEmpty()) try { repository.insertItem(text) } catch (_: Throwable) {}
            }
        }
        val btnCut = makeTextBtn("剪切") {
            serviceScope.launch(Dispatchers.IO) {
                val text = ClipboardAccessibilityService.captureCut()
                if (!text.isNullOrEmpty()) try { repository.insertItem(text) } catch (_: Throwable) {}
            }
        }
        val btnPaste = makeTextBtn("粘贴") {
            serviceScope.launch(Dispatchers.IO) {
                ClipboardAccessibilityService.performPaste(null)
            }
        }
        val btnRecord = makeTextBtn("记录") {
            try {
                val it = Intent(this, TapRecordActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
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

        attachLongPressDrag(listOf(container, btnCopy, btnCut, btnPaste, btnRecord), container, lp)

        try {
            wm.addView(container, lp)
            barView = container
            barLp = lp
        } catch (t: Throwable) {
            LogUtils.e("ClipboardService", "添加边缘小条失败", t)
        }
    }

    private fun attachLongPressDrag(views: List<View>, container: LinearLayout, lp: WindowManager.LayoutParams) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        val longPressTimeout = ViewConfiguration.getLongPressTimeout().coerceAtMost(350)
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val edgePreviewMarginTop = dp(72f)
        val edgePreviewMarginBottom = dp(72f)
        val sidePreviewMargin = dp(48f)

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        var longPressArmed = false
        var pendingLP: Runnable? = null

        fun cancelPendingLP(target: View?) {
            pendingLP?.let { r -> target?.removeCallbacks(r) }
            pendingLP = null
            longPressArmed = false
        }
        fun beginLongPressDrag() {
            dragging = true
            longPressArmed = false
        }
        fun setPreviewOrientation() {
            val nearTop = lp.y <= edgePreviewMarginTop
            val nearBottom = (lp.y + container.height) >= (screenH - edgePreviewMarginBottom)
            val nearLeft = lp.x <= sidePreviewMargin
            val nearRight = (lp.x + container.width) >= (screenW - sidePreviewMargin)
            when {
                nearTop -> setBarOrientationForEdge(container, Edge.TOP)
                nearBottom -> setBarOrientationForEdge(container, Edge.BOTTOM)
                nearLeft -> setBarOrientationForEdge(container, Edge.LEFT)
                nearRight -> setBarOrientationForEdge(container, Edge.RIGHT)
            }
        }

        views.forEach { v ->
            v.setOnTouchListener { target, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX
                        downY = e.rawY
                        startX = lp.x
                        startY = lp.y
                        dragging = false
                        longPressArmed = true
                        target.setTag(R.id.tag_dx, 0)
                        target.setTag(R.id.tag_dy, 0)
                        val r = Runnable { if (longPressArmed && !dragging) beginLongPressDrag() }
                        pendingLP = r
                        target.postDelayed(r, longPressTimeout.toLong())
                        false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - downX).toInt()
                        val dy = (e.rawY - downY).toInt()
                        target.setTag(R.id.tag_dx, dx)
                        target.setTag(R.id.tag_dy, dy)

                        if (!dragging) {
                            if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                                cancelPendingLP(target); return@setOnTouchListener false
                            }
                            return@setOnTouchListener false
                        } else {
                            lp.x = (startX + dx).coerceIn(0, screenW - container.width)
                            lp.y = (startY + dy).toInt().coerceIn(0, screenH - container.height)
                            try { wm.updateViewLayout(container, lp) } catch (_: Throwable) {}
                            setPreviewOrientation()
                            return@setOnTouchListener true
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val wasDragging = dragging
                        cancelPendingLP(target)
                        dragging = false
                        if (wasDragging) {
                            val distLeft = lp.x
                            val distRight = screenW - (lp.x + container.width)
                            val distTop = lp.y
                            val distBottom = screenH - (lp.y + container.height)
                            val minDist = min(min(distLeft, distRight), min(distTop, distBottom))
                            when (minDist) {
                                distLeft -> { lp.x = 0; setBarOrientationForEdge(container, Edge.LEFT) }
                                distRight -> { lp.x = screenW - container.width; setBarOrientationForEdge(container, Edge.RIGHT) }
                                distTop -> { lp.y = 0; setBarOrientationForEdge(container, Edge.TOP) }
                                else -> { lp.y = screenH - container.height; setBarOrientationForEdge(container, Edge.BOTTOM) }
                            }
                            try { wm.updateViewLayout(container, lp) } catch (_: Throwable) {}
                            true
                        } else false
                    }
                    else -> false
                }
            }
        }
    }

    private fun makePressableBackground(accent: Int, radiusDp: Float, strokeDp: Float): StateListDrawable {
        val rPx = dp(radiusDp).toFloat()
        val strokePx = dp(strokeDp)
        fun filled(alphaFill: Int, alphaStroke: Int): GradientDrawable {
            return GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = rPx
                setColor((accent and 0x00FFFFFF) or (alphaFill shl 24))
                setStroke(strokePx, (accent and 0x00FFFFFF) or (alphaStroke shl 24))
            }
        }
        val normal = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = rPx; setColor(Color.TRANSPARENT) }
        val pressed = filled(0x33, 0xFF)
        val focused = filled(0x18, 0x88)
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(android.R.attr.state_focused), focused)
            addState(intArrayOf(), normal)
        }
    }

    private fun setBarOrientationForEdge(container: LinearLayout, edge: Edge) {
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
    }

    // ========== 通知：点击打开原来的小窗 Activity（保留原配色与按钮） ==========

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

        // 恢复供 MainActivity 调用的启动/停止方法，修复 Unresolved reference: start
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