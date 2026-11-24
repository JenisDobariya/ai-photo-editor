package com.photo_editor.my_photo_editor

import com.photo_editor.my_photo_editor.ui.AppDialog
import com.photo_editor.my_photo_editor.utils.appColor
import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.util.Size

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import android.content.res.Configuration
import androidx.activity.OnBackPressedCallback
import com.photo_editor.my_photo_editor.ui.video.VideoPlayerController
import com.photo_editor.my_photo_editor.ui.SwipeDismissLayout
import androidx.activity.result.contract.ActivityResultContracts
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
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.PopupMenu
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import com.photo_editor.my_photo_editor.repository.MediaRepository
import com.photo_editor.my_photo_editor.repository.MediaOrganizer
import com.photo_editor.my_photo_editor.ui.MediaDetailsBottomSheet
import com.photo_editor.my_photo_editor.ui.MediaSelectionViewModel
import com.photo_editor.my_photo_editor.ui.SelectionMoreMenu
import com.photo_editor.my_photo_editor.utils.BitmapLoader
import androidx.activity.result.IntentSenderRequest
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.print.PrintHelper
import com.photo_editor.my_photo_editor.ui.ZoomableImageView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class PhotoDetailActivity : BaseActivity() {

    override fun getScreenTag(): String = "PhotoDetail"

    // Photos look best on dark: this screen stays dark in every theme.
    override val alwaysDark = true

    private lateinit var binding: ActivityPhotoDetailBinding
    private var initialUri: Uri? = null
    private var mediaList = mutableListOf<MediaFile>()
    private var adapter: MediaPagerAdapter? = null
    private lateinit var videoController: VideoPlayerController

    /** Subtitle file picker (MX "Subtitle → Open"). */
    private val pickSubtitle = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) videoController.loadSubtitle(uri)
    }

    /** While the video screen is locked, Back only reveals the unlock button. */
    private val lockedBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = videoController.flashUnlockButton()
    }

    private val db by lazy { AppDatabase.getDatabase(this) }
    private val repository by lazy { MediaRepository(this) }
    private val selectionViewModel: MediaSelectionViewModel by viewModels()
    private var pendingOrganizeAction: MediaSelectionViewModel.PendingAction? = null
    private val organizeLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val action = pendingOrganizeAction ?: return@registerForActivityResult
        pendingOrganizeAction = null
        selectionViewModel.onIntentSenderResult(action, result.resultCode == RESULT_OK)
    }
    private var chromeVisible = true
    private var isSlideshowRunning = false
    private val slideshowHandler = Handler(Looper.getMainLooper())
    private val slideshowRunnable = object : Runnable {
        override fun run() {
            if (!isSlideshowRunning) return
            val next = binding.viewPager.currentItem + 1
            if (next < mediaList.size) {
                binding.viewPager.setCurrentItem(next, true)
                slideshowHandler.postDelayed(this, SLIDESHOW_INTERVAL_MS)
            } else {
                stopSlideshow()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Always draw behind the camera cutout. With the default mode the window shrinks away
        // from the cutout whenever the status bar is hidden, so tapping to hide the controls
        // resized (and re-zoomed) the photo or video.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        binding = ActivityPhotoDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val uriString = intent.getStringExtra("IMAGE_URI")
        initialUri = uriString?.toUri()

        setupVideoController()
        setupSwipeToDismiss()
        loadMediaAndSetupViewPager()
        observeOrganizeEvents()

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

        binding.btnAudioTrack.setOnClickListener { videoController.showAudioTrackDialog() }
        binding.btnSubtitles.setOnClickListener { videoController.showSubtitleDialog() }

        binding.btnInfo.setOnClickListener {
            AnalyticsManager.logEvent("PhotoDetail_btnInfo_click")
            showDetails()
        }

        binding.btnMore.setOnClickListener {
            AnalyticsManager.logEvent("PhotoDetail_btnMore_click")
            showMoreMenu(it)
        }
    }

    /** Full-bleed viewer: only the bars are padded for the system bars. */
    override fun setupSystemBarInsets(rootView: View) {
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val dp = resources.displayMetrics.density
            binding.topBar.updatePadding(top = bars.top + (8 * dp).toInt(), left = bars.left, right = bars.right)
            binding.bottomActions.updatePadding(bottom = bars.bottom + (12 * dp).toInt(), left = bars.left, right = bars.right)
            binding.videoQuickTools.root.updatePadding(left = bars.left + (8 * dp).toInt(), right = bars.right + (8 * dp).toInt())
            binding.videoPanel.panel.updatePadding(
                top = bars.top + (20 * dp).toInt(),
                bottom = bars.bottom + (16 * dp).toInt(),
                right = bars.right + (16 * dp).toInt()
            )
            insets
        }
        ViewCompat.requestApplyInsets(rootView)
    }

    private fun loadMediaAndSetupViewPager() {
        lifecycleScope.launch {
            val fetched = withContext(Dispatchers.IO) {
                val list = mutableListOf<MediaFile>()
                val albumName = intent.getStringExtra("ALBUM_NAME")
                val uriList = intent.getStringArrayListExtra(EXTRA_URI_LIST)
                val smartAlbum = intent.getStringExtra(EXTRA_SMART_ALBUM)
                if (uriList != null) {
                    // Keep the caller's order (filters, custom sort, memories, Locked Folder)
                    val byUri = fetchMedia().associateBy { it.uri.toString() }
                    val ordered = uriList.mapNotNull { byUri[it] }
                    val favorites = db.favoriteDao().getAllFavorites().first().map { it.uri }.toSet()
                    ordered.forEach { it.isFavorite = favorites.contains(it.uri.toString()) }
                    return@withContext ordered
                } else if (smartAlbum == SMART_VIDEOS) {
                    list.addAll(fetchMedia().filter { it.type == MediaType.VIDEO })
                } else if (smartAlbum == SMART_SCREENSHOTS) {
                    list.addAll(fetchMedia().filter { it.isScreenshot })
                } else if (albumName == "Favorites") {
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
                val hidden = db.hiddenDao().getHiddenUris().toSet()
                if (hidden.isNotEmpty()) list.removeAll { hidden.contains(it.uri.toString()) }
                list.sortedWith(
                    compareByDescending<MediaFile> { it.displayDateSeconds }
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
                        updateTitle(position)
                    }
                }
            })

            if (mediaList.isNotEmpty()) {
                val current = mediaList[initialIndex]
                binding.btnEdit.isVisible = current.type != MediaType.VIDEO
                checkFavoriteStatus()
                playVideoAtPosition(initialIndex)
                updateTitle(initialIndex)
                if (intent.getBooleanExtra(EXTRA_START_SLIDESHOW, false)) {
                    binding.viewPager.post { startSlideshow() }
                }
            }
        }
    }

    private fun fetchMedia(): List<MediaFile> {
        val list = mutableListOf<MediaFile>()
        list.addAll(queryMedia(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaType.IMAGE))
        list.addAll(queryMedia(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaType.VIDEO))
        return list.sortedWith(
            compareByDescending<MediaFile> { it.displayDateSeconds }
                .thenByDescending { it.uri.lastPathSegment?.toLongOrNull() ?: 0L }
        )
    }

    private fun queryMedia(uri: Uri, type: MediaType): List<MediaFile> {
        val list = mutableListOf<MediaFile>()
        val projection = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            "bucket_display_name",
            MediaStore.MediaColumns.DATE_ADDED,
            "datetaken"
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
                val takenColumn = cursor.getColumnIndex("datetaken")
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
                    list.add(
                        MediaFile(
                            contentUri, name, album, date, type, duration,
                            dateTaken = if (takenColumn != -1) cursor.getLong(takenColumn) else 0L
                        )
                    )
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
            MediaStore.MediaColumns.DATE_ADDED,
            "datetaken"
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
                val takenColumn = cursor.getColumnIndex("datetaken")
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
                    list.add(
                        MediaFile(
                            contentUri, name, albumNameStr, date, type, duration,
                            dateTaken = if (takenColumn != -1) cursor.getLong(takenColumn) else 0L
                        )
                    )
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
            val ivMainPhoto: ZoomableImageView = view.findViewById(R.id.ivMainPhoto)
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
            holder.ivMainPhoto.onSingleTap = { onViewerTapped() }
            // The PlayerView sits on top for videos so it gets the MX-style gestures;
            // it stays transparent until the first frame, showing the thumbnail below.
            holder.playerView.isVisible = isVid
            holder.playerView.setOnTouchListener(if (isVid) videoController.touchListener else null)

            if (isVid) {
                lifecycleScope.launch {
                    val thumb = withContext(Dispatchers.IO) { getThumbnail(file.uri) }
                    if (thumb != null) {
                        holder.ivMainPhoto.load(thumb)
                    } else {
                        holder.ivMainPhoto.load(file.uri)
                    }
                }
            } else {
                holder.ivMainPhoto.load(file.uri) {
                    size(VIEWER_MAX_SIZE) // high-res for pinch zoom, bounded for memory
                    listener(onError = { _, _ -> handleMissingFile() })
                }
            }
        }

        /** Starts the video once its page is really on screen (also covers the first open). */
        override fun onViewAttachedToWindow(holder: MediaViewHolder) {
            super.onViewAttachedToWindow(holder)
            val position = holder.bindingAdapterPosition
            if (position != RecyclerView.NO_POSITION && position == binding.viewPager.currentItem) {
                playVideoAtPosition(position)
            }
        }

        override fun onViewRecycled(holder: MediaViewHolder) {
            super.onViewRecycled(holder)
            if (videoController.isAttachedTo(holder.playerView)) videoController.detach()
            holder.playerView.player = null
        }

        override fun getItemCount() = items.size

        fun removeAt(position: Int) {
            if (position in items.indices) {
                if (items[position].uri == videoController.currentUri) videoController.detach()
                items.removeAt(position)
                notifyItemRemoved(position)
                notifyItemRangeChanged(position, items.size)
            }
        }
    }

    private fun playVideoAtPosition(position: Int) {
        val file = mediaList.getOrNull(position)
        val isVideo = file?.type == MediaType.VIDEO
        updateVideoPanel(isVideo)
        if (file == null || !isVideo) {
            videoController.detach()
            videoController.resetOrientation()
            return
        }

        val holder = findPageHolder(position)
        if (holder == null) {
            // Page not laid out yet (first open, after a delete): try again after layout.
            binding.viewPager.post {
                if (binding.viewPager.currentItem == position) {
                    findPageHolder(position)?.let { attachVideo(it, file) }
                }
            }
            return
        }
        attachVideo(holder, file)
    }

    private fun attachVideo(holder: MediaPagerAdapter.MediaViewHolder, file: MediaFile) {
        holder.ivPlayVideo.isVisible = false
        val position = holder.bindingAdapterPosition
        videoController.setNavigationAvailable(position > 0, position < mediaList.size - 1)
        videoController.attach(holder.playerView, holder.ivMainPhoto, holder.videoProgressBar, file.uri)
        if (chromeVisible) videoController.scheduleAutoHide()
    }

    /** Video pages show the playback panel; in landscape it replaces the gallery actions, like MX. */
    private fun updateVideoPanel(isVideo: Boolean) {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        binding.videoControls.root.isVisible = isVideo
        binding.videoQuickTools.root.isVisible = isVideo && chromeVisible
        binding.videoQuickTools.root.alpha = 1f
        binding.btnAudioTrack.isVisible = isVideo
        binding.btnSubtitles.isVisible = isVideo
        binding.actionsRow.isVisible = !(isVideo && landscape)
    }

    private fun findPageHolder(position: Int): MediaPagerAdapter.MediaViewHolder? {
        val recyclerView = binding.viewPager.getChildAt(0) as? RecyclerView ?: return null
        return recyclerView.findViewHolderForAdapterPosition(position) as? MediaPagerAdapter.MediaViewHolder
    }

    private fun setupVideoController() {
        videoController = VideoPlayerController(
            this,
            binding.videoControls,
            binding.videoOverlay,
            binding.videoQuickTools,
            binding.videoPanel,
            object : VideoPlayerController.Callbacks {
                override fun bottomChromeHeight(): Int = binding.bottomActions.height - binding.bottomActions.paddingTop
                override fun onPickSubtitleFile() {
                    try {
                        pickSubtitle.launch(arrayOf("application/x-subrip", "text/vtt", "text/plain", "application/octet-stream", "*/*"))
                    } catch (_: Exception) {
                        Toast.makeText(this@PhotoDetailActivity, "No file picker available", Toast.LENGTH_SHORT).show()
                    }
                }
                override fun onVideoTapped() = onViewerTapped()
                override fun isChromeVisible() = chromeVisible
                override fun setChromeVisible(visible: Boolean) {
                    if (visible != chromeVisible) this@PhotoDetailActivity.setChromeVisible(visible)
                }
                override fun onPrevious() {
                    val target = binding.viewPager.currentItem - 1
                    if (target >= 0) binding.viewPager.setCurrentItem(target, true)
                }
                override fun onNext() {
                    val target = binding.viewPager.currentItem + 1
                    if (target < mediaList.size) binding.viewPager.setCurrentItem(target, true)
                }
                override fun onLockChanged(locked: Boolean) {
                    binding.viewPager.isUserInputEnabled = !locked
                    lockedBackCallback.isEnabled = locked
                    setChromeVisible(!locked)
                }
            }
        )
        onBackPressedDispatcher.addCallback(this, lockedBackCallback)
    }

    /** Gallery-style drag up/down to close the viewer. */
    private fun setupSwipeToDismiss() {
        val root = binding.detailRoot
        root.background = root.background?.mutate()
        binding.swipeDismiss.listener = object : SwipeDismissLayout.Listener {
            private var chromeWasVisible = false

            override fun canStartDismiss(x: Float, y: Float): Boolean {
                if (isSlideshowRunning || videoController.isLocked || mediaList.isEmpty()) return false
                val position = binding.viewPager.currentItem
                val holder = findPageHolder(position) ?: return false
                if (holder.ivMainPhoto.isZoomed) return false
                // On videos the side thirds are brightness / volume, so only the middle closes.
                if (mediaList.getOrNull(position)?.type == MediaType.VIDEO) {
                    val w = binding.swipeDismiss.width
                    return x > w / 3f && x < w * 2f / 3f
                }
                return true
            }

            override fun dismissTarget(): View? = findPageHolder(binding.viewPager.currentItem)?.itemView

            override fun onDragStarted() {
                chromeWasVisible = chromeVisible
                videoController.hidePanel(animate = false)
                chromeBars().forEach { it.animate().alpha(0f).setDuration(120).start() }
            }

            override fun onDragProgress(fraction: Float) {
                root.background?.alpha = ((1f - fraction) * 255).toInt().coerceIn(0, 255)
            }

            override fun onDragCancelled() {
                root.background?.alpha = 255
                if (chromeWasVisible) chromeBars().forEach { it.animate().alpha(1f).setDuration(150).start() }
            }

            override fun onDismissed() {
                AnalyticsManager.logEvent("PhotoDetail_swipe_dismiss")
                videoController.pause()
                finish()
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
            }
        }
    }

    /** Bars that show and hide together with a tap. */
    private fun chromeBars(): List<View> {
        val isVideo = currentMedia()?.type == MediaType.VIDEO
        return listOfNotNull(binding.topBar, binding.bottomActions, binding.videoQuickTools.root.takeIf { isVideo })
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        videoController.onConfigurationChanged(newConfig)
        // Rotation is handled in place (video keeps playing); keep the pager on the same page.
        val current = binding.viewPager.currentItem
        updateVideoPanel(mediaList.getOrNull(current)?.type == MediaType.VIDEO)
        binding.viewPager.post {
            (binding.viewPager.getChildAt(0) as? RecyclerView)?.scrollToPosition(current)
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+: the system shows its own "Move to trash" confirmation.
            AnalyticsManager.logEvent("PhotoDetail_trash_request")
            performDelete(currentPosition, uri)
            return
        }
        AnalyticsManager.logEvent("PhotoDetail_deleteDialog_show")
        AppDialog.Builder(this)
            .setIcon(R.drawable.ic_g_delete, R.color.lumina_danger)
            .setTitle("Delete $type?")
            .setMessage("This $type will be deleted. This can't be undone.")
            .setDestructive()
            .setPositiveButton("Delete") { _, _ ->
                AnalyticsManager.logEvent("PhotoDetail_deleteDialog_confirm")
                performDelete(currentPosition, uri)
            }
            .setNegativeButton("Cancel") { _, _ ->
                AnalyticsManager.logEvent("PhotoDetail_deleteDialog_cancel")
            }
            .show()
    }

    /** Item waiting for a system prompt (Recycle Bin on 11+, "allow delete" on Android 10). */
    private var pendingDeleteUri: Uri? = null

    @Suppress("DEPRECATION")
    private fun performDelete(position: Int, uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Move to the system Recycle Bin so it can be restored for 30 days
            try {
                val pendingIntent = MediaStore.createTrashRequest(contentResolver, listOf(uri), true)
                pendingDeleteUri = uri
                startIntentSenderForResult(pendingIntent.intentSender, DELETE_REQUEST_CODE, null, 0, 0, 0)
            } catch (e: Exception) {
                Log.w(TAG, "Trash request failed", e)
                pendingDeleteUri = null
                Toast.makeText(this, "Couldn't move to Recycle Bin", Toast.LENGTH_SHORT).show()
            }
            return
        }
        // Android 7–10: permanent delete (needs WRITE_EXTERNAL_STORAGE; legacy storage on 10).
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) { deleteLegacy(uri) }
            when (outcome) {
                is LegacyDelete.Deleted -> onItemDeleted(uri, position, "Deleted")
                is LegacyDelete.NeedsApproval -> {
                    // Android 10 scoped storage: the user must allow deleting another app's file.
                    // Launched here on the main thread; onActivityResult re-runs the delete.
                    try {
                        pendingDeleteUri = uri
                        startIntentSenderForResult(outcome.sender, DELETE_APPROVAL_REQUEST_CODE, null, 0, 0, 0)
                    } catch (e: Exception) {
                        Log.w(TAG, "Delete approval request failed", e)
                        pendingDeleteUri = null
                        showDeleteFailed()
                    }
                }
                LegacyDelete.Failed -> showDeleteFailed()
            }
        }
    }

    private sealed class LegacyDelete {
        data object Deleted : LegacyDelete()
        data object Failed : LegacyDelete()
        class NeedsApproval(val sender: android.content.IntentSender) : LegacyDelete()
    }

    /** Runs on a background thread. */
    private fun deleteLegacy(uri: Uri): LegacyDelete = try {
        if (contentResolver.delete(uri, null, null) > 0) LegacyDelete.Deleted else LegacyDelete.Failed
    } catch (e: Exception) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e is android.app.RecoverableSecurityException) {
            LegacyDelete.NeedsApproval(e.userAction.actionIntent.intentSender)
        } else {
            Log.w(TAG, "Delete failed for $uri", e)
            LegacyDelete.Failed
        }
    }

    private fun showDeleteFailed() {
        Toast.makeText(this, "Couldn't delete this item. Check that Gallerai has storage permission.", Toast.LENGTH_LONG).show()
    }

    /** Removes the deleted item from the pager and moves to the next one. */
    private fun onItemDeleted(uri: Uri, fallbackPosition: Int, message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        val position = mediaList.indexOfFirst { it.uri == uri }.takeIf { it >= 0 } ?: fallbackPosition
        adapter?.removeAt(position)
        if (mediaList.isEmpty()) {
            finish()
        } else {
            val newPos = position.coerceAtMost(mediaList.size - 1)
            binding.viewPager.setCurrentItem(newPos, false)
            playVideoAtPosition(newPos)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != DELETE_REQUEST_CODE && requestCode != DELETE_APPROVAL_REQUEST_CODE) return
        val uri = pendingDeleteUri
        pendingDeleteUri = null
        if (resultCode != RESULT_OK) return
        val currentPosition = binding.viewPager.currentItem
        if (requestCode == DELETE_REQUEST_CODE) {
            onItemDeleted(uri ?: mediaList.getOrNull(currentPosition)?.uri ?: return, currentPosition, "Moved to Recycle Bin")
            return
        }
        // Android 10: access was granted, now delete for real (no Recycle Bin on this version).
        if (uri == null) return
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) { deleteLegacy(uri) }
            if (outcome is LegacyDelete.Deleted) onItemDeleted(uri, currentPosition, "Deleted")
            else showDeleteFailed()
        }
    }

    companion object {
        private const val TAG = "PhotoDetail"
        private const val DELETE_REQUEST_CODE = 1001
        private const val DELETE_APPROVAL_REQUEST_CODE = 1002
        private const val SLIDESHOW_INTERVAL_MS = 3000L
        private const val VIEWER_MAX_SIZE = 2048
        const val EXTRA_URI_LIST = "URI_LIST"
        /**
         * Most uris a screen passes in [EXTRA_URI_LIST]: bigger lists risk the Binder transaction
         * limit (TransactionTooLargeException), so callers pass no list (the viewer then loads the
         * album / library itself) or only the part around the tapped item.
         */
        const val MAX_URIS_IN_INTENT = 3000

        /**
         * [uris] limited to [MAX_URIS_IN_INTENT]: the window around [around] (the tapped item stays
         * in it), or the first ones when nothing was tapped (lists are newest first).
         */
        fun cappedUriList(uris: List<String>, around: String? = null): ArrayList<String> {
            if (uris.size <= MAX_URIS_IN_INTENT) return ArrayList(uris)
            val index = if (around != null) uris.indexOf(around).coerceAtLeast(0) else 0
            val start = (index - MAX_URIS_IN_INTENT / 2).coerceIn(0, uris.size - MAX_URIS_IN_INTENT)
            return ArrayList(uris.subList(start, start + MAX_URIS_IN_INTENT))
        }


        const val EXTRA_TITLE = "TITLE"
        const val EXTRA_SMART_ALBUM = "SMART_ALBUM"
        const val EXTRA_START_SLIDESHOW = "START_SLIDESHOW"
        const val SMART_VIDEOS = "VIDEOS"
        const val SMART_SCREENSHOTS = "SCREENSHOTS"
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
        binding.btnLike.setImageResource(if (isFavorite) R.drawable.ic_g_heart else R.drawable.ic_g_heart_outline)
        binding.btnLike.setColorFilter(
            if (isFavorite) this.appColor(R.color.lumina_favorite)
            else android.graphics.Color.WHITE
        )
        if (isFavorite) {
            binding.btnLike.animate().scaleX(1.25f).scaleY(1.25f).setDuration(120).withEndAction {
                binding.btnLike.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }.start()
        }
    }

    // ------------------------------------------------------------------ viewer extras

    private fun currentMedia(): MediaFile? = mediaList.getOrNull(binding.viewPager.currentItem)

    private fun updateTitle(position: Int) {
        val media = mediaList.getOrNull(position) ?: return
        val customTitle = intent.getStringExtra(EXTRA_TITLE)
        if (media.type == MediaType.VIDEO) {
            // MX shows the file name for videos.
            binding.tvTitle.text = media.name.substringBeforeLast('.').ifBlank { media.name }
            binding.tvSubtitle.text = if (media.dateAdded > 0) {
                val date = Date(media.displayDateSeconds * 1000L)
                listOfNotNull(customTitle, SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(date), media.album)
                    .joinToString(" · ")
            } else {
                "${position + 1} of ${mediaList.size}"
            }
        } else if (media.dateAdded > 0) {
            val date = Date(media.displayDateSeconds * 1000L)
            binding.tvTitle.text = SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(date)
            val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(date)
            binding.tvSubtitle.text = listOfNotNull(customTitle, time, media.album).joinToString(" · ")
        } else {
            binding.tvTitle.text = customTitle ?: media.name
            binding.tvSubtitle.text = "${position + 1} of ${mediaList.size}"
        }
    }

    private fun onViewerTapped() {
        if (isSlideshowRunning) stopSlideshow() else setChromeVisible(!chromeVisible)
    }

    private fun setChromeVisible(visible: Boolean) {
        chromeVisible = visible
        val target = if (visible) 1f else 0f
        chromeBars().forEach { bar ->
            if (visible) bar.isVisible = true
            bar.animate().alpha(target).setDuration(180).withEndAction {
                if (!chromeVisible) bar.isVisible = false
            }.start()
        }
        videoController.onChromeVisibilityChanged(visible)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (visible) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun showDetails() {
        val media = currentMedia() ?: return
        MediaDetailsBottomSheet.newInstance(media.uri, media.type == MediaType.VIDEO)
            .show(supportFragmentManager, MediaDetailsBottomSheet.TAG)
    }

    private fun showMoreMenu(anchor: View) {
        val media = currentMedia() ?: return
        val isVideo = media.type == MediaType.VIDEO
        val popup = PopupMenu(this, anchor, Gravity.END)
        val menu = popup.menu
        // Smart tools first (all on-device)
        if (isVideo) {
            menu.add(0, MENU_TRIM, 0, "Trim video")
            menu.add(0, MENU_GIF, 1, "Make GIF")
        } else {
            menu.add(0, MENU_COPY_TEXT, 0, "Copy text from photo")
            menu.add(0, MENU_ENHANCE, 1, "Auto enhance")
            menu.add(0, MENU_BLUR_FACES, 2, "Blur faces")
            menu.add(0, MENU_SHARE_NO_LOCATION, 3, "Share without location")
        }
        menu.add(0, MENU_SLIDESHOW, 10, "Slideshow").isEnabled = mediaList.size > 1
        if (!isVideo) menu.add(0, MENU_SET_AS, 11, "Set as wallpaper / contact")
        menu.add(0, MENU_COPY_TO, 12, "Copy to album…")
        if (selectionViewModel.canEditInPlace) {
            menu.add(0, MENU_MOVE_TO, 13, "Move to album…")
            menu.add(0, MENU_RENAME, 14, "Rename")
        }
        if (!isVideo) menu.add(0, MENU_PRINT, 15, "Print")
        menu.add(0, MENU_OPEN_WITH, 16, "Open with…")
        menu.add(0, MENU_DETAILS, 17, "Details")
        menu.add(0, MENU_LOCK, 18, "Move to Locked Folder")
        popup.setOnMenuItemClickListener { item ->
            AnalyticsManager.logEvent("PhotoDetail_menu_${item.itemId}")
            when (item.itemId) {
                MENU_SLIDESHOW -> startSlideshow()
                MENU_SET_AS -> setAs(media)
                MENU_OPEN_WITH -> openWith(media)
                MENU_DETAILS -> showDetails()
                MENU_LOCK -> confirmHide(media)
                MENU_COPY_TEXT -> startActivity(TextLensActivity.intent(this, media.uri))
                MENU_ENHANCE -> startActivity(QuickFixActivity.intent(this, media.uri, QuickFixActivity.MODE_ENHANCE))
                MENU_BLUR_FACES -> startActivity(QuickFixActivity.intent(this, media.uri, QuickFixActivity.MODE_BLUR_FACES))
                MENU_SHARE_NO_LOCATION -> selectionViewModel.shareWithoutLocation(listOf(media))
                MENU_TRIM -> {
                    videoController.pause()
                    startActivity(VideoToolsActivity.intent(this, media.uri, VideoToolsActivity.MODE_TRIM))
                }
                MENU_GIF -> {
                    videoController.pause()
                    startActivity(VideoToolsActivity.intent(this, media.uri, VideoToolsActivity.MODE_GIF))
                }
                MENU_COPY_TO -> SelectionMoreMenu.pickAlbum(this, selectionViewModel, "Copy to album", listOf(media)) {
                    selectionViewModel.copyToAlbum(listOf(media), it)
                }
                MENU_MOVE_TO -> SelectionMoreMenu.pickAlbum(this, selectionViewModel, "Move to album", listOf(media)) {

                    selectionViewModel.moveToAlbum(listOf(media), it)
                }
                MENU_RENAME -> SelectionMoreMenu.askRename(this, selectionViewModel, media)
                MENU_PRINT -> printPhoto(media)
            }
            true
        }
        popup.show()
    }

    /** Prints the photo with the system print dialog (or "Save as PDF"). */
    private fun printPhoto(media: MediaFile) {
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { BitmapLoader.decodeUpright(this@PhotoDetailActivity, media.uri, 3000) }
            if (bitmap == null) {
                Toast.makeText(this@PhotoDetailActivity, "Couldn't open this photo", Toast.LENGTH_SHORT).show()
                return@launch
            }
            try {
                PrintHelper(this@PhotoDetailActivity).apply { scaleMode = PrintHelper.SCALE_MODE_FIT }
                    .printBitmap(media.name.substringBeforeLast('.'), bitmap)
            } catch (e: Exception) {
                Toast.makeText(this@PhotoDetailActivity, "Printing isn't available on this phone", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Organise actions (copy/move/rename/share without location) run in the shared ViewModel. */
    private fun observeOrganizeEvents() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                selectionViewModel.events.collect { event ->
                    when (event) {
                        is MediaSelectionViewModel.SelectionEvent.Message ->
                            Toast.makeText(this@PhotoDetailActivity, event.text, Toast.LENGTH_SHORT).show()
                        is MediaSelectionViewModel.SelectionEvent.StartIntent -> startActivity(event.intent)
                        is MediaSelectionViewModel.SelectionEvent.LaunchIntentSender -> {
                            pendingOrganizeAction = event.action
                            organizeLauncher.launch(IntentSenderRequest.Builder(event.sender).build())
                        }
                        MediaSelectionViewModel.SelectionEvent.Changed -> refreshCurrentName()
                    }
                }
            }
        }
    }

    /** After a rename/move the file keeps its id; refresh the title from MediaStore. */
    private fun refreshCurrentName() {
        val position = binding.viewPager.currentItem
        val media = mediaList.getOrNull(position) ?: return
        lifecycleScope.launch {
            val name = withContext(Dispatchers.IO) { MediaOrganizer(this@PhotoDetailActivity).queryDisplayName(media.uri) }
            if (name != null && name != media.name && position < mediaList.size && mediaList[position].uri == media.uri) {
                mediaList[position] = media.copy(name = name)
                updateTitle(position)
            }
        }
    }

    private fun startSlideshow() {
        if (mediaList.size < 2 || videoController.isLocked) return
        isSlideshowRunning = true
        setChromeVisible(false)
        binding.tvSlideshow.isVisible = true
        binding.tvSlideshow.postDelayed({ binding.tvSlideshow.isVisible = false }, 2000)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding.viewPager.setPageTransformer { page, position ->
            page.translationX = -position * page.width
            page.alpha = 1f - abs(position)
        }
        slideshowHandler.postDelayed(slideshowRunnable, SLIDESHOW_INTERVAL_MS)
    }

    private fun stopSlideshow() {
        if (!isSlideshowRunning) return
        isSlideshowRunning = false
        slideshowHandler.removeCallbacks(slideshowRunnable)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding.viewPager.setPageTransformer(null)
        binding.viewPager.post {
            (binding.viewPager.getChildAt(0) as? RecyclerView)?.let { rv ->
                for (i in 0 until rv.childCount) {
                    rv.getChildAt(i).apply { alpha = 1f; translationX = 0f }
                }
            }
        }
        binding.tvSlideshow.isVisible = false
        setChromeVisible(true)
        Toast.makeText(this, "Slideshow stopped", Toast.LENGTH_SHORT).show()
    }

    private fun setAs(media: MediaFile) {
        val mime = contentResolver.getType(media.uri) ?: "image/*"
        val intent = Intent(Intent.ACTION_ATTACH_DATA).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            setDataAndType(media.uri, mime)
            putExtra("mimeType", mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, "Set as"))
        } catch (_: Exception) {
            Toast.makeText(this, "No app available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openWith(media: MediaFile) {
        val mime = contentResolver.getType(media.uri) ?: if (media.type == MediaType.VIDEO) "video/*" else "image/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(media.uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, "Open with"))
        } catch (_: Exception) {
            Toast.makeText(this, "No app available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmHide(media: MediaFile) {
        AppDialog.Builder(this)
            .setIcon(R.drawable.ic_g_lock, R.color.lumina_accent)
            .setTitle("Move to Locked Folder?")
            .setMessage("It will be hidden from Photos and Albums. You can restore it from Locked Folder anytime.")
            .setPositiveButton("Move") { _, _ ->
                lifecycleScope.launch {
                    repository.hide(listOf(media.uri))
                    val position = mediaList.indexOf(media)
                    adapter?.removeAt(position)
                    Toast.makeText(this@PhotoDetailActivity, "Moved to Locked Folder", Toast.LENGTH_SHORT).show()
                    if (mediaList.isEmpty()) finish() else updateTitle(binding.viewPager.currentItem)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private val MENU_SLIDESHOW = 1
    private val MENU_SET_AS = 2
    private val MENU_OPEN_WITH = 3
    private val MENU_DETAILS = 4
    private val MENU_LOCK = 5
    private val MENU_COPY_TEXT = 6
    private val MENU_ENHANCE = 7
    private val MENU_BLUR_FACES = 8
    private val MENU_SHARE_NO_LOCATION = 9
    private val MENU_TRIM = 10
    private val MENU_GIF = 11
    private val MENU_COPY_TO = 12
    private val MENU_MOVE_TO = 13
    private val MENU_RENAME = 14
    private val MENU_PRINT = 15

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
        videoController.pause()
        if (isSlideshowRunning) stopSlideshow()
    }

    override fun onDestroy() {
        super.onDestroy()
        videoController.release()
    }
}
