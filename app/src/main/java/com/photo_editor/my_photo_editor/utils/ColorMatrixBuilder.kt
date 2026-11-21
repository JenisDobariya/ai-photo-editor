package com.photo_editor.my_photo_editor.utils

import android.graphics.ColorMatrix

object ColorMatrixBuilder {
    fun build(
        brightness: Float = 0f,
        contrast: Float = 1f,
        saturation: Float = 1f,
        exposure: Float = 0f,
        temperature: Float = 0f,
        tint: Float = 0f,
        shadows: Float = 0f,
        highlights: Float = 0f,
        skinTone: Float = 0f,
        skinTexture: Float = 0f,
        cheeks: Float = 0f,
        eyeSize: Float = 0f,
        filterMatrix: ColorMatrix? = null,
        filterIntensity: Float = 1f
    ): ColorMatrix {
        val matrix = ColorMatrix()

        // 1. Filter Matrix with Intensity
        if (filterMatrix != null) {
            if (filterIntensity == 1f) {
                matrix.postConcat(filterMatrix)
            } else {
                val interpolated = interpolate(ColorMatrix(), filterMatrix, filterIntensity)
                matrix.postConcat(interpolated)
            }
        }

        // 2. Exposure
        if (exposure != 0f) {
            val e = Math.pow(2.0, exposure.toDouble()).toFloat()
            matrix.postConcat(ColorMatrix(
                floatArrayOf(
                    e, 0f, 0f, 0f, 0f,
                    0f, e, 0f, 0f, 0f,
                    0f, 0f, e, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )
            ))
        }

        // 3. Temperature
        if (temperature != 0f) {
            val t = temperature * 50f
            matrix.postConcat(ColorMatrix(
                floatArrayOf(
                    1f, 0f, 0f, 0f, t,
                    0f, 1f, 0f, 0f, 0f,
                    0f, 0f, 1f, 0f, -t,
                    0f, 0f, 0f, 1f, 0f
                )
            ))
        }

        // 4. Tint
        if (tint != 0f) {
            val t = tint * 50f
            matrix.postConcat(ColorMatrix(
                floatArrayOf(
                    1f, 0f, 0f, 0f, t,
                    0f, 1f, 0f, 0f, -t,
                    0f, 0f, 1f, 0f, t,
                    0f, 0f, 0f, 1f, 0f
                )
            ))
        }

        // 5. Brightness
        if (brightness != 0f) {
            val b = brightness * 255f
            matrix.postConcat(ColorMatrix(
                floatArrayOf(
                    1f, 0f, 0f, 0f, b,
                    0f, 1f, 0f, 0f, b,
                    0f, 0f, 1f, 0f, b,
                    0f, 0f, 0f, 1f, 0f
                )
            ))
        }

        // 6. Contrast
        if (contrast != 1f) {
            val t = (1f - contrast) / 2f * 255f
            matrix.postConcat(ColorMatrix(
                floatArrayOf(
                    contrast, 0f, 0f, 0f, t,
                    0f, contrast, 0f, 0f, t,
                    0f, 0f, contrast, 0f, t,
                    0f, 0f, 0f, 1f, 0f
                )
            ))
        }

        // 7. Saturation
        if (saturation != 1f) {
            val satMatrix = ColorMatrix()
            satMatrix.setSaturation(saturation)
            matrix.postConcat(satMatrix)
        }

        // 8. Shadows & Highlights
        if (shadows != 0f || highlights != 0f) {
            val s = shadows * 20f
            val h = highlights * 20f
            matrix.postConcat(ColorMatrix(
                floatArrayOf(
                    1f, 0f, 0f, 0f, s + h,
                    0f, 1f, 0f, 0f, s + h,
                    0f, 0f, 1f, 0f, s + h,
                    0f, 0f, 0f, 1f, 0f
                )
            ))
        }

        // 9. Retouch Effects (Skin Tone, Cheeks, Skin Texture)
        if (skinTone != 0f || cheeks != 0f || skinTexture != 0f) {
            val rAdd = (skinTone * 15f) + (cheeks * 20f)
            val gAdd = skinTone * 5f
            val bAdd = -(skinTone * 5f)
            matrix.postConcat(ColorMatrix(
                floatArrayOf(
                    1f, 0f, 0f, 0f, rAdd,
                    0f, 1f, 0f, 0f, gAdd,
                    0f, 0f, 1f, 0f, bAdd,
                    0f, 0f, 0f, 1f, 0f
                )
            ))
        }

        if (skinTexture != 0f) {
            val c = 1f - (skinTexture * 0.1f)
            val t = (1f - c) * 128f
            matrix.postConcat(ColorMatrix(
                floatArrayOf(
                    c, 0f, 0f, 0f, t,
                    0f, c, 0f, 0f, t,
                    0f, 0f, c, 0f, t,
                    0f, 0f, 0f, 1f, 0f
                )
            ))
        }

        return matrix
    }

    private fun interpolate(m1: ColorMatrix, m2: ColorMatrix, fraction: Float): ColorMatrix {
        val v1 = m1.array
        val v2 = m2.array
        val result = FloatArray(20)
        for (i in 0 until 20) {
            result[i] = v1[i] + (v2[i] - v1[i]) * fraction
        }
        return ColorMatrix(result)
    }
    
    private fun ColorMatrix.postConcat(other: ColorMatrix) {
        val current = ColorMatrix(this)
        this.setConcat(current, other)
    }
}
