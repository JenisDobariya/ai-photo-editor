package com.photo_editor.my_photo_editor.utils

import android.content.Context
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import com.photo_editor.my_photo_editor.R

/**
 * Resolves a colour theme attribute (e.g. R.attr.appAccent) against this context's theme.
 * Use it instead of ContextCompat.getColor(ctx, R.color.lumina_accent) so the user's accent applies.
 */
@ColorInt
fun Context.themeColor(@AttrRes attr: Int): Int {
    val tv = TypedValue()
    if (!theme.resolveAttribute(attr, tv, true)) return 0
    return if (tv.resourceId != 0) getColor(tv.resourceId) else tv.data
}

/** The user's accent colour (Profile → Appearance). */
val Context.accentColor: Int @ColorInt get() = themeColor(R.attr.appAccent)

/** Lighter accent for small highlights, links and tags (was lumina_gold). */
val Context.accentSoftColor: Int @ColorInt get() = themeColor(R.attr.appAccentSoft)

val Context.accentContainerColor: Int @ColorInt get() = themeColor(R.attr.appAccentContainer)

/**
 * Like ContextCompat.getColor, but the accent colour resources (lumina_accent, lumina_gold, …)
 * resolve to the user's chosen accent. Safe to use for any colour resource.
 */
@ColorInt
fun Context.appColor(@androidx.annotation.ColorRes id: Int): Int = when (id) {
    R.color.lumina_accent, R.color.blue, R.color.logo_color -> themeColor(R.attr.appAccent)
    R.color.lumina_gold, R.color.lightBlue -> themeColor(R.attr.appAccentSoft)
    R.color.lumina_accent_container -> themeColor(R.attr.appAccentContainer)
    R.color.lumina_on_accent_container -> themeColor(R.attr.appOnAccentContainer)
    R.color.lumina_accent_dim, R.color.lumina_gold_dim -> themeColor(R.attr.appAccentDim)
    R.color.lumina_selection_overlay -> themeColor(R.attr.appSelectionOverlay)
    else -> androidx.core.content.ContextCompat.getColor(this, id)
}
