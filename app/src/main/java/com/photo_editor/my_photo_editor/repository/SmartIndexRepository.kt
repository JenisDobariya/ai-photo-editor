package com.photo_editor.my_photo_editor.repository

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeler
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.photo_editor.my_photo_editor.db.AppDatabase
import com.photo_editor.my_photo_editor.db.MediaIndex
import com.photo_editor.my_photo_editor.model.MediaFile
import com.photo_editor.my_photo_editor.model.MediaType
import com.photo_editor.my_photo_editor.utils.BitmapLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Background, on-device analysis of the photo library. For every photo it stores
 *  - labels from ML Kit image labeling (Smart Search, "Things" albums),
 *  - a 64-bit difference hash (duplicate / similar photo finder),
 *  - a sharpness score (blurry photo finder).
 *
 * Runs incrementally: only new or changed photos are analysed, removed photos are dropped.
 * Nothing leaves the phone. Started from [com.photo_editor.my_photo_editor.ui.MainViewModel]
 * whenever the library is (re)loaded.
 */
object SmartIndexRepository {

    data class Progress(
        val total: Int = 0,
        val done: Int = 0,
        val running: Boolean = false,
        /** False while the labeling model is still being downloaded by Play services. */
        val labelingAvailable: Boolean = true
    ) {
        val fraction: Float get() = if (total == 0) 1f else done.toFloat() / total
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    private var job: Job? = null
    @Volatile private var pending: List<MediaFile>? = null
    private var labeler: ImageLabeler? = null

    fun observe(context: Context): Flow<List<MediaIndex>> =
        AppDatabase.getDatabase(context).mediaIndexDao().observeAll()

    suspend fun getAll(context: Context): List<MediaIndex> =
        AppDatabase.getDatabase(context).mediaIndexDao().getAll()

    /** Brings the index in line with [media] (the full library). Safe to call often. */
    fun sync(context: Context, media: List<MediaFile>) {
        val appContext = context.applicationContext
        synchronized(this) {
            if (job?.isActive == true) {
                pending = media // picked up when the current pass ends
                return
            }
            job = scope.launch {
                var next: List<MediaFile>? = media
                while (next != null && isActive) {
                    runPass(appContext, next)
                    next = pending
                    pending = null
                }
            }
        }
    }

    private suspend fun runPass(context: Context, media: List<MediaFile>) {
        val db = AppDatabase.getDatabase(context)
        val dao = db.mediaIndexDao()
        val keys = dao.getKeys().associateBy { it.uri }
        // Locked Folder items are never analysed, whichever screen asked for the sync, so
        // every caller ends up with the same index (no rows deleted/re-added back and forth).
        val hidden = db.hiddenDao().getHiddenUris().toHashSet()
        val images = media.filter { it.type == MediaType.IMAGE && it.size > 0 && !hidden.contains(it.uri.toString()) }

        val stale = keys.keys - images.map { it.uri.toString() }.toSet()
        if (stale.isNotEmpty()) stale.chunked(500).forEach { dao.delete(it) }

        var labelingAvailable = true
        val todo = images.filter { file ->
            val key = keys[file.uri.toString()]
            key == null || key.dateAdded != file.dateAdded || key.size != file.size || key.labels == null
        }
        if (todo.isEmpty()) {
            _progress.value = Progress(images.size, images.size, running = false)
            return
        }

        _progress.value = Progress(todo.size, 0, running = true)
        val batch = ArrayList<MediaIndex>(BATCH)
        todo.forEachIndexed { i, file ->
            if (!scope.isActive) return
            val existing = keys[file.uri.toString()]
            val unchanged = existing != null && existing.dateAdded == file.dateAdded && existing.size == file.size
            // Only the labels are missing and the labeler isn't ready (model still downloading or
            // no Play services): nothing new to compute, try again on a later pass.
            if (unchanged && !labelingAvailable) {
                _progress.value = Progress(todo.size, i + 1, running = true, labelingAvailable = false)
                return@forEachIndexed
            }
            val bitmap = BitmapLoader.loadSmall(context, file.uri, THUMB_SIZE)
            if (bitmap != null) {
                try {
                    val labels = if (labelingAvailable) {
                        try {
                            label(bitmap)
                        } catch (e: MlKitException) {
                            if (e.errorCode == MlKitException.UNAVAILABLE) labelingAvailable = false
                            null
                        } catch (e: Exception) {
                            null
                        }
                    } else null
                    batch.add(
                        MediaIndex(
                            uri = file.uri.toString(),
                            dateAdded = file.dateAdded,
                            size = file.size,
                            width = file.width,
                            height = file.height,
                            dHash = differenceHash(bitmap),
                            sharpness = sharpness(bitmap),
                            labels = labels
                        )
                    )
                } finally {
                    bitmap.recycle()
                }
            }
            if (batch.size >= BATCH) {
                dao.upsert(batch.toList())
                batch.clear()
            }
            _progress.value = Progress(todo.size, i + 1, running = true, labelingAvailable = labelingAvailable)
        }
        if (batch.isNotEmpty()) dao.upsert(batch.toList())
        _progress.value = Progress(todo.size, todo.size, running = false, labelingAvailable = labelingAvailable)
    }

    // ------------------------------------------------------------------ analysis

    private fun label(bitmap: Bitmap): String? {
        val client = labeler ?: ImageLabeling.getClient(
            ImageLabelerOptions.Builder().setConfidenceThreshold(LABEL_CONFIDENCE).build()
        ).also { labeler = it }
        val result = Tasks.await(client.process(InputImage.fromBitmap(bitmap, 0)), 15, TimeUnit.SECONDS)
        // Empty string (not null) = analysed, nothing confident found; null = retry later.
        return result.sortedByDescending { it.confidence }
            .take(MAX_LABELS)
            .joinToString("|") { it.text.lowercase(Locale.ROOT) }
    }

    /**
     * 64-bit dHash: the photo is reduced to 9×8 grey cells (box-averaged, so it is stable
     * against resizing and recompression) and each bit says whether a cell is brighter than
     * its right neighbour.
     */
    fun differenceHash(bitmap: Bitmap): Long {
        val small = Bitmap.createScaledBitmap(bitmap, 36, 32, true)
        val px = IntArray(36 * 32)
        small.getPixels(px, 0, 36, 0, 0, 36, 32)
        if (small !== bitmap) small.recycle()
        val cells = FloatArray(9 * 8)
        for (y in 0 until 32) for (x in 0 until 36) {
            val c = px[y * 36 + x]
            val grey = 0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
            cells[(y / 4) * 9 + (x / 4)] += grey
        }
        var hash = 0L
        var bit = 0
        for (row in 0 until 8) for (col in 0 until 8) {
            if (cells[row * 9 + col] > cells[row * 9 + col + 1]) hash = hash or (1L shl bit)
            bit++
        }
        return hash
    }

    /**
     * Sharpness = variance of the 4-neighbour Laplacian on a grey copy whose long edge is
     * 256 px. Sharp photos usually score well above 100; badly blurred ones below ~25.
     */
    fun sharpness(bitmap: Bitmap): Float {
        val scale = 256f / maxOf(bitmap.width, bitmap.height)
        val w = (bitmap.width * scale).toInt().coerceAtLeast(3)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(3)
        val small = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== bitmap) small.recycle()
        val grey = FloatArray(w * h) { i ->
            val c = px[i]
            0.299f * ((c shr 16) and 0xFF) + 0.587f * ((c shr 8) and 0xFF) + 0.114f * (c and 0xFF)
        }
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val lap = grey[i - 1] + grey[i + 1] + grey[i - w] + grey[i + w] - 4f * grey[i]
            sum += lap
            sumSq += lap * lap
            n++
        }
        if (n == 0) return 0f
        val mean = sum / n
        return (sumSq / n - mean * mean).toFloat()
    }

    fun hammingDistance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    private const val THUMB_SIZE = 320
    private const val BATCH = 25
    private const val LABEL_CONFIDENCE = 0.7f
    private const val MAX_LABELS = 8
}
