package com.photo_editor.my_photo_editor.utils

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * On-device subject cutout, portrait blur and colour backdrop.
 *
 * Pipeline:
 *  1. ML Kit Subject Segmentation (any subject: people, pets, objects) gives a per-pixel
 *     foreground confidence at the working resolution. The selfie segmenter is only used while
 *     the subject model is still being downloaded by Play services.
 *  2. [cleanMask]: low-confidence noise is zeroed and detached specks/islands are removed.
 *  3. [extractForeground]: edge shaping per [EdgeStyle], then colour decontamination of the
 *     semi-transparent edge band so no background-coloured fringe ("lines") remains.
 *  4. [createBackgroundPlate] + [createPortraitBokeh]: the subject is removed from the background
 *     (push-pull hole filling) before blurring, so the subject never bleeds into the bokeh.
 *
 * All pixel work runs on IntArray/FloatArray buffers with no per-pixel allocation; call the
 * non-suspending functions from Dispatchers.Default.
 */
object AiCutoutManager {

    /** Long edge (px) of the bitmap we segment and cut out; keeps memory bounded. */
    const val WORKING_MAX_PX = 2048

    enum class EdgeStyle {
        CRISP,   // Tight contour (stickers)
        SMOOTH,  // Balanced anti-aliased edge with ~1px feather (natural default)
        FEATHER  // "Soft": wider feather that keeps wispy hair/fur looking natural
    }

    /**
     * Foreground opacity per pixel (row-major, 0..1) at the working resolution.
     * Already cleaned of noise and stray islands; edge styling is applied later.
     */
    class SubjectMask(val width: Int, val height: Int, val alpha: FloatArray)

    /** Result of [segmentImage]. [usedPersonFallback] is true when the selfie model was used. */
    class SegmentOutcome(val mask: SubjectMask, val usedPersonFallback: Boolean)

    // Confidence below this is treated as pure noise.
    private const val NOISE_FLOOR = 0.08f
    // Size (long edge) of the coarse grids used for island removal and colour estimation.
    private const val COARSE_GRID_PX = 512
    // Size (long edge) of the background plate used for the portrait blur.
    private const val PLATE_PX = 512
    // Window radius (full-res px) of the fine colour-decontamination pass.
    private const val FINE_RADIUS = 3

    private val subjectSegmenter by lazy {
        SubjectSegmentation.getClient(
            SubjectSegmenterOptions.Builder()
                .enableForegroundConfidenceMask()
                .build()
        )
    }

    private val selfieSegmenter by lazy {
        Segmentation.getClient(
            SelfieSegmenterOptions.Builder()
                .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                .build()
        )
    }

    // ---------------------------------------------------------------------------------------
    // Segmentation
    // ---------------------------------------------------------------------------------------

    /**
     * Segments [bitmap] (expected to be at most [WORKING_MAX_PX] on its long edge) and returns a
     * cleaned mask of the same size. Falls back to the selfie (people-only) model when the
     * subject model is not downloaded yet; other errors are thrown to the caller.
     */
    suspend fun segmentImage(bitmap: Bitmap): SegmentOutcome {
        var usedFallback = false
        val confidence = try {
            subjectConfidence(bitmap)
        } catch (e: Exception) {
            if (!isModelUnavailable(e)) throw e
            usedFallback = true
            selfieConfidence(bitmap)
        }
        val mask = SubjectMask(bitmap.width, bitmap.height, confidence)
        withContext(Dispatchers.Default) { cleanMask(mask) }
        return SegmentOutcome(mask, usedFallback)
    }

    private fun isModelUnavailable(e: Throwable): Boolean {
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth++ < 5) {
            if (t is MlKitException && t.errorCode == MlKitException.UNAVAILABLE) return true
            t = t.cause
        }
        return false
    }

    private suspend fun subjectConfidence(bitmap: Bitmap): FloatArray {
        val result = subjectSegmenter.process(InputImage.fromBitmap(bitmap, 0)).await()
        val out = FloatArray(bitmap.width * bitmap.height)
        val buffer = result.foregroundConfidenceMask ?: return out
        buffer.rewind()
        buffer.get(out, 0, min(out.size, buffer.remaining()))
        return out
    }

    private suspend fun selfieConfidence(bitmap: Bitmap): FloatArray {
        val mask = selfieSegmenter.process(InputImage.fromBitmap(bitmap, 0)).await()
        val mw = mask.width
        val mh = mask.height
        val src = FloatArray(mw * mh)
        val buffer = mask.buffer
        buffer.rewind()
        buffer.asFloatBuffer().get(src)
        return if (mw == bitmap.width && mh == bitmap.height) src
        else resizeBilinear(src, mw, mh, bitmap.width, bitmap.height)
    }

    /** True when the mask contains a reasonably sized confident subject. */
    fun isSubjectDetected(mask: SubjectMask): Boolean {
        val a = mask.alpha
        if (a.isEmpty()) return false
        val step = max(1, a.size / 20000)
        var sampled = 0
        var solid = 0
        var i = 0
        while (i < a.size) {
            sampled++
            if (a[i] > 0.5f) solid++
            i += step
        }
        return solid >= max(1, sampled / 500) // at least ~0.2% of the photo
    }

    // ---------------------------------------------------------------------------------------
    // Mask cleaning
    // ---------------------------------------------------------------------------------------

    private fun cleanMask(mask: SubjectMask) {
        val a = mask.alpha
        for (i in a.indices) {
            val v = a[i]
            a[i] = when {
                v.isNaN() || v < NOISE_FLOOR -> 0f
                v > 1f -> 1f
                else -> v
            }
        }
        removeIslands(a, mask.width, mask.height)
    }

    /**
     * Removes detached specks. Works on a coarse binary grid (~512 px): cells whose mean
     * confidence is >= 0.4 are "on"; 8-connected regions much smaller than the largest are
     * dropped. The kept regions are dilated by a few cells so faint hair/fur next to the subject
     * survives, and every full-res pixel outside that zone is zeroed.
     */
    private fun removeIslands(a: FloatArray, w: Int, h: Int) {
        if (w == 0 || h == 0) return
        val f = gridFactor(w, h, COARSE_GRID_PX)
        val sw = ceilDiv(w, f)
        val sh = ceilDiv(h, f)
        val n = sw * sh
        val sums = FloatArray(n)
        val counts = IntArray(n)
        for (y in 0 until h) {
            val rowCell = (y / f) * sw
            val row = y * w
            for (x in 0 until w) {
                val c = rowCell + x / f
                sums[c] += a[row + x]
                counts[c]++
            }
        }
        val on = BooleanArray(n) { counts[it] > 0 && sums[it] >= 0.4f * counts[it] }

        // Flood-fill labelling (8-connected) on the coarse grid.
        val labels = IntArray(n)
        val sizes = ArrayList<Int>()
        sizes.add(0) // label 0 = background
        val queue = IntArray(n)
        for (start in 0 until n) {
            if (!on[start] || labels[start] != 0) continue
            val label = sizes.size
            var head = 0
            var tail = 0
            queue[tail++] = start
            labels[start] = label
            while (head < tail) {
                val p = queue[head++]
                val px = p % sw
                val py = p / sw
                for (dy in -1..1) {
                    val ny = py + dy
                    if (ny < 0 || ny >= sh) continue
                    for (dx in -1..1) {
                        val nx = px + dx
                        if (nx < 0 || nx >= sw) continue
                        val q = ny * sw + nx
                        if (on[q] && labels[q] == 0) {
                            labels[q] = label
                            queue[tail++] = q
                        }
                    }
                }
            }
            sizes.add(tail)
        }

        if (sizes.size == 1) {
            // Nothing confident anywhere: treat as "no subject" rather than keeping a faint ghost.
            a.fill(0f)
            return
        }
        var largest = 0
        for (s in sizes) largest = max(largest, s)
        // Keep any region >= 5% of the largest (a second person, a pet next to its owner...).
        val minKeep = min(largest, max((largest * 0.05f).toInt(), (n * 0.001f).toInt()))
        val keep = BooleanArray(n) { labels[it] != 0 && sizes[labels[it]] >= minKeep }

        // Separable dilation by D cells.
        val d = 3
        val tmp = BooleanArray(n)
        for (y in 0 until sh) {
            val row = y * sw
            for (x in 0 until sw) {
                var hit = false
                for (k in max(0, x - d)..min(sw - 1, x + d)) if (keep[row + k]) { hit = true; break }
                tmp[row + x] = hit
            }
        }
        for (y in 0 until sh) {
            for (x in 0 until sw) {
                var hit = false
                for (k in max(0, y - d)..min(sh - 1, y + d)) if (tmp[k * sw + x]) { hit = true; break }
                keep[y * sw + x] = hit
            }
        }

        for (y in 0 until h) {
            val rowCell = (y / f) * sw
            val row = y * w
            for (x in 0 until w) {
                if (!keep[rowCell + x / f]) a[row + x] = 0f
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Cutout
    // ---------------------------------------------------------------------------------------

    /**
     * Transparent cutout at the mask's resolution: shaped alpha + decontaminated edge colours.
     * [original] should be the bitmap that was segmented (it is rescaled if it is not).
     */
    fun extractForeground(
        original: Bitmap,
        mask: SubjectMask,
        edgeStyle: EdgeStyle = EdgeStyle.SMOOTH
    ): Bitmap {
        val w = mask.width
        val h = mask.height
        val pixels = readPixels(original, w, h)
        val alpha = shapeAlpha(mask.alpha, w, h, edgeStyle)
        // Quantise to the 8-bit values that will be stored, so "edge pixel" (0 < a < 1)
        // means the same thing in decontamination and in the output.
        for (i in alpha.indices) {
            alpha[i] = (alpha[i] * 255f + 0.5f).toInt().coerceIn(0, 255) / 255f
        }
        decontaminate(pixels, alpha, w, h)
        for (i in pixels.indices) {
            val a8 = (alpha[i] * 255f + 0.5f).toInt()
            pixels[i] = if (a8 <= 0) 0 else (a8 shl 24) or (pixels[i] and 0x00FFFFFF)
        }
        // Colours are un-premultiplied ARGB, which is what createBitmap(int[]) expects.
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * Edge shaping. smoothstep(lo, hi, c) remaps confidence to opacity: a narrow [lo, hi] gives a
     * steep, crisp edge; a wider one plus a box blur of the alpha (2 passes ≈ Gaussian) gives a
     * feather. Thresholds sit slightly above 0.5 to keep the edge tight on the subject.
     */
    private fun shapeAlpha(base: FloatArray, w: Int, h: Int, style: EdgeStyle): FloatArray {
        val (lo, hi) = when (style) {
            EdgeStyle.CRISP -> 0.45f to 0.65f
            EdgeStyle.SMOOTH -> 0.30f to 0.80f
            EdgeStyle.FEATHER -> 0.18f to 0.85f
        }
        val out = FloatArray(base.size)
        val inv = 1f / (hi - lo)
        for (i in base.indices) {
            val t = ((base[i] - lo) * inv).coerceIn(0f, 1f)
            out[i] = t * t * (3f - 2f * t)
        }
        val longEdge = max(w, h)
        val featherRadius = when (style) {
            EdgeStyle.CRISP -> 0
            EdgeStyle.SMOOTH -> max(1, (longEdge / 1600f).roundToInt())
            EdgeStyle.FEATHER -> max(2, (longEdge / 400f).roundToInt())
        }
        if (featherRadius > 0) {
            val tmp = FloatArray(out.size)
            boxBlur(out, tmp, w, h, featherRadius)
            if (style == EdgeStyle.FEATHER) boxBlur(out, tmp, w, h, featherRadius)
            for (i in out.indices) out[i] = out[i].coerceIn(0f, 1f)
        }
        return out
    }

    /**
     * Colour decontamination of edge pixels (0 < a < 1), "Blur-Fusion" foreground estimation
     * (Germer et al. 2020) in two levels. With observed colour I = a·F + (1-a)·B:
     *   F̂ = blur(F·a) / blur(a),  B̂ = blur(B·(1-a)) / blur(1-a)   (local fg / bg colour means)
     *   r  = I - a·F̂ - (1-a)·B̂                                    (unexplained residual)
     *   F  = F̂ + a·r,   B = B̂ + (1-a)·r
     * Level 1 uses a large blur on a ~512 px grid (F = B = I initially). Level 2 repeats with a
     * small full-res window using level-1 F/B, which restores fine detail (hair). Low-alpha pixels
     * end up close to the local foreground colour, so the background no longer shows as a fringe.
     * Writes the estimated F into [pixels] for edge pixels only.
     */
    private fun decontaminate(pixels: IntArray, alpha: FloatArray, w: Int, h: Int) {
        val total = w * h
        var nEdge = 0
        for (i in 0 until total) {
            val a = alpha[i]
            if (a > 0f && a < 1f) nEdge++
        }
        if (nEdge == 0) return
        val edges = IntArray(nEdge)
        val edgeMap = IntArray(total) // pixel -> edge index (only read for edge pixels)
        var k = 0
        for (i in 0 until total) {
            val a = alpha[i]
            if (a > 0f && a < 1f) {
                edges[k] = i
                edgeMap[i] = k
                k++
            }
        }

        // ---- Level 1: coarse grid ----
        val f = gridFactor(w, h, COARSE_GRID_PX)
        val sw = ceilDiv(w, f)
        val sh = ceilDiv(h, f)
        val n = sw * sh
        val fR = FloatArray(n); val fG = FloatArray(n); val fB = FloatArray(n); val fW = FloatArray(n)
        val bR = FloatArray(n); val bG = FloatArray(n); val bB = FloatArray(n); val bW = FloatArray(n)
        for (y in 0 until h) {
            val rowCell = (y / f) * sw
            val row = y * w
            for (x in 0 until w) {
                val i = row + x
                val c = rowCell + x / f
                val a = alpha[i]
                val ia = 1f - a
                val p = pixels[i]
                val r = ((p shr 16) and 0xFF).toFloat()
                val g = ((p shr 8) and 0xFF).toFloat()
                val b = (p and 0xFF).toFloat()
                fR[c] += r * a; fG[c] += g * a; fB[c] += b * a; fW[c] += a
                bR[c] += r * ia; bG[c] += g * ia; bB[c] += b * ia; bW[c] += ia
            }
        }
        val coarseRadius = max(2, (48f / f).roundToInt()) // ~48 full-res px, 2 passes
        val tmp = FloatArray(n)
        for (arr in arrayOf(fR, fG, fB, fW, bR, bG, bB, bW)) {
            boxBlur(arr, tmp, sw, sh, coarseRadius)
            boxBlur(arr, tmp, sw, sh, coarseRadius)
        }
        for (c in 0 until n) {
            val fw = fW[c]
            val bw = bW[c]
            val tw = max(fw + bw, 1e-6f)
            // Plain local mean, used when one side has no support at all.
            val mr = (fR[c] + bR[c]) / tw
            val mg = (fG[c] + bG[c]) / tw
            val mb = (fB[c] + bB[c]) / tw
            if (fw > 1e-3f * tw) { fR[c] /= fw; fG[c] /= fw; fB[c] /= fw } else { fR[c] = mr; fG[c] = mg; fB[c] = mb }
            if (bw > 1e-3f * tw) { bR[c] /= bw; bG[c] /= bw; bB[c] /= bw } else { bR[c] = mr; bG[c] = mg; bB[c] = mb }
        }

        val f1 = IntArray(nEdge)
        val b1 = IntArray(nEdge)
        val invF = 1f / f
        val maxGx = (sw - 1).toFloat()
        val maxGy = (sh - 1).toFloat()
        for (e in 0 until nEdge) {
            val i = edges[e]
            val x = i % w
            val y = i / w
            // Bilinear sample of the coarse grids at this pixel's centre.
            val gx = ((x + 0.5f) * invF - 0.5f).coerceIn(0f, maxGx)
            val gy = ((y + 0.5f) * invF - 0.5f).coerceIn(0f, maxGy)
            val x0 = gx.toInt(); val y0 = gy.toInt()
            val x1 = min(x0 + 1, sw - 1); val y1 = min(y0 + 1, sh - 1)
            val tx = gx - x0; val ty = gy - y0
            val c00 = y0 * sw + x0; val c10 = y0 * sw + x1
            val c01 = y1 * sw + x0; val c11 = y1 * sw + x1
            val w00 = (1f - tx) * (1f - ty); val w10 = tx * (1f - ty)
            val w01 = (1f - tx) * ty; val w11 = tx * ty
            val fr = sample4(fR, c00, c10, c01, c11, w00, w10, w01, w11)
            val fg = sample4(fG, c00, c10, c01, c11, w00, w10, w01, w11)
            val fb = sample4(fB, c00, c10, c01, c11, w00, w10, w01, w11)
            val br = sample4(bR, c00, c10, c01, c11, w00, w10, w01, w11)
            val bg = sample4(bG, c00, c10, c01, c11, w00, w10, w01, w11)
            val bb = sample4(bB, c00, c10, c01, c11, w00, w10, w01, w11)

            val a = alpha[i]
            val ia = 1f - a
            val p = pixels[i]
            val resR = ((p shr 16) and 0xFF) - a * fr - ia * br
            val resG = ((p shr 8) and 0xFF) - a * fg - ia * bg
            val resB = (p and 0xFF) - a * fb - ia * bb
            f1[e] = packRgb(fr + a * resR, fg + a * resG, fb + a * resB)
            b1[e] = packRgb(br + ia * resR, bg + ia * resG, bb + ia * resB)
        }

        // ---- Level 2: fine full-res window around each edge pixel ----
        // Neighbours with a == 1 have F = I and neighbours with a == 0 have B = I (the level-1
        // update is the identity there), so only edge neighbours need the f1/b1 lookups.
        // pixels[] is only read here for non-edge neighbours and for the centre itself, so the
        // centre can be overwritten in place.
        val r = FINE_RADIUS
        for (e in 0 until nEdge) {
            val i = edges[e]
            val x = i % w
            val y = i / w
            var sfr = 0f; var sfg = 0f; var sfb = 0f; var sfw = 0f
            var sbr = 0f; var sbg = 0f; var sbb = 0f; var sbw = 0f
            val xStart = max(0, x - r)
            val xEnd = min(w - 1, x + r)
            for (yy in max(0, y - r)..min(h - 1, y + r)) {
                var j = yy * w + xStart
                for (xx in xStart..xEnd) {
                    val aj = alpha[j]
                    if (aj >= 1f) {
                        val q = pixels[j]
                        sfr += (q shr 16) and 0xFF; sfg += (q shr 8) and 0xFF; sfb += q and 0xFF
                        sfw += 1f
                    } else if (aj <= 0f) {
                        val q = pixels[j]
                        sbr += (q shr 16) and 0xFF; sbg += (q shr 8) and 0xFF; sbb += q and 0xFF
                        sbw += 1f
                    } else {
                        val ej = edgeMap[j]
                        val fq = f1[ej]
                        val bq = b1[ej]
                        val ij = 1f - aj
                        sfr += ((fq shr 16) and 0xFF) * aj
                        sfg += ((fq shr 8) and 0xFF) * aj
                        sfb += (fq and 0xFF) * aj
                        sfw += aj
                        sbr += ((bq shr 16) and 0xFF) * ij
                        sbg += ((bq shr 8) and 0xFF) * ij
                        sbb += (bq and 0xFF) * ij
                        sbw += ij
                    }
                    j++
                }
            }
            // sfw and sbw are > 0: the centre itself is an edge pixel and contributes to both.
            val fr = sfr / sfw; val fg = sfg / sfw; val fb = sfb / sfw
            val br = sbr / sbw; val bg = sbg / sbw; val bb = sbb / sbw
            val a = alpha[i]
            val ia = 1f - a
            val p = pixels[i]
            val resR = ((p shr 16) and 0xFF) - a * fr - ia * br
            val resG = ((p shr 8) and 0xFF) - a * fg - ia * bg
            val resB = (p and 0xFF) - a * fb - ia * bb
            pixels[i] = (p and 0xFF000000.toInt()) or packRgb(fr + a * resR, fg + a * resG, fb + a * resB)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Portrait blur / backdrops
    // ---------------------------------------------------------------------------------------

    /**
     * Small (~512 px) copy of the photo with the subject removed: subject pixels are filled with
     * the surrounding background colours. Blurring this instead of the photo stops the subject's
     * colours from bleeding into the bokeh (no ghost halo). Compute once per mask and reuse for
     * every blur strength.
     */
    fun createBackgroundPlate(original: Bitmap, mask: SubjectMask): Bitmap {
        val w = mask.width
        val h = mask.height
        val pixels = readPixels(original, w, h)
        val alpha = mask.alpha
        val f = gridFactor(w, h, PLATE_PX)
        val sw = ceilDiv(w, f)
        val sh = ceilDiv(h, f)
        val n = sw * sh
        val r = FloatArray(n); val g = FloatArray(n); val b = FloatArray(n)
        val wsum = FloatArray(n)
        val cnt = IntArray(n)
        for (y in 0 until h) {
            val rowCell = (y / f) * sw
            val row = y * w
            for (x in 0 until w) {
                val i = row + x
                val c = rowCell + x / f
                // Weight by (1-a)² so pixels that are even partly subject barely count.
                val ia = 1f - alpha[i]
                val bw = ia * ia
                val p = pixels[i]
                r[c] += ((p shr 16) and 0xFF) * bw
                g[c] += ((p shr 8) and 0xFF) * bw
                b[c] += (p and 0xFF) * bw
                wsum[c] += bw
                cnt[c]++
            }
        }
        for (c in 0 until n) {
            val ws = wsum[c]
            if (ws > 1e-4f) { r[c] /= ws; g[c] /= ws; b[c] /= ws }
            wsum[c] = if (cnt[c] > 0) ws / cnt[c] else 0f // clean-background fraction of the cell
        }
        // Erode the valid area by one cell (min over 3x3) so colours right at the subject
        // boundary are not used, then map to a 0..1 validity weight.
        val wt = FloatArray(n)
        for (y in 0 until sh) {
            for (x in 0 until sw) {
                var m = 1f
                for (yy in max(0, y - 1)..min(sh - 1, y + 1)) {
                    for (xx in max(0, x - 1)..min(sw - 1, x + 1)) m = min(m, wsum[yy * sw + xx])
                }
                val t = ((m - 0.35f) / 0.55f).coerceIn(0f, 1f)
                wt[y * sw + x] = t * t * (3f - 2f * t)
            }
        }
        pushPullFill(r, g, b, wt, sw, sh)
        val out = IntArray(n)
        for (c in 0 until n) out[c] = 0xFF000000.toInt() or packRgb(r[c], g[c], b[c])
        return Bitmap.createBitmap(out, sw, sh, Bitmap.Config.ARGB_8888)
    }

    /**
     * Portrait bokeh: blurs the subject-free [plate] (from [createBackgroundPlate]), scales it
     * to the cutout size and draws the sharp, decontaminated [foreground] over it. The cutout's
     * alpha does the blending, so there is no sharp background halo and no blurred subject edge.
     */
    fun createPortraitBokeh(plate: Bitmap, foreground: Bitmap, blurRadius: Int): Bitmap {
        val pw = plate.width
        val ph = plate.height
        // Slider 1..50 → blur sigma of 0.06%..3% of the long edge (3 box passes ≈ Gaussian,
        // sigma ≈ R). 25 (default) ≈ 1.5%, close to a phone's portrait mode: background
        // shapes stay readable but soft. The old mapping (≈8% at 25) washed it into a gradient.
        val radius = (blurRadius * 0.0006f * max(pw, ph)).roundToInt()
        val blurred = if (radius >= 1) {
            val n = pw * ph
            val px = IntArray(n)
            plate.getPixels(px, 0, pw, 0, 0, pw, ph)
            val r = FloatArray(n); val g = FloatArray(n); val b = FloatArray(n)
            for (i in 0 until n) {
                val p = px[i]
                r[i] = ((p shr 16) and 0xFF).toFloat()
                g[i] = ((p shr 8) and 0xFF).toFloat()
                b[i] = (p and 0xFF).toFloat()
            }
            val tmp = FloatArray(n)
            repeat(3) { // three box passes ≈ Gaussian
                boxBlur(r, tmp, pw, ph, radius)
                boxBlur(g, tmp, pw, ph, radius)
                boxBlur(b, tmp, pw, ph, radius)
            }
            for (i in 0 until n) px[i] = 0xFF000000.toInt() or packRgb(r[i], g[i], b[i])
            Bitmap.createBitmap(px, pw, ph, Bitmap.Config.ARGB_8888)
        } else {
            plate
        }

        val w = foreground.width
        val h = foreground.height
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG) // bilinear upscale of the blurred plate
        canvas.drawBitmap(blurred, null, Rect(0, 0, w, h), paint)
        canvas.drawBitmap(foreground, 0f, 0f, paint)
        if (blurred !== plate) blurred.recycle()
        return result
    }

    /** The cutout drawn over a light/grey checkerboard, the usual way to show transparency. */
    fun withCheckerboard(foreground: Bitmap): Bitmap {
        val w = foreground.width
        val h = foreground.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val cell = maxOf(8, maxOf(w, h) / 48)
        val tile = Bitmap.createBitmap(cell * 2, cell * 2, Bitmap.Config.ARGB_8888)
        Canvas(tile).apply {
            drawColor(0xFF3A3F47.toInt())
            val p = Paint().apply { color = 0xFF2A2E35.toInt() }
            drawRect(0f, 0f, cell.toFloat(), cell.toFloat(), p)
            drawRect(cell.toFloat(), cell.toFloat(), cell * 2f, cell * 2f, p)
        }
        val shaderPaint = Paint().apply {
            shader = BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), shaderPaint)
        canvas.drawBitmap(foreground, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        shaderPaint.shader = null
        tile.recycle()
        return out
    }

    fun composeWithBackground(foreground: Bitmap, background: Bitmap): Bitmap {
        val result = Bitmap.createBitmap(foreground.width, foreground.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(background, null, Rect(0, 0, foreground.width, foreground.height), paint)
        canvas.drawBitmap(foreground, 0f, 0f, paint)
        return result
    }

    /** Decontaminated cutout over a solid colour; the cutout's alpha does the blending. */
    fun composeWithColor(foreground: Bitmap, color: Int): Bitmap {
        val result = Bitmap.createBitmap(foreground.width, foreground.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(color)
        canvas.drawBitmap(foreground, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        return result
    }

    // ---------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------

    /**
     * Push-pull hole filling: [wt] (0..1) says how much each pixel's colour can be trusted.
     * Push builds a half-size level from weighted 2x2 averages (weight capped at 1) until 1x1;
     * pull fills each level's missing share from the completed coarser level, bilinearly
     * upsampled: C' = wt·C + (1-wt)·up(C_coarse). Result: smooth fill everywhere.
     */
    private fun pushPullFill(r: FloatArray, g: FloatArray, b: FloatArray, wt: FloatArray, w: Int, h: Int) {
        if (w <= 1 && h <= 1) return
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        val cn = cw * ch
        val cr = FloatArray(cn); val cg = FloatArray(cn); val cb = FloatArray(cn); val cwt = FloatArray(cn)
        for (y in 0 until h) {
            val rowCell = (y / 2) * cw
            val row = y * w
            for (x in 0 until w) {
                val i = row + x
                val c = rowCell + x / 2
                val wv = wt[i]
                cr[c] += r[i] * wv; cg[c] += g[i] * wv; cb[c] += b[i] * wv
                cwt[c] += wv
            }
        }
        for (c in 0 until cn) {
            val wv = cwt[c]
            if (wv > 1e-6f) { cr[c] /= wv; cg[c] /= wv; cb[c] /= wv }
            cwt[c] = min(1f, wv)
        }
        pushPullFill(cr, cg, cb, cwt, cw, ch)

        val maxGx = (cw - 1).toFloat()
        val maxGy = (ch - 1).toFloat()
        for (y in 0 until h) {
            val gy = ((y + 0.5f) * 0.5f - 0.5f).coerceIn(0f, maxGy)
            val y0 = gy.toInt(); val y1 = min(y0 + 1, ch - 1); val ty = gy - y0
            for (x in 0 until w) {
                val i = y * w + x
                val wv = wt[i]
                if (wv >= 1f) continue
                val gx = ((x + 0.5f) * 0.5f - 0.5f).coerceIn(0f, maxGx)
                val x0 = gx.toInt(); val x1 = min(x0 + 1, cw - 1); val tx = gx - x0
                val c00 = y0 * cw + x0; val c10 = y0 * cw + x1
                val c01 = y1 * cw + x0; val c11 = y1 * cw + x1
                val w00 = (1f - tx) * (1f - ty); val w10 = tx * (1f - ty)
                val w01 = (1f - tx) * ty; val w11 = tx * ty
                val iw = 1f - wv
                r[i] = r[i] * wv + sample4(cr, c00, c10, c01, c11, w00, w10, w01, w11) * iw
                g[i] = g[i] * wv + sample4(cg, c00, c10, c01, c11, w00, w10, w01, w11) * iw
                b[i] = b[i] * wv + sample4(cb, c00, c10, c01, c11, w00, w10, w01, w11) * iw
            }
        }
    }

    /**
     * One box-blur pass of radius [r] (edge pixels clamped), in place; [tmp] must be the same
     * size. Running sums make it O(pixels) regardless of radius. The vertical pass keeps one
     * running sum per column and walks rows, so memory access stays sequential.
     */
    private fun boxBlur(data: FloatArray, tmp: FloatArray, w: Int, h: Int, r: Int) {
        if (r <= 0 || w == 0 || h == 0) return
        val inv = 1f / (2 * r + 1)
        val lastX = w - 1
        val lastY = h - 1
        for (y in 0 until h) {
            val row = y * w
            var sum = 0f
            for (k in -r..r) sum += data[row + k.coerceIn(0, lastX)]
            for (x in 0 until w) {
                tmp[row + x] = sum * inv
                sum += data[row + min(lastX, x + r + 1)] - data[row + max(0, x - r)]
            }
        }
        val col = FloatArray(w)
        for (k in -r..r) {
            val row = k.coerceIn(0, lastY) * w
            for (x in 0 until w) col[x] += tmp[row + x]
        }
        for (y in 0 until h) {
            val row = y * w
            val add = min(lastY, y + r + 1) * w
            val sub = max(0, y - r) * w
            for (x in 0 until w) {
                data[row + x] = col[x] * inv
                col[x] += tmp[add + x] - tmp[sub + x]
            }
        }
    }

    private fun sample4(
        arr: FloatArray, c00: Int, c10: Int, c01: Int, c11: Int,
        w00: Float, w10: Float, w01: Float, w11: Float
    ): Float = arr[c00] * w00 + arr[c10] * w10 + arr[c01] * w01 + arr[c11] * w11

    private fun resizeBilinear(src: FloatArray, sw: Int, sh: Int, dw: Int, dh: Int): FloatArray {
        val out = FloatArray(dw * dh)
        if (sw <= 0 || sh <= 0) return out
        val sx = sw.toFloat() / dw
        val sy = sh.toFloat() / dh
        val maxX = (sw - 1).toFloat()
        val maxY = (sh - 1).toFloat()
        for (y in 0 until dh) {
            val fy = ((y + 0.5f) * sy - 0.5f).coerceIn(0f, maxY)
            val y0 = fy.toInt(); val y1 = min(y0 + 1, sh - 1); val ty = fy - y0
            for (x in 0 until dw) {
                val fx = ((x + 0.5f) * sx - 0.5f).coerceIn(0f, maxX)
                val x0 = fx.toInt(); val x1 = min(x0 + 1, sw - 1); val tx = fx - x0
                val top = src[y0 * sw + x0] + (src[y0 * sw + x1] - src[y0 * sw + x0]) * tx
                val bot = src[y1 * sw + x0] + (src[y1 * sw + x1] - src[y1 * sw + x0]) * tx
                out[y * dw + x] = top + (bot - top) * ty
            }
        }
        return out
    }

    /** Pixels of [src] at exactly w x h (rescaled through a temporary bitmap if needed). */
    private fun readPixels(src: Bitmap, w: Int, h: Int): IntArray {
        val pixels = IntArray(w * h)
        if (src.width == w && src.height == h) {
            src.getPixels(pixels, 0, w, 0, 0, w, h)
        } else {
            val scaled = Bitmap.createScaledBitmap(src, w, h, true)
            scaled.getPixels(pixels, 0, w, 0, 0, w, h)
            if (scaled !== src) scaled.recycle()
        }
        return pixels
    }

    private fun packRgb(r: Float, g: Float, b: Float): Int {
        val ri = (r + 0.5f).toInt().coerceIn(0, 255)
        val gi = (g + 0.5f).toInt().coerceIn(0, 255)
        val bi = (b + 0.5f).toInt().coerceIn(0, 255)
        return (ri shl 16) or (gi shl 8) or bi
    }

    private fun ceilDiv(a: Int, b: Int): Int = (a + b - 1) / b

    /** Integer downscale factor so the long edge is at most [target]. */
    private fun gridFactor(w: Int, h: Int, target: Int): Int = max(1, ceilDiv(max(w, h), target))
}
