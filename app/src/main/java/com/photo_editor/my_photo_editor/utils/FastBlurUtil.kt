package com.photo_editor.my_photo_editor.utils

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

object FastBlurUtil {

    /**
     * Creates a smooth bokeh background blur by downscaling, applying multiple
     * box-blur passes, and returning a high quality blurred bitmap.
     */
    fun blur(src: Bitmap, radius: Int = 25): Bitmap {
        if (radius <= 0) return src

        // Downscale for speed and smoother bokeh effect
        val scale = 0.25f
        val w = max(1, (src.width * scale).toInt())
        val h = max(1, (src.height * scale).toInt())
        val small = Bitmap.createScaledBitmap(src, w, h, true)

        val pixels = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)

        val r = radius.coerceIn(1, 50)
        boxBlur(pixels, w, h, r)
        boxBlur(pixels, w, h, r)

        val blurred = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        blurred.setPixels(pixels, 0, w, 0, 0, w, h)

        // Scale back up with bilinear filtering for smooth bokeh
        return Bitmap.createScaledBitmap(blurred, src.width, src.height, true)
    }

    private fun boxBlur(pixels: IntArray, w: Int, h: Int, r: Int) {
        val wm = w - 1
        val hm = h - 1
        val wh = w * h
        val div = r + r + 1

        val rAcc = IntArray(wh)
        val gAcc = IntArray(wh)
        val bAcc = IntArray(wh)
        val aAcc = IntArray(wh)

        var rSum: Int
        var gSum: Int
        var bSum: Int
        var aSum: Int
        var yi = 0

        // Horizontal pass
        for (y in 0 until h) {
            rSum = 0; gSum = 0; bSum = 0; aSum = 0
            val rowOffset = y * w

            for (i in -r..r) {
                val p = pixels[rowOffset + min(wm, max(i, 0))]
                aSum += (p ushr 24) and 0xff
                rSum += (p ushr 16) and 0xff
                gSum += (p ushr 8) and 0xff
                bSum += p and 0xff
            }

            for (x in 0 until w) {
                aAcc[yi] = aSum / div
                rAcc[yi] = rSum / div
                gAcc[yi] = gSum / div
                bAcc[yi] = bSum / div

                val p1 = pixels[rowOffset + min(wm, max(x + r + 1, 0))]
                val p2 = pixels[rowOffset + min(wm, max(x - r, 0))]

                aSum += ((p1 ushr 24) and 0xff) - ((p2 ushr 24) and 0xff)
                rSum += ((p1 ushr 16) and 0xff) - ((p2 ushr 16) and 0xff)
                gSum += ((p1 ushr 8) and 0xff) - ((p2 ushr 8) and 0xff)
                bSum += (p1 and 0xff) - (p2 and 0xff)

                yi++
            }
        }

        // Vertical pass
        for (x in 0 until w) {
            rSum = 0; gSum = 0; bSum = 0; aSum = 0
            var yp = -r * w

            for (i in -r..r) {
                val index = max(0, yp) + x
                aSum += aAcc[index]
                rSum += rAcc[index]
                gSum += gAcc[index]
                bSum += bAcc[index]
                yp += w
            }

            yi = x
            for (y in 0 until h) {
                val a = (aSum / div).coerceIn(0, 255)
                val rVal = (rSum / div).coerceIn(0, 255)
                val g = (gSum / div).coerceIn(0, 255)
                val b = (bSum / div).coerceIn(0, 255)

                pixels[yi] = (a shl 24) or (rVal shl 16) or (g shl 8) or b

                val y1 = min(hm, max(y + r + 1, 0)) * w + x
                val y2 = min(hm, max(y - r, 0)) * w + x

                aSum += aAcc[y1] - aAcc[y2]
                rSum += rAcc[y1] - rAcc[y2]
                gSum += gAcc[y1] - gAcc[y2]
                bSum += bAcc[y1] - bAcc[y2]

                yi += w
            }
        }
    }
}
