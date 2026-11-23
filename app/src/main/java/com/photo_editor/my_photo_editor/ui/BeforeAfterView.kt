package com.photo_editor.my_photo_editor.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class BeforeAfterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Mode {
        SLIDER,       // Interactive draggable wipe slider
        SIDE_BY_SIDE, // Left: Before, Right: After
        TOP_BOTTOM,   // Top: Before, Bottom: After
        DIAGONAL      // Diagonal split
    }

    enum class BadgeStyle {
        DARK_PILL,   // Semi-transparent dark pill with white text
        GOLD_ACCENT, // Lumina Gold pill with dark text
        MINIMAL,     // Clean text with shadow
        NONE         // Hidden
    }

    var beforeBitmap: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }

    var afterBitmap: Bitmap? = null
        set(value) {
            field = value
            invalidate()
        }

    var mode: Mode = Mode.SLIDER
        set(value) {
            field = value
            invalidate()
        }

    var sliderPosition: Float = 0.5f // 0f to 1f
        set(value) {
            field = value.coerceIn(0.05f, 0.95f)
            invalidate()
        }

    var aspectRatio: Float? = null // null = fit original, 1f = 1:1, 0.8f = 4:5, 9/16, etc.
        set(value) {
            field = value
            requestLayout()
            invalidate()
        }

    var dividerThicknessDp: Float = 3f
        set(value) {
            field = value
            invalidate()
        }

    var dividerColor: Int = Color.parseColor("#3D8BFD") // app blue default
        set(value) {
            field = value
            invalidate()
        }

    var cornerRadiusDp: Float = 16f
        set(value) {
            field = value
            invalidate()
        }

    var badgeStyle: BadgeStyle = BadgeStyle.DARK_PILL
        set(value) {
            field = value
            invalidate()
        }

    var beforeLabel: String = "BEFORE"
        set(value) {
            field = value
            invalidate()
        }

    var afterLabel: String = "AFTER"
        set(value) {
            field = value
            invalidate()
        }

    var showHandle: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    var onSliderMoveListener: ((Float) -> Unit)? = null

    // Drawing resources
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
    }
    private val badgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val canvasRect = RectF()
    private val clipPath = Path()
    private val splitPath = Path()

    private var isDraggingSlider = false

    private val density = resources.displayMetrics.density

    fun swapBitmaps() {
        val temp = beforeBitmap
        beforeBitmap = afterBitmap
        afterBitmap = temp
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)

        val targetRatio = aspectRatio ?: run {
            val bm = beforeBitmap ?: afterBitmap
            if (bm != null && bm.height > 0) bm.width.toFloat() / bm.height.toFloat() else 1.0f
        }

        var viewWidth = width
        var viewHeight = (width / targetRatio).roundToInt()

        if (viewHeight > height) {
            viewHeight = height
            viewWidth = (height * targetRatio).roundToInt()
        }

        setMeasuredDimension(viewWidth, viewHeight)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (mode != Mode.SLIDER) return super.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (canvasRect.contains(event.x, event.y)) {
                    isDraggingSlider = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    updateSliderFromTouch(event.x)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDraggingSlider) {
                    updateSliderFromTouch(event.x)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isDraggingSlider) {
                    isDraggingSlider = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateSliderFromTouch(touchX: Float) {
        if (canvasRect.width() > 0) {
            val pos = ((touchX - canvasRect.left) / canvasRect.width()).coerceIn(0.05f, 0.95f)
            sliderPosition = pos
            onSliderMoveListener?.invoke(pos)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvasRect.set(0f, 0f, width.toFloat(), height.toFloat())
        if (canvasRect.width() <= 0 || canvasRect.height() <= 0) return

        drawComparison(canvas, canvasRect, 1.0f)
    }

    fun drawComparison(canvas: Canvas, rect: RectF, scaleFactor: Float = 1.0f) {
        val cornerRadius = cornerRadiusDp * density * scaleFactor
        val dividerThickness = dividerThicknessDp * density * scaleFactor

        // Rounded clipping path for the whole card
        clipPath.reset()
        clipPath.addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW)

        val saveCount = canvas.save()
        canvas.clipPath(clipPath)

        val bBefore = beforeBitmap
        val bAfter = afterBitmap

        when (mode) {
            Mode.SLIDER -> {
                val splitX = rect.left + rect.width() * sliderPosition

                // 1. Draw Before image on left slice
                if (bBefore != null) {
                    val saveBefore = canvas.save()
                    canvas.clipRect(rect.left, rect.top, splitX, rect.bottom)
                    drawBitmapCenterCrop(canvas, bBefore, rect)
                    canvas.restoreToCount(saveBefore)
                }

                // 2. Draw After image on right slice
                if (bAfter != null) {
                    val saveAfter = canvas.save()
                    canvas.clipRect(splitX, rect.top, rect.right, rect.bottom)
                    drawBitmapCenterCrop(canvas, bAfter, rect)
                    canvas.restoreToCount(saveAfter)
                }

                // 3. Divider Line
                if (dividerThickness > 0f) {
                    linePaint.strokeWidth = dividerThickness
                    linePaint.color = dividerColor
                    canvas.drawLine(splitX, rect.top, splitX, rect.bottom, linePaint)
                }

                // 4. Circular Slider Handle
                if (showHandle) {
                    val handleRadius = 18f * density * scaleFactor
                    val handleCenterY = rect.centerY()

                    // Handle Outer Glow / Shadow Ring
                    handlePaint.color = Color.parseColor("#44000000")
                    canvas.drawCircle(splitX, handleCenterY, handleRadius + (2f * density * scaleFactor), handlePaint)

                    // Handle Body
                    handlePaint.color = dividerColor
                    canvas.drawCircle(splitX, handleCenterY, handleRadius, handlePaint)

                    // White Border
                    handleStrokePaint.strokeWidth = 2f * density * scaleFactor
                    canvas.drawCircle(splitX, handleCenterY, handleRadius, handleStrokePaint)

                    // Inner Arrows ◀ ▶
                    val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = if (dividerColor == Color.WHITE) Color.BLACK else Color.WHITE
                        textSize = spToPx(10f, scaleFactor)
                        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                        textAlign = Paint.Align.CENTER
                    }
                    val textY = handleCenterY - ((arrowPaint.descent() + arrowPaint.ascent()) / 2f)
                    canvas.drawText("◀ ▶", splitX, textY, arrowPaint)
                }
            }

            Mode.SIDE_BY_SIDE -> {
                val halfThickness = dividerThickness / 2f
                val splitX = rect.centerX()
                val leftRect = RectF(rect.left, rect.top, splitX - halfThickness, rect.bottom)
                val rightRect = RectF(splitX + halfThickness, rect.top, rect.right, rect.bottom)

                if (bBefore != null) {
                    drawBitmapCenterCrop(canvas, bBefore, leftRect)
                }
                if (bAfter != null) {
                    drawBitmapCenterCrop(canvas, bAfter, rightRect)
                }

                if (dividerThickness > 0f) {
                    linePaint.strokeWidth = dividerThickness
                    linePaint.color = dividerColor
                    canvas.drawLine(splitX, rect.top, splitX, rect.bottom, linePaint)
                }
            }

            Mode.TOP_BOTTOM -> {
                val halfThickness = dividerThickness / 2f
                val splitY = rect.centerY()
                val topRect = RectF(rect.left, rect.top, rect.right, splitY - halfThickness)
                val bottomRect = RectF(rect.left, splitY + halfThickness, rect.right, rect.bottom)

                if (bBefore != null) {
                    drawBitmapCenterCrop(canvas, bBefore, topRect)
                }
                if (bAfter != null) {
                    drawBitmapCenterCrop(canvas, bAfter, bottomRect)
                }

                if (dividerThickness > 0f) {
                    linePaint.strokeWidth = dividerThickness
                    linePaint.color = dividerColor
                    canvas.drawLine(rect.left, splitY, rect.right, splitY, linePaint)
                }
            }

            Mode.DIAGONAL -> {
                splitPath.reset()
                splitPath.moveTo(rect.left, rect.top)
                splitPath.lineTo(rect.right, rect.bottom)
                splitPath.lineTo(rect.left, rect.bottom)
                splitPath.close()

                if (bBefore != null) {
                    val s1 = canvas.save()
                    canvas.clipPath(splitPath)
                    drawBitmapCenterCrop(canvas, bBefore, rect)
                    canvas.restoreToCount(s1)
                }

                splitPath.reset()
                splitPath.moveTo(rect.left, rect.top)
                splitPath.lineTo(rect.right, rect.top)
                splitPath.lineTo(rect.right, rect.bottom)
                splitPath.close()

                if (bAfter != null) {
                    val s2 = canvas.save()
                    canvas.clipPath(splitPath)
                    drawBitmapCenterCrop(canvas, bAfter, rect)
                    canvas.restoreToCount(s2)
                }

                if (dividerThickness > 0f) {
                    linePaint.strokeWidth = dividerThickness
                    linePaint.color = dividerColor
                    canvas.drawLine(rect.left, rect.top, rect.right, rect.bottom, linePaint)
                }
            }
        }

        // Draw Badges
        drawBadges(canvas, rect, scaleFactor)

        canvas.restoreToCount(saveCount)
    }

    private fun drawBitmapCenterCrop(canvas: Canvas, bitmap: Bitmap, dstRect: RectF) {
        val bWidth = bitmap.width.toFloat()
        val bHeight = bitmap.height.toFloat()
        val dWidth = dstRect.width()
        val dHeight = dstRect.height()

        if (bWidth <= 0 || bHeight <= 0 || dWidth <= 0 || dHeight <= 0) return

        val scale = max(dWidth / bWidth, dHeight / bHeight)
        val scaledWidth = bWidth * scale
        val scaledHeight = bHeight * scale

        val left = dstRect.left + (dWidth - scaledWidth) / 2f
        val top = dstRect.top + (dHeight - scaledHeight) / 2f

        val save = canvas.save()
        canvas.clipRect(dstRect)
        val outRect = RectF(left, top, left + scaledWidth, top + scaledHeight)
        canvas.drawBitmap(bitmap, null, outRect, bitmapPaint)
        canvas.restoreToCount(save)
    }

    private fun drawBadges(canvas: Canvas, rect: RectF, scaleFactor: Float) {
        if (badgeStyle == BadgeStyle.NONE) return

        val margin = 14f * density * scaleFactor
        val textHeightSp = spToPx(11f, scaleFactor)
        textPaint.textSize = textHeightSp

        // Badge positions
        val isTopBottom = (mode == Mode.TOP_BOTTOM)
        val beforeX: Float
        val beforeY: Float
        val afterX: Float
        val afterY: Float

        if (isTopBottom) {
            beforeX = rect.left + margin
            beforeY = rect.top + margin
            afterX = rect.left + margin
            afterY = rect.centerY() + margin
        } else {
            beforeX = rect.left + margin
            beforeY = rect.top + margin
            afterX = rect.right - margin
            afterY = rect.top + margin
        }

        drawSingleBadge(canvas, beforeLabel, beforeX, beforeY, isLeft = true, scaleFactor)
        drawSingleBadge(canvas, afterLabel, afterX, afterY, isLeft = isTopBottom, scaleFactor)
    }

    private fun drawSingleBadge(
        canvas: Canvas,
        text: String,
        anchorX: Float,
        anchorY: Float,
        isLeft: Boolean,
        scaleFactor: Float
    ) {
        if (text.isEmpty()) return

        val textBounds = Rect()
        textPaint.getTextBounds(text, 0, text.length, textBounds)

        val padX = 10f * density * scaleFactor
        val padY = 5f * density * scaleFactor
        val badgeWidth = textBounds.width() + (padX * 2f)
        val badgeHeight = textBounds.height() + (padY * 2f)
        val badgeRadius = badgeHeight / 2f

        val badgeRect = if (isLeft) {
            RectF(anchorX, anchorY, anchorX + badgeWidth, anchorY + badgeHeight)
        } else {
            RectF(anchorX - badgeWidth, anchorY, anchorX, anchorY + badgeHeight)
        }

        when (badgeStyle) {
            BadgeStyle.DARK_PILL -> {
                badgeBgPaint.color = Color.parseColor("#B30F172A") // 70% dark slate
                canvas.drawRoundRect(badgeRect, badgeRadius, badgeRadius, badgeBgPaint)
                badgeStrokePaint.color = Color.parseColor("#33FFFFFF")
                badgeStrokePaint.strokeWidth = 1f * density * scaleFactor
                canvas.drawRoundRect(badgeRect, badgeRadius, badgeRadius, badgeStrokePaint)

                textPaint.color = Color.WHITE
                textPaint.setShadowLayer(0f, 0f, 0f, 0)
            }
            BadgeStyle.GOLD_ACCENT -> {
                badgeBgPaint.color = Color.parseColor("#3D8BFD") // app blue
                canvas.drawRoundRect(badgeRect, badgeRadius, badgeRadius, badgeBgPaint)

                textPaint.color = Color.BLACK
                textPaint.setShadowLayer(0f, 0f, 0f, 0)
            }
            BadgeStyle.MINIMAL -> {
                textPaint.color = Color.WHITE
                textPaint.setShadowLayer(6f * density * scaleFactor, 0f, 2f * density * scaleFactor, Color.BLACK)
            }
            BadgeStyle.NONE -> return
        }

        val textCenterX = badgeRect.centerX()
        val textCenterY = badgeRect.centerY() - ((textPaint.descent() + textPaint.ascent()) / 2f)
        canvas.drawText(text, textCenterX, textCenterY, textPaint)
    }

    private fun spToPx(sp: Float, scaleFactor: Float): Float = sp * density * scaleFactor

    fun exportBitmap(outputWidth: Int = 2048): Bitmap {
        val targetRatio = aspectRatio ?: run {
            val bm = beforeBitmap ?: afterBitmap
            if (bm != null && bm.height > 0) bm.width.toFloat() / bm.height.toFloat() else 1.0f
        }

        val outputHeight = (outputWidth / targetRatio).roundToInt().coerceAtLeast(100)
        val result = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)

        val exportRect = RectF(0f, 0f, outputWidth.toFloat(), outputHeight.toFloat())
        val scaleFactor = outputWidth.toFloat() / (canvasRect.width().coerceAtLeast(1f))

        drawComparison(canvas, exportRect, scaleFactor)
        return result
    }
}
