package com.photo_editor.my_photo_editor

import android.content.ContentUris
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.photo_editor.my_photo_editor.databinding.ActivityAlbumDetailBinding
import com.photo_editor.my_photo_editor.model.MediaFile
import com.photo_editor.my_photo_editor.model.MediaType
import com.photo_editor.my_photo_editor.ui.FastScrollerView
import com.photo_editor.my_photo_editor.utils.AnalyticsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

import com.photo_editor.my_photo_editor.db.AppDatabase

import coil.request.videoFrameMicros

import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

class AlbumDetailActivity : BaseActivity() {

    override fun getScreenTag(): String = "AlbumDetail"

    private lateinit var binding: ActivityAlbumDetailBinding
    private var albumName: String? = null
    private val db by lazy { AppDatabase.getDatabase(this) }
    private var refreshJob: Job? = null
    private lateinit var galleryAdapter: GalleryAdapter

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            refreshJob?.cancel()
            refreshJob = lifecycleScope.launch {
                delay(500)
                loadAlbumMedia()
            }
        }
    }

    private sealed class GalleryItem {
        data class Header(val date: String) : GalleryItem()
        data class Media(val file: MediaFile) : GalleryItem()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAlbumDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        albumName = intent.getStringExtra("ALBUM_NAME")
        
        setupUI()
        setupRecyclerView()
        setupSwipeRefresh()

        contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
        contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
    }

    override fun onDestroy() {
        super.onDestroy()
        contentResolver.unregisterContentObserver(observer)
    }

    private fun setupSwipeRefresh() {
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
    }

    private fun setupRecyclerView() {
        val layoutManager = GridLayoutManager(this, 5).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    return if (galleryAdapter.getItemViewType(position) == GalleryAdapter.TYPE_HEADER) 5 else 1
                }
            }
            isItemPrefetchEnabled = true
        }

        galleryAdapter = GalleryAdapter(emptyList()) { media ->
            val intent = Intent(this, PhotoDetailActivity::class.java).apply {
                putExtra("IMAGE_URI", media.uri.toString())
                putExtra("IS_VIDEO", media.type == MediaType.VIDEO)
                putExtra("ALBUM_NAME", albumName)
            }
            startActivity(intent)
        }

        binding.photoRecycler.apply {
            this.layoutManager = layoutManager
            this.adapter = galleryAdapter
            setHasFixedSize(true)
            setItemViewCacheSize(30)
        }
    }

    private fun loadAlbumMedia() {
        binding.swipeRefresh.isRefreshing = true
        lifecycleScope.launch {
            val mediaList = withContext(Dispatchers.IO) {
                val fetched = fetchAlbumMedia()
                val favorites = db.favoriteDao().getAllFavorites().first().map { it.uri }.toSet()
                fetched.forEach { it.isFavorite = favorites.contains(it.uri.toString()) }
                fetched
            }
            processAndShowPhotos(mediaList)
            binding.swipeRefresh.isRefreshing = false
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

    private suspend fun fetchAlbumMedia(): List<MediaFile> {
        val list = mutableListOf<MediaFile>()
        albumName?.let { name ->
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

        val selection = "bucket_display_name = ?"
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
