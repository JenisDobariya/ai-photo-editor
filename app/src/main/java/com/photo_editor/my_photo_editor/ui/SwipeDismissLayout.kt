package com.photo_editor.my_photo_editor.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign

/**
 * Gallery-style "drag to close" for the full-screen viewer.
 *
 * A mostly vertical drag (up or down) is taken over from the pager when [Listener.canStartDismiss]
 * allows it: the current page follows the finger and shrinks while the background fades out.
 * Releasing far enough (or with a fling) closes the viewer, otherwise the page springs back.
 * Horizontal swipes, pinch zoom and panning a zoomed photo are left to the children.
 */
class SwipeDismissLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    interface Listener {
        /** Whether a drag that started at ([x], [y]) may close the viewer. */
        fun canStartDismiss(x: Float, y: Float): Boolean
        /** The view that follows the finger (the current page). */
        fun dismissTarget(): View?
        fun onDragStarted()
        /** 0 = resting, 1 = fully dragged away. */
        fun onDragProgress(fraction: Float)
        fun onDragCancelled()
        fun onDismissed()
    }

    var listener: Listener? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 4f
    private var velocityTracker: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var animating = false
    private var target: View? = null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (animating) return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                dragging = false
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().apply { addMovement(ev) }
            }
            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(ev)
                if (ev.pointerCount > 1) return false
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (abs(dy) > touchSlop && abs(dy) > abs(dx) * 1.5f &&
                    listener?.canStartDismiss(downX, downY) == true
                ) {
                    val view = listener?.dismissTarget() ?: return false
                    target = view
                    dragging = true
                    // Start from where the slop was crossed so the page doesn't jump.
                    downY += sign(dy) * touchSlop
                    parent?.requestDisallowInterceptTouchEvent(true)
                    listener?.onDragStarted()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocityTracker?.recycle()
                velocityTracker = null
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!dragging) return animating
        velocityTracker?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> applyDrag(ev.x - downX, ev.y - downY)
            MotionEvent.ACTION_POINTER_DOWN -> {} // keep following the first finger
            MotionEvent.ACTION_UP -> {
                val vt = velocityTracker
                vt?.computeCurrentVelocity(1000)
                val vy = vt?.yVelocity ?: 0f
                val dy = ev.y - downY
                val flungAway = abs(vy) > minFlingVelocity && sign(vy) == sign(dy) && abs(dy) > touchSlop
                if (abs(dy) > height * DISMISS_FRACTION || flungAway) animateOut(dy) else animateBack()
                endTracking()
            }
            MotionEvent.ACTION_CANCEL -> {
                animateBack()
                endTracking()
            }
        }
        return true
    }

    private fun endTracking() {
        dragging = false
        velocityTracker?.recycle()
        velocityTracker = null
    }

    private fun applyDrag(dx: Float, dy: Float) {
        val view = target ?: return
        val h = height.coerceAtLeast(1).toFloat()
        val progress = min(abs(dy) / (h * 0.5f), 1f)
        val scale = 1f - progress * 0.35f
        view.translationX = dx * 0.7f
        view.translationY = dy
        view.scaleX = scale
        view.scaleY = scale
        listener?.onDragProgress(progress)
    }

    private fun animateBack() {
        val view = target ?: return
        animating = true
        val startProgress = currentProgress(view)
        view.animate()
            .translationX(0f).translationY(0f).scaleX(1f).scaleY(1f)
            .setDuration(220)
            .setInterpolator(DecelerateInterpolator())
            .setUpdateListener { listener?.onDragProgress(startProgress * (1f - it.animatedFraction)) }
            .withEndAction {
                animating = false
                view.animate().setUpdateListener(null)
                listener?.onDragProgress(0f)
                listener?.onDragCancelled()
            }
            .start()
    }

    private fun animateOut(dy: Float) {
        val view = target ?: return
        animating = true
        val startProgress = currentProgress(view)
        val endY = sign(dy) * height * 0.6f
        view.animate()
            .translationY(endY).scaleX(0.6f).scaleY(0.6f).alpha(0f)
            .setDuration(180)
            .setInterpolator(DecelerateInterpolator())
            .setUpdateListener { listener?.onDragProgress(startProgress + (1f - startProgress) * it.animatedFraction) }
            .withEndAction {
                view.animate().setUpdateListener(null)
                listener?.onDismissed()
            }
            .start()
    }

    private fun currentProgress(view: View): Float {
        val h = height.coerceAtLeast(1).toFloat()
        return min(abs(view.translationY) / (h * 0.5f), 1f)
    }

    companion object {
        private const val DISMISS_FRACTION = 0.14f
    }
}
