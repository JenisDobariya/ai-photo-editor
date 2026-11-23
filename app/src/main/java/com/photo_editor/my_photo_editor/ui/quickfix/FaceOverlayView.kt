package com.photo_editor.my_photo_editor.ui.quickfix

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.content.ContextCompat
import com.photo_editor.my_photo_editor.R
import com.photo_editor.my_photo_editor.utils.FaceBlurrer
import kotlin.math.abs
import kotlin.math.min

/**
 * Draws tappable outlines over faces of an image shown with `scaleType="fitCenter"` in a
 * view of the same size placed underneath. Tap a face to toggle it via [onFaceTap].
 */
class FaceOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var onFaceTap: ((Int) -> Unit)? = null

    private var imageW = 0
    private var imageH = 0
    private var regions: List<RectF> = emptyList()
    private var selected: List<Boolean> = emptyList()

    private val density = resources.displayMetrics.density
    private val accent = ContextCompat.getColor(context, R.color.lumina_accent)

    private val selectedStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = accent
    }
    private val selectedFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(46, Color.red(accent), Color.green(accent), Color.blue(accent))
    }
    private val unselectedStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = Color.argb(200, 255, 255, 255)
        pathEffect = DashPathEffect(floatArrayOf(6f * density, 4f * density), 0f)
    }
    private val shadowStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
        color = Color.argb(70, 0, 0, 0)
    }
    private val badgeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val badgeEmpty = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 0, 0, 0) }
    private val badgeRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = Color.WHITE
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }

    private val tmpRect = RectF()
    private val tickPath = Path()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f

    /** [faces] boxes are in image pixels of an image sized [imageWidth] x [imageHeight]. */
    fun setFaces(imageWidth: Int, imageHeight: Int, faces: List<FaceItem>) {
        val newRegions = faces.map { FaceBlurrer.expandedRegion(it.box) }
        val newSelected = faces.map { it.selected }
        if (imageWidth == imageW && imageHeight == imageH && newRegions == regions && newSelected == selected) return
        imageW = imageWidth
        imageH = imageHeight
        regions = newRegions
        selected = newSelected
        invalidate()
    }

    private fun scale(): Float {
        if (imageW <= 0 || imageH <= 0 || width == 0 || height == 0) return 0f
        return min(width.toFloat() / imageW, height.toFloat() / imageH)
    }

    private fun mapToView(src: RectF, scale: Float, out: RectF) {
        val dx = (width - imageW * scale) / 2f
        val dy = (height - imageH * scale) / 2f
        out.set(src.left * scale + dx, src.top * scale + dy, src.right * scale + dx, src.bottom * scale + dy)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val sc = scale()
        if (sc <= 0f) return
        regions.forEachIndexed { i, region ->
            mapToView(region, sc, tmpRect)
            val r = FaceBlurrer.cornerRadius(tmpRect)
            val isSel = selected.getOrElse(i) { false }
            canvas.drawRoundRect(tmpRect, r, r, shadowStroke)
            if (isSel) {
                canvas.drawRoundRect(tmpRect, r, r, selectedFill)
                canvas.drawRoundRect(tmpRect, r, r, selectedStroke)
            } else {
                canvas.drawRoundRect(tmpRect, r, r, unselectedStroke)
            }
            drawBadge(canvas, tmpRect, isSel)
        }
    }

    private fun drawBadge(canvas: Canvas, rect: RectF, isSel: Boolean) {
        val radius = 10f * density
        // On the outline, near the top-right "corner" of the rounded shape.
        val cx = (rect.right - rect.width() * 0.15f).coerceIn(radius, width - radius)
        val cy = (rect.top + rect.height() * 0.15f).coerceIn(radius, height - radius)
        canvas.drawCircle(cx, cy, radius, if (isSel) badgeFill else badgeEmpty)
        canvas.drawCircle(cx, cy, radius, badgeRing)
        if (isSel) {
            tickPath.reset()
            tickPath.moveTo(cx - radius * 0.45f, cy + radius * 0.02f)
            tickPath.lineTo(cx - radius * 0.1f, cy + radius * 0.38f)
            tickPath.lineTo(cx + radius * 0.48f, cy - radius * 0.32f)
            canvas.drawPath(tickPath, tickPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (regions.isEmpty() || !isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (abs(event.x - downX) <= touchSlop && abs(event.y - downY) <= touchSlop) {
                    val hit = hitTest(event.x, event.y)
                    if (hit >= 0) {
                        performClick()
                        onFaceTap?.invoke(hit)
                    }
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun hitTest(x: Float, y: Float): Int {
        val sc = scale()
        if (sc <= 0f) return -1
        val minHalf = 24f * density
        var best = -1
        var bestArea = Float.MAX_VALUE
        regions.forEachIndexed { i, region ->
            mapToView(region, sc, tmpRect)
            // Small faces still get a finger-sized target.
            val padX = ((minHalf * 2 - tmpRect.width()) / 2f).coerceAtLeast(0f)
            val padY = ((minHalf * 2 - tmpRect.height()) / 2f).coerceAtLeast(0f)
            val area = tmpRect.width() * tmpRect.height()
            tmpRect.inset(-padX, -padY)
            if (tmpRect.contains(x, y) && area < bestArea) {
                best = i
                bestArea = area
            }
        }
        return best
    }
}
