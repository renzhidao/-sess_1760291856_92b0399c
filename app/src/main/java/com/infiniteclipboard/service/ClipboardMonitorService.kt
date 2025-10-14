// 文件: app/src/main/java/com/infiniteclipboard/service/ClipboardMonitorService.kt
// 前台监控服务：不拉起前台Activity；Shizuku连上则后台读取；否则静默（边缘小条可手动操作）
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
import android.provider.Settings
import android.util.TypedValue
import android.view.*
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.infiniteclipboard.ClipboardApplication
import com.infiniteclipboard.R
import com.infiniteclipboard.ui.ClipboardAdapter
import com.infiniteclipboard.utils.ClipboardUtils
import com.infiniteclipboard.utils.LogUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class ClipboardMonitorService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var clipboardManager: ClipboardManager
    private val repository by lazy { (application as ClipboardApplication).repository }
    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }

    private var lastClipboardContent: String? = null
    private var isPaused: Boolean = false

    private lateinit var wm: WindowManager
    private var barView: View? = null

    private var overlayView: View? = null
    private var overlayJob: Job? = null

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
        }

        val enableShizuku = prefs.getBoolean("shizuku_enabled", false)
        if (enableShizuku) ShizukuClipboardMonitor.start(this)

        LogUtils.d("ClipboardService", "服务已启动，监听器已注册")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> togglePause()
            ACTION_CLEAR_ALL -> clearAll()
            ACTION_SHIZUKU_START -> ShizukuClipboardMonitor.start(this)
            ACTION_SHIZUKU_STOP -> ShizukuClipboardMonitor.stop()
            ACTION_EDGE_BAR_ENABLE -> ensureEdgeBar()
            ACTION_EDGE_BAR_DISABLE -> removeEdgeBar()
            ACTION_SHOW_WINDOW -> showClipboardOverlay()
            ACTION_HIDE_WINDOW -> hideClipboardOverlay()
        }
        updateNotification()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try { clipboardManager.removePrimaryClipChangedListener(clipboardListener) } catch (_: Throwable) { }
        removeEdgeBar()
        hideClipboardOverlay()
        ShizukuClipboardMonitor.stop()
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
            val shizukuActive = prefs.getBoolean("shizuku_enabled", false) && ShizukuClipboardMonitor.isRunning()
            if (shizukuActive) {
                LogUtils.d("ClipboardService", "Shizuku已运行，由后台读取")
                return
            }
            // 不拉起任何 Activity（静默）
            LogUtils.d("ClipboardService", "Shizuku未运行；按要求不前台拉起读取，保持静默")
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
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun togglePause() { isPaused = !isPaused }

    private fun showClipboardOverlay() {
        if (overlayView != null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            LogUtils.d("ClipboardService", "无悬浮窗权限，无法显示悬浮小窗")
            return
        }
        val wmType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val widthRatio = prefs.getFloat("overlay_width_ratio", 0.65f).coerceIn(0.5f, 0.85f)
        val desiredW = (screenW * widthRatio).toInt()
        val maxHeight = (screenH * 0.55f).toInt()

        val lp = WindowManager.LayoutParams(
            desiredW,
            WindowManager.LayoutParams.WRAP_CONTENT,
            wmType,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt("overlay_pos_x", (screenW - desiredW) / 2)
            y = prefs.getInt("overlay_pos_y", dp(64f))
        }

        val themedContext = android.view.ContextThemeWrapper(this, R.style.Theme_InfiniteClipboard)
        val v = LayoutInflater.from(themedContext).inflate(R.layout.activity_clipboard_window, null, false)

        val rv = v.findViewById<RecyclerView>(R.id.recyclerView).apply {
            setPadding(dp(6f), dp(6f), dp(6f), dp(6f))
            clipToPadding = false
            layoutParams = layoutParams?.apply { height = maxHeight } ?: ViewGroup.LayoutParams(desiredW, maxHeight)
        }
        val close = v.findViewById<View>(R.id.btnClose)

        val adapter = ClipboardAdapter(
            onCopyClick = { item -> ClipboardUtils.setClipboardText(this, item.content) },
            onDeleteClick = { item -> serviceScope.launch(Dispatchers.IO) { repository.deleteItem(item) } },
            onItemClick = { item -> ClipboardUtils.setClipboardText(this, item.content) },
            onShareClick = { item ->
                try {
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        this.type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, item.content)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(Intent.createChooser(sendIntent, getString(R.string.share)))
                } catch (_: Throwable) { }
            }
        )
        rv.layoutManager = LinearLayoutManager(themedContext)
        rv.adapter = adapter

        close.setOnClickListener { hideClipboardOverlay() }

        v.setOnTouchListener(object : View.OnTouchListener {
            var downX = 0f
            var downY = 0f
            var startX = 0
            var startY = 0
            override fun onTouch(view: View, event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        startX = lp.x
                        startY = lp.y
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - downX).toInt()
                        val dy = (event.rawY - downY).toInt()
                        lp.x = (startX + dx).coerceIn(0, screenW - desiredW)
                        val viewH = view.height.coerceAtLeast(maxHeight / 2)
                        lp.y = (startY + dy).coerceIn(0, screenH - viewH)
                        try { wm.updateViewLayout(view, lp) } catch (_: Throwable) {}
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        prefs.edit().putInt("overlay_pos_x", lp.x).putInt("overlay_pos_y", lp.y).apply()
                        return true
                    }
                }
                return false
            }
        })

        overlayJob = serviceScope.launch {
            repository.allItems.collectLatest { items ->
                withContext(Dispatchers.Main) { adapter.submitList(items) }
            }
        }

        try {
            wm.addView(v, lp)
            overlayView = v
            LogUtils.d("ClipboardService", "悬浮小窗已显示")
        } catch (t: Throwable) {
            LogUtils.e("ClipboardService", "显示悬浮小窗失败", t)
            overlayJob?.cancel()
            overlayJob = null
            overlayView = null
        }
    }

    private fun hideClipboardOverlay() {
        overlayJob?.cancel()
        overlayJob = null
        overlayView?.let { try { wm.removeViewImmediate(it) } catch (_: Throwable) { } }
        overlayView = null
        LogUtils.d("ClipboardService", "悬浮小窗已关闭")
    }

    private fun dp(v: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics
    ).toInt()

    private fun ensureEdgeBar() {
        if (barView != null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            LogUtils.d("ClipboardService", "无悬浮窗权限，跳过显示边缘小条")
            return
        }
        val width = dp(48f)
        val lp = WindowManager.LayoutParams(
            width,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            x = 0
            y = 0
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
            val pad = dp(4f)
            setPadding(pad, pad, pad, pad)

            fun makeBtn(label: String) = TextView(context).apply {
                text = label
                setTextColor(Color.parseColor("#001F3F"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
                setBackgroundColor(Color.TRANSPARENT)
                isClickable = true
                isFocusable = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6f) }
            }

            val btnCut = makeBtn("剪切")
            val btnCopy = makeBtn("复制")
            val btnPaste = makeBtn("粘贴")
            addView(btnCut); addView(btnCopy); addView(btnPaste)

            setOnTouchListener(object : View.OnTouchListener {
                var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
                val screenW = resources.displayMetrics.widthPixels
                val screenH = resources.displayMetrics.heightPixels
                override fun onTouch(v: View, e: MotionEvent): Boolean {
                    return when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; false }
                        MotionEvent.ACTION_MOVE -> { lp.x = startX + (e.rawX - downX).toInt(); lp.y = startY + (e.rawY - downY).toInt(); try { wm.updateViewLayout(v, lp) } catch (_: Throwable) {}; true }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            val viewW = v.width; val viewH = v.height
                            val centerX = lp.x + viewW / 2; val centerY = lp.y + viewH / 2
                            val distLeft = centerX; val distRight = screenW - centerX; val distTop = centerY; val distBottom = screenH - centerY
                            when (minOf(distLeft, distRight, distTop, distBottom)) {
                                distLeft -> { lp.gravity = Gravity.CENTER_VERTICAL or Gravity.START; lp.x = 0 }
                                distRight -> { lp.gravity = Gravity.CENTER_VERTICAL or Gravity.END; lp.x = 0 }
                                distTop -> { lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; lp.y = 0 }
                                distBottom -> { lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; lp.y = 0 }
                            }
                            try { wm.updateViewLayout(v, lp) } catch (_: Throwable) {}
                            true
                        }
                        else -> false
                    }
                }
            })

            btnCopy.setOnClickListener {
                serviceScope.launch(Dispatchers.IO) {
                    val text = ClipboardAccessibilityService.captureCopy()
                    if (!text.isNullOrEmpty()) try { repository.insertItem(text) } catch (_: Throwable) { }
                }
            }
            btnCut.setOnClickListener {
                serviceScope.launch(Dispatchers.IO) {
                    val text = ClipboardAccessibilityService.captureCut()
                    if (!text.isNullOrEmpty()) try { repository.insertItem(text) } catch (_: Throwable) { }
                }
            }
            btnPaste.setOnClickListener {
                serviceScope.launch(Dispatchers.IO) {
                    val latest = try { repository.getAllOnce().firstOrNull()?.content } catch (_: Throwable) { null }
                    ClipboardAccessibilityService.performPaste(latest)
                }
            }
        }

        try { wm.addView(container, lp); barView = container } catch (_: Throwable) { }
    }

    private fun removeEdgeBar() {
        val v = barView ?: return
        try { wm.removeViewImmediate(v) } catch (_: Throwable) { }
        barView = null
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
        val openIntent = Intent(this, ClipboardMonitorService::class.java).apply { action = ACTION_SHOW_WINDOW }
        val openPendingIntent = PendingIntent.getService(this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val toggleIntent = Intent(this, ClipboardMonitorService::class.java).apply { action = ACTION_TOGGLE }
        val togglePendingIntent = PendingIntent.getService(this, 1, toggleIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val clearIntent = Intent(this, ClipboardMonitorService::class.java).apply { action = ACTION_CLEAR_ALL }
        val clearPendingIntent = PendingIntent.getService(this, 2, clearIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

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
        const val ACTION_SHOW_WINDOW = "com.infiniteclipboard.action.SHOW_WINDOW"
        const val ACTION_HIDE_WINDOW = "com.infiniteclipboard.action.HIDE_WINDOW"

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