package com.photo_editor.my_photo_editor.utils

import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.photo_editor.my_photo_editor.model.EditorLayer
import com.photo_editor.my_photo_editor.model.LayerContent
import java.io.OutputStream

object ImageSaver {
    fun saveDirectBitmapToMediaStore(
        context: Context,
        bitmap: Bitmap,
        filenamePrefix: String = "COLLAGE",
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG,
        quality: Int = 100
    ): Uri? {
        val extension = if (format == Bitmap.CompressFormat.PNG) "png" else "jpg"
        val mimeType = if (format == Bitmap.CompressFormat.PNG) "image/png" else "image/jpeg"
        val filename = "${filenamePrefix}_${System.currentTimeMillis()}.$extension"

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/PhotoEditor")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val contentResolver = context.contentResolver
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

        return writeOrDelete(context, uri) { out -> bitmap.compress(format, quality, out) }
    }

    /**
     * Writes the image and publishes it. If anything fails the half-written MediaStore row is
     * deleted (otherwise it stays hidden with IS_PENDING=1, or shows as an empty file) and
     * null is returned so the caller can show an error.
     */
    private fun writeOrDelete(context: Context, uri: Uri?, write: (OutputStream) -> Boolean): Uri? {
        uri ?: return null
        val resolver = context.contentResolver
        return try {
            val ok = resolver.openOutputStream(uri)?.use { write(it) } ?: false
            if (!ok) throw java.io.IOException("Image encoding failed")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                resolver.update(uri, done, null, null)
            }
            uri
        } catch (e: Exception) {
            e.printStackTrace()
            try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            null
        }
    }

    fun saveBitmapToMediaStore(
        context: Context,
        originalBitmap: Bitmap,
        colorMatrix: ColorMatrix,
        rotation: Float = 0f,
        rotationX: Float = 0f,
        rotationY: Float = 0f,
        flipHorizontal: Boolean = false,
        flipVertical: Boolean = false,
        cropRect: RectF? = null,
        drawMarkup: ((Canvas) -> Unit)? = null,
        layers: List<EditorLayer>? = null
    ): Uri? {
        val editedBitmap = applyAdjustments(
            originalBitmap, colorMatrix, rotation, rotationX, rotationY,
            flipHorizontal, flipVertical, cropRect, drawMarkup, layers
        )

        val filename = "IMG_${System.currentTimeMillis()}.jpg"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/PhotoEditor")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val contentResolver = context.contentResolver
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

        return writeOrDelete(context, uri) { out -> editedBitmap.compress(Bitmap.CompressFormat.JPEG, 100, out) }
    }

    private fun applyAdjustments(
        bitmap: Bitmap,
        colorMatrix: ColorMatrix,
        rotation: Float,
        rotationX: Float = 0f,
        rotationY: Float = 0f,
        flipHorizontal: Boolean,
        flipVertical: Boolean,
        cropRect: RectF? = null,
        drawMarkup: ((Canvas) -> Unit)? = null,
        layers: List<EditorLayer>? = null
    ): Bitmap {
        var sourceBitmap = bitmap
        
        // 1. Handle Crop
        if (cropRect != null) {
            val left = (cropRect.left * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
            val top = (cropRect.top * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
            val right = (cropRect.right * bitmap.width).toInt().coerceIn(left + 1, bitmap.width)
            val bottom = (cropRect.bottom * bitmap.height).toInt().coerceIn(top + 1, bitmap.height)
            
            val w = right - left
            val h = bottom - top
            
            if (w > 0 && h > 0) {
                sourceBitmap = Bitmap.createBitmap(bitmap, left, top, w, h)
            }
        }

        // 2. Build the transform first: the output size depends on the rotation.
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        paint.colorFilter = ColorMatrixColorFilter(colorMatrix)

        val matrix = Matrix()
        
        // 3. 3D Rotation using Camera
        val limitedX = rotationX.coerceIn(-89f, 89f)
        val limitedY = rotationY.coerceIn(-89f, 89f)

        if (limitedX != 0f || limitedY != 0f) {
            val camera = Camera()
            camera.save()
            camera.rotateX(limitedX)
            camera.rotateY(limitedY)
            camera.getMatrix(matrix)
            camera.restore()
            
            matrix.preTranslate(-sourceBitmap.width / 2f, -sourceBitmap.height / 2f)
            matrix.postTranslate(sourceBitmap.width / 2f, sourceBitmap.height / 2f)
        }
        
        // 4. 2D Rotation and Flip
        val sx = if (flipHorizontal) -1f else 1f
        val sy = if (flipVertical) -1f else 1f
        if (flipHorizontal || flipVertical) {
            matrix.postScale(sx, sy, sourceBitmap.width / 2f, sourceBitmap.height / 2f)
        }
        
        if (rotation != 0f) {
            matrix.postRotate(rotation, sourceBitmap.width / 2f, sourceBitmap.height / 2f)
        }

        // 5. A rotated photo gets a frame that fits it (a 90° turn swaps width and height),
        //    like the preview, instead of being cut off inside the original frame.
        var outWidth = sourceBitmap.width
        var outHeight = sourceBitmap.height
        if (rotation % 360f != 0f) {
            val bounds = RectF(0f, 0f, sourceBitmap.width.toFloat(), sourceBitmap.height.toFloat())
            matrix.mapRect(bounds)
            outWidth = Math.round(bounds.width()).coerceAtLeast(1)
            outHeight = Math.round(bounds.height()).coerceAtLeast(1)
            matrix.postTranslate(-bounds.left, -bounds.top)
        }
        val config = sourceBitmap.config?.takeIf {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.O || it != Bitmap.Config.HARDWARE
        } ?: Bitmap.Config.ARGB_8888
        val result = Bitmap.createBitmap(outWidth, outHeight, config)
        val canvas = Canvas(result)
        canvas.drawBitmap(sourceBitmap, matrix, paint)
        
        // 6. Draw Markup & Layers on top
        if (drawMarkup != null) {
            drawMarkup.invoke(canvas)
        } else {
            // Fallback: Draw Text Layers directly if no canvas drawer was provided
            layers?.forEach { layer ->
                val textContent = layer.content as? LayerContent.Text ?: return@forEach
                val displayText = if (textContent.isAllCaps) textContent.text.uppercase() else textContent.text
                val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = textContent.color
                    alpha = (textContent.opacity * 255).toInt().coerceIn(0, 255)
                    textSize = textContent.fontSize * 2.5f
                    letterSpacing = textContent.letterSpacing
                    isFakeBoldText = textContent.isBold
                    textSkewX = if (textContent.isItalic) -0.25f else 0f
                    if (textContent.shadowRadius > 0f) {
                        setShadowLayer(textContent.shadowRadius * 2.5f, textContent.shadowDx * 2.5f, textContent.shadowDy * 2.5f, textContent.shadowColor)
                    } else if (textContent.shadowColor != Color.TRANSPARENT) {
                        setShadowLayer(6f, 3f, 3f, textContent.shadowColor)
                    }
                }
                canvas.drawText(displayText, layer.x, layer.y, textPaint)
            }
        }

        if (sourceBitmap != bitmap) {
            sourceBitmap.recycle()
        }
        
        return result
    }
}
