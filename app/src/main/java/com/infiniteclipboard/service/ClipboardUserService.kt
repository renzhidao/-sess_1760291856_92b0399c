文件: app/src/main/java/com/infiniteclipboard/service/ClipboardUserService.kt
// 文件: app/src/main/java/com/infiniteclipboard/service/ClipboardUserService.kt
// Shizuku UserService：真正的 Service，onBind 返回 AIDL Stub（在 :shizuku 进程中运行）
package com.infiniteclipboard.service

import android.app.Service
import android.content.ClipData
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.infiniteclipboard.IClipboardUserService
import rikka.shizuku.SystemServiceHelper

class ClipboardUserService : Service() {

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "UserService onCreate in process")
    }

    private val binder = object : IClipboardUserService.Stub() {
        override fun getClipboardText(): String? {
            return try {
                val iCb = obtainIClipboard() ?: return null
                val clip = invokeGetPrimaryClip(iCb)
                clipDataToText(clip)
            } catch (t: Throwable) {
                Log.e(TAG, "getClipboardText failed", t)
                null
            }
        }

        override fun destroy() {
            try { stopSelf() } catch (_: Throwable) { }
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "onBind: returning binder")
        return binder
    }

    // 使用 Shizuku SystemServiceHelper 获取系统 clipboard Binder（而非直接 ServiceManager）
    private fun obtainIClipboard(): Any? {
        return try {
            val raw = SystemServiceHelper.getSystemService("clipboard") as? IBinder ?: return null
            val stub = Class.forName("android.content.IClipboard\$Stub")
            val asInterface = stub.getMethod("asInterface", IBinder::class.java)
            asInterface.invoke(null, raw)
        } catch (t: Throwable) {
            Log.e(TAG, "obtainIClipboard failed", t)
            null
        }
    }

    // 兼容多签名的 getPrimaryClip
    private fun invokeGetPrimaryClip(proxy: Any): ClipData? {
        val clazz = proxy.javaClass

        // (AttributionSource, Int)
        clazz.methods.firstOrNull {
            it.name.equals("getPrimaryClip", true) &&
                    it.returnType == ClipData::class.java &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[0].name == "android.content.AttributionSource" &&
                    (it.parameterTypes[1] == Int::class.javaPrimitiveType || it.parameterTypes[1] == Integer::class.java)
        }?.let { m ->
            val src = buildAttributionSourceOrNull()
            val uid = myUserId() ?: 0
            return try { if (src != null) m.invoke(proxy, src, uid) as? ClipData else null } catch (_: Throwable) { null }
        }

        // (AttributionSource)
        clazz.methods.firstOrNull {
            it.name.equals("getPrimaryClip", true) &&
                    it.returnType == ClipData::class.java &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0].name == "android.content.AttributionSource"
        }?.let { m ->
            val src = buildAttributionSourceOrNull()
            return try { if (src != null) m.invoke(proxy, src) as? ClipData else null } catch (_: Throwable) { null }
        }

        // (String, String, Int)
        clazz.methods.firstOrNull {
            it.name.equals("getPrimaryClip", true) &&
                    it.returnType == ClipData::class.java &&
                    it.parameterTypes.size == 3 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[1] == String::class.java &&
                    (it.parameterTypes[2] == Int::class.javaPrimitiveType || it.parameterTypes[2] == Integer::class.java)
        }?.let { m ->
            val uid = myUserId() ?: 0
            return try { m.invoke(proxy, packageName, null, uid) as? ClipData } catch (_: Throwable) { null }
        }

        // (String, Int)
        clazz.methods.firstOrNull {
            it.name.equals("getPrimaryClip", true) &&
                    it.returnType == ClipData::class.java &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == String::class.java &&
                    (it.parameterTypes[1] == Int::class.javaPrimitiveType || it.parameterTypes[1] == Integer::class.java)
        }?.let { m ->
            val uid = myUserId() ?: 0
            return try { m.invoke(proxy, packageName, uid) as? ClipData } catch (_: Throwable) { null }
        }

        // (String, String)
        clazz.methods.firstOrNull {
            it.name.equals("getPrimaryClip", true) &&
                    it.returnType == ClipData::class.java &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == String::class.java &&
                    it.parameterTypes[1] == String::class.java
        }?.let { m ->
            return try { m.invoke(proxy, packageName, null) as? ClipData } catch (_: Throwable) { null }
        }

        // (String)
        clazz.methods.firstOrNull {
            it.name.equals("getPrimaryClip", true) &&
                    it.returnType == ClipData::class.java &&
                    it.parameterCount == 1 &&
                    it.parameterTypes[0] == String::class.java
        }?.let { m ->
            return try { m.invoke(proxy, packageName) as? ClipData } catch (_: Throwable) { null }
        }

        // ()
        clazz.methods.firstOrNull {
            it.name.equals("getPrimaryClip", true) &&
                    it.returnType == ClipData::class.java &&
                    it.parameterCount == 0
        }?.let { m ->
            return try { m.invoke(proxy) as? ClipData } catch (_: Throwable) { null }
        }

        return null
    }

    private fun clipDataToText(clip: ClipData?): String? {
        if (clip == null || clip.itemCount <= 0) return null
        val sb = StringBuilder()
        for (i in 0 until clip.itemCount) {
            val item = clip.getItemAt(i)
            val piece = try { item.coerceToText(this)?.toString() } catch (_: Throwable) { item.text?.toString() }
            val clean = piece?.trim()
            if (!clean.isNullOrEmpty()) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(clean)
            }
        }
        return sb.toString().trim().ifEmpty { null }
    }

    private fun buildAttributionSourceOrNull(): Any? {
        return try {
            val uid = android.os.Process.myUid()
            val cls = Class.forName("android.content.AttributionSource")
            // (int, String, String)
            cls.constructors.firstOrNull { c ->
                val p = c.parameterTypes
                p.size == 3 && p[0] == Int::class.javaPrimitiveType && p[1] == String::class.java && p[2] == String::class.java
            }?.newInstance(uid, packageName, null) ?: run {
                // Builder
                val bCls = Class.forName("android.content.AttributionSource\$Builder")
                val b = bCls.getConstructor(Int::class.javaPrimitiveType, String::class.java).newInstance(uid, packageName)
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