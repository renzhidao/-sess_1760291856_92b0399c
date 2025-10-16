// header
package com.infiniteclipboard.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 通知点击接收器：只负责把点击转换为启动服务动作（ACTION_SHOW_FLOATING_LIST），
 * 避免直接从通知 PendingIntent 触发 UI 悬浮窗导致的崩溃/时序问题。
 */
class NotificationClickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val appCtx = context.applicationContext
        val svc = Intent(appCtx, ClipboardMonitorService::class.java).apply {
            action = "com.infiniteclipboard.action.SHOW_FLOATING_LIST"
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appCtx.startForegroundService(svc)
            } else {
                appCtx.startService(svc)
            }
        } catch (_: Throwable) { }
    }
}