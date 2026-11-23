package com.photo_editor.my_photo_editor.ui

import android.content.Context
import com.photo_editor.my_photo_editor.utils.appColor
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.photo_editor.my_photo_editor.R

class AdjustmentCircleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.parseColor("#33FFFFFF")
    }

    private val rect = RectF()
    private var value = 0f // -1f to 1f

    init {
        paint.color = try {
            context.appColor(R.color.lumina_gold)
        } catch (_: Exception) {
            Color.parseColor("#FFB300")
        }
    }

    fun setValue(newValue: Float) {
        value = newValue.coerceIn(-1f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = (minOf(width, height) / 2f) - (paint.strokeWidth / 2f)
        if (radius <= 0f) return

        rect.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius)

        // Draw background circle
        canvas.drawCircle(centerX, centerY, radius, bgPaint)

        // Draw progress arc from top (270 degrees)
        // sweepAngle goes up to 180 (half circle)
        val sweepAngle = value * 180f 
        canvas.drawArc(rect, 270f, sweepAngle, false, paint)
    }
}
