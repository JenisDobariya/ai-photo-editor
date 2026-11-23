package com.photo_editor.my_photo_editor.ui.quickfix

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.photo_editor.my_photo_editor.utils.AnalyticsManager
import com.photo_editor.my_photo_editor.utils.AutoEnhancer
import com.photo_editor.my_photo_editor.utils.BitmapLoader
import com.photo_editor.my_photo_editor.utils.FaceBlurrer
import com.photo_editor.my_photo_editor.utils.MediaStoreSaver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class QuickFixMode { ENHANCE, BLUR_FACES }

enum class FaceStatus { IDLE, DETECTING, READY, NONE, UNAVAILABLE, FAILED }

/** A detected face; [box] is in preview-bitmap pixels. */
data class FaceItem(val box: RectF, val selected: Boolean)

data class QuickFixUiState(
    val mode: QuickFixMode = QuickFixMode.ENHANCE,
    /** Non-null while a blocking step runs (loading / saving). */
    val loadingText: String? = null,
    /** The untouched preview (shown while comparing). */
    val original: Bitmap? = null,
    /** What the preview shows now. */
    val display: Bitmap? = null,
    /** Bumped on every render (display buffers are reused, so compare this, not the bitmap). */
    val displayVersion: Int = 0,
    val strength: Int = 70,
    val enhanceReady: Boolean = false,
    val alreadyGood: Boolean = false,
    val faceStatus: FaceStatus = FaceStatus.IDLE,
    val faces: List<FaceItem> = emptyList(),
    val blurStyle: FaceBlurrer.Style = FaceBlurrer.Style.BLUR,
    val blurStrength: Int = 60,
    val saving: Boolean = false
)

sealed class QuickFixEvent {
    data class Message(val text: String) : QuickFixEvent()
    data class Saved(val uri: Uri) : QuickFixEvent()
    object NoFaces : QuickFixEvent()
    object LoadFailed : QuickFixEvent()
}

class QuickFixViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(QuickFixUiState())
    val state: StateFlow<QuickFixUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<QuickFixEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<QuickFixEvent> = _events.asSharedFlow()

    private var started = false
    private var sourceUri: Uri? = null

    // Preview-resolution working data. Touched only while holding [workMutex].
    private var preview: Bitmap? = null
    private var origPixels: IntArray? = null
    private var enhPixels: IntArray? = null
    private var outPixels: IntArray? = null
    private var params: AutoEnhancer.Params? = null
    private var bufferA: Bitmap? = null
    private var bufferB: Bitmap? = null
    private var front: Bitmap? = null

    /** Serialises renders, saves and the final cleanup so no bitmap is recycled mid-use. */
    private val workMutex = Mutex()
    private var renderJob: Job? = null
    private var detectJob: Job? = null
    private var noFacesNotified = false

    fun start(uri: Uri, mode: QuickFixMode) {
        if (started) return
        started = true
        sourceUri = uri
        _state.update { it.copy(mode = mode, loadingText = "Loading photo…") }
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.IO) { loadPreview(uri) }
            if (bmp == null) {
                _state.update { it.copy(loadingText = null) }
                _events.emit(QuickFixEvent.LoadFailed)
                return@launch
            }
            preview = bmp
            _state.update { it.copy(original = bmp, display = bmp, displayVersion = it.displayVersion + 1, loadingText = null) }
            onModeEntered(mode)
        }
    }

    private fun loadPreview(uri: Uri): Bitmap? {
        val ctx = getApplication<Application>()
        for (dim in intArrayOf(PREVIEW_SIZE, 1200, 800)) {
            BitmapLoader.decodeUpright(ctx, uri, dim)?.let { return it }
        }
        return null
    }

    fun setMode(mode: QuickFixMode) {
        if (_state.value.mode == mode) return
        AnalyticsManager.logEvent("QuickFix_mode_${mode.name.lowercase()}")
        _state.update { it.copy(mode = mode) }
        if (preview != null) onModeEntered(mode)
    }

    private fun onModeEntered(mode: QuickFixMode) {
        when (mode) {
            QuickFixMode.ENHANCE -> scheduleRender(0)
            QuickFixMode.BLUR_FACES -> when (_state.value.faceStatus) {
                FaceStatus.READY -> scheduleRender(0)
                FaceStatus.DETECTING -> showOriginal()
                FaceStatus.NONE -> {
                    showOriginal()
                    if (!noFacesNotified) {
                        noFacesNotified = true
                        _events.tryEmit(QuickFixEvent.NoFaces)
                    }
                }
                FaceStatus.IDLE, FaceStatus.UNAVAILABLE, FaceStatus.FAILED -> {
                    showOriginal()
                    detectFaces()
                }
            }
        }
    }

    private fun showOriginal() {
        renderJob?.cancel()
        val p = preview ?: return
        _state.update { it.copy(display = p, displayVersion = it.displayVersion + 1) }
    }

    // ---------------------------------------------------------------- enhance

    fun setStrength(value: Int) {
        val v = value.coerceIn(0, 100)
        if (_state.value.strength == v) return
        _state.update { it.copy(strength = v) }
        if (_state.value.mode == QuickFixMode.ENHANCE) scheduleRender(SLIDER_DEBOUNCE_MS)
    }

    /** Must hold [workMutex]. Builds analysis + fully enhanced preview pixels once. */
    private fun ensureEnhanceData(src: Bitmap): Boolean {
        if (enhPixels != null && origPixels != null && params != null) return true
        val w = src.width
        val h = src.height
        val o = IntArray(w * h)
        src.getPixels(o, 0, w, 0, 0, w, h)
        val p = AutoEnhancer.analyze(src)
        val e = o.copyOf()
        AutoEnhancer.enhancePixels(e, w, h, p, 1f)
        origPixels = o
        enhPixels = e
        params = p
        _state.update { it.copy(enhanceReady = true, alreadyGood = p.alreadyGood) }
        return true
    }

    // ---------------------------------------------------------------- faces

    fun retryFaceDetection() {
        detectFaces()
    }

    private fun detectFaces() {
        if (detectJob?.isActive == true) return
        val src = preview ?: return
        _state.update { it.copy(faceStatus = FaceStatus.DETECTING) }
        detectJob = viewModelScope.launch {
            try {
                // ML Kit reads the bitmap on its own thread; give it a private copy so a
                // cleanup can never recycle pixels it is still reading.
                val copy = withContext(Dispatchers.Default) { src.copy(Bitmap.Config.ARGB_8888, false) }
                    ?: throw IllegalStateException("Could not copy preview")
                val boxes = withTimeoutOrNull(DETECT_TIMEOUT_MS) { detect(copy) }
                if (boxes == null) {
                    onDetectionUnavailable()
                    return@launch
                }
                copy.recycle()
                val faces = boxes
                    .filter { it.width() >= 2f && it.height() >= 2f }
                    .filter { RectF.intersects(it, RectF(0f, 0f, src.width.toFloat(), src.height.toFloat())) }
                    .map { FaceItem(it, true) }
                if (faces.isEmpty()) {
                    _state.update { it.copy(faceStatus = FaceStatus.NONE, faces = emptyList()) }
                    AnalyticsManager.logEvent("QuickFix_faces_none")
                    if (_state.value.mode == QuickFixMode.BLUR_FACES && !noFacesNotified) {
                        noFacesNotified = true
                        _events.emit(QuickFixEvent.NoFaces)
                    }
                } else {
                    _state.update { it.copy(faceStatus = FaceStatus.READY, faces = faces) }
                    AnalyticsManager.logEvent("QuickFix_faces_found")
                    if (_state.value.mode == QuickFixMode.BLUR_FACES) scheduleRender(0)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: MlKitException) {
                onDetectionUnavailable()
            } catch (e: Exception) {
                e.printStackTrace()
                // No / outdated Play services surfaces as a gms ApiException.
                if (e.javaClass.name.contains("ApiException")) {
                    onDetectionUnavailable()
                } else {
                    _state.update { it.copy(faceStatus = FaceStatus.FAILED) }
                    if (_state.value.mode == QuickFixMode.BLUR_FACES) {
                        _events.emit(QuickFixEvent.Message("Couldn't scan this photo for faces"))
                    }
                }
            } catch (e: OutOfMemoryError) {
                _state.update { it.copy(faceStatus = FaceStatus.FAILED) }
            }
        }
    }

    private suspend fun onDetectionUnavailable() {
        _state.update { it.copy(faceStatus = FaceStatus.UNAVAILABLE) }
        if (_state.value.mode == QuickFixMode.BLUR_FACES) {
            _events.emit(QuickFixEvent.Message(MSG_DETECTOR_NOT_READY))
        }
    }

    private suspend fun detect(bitmap: Bitmap): List<RectF> {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setMinFaceSize(0.05f)
            .build()
        val detector = FaceDetection.getClient(options)
        try {
            val task: Task<List<Face>> = detector.process(InputImage.fromBitmap(bitmap, 0))
            return task.await().map { RectF(it.boundingBox) }
        } finally {
            detector.close()
        }
    }

    fun toggleFace(index: Int) {
        val faces = _state.value.faces
        if (index !in faces.indices) return
        _state.update { s ->
            s.copy(faces = s.faces.mapIndexed { i, f -> if (i == index) f.copy(selected = !f.selected) else f })
        }
        if (_state.value.mode == QuickFixMode.BLUR_FACES) scheduleRender(0)
    }

    fun setBlurStyle(style: FaceBlurrer.Style) {
        if (_state.value.blurStyle == style) return
        _state.update { it.copy(blurStyle = style) }
        if (_state.value.mode == QuickFixMode.BLUR_FACES) scheduleRender(0)
    }

    fun setBlurStrength(value: Int) {
        val v = value.coerceIn(0, 100)
        if (_state.value.blurStrength == v) return
        _state.update { it.copy(blurStrength = v) }
        if (_state.value.mode == QuickFixMode.BLUR_FACES) scheduleRender(SLIDER_DEBOUNCE_MS)
    }

    // ---------------------------------------------------------------- rendering

    private fun scheduleRender(debounceMs: Long) {
        renderJob?.cancel()
        renderJob = viewModelScope.launch(Dispatchers.Default) {
            if (debounceMs > 0) delay(debounceMs)
            workMutex.withLock {
                ensureActive()
                val src = preview ?: return@withLock
                if (src.isRecycled) return@withLock
                val s = _state.value
                val back = try {
                    when (s.mode) {
                        QuickFixMode.ENHANCE -> renderEnhance(src, s.strength)
                        QuickFixMode.BLUR_FACES -> {
                            if (s.faceStatus != FaceStatus.READY) null
                            else renderFaces(src, s)
                        }
                    }
                } catch (e: OutOfMemoryError) {
                    null
                }
                if (back == null) {
                    if (s.mode == QuickFixMode.ENHANCE) {
                        _events.tryEmit(QuickFixEvent.Message("Not enough memory to preview this photo"))
                    }
                    return@withLock
                }
                ensureActive()
                front = back
                _state.update { it.copy(display = back, displayVersion = it.displayVersion + 1) }
            }
        }
    }

    /** Double buffering: always draw into the bitmap that is not on screen. */
    private fun backBuffer(src: Bitmap): Bitmap {
        val a = bufferA ?: Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888).also { bufferA = it }
        val b = bufferB ?: Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888).also { bufferB = it }
        return if (front === a) b else a
    }

    private fun renderEnhance(src: Bitmap, strength: Int): Bitmap? {
        if (!ensureEnhanceData(src)) return null
        val o = origPixels ?: return null
        val e = enhPixels ?: return null
        val out = outPixels ?: IntArray(o.size).also { outPixels = it }
        AutoEnhancer.blend(o, e, out, strength / 100f)
        val back = backBuffer(src)
        back.setPixels(out, 0, src.width, 0, 0, src.width, src.height)
        return back
    }

    private fun renderFaces(src: Bitmap, s: QuickFixUiState): Bitmap {
        val back = backBuffer(src)
        Canvas(back).drawBitmap(src, 0f, 0f, null)
        val boxes = s.faces.filter { it.selected }.map { it.box }
        FaceBlurrer.apply(back, src, boxes, s.blurStyle, s.blurStrength)
        return back
    }

    // ---------------------------------------------------------------- saving

    fun save() {
        val s = _state.value
        val src = preview
        val uri = sourceUri
        if (s.saving || src == null || uri == null) return
        if (s.mode == QuickFixMode.BLUR_FACES) {
            val msg = when {
                s.faceStatus == FaceStatus.DETECTING -> "Still looking for faces…"
                s.faceStatus == FaceStatus.UNAVAILABLE -> MSG_DETECTOR_NOT_READY
                s.faceStatus != FaceStatus.READY -> "No faces to blur in this photo"
                s.faces.none { it.selected } -> "Tap a face to select it for blurring"
                else -> null
            }
            if (msg != null) {
                _events.tryEmit(QuickFixEvent.Message(msg))
                return
            }
        }
        AnalyticsManager.logEvent(if (s.mode == QuickFixMode.ENHANCE) "QuickFix_save_enhance" else "QuickFix_save_blur_faces")
        _state.update { it.copy(saving = true, loadingText = "Saving full quality…") }
        viewModelScope.launch {
            val savedUri = try {
                withContext(Dispatchers.Default) {
                    workMutex.withLock {
                        if (src.isRecycled) return@withLock null
                        val full = renderFull(uri, src, s) ?: return@withLock null
                        try {
                            withContext(Dispatchers.IO) {
                                MediaStoreSaver.saveBitmap(
                                    getApplication<Application>(),
                                    full,
                                    if (s.mode == QuickFixMode.ENHANCE) "ENHANCED" else "PRIVATE",
                                    Bitmap.CompressFormat.JPEG,
                                    95
                                )
                            }
                        } finally {
                            full.recycle()
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                e.printStackTrace()
                null
            }
            _state.update { it.copy(saving = false, loadingText = null) }
            if (savedUri != null) _events.emit(QuickFixEvent.Saved(savedUri))
            else _events.emit(QuickFixEvent.Message("Couldn't save the photo"))
        }
    }

    /**
     * Re-runs the current fix on a full-quality decode (long edge up to 4096), stepping down
     * the size on OutOfMemoryError. Returns a new bitmap the caller must recycle.
     */
    private fun renderFull(uri: Uri, src: Bitmap, s: QuickFixUiState): Bitmap? {
        val ctx = getApplication<Application>()
        for (dim in intArrayOf(FULL_SIZE, 3072, 2048, PREVIEW_SIZE)) {
            var decoded: Bitmap? = null
            try {
                decoded = BitmapLoader.decodeUpright(ctx, uri, dim) ?: continue
                when (s.mode) {
                    QuickFixMode.ENHANCE -> {
                        val p = params ?: AutoEnhancer.analyze(src).also { params = it }
                        val w = decoded.width
                        val h = decoded.height
                        val px = IntArray(w * h)
                        decoded.getPixels(px, 0, w, 0, 0, w, h)
                        decoded.recycle()
                        decoded = null
                        AutoEnhancer.enhancePixels(px, w, h, p, s.strength / 100f)
                        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
                    }
                    QuickFixMode.BLUR_FACES -> {
                        val target = if (decoded.isMutable) decoded else {
                            val copy = decoded.copy(Bitmap.Config.ARGB_8888, true) ?: throw OutOfMemoryError()
                            decoded.recycle()
                            copy
                        }
                        decoded = target
                        val sx = target.width.toFloat() / src.width
                        val sy = target.height.toFloat() / src.height
                        val boxes = s.faces.filter { it.selected }.map {
                            RectF(it.box.left * sx, it.box.top * sy, it.box.right * sx, it.box.bottom * sy)
                        }
                        FaceBlurrer.apply(target, target, boxes, s.blurStyle, s.blurStrength)
                        decoded = null
                        return target
                    }
                }
            } catch (e: OutOfMemoryError) {
                decoded?.let { if (!it.isRecycled) it.recycle() }
                System.gc()
            }
        }
        return null
    }

    override fun onCleared() {
        super.onCleared()
        // Running pixel loops don't stop on cancel; wait for them before freeing memory.
        val a = bufferA
        val b = bufferB
        val p = preview
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            workMutex.withLock {
                a?.recycle()
                b?.recycle()
                p?.recycle()
            }
        }
        bufferA = null
        bufferB = null
        front = null
        origPixels = null
        enhPixels = null
        outPixels = null
    }

    companion object {
        const val PREVIEW_SIZE = 1600
        const val FULL_SIZE = 4096
        private const val SLIDER_DEBOUNCE_MS = 60L
        private const val DETECT_TIMEOUT_MS = 20_000L
        const val MSG_DETECTOR_NOT_READY = "Face detection is getting ready, try again in a minute"
    }
}
