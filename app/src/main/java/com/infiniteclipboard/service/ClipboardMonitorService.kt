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
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.infiniteclipboard.ClipboardApplication
import com.infiniteclipboard.R
import com.infiniteclipboard.utils.LogUtils
import kotlinx.coroutines.*

class ClipboardMonitorService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var clipboardManager: ClipboardManager
    private val repository by lazy { (application as ClipboardApplication).repository }
    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }

    private var lastClipboardContent: String? = null
    private var isPaused: Boolean = false

    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (!isPaused) handleClipboardChange()
    }

    override fun onCreate() {
        super.onCreate()
        clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        clipboardManager.addPrimaryClipChangedListener(clipboardListener)

        // 开关开启则启动 Shizuku 监听（解耦生命周期：不在 onDestroy 自动 stop）
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
        }
        updateNotification()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        try { clipboardManager.removePrimaryClipChangedListener(clipboardListener) } catch (_: Throwable) { }
        // 关键：不再这里 stop，让监控常驻由应用/开关控制
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

    // 保留供测试调用
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
        val openIntent = Intent(this, ClipboardMonitorService::class.java).apply { action = ACTION_TOGGLE }
        val openPendingIntent = PendingIntent.getService(this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

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
            .addAction(toggleIcon, toggleTitle, openPendingIntent)
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ClipboardMonitorService::class.java)
            context.stopService(intent)
        }
    }
}