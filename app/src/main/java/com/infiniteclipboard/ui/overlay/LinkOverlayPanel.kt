// 文件: app/src/main/java/com/infiniteclipboard/ui/overlay/LinkOverlayPanel.kt
package com.infiniteclipboard.ui.overlay

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.card.MaterialCardView
import com.infiniteclipboard.R
import com.infiniteclipboard.utils.LinkExtractor

class LinkOverlayPanel(
    private val root: ViewGroup,           // 容器
    private val blurTarget: View,          // 被模糊的背景
    private var panelWidthRatio: Float = 0.66f
) {

    private val overlay: View = LayoutInflater.from(root.context)
        .inflate(R.layout.view_link_overlay, root, false)

    private val scrim: View = overlay.findViewById(R.id.view_scrim)
    private val panel: MaterialCardView = overlay.findViewById(R.id.card_panel)
    private val chipGroup: ChipGroup = overlay.findViewById(R.id.chips_links)
    private val btnClose: View = overlay.findViewById(R.id.btn_close)
    private val tvEmpty: View = overlay.findViewById(R.id.tv_empty)

    var onShowStateChanged: ((showing: Boolean) -> Unit)? = null

    init {
        overlay.visibility = View.GONE
        root.addView(overlay)
        scrim.setOnClickListener { hide() }
        btnClose.setOnClickListener { hide() }
    }

    fun setPanelWidthRatio(ratio: Float) {
        panelWidthRatio = ratio.coerceIn(0.4f, 0.95f)
    }

    fun isShowing(): Boolean = overlay.visibility == View.VISIBLE

    fun showForText(text: String?) {
        val links = LinkExtractor.extract(text ?: "")
        show(links)
    }

    fun show(links: List<String>) {
        preparePanelWidth()

        chipGroup.removeAllViews()
        if (links.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
        } else {
            tvEmpty.visibility = View.GONE
            val accent = root.resources.getColor(R.color.highlight, root.context.theme)
            links.forEach { url ->
                val chip = Chip(root.context).apply {
                    text = url
                    isClickable = true
                    isCheckable = false
                    setTextColor(accent)
                    chipStrokeWidth = root.resources.displayMetrics.density
                    setChipStrokeColorResource(R.color.highlight)
                    setChipBackgroundColorResource(android.R.color.transparent)
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    maxLines = 1
                    setOnClickListener {
                        try {
                            val intent = android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(url)
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            root.context.startActivity(intent)
                        } catch (_: Throwable) { }
                    }
                    setOnLongClickListener {
                        try {
                            val cm = root.context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("link", url))
                        } catch (_: Throwable) { }
                        true
                    }
                }
                chipGroup.addView(chip)
            }
        }

        overlay.visibility = View.VISIBLE
        animateIn()
        onShowStateChanged?.invoke(true)
    }

    fun hide() {
        if (!isShowing()) return
        animateOut {
            overlay.visibility = View.GONE
            clearBlur()
            onShowStateChanged?.invoke(false)
        }
    }

    fun hideIfShowing(): Boolean {
        return if (isShowing()) {
            hide(); true
        } else false
    }

    private fun preparePanelWidth() {
        val w = root.width.takeIf { it > 0 } ?: root.resources.displayMetrics.widthPixels
        val targetW = (w * panelWidthRatio).toInt().coerceAtLeast(dp(220f))
        panel.layoutParams = panel.layoutParams.apply {
            width = targetW
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }
        panel.translationX = -targetW.toFloat()
        panel.requestLayout()
    }

    private fun animateIn() {
        applyBlur()
        scrim.animate().alpha(1f).setDuration(180).setInterpolator(DecelerateInterpolator()).start()
        panel.animate()
            .translationX(0f)
            .setDuration(200)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun animateOut(end: () -> Unit) {
        val targetW = panel.width.toFloat().coerceAtLeast(1f)
        panel.animate()
            .translationX(-targetW)
            .setDuration(180)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { end() }
            .start()

        scrim.animate().alpha(0f).setDuration(160).setInterpolator(DecelerateInterpolator()).start()
    }

    private fun applyBlur() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val effect = RenderEffect.createBlurEffect(20f, 20f, Shader.TileMode.CLAMP)
                blurTarget.setRenderEffect(effect)
            } catch (_: Throwable) { }
        }
    }

    private fun clearBlur() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try { blurTarget.setRenderEffect(null) } catch (_: Throwable) { }
        }
    }

    private fun dp(v: Float): Int = (root.resources.displayMetrics.density * v + 0.5f).toInt()
}