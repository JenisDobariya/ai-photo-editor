package com.photo_editor.my_photo_editor.ui.gallery

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.request.videoFrameMicros
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.ShapeAppearanceModel
import com.photo_editor.my_photo_editor.R
import com.photo_editor.my_photo_editor.model.MediaFile
import com.photo_editor.my_photo_editor.model.MediaType
import com.photo_editor.my_photo_editor.model.Memory
import com.photo_editor.my_photo_editor.repository.MediaRepository
import com.photo_editor.my_photo_editor.ui.FastScrollerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shared photo grid adapter: date headers, optional memories carousel, selection state.
 * Uses DiffUtil so selection changes only re-bind the overlay instead of reloading images.
 */
class MediaGridAdapter(
    private val listener: Listener
) : ListAdapter<GridItem, RecyclerView.ViewHolder>(DIFF), FastScrollerView.OnPopupTextUpdate {

    interface Listener {
        fun onMediaClick(file: MediaFile, position: Int)
        fun onMediaLongClick(file: MediaFile, position: Int)
        fun onHeaderClick(header: GridItem.Header) {}
        fun onMemoryClick(memory: Memory) {}
    }

    private val popupFormat = SimpleDateFormat("MMM yyyy", Locale.getDefault())

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is GridItem.Header -> TYPE_HEADER
        is GridItem.Media -> TYPE_MEDIA
        is GridItem.Memories -> TYPE_MEMORIES
    }

    fun isFullSpan(position: Int): Boolean =
        position in 0 until itemCount && getItemViewType(position) != TYPE_MEDIA

    fun itemAt(position: Int): GridItem? = if (position in 0 until itemCount) getItem(position) else null

    override fun onChange(position: Int): CharSequence {
        return try {
            when (val item = itemAt(position)) {
                is GridItem.Header -> item.title
                is GridItem.Media -> popupFormat.format(Date(item.file.dateAdded * 1000L))
                else -> ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderVH(inflater.inflate(R.layout.item_grid_header, parent, false))
            TYPE_MEMORIES -> MemoriesVH(inflater.inflate(R.layout.item_memories_row, parent, false))
            else -> MediaVH(inflater.inflate(R.layout.item_media_grid, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is GridItem.Header -> (holder as HeaderVH).bind(item)
            is GridItem.Media -> (holder as MediaVH).bind(item)
            is GridItem.Memories -> (holder as MemoriesVH).bind(item)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_SELECTION)) {
            when (val item = getItem(position)) {
                is GridItem.Media -> (holder as MediaVH).bindSelection(item, animate = true)
                is GridItem.Header -> (holder as HeaderVH).bind(item)
                else -> onBindViewHolder(holder, position)
            }
        } else {
            onBindViewHolder(holder, position)
        }
    }

    // ------------------------------------------------------------------ holders

    inner class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.tvHeader)
        private val count: TextView = view.findViewById(R.id.tvHeaderCount)
        private val select: ImageView = view.findViewById(R.id.iv_header_select)

        fun bind(item: GridItem.Header) {
            title.text = item.title
            count.text = item.subtitle
            select.isVisible = item.selectionMode
            select.setImageResource(if (item.allSelected) R.drawable.ic_g_check_circle else R.drawable.ic_g_circle_outline)
            select.setColorFilter(
                ContextCompat.getColor(
                    itemView.context,
                    if (item.allSelected) R.color.lumina_accent else R.color.lumina_text_secondary
                )
            )
            itemView.setOnClickListener {
                if (item.selectionMode) listener.onHeaderClick(item)
            }
        }
    }

    inner class MediaVH(view: View) : RecyclerView.ViewHolder(view) {
        private val image: ShapeableImageView = view.findViewById(R.id.iv_photo)
        private val duration: TextView = view.findViewById(R.id.tv_duration)
        private val favorite: ImageView = view.findViewById(R.id.iv_favorite)
        private val select: ImageView = view.findViewById(R.id.iv_select)
        private val selectionBg: View = view.findViewById(R.id.selectionBg)
        private val videoScrim: View = view.findViewById(R.id.videoScrim)
        private val corner = view.resources.displayMetrics.density * 12f

        fun bind(item: GridItem.Media) {
            val file = item.file
            image.loadThumbnail(file.uri)
            val isVideo = file.type == MediaType.VIDEO
            duration.isVisible = isVideo
            videoScrim.isVisible = isVideo || file.isFavorite
            if (isVideo) duration.text = MediaRepository.formatDuration(file.duration)
            favorite.isVisible = file.isFavorite
            image.contentDescription = file.name

            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) listener.onMediaClick(file, pos)
            }
            itemView.setOnLongClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) listener.onMediaLongClick(file, pos)
                true
            }
            bindSelection(item, animate = false)
        }

        fun bindSelection(item: GridItem.Media, animate: Boolean) {
            select.isVisible = item.selectionMode
            select.setImageResource(if (item.selected) R.drawable.ic_g_check_circle else R.drawable.ic_g_circle_outline)
            select.setColorFilter(
                ContextCompat.getColor(itemView.context, if (item.selected) R.color.lumina_accent else R.color.white)
            )
            selectionBg.visibility = if (item.selected) View.VISIBLE else View.INVISIBLE
            val scale = if (item.selected) 0.84f else 1f
            image.shapeAppearanceModel = ShapeAppearanceModel.builder()
                .setAllCornerSizes(if (item.selected) corner else 0f)
                .build()
            if (animate) {
                image.animate().scaleX(scale).scaleY(scale).setDuration(140).start()
            } else {
                image.animate().cancel()
                image.scaleX = scale
                image.scaleY = scale
            }
        }
    }

    inner class MemoriesVH(view: View) : RecyclerView.ViewHolder(view) {
        private val list: RecyclerView = view.findViewById(R.id.rvMemories)
        private val adapter = MemoryAdapter { listener.onMemoryClick(it) }

        init {
            list.adapter = adapter
            list.setHasFixedSize(true)
        }

        fun bind(item: GridItem.Memories) {
            adapter.submit(item.memories)
        }
    }

    private class MemoryAdapter(
        private val onClick: (Memory) -> Unit
    ) : RecyclerView.Adapter<MemoryAdapter.VH>() {
        private var items: List<Memory> = emptyList()

        fun submit(newItems: List<Memory>) {
            if (newItems == items) return
            items = newItems
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
        }

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val image: ImageView = view.findViewById(R.id.ivMemory)
            val title: TextView = view.findViewById(R.id.tvMemoryTitle)
            val subtitle: TextView = view.findViewById(R.id.tvMemorySubtitle)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_memory_card, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val memory = items[position]
            holder.image.load(memory.cover.uri) { crossfade(true) }
            holder.title.text = memory.title
            holder.subtitle.text = memory.subtitle
            holder.itemView.setOnClickListener { onClick(memory) }
        }

        override fun getItemCount() = items.size
    }

    companion object {
        const val TYPE_HEADER = 0
        const val TYPE_MEDIA = 1
        const val TYPE_MEMORIES = 2
        private const val PAYLOAD_SELECTION = "selection"

        private val DIFF = object : DiffUtil.ItemCallback<GridItem>() {
            override fun areItemsTheSame(oldItem: GridItem, newItem: GridItem) = oldItem.key == newItem.key
            override fun areContentsTheSame(oldItem: GridItem, newItem: GridItem) = oldItem == newItem
            override fun getChangePayload(oldItem: GridItem, newItem: GridItem): Any? {
                if (oldItem is GridItem.Media && newItem is GridItem.Media && oldItem.file == newItem.file) {
                    return PAYLOAD_SELECTION
                }
                if (oldItem is GridItem.Header && newItem is GridItem.Header && oldItem.title == newItem.title) {
                    return PAYLOAD_SELECTION
                }
                return null
            }
        }
    }
}
