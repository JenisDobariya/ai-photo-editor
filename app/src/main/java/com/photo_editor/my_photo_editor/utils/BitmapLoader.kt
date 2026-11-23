package com.photo_editor.my_photo_editor.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.util.Size

/**
 * Shared, memory-safe bitmap decoding for the smart tools.
 * Every function returns software (non-HARDWARE) ARGB_8888 bitmaps, safe to draw into a Canvas.
 */
object BitmapLoader {

    /**
     * Decodes the photo upright (EXIF orientation applied, as the gallery shows it) and no
     * bigger than [maxDim] on its long edge, so 50–200 MP photos don't run out of memory.
     */
    fun decodeUpright(context: Context, uri: Uri, maxDim: Int): Bitmap? = try {
        decodeUprightOrThrow(context, uri, maxDim)
    } catch (e: Exception) {
        e.printStackTrace()
        null
    } catch (e: OutOfMemoryError) {
        null
    }

    private fun decodeUprightOrThrow(context: Context, uri: Uri, maxDim: Int): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        var bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val longEdge = maxOf(bitmap.width, bitmap.height)
        if (longEdge > maxDim) {
            val scale = maxDim.toFloat() / longEdge
            val scaled = Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true
            )
            if (scaled !== bitmap) bitmap.recycle()
            bitmap = scaled
        }
        val matrix = exifMatrix(context, uri) ?: return bitmap
        val upright = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (upright !== bitmap) bitmap.recycle()
        return upright
    }

    /** Rotation/flip needed to show the photo upright, or null when it already is. */
    fun exifMatrix(context: Context, uri: Uri): Matrix? {
        val orientation = try {
            context.contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return null
        }
        return m
    }

    /**
     * Small upright preview (about [size] px on the long edge). Uses the system thumbnail cache
     * on Android 10+ (fast, already rotated) and falls back to a sampled decode.
     */
    fun loadSmall(context: Context, uri: Uri, size: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val thumb = context.contentResolver.loadThumbnail(uri, Size(size, size), null)
                return if (thumb.config == Bitmap.Config.ARGB_8888) thumb
                else thumb.copy(Bitmap.Config.ARGB_8888, false).also { thumb.recycle() }
            } catch (_: Exception) {
                // fall through to a normal decode
            }
        }
        return decodeUpright(context, uri, size)
    }
}
