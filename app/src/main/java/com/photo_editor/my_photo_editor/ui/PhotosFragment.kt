package com.photo_editor.my_photo_editor.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.photo_editor.my_photo_editor.PhotoDetailActivity
import com.photo_editor.my_photo_editor.R
import com.photo_editor.my_photo_editor.databinding.FragmentMediaGridBinding
import com.photo_editor.my_photo_editor.model.MediaFile
import com.photo_editor.my_photo_editor.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

import coil.request.videoFrameMicros

class PhotosFragment : Fragment() {

    private var _binding: FragmentMediaGridBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    private lateinit var galleryAdapter: GalleryAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMediaGridBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupSwipeRefresh()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        val layoutManager = GridLayoutManager(requireContext(), 5).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    return if (galleryAdapter.getItemViewType(position) == GalleryAdapter.TYPE_HEADER) 5 else 1
                }
            }
            isItemPrefetchEnabled = true
        }

        galleryAdapter = GalleryAdapter(emptyList()) { media ->
            val intent = Intent(requireContext(), PhotoDetailActivity::class.java).apply {
                putExtra("IMAGE_URI", media.uri.toString())
                putExtra("IS_VIDEO", media.type == MediaType.VIDEO)
            }
            startActivity(intent)
        }

        binding.recyclerView.apply {
            this.layoutManager = layoutManager
            this.adapter = galleryAdapter
            setHasFixedSize(true)
            setItemViewCacheSize(30)
        }

        binding.fastScroller.attachFastScrollerToRecyclerView(binding.recyclerView)
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            viewModel.loadMedia()
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.allMedia.collectLatest { photos ->
                        processAndShowPhotos(photos)
                    }
                }
                launch {
                    viewModel.isLoading.collectLatest { loading ->
                        binding.swipeRefresh.isRefreshing = loading
                    }
                }
            }
        }
    }

    private suspend fun processAndShowPhotos(photos: List<MediaFile>) {
        val galleryItems = withContext(Dispatchers.Default) {
            val items = mutableListOf<GalleryItem>()
            val dateFormat = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
            
            val grouped = photos.groupBy { 
                dateFormat.format(Date(it.dateAdded * 1000L))
            }

            grouped.forEach { (date, files) ->
                items.add(GalleryItem.Header(date))
                files.forEach { items.add(GalleryItem.Media(it)) }
            }
            items
        }

        binding.emptyStateView.isVisible = galleryItems.isEmpty()
        galleryAdapter.updateItems(galleryItems)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private sealed class GalleryItem {
        data class Header(val date: String) : GalleryItem()
        data class Media(val file: MediaFile) : GalleryItem()
    }

    private class GalleryAdapter(
        private var items: List<GalleryItem>,
        private val onClick: (MediaFile) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>(), FastScrollerView.OnPopupTextUpdate {

        companion object {
            const val TYPE_HEADER = 0
            const val TYPE_MEDIA = 1
        }

        fun updateItems(newItems: List<GalleryItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onChange(position: Int): CharSequence {
            return try {
                when (val item = items.getOrNull(position)) {
                    is GalleryItem.Header -> item.date
                    is GalleryItem.Media -> {
                        val dateFormat = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
                        dateFormat.format(Date(item.file.dateAdded * 1000L))
                    }
                    else -> ""
                }
            } catch (_: Exception) {
                ""
            }
        }

        override fun getItemViewType(position: Int): Int {
            return if (items[position] is GalleryItem.Header) TYPE_HEADER else TYPE_MEDIA
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == TYPE_HEADER) {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_gallery_header, parent, false)
                HeaderViewHolder(view)
            } else {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_photo, parent, false)
                MediaViewHolder(view)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is GalleryItem.Header -> (holder as HeaderViewHolder).bind(item.date)
                is GalleryItem.Media -> (holder as MediaViewHolder).bind(item.file, onClick)
            }
        }

        override fun getItemCount() = items.size

        class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val tvHeader: TextView = view.findViewById(R.id.tvHeader)
            fun bind(date: String) { tvHeader.text = date }
        }

        class MediaViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val imageView: ImageView = view.findViewById(R.id.iv_photo)
            private val durationView: TextView = view.findViewById(R.id.tv_duration)
            private val labelView: TextView = view.findViewById(R.id.tv_label)
            private val favoriteView: ImageView = view.findViewById(R.id.iv_favorite)

            fun bind(file: MediaFile, onClick: (MediaFile) -> Unit) {
                labelView.isVisible = false
                imageView.load(file.uri) {
                    crossfade(true)
                    if (file.type == MediaType.VIDEO) {
                        videoFrameMicros(1000000)
                    }
                }
                favoriteView.isVisible = file.isFavorite
                if (file.type == MediaType.VIDEO) {
                    durationView.isVisible = true
                    durationView.text = formatDuration(file.duration)
                } else {
                    durationView.isVisible = false
                }
                itemView.setOnClickListener { onClick(file) }
            }

            private fun formatDuration(durationMs: Long): String {
                val seconds = (durationMs / 1000) % 60
                val minutes = (durationMs / (1000 * 60)) % 60
                return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
            }
        }
    }
}
