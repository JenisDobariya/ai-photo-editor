package com.photo_editor.my_photo_editor.utils

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Classic, on-device "auto enhance": levels, gentle white balance, gamma, a mild S-curve,
 * vibrance with skin protection and a light unsharp mask. Tuned to stay natural — every
 * correction is clamped and damped, and photos that are already well exposed get only a
 * light touch.
 *
 * Usage: [analyze] once (on any reasonably sized copy of the photo), then [enhancePixels]
 * on the pixels of any resolution of the same photo.
 */
object AutoEnhancer {

    class Params(
        val lutR: IntArray,
        val lutG: IntArray,
        val lutB: IntArray,
        /** 0..~0.3 extra saturation for muted colours. */
        val vibrance: Float,
        /** Unsharp-mask amount at full strength. */
        val sharpenAmount: Float,
        /** Luminance difference (0..255) ignored by the unsharp mask, so noise isn't boosted. */
        val sharpenThreshold: Int,
        /** True when the photo was already well exposed and only light touch-ups are applied. */
        val alreadyGood: Boolean
    )

    private const val ANALYSIS_SIZE = 256

    /** Analyses a 256 px copy of [src] and builds the correction. [src] is not modified. */
    fun analyze(src: Bitmap): Params {
        val longEdge = max(src.width, src.height)
        val small = if (longEdge > ANALYSIS_SIZE) {
            val sc = ANALYSIS_SIZE.toFloat() / longEdge
            Bitmap.createScaledBitmap(
                src,
                max(1, (src.width * sc).roundToInt()),
                max(1, (src.height * sc).roundToInt()),
                true
            )
        } else src
        val w = small.width
        val h = small.height
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== src) small.recycle()

        val hist = IntArray(256)
        var sumY = 0.0
        var sumY2 = 0.0
        var sumSat = 0.0
        var allR = 0.0; var allG = 0.0; var allB = 0.0
        var midR = 0.0; var midG = 0.0; var midB = 0.0
        var nMid = 0
        for (c in px) {
            val r = (c shr 16) and 0xff
            val g = (c shr 8) and 0xff
            val b = c and 0xff
            val y = (r * 77 + g * 150 + b * 29) shr 8
            hist[y]++
            sumY += y
            sumY2 += (y * y).toDouble()
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            if (mx > 0) sumSat += (mx - mn).toDouble() / mx
            allR += r; allG += g; allB += b
            // Clipped shadows/highlights say little about the light colour.
            if (y in 16..240) {
                midR += r; midG += g; midB += b
                nMid++
            }
        }
        val n = px.size.toDouble().coerceAtLeast(1.0)
        val black = percentile(hist, px.size, 0.005)
        val white = percentile(hist, px.size, 0.995)
        val meanY = sumY / n
        val stdY = sqrt(max(0.0, sumY2 / n - meanY * meanY))
        val meanSat = sumSat / n

        // --- Levels: stretch to 0.5% / 99.5% clip points, but never crush/blow too much.
        val bp = min(black, 36).toFloat()
        val wp = max(white, 200).toFloat()
        val range = (wp - bp).coerceAtLeast(1f)

        // --- Gamma: lift dark photos' midtones / calm bright ones. Dead zone 0.40..0.54.
        val m1 = ((meanY - bp) / range).coerceIn(0.03, 0.97)
        val target = m1.coerceIn(0.40, 0.54)
        var gamma = (1.0 + (ln(target) / ln(m1) - 1.0) * 0.6).coerceIn(0.75, 1.25)

        // --- Grey-world white balance, gentle so sunsets / candle light keep their mood.
        val useMid = nMid > px.size * 0.05
        val cr = if (useMid) midR / nMid else allR / n
        val cg = if (useMid) midG / nMid else allG / n
        val cb = if (useMid) midB / nMid else allB / n
        val gray = (cr + cg + cb) / 3.0
        val rawR = gray / max(1.0, cr)
        val rawG = gray / max(1.0, cg)
        val rawB = gray / max(1.0, cb)
        val dev = max(abs(rawR - 1.0), max(abs(rawG - 1.0), abs(rawB - 1.0)))
        var wbBlend = 0.4
        // Strong casts are usually intentional (sunset, underwater, neon): correct even less.
        if (dev > 0.12) wbBlend *= (1.0 - (dev - 0.12) / 0.3).coerceIn(0.3, 1.0)
        var gR = 1.0 + (rawR - 1.0) * wbBlend
        var gG = 1.0 + (rawG - 1.0) * wbBlend
        var gB = 1.0 + (rawB - 1.0) * wbBlend
        // Keep overall brightness unchanged by the white balance.
        val norm = (0.299 * gR + 0.587 * gG + 0.114 * gB).coerceAtLeast(0.01)
        gR = (gR / norm).coerceIn(0.85, 1.15)
        gG = (gG / norm).coerceIn(0.85, 1.15)
        gB = (gB / norm).coerceIn(0.85, 1.15)

        // --- Contrast: flatter photos get a little more S-curve.
        val stdN = stdY / range
        var curve = (0.34 - stdN).coerceIn(0.06, 0.22)

        // --- Vibrance: less when colours are already rich.
        var vibrance = (0.38 - meanSat * 0.6).coerceIn(0.06, 0.28)
        var sharpen = 0.4

        val alreadyGood = black <= 10 && white >= 240 && m1 in 0.38..0.58 && dev < 0.06 && stdN >= 0.2
        if (alreadyGood) {
            curve *= 0.5
            vibrance *= 0.6
            sharpen = 0.25
            gamma = 1.0 + (gamma - 1.0) * 0.5
        }

        return Params(
            lutR = buildLut(bp, range, gR, gamma, curve),
            lutG = buildLut(bp, range, gG, gamma, curve),
            lutB = buildLut(bp, range, gB, gamma, curve),
            vibrance = vibrance.toFloat(),
            sharpenAmount = sharpen.toFloat(),
            sharpenThreshold = 4,
            alreadyGood = alreadyGood
        )
    }

    private fun percentile(hist: IntArray, total: Int, p: Double): Int {
        val target = p * total
        var cum = 0L
        for (i in 0..255) {
            cum += hist[i]
            if (cum >= target) return i
        }
        return 255
    }

    /** levels → white-preserving channel gain → gamma → mild S-curve, as a 256-entry LUT. */
    private fun buildLut(bp: Float, range: Float, gain: Double, gamma: Double, curve: Double): IntArray {
        val lut = IntArray(256)
        for (i in 0..255) {
            var x = ((i - bp) / range).toDouble().coerceIn(0.0, 1.0)
            // Gain that keeps pure white white (monotonic for gains in 0.85..1.15).
            x = if (gain >= 1.0) x * gain / (1.0 + (gain - 1.0) * x.pow(4))
            else x * gain + (1.0 - gain) * x.pow(4)
            x = x.coerceIn(0.0, 1.0).pow(gamma)
            val s = x * x * (3.0 - 2.0 * x)
            x += curve * (s - x)
            lut[i] = (x * 255.0 + 0.5).toInt().coerceIn(0, 255)
        }
        return lut
    }

    /**
     * Enhances [px] (ARGB, row stride [w]) in place, blended with the original by [strength]
     * (0..1). Sharpening is scaled by [strength] too.
     */
    fun enhancePixels(px: IntArray, w: Int, h: Int, p: Params, strength: Float) {
        val s = strength.coerceIn(0f, 1f)
        if (s <= 0f) return
        val si = (s * 256f).roundToInt()
        val lutR = p.lutR
        val lutG = p.lutG
        val lutB = p.lutB
        val vib = p.vibrance
        for (i in 0 until min(px.size, w * h)) {
            val c = px[i]
            val a = c ushr 24
            val r0 = (c shr 16) and 0xff
            val g0 = (c shr 8) and 0xff
            val b0 = c and 0xff
            var r = lutR[r0]
            var g = lutG[g0]
            var b = lutB[b0]

            if (vib > 0f) {
                val mx = max(r, max(g, b))
                val mn = min(r, min(g, b))
                if (mx > mn) {
                    val sat = (mx - mn).toFloat() / mx
                    // Muted colours get the most, saturated ones almost nothing.
                    var amt = vib * (1f - sat) * (1f - sat)
                    // Skin tones (hue ~0°–50°: red max, blue min) are protected.
                    if (r == mx && b == mn) {
                        val hue = 60f * (g - b) / (mx - mn)
                        amt *= when {
                            hue <= 50f -> 0.3f
                            hue <= 60f -> 0.3f + 0.07f * (hue - 50f)
                            else -> 1f
                        }
                    }
                    if (amt > 0.002f) {
                        val lum = (r * 77 + g * 150 + b * 29) shr 8
                        val f = 1f + amt
                        r = (lum + (r - lum) * f).roundToInt().coerceIn(0, 255)
                        g = (lum + (g - lum) * f).roundToInt().coerceIn(0, 255)
                        b = (lum + (b - lum) * f).roundToInt().coerceIn(0, 255)
                    }
                }
            }

            if (si < 256) {
                r = r0 + (((r - r0) * si) shr 8)
                g = g0 + (((g - g0) * si) shr 8)
                b = b0 + (((b - b0) * si) shr 8)
            }
            px[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        val amount = p.sharpenAmount * s
        if (amount > 0.01f) sharpen(px, w, h, amount, p.sharpenThreshold)
    }

    /** out = orig + (enh - orig) * strength, per channel. All arrays have the same size. */
    fun blend(orig: IntArray, enh: IntArray, out: IntArray, strength: Float) {
        val si = (strength.coerceIn(0f, 1f) * 256f).roundToInt()
        val n = min(orig.size, min(enh.size, out.size))
        if (si >= 256) { System.arraycopy(enh, 0, out, 0, n); return }
        if (si <= 0) { System.arraycopy(orig, 0, out, 0, n); return }
        for (i in 0 until n) {
            val o = orig[i]
            val e = enh[i]
            val oR = (o shr 16) and 0xff
            val og = (o shr 8) and 0xff
            val ob = o and 0xff
            val r = oR + (((((e shr 16) and 0xff) - oR) * si) shr 8)
            val g = og + (((((e shr 8) and 0xff) - og) * si) shr 8)
            val b = ob + ((((e and 0xff) - ob) * si) shr 8)
            out[i] = (o and -0x1000000) or (r shl 16) or (g shl 8) or b
        }
    }

    /**
     * Light unsharp mask on luminance (radius 1 px, [1 2 1] kernel). Differences below
     * [threshold] are ignored (soft threshold) so flat areas / noise stay untouched.
     * Uses two byte buffers only, so it stays affordable on 12+ MP images.
     */
    private fun sharpen(px: IntArray, w: Int, h: Int, amount: Float, threshold: Int) {
        if (w < 3 || h < 3) return
        val n = w * h
        val lum = ByteArray(n)
        for (i in 0 until n) {
            val c = px[i]
            lum[i] = ((((c shr 16) and 0xff) * 77 + ((c shr 8) and 0xff) * 150 + (c and 0xff) * 29) shr 8).toByte()
        }
        val hb = ByteArray(n)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val l = lum[row + max(x - 1, 0)].toInt() and 0xff
                val c = lum[row + x].toInt() and 0xff
                val r = lum[row + min(x + 1, w - 1)].toInt() and 0xff
                hb[row + x] = ((l + 2 * c + r + 2) shr 2).toByte()
            }
        }
        val amt = (amount * 256f).roundToInt()
        for (y in 0 until h) {
            val row = y * w
            val up = max(y - 1, 0) * w
            val dn = min(y + 1, h - 1) * w
            for (x in 0 until w) {
                val i = row + x
                val blur = ((hb[up + x].toInt() and 0xff) + 2 * (hb[i].toInt() and 0xff) + (hb[dn + x].toInt() and 0xff) + 2) shr 2
                var d = (lum[i].toInt() and 0xff) - blur
                d = when {
                    d > threshold -> d - threshold
                    d < -threshold -> d + threshold
                    else -> continue
                }
                val add = ((d * amt) shr 8).coerceIn(-32, 32)
                if (add == 0) continue
                val c = px[i]
                val r = (((c shr 16) and 0xff) + add).coerceIn(0, 255)
                val g = (((c shr 8) and 0xff) + add).coerceIn(0, 255)
                val b = ((c and 0xff) + add).coerceIn(0, 255)
                px[i] = (c and -0x1000000) or (r shl 16) or (g shl 8) or b
            }
        }
    }
}
