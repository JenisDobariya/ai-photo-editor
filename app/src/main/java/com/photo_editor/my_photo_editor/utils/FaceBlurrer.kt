package com.photo_editor.my_photo_editor.utils

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Obscures faces for privacy: each face box is grown by ~20%, turned into a rounded
 * (almost oval) region and filled with a blurred or pixelated copy of itself with soft edges.
 * The effect size is relative to the face size, so a preview and a full-resolution render
 * look the same.
 */
object FaceBlurrer {

    enum class Style { BLUR, PIXELATE }

    private const val EXPAND = 1.2f

    /** The face box grown by ~20% around its centre (image coordinates, not clamped). */
    fun expandedRegion(box: RectF): RectF {
        val cx = box.centerX()
        val cy = box.centerY()
        val hw = box.width() * EXPAND / 2f
        val hh = box.height() * EXPAND / 2f
        return RectF(cx - hw, cy - hh, cx + hw, cy + hh)
    }

    /** Corner radius that makes [region] a soft rounded / oval shape. */
    fun cornerRadius(region: RectF): Float = min(region.width(), region.height()) * 0.42f

    /**
     * Draws the obscured faces into [target] (must be mutable), reading pixels from [source]
     * (may be the same bitmap as [target]). [faces] are raw face boxes in image coordinates.
     * [strength] 0..100: higher = coarser.
     */
    fun apply(target: Bitmap, source: Bitmap, faces: List<RectF>, style: Style, strength: Int) {
        if (faces.isEmpty() || !target.isMutable) return
        val canvas = Canvas(target)
        val s = strength.coerceIn(0, 100) / 100f
        for (box in faces) {
            val region = expandedRegion(box)
            val size = min(region.width(), region.height())
            if (size < 2f) continue
            val feather = max(2f, size * 0.10f)
            val shape = RectF(region).apply { inset(-feather * 0.5f, -feather * 0.5f) }
            val patch = Rect(
                floor(shape.left - feather * 1.5f).toInt(),
                floor(shape.top - feather * 1.5f).toInt(),
                ceil(shape.right + feather * 1.5f).toInt(),
                ceil(shape.bottom + feather * 1.5f).toInt()
            )
            if (!patch.intersect(0, 0, source.width, source.height)) continue
            if (patch.width() < 2 || patch.height() < 2) continue

            val crop = Bitmap.createBitmap(source, patch.left, patch.top, patch.width(), patch.height())
            val obscured = try {
                obscure(crop, style, s, size)
            } finally {
                if (crop !== source) crop.recycle()
            }

            val shader = BitmapShader(obscured, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            shader.setLocalMatrix(Matrix().apply { setTranslate(patch.left.toFloat(), patch.top.toFloat()) })
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                this.shader = shader
                maskFilter = BlurMaskFilter(feather, BlurMaskFilter.Blur.NORMAL)
            }
            val r = cornerRadius(shape)
            canvas.drawRoundRect(shape, r, r, paint)
            obscured.recycle()
        }
    }

    /** Returns a NEW bitmap the size of [crop] with the effect applied. */
    private fun obscure(crop: Bitmap, style: Style, s: Float, faceSize: Float): Bitmap {
        val w = crop.width
        val h = crop.height
        return when (style) {
            Style.PIXELATE -> {
                val blocksAcross = lerp(12f, 5f, s)
                val block = max(2f, faceSize / blocksAcross)
                val sw = max(1, (w / block).roundToInt())
                val sh = max(1, (h / block).roundToInt())
                val small = downscale(crop, sw, sh)
                val up = Bitmap.createScaledBitmap(small, w, h, false)
                if (small !== up && small !== crop) small.recycle()
                if (up === crop) crop.copy(Bitmap.Config.ARGB_8888, false) else up
            }
            Style.BLUR -> {
                val across = lerp(12f, 5f, s)
                val factor = max(1f, faceSize / across)
                val sw = max(2, (w / factor).roundToInt())
                val sh = max(2, (h / factor).roundToInt())
                val small = downscale(crop, sw, sh)
                val smallBlur = boxBlur(small, 1)
                if (small !== crop) small.recycle()
                val mid = Bitmap.createScaledBitmap(smallBlur, sw * 4, sh * 4, true)
                if (mid !== smallBlur) smallBlur.recycle()
                val midBlur = boxBlur(mid, 2)
                mid.recycle()
                val up = Bitmap.createScaledBitmap(midBlur, w, h, true)
                if (up !== midBlur) midBlur.recycle()
                up
            }
        }
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    /** Progressive halving so big reductions average pixels instead of aliasing. May return [src]. */
    private fun downscale(src: Bitmap, tw: Int, th: Int): Bitmap {
        var cur = src
        while (cur.width / 2 >= tw && cur.height / 2 >= th && cur.width >= 4 && cur.height >= 4) {
            val next = Bitmap.createScaledBitmap(cur, cur.width / 2, cur.height / 2, true)
            if (cur !== src && cur !== next) cur.recycle()
            cur = next
        }
        if (cur.width == tw && cur.height == th) return cur
        val fin = Bitmap.createScaledBitmap(cur, tw, th, true)
        if (cur !== src && cur !== fin) cur.recycle()
        return fin
    }

    /** Separable box blur with clamped edges; returns a new bitmap. */
    private fun boxBlur(src: Bitmap, radius: Int): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        val div = radius * 2 + 1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var a = 0; var r = 0; var g = 0; var b = 0
                for (k in -radius..radius) {
                    val c = px[row + (x + k).coerceIn(0, w - 1)]
                    a += c ushr 24; r += (c shr 16) and 0xff; g += (c shr 8) and 0xff; b += c and 0xff
                }
                tmp[row + x] = ((a / div) shl 24) or ((r / div) shl 16) or ((g / div) shl 8) or (b / div)
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                var a = 0; var r = 0; var g = 0; var b = 0
                for (k in -radius..radius) {
                    val c = tmp[(y + k).coerceIn(0, h - 1) * w + x]
                    a += c ushr 24; r += (c shr 16) and 0xff; g += (c shr 8) and 0xff; b += c and 0xff
                }
                px[y * w + x] = ((a / div) shl 24) or ((r / div) shl 16) or ((g / div) shl 8) or (b / div)
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }
}
