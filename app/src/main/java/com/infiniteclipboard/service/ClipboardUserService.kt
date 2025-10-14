// 文件: app/src/main/java/com/infiniteclipboard/service/ClipboardUserService.kt
package com.infiniteclipboard.service

import android.app.Service
import android.content.ClipData
import android.content.Intent
import android.os.IBinder
import com.infiniteclipboard.IClipboardUserService

class ClipboardUserService : Service() {

    private val binder = object : IClipboardUserService.Stub() {
        override fun getClipboardText(): String? {
            return try {
                val iCb = obtainIClipboard() ?: return null
                val clip = invokeGetPrimaryClip(iCb)
                clipDataToText(clip)
            } catch (_: Throwable) {
                null
            }
        }

        override fun destroy() {
            try { stopSelf() } catch (_: Throwable) {}
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    // 修复：改为块体函数，允许在函数体内使用 return
    private fun obtainIClipboard(): Any? {
        return try {
            val smCls = Class.forName("android.os.ServiceManager")
            val getService = smCls.getMethod("getService", String::class.java)
            val raw = getService.invoke(null, "clipboard") as? IBinder ?: return null
            val stub = Class.forName("android.content.IClipboard\$Stub")
            val asInterface = stub.getMethod("asInterface", IBinder::class.java)
            asInterface.invoke(null, raw)
        } catch (_: Throwable) {
            null
        }
    }

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
            val piece = item.coerceToText(this)?.toString()?.trim()
            if (!piece.isNullOrEmpty()) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(piece)
            }
        }
        return sb.toString().trim().ifEmpty { null }
    }

    private fun buildAttributionSourceOrNull(): Any? = try {
        val uid = android.os.Process.myUid()
        val tag: String? = null
        val cls = Class.forName("android.content.AttributionSource")
        cls.constructors.firstOrNull { c ->
            val p = c.parameterTypes
            p.size == 3 && p[0] == Int::class.javaPrimitiveType && p[1] == String::class.java && p[2] == String::class.java
        }?.newInstance(uid, packageName, tag) ?: run {
            val bCls = Class.forName("android.content.AttributionSource\$Builder")
            val b = bCls.getConstructor(Int::class.javaPrimitiveType, String::class.java).newInstance(uid, packageName)
            val build = bCls.getMethod("build")
            build.invoke(b)
        }
    } catch (_: Throwable) { null }

    private fun myUserId(): Int? = try {
        val uh = Class.forName("android.os.UserHandle")
        val m = uh.getMethod("myUserId")
        m.invoke(null) as? Int
    } catch (_: Throwable) { null }
}