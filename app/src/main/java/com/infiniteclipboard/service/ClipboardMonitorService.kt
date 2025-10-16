// 文件: app/src/main/java/com/infiniteclipboard/service/ClipboardMonitorService.kt
package com.infiniteclipboard.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.infiniteclipboard.ClipboardApplication
import com.infiniteclipboard.R
import com.infiniteclipboard.ui.ClipboardAdapter
import com.infiniteclipboard.ui.TapRecordActivity
import com.infiniteclipboard.utils.LogUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs
import kotlin.math.min

class ClipboardMonitorService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var clipboardManager: ClipboardManager
    private val repository by lazy { (application as ClipboardApplication).repository }
    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }

    private var lastClipboardContent: String? = null
    private var isPaused: Boolean = false

    private lateinit var wm: WindowManager
    private var barView: LinearLayout? = null
    private var barLp: WindowManager.LayoutParams? = null

    private var floatingListView: View? = null
    private var floatingListLp: WindowManager.LayoutParams? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isBarVisible = true
    private var hideBarRunnable: Runnable? = null
    private var lastTapTime = 0L
    private var tapCount = 0
    private val TAP_WINDOW_MS = 10_000L
    private val AUTO_HIDE_DELAY_MS = 10_000L

    private val screenTapReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_SCREEN_TAPPED) {
                onScreenTap()
            }
        }
    }

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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenTapReceiver, IntentFilter(ACTION_SCREEN_TAPPED), RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenTapReceiver, IntentFilter(ACTION_SCREEN_TAPPED))
        }

        if (prefs.getBoolean("edge_bar_enabled", false)) ensureEdgeBar() else removeEdgeBar()

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
            ACTION_SHOW_FLOATING_LIST -> toggleFloatingList()
        }
        updateNotification()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try { clipboardManager.removePrimaryClipChangedListener(clipboardListener) } catch (_: Throwable) {}
        try { unregisterReceiver(screenTapReceiver) } catch (_: Throwable) {}
        removeEdgeBar()
        removeFloatingList()
        ShizukuClipboardMonitor.stop()
        serviceScope.cancel()
        mainHandler.removeCallbacksAndMessages(null)
    }

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

    private fun dp(v: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics
    ).toInt()

    private fun toggleFloatingList() {
        if (floatingListView != null) {
            removeFloatingList()
        } else {
            showFloatingList()
        }
    }

    private fun showFloatingList() {
        if (floatingListView != null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) return

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

        val dm = resources.displayMetrics
        val w = (dm.widthPixels * 0.8f).toInt()
        val h = (dm.heightPixels * 0.7f).toInt()

        val lp = WindowManager.LayoutParams(
            w, h, type,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        val container = LayoutInflater.from(this).inflate(
            R.layout.floating_clipboard_list,
            null
        )

        val recyclerView = container.findViewById<RecyclerView>(R.id.recyclerView)
        val btnClose = container.findViewById<View>(R.id.btnClose)

        val adapter = ClipboardAdapter(
            onCopyClick = { item ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("clipboard", item.content))
            },
            onDeleteClick = { item ->
                serviceScope.launch { repository.deleteItem(item) }
            },
            onItemClick = { item ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("clipboard", item.content))
                removeFloatingList()
            },
            onShareClick = { item ->
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, item.content)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(Intent.createChooser(intent, "分享").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        )

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        serviceScope.launch {
            repository.allItems.collectLatest { items ->
                adapter.submitList(items)
            }
        }

        btnClose.setOnClickListener { removeFloatingList() }
        container.setOnClickListener { removeFloatingList() }
        recyclerView.setOnClickListener { }

        try {
            wm.addView(container, lp)
            floatingListView = container
            floatingListLp = lp
        } catch (t: Throwable) {
            LogUtils.e("ClipboardService", "显示悬浮列表失败", t)
        }
    }

    private fun removeFloatingList() {
        val v = floatingListView ?: return
        try { wm.removeViewImmediate(v) } catch (_: Throwable) {}
        floatingListView = null
        floatingListLp = null
    }

    private fun onScreenTap() {
        val now = System.currentTimeMillis()
        
        if (now - lastTapTime > TAP_WINDOW_MS) {
            tapCount = 1
            lastTapTime = now
        } else {
            tapCount++
            if (tapCount >= 2 && !isBarVisible) {
                showEdgeBar()
                tapCount = 0
            }
        }
        
        if (isBarVisible) {
            scheduleAutoHide()
        }
    }

    private fun showEdgeBar() {
        val bar = barView ?: return
        if (isBarVisible) return
        
        bar.visibility = View.VISIBLE
        bar.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(200)
            .start()
        isBarVisible = true
        scheduleAutoHide()
    }

    private fun hideEdgeBar() {
        val bar = barView ?: return
        if (!isBarVisible) return
        
        bar.animate()
            .alpha(0.3f)
            .scaleX(0.3f)
            .scaleY(0.3f)
            .setDuration(200)
            .withEndAction {
                bar.visibility = View.VISIBLE
            }
            .start()
        isBarVisible = false
        cancelAutoHide()
    }

    private fun scheduleAutoHide() {
        cancelAutoHide()
        hideBarRunnable = Runnable { hideEdgeBar() }
        mainHandler.postDelayed(hideBarRunnable!!, AUTO_HIDE_DELAY_MS)
    }

    private fun cancelAutoHide() {
        hideBarRunnable?.let { mainHandler.removeCallbacks(it) }
        hideBarRunnable = null
    }

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
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
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
            clipToPadding = true
            clipChildren = true
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
                setOnClickListener {
                    onClick()
                    scheduleAutoHide()
                }
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

        container.addView(btnCopy)
        container.addView(btnCut)
        container.addView(btnPaste)

        attachLongPressDragFast(container, lp)

        container.setOnClickListener {
            if (!isBarVisible) {
                showEdgeBar()
            }
        }

        try {
            wm.addView(container, lp)
            barView = container
            barLp = lp
            
            scheduleAutoHide()
        } catch (t: Throwable) {
            LogUtils.e("ClipboardService", "添加边缘小条失败", t)
        }
    }

    private fun attachLongPressDragFast(container: LinearLayout, lp: WindowManager.LayoutParams) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        val longPressTimeout = (ViewConfiguration.getLongPressTimeout() * 0.6f).toInt().coerceAtMost(250)
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        var longPressArmed = false
        var pendingLP: Runnable? = null
        var lastUpdateMs = 0L

        fun cancelPendingLP() {
            pendingLP?.let { container.removeCallbacks(it) }
            pendingLP = null
            longPressArmed = false
        }

        fun beginDrag() {
            dragging = true
            longPressArmed = false
            container.alpha = 0.95f
            cancelAutoHide()
        }

        container.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = lp.x
                    startY = lp.y
                    dragging = false
                    longPressArmed = true

                    val r = Runnable { if (longPressArmed && !dragging) beginDrag() }
                    pendingLP = r
                    container.postDelayed(r, longPressTimeout.toLong())
                    
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt()
                    val dy = (e.rawY - downY).toInt()
                    if (!dragging) {
                        if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                            cancelPendingLP()
                            return@setOnTouchListener false
                        }
                        return@setOnTouchListener false
                    } else {
                        val now = System.nanoTime() / 1_000_000
                        if (now - lastUpdateMs < 16) return@setOnTouchListener true
                        lastUpdateMs = now

                        val newX = (startX + dx).coerceIn(0, screenW - container.width)
                        val newY = (startY + dy).coerceIn(0, screenH - container.height)
                        if (newX != lp.x || newY != lp.y) {
                            lp.x = newX
                            lp.y = newY
                            try { wm.updateViewLayout(container, lp) } catch (_: Throwable) {}
                        }
                        true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val wasDragging = dragging
                    cancelPendingLP()
                    dragging = false
                    container.alpha = 1f
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
                        
                        scheduleAutoHide()
                        true
                    } else false
                }
                else -> false
            }
        }
    }

    private fun makePressableBackground(accent: Int, radiusDp: Float, strokeDp: Float): StateListDrawable {
        fun dpF(v: Float) = dp(v).toFloat()
        val rPx = dpF(radiusDp)
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
        cancelAutoHide()
    }

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
        val openIntent = Intent(this, ClipboardMonitorService::class.java).apply {
            action = ACTION_SHOW_FLOATING_LIST
        }
        val openPendingIntent = PendingIntent.getService(
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
        const val ACTION_SHOW_FLOATING_LIST = "com.infiniteclipboard.action.SHOW_FLOATING_LIST"
        const val ACTION_SCREEN_TAPPED = "com.infiniteclipboard.SCREEN_TAPPED"

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