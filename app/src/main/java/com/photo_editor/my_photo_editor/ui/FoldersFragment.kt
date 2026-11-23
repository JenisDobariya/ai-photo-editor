package com.photo_editor.my_photo_editor.ui

import android.content.Intent
import com.photo_editor.my_photo_editor.utils.appColor
import com.photo_editor.my_photo_editor.utils.accentColor
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
import com.photo_editor.my_photo_editor.AlbumDetailActivity
import com.photo_editor.my_photo_editor.CleanerActivity
import com.photo_editor.my_photo_editor.model.ThingCollection
import com.photo_editor.my_photo_editor.repository.SmartIndexRepository
import com.photo_editor.my_photo_editor.HiddenMediaActivity
import com.photo_editor.my_photo_editor.PhotoDetailActivity
import com.photo_editor.my_photo_editor.R
import com.photo_editor.my_photo_editor.TrashActivity
import com.photo_editor.my_photo_editor.databinding.FragmentMediaGridBinding
import com.photo_editor.my_photo_editor.model.AlbumSort
import com.photo_editor.my_photo_editor.model.MediaFile
import com.photo_editor.my_photo_editor.model.MediaType
import com.photo_editor.my_photo_editor.utils.AnalyticsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*

import coil.request.videoFrameMicros
import com.photo_editor.my_photo_editor.ui.gallery.loadThumbnail

class FoldersFragment : Fragment() {

    private var _binding: FragmentMediaGridBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by activityViewModels()

    private lateinit var albumAdapter: AlbumAdapter

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
        albumAdapter = AlbumAdapter(
            onAlbumClick = { albumItem ->
                AnalyticsManager.logEvent("Folders_album_click")
                val intent = Intent(requireContext(), AlbumDetailActivity::class.java).apply {
                    putExtra("ALBUM_NAME", albumItem.name)
                }
                startActivity(intent)
            },
            onSmartClick = { smart -> openSmartCollection(smart) },
            onThingClick = { thing ->
                AnalyticsManager.logEvent("Folders_thing_click")
                startActivity(
                    Intent(requireContext(), AlbumDetailActivity::class.java)
                        .putExtra("ALBUM_NAME", thing.title)
                        .putStringArrayListExtra(PhotoDetailActivity.EXTRA_URI_LIST, ArrayList(thing.uris.map { it.toString() }))
                )
            }
        )

        binding.fastScroller.isVisible = false
        binding.recyclerView.apply {
            layoutManager = GridLayoutManager(requireContext(), 2).apply {
                spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                    override fun getSpanSize(position: Int): Int =
                        if (albumAdapter.getItemViewType(position) == AlbumAdapter.TYPE_ALBUM) 1 else 2
                }
            }
            adapter = albumAdapter
            clipToPadding = false
            setPadding(10, 0, 10, 24)
            setItemViewCacheSize(20)
        }
    }

    private fun openSmartCollection(smart: SmartCollection) {
        AnalyticsManager.logEvent("Folders_smart_${smart.kind.name.lowercase()}")
        val context = requireContext()
        val intent = when (smart.kind) {
            SmartKind.FAVORITES -> Intent(context, AlbumDetailActivity::class.java)
                .putExtra("ALBUM_NAME", "Favorites")
            SmartKind.VIDEOS -> Intent(context, AlbumDetailActivity::class.java)
                .putExtra("ALBUM_NAME", "Videos")
                .putExtra(PhotoDetailActivity.EXTRA_SMART_ALBUM, PhotoDetailActivity.SMART_VIDEOS)
            SmartKind.SCREENSHOTS -> Intent(context, AlbumDetailActivity::class.java)
                .putExtra("ALBUM_NAME", "Screenshots")
                .putExtra(PhotoDetailActivity.EXTRA_SMART_ALBUM, PhotoDetailActivity.SMART_SCREENSHOTS)
            SmartKind.TRASH -> Intent(context, TrashActivity::class.java)
            SmartKind.LOCKED -> Intent(context, HiddenMediaActivity::class.java)
            SmartKind.CLEANER -> CleanerActivity.intent(context)
        }
        startActivity(intent)
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setColorSchemeColors(requireContext().accentColor)
        binding.swipeRefresh.setProgressBackgroundColorSchemeResource(R.color.lumina_surface_high)
        binding.swipeRefresh.setOnRefreshListener {
            viewModel.loadMedia()
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(
                        viewModel.libraryMedia, viewModel.albumSort, viewModel.searchQuery,
                        viewModel.things,
                        // Progress ticks per photo; refresh the list only every 25 photos.
                        viewModel.indexProgress
                            .map { p -> if (p.running) p.copy(done = p.done - p.done % 25) else p }
                            .distinctUntilChanged()
                    ) { media, sort, query, things, progress ->
                        AlbumsInput(media, sort, query, things, progress)
                    }.collectLatest { input ->
                        processAndShowAlbums(input)
                    }
                }
                launch {
                    viewModel.isLoading.collectLatest { loading ->
                        binding.swipeRefresh.isRefreshing = loading
                        if (!loading && viewModel.searchQuery.value.isBlank()) {
                            binding.emptyStateView.isVisible = viewModel.libraryMedia.value.isEmpty()
                        }
                    }
                }
            }
        }
    }

    private data class AlbumsInput(
        val media: List<MediaFile>,
        val sort: AlbumSort,
        val query: String,
        val things: List<ThingCollection>,
        val progress: SmartIndexRepository.Progress
    )

    private suspend fun processAndShowAlbums(input: AlbumsInput) {
        val allMedia = input.media
        val sort = input.sort
        val query = input.query
        val rows = withContext(Dispatchers.Default) {
            val items = mutableListOf<Row>()

            if (query.isBlank()) {
                val favorites = allMedia.filter { it.isFavorite }
                val videos = allMedia.filter { it.type == MediaType.VIDEO }
                val screenshots = allMedia.filter { it.isScreenshot }
                items.add(Row.Section("Collections", null))
                items.add(
                    Row.Smart(
                        listOf(
                            SmartCollection(SmartKind.FAVORITES, "Favorites", countText(favorites.size), R.drawable.ic_g_heart, R.color.lumina_favorite),
                            SmartCollection(SmartKind.VIDEOS, "Videos", countText(videos.size), R.drawable.ic_g_video, R.color.lumina_teal),
                            SmartCollection(SmartKind.SCREENSHOTS, "Screenshots", countText(screenshots.size), R.drawable.ic_g_screenshot, R.color.lumina_accent),
                            SmartCollection(SmartKind.CLEANER, "Clean up", "Free space", R.drawable.ic_g_cleanup, R.color.lumina_teal),
                            SmartCollection(SmartKind.LOCKED, "Locked Folder", "Private", R.drawable.ic_g_lock, R.color.lumina_gold),
                            SmartCollection(SmartKind.TRASH, "Recycle Bin", "30 days", R.drawable.ic_g_delete, R.color.lumina_danger)
                        )
                    )
                )

                // On-device "Things" albums (Dogs, Food, Beach…) from image labels.
                val progress = input.progress
                if (input.things.isNotEmpty()) {
                    val hint = if (progress.running) "Analysing ${progress.done}/${progress.total}" else "${input.things.size} found"
                    items.add(Row.Section("Things", hint))
                    items.add(Row.Things(input.things))
                } else if (progress.running && progress.total > 0) {
                    items.add(Row.Section("Things", "Analysing ${progress.done}/${progress.total}"))
                }
            }

            val albumGroups = allMedia.groupBy { it.album }
            val albums = albumGroups.map { (name, files) ->
                AlbumItem(
                    name = name,
                    coverUri = files.first().uri,
                    count = files.size,
                    videoCount = files.count { it.type == MediaType.VIDEO },
                    lastUpdated = files.maxOfOrNull { it.dateAdded } ?: 0L
                )
            }.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }
                .let { list ->
                    when (sort) {
                        AlbumSort.RECENT -> list.sortedByDescending { it.lastUpdated }
                        AlbumSort.NAME -> list.sortedBy { it.name.lowercase(Locale.getDefault()) }
                        AlbumSort.COUNT -> list.sortedByDescending { it.count }
                    }
                }

            if (albums.isNotEmpty()) {
                items.add(Row.Section("Albums", if (albums.size == 1) "1 album" else "${albums.size} albums"))
                albums.forEach { items.add(Row.Album(it)) }
            }
            items to albums.isEmpty()
        }

        if (_binding == null) return
        binding.emptyStateView.isVisible = rows.second && (query.isNotBlank() || allMedia.isEmpty()) && !viewModel.isLoading.value
        albumAdapter.updateRows(rows.first)
    }

    private fun countText(count: Int) = if (count == 1) "1 item" else "$count items"

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    data class AlbumItem(
        val name: String,
        val coverUri: android.net.Uri,
        val count: Int,
        val isFavoritesFolder: Boolean = false,
        val videoCount: Int = 0,
        val lastUpdated: Long = 0L
    )

    enum class SmartKind { FAVORITES, VIDEOS, SCREENSHOTS, CLEANER, LOCKED, TRASH }

    data class SmartCollection(
        val kind: SmartKind,
        val title: String,
        val subtitle: String,
        val icon: Int,
        val tint: Int
    )

    sealed class Row {
        data class Section(val title: String, val count: String?) : Row()
        data class Smart(val items: List<SmartCollection>) : Row()
        data class Things(val items: List<ThingCollection>) : Row()
        data class Album(val album: AlbumItem) : Row()
    }

    class AlbumAdapter(
        private val onAlbumClick: (AlbumItem) -> Unit,
        private val onSmartClick: (SmartCollection) -> Unit,
        private val onThingClick: (ThingCollection) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private var rows: List<Row> = emptyList()

        fun updateRows(newRows: List<Row>) {
            rows = newRows
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int = when (rows[position]) {
            is Row.Section -> TYPE_SECTION
            is Row.Smart -> TYPE_SMART
            is Row.Things -> TYPE_THINGS
            is Row.Album -> TYPE_ALBUM
        }

        class ThingsRowViewHolder(view: View, onClick: (ThingCollection) -> Unit) : RecyclerView.ViewHolder(view) {
            val adapter = ThingAdapter(onClick)
            init {
                (view as RecyclerView).adapter = adapter
            }
        }

        class SectionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(R.id.tvSectionTitle)
            val count: TextView = view.findViewById(R.id.tvSectionCount)
        }

        class SmartRowViewHolder(view: View, onClick: (SmartCollection) -> Unit) : RecyclerView.ViewHolder(view) {
            val adapter = SmartAdapter(onClick)
            init {
                (view as RecyclerView).adapter = adapter
            }
        }

        class AlbumViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val imageView: ImageView = view.findViewById(R.id.ivAlbumCover)
            val nameView: TextView = view.findViewById(R.id.tvAlbumName)
            val countView: TextView = view.findViewById(R.id.tvAlbumCount)
            val badgeView: TextView = view.findViewById(R.id.tvAlbumBadge)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                TYPE_SECTION -> SectionViewHolder(inflater.inflate(R.layout.item_section_header, parent, false))
                TYPE_SMART -> SmartRowViewHolder(inflater.inflate(R.layout.item_smart_row, parent, false), onSmartClick)
                TYPE_THINGS -> ThingsRowViewHolder(inflater.inflate(R.layout.item_smart_row, parent, false), onThingClick)
                else -> AlbumViewHolder(inflater.inflate(R.layout.item_album, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = rows[position]) {
                is Row.Section -> (holder as SectionViewHolder).apply {
                    title.text = row.title
                    count.text = row.count
                    count.isVisible = row.count != null
                }
                is Row.Smart -> (holder as SmartRowViewHolder).adapter.submit(row.items)
                is Row.Things -> (holder as ThingsRowViewHolder).adapter.submit(row.items)
                is Row.Album -> (holder as AlbumViewHolder).apply {
                    val album = row.album
                    nameView.text = album.name
                    countView.text = if (album.count == 1) "1 item" else String.format(Locale.getDefault(), "%,d items", album.count)
                    badgeView.isVisible = album.videoCount > 0
                    badgeView.text = album.videoCount.toString()
                    imageView.loadThumbnail(album.coverUri)
                    itemView.setOnClickListener { onAlbumClick(album) }
                }
            }
        }

        override fun getItemCount() = rows.size

        companion object {
            const val TYPE_SECTION = 0
            const val TYPE_SMART = 1
            const val TYPE_ALBUM = 2
            const val TYPE_THINGS = 3
        }
    }

    class ThingAdapter(
        private val onClick: (ThingCollection) -> Unit
    ) : RecyclerView.Adapter<ThingAdapter.VH>() {
        private var items: List<ThingCollection> = emptyList()

        fun submit(newItems: List<ThingCollection>) {
            if (newItems == items) return
            items = newItems
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
        }

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val cover: ImageView = view.findViewById(R.id.ivThingCover)
            val title: TextView = view.findViewById(R.id.tvThingTitle)
            val count: TextView = view.findViewById(R.id.tvThingCount)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_thing_card, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.cover.loadThumbnail(item.cover)
            holder.title.text = item.title
            holder.count.text = if (item.uris.size == 1) "1 photo" else "${item.uris.size} photos"
            holder.itemView.setOnClickListener { onClick(item) }
        }

        override fun getItemCount() = items.size
    }

    class SmartAdapter(
        private val onClick: (SmartCollection) -> Unit
    ) : RecyclerView.Adapter<SmartAdapter.VH>() {
        private var items: List<SmartCollection> = emptyList()

        fun submit(newItems: List<SmartCollection>) {
            if (newItems == items) return
            items = newItems
            @Suppress("NotifyDataSetChanged")
            notifyDataSetChanged()
        }

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.ivSmartIcon)
            val title: TextView = view.findViewById(R.id.tvSmartTitle)
            val count: TextView = view.findViewById(R.id.tvSmartCount)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_smart_album, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            holder.icon.setImageResource(item.icon)
            holder.icon.setColorFilter(holder.itemView.context.appColor(item.tint))
            holder.title.text = item.title
            holder.count.text = item.subtitle
            holder.itemView.setOnClickListener { onClick(item) }
        }

        override fun getItemCount() = items.size
    }
}
