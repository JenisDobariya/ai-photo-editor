package com.photo_editor.my_photo_editor.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import com.photo_editor.my_photo_editor.model.SlotRect

/** Small picture of a collage template: one rounded block per photo slot. */
class CollagePreviewDrawable(private val slots: List<SlotRect>) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val size = minOf(b.width(), b.height()).toFloat()
        val left = b.left + (b.width() - size) / 2f
        val top = b.top + (b.height() - size) / 2f
        val gap = size * 0.06f
        val radius = size * 0.06f
        for (s in slots) {
            rect.set(
                left + s.left * size + gap / 2f,
                top + s.top * size + gap / 2f,
                left + s.right * size - gap / 2f,
                top + s.bottom * size - gap / 2f
            )
            canvas.drawRoundRect(rect, radius, radius, paint)
        }
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth(): Int = 96
    override fun getIntrinsicHeight(): Int = 96
}
