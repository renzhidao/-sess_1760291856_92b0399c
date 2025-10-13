// 文件: app/src/main/java/com/infiniteclipboard/service/ClipboardUserService.kt
package com.infiniteclipboard.service

import android.content.ClipData
import android.os.IBinder
import android.os.Process
import com.infiniteclipboard.IClipboardUserService
import java.lang.reflect.Method

class ClipboardUserService : IClipboardUserService.Stub() {

    override fun getClipboardText(): String? {
        return try {
            // 在privileged进程中获取IClipboard Binder
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getService = serviceManagerClass.getMethod("getService", String::class.java)
            val binder = getService.invoke(null, "clipboard") as? IBinder
                ?: return null

            val stubClass = Class.forName("android.content.IClipboard\$Stub")
            val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
            val iClipboard = asInterface.invoke(null, binder) ?: return null

            // 获取getPrimaryClip方法（尝试多种签名）
            val clip = invokePrimaryClip(iClipboard)
            extractText(clip)
        } catch (e: Throwable) {
            android.util.Log.e("ClipboardUserService", "读取失败", e)
            null
        }
    }

    private fun invokePrimaryClip(proxy: Any): ClipData? {
        val clazz = proxy.javaClass
        val methods = clazz.methods

        // 尝试 getPrimaryClip(String, String, int)
        try {
            val m = clazz.getMethod("getPrimaryClip", String::class.java, String::class.java, Int::class.javaPrimitiveType)
            return m.invoke(proxy, "com.infiniteclipboard", null, getUserId()) as? ClipData
        } catch (_: Throwable) {}

        // 尝试 getPrimaryClip(String, int)
        try {
            val m = clazz.getMethod("getPrimaryClip", String::class.java, Int::class.javaPrimitiveType)
            return m.invoke(proxy, "com.infiniteclipboard", getUserId()) as? ClipData
        } catch (_: Throwable) {}

        // 尝试 getPrimaryClip(String)
        try {
            val m = clazz.getMethod("getPrimaryClip", String::class.java)
            return m.invoke(proxy, "com.infiniteclipboard") as? ClipData
        } catch (_: Throwable) {}

        // 尝试 getPrimaryClip()
        try {
            val m = clazz.getMethod("getPrimaryClip")
            return m.invoke(proxy) as? ClipData
        } catch (_: Throwable) {}

        return null
    }

    private fun extractText(clip: ClipData?): String? {
        if (clip == null || clip.itemCount == 0) return null
        val sb = StringBuilder()
        for (i in 0 until clip.itemCount) {
            val item = clip.getItemAt(i)
            val text = item?.text?.toString()
            if (!text.isNullOrBlank()) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(text)
            }
        }
        return sb.toString().trim().takeIf { it.isNotEmpty() }
    }

    private fun getUserId(): Int {
        return try {
            val userHandleClass = Class.forName("android.os.UserHandle")
            val myUserId = userHandleClass.getMethod("myUserId")
            myUserId.invoke(null) as? Int ?: 0
        } catch (_: Throwable) {
            0
        }
    }

    override fun destroy() {
        System.exit(0)
    }
}