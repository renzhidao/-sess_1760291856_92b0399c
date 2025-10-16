// 文件: app/src/main/java/com/infiniteclipboard/ui/ClipboardAdapter.kt
package com.infiniteclipboard.ui

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.infiniteclipboard.R
import com.infiniteclipboard.data.ClipboardEntity
import com.infiniteclipboard.databinding.ItemClipboardBinding
import com.infiniteclipboard.utils.ClipboardUtils
import com.infiniteclipboard.utils.LinkExtractor
import java.util.Locale

class ClipboardAdapter(
    private val onCopyClick: (ClipboardEntity) -> Unit,
    private val onDeleteClick: (ClipboardEntity) -> Unit,
    private val onItemClick: (ClipboardEntity) -> Unit,
    private val onShareClick: (ClipboardEntity) -> Unit,
    private val onEditRequest: (ClipboardEntity) -> Unit = {}
) : ListAdapter<ClipboardEntity, ClipboardAdapter.ViewHolder>(DiffCallback()) {

    private var highlightQuery: String = ""
    private val expandedLinkIds = mutableSetOf<Long>()

    fun setHighlightQuery(query: String) {
        highlightQuery = query
        notifyDataSetChanged()
    }

    fun toggleLinksForId(id: Long) {
        if (expandedLinkIds.contains(id)) expandedLinkIds.remove(id) else expandedLinkIds.add(id)
        val pos = currentList.indexOfFirst { it.id == id }
        if (pos >= 0) notifyItemChanged(pos)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemClipboardBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = getItem(position)
        holder.bind(item, highlightQuery, expandedLinkIds.contains(item.id))
    }

    inner class ViewHolder(
        private val binding: ItemClipboardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ClipboardEntity, query: String, showLinks: Boolean) {
            val ctx = binding.root.context
            binding.apply {
                tvContent.text = item.content
                if (query.isNotBlank()) {
                    tvContent.text = buildHighlighted(item.content, query, ctx)
                }

                val copyView = root.findViewById<View>(R.id.ib_copy) ?: root.findViewById(R.id.btnCopy)
                val shareView = root.findViewById<View>(R.id.ib_share) ?: root.findViewById(R.id.btnShare)
                val deleteView = root.findViewById<View>(R.id.ib_delete) ?: root.findViewById(R.id.btnDelete)

                copyView?.setOnClickListener { onCopyClick(item) }
                shareView?.setOnClickListener { onShareClick(item) }
                deleteView?.setOnClickListener { onDeleteClick(item) }

                root.setOnClickListener { onItemClick(item) }
                root.setOnLongClickListener { onEditRequest(item); true }

                val linkBox = ensureLinkBox(root as ViewGroup)
                if (showLinks) {
                    populateLinkChips(linkBox, ctx, item.content)
                    linkBox.visibility = View.VISIBLE
                } else {
                    linkBox.visibility = View.GONE
                }
            }
        }

        private fun ensureLinkBox(parent: ViewGroup): LinearLayout {
            val tag = "link_box"
            var box = parent.findViewWithTag<LinearLayout>(tag)
            if (box == null) {
                box = LinearLayout(parent.context).apply {
                    this.tag = tag
                    orientation = LinearLayout.HORIZONTAL
                    visibility = View.GONE
                    layoutParams = ViewGroup.MarginLayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = dp(parent.context, 4f)
                        marginStart = dp(parent.context, 4f)
                    }
                }
                parent.addView(box, 0)
            }
            return box
        }

        private fun populateLinkChips(container: LinearLayout, ctx: Context, text: String) {
            container.removeAllViews()
            val accent = ContextCompat.getColor(ctx, R.color.highlight)
            val links = LinkExtractor.extract(text)
            if (links.isEmpty()) {
                container.visibility = View.GONE
                return
            }
            container.isHorizontalScrollBarEnabled = true

            links.forEach { url ->
                val chip = Chip(ctx).apply {
                    this.text = url
                    isClickable = true
                    chipStrokeWidth = dp(ctx, 1f).toFloat()
                    chipStrokeColor = ContextCompat.getColorStateList(ctx, R.color.highlight)
                    chipBackgroundColor = ContextCompat.getColorStateList(ctx, android.R.color.transparent)
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    maxLines = 1
                    setOnClickListener {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            ctx.startActivity(intent)
                        } catch (_: Throwable) {}
                    }
                    setOnLongClickListener {
                        ClipboardUtils.setClipboardText(ctx, url)
                        true
                    }
                }
                container.addView(chip, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(ctx, 6f) })
            }
        }

        private fun buildHighlighted(text: String, query: String, ctx: Context): CharSequence {
            val source = text
            val q = query.trim()
            if (q.isEmpty()) return source

            val lowerSrc = source.lowercase(Locale.getDefault())
            val lowerQ = q.lowercase(Locale.getDefault())
            val span = SpannableString(source)
            val color = ContextCompat.getColor(ctx, R.color.highlight)

            var idx = 0
            while (true) {
                idx = lowerSrc.indexOf(lowerQ, idx)
                if (idx < 0) break
                val end = idx + lowerQ.length
                span.setSpan(ForegroundColorSpan(color), idx, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                span.setSpan(StyleSpan(Typeface.BOLD), idx, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                idx = end
            }
            return span
        }

        private fun dp(ctx: Context, v: Float) = (ctx.resources.displayMetrics.density * v + 0.5f).toInt()
    }

    private class DiffCallback : DiffUtil.ItemCallback<ClipboardEntity>() {
        override fun areItemsTheSame(old: ClipboardEntity, new: ClipboardEntity) = old.id == new.id
        override fun areContentsTheSame(old: ClipboardEntity, new: ClipboardEntity) = old == new
    }
}