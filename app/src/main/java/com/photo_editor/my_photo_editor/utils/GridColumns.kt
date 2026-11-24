package com.photo_editor.my_photo_editor.utils

import android.content.Context
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Grid column counts that follow the screen width, so tablets, foldables and landscape get
 * more (not bigger) cells while phones in portrait keep their usual counts.
 */

/** Width (dp) below which a screen counts as a phone in portrait: column counts stay as designed. */
private const val PHONE_MAX_WIDTH_DP = 600

/** Width (dp) the main photo grid's "N per row" choice was designed for. */
private const val PHONE_WIDTH_DP = 400f

/** Smallest cell (dp) of the 3-per-row thumbnail grids (Recycle Bin, Locked Folder, Clean up, PDF). */
const val GRID_CELL_DP = 130

/** Smallest album card (dp) in the Albums tab (2 per row on phones). */
const val ALBUM_CELL_DP = 180

/** At least [base] columns; more when the screen fits more cells of [minCellDp]. */
fun Context.adaptiveColumns(base: Int, minCellDp: Int): Int =
    max(base, resources.configuration.screenWidthDp / minCellDp)

/**
 * Main photo grid (user's "Grid size" 2–6, also changed by pinch): unchanged on phones,
 * scaled with the width on wider screens (e.g. 4 per row on a phone ≈ 8 on a 800dp tablet).
 */
fun Context.scaledGridColumns(userColumns: Int): Int {
    val widthDp = resources.configuration.screenWidthDp
    if (widthDp < PHONE_MAX_WIDTH_DP) return userColumns
    return (userColumns * widthDp / PHONE_WIDTH_DP).roundToInt().coerceAtLeast(userColumns)
}
