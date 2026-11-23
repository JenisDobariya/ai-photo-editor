package com.photo_editor.my_photo_editor.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.photo_editor.my_photo_editor.R

class FastScrollerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface OnPopupTextUpdate {
        fun onChange(position: Int): CharSequence
    }

    private var recyclerView: RecyclerView? = null

    private val scrollIconDrawable = ContextCompat.getDrawable(context, R.drawable.ic_scrolling_icon)

    private val popupBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.lumina_surface_high)
        style = Paint.Style.FILL
    }

    private val popupTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 15f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    // Auto-hide: the handle fades in while scrolling and fades out shortly after.
    private val hideRunnable = Runnable {
        if (!isDragging) animate().alpha(0f).setDuration(250).start()
    }

    private val thumbRect = RectF()
    private val popupRect = RectF()
    private var thumbWidth = 24f
    private var thumbHeight = 56f
    private var thumbTop = 0f
    private var isDragging = false
    private var currentPopupText: CharSequence = ""
    private var lastTargetPosition = -1

    private val onScrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            if (!isDragging) {
                updateThumbPosition()
                if (dy != 0) showTemporarily()
            }
        }
    }

    init {
        try {
            val density = resources?.displayMetrics?.density ?: 2f
            thumbWidth = 22f * density
            thumbHeight = 56f * density
        } catch (_: Exception) {
            thumbWidth = 44f
            thumbHeight = 112f
        }
        alpha = 0f
    }

    private fun showTemporarily() {
        removeCallbacks(hideRunnable)
        if (alpha < 1f) animate().alpha(1f).setDuration(120).start()
        postDelayed(hideRunnable, HIDE_DELAY_MS)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(hideRunnable)
        super.onDetachedFromWindow()
    }

    fun attachFastScrollerToRecyclerView(rv: RecyclerView) {
        attachToRecyclerView(rv)
    }

    fun attachToRecyclerView(rv: RecyclerView) {
        recyclerView?.removeOnScrollListener(onScrollListener)
        recyclerView = rv
        recyclerView?.addOnScrollListener(onScrollListener)
        updateThumbPosition()
    }

    fun updateThumbPosition() {
        if (height == 0) {
            post { updateThumbPosition() }
            return
        }

        val rv = recyclerView ?: run {
            visibility = GONE
            return
        }

        val adapter = rv.adapter ?: run {
            visibility = GONE
            return
        }

        if (adapter.itemCount <= 0) {
            visibility = GONE
            return
        }

        val extent = rv.computeVerticalScrollExtent()
        val range = rv.computeVerticalScrollRange()
        val offset = rv.computeVerticalScrollOffset()

        if (range <= extent) {
            visibility = GONE
            return
        } else {
            visibility = VISIBLE
        }

        val maxOffset = range - extent
        val scrollRatio = if (maxOffset > 0) offset.toFloat() / maxOffset else 0f
        val availableHeight = height - thumbHeight
        thumbTop = (scrollRatio * availableHeight).coerceIn(0f, availableHeight.coerceAtLeast(0f))
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateThumbPosition()
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val rv = recyclerView ?: return super.onTouchEvent(event)
        val adapter = rv.adapter ?: return super.onTouchEvent(event)
        if (adapter.itemCount <= 0 || visibility != VISIBLE) return false

        val availableHeight = height - thumbHeight
        if (availableHeight <= 0) return false

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // Only grab the gesture when the visible handle itself is touched; anything
                // else falls through to the grid underneath (this view is wider than the
                // handle so the date bubble has room to draw).
                val slop = thumbHeight * 0.4f
                val onHandle = alpha > 0.1f &&
                        event.x >= width - thumbWidth * 1.8f &&
                        event.y >= thumbTop - slop && event.y <= thumbTop + thumbHeight + slop
                if (!onHandle) return false
                removeCallbacks(hideRunnable)
                animate().cancel()
                alpha = 1f
                performClick()
                isDragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                handleTouchScroll(event.y, availableHeight)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    handleTouchScroll(event.y, availableHeight)
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                lastTargetPosition = -1
                parent?.requestDisallowInterceptTouchEvent(false)
                updateThumbPosition()
                postDelayed(hideRunnable, HIDE_DELAY_MS)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleTouchScroll(touchY: Float, availableHeight: Float) {
        val rv = recyclerView ?: return
        val targetTop = (touchY - thumbHeight / 2f).coerceIn(0f, availableHeight)
        thumbTop = targetTop

        val ratio = (targetTop / availableHeight).coerceIn(0f, 1f)
        val adapter = rv.adapter
        val itemCount = adapter?.itemCount ?: 0
        if (itemCount > 0) {
            val targetPosition = (ratio * (itemCount - 1)).toInt().coerceIn(0, itemCount - 1)
            currentPopupText = (adapter as? OnPopupTextUpdate)?.onChange(targetPosition) ?: ""
            if (targetPosition != lastTargetPosition) {
                lastTargetPosition = targetPosition
                rv.scrollToPosition(targetPosition)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (visibility != VISIBLE) return

        val rightMargin = 0f // Flush against right edge with zero space/padding
        val right = width.toFloat() - rightMargin
        val left = right - thumbWidth
        val top = thumbTop
        val bottom = top + thumbHeight

        thumbRect.set(left, top, right, bottom)
        
        scrollIconDrawable?.let { drawable ->
            drawable.setBounds(thumbRect.left.toInt(), thumbRect.top.toInt(), thumbRect.right.toInt(), thumbRect.bottom.toInt())
            drawable.draw(canvas)
        }

        // Draw date popup when dragging
        if (isDragging && currentPopupText.isNotEmpty()) {
            val textWidth = popupTextPaint.measureText(currentPopupText.toString())
            val density = resources.displayMetrics.density
            val popupWidth = textWidth + 32f * density
            val popupHeight = 40f * density
            val popupLeft = (left - popupWidth - 12f * density).coerceAtLeast(0f)
            val popupTop = (top + thumbHeight / 2f - popupHeight / 2f).coerceIn(0f, height.toFloat() - popupHeight)

            popupRect.set(popupLeft, popupTop, popupLeft + popupWidth, popupTop + popupHeight)
            canvas.drawRoundRect(popupRect, popupHeight / 2f, popupHeight / 2f, popupBgPaint)

            val fontMetrics = popupTextPaint.fontMetrics
            val textY = popupRect.centerY() - (fontMetrics.ascent + fontMetrics.descent) / 2f
            canvas.drawText(currentPopupText, 0, currentPopupText.length, popupRect.centerX(), textY, popupTextPaint)
        }
    }

    private companion object {
        const val HIDE_DELAY_MS = 1500L
    }
}
