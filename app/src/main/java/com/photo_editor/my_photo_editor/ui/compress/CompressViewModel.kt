package com.photo_editor.my_photo_editor.ui.compress

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.photo_editor.my_photo_editor.utils.AnalyticsManager
import com.photo_editor.my_photo_editor.utils.BitmapLoader
import com.photo_editor.my_photo_editor.utils.MediaStoreSaver
import com.photo_editor.my_photo_editor.utils.ShareHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** Compress & resize: shrinks photos into new copies (originals are never touched). */
class CompressViewModel(application: Application) : AndroidViewModel(application) {

    enum class SizeOption(val maxPx: Int) {
        ORIGINAL(Int.MAX_VALUE), LARGE(2560), MEDIUM(1920), SMALL(1280), CUSTOM(0)
    }

    enum class Format(val ext: String, val mime: String) {
        JPEG("jpg", "image/jpeg"), WEBP("webp", "image/webp")
    }

    data class Options(
        val size: SizeOption = SizeOption.LARGE,
        val customPx: Int = 1600,
        val quality: Int = 80,
        val format: Format = Format.JPEG,
        val stripMetadata: Boolean = true
    ) {
        val targetLongEdge: Int get() = if (size == SizeOption.CUSTOM) customPx else size.maxPx
    }

    /** [width]/[height] are 0 when the header couldn't be read. [sizeBytes] is -1 when unknown. */
    data class Photo(val uri: Uri, val name: String, val sizeBytes: Long, val width: Int, val height: Int) {
        val readable: Boolean get() = width > 0 && height > 0
    }

    data class UiState(
        val loading: Boolean = true,
        val photos: List<Photo> = emptyList(),
        val options: Options = Options()
    ) {
        val totalBytes: Long get() = photos.sumOf { max(it.sizeBytes, 0L) }
    }

    /** [totalBytes] null = no estimate yet / not possible. */
    data class Estimate(val computing: Boolean = false, val totalBytes: Long? = null, val originalBytes: Long = 0L)

    data class Progress(val done: Int, val total: Int, val sharing: Boolean)

    data class Report(val title: String, val message: String)

    sealed class Event {
        data class Message(val text: String) : Event()
        data class StartIntent(val intent: Intent) : Event()
    }

    private val app get() = getApplication<Application>()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _estimate = MutableStateFlow(Estimate())
    val estimate: StateFlow<Estimate> = _estimate.asStateFlow()

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    /** Kept until the activity dismisses it, so it survives rotation. */
    private val _result = MutableStateFlow<Report?>(null)
    val result: StateFlow<Report?> = _result.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    private var initialized = false

    /** Set by the activity once it opened the photo picker (a new ViewModel = process restarted). */
    var pickerLaunched = false
    private var job: Job? = null
    val isBusy: Boolean get() = job?.isActive == true

    // Decoded sample photo for the live estimate (reused while only quality/format change).
    private val estimateLock = Mutex()
    private var sampleBitmap: Bitmap? = null
    private var sampleKey: Pair<Uri, Int>? = null

    init {
        startEstimates()
    }

    fun init(uris: List<Uri>) {
        if (initialized || uris.isEmpty()) return
        initialized = true
        viewModelScope.launch {
            val photos = withContext(Dispatchers.IO) { uris.distinct().map { readInfo(it) } }
            _state.update { it.copy(loading = false, photos = photos) }
        }
    }

    fun setSize(size: SizeOption) = _state.update { it.copy(options = it.options.copy(size = size)) }

    fun setCustomSize(px: Int) = _state.update {
        it.copy(options = it.options.copy(size = SizeOption.CUSTOM, customPx = px.coerceIn(MIN_CUSTOM_PX, MAX_CUSTOM_PX)))
    }

    fun setQuality(quality: Int) = _state.update { it.copy(options = it.options.copy(quality = quality.coerceIn(40, 100))) }
    fun setFormat(format: Format) = _state.update { it.copy(options = it.options.copy(format = format)) }
    fun setStripMetadata(strip: Boolean) = _state.update { it.copy(options = it.options.copy(stripMetadata = strip)) }

    fun consumeResult() { _result.value = null }

    // ------------------------------------------------------------------ live estimate

    @OptIn(FlowPreview::class)
    private fun startEstimates() {
        viewModelScope.launch {
            _state
                .map { it.photos to it.options }
                .distinctUntilChanged()
                .debounce(300)
                .collectLatest { (photos, options) ->
                    if (photos.isEmpty()) return@collectLatest
                    _estimate.update { it.copy(computing = true) }
                    val estimate = withContext(Dispatchers.Default) { computeEstimate(photos, options) }
                    _estimate.value = estimate
                }
        }
    }

    /**
     * Encodes the first readable photo with the chosen options, measures bytes per pixel and
     * scales that across all photos by their output pixel count. It is an estimate: photos
     * with more detail compress less.
     */
    private suspend fun computeEstimate(photos: List<Photo>, options: Options): Estimate {
        val readable = photos.filter { it.readable }
        val originalBytes = readable.sumOf { max(it.sizeBytes, 0L) }
        if (readable.isEmpty()) return Estimate(totalBytes = null, originalBytes = originalBytes)
        for (sample in readable.take(3)) {
            coroutineContextEnsureActive()
            val (outW, outH) = outputSize(sample, options.targetLongEdge)
            val sampleDim = min(max(outW, outH), ESTIMATE_MAX_DIM)
            val bytesPerPixel = estimateLock.withLock {
                val bitmap = sampleBitmapFor(sample.uri, sampleDim) ?: return@withLock null
                val counter = CountingStream()
                val encoded = try {
                    prepareForFormat(bitmap, options.format, recycleSource = false).let { prepared ->
                        val ok = prepared.compress(compressFormat(options.format), options.quality, counter)
                        if (prepared !== bitmap) prepared.recycle()
                        ok
                    }
                } catch (e: OutOfMemoryError) {
                    false
                }
                if (!encoded || counter.count == 0L) null
                else counter.count.toDouble() / (bitmap.width.toLong() * bitmap.height).toDouble()
            } ?: continue
            val totalPixels = readable.sumOf { p ->
                val (w, h) = outputSize(p, options.targetLongEdge)
                w.toLong() * h
            }
            return Estimate(totalBytes = (bytesPerPixel * totalPixels).roundToLong(), originalBytes = originalBytes)
        }
        return Estimate(totalBytes = null, originalBytes = originalBytes)
    }

    private suspend fun coroutineContextEnsureActive() = kotlin.coroutines.coroutineContext.ensureActive()

    /** Called under [estimateLock]. */
    private fun sampleBitmapFor(uri: Uri, dim: Int): Bitmap? {
        val key = uri to dim
        if (key == sampleKey) sampleBitmap?.let { if (!it.isRecycled) return it }
        sampleBitmap?.recycle()
        sampleBitmap = null
        sampleKey = null
        val bitmap = BitmapLoader.decodeUpright(app, uri, dim) ?: return null
        sampleBitmap = bitmap
        sampleKey = key
        return bitmap
    }

    override fun onCleared() {
        super.onCleared()
        // Don't recycle while an estimate might still be encoding it; let GC reclaim it.
        sampleBitmap = null
        sampleKey = null
    }

    // ------------------------------------------------------------------ save / share

    fun saveCopies() = runBatch(share = false)
    fun share() = runBatch(share = true)

    fun cancel() {
        if (job?.isActive == true) {
            job?.cancel()
            _progress.value = null
        }
    }

    private sealed class Outcome {
        data class Done(val inBytes: Long, val outBytes: Long, val shareUri: Uri?) : Outcome()
        object Unreadable : Outcome()
        object WriteFailed : Outcome()
    }

    private fun runBatch(share: Boolean) {
        if (isBusy) return
        val snapshot = _state.value
        val photos = snapshot.photos
        if (photos.isEmpty()) return
        val options = snapshot.options
        AnalyticsManager.logEvent(if (share) "Compress_share" else "Compress_save")
        _progress.value = Progress(0, photos.size, share)

        job = viewModelScope.launch {
            var saved = 0
            var unreadable = 0
            var writeFailed = 0
            var inBytes = 0L
            var outBytes = 0L
            var knownInCount = 0
            val shareUris = ArrayList<Uri>()
            try {
                withContext(Dispatchers.IO) {
                    val shareDir = if (share) ShareHelper.freshShareDir(app) else null
                    val usedNames = HashSet<String>()
                    photos.forEachIndexed { index, photo ->
                        ensureActive()
                        if (job?.isCancelled != true) _progress.value = Progress(index, photos.size, share)
                        val outcome = try {
                            processOne(photo, options, shareDir, usedNames)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: OutOfMemoryError) {
                            Outcome.Unreadable
                        } catch (e: Exception) {
                            e.printStackTrace()
                            Outcome.Unreadable
                        }
                        when (outcome) {
                            is Outcome.Done -> {
                                saved++
                                if (photo.sizeBytes > 0) {
                                    knownInCount++
                                    inBytes += outcome.inBytes
                                    outBytes += outcome.outBytes
                                }
                                outcome.shareUri?.let { shareUris += it }
                            }
                            Outcome.Unreadable -> unreadable++
                            Outcome.WriteFailed -> writeFailed++
                        }
                    }
                }
                finishBatch(share, saved, unreadable, writeFailed, inBytes, outBytes, knownInCount, shareUris)
            } catch (e: CancellationException) {
                if (!share && saved > 0) {
                    _result.value = Report("Cancelled", "Saved ${photoCount(saved)} to Pictures/PhotoEditor before cancelling")
                } else {
                    _events.trySend(Event.Message("Cancelled"))
                }
                throw e
            } finally {
                _progress.value = null
            }
        }
    }

    private fun finishBatch(
        share: Boolean, done: Int, unreadable: Int, writeFailed: Int,
        inBytes: Long, outBytes: Long, knownInCount: Int, shareUris: List<Uri>
    ) {
        val problems = buildList {
            if (unreadable > 0) add("${photoCount(unreadable)} couldn't be read")
            if (writeFailed > 0) add("${photoCount(writeFailed)} couldn't be saved")
        }.joinToString("\n")

        if (share) {
            if (shareUris.isEmpty()) {
                _events.trySend(Event.Message(problems.ifEmpty { "Nothing to share" }))
                return
            }
            val mime = _state.value.options.format.mime
            ShareHelper.shareIntent(shareUris, mime)?.let { _events.trySend(Event.StartIntent(it)) }
            if (problems.isNotEmpty()) _events.trySend(Event.Message(problems))
            return
        }

        if (done == 0) {
            _result.value = Report("Nothing saved", problems.ifEmpty { "No photos were saved" })
            return
        }
        val summary = buildString {
            append("Saved ").append(photoCount(done))
            if (knownInCount > 0) append(" · ").append(formatSize(inBytes)).append(" → ").append(formatSize(outBytes))
            append("\nPictures/PhotoEditor")
            if (problems.isNotEmpty()) append("\n\n").append(problems)
        }
        AnalyticsManager.logEvent("Compress_saved")
        _result.value = Report("Copies saved", summary)
    }

    private fun processOne(photo: Photo, options: Options, shareDir: File?, usedNames: MutableSet<String>): Outcome {
        if (!photo.readable) return Outcome.Unreadable
        val target = memorySafeLongEdge(photo, options.targetLongEdge)
        var bitmap = BitmapLoader.decodeUpright(app, photo.uri, target)
            ?: BitmapLoader.decodeUpright(app, photo.uri, max(target / 2, 1))
            ?: return Outcome.Unreadable
        bitmap = prepareForFormat(bitmap, options.format, recycleSource = true)

        val format = options.format
        val outName = uniqueName(baseName(photo.name) + "_small", format.ext, usedNames, checkLegacyDisk = shareDir == null)
        val file = if (shareDir != null) File(shareDir, outName)
        else File.createTempFile("cmp_", ".${format.ext}", app.cacheDir)

        try {
            val ok = try {
                file.outputStream().buffered().use { bitmap.compress(compressFormat(format), options.quality, it) }
            } finally {
                bitmap.recycle()
            }
            if (!ok || file.length() == 0L) {
                file.delete()
                return Outcome.WriteFailed
            }
            if (format == Format.JPEG && !options.stripMetadata) copyBasicExif(photo.uri, file)
            val outBytes = file.length()

            if (shareDir != null) {
                return Outcome.Done(photo.sizeBytes, outBytes, ShareHelper.fileUri(app, file))
            }
            val uri = MediaStoreSaver.saveFile(
                app, file, outName, format.mime, MediaStoreSaver.Kind.IMAGE, MediaStoreSaver.PHOTOS_DIR
            )
            return if (uri != null) Outcome.Done(photo.sizeBytes, outBytes, null) else Outcome.WriteFailed
        } finally {
            if (shareDir == null) file.delete()
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun readInfo(uri: Uri): Photo {
        val resolver = app.contentResolver
        var name: String? = null
        var size = -1L
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIdx >= 0 && !c.isNull(nameIdx)) name = c.getString(nameIdx)
                    if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        if (size <= 0) {
            size = try {
                resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            } catch (e: Exception) {
                -1L
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        val fallbackName = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "photo"
        return Photo(uri, name ?: fallbackName, size, max(bounds.outWidth, 0), max(bounds.outHeight, 0))
    }

    /** Output size for [targetLongEdge], never upscaling. Rotation doesn't change pixel count. */
    private fun outputSize(photo: Photo, targetLongEdge: Int): Pair<Int, Int> {
        val long = max(photo.width, photo.height)
        if (long <= 0) return 0 to 0
        if (targetLongEdge >= long) return photo.width to photo.height
        val s = targetLongEdge.toDouble() / long
        return max((photo.width * s).toInt(), 1) to max((photo.height * s).toInt(), 1)
    }

    /** Keeps huge photos (50–200 MP) from running out of memory at "Original" size. */
    private fun memorySafeLongEdge(photo: Photo, targetLongEdge: Int): Int {
        val long = max(photo.width, photo.height)
        var target = min(long, targetLongEdge)
        val maxPixels = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            MAX_DECODE_PIXELS
        } else {
            // Before Android 8 bitmaps live on the Java heap.
            min(MAX_DECODE_PIXELS, Runtime.getRuntime().maxMemory() / 4 / 3)
        }
        val scale = target.toDouble() / long
        val pixels = photo.width.toDouble() * photo.height * scale * scale
        if (pixels > maxPixels) target = (target * sqrt(maxPixels / pixels)).toInt()
        return max(target, 1)
    }

    /** JPEG has no transparency: flatten onto white so transparent areas don't turn black. */
    private fun prepareForFormat(bitmap: Bitmap, format: Format, recycleSource: Boolean): Bitmap {
        if (format != Format.JPEG || !bitmap.hasAlpha()) return bitmap
        val flat = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(flat).apply {
            drawColor(Color.WHITE)
            drawBitmap(bitmap, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        if (recycleSource) bitmap.recycle()
        return flat
    }

    private fun compressFormat(format: Format): Bitmap.CompressFormat = when {
        format == Format.JPEG -> Bitmap.CompressFormat.JPEG
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Bitmap.CompressFormat.WEBP_LOSSY
        else -> @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
    }

    /** Copies date & camera & location tags back (orientation stays normal: pixels are upright). */
    private fun copyBasicExif(source: Uri, target: File) {
        try {
            val src = openForExif(source)?.use { ExifInterface(it) } ?: return
            val dst = ExifInterface(target.absolutePath)
            var changed = false
            for (tag in EXIF_TAGS_TO_KEEP) {
                val value = src.getAttribute(tag) ?: continue
                dst.setAttribute(tag, value)
                changed = true
            }
            if (!changed) return
            dst.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            dst.saveAttributes()
        } catch (e: Exception) {
            e.printStackTrace()
        } catch (e: OutOfMemoryError) {
            // Keep the copy without metadata.
        }
    }

    /**
     * Android 10+ redacts GPS tags unless the original is requested (needs ACCESS_MEDIA_LOCATION);
     * fall back to the redacted stream when that isn't allowed.
     */
    private fun openForExif(uri: Uri): java.io.InputStream? {
        val resolver = app.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && uri.authority == MediaStore.AUTHORITY) {
            try {
                return resolver.openInputStream(MediaStore.setRequireOriginal(uri))
            } catch (_: Exception) {
                // SecurityException / UnsupportedOperationException: use the normal stream
            }
        }
        return resolver.openInputStream(uri)
    }

    private fun baseName(displayName: String): String {
        val dot = displayName.lastIndexOf('.')
        val base = if (dot > 0) displayName.substring(0, dot) else displayName
        val clean = base.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim()
        return clean.ifEmpty { "photo" }.take(80)
    }

    @Suppress("DEPRECATION")
    private fun uniqueName(base: String, ext: String, used: MutableSet<String>, checkLegacyDisk: Boolean): String {
        // Android 10+ MediaStore renames clashes itself; older versions would overwrite.
        val legacyDir = if (checkLegacyDisk && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            File(Environment.getExternalStorageDirectory(), MediaStoreSaver.PHOTOS_DIR)
        } else {
            null
        }
        var candidate = "$base.$ext"
        var i = 2
        while (used.contains(candidate.lowercase(Locale.ROOT)) || (legacyDir != null && File(legacyDir, candidate).exists())) {
            candidate = "${base}_$i.$ext"
            i++
        }
        used.add(candidate.lowercase(Locale.ROOT))
        return candidate
    }

    private class CountingStream : OutputStream() {
        var count = 0L
            private set

        override fun write(b: Int) { count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { count += len }
    }

    companion object {
        const val MIN_CUSTOM_PX = 200
        const val MAX_CUSTOM_PX = 10000
        private const val ESTIMATE_MAX_DIM = 2560
        private const val MAX_DECODE_PIXELS = 40_000_000L

        /** Basic EXIF names (string literals so every API level resolves them). */
        private val EXIF_TAGS_TO_KEEP = listOf(
            "DateTime", "DateTimeOriginal", "DateTimeDigitized",
            "SubSecTime", "SubSecTimeOriginal", "SubSecTimeDigitized",
            "Make", "Model", "Software", "Artist", "Copyright",
            "ExposureTime", "FNumber", "ISOSpeedRatings", "FocalLength", "FocalLengthIn35mmFilm",
            "Flash", "WhiteBalance", "ExposureBiasValue", "ExposureProgram", "MeteringMode",
            "LensMake", "LensModel",
            "GPSLatitude", "GPSLatitudeRef", "GPSLongitude", "GPSLongitudeRef",
            "GPSAltitude", "GPSAltitudeRef", "GPSTimeStamp", "GPSDateStamp", "GPSProcessingMethod"
        )

        fun photoCount(n: Int) = if (n == 1) "1 photo" else "$n photos"

        fun formatSize(bytes: Long): String = when {
            bytes >= 1_000_000_000L -> String.format(Locale.US, "%.2f GB", bytes / 1_000_000_000.0)
            bytes >= 1_000_000L -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
            bytes >= 1_000L -> String.format(Locale.US, "%d KB", (bytes + 500) / 1_000)
            else -> "$bytes B"
        }
    }
}
