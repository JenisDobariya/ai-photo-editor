package com.photo_editor.my_photo_editor

import com.photo_editor.my_photo_editor.ui.AppDialog
import com.photo_editor.my_photo_editor.utils.accentColor
import android.content.ContentUris
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import com.photo_editor.my_photo_editor.databinding.ActivityAlbumDetailBinding
import com.photo_editor.my_photo_editor.model.GroupBy
import com.photo_editor.my_photo_editor.model.MediaFile
import com.photo_editor.my_photo_editor.model.MediaType
import com.photo_editor.my_photo_editor.model.SortOrder
import com.photo_editor.my_photo_editor.ui.MediaSelectionViewModel
import com.photo_editor.my_photo_editor.ui.SelectionMoreMenu
import com.photo_editor.my_photo_editor.ui.gallery.DragSelectTouchListener
import com.photo_editor.my_photo_editor.ui.gallery.GridItem
import com.photo_editor.my_photo_editor.ui.gallery.MediaGridAdapter
import com.photo_editor.my_photo_editor.ui.gallery.MediaGrouping
import com.photo_editor.my_photo_editor.ui.gallery.PinchToZoomGridListener
import com.photo_editor.my_photo_editor.utils.AnalyticsManager
import com.photo_editor.my_photo_editor.utils.GalleryPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*

import com.photo_editor.my_photo_editor.db.AppDatabase

import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

class AlbumDetailActivity : BaseActivity(), MediaGridAdapter.Listener {

    override fun getScreenTag(): String = "AlbumDetail"

    private lateinit var binding: ActivityAlbumDetailBinding
    private var albumName: String? = null
    private var smartAlbum: String? = null
    /** Set for virtual albums (e.g. "Things" like Dogs/Food): only these uris are shown. */
    private var uriFilter: Set<String>? = null
    private val db by lazy { AppDatabase.getDatabase(this) }
    private val prefs by lazy { GalleryPreferences(this) }
    private val selectionViewModel: MediaSelectionViewModel by viewModels()
    private var refreshJob: Job? = null
    private lateinit var galleryAdapter: MediaGridAdapter
    private lateinit var layoutManager: GridLayoutManager
    private lateinit var dragSelect: DragSelectTouchListener
    private var albumMedia: List<MediaFile> = emptyList()
    private var dragBaseSelection: Set<Uri> = emptySet()
    private var dragSelects = true
    private var pendingAction: MediaSelectionViewModel.PendingAction? = null

    private val intentSenderLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val action = pendingAction ?: return@registerForActivityResult
        pendingAction = null
        selectionViewModel.onIntentSenderResult(action, result.resultCode == RESULT_OK)
    }

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            refreshJob?.cancel()
            refreshJob = lifecycleScope.launch {
                delay(500)
                loadAlbumMedia()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAlbumDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        albumName = intent.getStringExtra("ALBUM_NAME")
        smartAlbum = intent.getStringExtra(PhotoDetailActivity.EXTRA_SMART_ALBUM)
        uriFilter = intent.getStringArrayListExtra(PhotoDetailActivity.EXTRA_URI_LIST)?.toHashSet()
        
        setupUI()
        setupRecyclerView()
        setupSwipeRefresh()
        setupSelection()

        contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
        contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
    }

    override fun onDestroy() {
        super.onDestroy()
        contentResolver.unregisterContentObserver(observer)
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setColorSchemeColors(this.accentColor)
        binding.swipeRefresh.setProgressBackgroundColorSchemeResource(R.color.lumina_surface_high)
        binding.swipeRefresh.setOnRefreshListener {
            loadAlbumMedia()
        }
    }

    override fun onResume() {
        super.onResume()
        loadAlbumMedia()
    }

    private fun setupUI() {
        binding.headerTitle.text = albumName ?: getString(R.string.nav_folders)
        binding.btnBackHeader.setOnClickListener {
            AnalyticsManager.logEvent("AlbumDetail_btnBackHeader_click")
            finish()
        }
        binding.btnSlideshow.setOnClickListener {
            AnalyticsManager.logEvent("AlbumDetail_btnSlideshow_click")
            val first = albumMedia.firstOrNull { it.type == MediaType.IMAGE } ?: return@setOnClickListener
            startActivity(detailIntent(first).putExtra(PhotoDetailActivity.EXTRA_START_SLIDESHOW, true))
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (selectionViewModel.isSelectionMode) selectionViewModel.clear() else finish()
            }
        })
    }

    private fun setupRecyclerView() {
        galleryAdapter = MediaGridAdapter(this)
        layoutManager = GridLayoutManager(this, prefs.gridColumns).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    return if (galleryAdapter.isFullSpan(position)) spanCount else 1
                }
            }
            isItemPrefetchEnabled = true
        }

        binding.photoRecycler.apply {
            this.layoutManager = this@AlbumDetailActivity.layoutManager
            this.adapter = galleryAdapter
            setHasFixedSize(true)
            setItemViewCacheSize(30)
        }
        dragSelect = DragSelectTouchListener { start, end ->
            val range = (start..end).mapNotNull { (galleryAdapter.itemAt(it) as? GridItem.Media)?.file?.uri }
            selectionViewModel.setSelection(
                if (dragSelects) dragBaseSelection + range else dragBaseSelection - range.toSet()
            )
        }
        dragSelect.attach(binding.photoRecycler)
        binding.photoRecycler.addOnItemTouchListener(PinchToZoomGridListener(this) { delta ->
            val columns = (layoutManager.spanCount + delta)
                .coerceIn(GalleryPreferences.MIN_COLUMNS, GalleryPreferences.MAX_COLUMNS)
            if (columns != layoutManager.spanCount) {
                prefs.gridColumns = columns
                layoutManager.spanCount = columns
                galleryAdapter.notifyItemRangeChanged(0, galleryAdapter.itemCount)
            }
        })
        binding.fastScroller.attachFastScrollerToRecyclerView(binding.photoRecycler)
    }

    private fun setupSelection() {
        binding.selectionHeader.btnCloseSelection.setOnClickListener { selectionViewModel.clear() }
        binding.selectionHeader.btnSelectAll.setOnClickListener {
            val all = albumMedia.map { it.uri }.toSet()
            if (selectionViewModel.selection.value.containsAll(all)) selectionViewModel.clear()
            else selectionViewModel.setSelection(all)
        }
        val actions = binding.selectionActions
        actions.actionShare.setOnClickListener { selectionViewModel.share(selectedItems()) }
        actions.actionFavorite.setOnClickListener { selectionViewModel.toggleFavorite(selectedItems()) }
        actions.actionHide.setOnClickListener {
            val items = selectedItems()
            AppDialog.Builder(this)
                .setIcon(R.drawable.ic_g_lock, R.color.lumina_accent)
                .setTitle("Move to Locked Folder?")
                .setMessage("${if (items.size == 1) "This item" else "These ${items.size} items"} will be hidden from Photos and Albums. Open them anytime from Locked Folder.")
                .setPositiveButton("Move") { _, _ -> selectionViewModel.hide(items) }
                .setNegativeButton("Cancel", null)
                .show()
        }
        actions.actionDelete.setOnClickListener {
            val items = selectedItems()
            if (selectionViewModel.isRecycleBinSupported) {
                selectionViewModel.moveToTrash(items)
            } else {
                AppDialog.Builder(this)
                    .setIcon(R.drawable.ic_g_delete, R.color.lumina_danger)
                    .setTitle(if (items.size == 1) "Delete this item?" else "Delete ${items.size} items?")
                    .setMessage("This can't be undone on this Android version.")
                    .setPositiveButton("Delete") { _, _ -> selectionViewModel.moveToTrash(items) }
                    .setDestructive()
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
        actions.actionMore.setOnClickListener {
            SelectionMoreMenu.show(this, it, selectedItems(), selectionViewModel)
        }
        ViewCompat.setOnApplyWindowInsetsListener(actions.root) { v, insets ->
            val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.updatePadding(bottom = nav.bottom + (4 * resources.displayMetrics.density).toInt())
            insets
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    selectionViewModel.selection.collect { selection ->
                        val active = selection.isNotEmpty()
                        binding.selectionHeader.root.isVisible = active
                        binding.selectionActions.root.isVisible = active
                        binding.header.visibility = if (active) View.INVISIBLE else View.VISIBLE
                        binding.swipeRefresh.isEnabled = !active
                        binding.selectionHeader.tvSelectionCount.text =
                            if (selection.size == 1) "1 selected" else "${selection.size} selected"
                        val allFav = selectedItems().let { it.isNotEmpty() && it.all { f -> f.isFavorite } }
                        actions.ivActionFavorite.setImageResource(if (allFav) R.drawable.ic_g_heart else R.drawable.ic_g_heart_outline)
                        actions.tvActionFavorite.text = if (allFav) "Unfavorite" else "Favorite"
                        renderGrid()
                    }
                }
                launch {
                    selectionViewModel.events.collect { event ->
                        when (event) {
                            is MediaSelectionViewModel.SelectionEvent.Message ->
                                Toast.makeText(this@AlbumDetailActivity, event.text, Toast.LENGTH_SHORT).show()
                            is MediaSelectionViewModel.SelectionEvent.StartIntent -> startActivity(event.intent)
                            is MediaSelectionViewModel.SelectionEvent.LaunchIntentSender -> {
                                pendingAction = event.action
                                intentSenderLauncher.launch(IntentSenderRequest.Builder(event.sender).build())
                            }
                            MediaSelectionViewModel.SelectionEvent.Changed -> loadAlbumMedia()
                        }
                    }
                }
            }
        }
    }

    private fun selectedItems(): List<MediaFile> {
        val selected = selectionViewModel.selection.value
        return albumMedia.filter { selected.contains(it.uri) }
    }

    private fun loadAlbumMedia() {
        binding.swipeRefresh.isRefreshing = true
        lifecycleScope.launch {
            val mediaList = withContext(Dispatchers.IO) {
                val fetched = fetchAlbumMedia()
                val favorites = db.favoriteDao().getAllFavorites().first().map { it.uri }.toSet()
                fetched.forEach { it.isFavorite = favorites.contains(it.uri.toString()) }
                val hidden = db.hiddenDao().getHiddenUris().toSet()
                fetched.filterNot { hidden.contains(it.uri.toString()) }
            }
            processAndShowPhotos(mediaList)
            binding.swipeRefresh.isRefreshing = false
        }
    }

    private fun processAndShowPhotos(photos: List<MediaFile>) {
        albumMedia = photos
        val videos = photos.count { it.type == MediaType.VIDEO }
        val parts = mutableListOf<String>()
        parts.add(if (photos.size - videos == 1) "1 photo" else "${photos.size - videos} photos")
        if (videos > 0) parts.add(if (videos == 1) "1 video" else "$videos videos")
        binding.headerSubtitle.text = parts.joinToString(" · ")
        binding.btnSlideshow.isVisible = photos.count { it.type == MediaType.IMAGE } > 1
        binding.emptyStateView.isVisible = photos.isEmpty()
        renderGrid()
    }

    private fun renderGrid() {
        val photos = albumMedia
        val selection = selectionViewModel.selection.value
        lifecycleScope.launch {
            val galleryItems = withContext(Dispatchers.Default) {
                MediaGrouping.build(photos, GroupBy.DAY, SortOrder.DATE_DESC, selection)
            }
            galleryAdapter.submitList(galleryItems)
        }
    }

    private fun detailIntent(media: MediaFile) = Intent(this, PhotoDetailActivity::class.java).apply {
        putExtra("IMAGE_URI", media.uri.toString())
        putExtra("IS_VIDEO", media.type == MediaType.VIDEO)
        putExtra("ALBUM_NAME", albumName)
        smartAlbum?.let { putExtra(PhotoDetailActivity.EXTRA_SMART_ALBUM, it) }
        if (uriFilter != null) {
            // Virtual album: page through exactly what this screen shows.
            putExtra(PhotoDetailActivity.EXTRA_TITLE, albumName)
            putStringArrayListExtra(PhotoDetailActivity.EXTRA_URI_LIST, ArrayList(albumMedia.map { it.uri.toString() }))
        }
    }

    // ------------------------------------------------------------------ adapter callbacks

    override fun onMediaClick(file: MediaFile, position: Int) {
        if (selectionViewModel.isSelectionMode) {
            selectionViewModel.toggle(file.uri)
        } else {
            startActivity(detailIntent(file))
        }
    }

    override fun onMediaLongClick(file: MediaFile, position: Int) {
        binding.photoRecycler.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        dragBaseSelection = selectionViewModel.selection.value
        dragSelects = !dragBaseSelection.contains(file.uri)
        dragSelect.startDragSelection(position)
    }

    override fun onHeaderClick(header: GridItem.Header) {
        selectionViewModel.select(header.uris, !header.allSelected)
    }

    private suspend fun fetchAlbumMedia(): List<MediaFile> {
        val list = mutableListOf<MediaFile>()
        val filter = uriFilter
        if (filter != null) {
            list.addAll(fetchAllDeviceMedia().filter { filter.contains(it.uri.toString()) })
        } else if (smartAlbum == PhotoDetailActivity.SMART_VIDEOS) {
            list.addAll(fetchAllDeviceMedia().filter { it.type == MediaType.VIDEO })
        } else if (smartAlbum == PhotoDetailActivity.SMART_SCREENSHOTS) {
            list.addAll(fetchAllDeviceMedia().filter { it.isScreenshot })
        } else albumName?.let { name ->
            if (name == "Favorites") {
                val favorites = db.favoriteDao().getAllFavorites().first().map { it.uri.toString() }.toSet()
                val allFetched = fetchAllDeviceMedia()
                list.addAll(allFetched.filter { favorites.contains(it.uri.toString()) })
            } else {
                list.addAll(queryAlbumMedia(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaType.IMAGE, name))
                list.addAll(queryAlbumMedia(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaType.VIDEO, name))
            }
        }
        return list.sortedWith(
            compareByDescending<MediaFile> { it.dateAdded }
                .thenByDescending { it.uri.lastPathSegment?.toLongOrNull() ?: 0L }
        )
    }

    private fun fetchAllDeviceMedia(): List<MediaFile> {
        val list = mutableListOf<MediaFile>()
        list.addAll(queryAlbumMediaGeneral(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaType.IMAGE))
        list.addAll(queryAlbumMediaGeneral(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaType.VIDEO))
        return list
    }

    private fun queryAlbumMediaGeneral(uri: android.net.Uri, type: MediaType): List<MediaFile> {
        val list = mutableListOf<MediaFile>()
        val projection = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            "bucket_display_name",
            MediaStore.MediaColumns.DATE_ADDED
        )
        if (type == MediaType.VIDEO) {
            projection.add(MediaStore.Video.Media.DURATION)
        }

        try {
            contentResolver.query(
                uri,
                projection.toTypedArray(),
                null,
                null,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC, ${MediaStore.MediaColumns._ID} DESC"
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val albumColumn = cursor.getColumnIndex("bucket_display_name")
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val durationColumn = if (type == MediaType.VIDEO) cursor.getColumnIndex(MediaStore.Video.Media.DURATION) else -1

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val fileName = cursor.getString(nameColumn) ?: "Unknown"
                    val albumNameStr = if (albumColumn != -1) {
                        cursor.getString(albumColumn)?.takeIf { it.isNotBlank() } ?: "Camera"
                    } else {
                        "Camera"
                    }
                    val date = cursor.getLong(dateColumn)
                    val duration = if (durationColumn != -1) cursor.getLong(durationColumn) else 0L
                    val contentUri = ContentUris.withAppendedId(uri, id)
                    list.add(MediaFile(contentUri, fileName, albumNameStr, date, type, duration))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    private fun queryAlbumMedia(uri: android.net.Uri, type: MediaType, album: String): List<MediaFile> {
        val list = mutableListOf<MediaFile>()
        val projection = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            "bucket_display_name",
            MediaStore.MediaColumns.DATE_ADDED
        )
        if (type == MediaType.VIDEO) {
            projection.add(MediaStore.Video.Media.DURATION)
        }

        // The library groups files without a bucket name under "Camera", so include them here too
        // (otherwise the album card count and the album contents disagree).
        val selection = if (album == "Camera") {
            "(bucket_display_name = ? OR bucket_display_name IS NULL OR bucket_display_name = '')"
        } else {
            "bucket_display_name = ?"
        }
        val selectionArgs = arrayOf(album)

        try {
            contentResolver.query(
                uri,
                projection.toTypedArray(),
                selection,
                selectionArgs,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC, ${MediaStore.MediaColumns._ID} DESC"
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val albumColumn = cursor.getColumnIndex("bucket_display_name")
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val durationColumn = if (type == MediaType.VIDEO) cursor.getColumnIndex(MediaStore.Video.Media.DURATION) else -1

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val fileName = cursor.getString(nameColumn) ?: "Unknown"
                    val albumNameStr = if (albumColumn != -1) {
                        cursor.getString(albumColumn)?.takeIf { it.isNotBlank() } ?: album
                    } else {
                        album
                    }
                    val date = cursor.getLong(dateColumn)
                    val duration = if (durationColumn != -1) cursor.getLong(durationColumn) else 0L
                    val contentUri = ContentUris.withAppendedId(uri, id)
                    list.add(MediaFile(contentUri, fileName, albumNameStr, date, type, duration))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }
}
