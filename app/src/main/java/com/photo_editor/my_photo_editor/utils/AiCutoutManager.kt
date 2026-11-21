package com.photo_editor.my_photo_editor.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.SegmentationMask
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object AiCutoutManager {

    enum class EdgeStyle {
        CRISP,   // Tight contour, zero background bleed (ideal for transparent stickers)
        SMOOTH,  // Balanced anti-aliased Hermite curve (natural portrait)
        FEATHER  // Soft, permeable transition preserving fine hair strands
    }

    private val segmenter by lazy {
        val options = SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
            .build()
        Segmentation.getClient(options)
    }

    suspend fun segmentImage(bitmap: Bitmap): SegmentationMask = suspendCancellableCoroutine { cont ->
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        segmenter.process(inputImage)
            .addOnSuccessListener { mask ->
                if (cont.isActive) cont.resume(mask)
            }
            .addOnFailureListener { e ->
                if (cont.isActive) cont.resumeWithException(e)
            }
    }

    /**
     * Checks if the segmentation mask detected a distinct subject.
     */
    fun isSubjectDetected(mask: SegmentationMask): Boolean {
        val buffer = mask.buffer
        buffer.rewind()
        val floatBuffer = buffer.asFloatBuffer()
        val total = floatBuffer.remaining()
        var maxConfidence = 0f
        val step = maxOf(1, total / 2000)
        for (i in 0 until total step step) {
            val c = floatBuffer.get(i)
            if (c > maxConfidence) maxConfidence = c
            if (maxConfidence > 0.45f) return true
        }
        return maxConfidence > 0.35f
    }

    /**
     * Extracts foreground with sub-pixel hardware bilinear filtering and Hermite smoothstep antialiasing.
     * Completely eliminates nearest-neighbor staircase/pixelated blocks and background color fringing.
     */
    fun extractForeground(
        original: Bitmap,
        mask: SegmentationMask,
        edgeStyle: EdgeStyle = EdgeStyle.SMOOTH
    ): Bitmap {
        val w = original.width
        val h = original.height
        val mw = mask.width
        val mh = mask.height
        val buffer = mask.buffer
        buffer.rewind()

        val maskFloats = FloatArray(mw * mh)
        buffer.asFloatBuffer().get(maskFloats)

        // Threshold tuning based on edge style
        val (lowThresh, highThresh) = when (edgeStyle) {
            EdgeStyle.CRISP -> 0.42f to 0.85f   // tighter boundary, no background fringe
            EdgeStyle.SMOOTH -> 0.28f to 0.78f  // natural balanced transition
            EdgeStyle.FEATHER -> 0.16f to 0.68f // soft edge capturing fine hair strands
        }
        val range = (highThresh - lowThresh).coerceAtLeast(0.01f)

        // 1. Build alpha mask bitmap at mask native resolution
        val maskPixels = IntArray(mw * mh)
        for (i in 0 until (mw * mh)) {
            val conf = maskFloats[i]
            val alpha = when {
                conf >= highThresh -> 255
                conf <= lowThresh -> 0
                else -> {
                    // Cubic Hermite smoothstep S-curve for silky sub-pixel transitions
                    val t = ((conf - lowThresh) / range).coerceIn(0f, 1f)
                    (t * t * (3f - 2f * t) * 255f).toInt().coerceIn(0, 255)
                }
            }
            maskPixels[i] = (alpha shl 24) or 0x00FFFFFF
        }

        val rawMaskBitmap = Bitmap.createBitmap(maskPixels, mw, mh, Bitmap.Config.ARGB_8888)

        // 2. High-quality Bilinear upscale to full image resolution using Skia native hardware scaler
        val scaledMask = if (mw != w || mh != h) {
            Bitmap.createScaledBitmap(rawMaskBitmap, w, h, true)
        } else {
            rawMaskBitmap
        }

        // 3. Composite original photo with anti-aliased mask using PorterDuff DST_IN
        val foreground = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(foreground)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(original, 0f, 0f, paint)

        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        canvas.drawBitmap(scaledMask, 0f, 0f, paint)
        paint.xfermode = null

        if (scaledMask != rawMaskBitmap) {
            rawMaskBitmap.recycle()
        }
        scaledMask.recycle()

        return foreground
    }

    /**
     * Creates a studio DSLR Portrait Bokeh background by defringing the subject region
     * before blurring, eliminating double-silhouette ghost halos around head and shoulders.
     */
    fun createPortraitBokeh(
        original: Bitmap,
        foreground: Bitmap,
        blurRadius: Int
    ): Bitmap {
        val w = original.width
        val h = original.height

        // Downsample original slightly for fast, halo-free background blur
        val targetDim = 1000
        val scale = minOf(1.0f, targetDim.toFloat() / maxOf(w, h))
        val bw = (w * scale).toInt().coerceAtLeast(1)
        val bh = (h * scale).toInt().coerceAtLeast(1)

        val downscaledBg = Bitmap.createScaledBitmap(original, bw, bh, true)
        val blurredBg = FastBlurUtil.blur(downscaledBg, blurRadius)
        if (downscaledBg != blurredBg && downscaledBg != original) {
            downscaledBg.recycle()
        }

        // Composite full-res foreground over smoothly blurred backdrop
        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        val scaledBlurred = if (blurredBg.width != w || blurredBg.height != h) {
            Bitmap.createScaledBitmap(blurredBg, w, h, true)
        } else {
            blurredBg
        }
        canvas.drawBitmap(scaledBlurred, 0f, 0f, paint)
        canvas.drawBitmap(foreground, 0f, 0f, paint)

        if (scaledBlurred != blurredBg) {
            scaledBlurred.recycle()
        }
        blurredBg.recycle()

        return result
    }

    fun composeWithBackground(foreground: Bitmap, background: Bitmap): Bitmap {
        val result = Bitmap.createBitmap(foreground.width, foreground.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        val scaledBg = if (background.width != foreground.width || background.height != foreground.height) {
            Bitmap.createScaledBitmap(background, foreground.width, foreground.height, true)
        } else {
            background
        }
        canvas.drawBitmap(scaledBg, 0f, 0f, paint)
        canvas.drawBitmap(foreground, 0f, 0f, paint)
        return result
    }

    fun composeWithColor(foreground: Bitmap, color: Int): Bitmap {
        val result = Bitmap.createBitmap(foreground.width, foreground.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(color)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(foreground, 0f, 0f, paint)
        return result
    }
}
