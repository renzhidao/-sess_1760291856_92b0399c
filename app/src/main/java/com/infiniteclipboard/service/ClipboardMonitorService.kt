// 文件: app/src/main/java/com/infiniteclipboard/service/ClipboardMonitorService.kt
// ClipboardMonitorService（修复：改为“复制后短时一次性透明层”；只拦轻点；滑动/长按不处理）
package com.infiniteclipboard.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.infiniteclipboard.ClipboardApplication
import com.infiniteclipboard.R
import com.infiniteclipboard.ui.ClipboardWindowActivity
import com.infiniteclipboard.ui.TapRecordActivity
import com.infiniteclipboard.utils.LogUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.hypot

class ClipboardMonitorService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var clipboardManager: ClipboardManager
    private val repository by lazy { (application as ClipboardApplication).repository }

    private var lastClipboardContent: String? = null
    private var isPaused: Boolean = false

    // 一次性透明层（复制后短时出现）
    private lateinit var wm: WindowManager
    private var overlayView: View? = null
    private var overlayLp: WindowManager.LayoutParams? = null
    private var overlayShowing = false

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
        prepareOverlay() // 预创建视图（默认不显示）
        LogUtils.d("ClipboardService", "服务已启动，监听器已注册")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE -> togglePause()
            ACTION_CLEAR_ALL -> clearAll()
        }
        updateNotification()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        clipboardManager.removePrimaryClipChangedListener(clipboardListener)
        removeOverlay(force = true)
        serviceScope.cancel()
    }

    private fun handleClipboardChange() {
        try {
            val clip = clipboardManager.primaryClip
            if (clip == null || clip.itemCount <= 0) {
                LogUtils.d("ClipboardService", "剪切板为空")
                return
            }
            val text = clip.getItemAt(0).text?.toString()
            LogUtils.d("ClipboardService", "获取到文本: ${text?.take(50)}")
            if (!text.isNullOrEmpty()) {
                if (text != lastClipboardContent) {
                    lastClipboardContent = text
                    saveClipboardContent(text)
                } else {
                    LogUtils.d("ClipboardService", "重复内容，跳过")
                }
                // 复制后：短时显示一次性透明层，等待“下一次轻点”
                showOverlayOnce()
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
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun togglePause() { isPaused = !isPaused }

    // ========== 一次性透明层（仅拦“轻点”，滑动/长按不处理；出现后点一次即消失） ==========

    private fun prepareOverlay() {
        if (overlayView != null) return
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            // 不获取焦点；仅在显示时接管触控
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        val frame = FrameLayout(this).apply {
            // 极细边框 + 左上角淡水印（默认开；以后可加设置开关）
            setBackgroundResource(R.drawable.overlay_border)
            addView(TextView(context).apply {
                text = "轻触记录"
                setTextColor(0x55FFFFFF.toInt())
                textSize = 10f
                val pad = (8 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
            }, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.TOP or Gravity.START })

            // 只判定“轻点”：移动 < 10dp 且按压 < 220ms
            val touchSlop = 10f * resources.displayMetrics.density
            var downX = 0f
            var downY = 0f
            var downRawX = 0f
            var downRawY = 0f
            var downTime = 0L
            var moved = false

            visibility = View.GONE // 默认不显示
            setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = ev.x; downY = ev.y
                        downRawX = ev.rawX; downRawY = ev.rawY
                        downTime = SystemClock.uptimeMillis()
                        moved = false
                        true // 拦下按，等待判定
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = ev.x - downX
                        val dy = ev.y - downY
                        if (hypot(dx.toDouble(), dy.toDouble()) > touchSlop) {
                            moved = true
                        }
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val dt = SystemClock.uptimeMillis() - downTime
                        val isClick = !moved && dt < 220
                        if (isClick) {
                            // 轻点：记录 + 透传
                            handleGlobalTap(downRawX, downRawY)
                        }
                        // 无论轻点还是滑动/长按，点一次即消失（一次性）
                        removeOverlay(force = false)
                        true
                    }
                    else -> true
                }
            }
        }

        try {
            wm.addView(frame, lp)
            overlayView = frame
            overlayLp = lp
        } catch (_: Throwable) { }
    }

    private fun showOverlayOnce(timeoutMs: Long = 1500L) {
        val v = overlayView ?: return
        if (overlayShowing) return
        try {
            v.visibility = View.VISIBLE
            overlayShowing = true
            // 超时自动消失（未点的情况）
            serviceScope.launch(Dispatchers.Main) {
                delay(timeoutMs)
                removeOverlay(force = false)
            }
        } catch (_: Throwable) { }
    }

    private fun removeOverlay(force: Boolean) {
        val v = overlayView ?: return
        if (!overlayShowing && !force) return
        try {
            v.visibility = View.GONE
        } catch (_: Throwable) { }
        overlayShowing = false
    }

    private fun handleGlobalTap(rawX: Float, rawY: Float) {
        // 1) 先“消失”，避免把我们自己注入的轻点再吃掉
        removeOverlay(force = false)

        // 2) 启动透明前台 Activity，完成读取并立即关闭（无跳转感）
        try {
            val it = Intent(this, TapRecordActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            startActivity(it)
        } catch (_: Throwable) {}

        // 3) 短延迟：等回到底层后，注入同坐标轻点（透传）
        serviceScope.launch(Dispatchers.Main) {
            try {
                delay(220L)
                ClipboardAccessibilityService.dispatchTap(rawX, rawY)
            } catch (_: Throwable) { }
        }
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
}