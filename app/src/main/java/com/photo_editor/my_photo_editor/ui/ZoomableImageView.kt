package com.photo_editor.my_photo_editor.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.min

/**
 * ImageView with pinch-to-zoom, double-tap zoom and panning.
 * While zoomed it keeps touch events away from a parent ViewPager2 until the image
 * edge is reached, so swiping to the next photo still feels natural.
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    /** Called on a confirmed single tap (used to toggle the viewer chrome). */
    var onSingleTap: (() -> Unit)? = null

    /** Called whenever the zoom level crosses the "zoomed" threshold. */
    var onZoomChanged: ((zoomed: Boolean) -> Unit)? = null

    private val drawMatrix = Matrix()
    private val baseMatrix = Matrix()
    private val rect = RectF()
    private var currentScale = 1f
    private var animator: ValueAnimator? = null
    private var wasZoomed = false
    private var lastTouchX = 0f

    private val minScale = 1f
    private val maxScale = 6f
    private val doubleTapScale = 2.5f

    val isZoomed: Boolean get() = currentScale > 1.02f

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val target = (currentScale * detector.scaleFactor).coerceIn(minScale * 0.85f, maxScale)
            val factor = target / currentScale
            drawMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
            currentScale = target
            fixTranslation()
            applyMatrix()
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            if (currentScale < minScale) animateZoomTo(minScale, width / 2f, height / 2f)
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onSingleTap?.invoke()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (isZoomed) animateZoomTo(minScale, e.x, e.y)
            else animateZoomTo(doubleTapScale, e.x, e.y)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (!isZoomed) return false
            drawMatrix.postTranslate(-distanceX, -distanceY)
            fixTranslation()
            applyMatrix()
            return true
        }
    })

    init {
        scaleType = ScaleType.MATRIX
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        resetZoom()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetZoom()
    }

    fun resetZoom() {
        animator?.cancel()
        val d = drawable
        if (d == null || width == 0 || height == 0 || d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0) {
            baseMatrix.reset()
            drawMatrix.reset()
            currentScale = 1f
            imageMatrix = drawMatrix
            notifyZoom()
            return
        }
        val dw = d.intrinsicWidth.toFloat()
        val dh = d.intrinsicHeight.toFloat()
        val scale = min(width / dw, height / dh)
        baseMatrix.reset()
        baseMatrix.postScale(scale, scale)
        baseMatrix.postTranslate((width - dw * scale) / 2f, (height - dh * scale) / 2f)
        drawMatrix.set(baseMatrix)
        currentScale = 1f
        applyMatrix()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        // Decide whether the parent pager may steal the gesture.
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastTouchX = event.x
                parent?.requestDisallowInterceptTouchEvent(isZoomed)
            }
            MotionEvent.ACTION_POINTER_DOWN -> parent?.requestDisallowInterceptTouchEvent(true)
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastTouchX
                lastTouchX = event.x
                if (event.pointerCount > 1 || scaleDetector.isInProgress) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                } else if (isZoomed && dx != 0f) {
                    // Finger moving right reveals the left side; only hand over to the
                    // pager when there is nothing left to pan in that direction.
                    val r = displayRect()
                    val atEdge = if (dx > 0) r.left >= -1f else r.right <= width + 1f
                    parent?.requestDisallowInterceptTouchEvent(!atEdge)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    override fun canScrollHorizontally(direction: Int): Boolean {
        if (!isZoomed) return false
        val r = displayRect()
        return if (direction > 0) r.right > width + 1f else r.left < -1f
    }

    private fun displayRect(): RectF {
        val d = drawable ?: return rect.apply { setEmpty() }
        rect.set(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        drawMatrix.mapRect(rect)
        return rect
    }

    private fun fixTranslation() {
        val r = displayRect()
        if (r.isEmpty) return
        val dx = when {
            r.width() <= width -> (width - r.width()) / 2f - r.left
            r.left > 0 -> -r.left
            r.right < width -> width - r.right
            else -> 0f
        }
        val dy = when {
            r.height() <= height -> (height - r.height()) / 2f - r.top
            r.top > 0 -> -r.top
            r.bottom < height -> height - r.bottom
            else -> 0f
        }
        drawMatrix.postTranslate(dx, dy)
    }

    private fun animateZoomTo(targetScale: Float, focusX: Float, focusY: Float) {
        animator?.cancel()
        val start = currentScale
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val fraction = anim.animatedValue as Float
                val next = start + (targetScale - start) * fraction
                val factor = next / currentScale
                drawMatrix.postScale(factor, factor, focusX, focusY)
                currentScale = next
                fixTranslation()
                applyMatrix()
            }
            start()
        }
    }

    private fun applyMatrix() {
        imageMatrix = drawMatrix
        invalidate()
        notifyZoom()
    }

    private fun notifyZoom() {
        val zoomed = isZoomed
        if (zoomed != wasZoomed) {
            wasZoomed = zoomed
            onZoomChanged?.invoke(zoomed)
        }
    }
}
