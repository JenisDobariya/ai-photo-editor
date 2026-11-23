package com.photo_editor.my_photo_editor.ui.gallery

import android.content.Context
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.recyclerview.widget.RecyclerView

/** Pinch in/out on the grid to change the number of columns (like Google Photos). */
class PinchToZoomGridListener(
    context: Context,
    private val onColumnDelta: (delta: Int) -> Unit
) : RecyclerView.OnItemTouchListener {

    private var scaling = false
    private var accumulated = 1f

    private val detector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            scaling = true
            accumulated = 1f
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            accumulated *= detector.scaleFactor
            if (accumulated > 1.3f) {
                onColumnDelta(-1) // spread fingers = bigger thumbnails = fewer columns
                accumulated = 1f
            } else if (accumulated < 0.77f) {
                onColumnDelta(+1)
                accumulated = 1f
            }
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            scaling = false
        }
    })

    override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
        detector.onTouchEvent(e)
        if (e.pointerCount > 1) rv.parent?.requestDisallowInterceptTouchEvent(true)
        return scaling || e.pointerCount > 1
    }

    override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
        detector.onTouchEvent(e)
        if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
            scaling = false
        }
    }

    override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
}
