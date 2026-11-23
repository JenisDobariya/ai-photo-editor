package com.photo_editor.my_photo_editor.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

class DrawingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class BrushType { PEN, PENCIL, HIGHLIGHTER, FOUNTAIN, ERASER }

    private class DrawPath(val path: Path, val paint: Paint)

    private val paths = mutableListOf<DrawPath>()
    private val undonePaths = mutableListOf<DrawPath>()
    
    private var currentPath = Path()
    private var currentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 14f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    var brushType: BrushType = BrushType.PEN
        set(value) {
            field = value
            updatePaintForBrush()
        }

    var brushColor: Int = Color.RED
        set(value) {
            field = value
            updatePaintForBrush()
        }

    init {
        isClickable = true
        isFocusable = true
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        updatePaintForBrush()
    }

    private fun updatePaintForBrush() {
        val a = Color.alpha(brushColor)
        currentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            when (brushType) {
                BrushType.PEN -> {
                    color = brushColor
                    strokeWidth = 14f
                    alpha = a
                    xfermode = null
                }
                BrushType.PENCIL -> {
                    color = brushColor
                    strokeWidth = 6f
                    alpha = (200 * a) / 255
                    xfermode = null
                }
                BrushType.HIGHLIGHTER -> {
                    color = brushColor
                    strokeWidth = 40f
                    alpha = (100 * a) / 255
                    xfermode = null
                }
                BrushType.FOUNTAIN -> {
                    color = brushColor
                    strokeWidth = 20f
                    alpha = a
                    xfermode = null
                }
                BrushType.ERASER -> {
                    color = Color.TRANSPARENT
                    strokeWidth = 48f
                    alpha = 255
                    xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                }
            }
        }
    }

    fun undo() {
        if (paths.isNotEmpty()) {
            undonePaths.add(paths.removeAt(paths.size - 1))
            invalidate()
        }
    }

    fun clear() {
        paths.clear()
        undonePaths.clear()
        invalidate()
    }

    fun hasDrawings(): Boolean = paths.isNotEmpty()

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || visibility != VISIBLE) return super.onTouchEvent(event)
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                performClick()
                parent?.requestDisallowInterceptTouchEvent(true)
                currentPath = Path().apply {
                    moveTo(x, y)
                }
                paths.add(DrawPath(currentPath, Paint(currentPaint)))
                undonePaths.clear()
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                currentPath.lineTo(x, y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                currentPath.lineTo(x, y)
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                return true
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawStrokes(canvas)
    }

    /**
     * Strokes are drawn in their own layer so the eraser (CLEAR) only removes strokes;
     * without it the eraser punched through to the photo and saved black holes.
     */
    private fun drawStrokes(canvas: Canvas) {
        if (paths.isEmpty()) return
        val layer = canvas.saveLayer(null, null)
        for (p in paths) {
            canvas.drawPath(p.path, p.paint)
        }
        canvas.restoreToCount(layer)
    }

    /** Draws the strokes in this view's coordinates; the caller maps them onto the photo. */
    fun drawOntoCanvas(canvas: Canvas) {
        if (paths.isEmpty() || width <= 0 || height <= 0) return
        drawStrokes(canvas)
    }
}
