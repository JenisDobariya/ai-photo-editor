package com.photo_editor.my_photo_editor

import com.photo_editor.my_photo_editor.ui.BeforeAfterView
import org.junit.Assert.*
import org.junit.Test

class BeforeAfterTest {

    @Test
    fun testSliderBoundsClamping() {
        val minSlider = 0.05f
        val maxSlider = 0.95f

        val underflow = (-0.5f).coerceIn(minSlider, maxSlider)
        assertEquals(minSlider, underflow, 0.001f)

        val overflow = (1.5f).coerceIn(minSlider, maxSlider)
        assertEquals(maxSlider, overflow, 0.001f)

        val mid = (0.5f).coerceIn(minSlider, maxSlider)
        assertEquals(0.5f, mid, 0.001f)
    }

    @Test
    fun testModesAndBadgesCompleteness() {
        assertEquals(4, BeforeAfterView.Mode.values().size)
        assertEquals(4, BeforeAfterView.BadgeStyle.values().size)

        assertTrue(BeforeAfterView.Mode.values().contains(BeforeAfterView.Mode.SLIDER))
        assertTrue(BeforeAfterView.Mode.values().contains(BeforeAfterView.Mode.SIDE_BY_SIDE))
        assertTrue(BeforeAfterView.Mode.values().contains(BeforeAfterView.Mode.TOP_BOTTOM))
        assertTrue(BeforeAfterView.Mode.values().contains(BeforeAfterView.Mode.DIAGONAL))

        assertTrue(BeforeAfterView.BadgeStyle.values().contains(BeforeAfterView.BadgeStyle.DARK_PILL))
        assertTrue(BeforeAfterView.BadgeStyle.values().contains(BeforeAfterView.BadgeStyle.GOLD_ACCENT))
        assertTrue(BeforeAfterView.BadgeStyle.values().contains(BeforeAfterView.BadgeStyle.MINIMAL))
        assertTrue(BeforeAfterView.BadgeStyle.values().contains(BeforeAfterView.BadgeStyle.NONE))
    }

    @Test
    fun testAspectRatioDimensionsCalculation() {
        val targetWidth = 1080
        val ratioSquare = 1.0f
        val heightSquare = (targetWidth / ratioSquare).toInt()
        assertEquals(1080, heightSquare)

        val ratioPortrait = 4f / 5f
        val heightPortrait = (targetWidth / ratioPortrait).toInt()
        assertEquals(1350, heightPortrait)

        val ratioStory = 9f / 16f
        val heightStory = (targetWidth / ratioStory).toInt()
        assertEquals(1920, heightStory)
    }
}
