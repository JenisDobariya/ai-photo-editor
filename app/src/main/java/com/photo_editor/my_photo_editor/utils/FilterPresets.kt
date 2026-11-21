package com.photo_editor.my_photo_editor.utils

import android.graphics.ColorMatrix

object FilterPresets {
    val Normal = ColorMatrix()

    val Silk = ColorMatrix(floatArrayOf(
        1.1f, 0f, 0f, 0f, 10f,
        0f, 1.0f, 0f, 0f, 5f,
        0f, 0f, 1.0f, 0f, 5f,
        0f, 0f, 0f, 1f, 0f
    ))
    
    val Glow = ColorMatrix(floatArrayOf(
        1.2f, 0f, 0f, 0f, 20f,
        0f, 1.1f, 0f, 0f, 10f,
        0f, 0f, 1.1f, 0f, 10f,
        0f, 0f, 0f, 1f, 0f
    ))

    val Cinema = ColorMatrix(floatArrayOf(
        0.8f, 0.2f, 0f, 0f, -20f,
        0.1f, 0.9f, 0f, 0f, -10f,
        0f, 0f, 1.1f, 0f, 20f,
        0f, 0f, 0f, 1f, 0f
    ))
    
    val Deep = ColorMatrix(floatArrayOf(
        0.6f, 0.4f, 0f, 0f, -30f,
        0.3f, 0.6f, 0.1f, 0f, -20f,
        0.1f, 0.2f, 0.7f, 0f, -10f,
        0f, 0f, 0f, 1f, 0f
    ))

    val Retro = ColorMatrix(floatArrayOf(
        0.9f, 0f, 0f, 0f, 20f,
        0f, 0.8f, 0f, 0f, 10f,
        0f, 0f, 0.7f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    ))

    val Sepia = ColorMatrix(floatArrayOf(
        0.393f, 0.769f, 0.189f, 0f, 0f,
        0.349f, 0.686f, 0.168f, 0f, 0f,
        0.272f, 0.534f, 0.131f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f
    ))

    val Portrait = listOf("Normal" to Normal, "Silk" to Silk, "Glow" to Glow)
    val Moody = listOf("Cinema" to Cinema, "Deep" to Deep)
    val Vintage = listOf("Retro" to Retro, "Sepia" to Sepia)
    
    val AllCategories = listOf(
        "Portrait" to Portrait,
        "Moody" to Moody,
        "Vintage" to Vintage
    )
}
