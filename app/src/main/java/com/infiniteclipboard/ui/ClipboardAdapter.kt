// 文件: app/src/main/java/com/infiniteclipboard/ui/ClipboardAdapter.kt
package com.infiniteclipboard.ui

import android.content.Context
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.infiniteclipboard.R
import com.infiniteclipboard.data.ClipboardEntity
import com.infiniteclipboard.databinding.ItemClipboardBinding
import java.util.Locale

class ClipboardAdapter(
    private val onCopyClick: (ClipboardEntity) -> Unit,
    private val onDeleteClick: (ClipboardEntity) -> Unit,
    private val onItemClick: (ClipboardEntity) -> Unit,
    private val onShareClick: (ClipboardEntity) -> Unit,
    private val onEditRequest: (ClipboardEntity) -> Unit = {}
) : ListAdapter<ClipboardEntity, ClipboardAdapter.ViewHolder>(DiffCallback()) {

    private var highlightQuery: String = ""

    fun setHighlightQuery(query: String) {
        highlightQuery = query
        notifyDataSetChanged()
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
        holder.bind(getItem(position), highlightQuery)
    }

    inner class ViewHolder(
        private val binding: ItemClipboardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ClipboardEntity, query: String) {
            binding.apply {
                tvContent.text = item.content
                if (query.isNotBlank()) {
                    tvContent.text = buildHighlighted(item.content, query, root.context)
                }

                val copyView = root.findViewById<View>(R.id.ib_copy) ?: root.findViewById(R.id.btnCopy)
                val shareView = root.findViewById<View>(R.id.ib_share) ?: root.findViewById(R.id.btnShare)
                val deleteView = root.findViewById<View>(R.id.ib_delete) ?: root.findViewById(R.id.btnDelete)

                copyView?.setOnClickListener { onCopyClick(item) }
                shareView?.setOnClickListener { onShareClick(item) }
                deleteView?.setOnClickListener { onDeleteClick(item) }

                root.setOnClickListener { onItemClick(item) }
                root.setOnLongClickListener { onEditRequest(item); true }
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
    }

    private class DiffCallback : DiffUtil.ItemCallback<ClipboardEntity>() {
        override fun areItemsTheSame(oldItem: ClipboardEntity, newItem: ClipboardEntity) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: ClipboardEntity, newItem: ClipboardEntity) = oldItem == newItem
    }
}