// 文件: app/src/main/java/com/infiniteclipboard/service/ClipboardUserService.kt
// 终局方案-服务端：在 :shizuku 进程（shell权限）内运行，负责所有 IClipboard 反射操作，为客户端提供稳定的后台读取能力。
package com.infiniteclipboard.service

import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.infiniteclipboard.IClipboardUserService
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method

class ClipboardUserService : Service() {

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "UserService onCreate in process ${android.os.Process.myUid()}")
    }

    private val binder = object : IClipboardUserService.Stub() {
        override fun getClipboardText(): String? {
            return getClipboardTextViaShizuku(this@ClipboardUserService)
        }

        override fun destroy() {
            try { stopSelf() } catch (_: Throwable) { }
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "onBind: returning binder")
        return binder
    }

    // ===== 以下为 IClipboard 反射读取核心逻辑，现在完全在本服务内执行 =====

    private fun getClipboardTextViaShizuku(ctx: Context): String? {
        val proxy = obtainIClipboard() ?: return null
        val (clip, _) = getPrimaryClipAllShapes(ctx, proxy)
        return clipDataToText(ctx, clip)
    }

    private fun obtainIClipboard(): Any? {
        return try {
            val raw = SystemServiceHelper.getSystemService("clipboard") as? IBinder ?: return null
            val stub = Class.forName("android.content.IClipboard\$Stub")
            val asInterface = stub.getMethod("asInterface", IBinder::class.java)
            asInterface.invoke(null, raw)
        } catch (_: Throwable) { null }
    }

    private fun getPrimaryClipAllShapes(ctx: Context, proxy: Any): Pair<ClipData?, String> {
        val clazz = proxy.javaClass
        val userId = myUserId() ?: 0

        // 优先使用带包名参数的签名，因为这是最常见的形式
        // 在 :shizuku 进程里，packageName 仍然是我们的应用包名，但 UID 是 shell，这样组合才能通过系统校验
        val pkgName = ctx.packageName

        // (String, Int)
        clazz.methods.firstOrNull {
            it.name.equals("getPrimaryClip", true) && it.parameterCount == 2 &&
            it.parameterTypes[0] == String::class.java &&
            (it.parameterTypes[1] == Int::class.javaPrimitiveType || it.parameterTypes[1] == Integer::class.java)
        }?.let { m ->
            val tag = "getPrimaryClip(String,Int)"
            tryInvoke(tag, m, proxy, pkgName, userId)?.let { return it to tag }
        }
        
        // (AttributionSource, Int) - 作为备用
        clazz.methods.firstOrNull {
            it.name.equals("getPrimaryClip", true) && it.parameterCount == 2 &&
            it.parameterTypes[0].name == "android.content.AttributionSource"
        }?.let { m ->
            val src = buildAttributionSource(ctx)
            val tag = "getPrimaryClip(AttributionSource,Int)"
            tryInvoke(tag, m, proxy, src, userId)?.let { return it to tag }
        }

        // 无参数 - 作为最终备用
        clazz.methods.firstOrNull {
            it.name.equals("getPrimaryClip", true) && it.parameterCount == 0
        }?.let { m ->
            val tag = "getPrimaryClip()"
            tryInvoke(tag, m, proxy)?.let { return it to tag }
        }

        return null to "none"
    }

    private fun tryInvoke(tag: String, m: Method, target: Any, vararg args: Any?): ClipData? {
        return try {
            m.invoke(target, *args) as? ClipData
        } catch (t: Throwable) {
            Log.e(TAG, "Invoke $tag failed", t)
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
            val uid = android.os.Process.myUid() // 在这里是 shell 的 UID
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
        } catch (_: Throwable) { null }
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