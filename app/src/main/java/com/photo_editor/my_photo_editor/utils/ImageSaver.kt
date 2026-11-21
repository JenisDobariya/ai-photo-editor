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

        uri?.let {
            contentResolver.openOutputStream(it)?.use { outputStream: OutputStream ->
                bitmap.compress(format, quality, outputStream)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                contentResolver.update(it, contentValues, null, null)
            }
        }

        return uri
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

        uri?.let {
            contentResolver.openOutputStream(it)?.use { outputStream: OutputStream ->
                editedBitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                contentResolver.update(it, contentValues, null, null)
            }
        }

        return uri
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

        // 2. Prepare Result Bitmap
        val result = Bitmap.createBitmap(sourceBitmap.width, sourceBitmap.height, sourceBitmap.config ?: Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
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

        // 5. Draw to canvas with matrix
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
