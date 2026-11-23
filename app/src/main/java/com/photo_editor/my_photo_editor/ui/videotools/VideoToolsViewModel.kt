package com.photo_editor.my_photo_editor.ui.videotools

import android.app.Application
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.annotation.OptIn
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.photo_editor.my_photo_editor.VideoToolsActivity
import com.photo_editor.my_photo_editor.utils.AnalyticsManager
import com.photo_editor.my_photo_editor.utils.GifEncoder
import com.photo_editor.my_photo_editor.utils.MediaStoreSaver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

data class VideoToolsUiState(
    val uri: Uri? = null,
    val mode: String = VideoToolsActivity.MODE_TRIM,
    val durationMs: Long = 0L,
    /** Display-oriented size (rotation applied); 0 when unknown. */
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val loadError: String? = null,
    val startMs: Long = 0L,
    val endMs: Long = 0L,
    val removeAudio: Boolean = false,
    val gifWidth: Int = 480,
    val gifFps: Int = 10,
    val gifSpeed: Float = 1f,
    val gifLoop: Boolean = true,
    val exporting: Boolean = false,
    val progress: Int = 0,
    val progressLabel: String = ""
) {
    val ready: Boolean get() = durationMs > 0 && loadError == null
    val spanMs: Long get() = endMs - startMs
    val isGif: Boolean get() = mode == VideoToolsActivity.MODE_GIF
}

data class GifPlan(
    val width: Int,
    val height: Int,
    val frames: Int,
    val outputDurationMs: Long,
    val estimatedBytes: Long
)

sealed interface VideoToolsEvent {
    data class Message(val text: String) : VideoToolsEvent
    data class Error(val text: String) : VideoToolsEvent
    data class Saved(val uri: Uri, val mimeType: String, val folder: String) : VideoToolsEvent
}

/**
 * Video trimmer + video-to-GIF. Holds the selected range and options, runs the Media3
 * Transformer export (on the main looper, as Transformer requires) and the GIF encoding
 * (frames from MediaMetadataRetriever, streamed through [GifEncoder] straight into MediaStore).
 */
@OptIn(UnstableApi::class)
class VideoToolsViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        const val MIN_GAP_MS = 500L
        const val MAX_GIF_MS = 15_000L
        private const val THUMB_COUNT = 10
        /** Rough bytes per pixel for a dithered 256-colour LZW frame (used for the estimate). */
        private const val GIF_BYTES_PER_PIXEL = 0.45 // measured ~0.34 on a real clip; a little headroom for busy scenes

        fun gifPlan(s: VideoToolsUiState): GifPlan {
            val span = min(max(s.spanMs, 0L), MAX_GIF_MS)
            val vw = if (s.videoWidth > 0) s.videoWidth else 16
            val vh = if (s.videoHeight > 0) s.videoHeight else 9
            val w = if (s.videoWidth > 0) min(s.gifWidth, s.videoWidth) else s.gifWidth
            val h = max(1, (w.toDouble() * vh / vw).roundToInt())
            val outDuration = (span / s.gifSpeed.toDouble()).roundToLong()
            val frames = max(1, (outDuration * s.gifFps / 1000.0).roundToInt())
            val est = (w.toLong() * h * frames * GIF_BYTES_PER_PIXEL).toLong() + 1024L
            return GifPlan(w, h, frames, outDuration, est)
        }
    }

    private val app get() = getApplication<Application>()

    private val _state = MutableStateFlow(VideoToolsUiState())
    val state: StateFlow<VideoToolsUiState> = _state.asStateFlow()

    /** One-off events. Buffered, so a result that arrives while the screen is stopped is still shown. */
    private val _events = Channel<VideoToolsEvent>(Channel.BUFFERED)
    val events: Flow<VideoToolsEvent> = _events.receiveAsFlow()

    private val _thumbnails = MutableStateFlow<List<Bitmap>>(emptyList())
    val thumbnails: StateFlow<List<Bitmap>> = _thumbnails.asStateFlow()

    private var initialized = false
    private var exportJob: Job? = null

    // ------------------------------------------------------------------ loading

    fun init(uri: Uri, mode: String) {
        if (initialized) return
        initialized = true
        val m = if (mode == VideoToolsActivity.MODE_GIF) VideoToolsActivity.MODE_GIF else VideoToolsActivity.MODE_TRIM
        _state.update { it.copy(uri = uri, mode = m) }
        viewModelScope.launch {
            val meta = withContext(Dispatchers.IO) { readMetadata(uri) }
            if (meta != null) {
                _state.update {
                    it.copy(
                        videoWidth = if (it.videoWidth > 0) it.videoWidth else meta.width,
                        videoHeight = if (it.videoHeight > 0) it.videoHeight else meta.height
                    )
                }
                if (meta.durationMs > 0) applyDuration(meta.durationMs)
            }
            val dur = _state.value.durationMs
            if (dur > 0) loadThumbnails(uri, dur)
        }
    }

    private class Meta(val durationMs: Long, val width: Int, val height: Int)

    private fun readMetadata(uri: Uri): Meta? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(app, uri)
            val dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) {
                val t = w; w = h; h = t
            }
            if (w <= 0 || h <= 0) {
                // Frames come back already rotated, so their size is the display size.
                val frame = r.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (frame != null) {
                    w = frame.width; h = frame.height
                    frame.recycle()
                }
            }
            Meta(dur, w, h)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } catch (e: OutOfMemoryError) {
            null
        } finally {
            releaseQuietly(r)
        }
    }

    private fun applyDuration(durationMs: Long) {
        val current = _state.value
        if (current.durationMs > 0) return
        if (durationMs < MIN_GAP_MS) {
            _state.update { it.copy(loadError = "This video is too short to edit (minimum 0.5 s).") }
            return
        }
        val gifClamped = current.isGif && durationMs > MAX_GIF_MS
        _state.update {
            it.copy(
                durationMs = durationMs,
                startMs = 0L,
                endMs = if (it.isGif) min(durationMs, MAX_GIF_MS) else durationMs,
                loadError = null
            )
        }
        if (gifClamped) _events.trySend(VideoToolsEvent.Message("GIFs can be up to 15 s, so the first 15 s are selected"))
    }

    /** Called by the Activity once ExoPlayer knows the duration (fallback if metadata failed). */
    fun onPlayerDuration(durationMs: Long) {
        if (durationMs <= 0 || _state.value.durationMs > 0) return
        applyDuration(durationMs)
        val s = _state.value
        val uri = s.uri
        if (s.durationMs > 0 && uri != null && _thumbnails.value.isEmpty()) {
            viewModelScope.launch { loadThumbnails(uri, s.durationMs) }
        }
    }

    fun onPlayerError() {
        if (_state.value.durationMs <= 0) {
            _state.update { it.copy(loadError = "This video can't be opened.") }
        } else {
            _events.trySend(VideoToolsEvent.Message("Preview unavailable for this video"))
        }
    }

    private suspend fun loadThumbnails(uri: Uri, durationMs: Long) {
        val thumbs = withContext(Dispatchers.IO) {
            val list = ArrayList<Bitmap>(THUMB_COUNT)
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(app, uri)
                for (i in 0 until THUMB_COUNT) {
                    val tUs = ((i + 0.5) * durationMs / THUMB_COUNT * 1000.0).toLong()
                    val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        r.getScaledFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 160, 160)
                    } else {
                        r.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    } ?: continue
                    list.add(scaleToFit(raw, 160))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } catch (e: OutOfMemoryError) {
                // Thumbnails are decoration only.
            } finally {
                releaseQuietly(r)
            }
            list
        }
        if (thumbs.isNotEmpty()) _thumbnails.value = thumbs
    }

    private fun scaleToFit(src: Bitmap, maxSide: Int): Bitmap {
        val larger = max(src.width, src.height)
        if (larger <= maxSide) return src
        val f = maxSide.toFloat() / larger
        val out = Bitmap.createScaledBitmap(src, max(1, (src.width * f).toInt()), max(1, (src.height * f).toInt()), true)
        if (out !== src) src.recycle()
        return out
    }

    // ------------------------------------------------------------------ editing

    fun setMode(mode: String) {
        val m = if (mode == VideoToolsActivity.MODE_GIF) VideoToolsActivity.MODE_GIF else VideoToolsActivity.MODE_TRIM
        val s = _state.value
        if (s.mode == m || s.exporting) return
        AnalyticsManager.logEvent("VideoTools_mode_$m")
        _state.update { it.copy(mode = m) }
        if (m == VideoToolsActivity.MODE_GIF && s.durationMs > 0 && s.spanMs > MAX_GIF_MS) {
            _state.update { it.copy(endMs = it.startMs + MAX_GIF_MS) }
            _events.trySend(VideoToolsEvent.Message("GIFs can be up to 15 s, so the selection was shortened"))
        }
    }

    /** Live range update while the user drags the slider (the 15 s GIF limit is applied on release). */
    fun setRange(startMs: Long, endMs: Long) {
        val s = _state.value
        val dur = s.durationMs
        if (dur <= 0 || s.exporting) return
        var start = startMs.coerceIn(0L, dur)
        var end = endMs.coerceIn(0L, dur)
        if (end - start < MIN_GAP_MS) {
            if (start != s.startMs) start = end - MIN_GAP_MS else end = start + MIN_GAP_MS
            if (start < 0L) { start = 0L; end = MIN_GAP_MS }
            if (end > dur) { end = dur; start = dur - MIN_GAP_MS }
        }
        if (start != s.startMs || end != s.endMs) _state.update { it.copy(startMs = start, endMs = end) }
    }

    /**
     * Called when a drag ends. In GIF mode a selection longer than 15 s is shortened, keeping
     * the thumb the user just moved ([endThumbMoved]) and pulling the other one in.
     */
    fun commitRange(endThumbMoved: Boolean) {
        val s = _state.value
        if (s.isGif && s.spanMs > MAX_GIF_MS) {
            val (start, end) = if (endThumbMoved) (s.endMs - MAX_GIF_MS) to s.endMs
            else s.startMs to (s.startMs + MAX_GIF_MS)
            _state.update { it.copy(startMs = start, endMs = end) }
            _events.trySend(VideoToolsEvent.Message("GIFs are limited to 15 s"))
        }
    }

    fun setRemoveAudio(remove: Boolean) = _state.update { it.copy(removeAudio = remove) }
    fun setGifWidth(width: Int) = _state.update { it.copy(gifWidth = width) }
    fun setGifFps(fps: Int) = _state.update { it.copy(gifFps = fps) }
    fun setGifSpeed(speed: Float) = _state.update { it.copy(gifSpeed = speed) }
    fun setGifLoop(loop: Boolean) = _state.update { it.copy(gifLoop = loop) }

    // ------------------------------------------------------------------ export

    fun save() {
        val s = _state.value
        if (s.exporting || !s.ready || s.uri == null) return
        AnalyticsManager.logEvent(if (s.isGif) "VideoTools_gif_save" else "VideoTools_trim_save")
        exportJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    exporting = true,
                    progress = 0,
                    progressLabel = if (s.isGif) "Reading frames…" else "Exporting video…"
                )
            }
            try {
                if (s.isGif) {
                    val clamped = if (s.spanMs > MAX_GIF_MS) s.copy(endMs = s.startMs + MAX_GIF_MS) else s
                    exportGif(clamped)
                } else {
                    exportTrim(s)
                }
            } catch (e: CancellationException) {
                _events.trySend(VideoToolsEvent.Message("Export cancelled"))
                throw e
            } catch (e: OutOfMemoryError) {
                _events.trySend(VideoToolsEvent.Error("Not enough memory to finish. Try a smaller size or a shorter range."))
            } catch (e: Exception) {
                e.printStackTrace()
                _events.trySend(VideoToolsEvent.Error(errorMessage(e)))
            } finally {
                _state.update { it.copy(exporting = false, progress = 0, progressLabel = "") }
                exportJob = null
            }
        }
    }

    fun cancelExport() {
        if (exportJob?.isActive == true) {
            _state.update { it.copy(progressLabel = "Cancelling…") }
            exportJob?.cancel()
        }
    }

    private fun timeStamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    // --- Trim (Media3 Transformer) ---

    private suspend fun exportTrim(s: VideoToolsUiState) {
        val uri = s.uri ?: return
        val dir = File(app.cacheDir, "video_tools").apply { mkdirs() }
        val temp = File(dir, "trim_${System.currentTimeMillis()}.mp4")
        try {
            runTransformer(uri, s, temp)
            _state.update { it.copy(progress = 100, progressLabel = "Saving…") }
            val saved = withContext(Dispatchers.IO) {
                MediaStoreSaver.saveFile(
                    app, temp, "TRIM_${timeStamp()}.mp4", "video/mp4", MediaStoreSaver.Kind.VIDEO
                )
            }
            if (saved != null) {
                AnalyticsManager.logEvent("VideoTools_trim_saved")
                _events.trySend(VideoToolsEvent.Saved(saved, "video/mp4", MediaStoreSaver.VIDEOS_DIR))
            } else {
                _events.trySend(VideoToolsEvent.Error("Couldn't save the video to ${MediaStoreSaver.VIDEOS_DIR}."))
            }
        } finally {
            temp.delete()
        }
    }

    /** Must run on the main thread (viewModelScope = Dispatchers.Main): Transformer is bound to it. */
    private suspend fun runTransformer(uri: Uri, s: VideoToolsUiState, output: File) {
        val clipping = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(s.startMs)
            .apply { if (s.endMs < s.durationMs - 1) setEndPositionMs(s.endMs) }
            .build()
        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setClippingConfiguration(clipping)
            .build()
        val edited = EditedMediaItem.Builder(mediaItem)
            .setRemoveAudio(s.removeAudio)
            .build()

        val done = CompletableDeferred<Unit>()
        val transformer = Transformer.Builder(app)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    done.complete(Unit)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    done.completeExceptionally(exportException)
                }
            })
            .build()

        transformer.start(edited, output.absolutePath)
        coroutineScope {
            val poller = launch {
                val holder = ProgressHolder()
                while (isActive) {
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                        val p = holder.progress.coerceIn(0, 99)
                        _state.update { if (it.exporting) it.copy(progress = p) else it }
                    }
                    delay(200)
                }
            }
            try {
                done.await()
            } catch (e: CancellationException) {
                try { transformer.cancel() } catch (_: Exception) {}
                throw e
            } finally {
                poller.cancel()
            }
        }
    }

    // --- GIF ---

    private suspend fun exportGif(s: VideoToolsUiState) {
        val uri = s.uri ?: return
        val plan = gifPlan(s)
        val job = currentCoroutineContext()[Job]
        var failure: String? = null

        val saved = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(app, uri)
                MediaStoreSaver.save(
                    app, "GIF_${timeStamp()}.gif", "image/gif", MediaStoreSaver.Kind.IMAGE
                ) { out ->
                    try {
                        writeGif(retriever, s, plan, out, job).also { ok ->
                            if (!ok && failure == null && job?.isActive != false) failure = "Couldn't read frames from this video."
                        }
                    } catch (e: OutOfMemoryError) {
                        failure = "Not enough memory to make this GIF. Try a smaller size or a shorter range."
                        false
                    } catch (e: CancellationException) {
                        false
                    } catch (e: Exception) {
                        e.printStackTrace()
                        failure = "GIF encoding failed: ${e.message ?: e.javaClass.simpleName}"
                        false
                    }
                }
            } finally {
                releaseQuietly(retriever)
            }
        }
        currentCoroutineContext().ensureActive()
        if (saved != null) {
            AnalyticsManager.logEvent("VideoTools_gif_saved")
            _events.trySend(VideoToolsEvent.Saved(saved, "image/gif", MediaStoreSaver.PHOTOS_DIR))
        } else {
            _events.trySend(VideoToolsEvent.Error(failure ?: "Couldn't save the GIF to ${MediaStoreSaver.PHOTOS_DIR}."))
        }
    }

    /** Streams every frame through the encoder. Returns false when cancelled or no frame could be read. */
    private fun writeGif(
        retriever: MediaMetadataRetriever,
        s: VideoToolsUiState,
        plan: GifPlan,
        out: OutputStream,
        job: Job?
    ): Boolean {
        val buffered = BufferedOutputStream(out, 64 * 1024)
        val encoder = GifEncoder(buffered, plan.width, plan.height, loopForever = s.gifLoop, dither = true)
        var last: Bitmap? = null
        try {
            _state.update { it.copy(progressLabel = "Encoding GIF…") }
            for (i in 0 until plan.frames) {
                if (job?.isActive == false) return false
                // Output time i/fps maps to source time start + (i/fps) * speed.
                val srcMs = s.startMs + (i * 1000.0 / s.gifFps * s.gifSpeed).roundToLong()
                val timeUs = min(srcMs, s.endMs) * 1000L
                var frame = grabFrame(retriever, timeUs, plan.width, plan.height, MediaMetadataRetriever.OPTION_CLOSEST)
                if (frame == null && last == null) {
                    frame = grabFrame(retriever, timeUs, plan.width, plan.height, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }
                val use = frame ?: last ?: return false
                // Delays in 1/100 s; spreading the rounding keeps 15 fps at 7,7,6,... (avg 6.67).
                val delay = ((i + 1) * 100.0 / s.gifFps).roundToInt() - (i * 100.0 / s.gifFps).roundToInt()
                encoder.addFrame(use, max(2, delay))
                if (frame != null) {
                    last?.recycle()
                    last = frame
                }
                val p = ((i + 1) * 100 / plan.frames).coerceIn(0, 99)
                _state.update { if (it.exporting) it.copy(progress = p) else it }
            }
            if (job?.isActive == false) return false
            encoder.finish()
            buffered.flush()
            _state.update { if (it.exporting) it.copy(progress = 100, progressLabel = "Saving…") else it }
            return true
        } finally {
            last?.recycle()
        }
    }

    private fun grabFrame(r: MediaMetadataRetriever, timeUs: Long, w: Int, h: Int, option: Int): Bitmap? {
        val raw = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                r.getScaledFrameAtTime(timeUs, option, w, h)
            } else {
                r.getFrameAtTime(timeUs, option)
            }
        } catch (e: Exception) {
            null
        } ?: return null
        val isHardware = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && raw.config == Bitmap.Config.HARDWARE
        if (!isHardware && raw.width == w && raw.height == h) return raw
        // getPixels() doesn't work on hardware bitmaps; copy to a software bitmap first.
        val soft = if (isHardware) {
            val copy = raw.copy(Bitmap.Config.ARGB_8888, false)
            raw.recycle()
            copy ?: return null
        } else raw
        if (soft.width == w && soft.height == h) return soft
        val scaled = Bitmap.createScaledBitmap(soft, w, h, true)
        if (scaled !== soft) soft.recycle()
        return scaled
    }

    private fun errorMessage(e: Throwable): String {
        if (e is ExportException) {
            return when (e.errorCode) {
                ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED,
                ExportException.ERROR_CODE_DECODER_INIT_FAILED,
                ExportException.ERROR_CODE_ENCODER_INIT_FAILED ->
                    "This device can't re-encode this video format."
                ExportException.ERROR_CODE_IO_FILE_NOT_FOUND,
                ExportException.ERROR_CODE_IO_NO_PERMISSION ->
                    "The video can't be read. It may have been moved or deleted."
                else -> "Export failed (${e.errorCodeName})."
            }
        }
        return "Export failed: ${e.message ?: e.javaClass.simpleName}"
    }

    private fun releaseQuietly(r: MediaMetadataRetriever) {
        try { r.release() } catch (_: Exception) {}
    }

    override fun onCleared() {
        exportJob?.cancel()
        super.onCleared()
    }
}
