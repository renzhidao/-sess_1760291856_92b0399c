可以。下面给你“可直接覆盖”的完整文件（Android 12 真·毛玻璃 + 左上瀑布流 + 无边框气泡）。MainActivity 无需改动，保持你现在的构造：LinkOverlayPanel(binding.root as ViewGroup, binding.recyclerView, 0.66f)。

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

/**
 * Android 12+ 真·原位毛玻璃：
 * - 对 blurTarget(RecyclerView) 施加 RenderEffect
 * - overlay 自身是透明，仅叠加轻微 scrim + 气泡
 * - 气泡从左上角开始纵向瀑布流
 */
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

        applyBlur()               // 原位毛玻璃（Android 12+）
        scrim.visibility = View.VISIBLE

        overlay.alpha = 0f
        overlay.visibility = View.VISIBLE
        overlay.animate()
            .alpha(1f)
            .setDuration(180)
            .setInterpolator(DecelerateInterpolator())
            .start()
        onShowStateChanged?.invoke(true)
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

    private fun applyBlur() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val effect = RenderEffect.createBlurEffect(24f, 24f, Shader.TileMode.CLAMP)
                blurTarget.setRenderEffect(effect)
            } catch (_: Throwable) { }
        }
    }

    private fun clearBlur() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try { blurTarget.setRenderEffect(null) } catch (_: Throwable) { }
        }
    }
}


// 文件: app/src/main/res/layout/view_link_bubbles_overlay.xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/link_bubbles_overlay_root"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:clickable="true"
    android:focusable="true"
    android:background="@android:color/transparent"
    android:visibility="gone">

    <!-- 轻微遮罩，提升对比度；背景必须透明以便看到“被模糊的 RecyclerView” -->
    <View
        android:id="@+id/scrim"
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:background="#14000000"
        android:visibility="gone" />

    <!-- 左上角纵向瀑布流 -->
    <com.google.android.flexbox.FlexboxLayout
        android:id="@+id/bubbles_container"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="start|top"
        android:background="@android:color/transparent"
        android:paddingStart="12dp"
        android:paddingTop="12dp"
        android:paddingEnd="12dp"
        android:paddingBottom="12dp"
        android:clipToPadding="false"
        app:flexWrap="wrap"
        app:flexDirection="column"
        app:justifyContent="flex_start"
        app:alignItems="flex_start"
        app:alignContent="flex_start" />
</FrameLayout>


// 文件: app/src/main/res/layout/item_link_bubble.xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:layout_margin="6dp"
    android:gravity="center_vertical">

    <ImageView
        android:layout_width="8dp"
        android:layout_height="12dp"
        android:src="@drawable/ic_bubble_tail_left"
        android:layout_marginEnd="2dp"
        android:contentDescription="@null" />

    <TextView
        android:id="@+id/tvLink"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:background="@drawable/bg_bubble_link"
        android:textColor="#1E88E5"
        android:textSize="13sp"
        android:paddingStart="10dp"
        android:paddingEnd="10dp"
        android:paddingTop="7dp"
        android:paddingBottom="7dp"
        android:maxLines="1"
        android:ellipsize="end" />
</LinearLayout>


// 文件: app/src/main/res/drawable/bg_bubble_link.xml
<?xml version="1.0" encoding="utf-8"?>
<layer-list xmlns:android="http://schemas.android.com/apk/res/android">
    <!-- 轻微投影（无边框） -->
    <item android:top="1dp" android:left="1dp">
        <shape android:shape="rectangle">
            <solid android:color="#20000000"/>
            <corners android:radius="12dp"/>
        </shape>
    </item>
    <!-- 白色主体 -->
    <item android:bottom="1dp" android:right="1dp">
        <shape android:shape="rectangle">
            <solid android:color="@android:color/white"/>
            <corners android:radius="12dp"/>
        </shape>
    </item>
</layer-list>


// 文件: app/src/main/res/drawable/ic_bubble_tail_left.xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="8dp"
    android:height="12dp"
    android:viewportWidth="8"
    android:viewportHeight="12">
    <!-- 只保留白色填充，无描边 -->
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="M8,0 L0,6 L8,12 Z"/>
</vector>

说明/检查：
- 这版 overlay 背景是透明的，scrim 很轻（#14000000）。背景的“虚化”来自 RenderEffect 直接施加在 RecyclerView 上。
- 气泡从左上角开始纵向瀑布流：flexWrap="wrap" + flexDirection="column" + align/justify 全部 flex_start。
- 不用改 MainActivity。如果仍然看到“被覆盖而非虚化”，请确认没有关闭硬件加速（Activity 默认应是启用的）；另外确保构造时 blurTarget 传的是 binding.recyclerView。