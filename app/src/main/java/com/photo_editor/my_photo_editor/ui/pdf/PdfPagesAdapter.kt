package com.photo_editor.my_photo_editor.ui.pdf

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.photo_editor.my_photo_editor.databinding.ItemPdfAddBinding
import com.photo_editor.my_photo_editor.databinding.ItemPdfOptionsBinding
import com.photo_editor.my_photo_editor.databinding.ItemPdfPageBinding
import com.photo_editor.my_photo_editor.ui.gallery.loadThumbnail

/**
 * Grid of PDF pages followed by an "Add photos" tile and the options card (full width).
 * Pages are reordered in place while dragging ([move]) and the final order is read from [pages].
 */
class PdfPagesAdapter(private val listener: Listener) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    interface Listener {
        fun onRemovePage(page: PhotosToPdfViewModel.Page)
        fun onAddPhotos()
        /** Called once per options view holder: wire up click listeners. */
        fun onOptionsCreated(binding: ItemPdfOptionsBinding)
        /** Called whenever the options card should show the current state. */
        fun onOptionsBind(binding: ItemPdfOptionsBinding)
    }

    private val _pages = mutableListOf<PhotosToPdfViewModel.Page>()
    val pages: List<PhotosToPdfViewModel.Page> get() = _pages

    init {
        setHasStableIds(true)
    }

    fun submit(newPages: List<PhotosToPdfViewModel.Page>) {
        if (newPages == _pages) return
        if (_pages.isEmpty()) {
            // First photos: a full refresh, so the grid shows page 1 at the top. With an
            // insert-at-0 the list keeps the "Add photos" tile anchored and the new pages
            // end up scrolled out of view above it.
            _pages.addAll(newPages)
            notifyDataSetChanged()
            return
        }
        val old = ArrayList(_pages)
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = newPages.size
            override fun areItemsTheSame(o: Int, n: Int) = old[o].id == newPages[n].id
            override fun areContentsTheSame(o: Int, n: Int) = old[o] == newPages[n]
        })
        _pages.clear()
        _pages.addAll(newPages)
        diff.dispatchUpdatesTo(this)
        refreshPageNumbers()
    }

    fun move(from: Int, to: Int) {
        if (!isPage(from) || !isPage(to)) return
        val item = _pages.removeAt(from)
        _pages.add(to, item)
        notifyItemMoved(from, to)
    }

    fun refreshPageNumbers() {
        if (_pages.isNotEmpty()) notifyItemRangeChanged(0, _pages.size, PAYLOAD_NUMBER)
    }

    fun refreshOptions() = notifyItemChanged(optionsPosition, PAYLOAD_OPTIONS)

    fun isPage(position: Int) = position in _pages.indices
    fun isFullSpan(position: Int) = getItemViewType(position) == TYPE_OPTIONS

    private val addPosition get() = _pages.size
    private val optionsPosition get() = _pages.size + 1

    override fun getItemCount() = _pages.size + 2

    override fun getItemViewType(position: Int) = when {
        position < _pages.size -> TYPE_PAGE
        position == addPosition -> TYPE_ADD
        else -> TYPE_OPTIONS
    }

    override fun getItemId(position: Int): Long = when (getItemViewType(position)) {
        TYPE_PAGE -> _pages[position].id
        TYPE_ADD -> ID_ADD
        else -> ID_OPTIONS
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_PAGE -> PageHolder(ItemPdfPageBinding.inflate(inflater, parent, false))
            TYPE_ADD -> AddHolder(ItemPdfAddBinding.inflate(inflater, parent, false)).also { holder ->
                holder.binding.root.setOnClickListener { listener.onAddPhotos() }
            }
            else -> OptionsHolder(ItemPdfOptionsBinding.inflate(inflater, parent, false)).also { holder ->
                listener.onOptionsCreated(holder.binding)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is PageHolder -> {
                val page = _pages[position]
                holder.binding.ivPage.loadThumbnail(page.uri)
                holder.binding.tvPageNumber.text = (position + 1).toString()
                holder.binding.btnRemovePage.setOnClickListener {
                    val pos = holder.bindingAdapterPosition
                    if (isPage(pos)) listener.onRemovePage(_pages[pos])
                }
            }
            is OptionsHolder -> listener.onOptionsBind(holder.binding)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) {
            onBindViewHolder(holder, position)
            return
        }
        when (holder) {
            is PageHolder -> if (payloads.contains(PAYLOAD_NUMBER)) {
                holder.binding.tvPageNumber.text = (position + 1).toString()
            }
            is OptionsHolder -> listener.onOptionsBind(holder.binding)
        }
    }

    class PageHolder(val binding: ItemPdfPageBinding) : RecyclerView.ViewHolder(binding.root)
    class AddHolder(val binding: ItemPdfAddBinding) : RecyclerView.ViewHolder(binding.root)
    class OptionsHolder(val binding: ItemPdfOptionsBinding) : RecyclerView.ViewHolder(binding.root)

    companion object {
        const val TYPE_PAGE = 0
        const val TYPE_ADD = 1
        const val TYPE_OPTIONS = 2
        private const val ID_ADD = -1L
        private const val ID_OPTIONS = -2L
        private const val PAYLOAD_NUMBER = "number"
        private const val PAYLOAD_OPTIONS = "options"
    }
}
