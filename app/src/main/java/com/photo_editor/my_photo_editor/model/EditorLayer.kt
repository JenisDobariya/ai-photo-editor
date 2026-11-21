package com.photo_editor.my_photo_editor.model

import android.graphics.Color
import android.graphics.PointF

sealed class LayerContent {
    data class Text(
        val text: String,
        val color: Int = Color.WHITE,
        val fontSize: Float = 28f,
        val shadowColor: Int = Color.BLACK,
        val fontStyleIndex: Int = 0,
        val bgBadgeColor: Int? = null,
        val letterSpacing: Float = 0f,
        val opacity: Float = 1.0f,
        val alignment: Int = android.view.Gravity.CENTER,
        val isBold: Boolean = false,
        val isItalic: Boolean = false,
        val isAllCaps: Boolean = false,
        val shadowRadius: Float = 0f,
        val shadowDx: Float = 0f,
        val shadowDy: Float = 0f
    ) : LayerContent()
    
    data class Sticker(val uri: String) : LayerContent()
}

data class EditorLayer(
    val id: Long = System.currentTimeMillis() + (0..1000).random(),
    val content: LayerContent,
    var x: Float = 100f,
    var y: Float = 100f,
    var scale: Float = 1f,
    var rotation: Float = 0f
)
