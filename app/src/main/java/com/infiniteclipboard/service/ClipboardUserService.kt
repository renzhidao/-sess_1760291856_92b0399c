// 文件: app/src/main/java/com/infiniteclipboard/service/ClipboardUserService.kt
package com.infiniteclipboard.service

import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.infiniteclipboard.IClipboardUserService
import com.infiniteclipboard.utils.LogUtils
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method

class ClipboardUserService : Service() {

    override fun onCreate() {
        super.onCreate()
        // 新增：初始化文件日志，并打印关键进程/UID信息，便于确认服务是否真正启动
        try { LogUtils.init(applicationContext) } catch (_: Throwable) {}
        val msg = "onCreate in :shizuku uid=${android.os.Process.myUid()} pid=${android.os.Process.myPid()} pkg=$packageName"
        Log.d(TAG, msg)
        LogUtils.d(TAG, msg)
    }

    private val binder = object : IClipboardUserService.Stub() {
        override fun getClipboardText(): String? {
            LogUtils.d(TAG, "getClipboardText() invoked")
            val text = getClipboardTextViaShizuku(this@ClipboardUserService)
            LogUtils.d(TAG, "getClipboardText() result.length=${text?.length ?: 0}")
            return text
        }

        override fun destroy() {
            LogUtils.d(TAG, "destroy() invoked, stopping service")
            try { stopSelf() } catch (_: Throwable) { }
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "onBind: returning binder")
        LogUtils.d(TAG, "onBind() called, intent=$intent")
        return binder
    }

    // ===== IClipboard 反射读取逻辑（保持不变，补充失败日志） =====

    private fun getClipboardTextViaShizuku(ctx: Context): String? {
        val proxy = obtainIClipboard()
        if (proxy == null) {
            LogUtils.e(TAG, "obtainIClipboard() == null")
            return null
        }
        val (clip, shape) = getPrimaryClipAllShapes(ctx, proxy)
        LogUtils.d(TAG, "getPrimaryClip via $shape, clipItems=${clip?.itemCount ?: 0}")
        return clipDataToText(ctx, clip)
    }

    private fun obtainIClipboard(): Any? {
        return try {
            val raw = SystemServiceHelper.getSystemService("clipboard") as? IBinder ?: run {
                LogUtils.e(TAG, "SystemServiceHelper.getSystemService(\"clipboard\") returned null")
                return null
            }
            val stub = Class.forName("android.content.IClipboard\$Stub")
            val asInterface = stub.getMethod("asInterface", IBinder::class.java)
            asInterface.invoke(null, raw)
        } catch (t: Throwable) {
            LogUtils.e(TAG, "obtainIClipboard() failed", t)
            null
        }
    }

    private fun getPrimaryClipAllShapes(ctx: Context, proxy: Any): Pair<ClipData?, String> {
        val clazz = proxy.javaClass
        val userId = myUserId() ?: 0
        val pkgName = ctx.packageName

        // (String, Int)
        clazz.methods.firstOrNull {
            it.name.equals("getPrimaryClip", true) && it.parameterCount == 2 &&
                    it.parameterTypes[0] == String::class.java
        }?.let { m ->
            val tag = "getPrimaryClip(String,Int)"
            tryInvoke(tag, m, proxy, pkgName, userId)?.let { return it to tag }
        }

        // (AttributionSource, Int)
        clazz.methods.firstOrNull {
            it.name.equals("getPrimaryClip", true) && it.parameterCount == 2 &&
                    it.parameterTypes[0].name == "android.content.AttributionSource"
        }?.let { m ->
            val src = buildAttributionSource(ctx)
            val tag = "getPrimaryClip(AttributionSource,Int)"
            tryInvoke(tag, m, proxy, src, userId)?.let { return it to tag }
        }

        // ()
        clazz.methods.firstOrNull {
            it.name.equals("getPrimaryClip", true) && it.parameterCount == 0
        }?.let { m ->
            val tag = "getPrimaryClip()"
            tryInvoke(tag, m, proxy)?.let { return it to tag }
        }

        LogUtils.e(TAG, "No suitable getPrimaryClip signature found")
        return null to "none"
    }

    private fun tryInvoke(tag: String, m: Method, target: Any, vararg args: Any?): ClipData? {
        return try {
            (m.invoke(target, *args) as? ClipData).also {
                LogUtils.d(TAG, "Invoke $tag success: items=${it?.itemCount ?: 0}")
            }
        } catch (t: Throwable) {
            LogUtils.e(TAG, "Invoke $tag failed", t)
            null
        }
    }

    private fun clipDataToText(ctx: Context, clip: ClipData?): String? {
        if (clip == null || clip.itemCount <= 0) return null
        val sb = StringBuilder()
        for (i in 0 until clip.itemCount) {
            val item = clip.getItemAt(i)
            val piece = try { item.coerceToText(ctx)?.toString() } catch (_: Throwable) { item.text?.toString() }
            val clean = piece?.trim()
            if (!clean.isNullOrEmpty()) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(clean)
            }
        }
        return sb.toString().trim().ifEmpty { null }
    }

    private fun buildAttributionSource(ctx: Context): Any? {
        return try {
            val uid = android.os.Process.myUid()
            val cls = Class.forName("android.content.AttributionSource")
            cls.constructors.firstOrNull { c ->
                val p = c.parameterTypes
                p.size == 3 && p[0] == Int::class.javaPrimitiveType && p[1] == String::class.java && p[2] == String::class.java
            }?.newInstance(uid, ctx.packageName, null) ?: run {
                val bCls = Class.forName("android.content.AttributionSource\$Builder")
                val b = bCls.getConstructor(Int::class.javaPrimitiveType, String::class.java).newInstance(uid, ctx.packageName)
                val build = bCls.getMethod("build")
                build.invoke(b)
            }
        } catch (t: Throwable) {
            LogUtils.e(TAG, "buildAttributionSource() failed", t)
            null
        }
    }

    private fun myUserId(): Int? {
        return try {
            val uh = Class.forName("android.os.UserHandle")
            val m = uh.getMethod("myUserId")
            m.invoke(null) as? Int
        } catch (_: Throwable) { null }
    }

    companion object {
        private const val TAG = "ShizukuUserService"
    }
}