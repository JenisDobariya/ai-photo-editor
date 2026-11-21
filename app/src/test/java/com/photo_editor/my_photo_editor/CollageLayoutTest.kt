package com.photo_editor.my_photo_editor

import com.photo_editor.my_photo_editor.model.CollageLayoutRepository
import org.junit.Assert.*
import org.junit.Test

class CollageLayoutTest {

    @Test
    fun testTemplatesGeneratedForCountsTwoToNine() {
        for (count in 2..9) {
            val templates = CollageLayoutRepository.getTemplatesForCount(count)
            assertTrue("Templates should exist for count $count", templates.isNotEmpty())

            for (template in templates) {
                assertEquals(
                    "Template ${template.id} should have $count slots",
                    count,
                    template.slots.size
                )

                for (slot in template.slots) {
                    assertTrue("Slot left should be >= 0", slot.left >= 0f)
                    assertTrue("Slot top should be >= 0", slot.top >= 0f)
                    assertTrue("Slot right should be <= 1.01", slot.right <= 1.01f)
                    assertTrue("Slot bottom should be <= 1.01", slot.bottom <= 1.01f)
                    assertTrue("Slot width should be positive", slot.width > 0f)
                    assertTrue("Slot height should be positive", slot.height > 0f)
                }
            }
        }
    }
}
