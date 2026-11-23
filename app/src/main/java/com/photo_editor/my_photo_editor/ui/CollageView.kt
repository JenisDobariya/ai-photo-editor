package com.photo_editor.my_photo_editor.ui

import android.content.Context
import android.graphics.*
import android.net.Uri
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.graphics.toRect
import com.photo_editor.my_photo_editor.model.CollageLayoutRepository
import com.photo_editor.my_photo_editor.model.CollageTemplate
import kotlin.math.max

class CollageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class SlotState(
        var bitmap: Bitmap? = null,
        var uri: Uri? = null,
        var scale: Float = 1f,
        var transX: Float = 0f,
        var transY: Float = 0f,
        var rotationDegrees: Float = 0f,
        val pixelRect: RectF = RectF(),
        val clipPath: Path = Path(),
        val matrix: Matrix = Matrix()
    )

    private val slots = mutableListOf<SlotState>()
    private var activeTemplate: CollageTemplate = CollageLayoutRepository.getTemplatesForCount(2).first()

    var collageBackgroundColor: Int = Color.parseColor("#0F1114")
        set(value) {
            field = value
            invalidate()
        }

    var outerMargin: Float = 16f
        set(value) {
            field = value
            recalculateLayout()
            invalidate()
        }

    var spacing: Float = 12f
        set(value) {
            field = value
            recalculateLayout()
            invalidate()
        }

    var cornerRadius: Float = 16f
        set(value) {
            field = value
            recalculateLayout()
            invalidate()
        }

    var aspectRatio: Float = 1.0f // width / height
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    var selectedSlotIndex: Int = -1
        set(value) {
            field = value
            onSlotSelectedListener?.invoke(value)
            invalidate()
        }

    var onSlotSelectedListener: ((Int) -> Unit)? = null

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val selectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#3D8BFD")
        strokeWidth = 6f
    }

    private var canvasBounds = RectF()

    // Touch handling
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isDragging = false

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (selectedSlotIndex in slots.indices) {
                val slot = slots[selectedSlotIndex]
                slot.scale = (slot.scale * detector.scaleFactor).coerceIn(0.5f, 5.0f)
                updateSlotMatrix(slot)
                invalidate()
                return true
            }
            return false
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val touchedIndex = slots.indexOfFirst { it.pixelRect.contains(e.x, e.y) }
            selectedSlotIndex = touchedIndex
            return true
        }
    })

    fun setTemplate(template: CollageTemplate) {
        activeTemplate = template
        syncSlotsWithTemplate()
        recalculateLayout()
        invalidate()
    }

    fun getTemplate(): CollageTemplate = activeTemplate

    fun setImageBitmaps(items: List<Pair<Uri, Bitmap>>) {
        slots.clear()
        items.forEach { (uri, bmp) ->
            slots.add(SlotState(bitmap = bmp, uri = uri))
        }
        syncSlotsWithTemplate()
        recalculateLayout()
        invalidate()
    }

    fun getSlotCount(): Int = slots.size

    fun getSelectedSlot(): SlotState? = slots.getOrNull(selectedSlotIndex)

    fun rotateSelectedSlot() {
        if (selectedSlotIndex in slots.indices) {
            val slot = slots[selectedSlotIndex]
            slot.rotationDegrees = (slot.rotationDegrees + 90f) % 360f
            updateSlotMatrix(slot)
            invalidate()
        }
    }

    fun swapSlots(index1: Int, index2: Int) {
        if (index1 in slots.indices && index2 in slots.indices && index1 != index2) {
            val tempBmp = slots[index1].bitmap
            val tempUri = slots[index1].uri
            val tempScale = slots[index1].scale
            val tempRot = slots[index1].rotationDegrees

            slots[index1].bitmap = slots[index2].bitmap
            slots[index1].uri = slots[index2].uri
            slots[index1].scale = slots[index2].scale
            slots[index1].rotationDegrees = slots[index2].rotationDegrees

            slots[index2].bitmap = tempBmp
            slots[index2].uri = tempUri
            slots[index2].scale = tempScale
            slots[index2].rotationDegrees = tempRot

            updateSlotMatrix(slots[index1])
            updateSlotMatrix(slots[index2])
            selectedSlotIndex = index2
            invalidate()
        }
    }

    fun replaceSelectedSlot(uri: Uri, bitmap: Bitmap) {
        if (selectedSlotIndex in slots.indices) {
            val slot = slots[selectedSlotIndex]
            slot.uri = uri
            slot.bitmap = bitmap
            slot.scale = 1f
            slot.transX = 0f
            slot.transY = 0f
            slot.rotationDegrees = 0f
            updateSlotMatrix(slot)
            invalidate()
        }
    }

    private fun syncSlotsWithTemplate() {
        val requiredCount = activeTemplate.slots.size
        while (slots.size < requiredCount) {
            slots.add(SlotState())
        }
        while (slots.size > requiredCount) {
            slots.removeAt(slots.lastIndex)
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
        val availableHeight = MeasureSpec.getSize(heightMeasureSpec)

        var finalWidth = availableWidth
        var finalHeight = (finalWidth / aspectRatio).toInt()

        if (finalHeight > availableHeight && availableHeight > 0) {
            finalHeight = availableHeight
            finalWidth = (finalHeight * aspectRatio).toInt()
        }

        setMeasuredDimension(finalWidth, finalHeight)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        canvasBounds.set(0f, 0f, w.toFloat(), h.toFloat())
        recalculateLayout()
    }

    private fun recalculateLayout() {
        if (canvasBounds.width() <= 0f || canvasBounds.height() <= 0f) return

        val w = canvasBounds.width()
        val h = canvasBounds.height()

        val usableLeft = outerMargin
        val usableTop = outerMargin
        val usableRight = w - outerMargin
        val usableBottom = h - outerMargin

        val usableWidth = max(0f, usableRight - usableLeft)
        val usableHeight = max(0f, usableBottom - usableTop)

        val halfSpacing = spacing / 2f

        for (i in activeTemplate.slots.indices) {
            if (i >= slots.size) break
            val normalized = activeTemplate.slots[i]
            val slot = slots[i]

            // Calculate pixel bounds including inner spacing
            val slotLeft = usableLeft + normalized.left * usableWidth + (if (normalized.left > 0f) halfSpacing else 0f)
            val slotTop = usableTop + normalized.top * usableHeight + (if (normalized.top > 0f) halfSpacing else 0f)
            val slotRight = usableLeft + normalized.right * usableWidth - (if (normalized.right < 1f) halfSpacing else 0f)
            val slotBottom = usableTop + normalized.bottom * usableHeight - (if (normalized.bottom < 1f) halfSpacing else 0f)

            slot.pixelRect.set(slotLeft, slotTop, slotRight, slotBottom)

            // Clip path with rounded corners
            slot.clipPath.reset()
            val cr = cornerRadius.coerceAtMost(max(0f, minOf(slot.pixelRect.width(), slot.pixelRect.height()) / 2f))
            slot.clipPath.addRoundRect(slot.pixelRect, cr, cr, Path.Direction.CW)

            updateSlotMatrix(slot)
        }
    }

    private fun updateSlotMatrix(slot: SlotState) {
        val bmp = slot.bitmap ?: return
        if (slot.pixelRect.width() <= 0f || slot.pixelRect.height() <= 0f) return

        val matrix = slot.matrix
        matrix.reset()

        // Center Crop base scaling
        val bw = bmp.width.toFloat()
        val bh = bmp.height.toFloat()
        val sw = slot.pixelRect.width()
        val sh = slot.pixelRect.height()

        // Handle rotation in base dimensions
        val isSideways = (slot.rotationDegrees % 180f) != 0f
        val effectiveBw = if (isSideways) bh else bw
        val effectiveBh = if (isSideways) bw else bh

        val baseScale = max(sw / effectiveBw, sh / effectiveBh)
        val finalScale = baseScale * slot.scale

        // Translate to slot center
        val centerX = slot.pixelRect.centerX()
        val centerY = slot.pixelRect.centerY()

        matrix.postTranslate(-bw / 2f, -bh / 2f)
        matrix.postRotate(slot.rotationDegrees)
        matrix.postScale(finalScale, finalScale)
        matrix.postTranslate(centerX + slot.transX, centerY + slot.transY)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw Canvas Background
        bgPaint.color = collageBackgroundColor
        canvas.drawRect(canvasBounds, bgPaint)

        // Draw Each Slot
        for (i in slots.indices) {
            val slot = slots[i]
            canvas.save()
            canvas.clipPath(slot.clipPath)

            val bmp = slot.bitmap
            if (bmp != null && !bmp.isRecycled) {
                canvas.drawBitmap(bmp, slot.matrix, bitmapPaint)
            } else {
                // Placeholder if empty
                bgPaint.color = Color.parseColor("#2A2F36")
                canvas.drawRect(slot.pixelRect, bgPaint)
            }

            canvas.restore()

            // Highlight selected slot
            if (i == selectedSlotIndex) {
                val cr = cornerRadius.coerceAtMost(minOf(slot.pixelRect.width(), slot.pixelRect.height()) / 2f)
                canvas.drawRoundRect(slot.pixelRect, cr, cr, selectionPaint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                lastTouchY = event.y
                isDragging = selectedSlotIndex in slots.indices && slots[selectedSlotIndex].pixelRect.contains(event.x, event.y)
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging && !scaleDetector.isInProgress && selectedSlotIndex in slots.indices) {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    val slot = slots[selectedSlotIndex]
                    slot.transX += dx
                    slot.transY += dy
                    updateSlotMatrix(slot)
                    invalidate()
                }
                lastTouchX = event.x
                lastTouchY = event.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
            }
        }
        return true
    }

    fun exportBitmap(targetWidth: Int = 2048): Bitmap {
        val targetHeight = (targetWidth / aspectRatio).toInt()
        val exportBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val exportCanvas = Canvas(exportBitmap)

        val scaleFactor = targetWidth.toFloat() / canvasBounds.width()

        // Background
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = collageBackgroundColor }
        exportCanvas.drawRect(0f, 0f, targetWidth.toFloat(), targetHeight.toFloat(), paint)

        val halfSpacing = (spacing * scaleFactor) / 2f
        val usableLeft = outerMargin * scaleFactor
        val usableTop = outerMargin * scaleFactor
        val usableRight = targetWidth - (outerMargin * scaleFactor)
        val usableBottom = targetHeight - (outerMargin * scaleFactor)
        val usableWidth = max(0f, usableRight - usableLeft)
        val usableHeight = max(0f, usableBottom - usableTop)

        val exportPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        for (i in activeTemplate.slots.indices) {
            if (i >= slots.size) break
            val normalized = activeTemplate.slots[i]
            val slot = slots[i]
            val bmp = slot.bitmap ?: continue
            if (bmp.isRecycled) continue

            val slotLeft = usableLeft + normalized.left * usableWidth + (if (normalized.left > 0f) halfSpacing else 0f)
            val slotTop = usableTop + normalized.top * usableHeight + (if (normalized.top > 0f) halfSpacing else 0f)
            val slotRight = usableLeft + normalized.right * usableWidth - (if (normalized.right < 1f) halfSpacing else 0f)
            val slotBottom = usableTop + normalized.bottom * usableHeight - (if (normalized.bottom < 1f) halfSpacing else 0f)

            val pixelRect = RectF(slotLeft, slotTop, slotRight, slotBottom)
            val clipPath = Path()
            val cr = (cornerRadius * scaleFactor).coerceAtMost(minOf(pixelRect.width(), pixelRect.height()) / 2f)
            clipPath.addRoundRect(pixelRect, cr, cr, Path.Direction.CW)

            // Compute matrix for export scale
            val matrix = Matrix()
            val bw = bmp.width.toFloat()
            val bh = bmp.height.toFloat()
            val isSideways = (slot.rotationDegrees % 180f) != 0f
            val effectiveBw = if (isSideways) bh else bw
            val effectiveBh = if (isSideways) bw else bh

            val baseScale = max(pixelRect.width() / effectiveBw, pixelRect.height() / effectiveBh)
            val finalScale = baseScale * slot.scale

            matrix.postTranslate(-bw / 2f, -bh / 2f)
            matrix.postRotate(slot.rotationDegrees)
            matrix.postScale(finalScale, finalScale)
            matrix.postTranslate(pixelRect.centerX() + (slot.transX * scaleFactor), pixelRect.centerY() + (slot.transY * scaleFactor))

            exportCanvas.save()
            exportCanvas.clipPath(clipPath)
            exportCanvas.drawBitmap(bmp, matrix, exportPaint)
            exportCanvas.restore()
        }

        return exportBitmap
    }
}
