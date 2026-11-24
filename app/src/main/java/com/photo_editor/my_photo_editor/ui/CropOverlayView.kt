package com.photo_editor.my_photo_editor.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.photo_editor.my_photo_editor.R

class CropOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#99000000")
        style = Paint.Style.FILL
    }

    private var cropRect = RectF()
    private var imageRect = RectF()
    private var lastX = 0f
    private var lastY = 0f
    private var activeCorner = -1 // 0: TL, 1: TR, 2: BR, 3: BL, 4: Center

    private val cornerSize = 60f
    private val touchThreshold = 80f

    var originalRatio: Float? = null

    var aspectRatio: Float? = null
        set(value) {
            field = value
            if (value != null) {
                applyAspectRatio(value)
            } else {
                originalRatio?.let { applyAspectRatio(it) } ?: resetCropRect()
            }
        }

    init {
        post {
            resetCropRect()
        }
    }

    fun resetCropRect() {
        if (width == 0 || height == 0) {
            if (isAttachedToWindow) {
                post { resetCropRect() }
            }
            return
        }
        
        if (imageRect.isEmpty) {
            imageRect.set(0f, 0f, width.toFloat(), height.toFloat())
        }

        val padding = 40f
        cropRect.set(
            imageRect.left + padding,
            imageRect.top + padding,
            imageRect.right - padding,
            imageRect.bottom - padding
        )
        invalidate()
    }

    fun setImageRect(rect: RectF) {
        // Same bounds as now (e.g. a re-sync after layout): keep the user's crop as it is.
        if (rect == imageRect && !cropRect.isEmpty) return
        imageRect.set(rect)
        resetCropRect()
    }

    private fun applyAspectRatio(ratio: Float) {
        if (width == 0 || height == 0) {
            post { applyAspectRatio(ratio) }
            return
        }

        if (imageRect.isEmpty) {
            imageRect.set(0f, 0f, width.toFloat(), height.toFloat())
        }

        val padding = 40f
        val availableWidth = imageRect.width() - (padding * 2)
        val availableHeight = imageRect.height() - (padding * 2)
        val centerX = imageRect.centerX()
        val centerY = imageRect.centerY()

        var newWidth = availableWidth
        var newHeight = availableWidth / ratio

        if (newHeight > availableHeight) {
            newHeight = availableHeight
            newWidth = newHeight * ratio
        }

        cropRect.set(
            centerX - newWidth / 2,
            centerY - newHeight / 2,
            centerX + newWidth / 2,
            centerY + newHeight / 2
        )
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                activeCorner = getTouchedCorner(x, y)
                lastX = x
                lastY = y
                return activeCorner != -1
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeCorner != -1) {
                    val dx = x - lastX
                    val dy = y - lastY
                    updateCropRect(dx, dy)
                    lastX = x
                    lastY = y
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                activeCorner = -1
            }
        }
        return true
    }

    private fun getTouchedCorner(x: Float, y: Float): Int {
        if (isNear(x, y, cropRect.left, cropRect.top)) return 0
        if (isNear(x, y, cropRect.right, cropRect.top)) return 1
        if (isNear(x, y, cropRect.right, cropRect.bottom)) return 2
        if (isNear(x, y, cropRect.left, cropRect.bottom)) return 3
        if (cropRect.contains(x, y)) return 4
        return -1
    }

    private fun isNear(x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
        return Math.sqrt(((x1 - x2) * (x1 - x2) + (y1 - y2) * (y1 - y2)).toDouble()) < touchThreshold
    }

    private fun updateCropRect(dx: Float, dy: Float) {
        val minSize = 100f
        val bounds = if (imageRect.isEmpty) RectF(0f, 0f, width.toFloat(), height.toFloat()) else imageRect

        when (activeCorner) {
            0 -> { // TL
                cropRect.left = (cropRect.left + dx).coerceIn(bounds.left, (cropRect.right - minSize).coerceAtLeast(bounds.left))
                cropRect.top = (cropRect.top + dy).coerceIn(bounds.top, (cropRect.bottom - minSize).coerceAtLeast(bounds.top))
            }
            1 -> { // TR
                cropRect.right = (cropRect.right + dx).coerceIn((cropRect.left + minSize).coerceAtMost(bounds.right), bounds.right)
                cropRect.top = (cropRect.top + dy).coerceIn(bounds.top, (cropRect.bottom - minSize).coerceAtLeast(bounds.top))
            }
            2 -> { // BR
                cropRect.right = (cropRect.right + dx).coerceIn((cropRect.left + minSize).coerceAtMost(bounds.right), bounds.right)
                cropRect.bottom = (cropRect.bottom + dy).coerceIn((cropRect.top + minSize).coerceAtMost(bounds.bottom), bounds.bottom)
            }
            3 -> { // BL
                cropRect.left = (cropRect.left + dx).coerceIn(bounds.left, (cropRect.right - minSize).coerceAtLeast(bounds.left))
                cropRect.bottom = (cropRect.bottom + dy).coerceIn((cropRect.top + minSize).coerceAtMost(bounds.bottom), bounds.bottom)
            }
            4 -> { // Move
                var w = cropRect.width()
                var h = cropRect.height()

                if (w > bounds.width()) w = bounds.width()
                if (h > bounds.height()) h = bounds.height()

                val maxLeft = (bounds.right - w).coerceAtLeast(bounds.left)
                val maxTop = (bounds.bottom - h).coerceAtLeast(bounds.top)

                cropRect.left = (cropRect.left + dx).coerceIn(bounds.left, maxLeft)
                cropRect.top = (cropRect.top + dy).coerceIn(bounds.top, maxTop)
                cropRect.right = cropRect.left + w
                cropRect.bottom = cropRect.top + h
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw overlay
        val path = Path().apply {
            addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
            addRect(cropRect, Path.Direction.CCW)
        }
        canvas.drawPath(path, overlayPaint)

        // Draw rectangle
        canvas.drawRect(cropRect, paint)

        // Draw corners (L-shaped)
        val l = cornerSize
        // TL
        canvas.drawRect(cropRect.left - 4, cropRect.top - 4, cropRect.left + l, cropRect.top + 12, cornerPaint)
        canvas.drawRect(cropRect.left - 4, cropRect.top - 4, cropRect.left + 12, cropRect.top + l, cornerPaint)
        // TR
        canvas.drawRect(cropRect.right - l, cropRect.top - 4, cropRect.right + 4, cropRect.top + 12, cornerPaint)
        canvas.drawRect(cropRect.right - 12, cropRect.top - 4, cropRect.right + 4, cropRect.top + l, cornerPaint)
        // BR
        canvas.drawRect(cropRect.right - l, cropRect.bottom - 12, cropRect.right + 4, cropRect.bottom + 4, cornerPaint)
        canvas.drawRect(cropRect.right - 12, cropRect.bottom - l, cropRect.right + 4, cropRect.bottom + 4, cornerPaint)
        // BL
        canvas.drawRect(cropRect.left - 4, cropRect.bottom - 12, cropRect.left + l, cropRect.bottom + 4, cornerPaint)
        canvas.drawRect(cropRect.left - 4, cropRect.bottom - l, cropRect.left + 12, cropRect.bottom + 4, cornerPaint)

        // Draw grid lines (rule of thirds)
        val thirdW = cropRect.width() / 3
        val thirdH = cropRect.height() / 3
        paint.strokeWidth = 1f
        paint.alpha = 100
        canvas.drawLine(cropRect.left + thirdW, cropRect.top, cropRect.left + thirdW, cropRect.bottom, paint)
        canvas.drawLine(cropRect.left + 2 * thirdW, cropRect.top, cropRect.left + 2 * thirdW, cropRect.bottom, paint)
        canvas.drawLine(cropRect.left, cropRect.top + thirdH, cropRect.right, cropRect.top + thirdH, paint)
        canvas.drawLine(cropRect.left, cropRect.top + 2 * thirdH, cropRect.right, cropRect.top + 2 * thirdH, paint)
        paint.alpha = 255
        paint.strokeWidth = 4f
    }
    
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            if (oldw == 0 || oldh == 0) {
                resetCropRect()
            } else {
                // Ensure crop rect is within new bounds
                val newRect = RectF(cropRect)
                newRect.left = newRect.left.coerceIn(0f, w.toFloat())
                newRect.top = newRect.top.coerceIn(0f, h.toFloat())
                newRect.right = newRect.right.coerceIn(newRect.left, w.toFloat())
                newRect.bottom = newRect.bottom.coerceIn(newRect.top, h.toFloat())

                // If it became too small due to shrinking, reset it or clamp to min
                if (newRect.width() < 100f || newRect.height() < 100f) {
                    resetCropRect()
                } else {
                    cropRect.set(newRect)
                }
            }
        }
    }

    fun getNormalizedCropRect(): RectF {
        val bounds = if (imageRect.isEmpty) RectF(0f, 0f, width.toFloat(), height.toFloat()) else imageRect
        return RectF(
            (cropRect.left - bounds.left) / bounds.width(),
            (cropRect.top - bounds.top) / bounds.height(),
            (cropRect.right - bounds.left) / bounds.width(),
            (cropRect.bottom - bounds.top) / bounds.height()
        )
    }
}
