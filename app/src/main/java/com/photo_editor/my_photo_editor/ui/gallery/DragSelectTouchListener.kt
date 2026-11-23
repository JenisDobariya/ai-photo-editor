package com.photo_editor.my_photo_editor.ui.gallery

import android.view.MotionEvent
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.max
import kotlin.math.min

/**
 * Google Photos style "long-press then drag" range selection with edge auto-scroll.
 * Call [startDragSelection] from an item's long-click handler.
 */
class DragSelectTouchListener(
    private val onRangeChanged: (start: Int, end: Int) -> Unit
) : RecyclerView.OnItemTouchListener {

    private var active = false
    private var startPosition = RecyclerView.NO_POSITION
    private var lastPosition = RecyclerView.NO_POSITION
    private var recyclerView: RecyclerView? = null
    private var lastX = 0f
    private var lastY = 0f
    private var scrollSpeed = 0

    private val autoScroll = object : Runnable {
        override fun run() {
            val rv = recyclerView ?: return
            if (!active || scrollSpeed == 0) return
            rv.scrollBy(0, scrollSpeed)
            updateSelection(rv, lastX, lastY)
            rv.postOnAnimation(this)
        }
    }

    fun attach(rv: RecyclerView) {
        recyclerView = rv
        rv.addOnItemTouchListener(this)
    }

    fun startDragSelection(position: Int) {
        if (position == RecyclerView.NO_POSITION) return
        active = true
        startPosition = position
        lastPosition = position
        recyclerView?.parent?.requestDisallowInterceptTouchEvent(true)
        onRangeChanged(position, position)
    }

    override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
        recyclerView = rv
        if (active && (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL)) {
            stop()
            return false
        }
        return active
    }

    override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
        if (!active) return
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                lastX = e.x
                lastY = e.y
                updateSelection(rv, e.x, e.y)
                updateAutoScroll(rv, e.y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stop()
        }
    }

    override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}

    private fun updateSelection(rv: RecyclerView, x: Float, y: Float) {
        val child = rv.findChildViewUnder(x, y) ?: return
        val position = rv.getChildAdapterPosition(child)
        if (position == RecyclerView.NO_POSITION || position == lastPosition) return
        lastPosition = position
        onRangeChanged(min(startPosition, position), max(startPosition, position))
    }

    private fun updateAutoScroll(rv: RecyclerView, y: Float) {
        val hotspot = rv.height * 0.14f
        val maxSpeed = (rv.resources.displayMetrics.density * 18).toInt()
        val newSpeed = when {
            y < hotspot -> -((hotspot - y) / hotspot * maxSpeed).toInt().coerceAtLeast(1)
            y > rv.height - hotspot -> ((y - (rv.height - hotspot)) / hotspot * maxSpeed).toInt().coerceAtLeast(1)
            else -> 0
        }
        val wasIdle = scrollSpeed == 0
        scrollSpeed = newSpeed
        if (wasIdle && newSpeed != 0) rv.postOnAnimation(autoScroll)
    }

    private fun stop() {
        active = false
        scrollSpeed = 0
        recyclerView?.removeCallbacks(autoScroll)
        startPosition = RecyclerView.NO_POSITION
        lastPosition = RecyclerView.NO_POSITION
    }
}
