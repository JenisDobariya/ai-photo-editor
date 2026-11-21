package com.photo_editor.my_photo_editor

import com.photo_editor.my_photo_editor.utils.AiCutoutManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiCutoutTest {

    @Test
    fun testEdgeStyleEnumValues() {
        val styles = AiCutoutManager.EdgeStyle.values()
        assertEquals(3, styles.size)
        assertTrue(styles.contains(AiCutoutManager.EdgeStyle.CRISP))
        assertTrue(styles.contains(AiCutoutManager.EdgeStyle.SMOOTH))
        assertTrue(styles.contains(AiCutoutManager.EdgeStyle.FEATHER))
    }

    @Test
    fun testEdgeStyleOrderAndDefaults() {
        assertNotNull(AiCutoutManager.EdgeStyle.valueOf("CRISP"))
        assertNotNull(AiCutoutManager.EdgeStyle.valueOf("SMOOTH"))
        assertNotNull(AiCutoutManager.EdgeStyle.valueOf("FEATHER"))
    }
}
