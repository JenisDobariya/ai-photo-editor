package com.photo_editor.my_photo_editor.ui.video

import android.content.ContentValues
import com.photo_editor.my_photo_editor.utils.appColor
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.media.audiofx.Equalizer
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.TextureView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.SeekBar
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import android.content.Intent
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.photo_editor.my_photo_editor.R
import com.photo_editor.my_photo_editor.databinding.ViewVideoControlsBinding
import com.photo_editor.my_photo_editor.databinding.ViewVideoOverlayBinding
import com.photo_editor.my_photo_editor.databinding.ViewVideoPanelBinding
import com.photo_editor.my_photo_editor.databinding.ViewVideoQuickToolsBinding
import android.util.TypedValue
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import com.photo_editor.my_photo_editor.utils.AnalyticsManager
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * MX-style video playback for the media viewer.
 *
 * One ExoPlayer is shared by every video page: [attach] moves it onto the page that is on
 * screen and [detach] takes it off again. The controller owns the playback panel
 * (seek bar, previous/next, play/pause, lock, resize), the quick-tools row under the top bar,
 * the MX-style side panel used for every list of options, and the gestures on the video:
 * tap toggles the chrome, double-tap left/right seeks 10s, double-tap centre plays/pauses,
 * vertical swipe on the left third changes brightness and on the right third the volume
 * (the middle third is drag-to-close). It also has MX's tools: audio track, subtitles
 * (embedded or from a file), colour filters / night mode, equalizer, sleep timer, loop,
 * A-B repeat, mute, speed, screenshot and screen rotation.
 * Horizontal swipes are left to the ViewPager so paging between items keeps working.
 */
@OptIn(UnstableApi::class)
class VideoPlayerController(
    private val activity: AppCompatActivity,
    private val controls: ViewVideoControlsBinding,
    private val overlay: ViewVideoOverlayBinding,
    private val quickTools: ViewVideoQuickToolsBinding,
    private val panel: ViewVideoPanelBinding,
    private val callbacks: Callbacks
) {

    interface Callbacks {
        /** Single tap on the video (same meaning as a tap on a photo). */
        fun onVideoTapped()
        fun isChromeVisible(): Boolean
        fun setChromeVisible(visible: Boolean)
        fun onLockChanged(locked: Boolean)
        /** Previous / next item in the viewer (MX's skip buttons). */
        fun onPrevious()
        fun onNext()
        /** Open the system file picker for a subtitle file; the result goes to [loadSubtitle]. */
        fun onPickSubtitleFile()
        /** Height of the bottom controls, so subtitles can move above them. */
        fun bottomChromeHeight(): Int
    }

    private val context: Context get() = activity
    private val handler = Handler(Looper.getMainLooper())
    private val audioManager = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val touchSlop = ViewConfiguration.get(activity).scaledTouchSlop
    private val density = activity.resources.displayMetrics.density

    private var playerView: PlayerView? = null
    private var thumbView: View? = null
    private var loadingView: View? = null
    var currentUri: Uri? = null
        private set

    private val savedPositions = mutableMapOf<Uri, Long>()
    private var resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
    private var speed = 1f
    private var isScrubbing = false
    private var showRemaining = true
    private var abStart = -1L
    private var abEnd = -1L
    private var orientationChanged = false

    var isLocked = false
        private set

    val player: ExoPlayer by lazy { buildPlayer() }
    private var playerBuilt = false

    // ------------------------------------------------------------------ player

    private fun buildPlayer(): ExoPlayer {
        playerBuilt = true
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(1500, 5000, 250, 500)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        // If the preferred decoder fails to start, try the next one that claims the format.
        val renderers = DefaultRenderersFactory(activity).setEnableDecoderFallback(true)
        return ExoPlayer.Builder(activity, renderers)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build().apply {
                repeatMode = Player.REPEAT_MODE_OFF
                addListener(playerListener)
            }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlayButton()
            playerView?.keepScreenOn = isPlaying
            if (isPlaying) scheduleAutoHide() else cancelAutoHide()
        }

        override fun onPlaybackStateChanged(state: Int) {
            loadingView?.isVisible = state == Player.STATE_BUFFERING
            when (state) {
                Player.STATE_READY -> updateProgress()
                Player.STATE_ENDED -> {
                    if (abEnd > 0) {
                        player.seekTo(abStart)
                        player.play()
                        return
                    }
                    updateProgress()
                    updatePlayButton()
                    currentUri?.let { savedPositions.remove(it) }
                    if (!isLocked) callbacks.setChromeVisible(true)
                }
                else -> {}
            }
        }

        override fun onCues(cueGroup: androidx.media3.common.text.CueGroup) {
            overlay.videoSubtitles.setCues(cueGroup.cues)
        }

        override fun onRenderedFirstFrame() {
            thumbView?.isVisible = false
        }

        override fun onPlayerError(error: PlaybackException) {
            AnalyticsManager.logEvent("PhotoDetail_video_error")
            loadingView?.isVisible = false
            thumbView?.isVisible = true
            showUnsupported(error)
        }
    }

    /** Puts the shared player on [view] and starts playback (no-op if already there). */
    fun attach(view: PlayerView, thumb: View, loading: View, uri: Uri) {
        if (playerView === view && currentUri == uri) {
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            return
        }
        detach()
        playerView = view
        thumbView = thumb
        loadingView = loading
        currentUri = uri

        unsupportedView(view)?.isVisible = false
        view.isVisible = true
        view.resizeMode = resizeMode
        view.player = player
        // A new video starts with its own default audio track (subtitle on/off is kept).
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverrides()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .build()
        player.setMediaItem(buildMediaItem(uri), savedPositions[uri] ?: 0L)
        styleSubtitles(view)
        applyFilter()
        player.setPlaybackSpeed(speed)
        player.prepare()
        player.playWhenReady = true

        abStart = -1L
        abEnd = -1L
        buildQuickTools()
        controls.seekBar.progress = 0
        controls.seekBar.secondaryProgress = 0
        controls.tvPosition.text = formatTime(savedPositions[uri] ?: 0L)
        controls.tvDuration.text = "--:--"
        updatePlayButton()
        handler.removeCallbacks(progressRunnable)
        handler.post(progressRunnable)
    }

    /** Stops playback and releases the current page (remembering where it stopped). */
    fun detach() {
        handler.removeCallbacks(progressRunnable)
        cancelAutoHide()
        hidePanel(animate = false)
        val uri = currentUri
        if (uri != null && playerBuilt) {
            val pos = player.currentPosition
            if (player.playbackState != Player.STATE_ENDED && pos > 1000) savedPositions[uri] = pos
            player.stop()
            player.clearMediaItems()
        }
        playerView?.let {
            unsupportedView(it)?.isVisible = false
            it.player = null
            it.keepScreenOn = false
            (it.videoSurfaceView as? TextureView)?.setLayerPaint(null)
        }
        thumbView?.isVisible = true
        loadingView?.isVisible = false
        overlay.videoSubtitles.setCues(emptyList())
        playerView = null
        thumbView = null
        loadingView = null
        currentUri = null
    }

    fun isAttachedTo(view: PlayerView) = playerView === view

    // ------------------------------------------------------------------ unsupported videos

    private fun unsupportedView(view: PlayerView): View? =
        (view.parent as? View)?.findViewById(R.id.videoUnsupported)

    /**
     * Replaces the black screen with an explanation. Typical case: a 4K (or 4K 60 fps HDR)
     * video on a phone whose hardware decoder stops at 1080p — no player can decode it there.
     */
    private fun showUnsupported(error: PlaybackException) {
        val view = playerView ?: return
        val panel = unsupportedView(view) ?: run {
            Toast.makeText(context, "Can't play this video", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = currentUri
        val format = (error as? ExoPlaybackException)
            ?.takeIf { it.type == ExoPlaybackException.TYPE_RENDERER }
            ?.rendererFormat
            ?: player.videoFormat
        val title = panel.findViewById<TextView>(R.id.tvUnsupportedTitle)
        val detail = panel.findViewById<TextView>(R.id.tvUnsupportedDetail)
        val video = format?.takeIf { it.width > 0 && it.height > 0 }
        if (video != null) {
            val limit = VideoSupport.maxPlayableLabel(video.sampleMimeType)
            val tooBig = !VideoSupport.isSizeSupported(video.sampleMimeType, video.width, video.height)
            title.text = if (tooBig) "This video is too big for your phone" else "Can't play this video"
            detail.text = buildString {
                append(VideoSupport.describe(video))
                if (tooBig && limit != null) append("\nYour phone's video chip plays up to ").append(limit).append('.')
                else append("\nThis phone has no decoder for this format.")
            }
        } else {
            title.text = "Can't play this video"
            detail.text = "The file may be damaged or use a format this phone doesn't support."
        }
        panel.findViewById<View>(R.id.btnUnsupportedOpenWith).setOnClickListener {
            if (uri == null) return@setOnClickListener
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "video/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                activity.startActivity(Intent.createChooser(intent, "Open with"))
            } catch (e: Exception) {
                Toast.makeText(context, "No other video app found", Toast.LENGTH_SHORT).show()
            }
        }
        panel.isVisible = true
        // Nothing is playing: show the play icon (tapping it retries) instead of pause.
        player.playWhenReady = false
        updatePlayButton()
    }

    fun pause() {
        if (playerBuilt) player.pause()
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        detach()
        try { equalizer?.release() } catch (_: Exception) {}
        equalizer = null
        if (playerBuilt) player.release()
    }

    private fun togglePlayPause() {
        if (currentUri == null) return
        when {
            player.playbackState == Player.STATE_ENDED -> {
                player.seekTo(0)
                player.play()
            }
            player.isPlaying -> player.pause()
            else -> {
                if (player.playbackState == Player.STATE_IDLE) player.prepare()
                player.play()
            }
        }
    }

    private fun seekBy(deltaMs: Long) {
        if (currentUri == null) return
        val duration = player.duration.takeIf { it != C.TIME_UNSET } ?: return
        player.setSeekParameters(SeekParameters.EXACT)
        player.seekTo((player.currentPosition + deltaMs).coerceIn(0, duration))
        updateProgress()
    }

    // ------------------------------------------------------------------ panel

    init {
        controls.btnPlayPause.setOnClickListener {
            AnalyticsManager.logEvent("PhotoDetail_video_playPause")
            togglePlayPause(); scheduleAutoHide()
        }
        controls.btnPrev.setOnClickListener { callbacks.onPrevious() }
        controls.btnNext.setOnClickListener { callbacks.onNext() }
        controls.btnLock.setOnClickListener {
            AnalyticsManager.logEvent("PhotoDetail_video_lock")
            setLocked(true)
        }
        overlay.btnUnlock.setOnClickListener { setLocked(false) }
        controls.btnAspect.setOnClickListener {
            AnalyticsManager.logEvent("PhotoDetail_video_resize")
            cycleResizeMode(); scheduleAutoHide()
        }
        controls.tvDuration.setOnClickListener {
            showRemaining = !showRemaining
            updateProgress()
        }

        controls.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser || currentUri == null) return
                val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
                val target = duration * progress / seekBar.max
                controls.tvPosition.text = formatTime(target)
                showCenterMessage("${formatTime(target)} / ${formatTime(duration)}", 0)
                player.setSeekParameters(SeekParameters.CLOSEST_SYNC)
                player.seekTo(target)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                isScrubbing = true
                cancelAutoHide()
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                isScrubbing = false
                val duration = if (currentUri != null) player.duration.takeIf { it != C.TIME_UNSET && it > 0 } else null
                if (duration != null) {
                    player.setSeekParameters(SeekParameters.EXACT)
                    player.seekTo(duration * seekBar.progress / seekBar.max)
                }
                hideCenterMessage(400)
                scheduleAutoHide()
            }
        })
    }

    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 250)
        }
    }

    private fun updateProgress() {
        if (currentUri == null) return
        val duration = player.duration
        if (duration == C.TIME_UNSET || duration <= 0) return
        val pos = player.currentPosition.coerceIn(0, duration)
        if (abEnd > 0 && pos >= abEnd) player.seekTo(abStart)
        controls.tvDuration.text = if (showRemaining) "-" + formatTime(duration - pos) else formatTime(duration)
        if (isScrubbing) return
        controls.tvPosition.text = formatTime(pos)
        val max = controls.seekBar.max
        controls.seekBar.progress = (pos * max / duration).toInt()
        controls.seekBar.secondaryProgress = (player.bufferedPosition.coerceIn(0, duration) * max / duration).toInt()
    }

    private fun updatePlayButton() {
        val icon = when {
            currentUri != null && player.playbackState == Player.STATE_ENDED -> R.drawable.ic_v_replay
            currentUri != null && player.playWhenReady -> R.drawable.ic_v_pause
            else -> R.drawable.ic_v_play
        }
        controls.btnPlayPause.setImageResource(icon)
    }

    private fun cycleResizeMode() {
        val (mode, label) = when (resizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Crop"
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL to "Stretch"
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT to "Fit to screen"
        }
        resizeMode = mode
        playerView?.resizeMode = mode
        showCenterMessage(label, 900)
    }

    private fun speedLabel(s: Float): String =
        if (s % 1f == 0f) "${s.toInt()}x" else "${s}x"

    /** MX speed list. */
    fun showSpeedPanel() {
        val speeds = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f)
        showPanel("Playback speed", speeds.map { if (it == 1f) "1x (Normal)" else speedLabel(it) },
            speeds.indexOfFirst { it == speed }) { which ->
            speed = speeds[which]
            AnalyticsManager.logEvent("PhotoDetail_video_speed")
            if (playerBuilt) player.setPlaybackSpeed(speed)
            showCenterMessage("Speed ${speedLabel(speed)}", 800)
            buildQuickTools()
        }
    }

    /** True while the (normally translucent) viewer has been made opaque so it can rotate. */
    private var madeOpaque = false

    /** Whether the quick tools currently include the rotate button. */
    private var rotateShown = true

    /**
     * Whether asking for an orientation can work here. Android 16+ ignores orientation requests
     * on large screens (smallest width ≥ 600dp), and split-screen windows can't rotate either.
     */
    private val canRotate: Boolean
        get() = !(Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA &&
            activity.resources.configuration.smallestScreenWidthDp >= 600) &&
            !activity.isInMultiWindowMode

    /** The landscape request didn't rotate the screen: undo the opaque switch and the request. */
    private val rotationTimeoutRunnable = Runnable {
        if (activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) return@Runnable
        orientationChanged = false
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        if (madeOpaque && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.setTranslucent(true)
            madeOpaque = false
        }
        showCenterMessage("Rotation isn't available here", 1200)
    }

    private fun toggleOrientation() {
        if (!canRotate) {
            showCenterMessage("Rotation isn't available here", 1200)
            return
        }
        val landscape = activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        // A translucent activity takes its orientation from the screen behind it, so the
        // viewer becomes opaque before it asks for landscape (Android 11+).
        if (!landscape && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !madeOpaque) {
            madeOpaque = activity.setTranslucent(false)
        }
        activity.requestedOrientation = if (landscape) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        orientationChanged = true
        // If the system ignores the request, don't leave the viewer opaque (drag-to-close
        // would show black instead of the gallery).
        handler.removeCallbacks(rotationTimeoutRunnable)
        if (!landscape) handler.postDelayed(rotationTimeoutRunnable, ROTATION_TIMEOUT_MS)
    }

    /** Gives orientation back to the system once the user leaves the video. */
    fun resetOrientation() {
        if (!orientationChanged) return
        orientationChanged = false
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onConfigurationChanged(activity.resources.configuration)
    }

    /** Back in portrait: become translucent again so drag-to-close shows the gallery. */
    fun onConfigurationChanged(config: Configuration) {
        if (config.orientation == Configuration.ORIENTATION_LANDSCAPE) handler.removeCallbacks(rotationTimeoutRunnable)
        // Quick tools depend on the screen (rotate is hidden where it can't work).
        if (canRotate != rotateShown) buildQuickTools()
        if (madeOpaque && config.orientation == Configuration.ORIENTATION_PORTRAIT &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        ) {
            activity.setTranslucent(true)
            madeOpaque = false
        }
    }

    /** Dims Previous/Next at the ends of the list. */
    fun setNavigationAvailable(hasPrevious: Boolean, hasNext: Boolean) {
        controls.btnPrev.isEnabled = hasPrevious
        controls.btnPrev.alpha = if (hasPrevious) 1f else 0.35f
        controls.btnNext.isEnabled = hasNext
        controls.btnNext.alpha = if (hasNext) 1f else 0.35f
    }

    // ------------------------------------------------------------------ lock

    fun setLocked(locked: Boolean) {
        if (isLocked == locked) return
        isLocked = locked
        callbacks.onLockChanged(locked)
        if (locked) {
            showCenterMessage("Screen locked", 900)
            flashUnlockButton()
        } else {
            handler.removeCallbacks(hideUnlockRunnable)
            overlay.btnUnlock.isVisible = false
            showCenterMessage("Screen unlocked", 700)
        }
    }

    private val hideUnlockRunnable = Runnable { overlay.btnUnlock.isVisible = false }

    /** Shows the unlock button for a moment (taps and Back while locked). */
    fun flashUnlockButton() {
        overlay.btnUnlock.isVisible = true
        handler.removeCallbacks(hideUnlockRunnable)
        handler.postDelayed(hideUnlockRunnable, 2500)
    }

    // ------------------------------------------------------------------ auto-hide

    private val autoHideRunnable = Runnable {
        if (currentUri != null && player.isPlaying && !isScrubbing && callbacks.isChromeVisible() &&
            !panel.panelScrim.isVisible
        ) {
            callbacks.setChromeVisible(false)
        }
    }

    /** Hides the chrome after a few seconds of playback without interaction, like MX. */
    fun scheduleAutoHide() {
        handler.removeCallbacks(autoHideRunnable)
        if (currentUri != null && playerBuilt && player.isPlaying && callbacks.isChromeVisible() &&
            !panel.panelScrim.isVisible
        ) {
            handler.postDelayed(autoHideRunnable, AUTO_HIDE_MS)
        }
    }

    private fun cancelAutoHide() = handler.removeCallbacks(autoHideRunnable)

    // ------------------------------------------------------------------ gestures

    private enum class Swipe { NONE, BRIGHTNESS, VOLUME }

    private var touchedView: View? = null
    private var swipe = Swipe.NONE
    private var brightness = -1f
    private var volume = 0f
    private var lastSeekSide = 0
    private var lastSeekAt = 0L
    private var seekAccumulated = 0L

    private val gestureDetector = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            callbacks.onVideoTapped()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val v = touchedView ?: return false
            if (!isAttachedTo(v as? PlayerView ?: return false)) return false
            val third = v.width / 3f
            when {
                e.x < third -> doubleTapSeek(-1)
                e.x > v.width - third -> doubleTapSeek(1)
                else -> {
                    togglePlayPause()
                    showCenterMessage(if (player.playWhenReady) "Play" else "Pause", 500)
                }
            }
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            val v = touchedView ?: return false
            val start = e1 ?: return false
            if (swipe == Swipe.NONE) {
                val totalX = e2.x - start.x
                val totalY = e2.y - start.y
                val edge = 40 * density
                val nearSystemEdge = start.y < edge || start.y > v.height - edge
                if (nearSystemEdge || abs(totalY) < touchSlop * 1.5f || abs(totalY) < abs(totalX) * 1.4f) return false
                // Left third = brightness, right third = volume; the middle is drag-to-close.
                val third = v.width / 3f
                swipe = when {
                    start.x < third -> Swipe.BRIGHTNESS
                    start.x > v.width - third -> Swipe.VOLUME
                    else -> return false
                }
                v.parent?.requestDisallowInterceptTouchEvent(true)
                if (swipe == Swipe.BRIGHTNESS) brightness = currentBrightness()
                else volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
            }
            val fraction = distanceY / (v.height * 0.6f)
            when (swipe) {
                Swipe.BRIGHTNESS -> setBrightness(brightness + fraction)
                Swipe.VOLUME -> setVolume(volume + fraction * audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
                Swipe.NONE -> {}
            }
            return true
        }
    })

    private val lockedTapDetector = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (overlay.btnUnlock.isVisible) hideUnlockRunnable.run() else flashUnlockButton()
            return true
        }
    })

    /** Touch handling for every video page's PlayerView. */
    val touchListener = View.OnTouchListener { v, event ->
        touchedView = v
        if (isLocked) {
            v.parent?.requestDisallowInterceptTouchEvent(true)
            lockedTapDetector.onTouchEvent(event)
            return@OnTouchListener true
        }
        gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (swipe != Swipe.NONE) {
                swipe = Swipe.NONE
                v.parent?.requestDisallowInterceptTouchEvent(false)
                handler.postDelayed(hideLevelRunnable, 600)
            }
        }
        true
    }

    private fun doubleTapSeek(side: Int) {
        val now = System.currentTimeMillis()
        seekAccumulated = if (side == lastSeekSide && now - lastSeekAt < 1200) seekAccumulated + SEEK_STEP_MS else SEEK_STEP_MS
        lastSeekSide = side
        lastSeekAt = now
        seekBy(side * SEEK_STEP_MS)
        val label = "${seekAccumulated / 1000} seconds"
        val bubble = if (side < 0) overlay.seekBackBubble else overlay.seekForwardBubble
        val other = if (side < 0) overlay.seekForwardBubble else overlay.seekBackBubble
        (if (side < 0) overlay.tvSeekBack else overlay.tvSeekForward).text = label
        other.isVisible = false
        bubble.animate().cancel()
        bubble.alpha = 1f
        bubble.isVisible = true
        bubble.animate().alpha(0f).setStartDelay(500).setDuration(250)
            .withEndAction { bubble.isVisible = false; bubble.alpha = 1f }.start()
        scheduleAutoHide()
    }

    private fun currentBrightness(): Float {
        val attr = activity.window.attributes.screenBrightness
        if (attr >= 0f) return attr
        return try {
            val sys = Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            (sys / 255f).coerceIn(0.05f, 1f)
        } catch (_: Exception) {
            0.5f
        }
    }

    private fun setBrightness(value: Float) {
        brightness = value.coerceIn(0.01f, 1f)
        val lp = activity.window.attributes
        lp.screenBrightness = brightness
        activity.window.attributes = lp
        showLevel(R.drawable.ic_v_brightness, (brightness * 100).roundToInt())
    }

    private fun setVolume(value: Float) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        volume = value.coerceIn(0f, max.toFloat())
        val step = volume.roundToInt()
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, step, 0)
        } catch (_: SecurityException) {
            // Do-not-disturb can refuse volume changes; just keep showing the level.
        }
        val percent = if (max > 0) step * 100 / max else 0
        showLevel(if (step == 0) R.drawable.ic_v_volume_off else R.drawable.ic_v_volume, percent)
    }

    private val hideLevelRunnable = Runnable { overlay.levelIndicator.isVisible = false }

    private fun showLevel(icon: Int, percent: Int) {
        handler.removeCallbacks(hideLevelRunnable)
        overlay.tvCenterMessage.isVisible = false
        overlay.ivLevelIcon.setImageResource(icon)
        overlay.levelProgress.progress = percent
        overlay.tvLevel.text = percent.toString()
        overlay.levelIndicator.isVisible = true
    }

    private val hideMessageRunnable = Runnable { overlay.tvCenterMessage.isVisible = false }

    private fun showCenterMessage(text: String, hideAfterMs: Long) {
        handler.removeCallbacks(hideMessageRunnable)
        overlay.levelIndicator.isVisible = false
        overlay.tvCenterMessage.text = text
        overlay.tvCenterMessage.isVisible = true
        if (hideAfterMs > 0) handler.postDelayed(hideMessageRunnable, hideAfterMs)
    }

    private fun hideCenterMessage(delayMs: Long) {
        handler.removeCallbacks(hideMessageRunnable)
        handler.postDelayed(hideMessageRunnable, delayMs)
    }

    // ------------------------------------------------------------------ MX tools

    private val sideloadedSubtitles = mutableMapOf<Uri, Pair<Uri, String>>()
    private var filterIndex = 0
    private var subtitleScale = 1f
    private var equalizer: Equalizer? = null
    private var sleepMinutes = 0
    private var resumeAfterPick = false

    var isLooping = false
        private set
    var isMuted = false
        private set

    private fun buildMediaItem(uri: Uri): MediaItem {
        val builder = MediaItem.Builder().setUri(uri)
        sideloadedSubtitles[uri]?.let { (subUri, mime) ->
            builder.setSubtitleConfigurations(
                listOf(
                    MediaItem.SubtitleConfiguration.Builder(subUri)
                        .setMimeType(mime)
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .setLabel(displayName(subUri) ?: "Subtitle file")
                        .build()
                )
            )
        }
        return builder.build()
    }

    private data class TrackOption(val label: String, val group: Tracks.Group, val index: Int)

    private fun trackOptions(type: Int): List<TrackOption> {
        val list = mutableListOf<TrackOption>()
        for (group in player.currentTracks.groups) {
            if (group.type != type) continue
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                list.add(TrackOption(trackLabel(group.getTrackFormat(i), list.size + 1, type), group, i))
            }
        }
        return list
    }

    private fun trackLabel(f: Format, n: Int, type: Int): String {
        val parts = mutableListOf<String>()
        // Some phones write junk language codes (e.g. "```"); only show real ones.
        f.language?.takeIf { LANGUAGE_CODE.matches(it) && it != C.LANGUAGE_UNDETERMINED }?.let {
            val name = Locale.forLanguageTag(it).displayName
            if (name.isNotBlank() && !name.equals(it, ignoreCase = true)) parts.add(name)
        }
        f.label?.takeIf { it.isNotBlank() && it !in parts }?.let { parts.add(it) }
        if (type == C.TRACK_TYPE_AUDIO && f.channelCount != Format.NO_VALUE) {
            parts.add(
                when (f.channelCount) {
                    1 -> "Mono"; 2 -> "Stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> "${f.channelCount} ch"
                }
            )
        }
        val kind = if (type == C.TRACK_TYPE_AUDIO) "Audio track" else "Subtitle"
        return if (parts.isEmpty()) "$kind #$n" else "$kind #$n - ${parts.joinToString(" · ")}"
    }

    private fun requireVideo(): Boolean {
        if (currentUri == null) {
            Toast.makeText(context, "Open a video first", Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    /** MX "Audio track": pick the audio language/track, or disable it. */
    fun showAudioTrackDialog() {
        if (!requireVideo()) return
        val options = trackOptions(C.TRACK_TYPE_AUDIO)
        if (options.isEmpty()) {
            Toast.makeText(context, "This video has no audio track", Toast.LENGTH_SHORT).show()
            return
        }
        val disabled = C.TRACK_TYPE_AUDIO in player.trackSelectionParameters.disabledTrackTypes
        val checked = if (disabled) options.size
        else options.indexOfFirst { it.group.isTrackSelected(it.index) }.coerceAtLeast(0)
        showPanel("Audio track", options.map { it.label } + "Disable", checked) { which ->
            AnalyticsManager.logEvent("PhotoDetail_video_audioTrack")
            val params = player.trackSelectionParameters.buildUpon()
            if (which == options.size) {
                params.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
            } else {
                val o = options[which]
                params.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                    .setOverrideForType(TrackSelectionOverride(o.group.mediaTrackGroup, o.index))
            }
            player.trackSelectionParameters = params.build()
        }
    }

    /** MX "Subtitle": embedded tracks or off, plus open a subtitle file and text size. */
    fun showSubtitleDialog() {
        if (!requireVideo()) return
        val options = trackOptions(C.TRACK_TYPE_TEXT)
        val disabled = C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes
        val selected = options.indexOfFirst { it.group.isTrackSelected(it.index) }
        val checked = if (disabled || selected < 0) 0 else selected + 1
        showPanel(
            "Subtitle",
            listOf("Off") + options.map { it.label },
            checked,
            listOf(
                PanelAction("Open subtitle file…") {
                    resumeAfterPick = player.isPlaying
                    callbacks.onPickSubtitleFile()
                },
                PanelAction("Subtitle size") { showSubtitleSizeDialog() }
            )
        ) { which ->
            AnalyticsManager.logEvent("PhotoDetail_video_subtitles")
            player.trackSelectionParameters = if (which == 0) {
                player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            } else {
                val o = options[which - 1]
                player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .setOverrideForType(TrackSelectionOverride(o.group.mediaTrackGroup, o.index))
                    .build()
            }
        }
    }

    private fun showSubtitleSizeDialog() {
        val sizes = floatArrayOf(0.8f, 1f, 1.25f, 1.5f)
        showPanel("Subtitle size", listOf("Small", "Normal", "Large", "Extra large"),
            sizes.indexOfFirst { it == subtitleScale }.coerceAtLeast(1)) { which ->
            subtitleScale = sizes[which]
            playerView?.let { styleSubtitles(it) }
        }
    }

    /** Adds a subtitle file (.srt, .vtt, .ass/.ssa, .ttml) to the current video. */
    fun loadSubtitle(subUri: Uri) {
        val video = currentUri ?: return
        val name = displayName(subUri)?.lowercase(Locale.ROOT) ?: ""
        val mime = when {
            name.endsWith(".vtt") -> MimeTypes.TEXT_VTT
            name.endsWith(".ass") || name.endsWith(".ssa") -> MimeTypes.TEXT_SSA
            name.endsWith(".ttml") || name.endsWith(".xml") || name.endsWith(".dfxp") -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.APPLICATION_SUBRIP
        }
        sideloadedSubtitles[video] = subUri to mime
        val position = player.currentPosition
        val playWhenReady = player.playWhenReady || resumeAfterPick
        resumeAfterPick = false
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .build()
        player.setMediaItem(buildMediaItem(video), position)
        player.prepare()
        player.playWhenReady = playWhenReady
        showCenterMessage("Subtitles added", 900)
    }

    private fun displayName(uri: Uri): String? = try {
        activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) {
        null
    } ?: uri.lastPathSegment

    private fun styleSubtitles(view: PlayerView) {
        // The PlayerView's own subtitle view lives inside the video frame; ours is full screen.
        view.subtitleView?.visibility = View.GONE
        overlay.videoSubtitles.apply {
            setApplyEmbeddedStyles(true)
            setApplyEmbeddedFontSizes(false)
            setStyle(
                CaptionStyleCompat(
                    Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT,
                    CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, null
                )
            )
            setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, SUBTITLE_TEXT_SP * subtitleScale)
            setBottomPaddingFraction(SUBTITLE_BOTTOM_FRACTION)
        }
        positionSubtitles(callbacks.isChromeVisible(), animate = false)
    }

    /** Subtitles sit at the bottom of the screen, and move up above the controls while shown. */
    private fun positionSubtitles(chromeVisible: Boolean, animate: Boolean = true) {
        val v = overlay.videoSubtitles
        val basePadding = v.height * SUBTITLE_BOTTOM_FRACTION
        val lift = if (chromeVisible) -(callbacks.bottomChromeHeight() - basePadding + dp(8)).coerceAtLeast(0f) else 0f
        v.animate().cancel()
        if (animate) v.animate().translationY(lift).setDuration(180).start() else v.translationY = lift
    }

    /** Keeps subtitles above the playback panel while it is showing. */
    fun onChromeVisibilityChanged(visible: Boolean) {
        positionSubtitles(visible)
        if (visible) scheduleAutoHide()
    }

    private val filterNames = listOf("Normal", "Vivid", "Warm", "Cool", "Black & white", "Sepia", "Night")
    private val nightFilter = 6

    private fun filterMatrix(index: Int): ColorMatrix? = when (index) {
        1 -> ColorMatrix().apply { setSaturation(1.45f) }
        2 -> ColorMatrix(floatArrayOf(1.08f, 0f, 0f, 0f, 10f, 0f, 1f, 0f, 0f, 2f, 0f, 0f, 0.88f, 0f, -6f, 0f, 0f, 0f, 1f, 0f))
        3 -> ColorMatrix(floatArrayOf(0.92f, 0f, 0f, 0f, -6f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1.1f, 0f, 10f, 0f, 0f, 0f, 1f, 0f))
        4 -> ColorMatrix().apply { setSaturation(0f) }
        5 -> ColorMatrix().apply {
            setSaturation(0f)
            postConcat(ColorMatrix().apply { setScale(1.1f, 0.95f, 0.78f, 1f) })
        }
        nightFilter -> ColorMatrix().apply { setScale(0.7f, 0.64f, 0.52f, 1f) }
        else -> null
    }

    private fun applyFilter() {
        val texture = playerView?.videoSurfaceView as? TextureView ?: return
        val matrix = filterMatrix(filterIndex)
        texture.setLayerPaint(matrix?.let { Paint().apply { colorFilter = ColorMatrixColorFilter(it) } })
    }

    private fun setFilter(index: Int) {
        filterIndex = index
        applyFilter()
        showCenterMessage(filterNames[index], 800)
        buildQuickTools()
    }

    /** Colour filters for the video picture (display only, the file is not changed). */
    fun showFilterDialog() {
        if (!requireVideo()) return
        showPanel("Video filter", filterNames, filterIndex) { which ->
            AnalyticsManager.logEvent("PhotoDetail_video_filter")
            setFilter(which)
        }
    }

    private fun toggleNightMode() {
        AnalyticsManager.logEvent("PhotoDetail_video_night")
        setFilter(if (filterIndex == nightFilter) 0 else nightFilter)
    }

    private fun equalizerOrNull(): Equalizer? = equalizer ?: try {
        Equalizer(0, player.audioSessionId).also { equalizer = it }
    } catch (_: Throwable) {
        null
    }

    /** Sound presets using the system equalizer on the player's audio session. */
    fun showEqualizerDialog() {
        if (!requireVideo()) return
        val eq = equalizerOrNull()
        if (eq == null || eq.numberOfPresets <= 0) {
            Toast.makeText(context, "Equalizer isn't available on this device", Toast.LENGTH_SHORT).show()
            return
        }
        val presets = (0 until eq.numberOfPresets).map { eq.getPresetName(it.toShort()) }
        val labels = listOf("Off") + presets
        val checked = try { if (eq.enabled) eq.currentPreset + 1 else 0 } catch (_: Exception) { 0 }
        showPanel("Equalizer", labels, checked.coerceIn(0, presets.size)) { which ->
            AnalyticsManager.logEvent("PhotoDetail_video_equalizer")
            try {
                if (which == 0) {
                    eq.enabled = false
                } else {
                    eq.usePreset((which - 1).toShort())
                    eq.enabled = true
                }
                showCenterMessage(labels[which], 800)
            } catch (_: Exception) {
                Toast.makeText(context, "Couldn't change the equalizer", Toast.LENGTH_SHORT).show()
            }
            buildQuickTools()
        }
    }

    private fun toggleAbRepeat() {
        if (!requireVideo()) return
        AnalyticsManager.logEvent("PhotoDetail_video_abRepeat")
        when {
            abStart < 0 -> {
                abStart = player.currentPosition
                showCenterMessage("A set at ${formatTime(abStart)} · tap again for B", 1500)
            }
            abEnd < 0 -> {
                val pos = player.currentPosition
                if (pos <= abStart + 500) {
                    showCenterMessage("Play a little further, then set B", 1200)
                    return
                }
                abEnd = pos
                player.seekTo(abStart)
                showCenterMessage("Repeating ${formatTime(abStart)} – ${formatTime(abEnd)}", 1200)
            }
            else -> {
                abStart = -1L
                abEnd = -1L
                showCenterMessage("A-B repeat off", 800)
            }
        }
        buildQuickTools()
    }

    private val sleepRunnable = Runnable {
        sleepMinutes = 0
        buildQuickTools()
        if (playerBuilt) player.pause()
        showCenterMessage("Sleep timer: paused", 1500)
    }

    /** Pauses playback after the chosen time. */
    fun showSleepTimerDialog() {
        val minutes = intArrayOf(0, 10, 20, 30, 45, 60)
        val labels = listOf("Off", "10 minutes", "20 minutes", "30 minutes", "45 minutes", "1 hour")
        showPanel("Sleep timer", labels, minutes.indexOf(sleepMinutes).coerceAtLeast(0)) { which ->
            AnalyticsManager.logEvent("PhotoDetail_video_sleepTimer")
            sleepMinutes = minutes[which]
            handler.removeCallbacks(sleepRunnable)
            if (sleepMinutes > 0) {
                handler.postDelayed(sleepRunnable, sleepMinutes * 60_000L)
                showCenterMessage("Pauses in ${labels[which]}", 1000)
            } else {
                showCenterMessage("Sleep timer off", 800)
            }
            buildQuickTools()
        }
    }

    fun toggleLoop() {
        isLooping = !isLooping
        player.repeatMode = if (isLooping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        showCenterMessage(if (isLooping) "Loop on" else "Loop off", 800)
        buildQuickTools()
    }

    fun toggleMute() {
        isMuted = !isMuted
        player.volume = if (isMuted) 0f else 1f
        showCenterMessage(if (isMuted) "Muted" else "Sound on", 800)
        buildQuickTools()
    }

    /** Saves the current frame to Pictures/PhotoEditor (same folder as the editor). */
    fun takeScreenshot() {
        if (!requireVideo()) return
        val texture = playerView?.videoSurfaceView as? TextureView
        val size = player.videoSize
        val frame: Bitmap? = try {
            if (size.width in 1..4096 && size.height in 1..4096) texture?.getBitmap(size.width, size.height)
            else texture?.bitmap
        } catch (_: Throwable) {
            null
        }
        if (frame == null) {
            Toast.makeText(context, "Couldn't capture this frame", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Toast.makeText(context, "Screenshots need Android 10 or newer", Toast.LENGTH_SHORT).show()
            return
        }
        AnalyticsManager.logEvent("PhotoDetail_video_screenshot")
        activity.lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                try {
                    val resolver = activity.contentResolver
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, "Gallerai_${System.currentTimeMillis()}.jpg")
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PhotoEditor")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@withContext false
                    resolver.openOutputStream(uri)?.use { frame.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                    values.clear()
                    values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    true
                } catch (_: Exception) {
                    false
                } finally {
                    frame.recycle()
                }
            }
            if (saved) showCenterMessage("Screenshot saved", 1200)
            else Toast.makeText(context, "Couldn't save the screenshot", Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------ side panel

    private class PanelAction(val label: String, val action: () -> Unit)

    private val panelBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = hidePanel()
    }

    private fun dp(v: Int) = (v * density).toInt()

    /** MX-style list panel sliding in from the right. */
    private fun showPanel(
        title: String,
        options: List<String>,
        checked: Int,
        actions: List<PanelAction> = emptyList(),
        onPick: (Int) -> Unit
    ) {
        cancelAutoHide()
        panel.panelTitle.text = title
        val items = panel.panelItems
        items.removeAllViews()
        options.forEachIndexed { i, label ->
            items.addView(panelRow(label, if (i == checked) R.drawable.ic_radio_on else R.drawable.ic_radio_off) {
                hidePanel()
                onPick(i)
            })
        }
        if (actions.isNotEmpty()) {
            items.addView(View(activity).apply {
                setBackgroundColor(0x33FFFFFF)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                    topMargin = dp(10); bottomMargin = dp(6)
                }
            })
            actions.forEach { a ->
                items.addView(panelRow(a.label, null) {
                    hidePanel()
                    a.action()
                })
            }
        }
        val screenWidth = activity.resources.displayMetrics.widthPixels
        val width = minOf(dp(320), (screenWidth * 0.8f).toInt())
        panel.panel.layoutParams = panel.panel.layoutParams.apply { this.width = width }
        panel.panelScrim.isVisible = true
        panel.panel.animate().cancel()
        panel.panel.translationX = width.toFloat()
        panel.panel.animate().translationX(0f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
        panelBackCallback.isEnabled = true
    }

    fun hidePanel(animate: Boolean = true) {
        if (!panel.panelScrim.isVisible) return
        panelBackCallback.isEnabled = false
        val p = panel.panel
        p.animate().cancel()
        if (animate) {
            p.animate().translationX(p.width.toFloat()).setDuration(170)
                .withEndAction { panel.panelScrim.isVisible = false; scheduleAutoHide() }.start()
        } else {
            panel.panelScrim.isVisible = false
            scheduleAutoHide()
        }
    }

    private fun panelRow(label: String, icon: Int?, onClick: () -> Unit): View = TextView(activity).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 16f
        gravity = Gravity.CENTER_VERTICAL
        minHeight = dp(52)
        setPadding(0, dp(6), dp(8), dp(6))
        if (icon != null) {
            setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
            compoundDrawablePadding = dp(18)
        }
        val tv = TypedValue()
        activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        setBackgroundResource(tv.resourceId)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setOnClickListener { onClick() }
    }

    // ------------------------------------------------------------------ quick tools

    private var toolsExpanded = false

    private class Tool(
        val label: String,
        val icon: Int = 0,
        val text: String? = null,
        val active: Boolean = false,
        val iconRotation: Float = 0f,
        val action: () -> Unit
    )

    private fun quickToolList(): List<Tool> {
        val eqOn = try { equalizer?.enabled == true } catch (_: Exception) { false }
        val speedText = (if (speed % 1f == 0f) "${speed.toInt()}" else "$speed") + "X"
        val equalizerTool = Tool("Equalizer", R.drawable.ic_v_equalizer, active = eqOn) { showEqualizerDialog() }
        val speedTool = Tool("Speed", text = speedText, active = speed != 1f) { showSpeedPanel() }
        val screenshotTool = Tool("Screenshot", R.drawable.ic_v_screenshot) { takeScreenshot() }
        val rotateTool = Tool("Screen Rotation", R.drawable.ic_v_rotate) {
            AnalyticsManager.logEvent("PhotoDetail_video_rotate")
            toggleOrientation()
        }
        val nightTool = Tool("Night Mode", R.drawable.ic_v_night, active = filterIndex == nightFilter) { toggleNightMode() }
        // No rotate button where the system ignores orientation requests (Android 16+ tablets).
        val rotate = rotateTool.takeIf { canRotate }
        rotateShown = rotate != null
        return if (!toolsExpanded) {
            listOfNotNull(
                equalizerTool, speedTool, screenshotTool, rotate, nightTool,
                Tool("More", R.drawable.ic_chevron_left, iconRotation = 180f) { toolsExpanded = true; buildQuickTools() }
            )
        } else {
            listOfNotNull(
                nightTool,
                Tool("Filters", R.drawable.ic_v_filter, active = filterIndex != 0 && filterIndex != nightFilter) { showFilterDialog() },
                Tool("Loop", R.drawable.ic_v_loop, active = isLooping) { toggleLoop() },
                Tool("Mute", if (isMuted) R.drawable.ic_v_volume_off else R.drawable.ic_v_volume, active = isMuted) { toggleMute() },
                Tool("Sleep Timer", R.drawable.ic_v_timer, active = sleepMinutes > 0) { showSleepTimerDialog() },
                Tool("A-B Repeat", text = "A-B", active = abStart >= 0) { toggleAbRepeat() },
                equalizerTool, speedTool, screenshotTool, rotate,
                Tool("Less", R.drawable.ic_chevron_left) { toolsExpanded = false; buildQuickTools() }

            )
        }
    }

    private fun buildQuickTools() {
        val row = quickTools.quickItems
        row.removeAllViews()
        val accent = activity.appColor(R.color.lumina_accent)
        quickToolList().forEach { tool ->
            val item = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(if (toolsExpanded) dp(74) else dp(58), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val circle = FrameLayout(activity).apply {
                setBackgroundResource(R.drawable.bg_video_tool)
                layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
                contentDescription = tool.label
                setOnClickListener {
                    tool.action()
                    scheduleAutoHide()
                }
            }
            if (tool.text != null) {
                circle.addView(TextView(activity).apply {
                    text = tool.text
                    setTextColor(if (tool.active) accent else Color.WHITE)
                    textSize = 14f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    gravity = Gravity.CENTER
                }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            } else {
                circle.addView(ImageView(activity).apply {
                    setImageResource(tool.icon)
                    rotation = tool.iconRotation
                    setColorFilter(if (tool.active) accent else Color.WHITE)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
            }
            item.addView(circle)
            if (toolsExpanded) {
                item.addView(TextView(activity).apply {
                    text = tool.label
                    setTextColor(if (tool.active) accent else Color.WHITE)
                    textSize = 11f
                    gravity = Gravity.CENTER
                    maxLines = 2
                    setShadowLayer(4f, 0f, 1f, Color.BLACK)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(4)
                    }
                })
            }
            row.addView(item)
        }
    }

    // Runs last so every property above is initialised.
    init {
        panel.panelScrim.setOnClickListener { hidePanel() }
        activity.onBackPressedDispatcher.addCallback(activity, panelBackCallback)
        buildQuickTools()
    }

    companion object {
        private const val SEEK_STEP_MS = 10_000L
        private const val SUBTITLE_TEXT_SP = 16f
        private const val SUBTITLE_BOTTOM_FRACTION = 0.05f
        private val LANGUAGE_CODE = Regex("^[A-Za-z]{2,3}([-_][A-Za-z0-9]{2,8})*$")
        private const val AUTO_HIDE_MS = 3500L
        /** How long a landscape request may take before it counts as ignored. */
        private const val ROTATION_TIMEOUT_MS = 1500L


        fun formatTime(ms: Long): String {
            val totalSec = (ms / 1000).coerceAtLeast(0)
            val h = totalSec / 3600
            val m = (totalSec % 3600) / 60
            val s = totalSec % 60
            return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
            else String.format(Locale.US, "%d:%02d", m, s)
        }
    }
}
