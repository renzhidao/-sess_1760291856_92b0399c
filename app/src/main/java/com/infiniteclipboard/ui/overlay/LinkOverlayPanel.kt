// 文件: app/src/main/java/com/infiniteclipboard/ui/overlay/LinkOverlayPanel.kt
package com.infiniteclipboard.ui.overlay

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import com.google.android.flexbox.FlexboxLayout
import com.infiniteclipboard.R
import com.infiniteclipboard.utils.LinkExtractor

class LinkOverlayPanel(
    private val root: ViewGroup,
    private val blurTarget: View,
    private var panelWidthRatio: Float = 0.66f
) {
    private val overlay: View = LayoutInflater.from(root.context)
        .inflate(R.layout.view_link_bubbles_overlay, root, false)

    private val bubbles: FlexboxLayout = overlay.findViewById(R.id.bubbles_container)
    private val scrim: View = overlay.findViewById(R.id.scrim)

    var onShowStateChanged: ((Boolean) -> Unit)? = null

    init {
        overlay.visibility = View.GONE
        overlay.setOnClickListener { hide() } // 点击空白关闭
        // 确保加到最上层
        root.addView(
            overlay,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
    }

    fun isShowing() = overlay.visibility == View.VISIBLE

    fun showForText(text: String?) {
        val links = LinkExtractor.extract(text ?: "")
        show(links)
    }

    fun show(links: List<String>) {
        bubbles.removeAllViews()
        if (links.isEmpty()) {
            hide()
            return
        }
        val inflater = LayoutInflater.from(root.context)
        links.forEach { url ->
            val item = inflater.inflate(R.layout.item_link_bubble, bubbles, false)
            val tv = item.findViewById<TextView>(R.id.tvLink)
            tv.text = url
            tv.setOnClickListener {
                try {
                    val it = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(url)
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    root.context.startActivity(it)
                } catch (_: Throwable) { }
            }
            tv.setOnLongClickListener {
                try {
                    val cm = root.context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("link", url))
                } catch (_: Throwable) { }
                true
            }
            bubbles.addView(item)
        }

        val blurred = applyBlur() // 原位毛玻璃
        scrim.visibility = View.VISIBLE

        overlay.alpha = 0f
        overlay.visibility = View.VISIBLE
        overlay.animate()
            .alpha(1f)
            .setDuration(180)
            .setInterpolator(DecelerateInterpolator())
            .start()
        onShowStateChanged?.invoke(true)

        // 如果没成功模糊（极少见：硬件加速被关），至少不至于黑屏：把 scrim 变浅一些
        if (!blurred) {
            scrim.alpha = 0.08f
        } else {
            scrim.alpha = 1f
        }
    }

    fun hide() {
        if (!isShowing()) return
        overlay.animate()
            .alpha(0f)
            .setDuration(140)
            .withEndAction {
                overlay.visibility = View.GONE
                clearBlur()
                scrim.visibility = View.GONE
                onShowStateChanged?.invoke(false)
            }
            .start()
    }

    fun hideIfShowing(): Boolean {
        return if (isShowing()) { hide(); true } else false
    }

    // 返回是否成功套用毛玻璃
    private fun applyBlur(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // 确保走硬件加速（若外部强制成 software，会看起来像“黑屏”）
            if (!blurTarget.isHardwareAccelerated) {
                // 尝试强制切到硬件层（如果整个窗口关闭硬件加速，这里也救不回来）
                blurTarget.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            }
            return try {
                val effect = RenderEffect.createBlurEffect(24f, 24f, Shader.TileMode.CLAMP)
                blurTarget.setRenderEffect(effect)
                true
            } catch (_: Throwable) {
                false
            }
        }
        return false
    }

    private fun clearBlur() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try { blurTarget.setRenderEffect(null) } catch (_: Throwable) { }
        }
    }
}