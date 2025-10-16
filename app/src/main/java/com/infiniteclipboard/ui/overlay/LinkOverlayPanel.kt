// 文件: app/src/main/java/com/infiniteclipboard/ui/overlay/LinkOverlayPanel.kt
package com.infiniteclipboard.ui.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import com.google.android.flexbox.FlexboxLayout
import com.infiniteclipboard.R
import com.infiniteclipboard.utils.LinkExtractor
import com.infiniteclipboard.utils.BlurUtils

class LinkOverlayPanel(
    private val root: ViewGroup,
    private val blurTarget: View,
    private var panelWidthRatio: Float = 0.66f
) {
    private val overlay: View = LayoutInflater.from(root.context)
        .inflate(R.layout.view_link_bubbles_overlay, root, false)

    private val bubbles: FlexboxLayout = overlay.findViewById(R.id.bubbles_container)
    private val scrim: View = overlay.findViewById(R.id.scrim)
    private val blurBg: ImageView = overlay.findViewById(R.id.iv_blur_bg)

    var onShowStateChanged: ((Boolean) -> Unit)? = null

    init {
        overlay.visibility = View.GONE
        overlay.setOnClickListener { hide() }
        root.addView(overlay)
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

        captureAndBlur {
            overlay.alpha = 0f
            overlay.visibility = View.VISIBLE
            overlay.animate()
                .alpha(1f)
                .setDuration(180)
                .setInterpolator(DecelerateInterpolator())
                .start()
            onShowStateChanged?.invoke(true)
        }
    }

    fun hide() {
        if (!isShowing()) return
        overlay.animate()
            .alpha(0f)
            .setDuration(140)
            .withEndAction {
                overlay.visibility = View.GONE
                scrim.visibility = View.GONE
                blurBg.setImageDrawable(null)
                onShowStateChanged?.invoke(false)
            }
            .start()
    }

    fun hideIfShowing(): Boolean {
        return if (isShowing()) { hide(); true } else false
    }

    private fun captureAndBlur(onReady: () -> Unit) {
        blurTarget.post {
            val bitmap = captureView(blurTarget)
            if (bitmap != null) {
                val blurred = BlurUtils.fastBlur(bitmap, 20)
                blurBg.setImageBitmap(blurred)
                blurBg.visibility = View.VISIBLE
            }
            scrim.visibility = View.VISIBLE
            onReady()
        }
    }

    private fun captureView(view: View): Bitmap? {
        val w = view.width
        val h = view.height
        if (w <= 0 || h <= 0) return null
        
        val scale = 0.25f
        val sw = (w * scale).toInt().coerceAtLeast(1)
        val sh = (h * scale).toInt().coerceAtLeast(1)
        
        val bitmap = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale)
        view.draw(canvas)
        return bitmap
    }
}