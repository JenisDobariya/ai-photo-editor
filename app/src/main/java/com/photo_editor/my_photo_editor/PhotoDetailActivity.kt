package com.photo_editor.my_photo_editor

import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import coil.load
import com.photo_editor.my_photo_editor.databinding.ActivityPhotoDetailBinding
import com.photo_editor.my_photo_editor.db.AppDatabase
import com.photo_editor.my_photo_editor.db.FavoriteMedia
import com.photo_editor.my_photo_editor.model.MediaFile
import com.photo_editor.my_photo_editor.model.MediaType
import com.photo_editor.my_photo_editor.utils.AnalyticsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PhotoDetailActivity : BaseActivity() {

    override fun getScreenTag(): String = "PhotoDetail"

    private lateinit var binding: ActivityPhotoDetailBinding
    private var initialUri: Uri? = null
    private var mediaList = mutableListOf<MediaFile>()
    private var adapter: MediaPagerAdapter? = null
    private val activePlayers = mutableMapOf<Int, ExoPlayer>()

    private val db by lazy { AppDatabase.getDatabase(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhotoDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val uriString = intent.getStringExtra("IMAGE_URI")
        initialUri = uriString?.toUri()

        loadMediaAndSetupViewPager()

        binding.btnBack.setOnClickListener {
            AnalyticsManager.logEvent("PhotoDetail_btnBack_click")
            finish()
        }

        binding.btnEdit.setOnClickListener {
            AnalyticsManager.logEvent("PhotoDetail_btnEdit_click")
            val currentPos = binding.viewPager.currentItem
            val currentMedia = mediaList.getOrNull(currentPos)
            if (currentMedia != null && currentMedia.type != MediaType.VIDEO) {
                val intent = Intent(this, PhotoEditorActivity::class.java).apply {
                    putExtra("IMAGE_URI", currentMedia.uri.toString())
                }
                startActivity(intent)
            } else {
                Toast.makeText(this, "Video editing not supported yet", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnShare.setOnClickListener {
            AnalyticsManager.logEvent("PhotoDetail_btnShare_click")
            val currentPos = binding.viewPager.currentItem
            val currentMedia = mediaList.getOrNull(currentPos)
            currentMedia?.let { media ->
                val isVid = media.type == MediaType.VIDEO
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = if (isVid) "video/*" else "image/*"
                    putExtra(Intent.EXTRA_STREAM, media.uri)
                }
                startActivity(Intent.createChooser(shareIntent, if (isVid) "Share Video" else "Share Image"))
            }
        }
        
        binding.btnDelete.setOnClickListener {
            AnalyticsManager.logEvent("PhotoDetail_btnDelete_click")
            deleteMedia()
        }

        binding.btnLike.setOnClickListener {
            AnalyticsManager.logEvent("PhotoDetail_btnLike_click")
            toggleFavorite()
        }
    }

    private fun loadMediaAndSetupViewPager() {
        lifecycleScope.launch {
            val fetched = withContext(Dispatchers.IO) {
                val list = mutableListOf<MediaFile>()
                val albumName = intent.getStringExtra("ALBUM_NAME")
                if (albumName == "Favorites") {
                    val favorites = db.favoriteDao().getAllFavorites().first().map { it.uri }.toSet()
                    val allFetched = fetchMedia()
                    list.addAll(allFetched.filter { favorites.contains(it.uri.toString()) })
                } else if (albumName != null) {
                    list.addAll(queryAlbumMedia(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaType.IMAGE, albumName))
                    list.addAll(queryAlbumMedia(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaType.VIDEO, albumName))
                } else {
                    list.addAll(fetchMedia())
                }
                val favorites = db.favoriteDao().getAllFavorites().first().map { it.uri }.toSet()
                list.forEach { it.isFavorite = favorites.contains(it.uri.toString()) }
                list.sortedWith(
                    compareByDescending<MediaFile> { it.dateAdded }
                        .thenByDescending { it.uri.lastPathSegment?.toLongOrNull() ?: 0L }
                )
            }

            mediaList = fetched.toMutableList()
            if (mediaList.isEmpty() && initialUri != null) {
                val isVid = isVideoUri(initialUri!!)
                mediaList.add(MediaFile(initialUri!!, "Unknown", "Camera", 0L, if (isVid) MediaType.VIDEO else MediaType.IMAGE, 0L))
            }

            val initialIndex = mediaList.indexOfFirst { it.uri == initialUri }.coerceAtLeast(0)

            adapter = MediaPagerAdapter(mediaList)
            binding.viewPager.adapter = adapter
            binding.viewPager.setCurrentItem(initialIndex, false)

            binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    super.onPageSelected(position)
                    if (position in mediaList.indices) {
                        val current = mediaList[position]
                        binding.btnEdit.isVisible = current.type != MediaType.VIDEO
                        checkFavoriteStatus()
                        playVideoAtPosition(position)
                    }
                }
            })

            if (mediaList.isNotEmpty()) {
                val current = mediaList[initialIndex]
                binding.btnEdit.isVisible = current.type != MediaType.VIDEO
                checkFavoriteStatus()
                playVideoAtPosition(initialIndex)
            }
        }
    }

    private fun fetchMedia(): List<MediaFile> {
        val list = mutableListOf<MediaFile>()
        list.addAll(queryMedia(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaType.IMAGE))
        list.addAll(queryMedia(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaType.VIDEO))
        return list.sortedWith(
            compareByDescending<MediaFile> { it.dateAdded }
                .thenByDescending { it.uri.lastPathSegment?.toLongOrNull() ?: 0L }
        )
    }

    private fun queryMedia(uri: Uri, type: MediaType): List<MediaFile> {
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
                    val name = cursor.getString(nameColumn) ?: "Unknown"
                    val album = if (albumColumn != -1) {
                        cursor.getString(albumColumn)?.takeIf { it.isNotBlank() } ?: "Camera"
                    } else {
                        "Camera"
                    }
                    val date = cursor.getLong(dateColumn)
                    val duration = if (durationColumn != -1) cursor.getLong(durationColumn) else 0L
                    val contentUri = ContentUris.withAppendedId(uri, id)
                    list.add(MediaFile(contentUri, name, album, date, type, duration))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    private fun queryAlbumMedia(uri: Uri, type: MediaType, album: String): List<MediaFile> {
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
                    val name = cursor.getString(nameColumn) ?: "Unknown"
                    val albumNameStr = if (albumColumn != -1) {
                        cursor.getString(albumColumn)?.takeIf { it.isNotBlank() } ?: album
                    } else {
                        album
                    }
                    val date = cursor.getLong(dateColumn)
                    val duration = if (durationColumn != -1) cursor.getLong(durationColumn) else 0L
                    val contentUri = ContentUris.withAppendedId(uri, id)
                    list.add(MediaFile(contentUri, name, albumNameStr, date, type, duration))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    private inner class MediaPagerAdapter(
        private val items: MutableList<MediaFile>
    ) : RecyclerView.Adapter<MediaPagerAdapter.MediaViewHolder>() {

        inner class MediaViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val ivMainPhoto: ImageView = view.findViewById(R.id.ivMainPhoto)
            val playerView: androidx.media3.ui.PlayerView = view.findViewById(R.id.playerView)
            val videoProgressBar: ProgressBar = view.findViewById(R.id.videoProgressBar)
            val ivPlayVideo: ImageView = view.findViewById(R.id.ivPlayVideo)
            val mediaContainer: FrameLayout = view.findViewById(R.id.mediaContainer)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_photo_detail_page, parent, false)
            return MediaViewHolder(view)
        }

        override fun onBindViewHolder(holder: MediaViewHolder, position: Int) {
            val file = items[position]
            val isVid = file.type == MediaType.VIDEO

            holder.ivMainPhoto.isVisible = true
            holder.playerView.isVisible = false
            holder.videoProgressBar.isVisible = false
            holder.ivPlayVideo.isVisible = false

            if (isVid) {
                lifecycleScope.launch {
                    val thumb = withContext(Dispatchers.IO) { getThumbnail(file.uri) }
                    if (thumb != null) {
                        holder.ivMainPhoto.load(thumb)
                    } else {
                        holder.ivMainPhoto.load(file.uri)
                    }
                }
                if (position == binding.viewPager.currentItem) {
                    playVideoAtPosition(position)
                }
            } else {
                holder.ivMainPhoto.load(file.uri) {
                    listener(onError = { _, _ -> handleMissingFile() })
                }
            }
        }

        override fun onViewRecycled(holder: MediaViewHolder) {
            super.onViewRecycled(holder)
            activePlayers.entries.find { it.value == holder.playerView.player }?.let { entry ->
                entry.value.release()
                activePlayers.remove(entry.key)
            }
            holder.playerView.player = null
        }

        override fun getItemCount() = items.size

        fun removeAt(position: Int) {
            if (position in items.indices) {
                activePlayers[position]?.release()
                activePlayers.remove(position)
                items.removeAt(position)
                notifyItemRemoved(position)
                notifyItemRangeChanged(position, items.size)
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun playVideoAtPosition(position: Int) {
        activePlayers.forEach { (pos, player) ->
            if (pos != position) {
                player.pause()
            }
        }

        val file = mediaList.getOrNull(position) ?: return
        if (file.type != MediaType.VIDEO) {
            activePlayers[position]?.pause()
            return
        }

        val recyclerView = binding.viewPager.getChildAt(0) as? RecyclerView
        val holder = recyclerView?.findViewHolderForAdapterPosition(position) as? MediaPagerAdapter.MediaViewHolder 
            ?: return

        holder.ivMainPhoto.isVisible = true
        holder.playerView.isVisible = false

        val existingPlayer = activePlayers[position]
        if (existingPlayer != null) {
            existingPlayer.playWhenReady = true
            existingPlayer.play()
            holder.playerView.player = existingPlayer
            return
        }

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(1500, 5000, 250, 500)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .build().apply {
                setMediaItem(MediaItem.fromUri(file.uri))
                repeatMode = Player.REPEAT_MODE_ONE
                playWhenReady = true
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        when (state) {
                            Player.STATE_BUFFERING -> holder.videoProgressBar.isVisible = true
                            Player.STATE_READY -> {
                                holder.videoProgressBar.isVisible = false
                                holder.ivMainPhoto.isVisible = false
                                holder.playerView.isVisible = true
                            }
                            Player.STATE_ENDED -> holder.ivPlayVideo.isVisible = true
                            else -> {}
                        }
                    }
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        handleMissingFile()
                    }
                })
                prepare()
            }

        activePlayers[position] = player
        holder.playerView.player = player

        holder.mediaContainer.setOnClickListener {
            if (player.isPlaying) {
                player.pause()
                holder.ivPlayVideo.isVisible = true
            } else {
                player.play()
                holder.ivPlayVideo.isVisible = false
            }
        }
        holder.ivPlayVideo.setOnClickListener {
            player.play()
            holder.ivPlayVideo.isVisible = false
        }
    }

    private fun handleMissingFile() {
        Toast.makeText(this, "File no longer available", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun deleteMedia() {
        val currentPosition = binding.viewPager.currentItem
        val currentMedia = mediaList.getOrNull(currentPosition) ?: return
        val uri = currentMedia.uri
        val type = if (currentMedia.type == MediaType.VIDEO) "Video" else "Photo"

        AnalyticsManager.logEvent("PhotoDetail_deleteDialog_show")
        AlertDialog.Builder(this)
            .setTitle("Delete $type")
            .setMessage("Are you sure you want to delete this $type?")
            .setPositiveButton("Delete") { _, _ ->
                AnalyticsManager.logEvent("PhotoDetail_deleteDialog_confirm")
                performDelete(currentPosition, uri)
            }
            .setNegativeButton("Cancel") { _, _ ->
                AnalyticsManager.logEvent("PhotoDetail_deleteDialog_cancel")
            }
            .show()
    }

    private fun performDelete(position: Int, uri: Uri) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val pendingIntent = MediaStore.createDeleteRequest(contentResolver, listOf(uri))
                        startIntentSenderForResult(pendingIntent.intentSender, DELETE_REQUEST_CODE, null, 0, 0, 0)
                        true
                    } else {
                        val deletedRows = contentResolver.delete(uri, null, null)
                        deletedRows > 0
                    }
                } catch (e: Exception) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e is android.app.RecoverableSecurityException) {
                        startIntentSenderForResult(e.userAction.actionIntent.intentSender, DELETE_REQUEST_CODE, null, 0, 0, 0)
                        true
                    } else {
                        e.printStackTrace()
                        false
                    }
                }
            }
            
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && result) {
                Toast.makeText(this@PhotoDetailActivity, "Deleted successfully", Toast.LENGTH_SHORT).show()
                adapter?.removeAt(position)
                if (mediaList.isEmpty()) {
                    finish()
                } else {
                    val newPos = position.coerceAtMost(mediaList.size - 1)
                    binding.viewPager.setCurrentItem(newPos, false)
                    playVideoAtPosition(newPos)
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == DELETE_REQUEST_CODE && resultCode == RESULT_OK) {
            Toast.makeText(this, "Deleted successfully", Toast.LENGTH_SHORT).show()
            val currentPosition = binding.viewPager.currentItem
            adapter?.removeAt(currentPosition)
            if (mediaList.isEmpty()) {
                finish()
            } else {
                val newPos = currentPosition.coerceAtMost(mediaList.size - 1)
                binding.viewPager.setCurrentItem(newPos, false)
                playVideoAtPosition(newPos)
            }
        }
    }

    private companion object {
        const val DELETE_REQUEST_CODE = 1001
    }

    private fun checkFavoriteStatus() {
        val currentPos = binding.viewPager.currentItem
        val currentMedia = mediaList.getOrNull(currentPos) ?: return
        lifecycleScope.launch {
            val isFav = withContext(Dispatchers.IO) {
                db.favoriteDao().isFavorite(currentMedia.uri.toString())
            }
            currentMedia.isFavorite = isFav
            updateLikeButtonUI(isFav)
        }
    }

    private fun toggleFavorite() {
        val currentPos = binding.viewPager.currentItem
        val currentMedia = mediaList.getOrNull(currentPos) ?: return
        lifecycleScope.launch {
            val currentUri = currentMedia.uri.toString()
            val existing = withContext(Dispatchers.IO) {
                db.favoriteDao().getFavorite(currentUri)
            }
            
            withContext(Dispatchers.IO) {
                if (existing != null) {
                    db.favoriteDao().removeFavorite(existing)
                } else {
                    db.favoriteDao().addFavorite(FavoriteMedia(currentUri))
                }
            }
            currentMedia.isFavorite = (existing == null)
            updateLikeButtonUI(currentMedia.isFavorite)
        }
    }

    private fun updateLikeButtonUI(isFavorite: Boolean) {
        binding.btnLike.isSelected = isFavorite
        binding.btnLike.setColorFilter(
            if (isFavorite) ContextCompat.getColor(this, R.color.lumina_gold)
            else android.graphics.Color.WHITE
        )
    }

    private fun isVideoUri(uri: Uri): Boolean {
        return try {
            val contentResolver = contentResolver
            val type = contentResolver.getType(uri)
            if (type != null && type.startsWith("video/")) return true

            val path = uri.path ?: ""
            val authority = uri.authority ?: ""
            if (path.contains("/video/") || authority.contains("video")) return true

            val extension = path.substringAfterLast(".", "").lowercase()
            extension in listOf("mp4", "mkv", "mov", "3gp", "avi", "webm")
        } catch (_: Exception) {
            false
        }
    }

    private fun getThumbnail(uri: Uri): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentResolver.loadThumbnail(uri, Size(800, 800), null)
            } else {
                @Suppress("DEPRECATION")
                val filePath = getRealPathFromURI(uri)
                if (filePath != null) {
                    @Suppress("DEPRECATION")
                    ThumbnailUtils.createVideoThumbnail(filePath, MediaStore.Video.Thumbnails.MINI_KIND)
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun getRealPathFromURI(contentUri: Uri): String? {
        return try {
            val proj = arrayOf(MediaStore.Video.Media.DATA)
            contentResolver.query(contentUri, proj, null, null, null)?.use { cursor ->
                val columnIndex = cursor.getColumnIndex(MediaStore.Video.Media.DATA)
                if (columnIndex != -1 && cursor.moveToFirst()) {
                    cursor.getString(columnIndex)
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    override fun onStop() {
        super.onStop()
        activePlayers.values.forEach { it.pause() }
    }

    override fun onDestroy() {
        super.onDestroy()
        activePlayers.values.forEach { it.release() }
        activePlayers.clear()
    }
}
