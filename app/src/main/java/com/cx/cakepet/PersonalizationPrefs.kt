package com.cx.cakepet

import android.content.Context

/** App-wide presentation preferences, independent from any role's material or behavior. */
object PersonalizationPrefs {
    private const val FILE = "cakepet_personalization"
    private const val SPAWN = "summon_position"
    private const val ANIMATE = "animated_preview"
    private const val PAT = "preview_pat"
    private const val PRESENCE = "home_preview_mode"

    const val BOTTOM_CENTER = "bottom_center"
    const val RANDOM = "random"
    const val ALWAYS = "always"
    const val DYNAMIC = "dynamic"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun summonPosition(context: Context): String =
        prefs(context).getString(SPAWN, BOTTOM_CENTER)
            .takeIf { it == BOTTOM_CENTER || it == RANDOM } ?: BOTTOM_CENTER

    fun setSummonPosition(context: Context, value: String) {
        require(value == BOTTOM_CENTER || value == RANDOM)
        prefs(context).edit().putString(SPAWN, value).apply()
    }

    fun animatedPreview(context: Context): Boolean = prefs(context).getBoolean(ANIMATE, true)
    fun setAnimatedPreview(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(ANIMATE, enabled).apply()

    fun previewPat(context: Context): Boolean = prefs(context).getBoolean(PAT, true)
    fun setPreviewPat(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(PAT, enabled).apply()

    fun homePreviewMode(context: Context): String =
        prefs(context).getString(PRESENCE, ALWAYS)
            .takeIf { it == ALWAYS || it == DYNAMIC } ?: ALWAYS

    fun setHomePreviewMode(context: Context, mode: String) {
        require(mode == ALWAYS || mode == DYNAMIC)
        prefs(context).edit().putString(PRESENCE, mode).apply()
    }
}
