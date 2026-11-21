package com.photo_editor.my_photo_editor.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.photo_editor.my_photo_editor.R
import kotlin.math.roundToInt

class StraightenRulerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GRAY
        strokeWidth = 2f
    }

    private val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = try {
            ContextCompat.getColor(context, R.color.lumina_gold)
        } catch (_: Exception) {
            Color.parseColor("#FFB300")
        }
        strokeWidth = 6f
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 24f
        textAlign = Paint.Align.CENTER
    }

    private var currentDegrees = 0f
    private var minDegrees = -3600f
    private var maxDegrees = 3600f
    private var onDegreesChangedListener: ((Float) -> Unit)? = null
    
    private var lastX = 0f

    fun setRange(min: Float, max: Float) {
        minDegrees = min
        maxDegrees = max
        currentDegrees = currentDegrees.coerceIn(min, max)
        invalidate()
    }

    fun setDegrees(degrees: Float) {
        currentDegrees = degrees.coerceIn(minDegrees, maxDegrees)
        invalidate()
    }

    fun setOnDegreesChangedListener(listener: (Float) -> Unit) {
        onDegreesChangedListener = listener
    }

    init {
        isHapticFeedbackEnabled = true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                lastX = event.x
                // Sensitivity: scroll 15 pixels = 1 degree
                val degreeDelta = -dx / 15f
                val oldDegrees = currentDegrees
                currentDegrees = (currentDegrees + degreeDelta).coerceIn(minDegrees, maxDegrees)
                
                if (currentDegrees.roundToInt() != oldDegrees.roundToInt()) {
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }

                onDegreesChangedListener?.invoke(currentDegrees)
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val centerX = width / 2f
        val centerY = height / 2f
        val spacing = 20f // pixels per degree

        // Determine range to draw
        val startVisible = (currentDegrees - (centerX / spacing)).toInt() - 1
        val endVisible = (currentDegrees + (centerX / spacing)).toInt() + 1

        for (i in startVisible..endVisible) {
            if (i < minDegrees || i > maxDegrees) continue

            val degreePos = centerX + (i - currentDegrees) * spacing
            
            val isMajor = i % 5 == 0
            val isTen = i % 10 == 0
            
            linePaint.color = if (isTen) Color.WHITE else Color.GRAY
            linePaint.alpha = if (isMajor) 255 else 100
            
            val lineLength = if (isTen) height * 0.4f else if (isMajor) height * 0.25f else height * 0.15f
            canvas.drawLine(degreePos, centerY - lineLength / 2, degreePos, centerY + lineLength / 2, linePaint)
            
            if (isTen) {
                canvas.drawText(i.toString(), degreePos, centerY + lineLength / 2 + 30f, textPaint)
            }
        }

        // Draw center indicator
        canvas.drawLine(centerX, centerY - height * 0.4f, centerX, centerY + height * 0.4f, centerPaint)
    }
}
