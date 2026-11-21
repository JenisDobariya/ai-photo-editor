package com.photo_editor.my_photo_editor.model

data class SlotRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

data class CollageTemplate(
    val id: String,
    val name: String,
    val photoCount: Int,
    /**
     * Normalized bounds for each photo slot: SlotRect(left, top, right, bottom) in range [0f, 1f]
     */
    val slots: List<SlotRect>
)

object CollageLayoutRepository {

    fun getTemplatesForCount(count: Int): List<CollageTemplate> {
        val clampedCount = count.coerceIn(2, 9)
        return when (clampedCount) {
            2 -> getTwoPhotoTemplates()
            3 -> getThreePhotoTemplates()
            4 -> getFourPhotoTemplates()
            5 -> getFivePhotoTemplates()
            6 -> getSixPhotoTemplates()
            7 -> getSevenPhotoTemplates()
            8 -> getEightPhotoTemplates()
            9 -> getNinePhotoTemplates()
            else -> getTwoPhotoTemplates()
        }
    }

    private fun getTwoPhotoTemplates(): List<CollageTemplate> = listOf(
        CollageTemplate(
            id = "2_vertical",
            name = "Side by Side",
            photoCount = 2,
            slots = listOf(
                SlotRect(0f, 0f, 0.5f, 1f),
                SlotRect(0.5f, 0f, 1f, 1f)
            )
        ),
        CollageTemplate(
            id = "2_horizontal",
            name = "Top & Bottom",
            photoCount = 2,
            slots = listOf(
                SlotRect(0f, 0f, 1f, 0.5f),
                SlotRect(0f, 0.5f, 1f, 1f)
            )
        ),
        CollageTemplate(
            id = "2_ratio_left",
            name = "Focus Left",
            photoCount = 2,
            slots = listOf(
                SlotRect(0f, 0f, 0.67f, 1f),
                SlotRect(0.67f, 0f, 1f, 1f)
            )
        ),
        CollageTemplate(
            id = "2_ratio_top",
            name = "Focus Top",
            photoCount = 2,
            slots = listOf(
                SlotRect(0f, 0f, 1f, 0.67f),
                SlotRect(0f, 0.67f, 1f, 1f)
            )
        )
    )

    private fun getThreePhotoTemplates(): List<CollageTemplate> = listOf(
        CollageTemplate(
            id = "3_one_top",
            name = "1 Top, 2 Bottom",
            photoCount = 3,
            slots = listOf(
                SlotRect(0f, 0f, 1f, 0.5f),
                SlotRect(0f, 0.5f, 0.5f, 1f),
                SlotRect(0.5f, 0.5f, 1f, 1f)
            )
        ),
        CollageTemplate(
            id = "3_one_left",
            name = "1 Left, 2 Right",
            photoCount = 3,
            slots = listOf(
                SlotRect(0f, 0f, 0.5f, 1f),
                SlotRect(0.5f, 0f, 1f, 0.5f),
                SlotRect(0.5f, 0.5f, 1f, 1f)
            )
        ),
        CollageTemplate(
            id = "3_one_bottom",
            name = "2 Top, 1 Bottom",
            photoCount = 3,
            slots = listOf(
                SlotRect(0f, 0f, 0.5f, 0.5f),
                SlotRect(0.5f, 0f, 1f, 0.5f),
                SlotRect(0f, 0.5f, 1f, 1f)
            )
        ),
        CollageTemplate(
            id = "3_cols",
            name = "3 Columns",
            photoCount = 3,
            slots = listOf(
                SlotRect(0f, 0f, 0.333f, 1f),
                SlotRect(0.333f, 0f, 0.667f, 1f),
                SlotRect(0.667f, 0f, 1f, 1f)
            )
        ),
        CollageTemplate(
            id = "3_rows",
            name = "3 Rows",
            photoCount = 3,
            slots = listOf(
                SlotRect(0f, 0f, 1f, 0.333f),
                SlotRect(0f, 0.333f, 1f, 0.667f),
                SlotRect(0f, 0.667f, 1f, 1f)
            )
        )
    )

    private fun getFourPhotoTemplates(): List<CollageTemplate> = listOf(
        CollageTemplate(
            id = "4_grid",
            name = "2x2 Grid",
            photoCount = 4,
            slots = listOf(
                SlotRect(0f, 0f, 0.5f, 0.5f),
                SlotRect(0.5f, 0f, 1f, 0.5f),
                SlotRect(0f, 0.5f, 0.5f, 1f),
                SlotRect(0.5f, 0.5f, 1f, 1f)
            )
        ),
        CollageTemplate(
            id = "4_one_left",
            name = "1 Left, 3 Right",
            photoCount = 4,
            slots = listOf(
                SlotRect(0f, 0f, 0.5f, 1f),
                SlotRect(0.5f, 0f, 1f, 0.333f),
                SlotRect(0.5f, 0.333f, 1f, 0.667f),
                SlotRect(0.5f, 0.667f, 1f, 1f)
            )
        ),
        CollageTemplate(
            id = "4_one_top",
            name = "1 Top, 3 Bottom",
            photoCount = 4,
            slots = listOf(
                SlotRect(0f, 0f, 1f, 0.5f),
                SlotRect(0f, 0.5f, 0.333f, 1f),
                SlotRect(0.333f, 0.5f, 0.667f, 1f),
                SlotRect(0.667f, 0.5f, 1f, 1f)
            )
        ),
        CollageTemplate(
            id = "4_cols",
            name = "4 Columns",
            photoCount = 4,
            slots = listOf(
                SlotRect(0f, 0f, 0.25f, 1f),
                SlotRect(0.25f, 0f, 0.5f, 1f),
                SlotRect(0.5f, 0f, 0.75f, 1f),
                SlotRect(0.75f, 0f, 1f, 1f)
            )
        )
    )

    private fun getFivePhotoTemplates(): List<CollageTemplate> = listOf(
        CollageTemplate(
            id = "5_two_three",
            name = "2 Top, 3 Bottom",
            photoCount = 5,
            slots = listOf(
                SlotRect(0f, 0f, 0.5f, 0.5f),
                SlotRect(0.5f, 0f, 1f, 0.5f),
                SlotRect(0f, 0.5f, 0.333f, 1f),
                SlotRect(0.333f, 0.5f, 0.667f, 1f),
                SlotRect(0.667f, 0.5f, 1f, 1f)
            )
        ),
        CollageTemplate(
            id = "5_three_two",
            name = "3 Top, 2 Bottom",
            photoCount = 5,
            slots = listOf(
                SlotRect(0f, 0f, 0.333f, 0.5f),
                SlotRect(0.333f, 0f, 0.667f, 0.5f),
                SlotRect(0.667f, 0f, 1f, 0.5f),
                SlotRect(0f, 0.5f, 0.5f, 1f),
                SlotRect(0.5f, 0.5f, 1f, 1f)
            )
        ),
        CollageTemplate(
            id = "5_one_center",
            name = "1 Center, 4 Corner",
            photoCount = 5,
            slots = listOf(
                SlotRect(0f, 0f, 0.5f, 0.333f),
                SlotRect(0.5f, 0f, 1f, 0.333f),
                SlotRect(0f, 0.333f, 1f, 0.667f),
                SlotRect(0f, 0.667f, 0.5f, 1f),
                SlotRect(0.5f, 0.667f, 1f, 1f)
            )
        )
    )

    private fun getSixPhotoTemplates(): List<CollageTemplate> = listOf(
        CollageTemplate(
            id = "6_grid_3x2",
            name = "3 Rows x 2 Cols",
            photoCount = 6,
            slots = listOf(
                SlotRect(0f, 0f, 0.5f, 0.333f),
                SlotRect(0.5f, 0f, 1f, 0.333f),
                SlotRect(0f, 0.333f, 0.5f, 0.667f),
                SlotRect(0.5f, 0.333f, 1f, 0.667f),
                SlotRect(0f, 0.667f, 0.5f, 1f),
                SlotRect(0.5f, 0.667f, 1f, 1f)
            )
        ),
        CollageTemplate(
            id = "6_grid_2x3",
            name = "2 Rows x 3 Cols",
            photoCount = 6,
            slots = listOf(
                SlotRect(0f, 0f, 0.333f, 0.5f),
                SlotRect(0.333f, 0f, 0.667f, 0.5f),
                SlotRect(0.667f, 0f, 1f, 0.5f),
                SlotRect(0f, 0.5f, 0.333f, 1f),
                SlotRect(0.333f, 0.5f, 0.667f, 1f),
                SlotRect(0.667f, 0.5f, 1f, 1f)
            )
        ),
        CollageTemplate(
            id = "6_one_left_five",
            name = "1 Large, 5 Side",
            photoCount = 6,
            slots = listOf(
                SlotRect(0f, 0f, 0.6f, 1f),
                SlotRect(0.6f, 0f, 1f, 0.2f),
                SlotRect(0.6f, 0.2f, 1f, 0.4f),
                SlotRect(0.6f, 0.4f, 1f, 0.6f),
                SlotRect(0.6f, 0.6f, 1f, 0.8f),
                SlotRect(0.6f, 0.8f, 1f, 1f)
            )
        )
    )

    private fun getSevenPhotoTemplates(): List<CollageTemplate> = listOf(
        CollageTemplate(
            id = "7_standard",
            name = "3 - 1 - 3",
            photoCount = 7,
            slots = listOf(
                SlotRect(0f, 0f, 0.333f, 0.333f),
                SlotRect(0.333f, 0f, 0.667f, 0.333f),
                SlotRect(0.667f, 0f, 1f, 0.333f),
                SlotRect(0f, 0.333f, 1f, 0.667f),
                SlotRect(0f, 0.667f, 0.333f, 1f),
                SlotRect(0.333f, 0.667f, 0.667f, 1f),
                SlotRect(0.667f, 0.667f, 1f, 1f)
            )
        )
    )

    private fun getEightPhotoTemplates(): List<CollageTemplate> = listOf(
        CollageTemplate(
            id = "8_grid",
            name = "4 Rows x 2 Cols",
            photoCount = 8,
            slots = listOf(
                SlotRect(0f, 0f, 0.5f, 0.25f),
                SlotRect(0.5f, 0f, 1f, 0.25f),
                SlotRect(0f, 0.25f, 0.5f, 0.5f),
                SlotRect(0.5f, 0.25f, 1f, 0.5f),
                SlotRect(0f, 0.5f, 0.5f, 0.75f),
                SlotRect(0.5f, 0.5f, 1f, 0.75f),
                SlotRect(0f, 0.75f, 0.5f, 1f),
                SlotRect(0.5f, 0.75f, 1f, 1f)
            )
        )
    )

    private fun getNinePhotoTemplates(): List<CollageTemplate> = listOf(
        CollageTemplate(
            id = "9_grid_3x3",
            name = "3x3 Classic Grid",
            photoCount = 9,
            slots = listOf(
                SlotRect(0f, 0f, 0.333f, 0.333f),
                SlotRect(0.333f, 0f, 0.667f, 0.333f),
                SlotRect(0.667f, 0f, 1f, 0.333f),
                SlotRect(0f, 0.333f, 0.333f, 0.667f),
                SlotRect(0.333f, 0.333f, 0.667f, 0.667f),
                SlotRect(0.667f, 0.333f, 1f, 0.667f),
                SlotRect(0f, 0.667f, 0.333f, 1f),
                SlotRect(0.333f, 0.667f, 0.667f, 1f),
                SlotRect(0.667f, 0.667f, 1f, 1f)
            )
        )
    )
}
