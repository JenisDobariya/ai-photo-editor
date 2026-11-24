package com.photo_editor.my_photo_editor.ui

import android.content.Intent
import com.photo_editor.my_photo_editor.utils.accentColor
import android.net.Uri
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import com.photo_editor.my_photo_editor.PhotoDetailActivity
import com.photo_editor.my_photo_editor.R
import com.photo_editor.my_photo_editor.databinding.FragmentPhotosBinding
import com.photo_editor.my_photo_editor.model.MediaFile
import com.photo_editor.my_photo_editor.model.MediaFilter
import com.photo_editor.my_photo_editor.model.MediaType
import com.photo_editor.my_photo_editor.model.Memory
import com.photo_editor.my_photo_editor.model.SortOrder
import com.photo_editor.my_photo_editor.ui.gallery.DragSelectTouchListener
import com.photo_editor.my_photo_editor.ui.gallery.GridItem
import com.photo_editor.my_photo_editor.ui.gallery.MediaGridAdapter
import com.photo_editor.my_photo_editor.ui.gallery.MediaGrouping
import com.photo_editor.my_photo_editor.ui.gallery.PinchToZoomGridListener
import com.photo_editor.my_photo_editor.utils.AnalyticsManager
import com.photo_editor.my_photo_editor.utils.scaledGridColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PhotosFragment : Fragment(), MediaGridAdapter.Listener {

    private var _binding: FragmentPhotosBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()
    private val selectionViewModel: MediaSelectionViewModel by activityViewModels()

    private lateinit var galleryAdapter: MediaGridAdapter
    private lateinit var layoutManager: GridLayoutManager
    private lateinit var dragSelect: DragSelectTouchListener

    // Drag-select bookkeeping
    private var dragBaseSelection: Set<Uri> = emptySet()
    private var dragSelects = true

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPhotosBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupChips()
        setupSwipeRefresh()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        galleryAdapter = MediaGridAdapter(this)
        // User's "N per row" on phones; proportionally more columns on tablets / landscape.
        layoutManager = GridLayoutManager(requireContext(), requireContext().scaledGridColumns(viewModel.gridColumns.value)).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    return if (galleryAdapter.isFullSpan(position)) spanCount else 1
                }
            }
            isItemPrefetchEnabled = true
        }

        binding.recyclerView.apply {
            this.layoutManager = this@PhotosFragment.layoutManager
            this.adapter = galleryAdapter
            setHasFixedSize(true)
            setItemViewCacheSize(30)
            itemAnimator?.changeDuration = 120
        }

        dragSelect = DragSelectTouchListener { start, end -> onDragRange(start, end) }
        dragSelect.attach(binding.recyclerView)
        binding.recyclerView.addOnItemTouchListener(
            PinchToZoomGridListener(requireContext()) { delta ->
                viewModel.setGridColumns(viewModel.gridColumns.value + delta)
            }
        )

        binding.fastScroller.attachFastScrollerToRecyclerView(binding.recyclerView)
    }

    private fun setupChips() {
        binding.chipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            val filter = when (checkedIds.firstOrNull()) {
                R.id.chipPhotos -> MediaFilter.PHOTOS
                R.id.chipVideos -> MediaFilter.VIDEOS
                R.id.chipFavorites -> MediaFilter.FAVORITES
                R.id.chipScreenshots -> MediaFilter.SCREENSHOTS
                else -> MediaFilter.ALL
            }
            AnalyticsManager.logEvent("Photos_filter_${filter.name.lowercase()}")
            viewModel.setFilter(filter)
            binding.recyclerView.scrollToPosition(0)
        }
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setColorSchemeColors(requireContext().accentColor)
        binding.swipeRefresh.setProgressBackgroundColorSchemeResource(R.color.lumina_surface_high)
        binding.swipeRefresh.setOnRefreshListener {
            viewModel.loadMedia()
        }
    }

    private fun observeViewModel() {
        val displayOptions = combine(viewModel.groupBy, viewModel.sortOrder, viewModel.showMemories) { g, s, m ->
            Triple(g, s, m)
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(
                        viewModel.visibleMedia,
                        displayOptions,
                        selectionViewModel.selection,
                        viewModel.memories
                    ) { media, options, selection, memories ->
                        PhotosState(media, options.first, options.second, options.third, selection, memories)
                    }.collectLatest { state -> processAndShowPhotos(state) }
                }
                launch {
                    viewModel.isLoading.collectLatest { loading ->
                        binding.swipeRefresh.isRefreshing = loading
                        // An empty library emits the same empty list before and after loading,
                        // so refresh the empty state when loading finishes.
                        if (!loading) updateEmptyState(viewModel.visibleMedia.value.isEmpty())
                    }
                }
                launch {
                    viewModel.gridColumns.collectLatest { columns ->
                        val span = requireContext().scaledGridColumns(columns)
                        if (layoutManager.spanCount != span) {
                            layoutManager.spanCount = span
                            galleryAdapter.notifyItemRangeChanged(0, galleryAdapter.itemCount)
                        }
                    }
                }
                launch {
                    selectionViewModel.selection.collectLatest { selection ->
                        binding.swipeRefresh.isEnabled = selection.isEmpty()
                        binding.chipScroll.alpha = if (selection.isEmpty()) 1f else 0.4f
                        setChipsEnabled(selection.isEmpty())
                    }
                }
            }
        }
    }

    private data class PhotosState(
        val media: List<MediaFile>,
        val groupBy: com.photo_editor.my_photo_editor.model.GroupBy,
        val sortOrder: SortOrder,
        val showMemories: Boolean,
        val selection: Set<Uri>,
        val memories: List<Memory>
    )

    private suspend fun processAndShowPhotos(state: PhotosState) {
        val showMemories = state.showMemories &&
                viewModel.filter.value == MediaFilter.ALL &&
                viewModel.searchQuery.value.isBlank() &&
                state.sortOrder == SortOrder.DATE_DESC
        val galleryItems = withContext(Dispatchers.Default) {
            MediaGrouping.build(
                media = state.media,
                groupBy = state.groupBy,
                sortOrder = state.sortOrder,
                selection = state.selection,
                memories = if (showMemories) state.memories else emptyList()
            )
        }
        if (_binding == null) return
        updateEmptyState(state.media.isEmpty())
        // New search text should show results from the top, not wherever the grid was scrolled.
        val query = viewModel.searchQuery.value.trim()
        val queryChanged = query != lastShownQuery
        lastShownQuery = query
        galleryAdapter.submitList(galleryItems) {
            if (queryChanged) _binding?.recyclerView?.scrollToPosition(0)
        }
    }

    private var lastShownQuery = ""

    private fun updateEmptyState(isEmpty: Boolean) {
        binding.emptyStateView.isVisible = isEmpty && !viewModel.isLoading.value
        if (!isEmpty) return
        val query = viewModel.searchQuery.value
        when {
            query.isNotBlank() -> {
                binding.ivEmptyIcon.setImageResource(R.drawable.ic_g_search)
                binding.tvEmptyTitle.text = "No results for “$query”"
                binding.tvEmptySubtitle.text = "Try a file name, album, month (\"march\"), year or \"video\""
            }
            viewModel.filter.value == MediaFilter.FAVORITES -> {
                binding.ivEmptyIcon.setImageResource(R.drawable.ic_g_heart)
                binding.tvEmptyTitle.text = "No favorites yet"
                binding.tvEmptySubtitle.text = "Tap the heart on any photo to keep it here"
            }
            viewModel.filter.value == MediaFilter.VIDEOS -> {
                binding.ivEmptyIcon.setImageResource(R.drawable.ic_g_video)
                binding.tvEmptyTitle.text = "No videos"
                binding.tvEmptySubtitle.text = "Videos you record will show up here"
            }
            viewModel.filter.value == MediaFilter.SCREENSHOTS -> {
                binding.ivEmptyIcon.setImageResource(R.drawable.ic_g_screenshot)
                binding.tvEmptyTitle.text = "No screenshots"
                binding.tvEmptySubtitle.text = "Screenshots are collected here automatically"
            }
            else -> {
                binding.ivEmptyIcon.setImageResource(R.drawable.ic_g_photo)
                binding.tvEmptyTitle.text = "No photos or videos found"
                binding.tvEmptySubtitle.text = "Photos you take or download will appear here"
            }
        }
    }

    private fun setChipsEnabled(enabled: Boolean) {
        for (i in 0 until binding.chipGroup.childCount) {
            binding.chipGroup.getChildAt(i).isEnabled = enabled
        }
    }

    /** Called from the toolbar menu: selects the first visible item to enter selection mode. */
    fun startSelectionMode() {
        val first = (0 until galleryAdapter.itemCount)
            .firstNotNullOfOrNull { (galleryAdapter.itemAt(it) as? GridItem.Media)?.file }
        if (first != null) selectionViewModel.setSelection(setOf(first.uri))
    }

    // ------------------------------------------------------------------ adapter callbacks

    override fun onMediaClick(file: MediaFile, position: Int) {
        if (selectionViewModel.isSelectionMode) {
            selectionViewModel.toggle(file.uri)
            return
        }
        AnalyticsManager.logEvent("Photos_media_click")
        val intent = Intent(requireContext(), PhotoDetailActivity::class.java).apply {
            putExtra("IMAGE_URI", file.uri.toString())
            putExtra("IS_VIDEO", file.type == MediaType.VIDEO)
            val visible = viewModel.visibleMedia.value
            if (viewModel.isFilterActive && visible.size <= PhotoDetailActivity.MAX_URIS_IN_INTENT) {
                putStringArrayListExtra(PhotoDetailActivity.EXTRA_URI_LIST, ArrayList(visible.map { it.uri.toString() }))
            }
        }
        startActivity(intent)
    }

    override fun onMediaLongClick(file: MediaFile, position: Int) {
        binding.recyclerView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        dragBaseSelection = selectionViewModel.selection.value
        dragSelects = !dragBaseSelection.contains(file.uri)
        dragSelect.startDragSelection(position)
    }

    private fun onDragRange(start: Int, end: Int) {
        val rangeUris = (start..end).mapNotNull { (galleryAdapter.itemAt(it) as? GridItem.Media)?.file?.uri }
        selectionViewModel.setSelection(
            if (dragSelects) dragBaseSelection + rangeUris else dragBaseSelection - rangeUris.toSet()
        )
    }

    override fun onHeaderClick(header: GridItem.Header) {
        selectionViewModel.select(header.uris, !header.allSelected)
    }

    override fun onMemoryClick(memory: Memory) {
        if (selectionViewModel.isSelectionMode) return
        AnalyticsManager.logEvent("Photos_memory_click")
        val intent = Intent(requireContext(), PhotoDetailActivity::class.java).apply {
            putExtra("IMAGE_URI", memory.cover.uri.toString())
            putStringArrayListExtra(PhotoDetailActivity.EXTRA_URI_LIST, ArrayList(memory.uris.map { it.toString() }))
            putExtra(PhotoDetailActivity.EXTRA_TITLE, memory.title)
        }
        startActivity(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
