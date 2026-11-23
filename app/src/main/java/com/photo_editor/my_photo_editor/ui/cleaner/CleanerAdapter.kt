package com.photo_editor.my_photo_editor.ui.cleaner

import android.content.res.ColorStateList
import com.photo_editor.my_photo_editor.utils.appColor
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.photo_editor.my_photo_editor.R
import com.photo_editor.my_photo_editor.databinding.ItemCleanerGroupHeaderBinding
import com.photo_editor.my_photo_editor.databinding.ItemCleanerMediaBinding
import com.photo_editor.my_photo_editor.ui.gallery.loadThumbnail

/** Group headers (full span) and square thumbnails with a selection checkbox. */
class CleanerAdapter(private val listener: Listener) :
    ListAdapter<CleanerRow, RecyclerView.ViewHolder>(Diff) {

    interface Listener {
        fun onPreview(row: CleanerRow.Media)
        fun onToggle(row: CleanerRow.Media)
        fun onGroupAction(header: CleanerRow.GroupHeader)
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is CleanerRow.GroupHeader -> TYPE_HEADER
        is CleanerRow.Media -> TYPE_MEDIA
    }

    fun isFullSpan(position: Int): Boolean =
        position in 0 until itemCount && getItem(position) is CleanerRow.GroupHeader

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderHolder(ItemCleanerGroupHeaderBinding.inflate(inflater, parent, false))
        } else {
            MediaHolder(ItemCleanerMediaBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is CleanerRow.GroupHeader -> (holder as HeaderHolder).bind(row)
            is CleanerRow.Media -> (holder as MediaHolder).bind(row)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        val row = getItem(position)
        if (payloads.isNotEmpty() && payloads.all { it == PAYLOAD_SELECTION } &&
            holder is MediaHolder && row is CleanerRow.Media
        ) {
            holder.bindSelection(row)
        } else {
            onBindViewHolder(holder, position)
        }
    }

    inner class HeaderHolder(private val binding: ItemCleanerGroupHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(row: CleanerRow.GroupHeader) {
            binding.tvGroupTitle.text = row.title
            binding.tvGroupSubtitle.text = row.subtitle
            binding.tvGroupAction.text = row.actionText
            binding.tvGroupAction.setOnClickListener { listener.onGroupAction(row) }
        }
    }

    inner class MediaHolder(private val binding: ItemCleanerMediaBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private var row: CleanerRow.Media? = null

        init {
            binding.ivThumb.clipToOutline = true
            binding.ivThumb.setOnClickListener { row?.let { listener.onPreview(it) } }
            binding.ivThumb.setOnLongClickListener {
                row?.let { listener.onToggle(it) }
                true
            }
            binding.checkTouch.setOnClickListener { row?.let { listener.onToggle(it) } }
        }

        fun bind(row: CleanerRow.Media) {
            this.row = row
            binding.ivThumb.loadThumbnail(row.media.uri)
            binding.tvBest.isVisible = row.isBest
            binding.ivVideo.isVisible = row.media.isVideo
            binding.tvBadge.isVisible = !row.badge.isNullOrEmpty()
            binding.tvBadge.text = row.badge
            bindSelection(row)
        }

        fun bindSelection(row: CleanerRow.Media) {
            this.row = row
            val ctx = binding.root.context
            binding.selectedFrame.isVisible = row.selected
            binding.ivCheck.setImageResource(if (row.selected) R.drawable.ic_g_check_circle else R.drawable.ic_g_circle_outline)
            ImageViewCompat.setImageTintList(
                binding.ivCheck,
                ColorStateList.valueOf(ctx.appColor(if (row.selected) R.color.lumina_accent else R.color.white))
            )
            binding.checkTouch.contentDescription = if (row.selected) "Selected for deletion" else "Not selected"
        }
    }

    private object Diff : DiffUtil.ItemCallback<CleanerRow>() {
        override fun areItemsTheSame(oldItem: CleanerRow, newItem: CleanerRow): Boolean = oldItem.key == newItem.key

        override fun areContentsTheSame(oldItem: CleanerRow, newItem: CleanerRow): Boolean = oldItem == newItem

        override fun getChangePayload(oldItem: CleanerRow, newItem: CleanerRow): Any? {
            if (oldItem is CleanerRow.Media && newItem is CleanerRow.Media &&
                oldItem.copy(selected = newItem.selected) == newItem
            ) {
                return PAYLOAD_SELECTION
            }
            return null
        }
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_MEDIA = 1
        private const val PAYLOAD_SELECTION = "selection"
    }
}
