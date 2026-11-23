package com.photo_editor.my_photo_editor.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photo_editor.my_photo_editor.model.EditorLayer
import com.photo_editor.my_photo_editor.model.LayerContent
import com.photo_editor.my_photo_editor.utils.ColorMatrixBuilder
import com.photo_editor.my_photo_editor.utils.FilterPresets
import com.photo_editor.my_photo_editor.utils.ImageSaver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PhotoEditorViewModel : ViewModel() {
    private val _selectedUri = MutableStateFlow<Uri?>(null)
    val selectedUri: StateFlow<Uri?> = _selectedUri.asStateFlow()

    val brightness = MutableStateFlow(0f)
    val contrast = MutableStateFlow(1f)
    val saturation = MutableStateFlow(1f)
    val exposure = MutableStateFlow(0f)
    val temperature = MutableStateFlow(0f)
    val tint = MutableStateFlow(0f)
    val blur = MutableStateFlow(0f)
    val shadows = MutableStateFlow(0f)
    val highlights = MutableStateFlow(0f)
    val skinTone = MutableStateFlow(0f)
    val skinTexture = MutableStateFlow(0f)
    val spotHealing = MutableStateFlow(0f)
    val cheeks = MutableStateFlow(0f)
    val eyeSize = MutableStateFlow(0f)
    val selectedFilter = MutableStateFlow(FilterPresets.Normal)
    val filterIntensity = MutableStateFlow(1f)

    val rotation = MutableStateFlow(0f)
    val rotationX = MutableStateFlow(0f)
    val rotationY = MutableStateFlow(0f)
    val flipHorizontal = MutableStateFlow(false)
    val flipVertical = MutableStateFlow(false)
    val straighten = MutableStateFlow(0f)
    val cropRatio = MutableStateFlow<Float?>(null)
    val normalizedCropRect = MutableStateFlow<android.graphics.RectF?>(null)

    private val _layers = MutableStateFlow<List<EditorLayer>>(emptyList())
    val layers: StateFlow<List<EditorLayer>> = _layers.asStateFlow()

    private val _selectedLayerId = MutableStateFlow<Long?>(null)
    val selectedLayerId: StateFlow<Long?> = _selectedLayerId.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _saveResultUri = MutableStateFlow<Uri?>(null)
    val saveResultUri: StateFlow<Uri?> = _saveResultUri.asStateFlow()

    // Undo / Redo Stacks
    data class EditorSnapshot(
        val brightness: Float,
        val contrast: Float,
        val saturation: Float,
        val exposure: Float,
        val temperature: Float,
        val tint: Float,
        val blur: Float,
        val shadows: Float,
        val highlights: Float,
        val skinTone: Float,
        val skinTexture: Float,
        val spotHealing: Float,
        val cheeks: Float,
        val eyeSize: Float,
        val selectedFilter: android.graphics.ColorMatrix,
        val filterIntensity: Float,
        val rotation: Float,
        val rotationX: Float,
        val rotationY: Float,
        val flipHorizontal: Boolean,
        val flipVertical: Boolean,
        val straighten: Float,
        val cropRatio: Float?,
        val normalizedCropRect: android.graphics.RectF?,
        val layers: List<EditorLayer>
    )

    private val undoStack = ArrayDeque<EditorSnapshot>()
    private val redoStack = ArrayDeque<EditorSnapshot>()

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    private fun captureSnapshot(): EditorSnapshot = EditorSnapshot(
        brightness = brightness.value,
        contrast = contrast.value,
        saturation = saturation.value,
        exposure = exposure.value,
        temperature = temperature.value,
        tint = tint.value,
        blur = blur.value,
        shadows = shadows.value,
        highlights = highlights.value,
        skinTone = skinTone.value,
        skinTexture = skinTexture.value,
        spotHealing = spotHealing.value,
        cheeks = cheeks.value,
        eyeSize = eyeSize.value,
        selectedFilter = android.graphics.ColorMatrix(selectedFilter.value),
        filterIntensity = filterIntensity.value,
        rotation = rotation.value,
        rotationX = rotationX.value,
        rotationY = rotationY.value,
        flipHorizontal = flipHorizontal.value,
        flipVertical = flipVertical.value,
        straighten = straighten.value,
        cropRatio = cropRatio.value,
        normalizedCropRect = normalizedCropRect.value?.let { android.graphics.RectF(it) },
        layers = layers.value.toList()
    )

    fun recordSnapshot() {
        undoStack.addLast(captureSnapshot())
        if (undoStack.size > 25) undoStack.removeFirst()
        redoStack.clear()
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = false
    }

    private fun applySnapshot(snapshot: EditorSnapshot) {
        brightness.value = snapshot.brightness
        contrast.value = snapshot.contrast
        saturation.value = snapshot.saturation
        exposure.value = snapshot.exposure
        temperature.value = snapshot.temperature
        tint.value = snapshot.tint
        blur.value = snapshot.blur
        shadows.value = snapshot.shadows
        highlights.value = snapshot.highlights
        skinTone.value = snapshot.skinTone
        skinTexture.value = snapshot.skinTexture
        spotHealing.value = snapshot.spotHealing
        cheeks.value = snapshot.cheeks
        eyeSize.value = snapshot.eyeSize
        selectedFilter.value = android.graphics.ColorMatrix(snapshot.selectedFilter)
        filterIntensity.value = snapshot.filterIntensity
        rotation.value = snapshot.rotation
        rotationX.value = snapshot.rotationX
        rotationY.value = snapshot.rotationY
        flipHorizontal.value = snapshot.flipHorizontal
        flipVertical.value = snapshot.flipVertical
        straighten.value = snapshot.straighten
        cropRatio.value = snapshot.cropRatio
        normalizedCropRect.value = snapshot.normalizedCropRect?.let { android.graphics.RectF(it) }
        _layers.value = snapshot.layers.toList()
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        val current = captureSnapshot()
        val previous = undoStack.removeLast()
        redoStack.addLast(current)
        applySnapshot(previous)
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        val current = captureSnapshot()
        val next = redoStack.removeLast()
        undoStack.addLast(current)
        applySnapshot(next)
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
    }

    fun onImageSelected(uri: Uri) {
        _selectedUri.value = uri
        reset()
    }

    fun addLayer(layer: EditorLayer) {
        recordSnapshot()
        val current = _layers.value.toMutableList()
        current.add(layer)
        _layers.value = current
        _selectedLayerId.value = layer.id
    }

    fun addTextLayer(
        text: String,
        fontSize: Float = 28f,
        initialX: Float = 200f,
        initialY: Float = 300f,
        color: Int = android.graphics.Color.WHITE,
        fontStyleIndex: Int = 0,
        bgBadgeColor: Int? = null,
        letterSpacing: Float = 0f
    ) {
        recordSnapshot()
        val current = _layers.value.toMutableList()
        val newLayer = EditorLayer(
            content = LayerContent.Text(
                text = text,
                color = color,
                fontSize = fontSize,
                fontStyleIndex = fontStyleIndex,
                bgBadgeColor = bgBadgeColor,
                letterSpacing = letterSpacing
            ),
            x = initialX,
            y = initialY
        )
        current.add(newLayer)
        _layers.value = current
        _selectedLayerId.value = newLayer.id
    }

    fun updateLayerColor(layer: EditorLayer, newColor: Int) {
        val current = _layers.value.toMutableList()
        val index = current.indexOfFirst { it.id == layer.id }
        if (index != -1) {
            val content = current[index].content as? LayerContent.Text ?: return
            recordSnapshot()
            current[index] = current[index].copy(content = content.copy(color = newColor))
            _layers.value = current
        }
    }

    fun updateLayerStyle(layer: EditorLayer, styleIndex: Int, letterSpacing: Float = 0f) {
        val current = _layers.value.toMutableList()
        val index = current.indexOfFirst { it.id == layer.id }
        if (index != -1) {
            val content = current[index].content as? LayerContent.Text ?: return
            recordSnapshot()
            current[index] = current[index].copy(content = content.copy(fontStyleIndex = styleIndex, letterSpacing = letterSpacing))
            _layers.value = current
        }
    }

    fun updateLayerBadge(layer: EditorLayer, badgeColor: Int?) {
        val current = _layers.value.toMutableList()
        val index = current.indexOfFirst { it.id == layer.id }
        if (index != -1) {
            val content = current[index].content as? LayerContent.Text ?: return
            recordSnapshot()
            current[index] = current[index].copy(content = content.copy(bgBadgeColor = badgeColor))
            _layers.value = current
        }
    }

    fun removeLayer(layer: EditorLayer) {
        recordSnapshot()
        val current = _layers.value.toMutableList()
        current.removeAll { it.id == layer.id }
        _layers.value = current
        if (_selectedLayerId.value == layer.id) {
            _selectedLayerId.value = null
        }
    }

    fun updateLayerPosition(layer: EditorLayer, newX: Float, newY: Float) {
        val current = _layers.value.toMutableList()
        val index = current.indexOfFirst { it.id == layer.id }
        if (index != -1 && (current[index].x != newX || current[index].y != newY)) {
            recordSnapshot()
            current[index] = current[index].copy(x = newX, y = newY)
            _layers.value = current
        }
    }

    fun updateLayerTransform(layer: EditorLayer, newX: Float, newY: Float, newScale: Float, newRotation: Float) {
        val current = _layers.value.toMutableList()
        val index = current.indexOfFirst { it.id == layer.id }
        if (index != -1) {
            val old = current[index]
            if (old.x != newX || old.y != newY || old.scale != newScale || old.rotation != newRotation) {
                recordSnapshot()
                current[index] = old.copy(x = newX, y = newY, scale = newScale, rotation = newRotation)
                _layers.value = current
            }
        }
    }

    fun updateLayerText(layer: EditorLayer, newText: String) {
        val current = _layers.value.toMutableList()
        val index = current.indexOfFirst { it.id == layer.id }
        if (index != -1) {
            val content = current[index].content as? LayerContent.Text ?: return
            current[index] = current[index].copy(content = content.copy(text = newText))
            _layers.value = current
        }
    }

    fun updateLayerFontSize(layer: EditorLayer, newSize: Float) {
        val current = _layers.value.toMutableList()
        val index = current.indexOfFirst { it.id == layer.id }
        if (index != -1) {
            val content = current[index].content as? LayerContent.Text ?: return
            current[index] = current[index].copy(content = content.copy(fontSize = newSize))
            _layers.value = current
        }
    }

    fun updateLayerOpacity(layer: EditorLayer, opacity: Float) {
        val current = _layers.value.toMutableList()
        val index = current.indexOfFirst { it.id == layer.id }
        if (index != -1) {
            val content = current[index].content as? LayerContent.Text ?: return
            current[index] = current[index].copy(content = content.copy(opacity = opacity))
            _layers.value = current
        }
    }

    fun updateLayerAlignment(layer: EditorLayer, alignment: Int) {
        val current = _layers.value.toMutableList()
        val index = current.indexOfFirst { it.id == layer.id }
        if (index != -1) {
            val content = current[index].content as? LayerContent.Text ?: return
            recordSnapshot()
            current[index] = current[index].copy(content = content.copy(alignment = alignment))
            _layers.value = current
        }
    }

    fun updateLayerFormat(
        layer: EditorLayer,
        isBold: Boolean? = null,
        isItalic: Boolean? = null,
        isAllCaps: Boolean? = null
    ) {
        val current = _layers.value.toMutableList()
        val index = current.indexOfFirst { it.id == layer.id }
        if (index != -1) {
            val content = current[index].content as? LayerContent.Text ?: return
            recordSnapshot()
            current[index] = current[index].copy(
                content = content.copy(
                    isBold = isBold ?: content.isBold,
                    isItalic = isItalic ?: content.isItalic,
                    isAllCaps = isAllCaps ?: content.isAllCaps
                )
            )
            _layers.value = current
        }
    }

    fun updateLayerShadow(
        layer: EditorLayer,
        shadowRadius: Float,
        shadowDx: Float,
        shadowDy: Float,
        shadowColor: Int
    ) {
        val current = _layers.value.toMutableList()
        val index = current.indexOfFirst { it.id == layer.id }
        if (index != -1) {
            val content = current[index].content as? LayerContent.Text ?: return
            recordSnapshot()
            current[index] = current[index].copy(
                content = content.copy(
                    shadowRadius = shadowRadius,
                    shadowDx = shadowDx,
                    shadowDy = shadowDy,
                    shadowColor = shadowColor
                )
            )
            _layers.value = current
        }
    }

    fun setSelectedLayerId(id: Long?) {
        _selectedLayerId.value = id
    }

    fun saveImage(context: Context, drawMarkup: ((Canvas) -> Unit)? = null) {
        val uri = _selectedUri.value ?: return
        if (_isSaving.value) return
        _isSaving.value = true
        _saveResultUri.value = null

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val bitmap = decodeUpright(context, uri, MAX_SAVE_DIMENSION)

                    if (bitmap != null) {
                        val finalMatrix = ColorMatrixBuilder.build(
                            brightness = brightness.value,
                            contrast = contrast.value,
                            saturation = saturation.value,
                            exposure = exposure.value,
                            temperature = temperature.value,
                            tint = tint.value,
                            shadows = shadows.value,
                            highlights = highlights.value,
                            skinTone = skinTone.value,
                            skinTexture = skinTexture.value,
                            cheeks = cheeks.value,
                            eyeSize = eyeSize.value,
                            filterMatrix = selectedFilter.value,
                            filterIntensity = filterIntensity.value
                        )
                        ImageSaver.saveBitmapToMediaStore(
                            context = context,
                            originalBitmap = bitmap,
                            colorMatrix = finalMatrix,
                            rotation = rotation.value + straighten.value,
                            rotationX = rotationX.value,
                            rotationY = rotationY.value,
                            flipHorizontal = flipHorizontal.value,
                            flipVertical = flipVertical.value,
                            cropRect = normalizedCropRect.value,
                            drawMarkup = drawMarkup,
                            layers = layers.value
                        )
                    } else null
                } catch (e: Throwable) {
                    e.printStackTrace()
                    null
                }
            }
            _saveResultUri.value = result
            _isSaving.value = false
        }
    }

    /**
     * Decodes the photo upright (EXIF orientation applied, as the preview shows it) and no
     * bigger than [maxDim] on its long edge, so 50–200 MP photos don't run out of memory.
     */
    private fun decodeUpright(context: Context, uri: Uri, maxDim: Int): android.graphics.Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        }
        var bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val longEdge = maxOf(bitmap.width, bitmap.height)
        if (longEdge > maxDim) {
            val scale = maxDim.toFloat() / longEdge
            val scaled = android.graphics.Bitmap.createScaledBitmap(
                bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true
            )
            if (scaled !== bitmap) bitmap.recycle()
            bitmap = scaled
        }
        val orientation = try {
            resolver.openInputStream(uri)?.use {
                android.media.ExifInterface(it).getAttributeInt(
                    android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL
                )
            } ?: android.media.ExifInterface.ORIENTATION_NORMAL
        } catch (_: Exception) {
            android.media.ExifInterface.ORIENTATION_NORMAL
        }
        val m = android.graphics.Matrix()
        when (orientation) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            android.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            android.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            android.media.ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            android.media.ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return bitmap
        }
        val upright = android.graphics.Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        if (upright !== bitmap) bitmap.recycle()
        return upright
    }

    fun reset() {
        brightness.value = 0f
        contrast.value = 1f
        saturation.value = 1f
        exposure.value = 0f
        temperature.value = 0f
        tint.value = 0f
        blur.value = 0f
        shadows.value = 0f
        highlights.value = 0f
        skinTone.value = 0f
        skinTexture.value = 0f
        spotHealing.value = 0f
        cheeks.value = 0f
        eyeSize.value = 0f
        selectedFilter.value = FilterPresets.Normal
        filterIntensity.value = 1f
        rotation.value = 0f
        rotationX.value = 0f
        rotationY.value = 0f
        flipHorizontal.value = false
        flipVertical.value = false
        straighten.value = 0f
        cropRatio.value = null
        normalizedCropRect.value = null
        _layers.value = emptyList()
        _selectedLayerId.value = null
        undoStack.clear()
        redoStack.clear()
        _canUndo.value = false
        _canRedo.value = false
    }

    companion object {
        /** Long-edge cap for saved edits (about 27 MP for 4:3) — keeps big photos in memory. */
        private const val MAX_SAVE_DIMENSION = 6000
    }
}
