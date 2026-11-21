package com.photo_editor.my_photo_editor.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

class ColorWheelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var selectedColor = Color.RED
    var onColorSelected: ((Int) -> Unit)? = null

    private var wheelBitmap: Bitmap? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            wheelBitmap = createColorWheelBitmap(w, h)
        }
    }

    private fun createColorWheelBitmap(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = minOf(width, height) / 2f - 16f
        val centerX = width / 2f
        val centerY = height / 2f

        val sweepShader = SweepGradient(centerX, centerY, intArrayOf(
            Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED
        ), null)
        paint.shader = sweepShader
        canvas.drawCircle(centerX, centerY, radius, paint)

        val radialShader = RadialGradient(centerX, centerY, radius, Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        paint.shader = radialShader
        canvas.drawCircle(centerX, centerY, radius, paint)

        return bitmap
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        wheelBitmap?.let {
            canvas.drawBitmap(it, 0f, 0f, null)
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
            performClick()
            val x = event.x
            val y = event.y
            val centerX = width / 2f
            val centerY = height / 2f
            val dx = x - centerX
            val dy = y - centerY
            val distance = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            val radius = minOf(width, height) / 2f - 16f

            if (distance <= radius) {
                val bitmap = wheelBitmap
                if (bitmap != null && x >= 0 && y >= 0 && x < bitmap.width && y < bitmap.height) {
                    val pixel = bitmap.getPixel(x.toInt(), y.toInt())
                    if (pixel != Color.TRANSPARENT) {
                        selectedColor = pixel
                        onColorSelected?.invoke(selectedColor)
                    }
                }
            }
            return true
        }
        return super.onTouchEvent(event)
    }
}
