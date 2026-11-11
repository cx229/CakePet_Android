package com.cx.cakepet

import android.content.Context

/** App-wide presentation preferences, independent from any role's material or behavior. */
object PersonalizationPrefs {
    private const val FILE = "cakepet_personalization"
    private const val SPAWN = "summon_position"
    private const val ANIMATE = "animated_preview" // Legacy toggle; read once for migration.
    private const val PREVIEW_SOURCE = "preview_source"
    private const val PAT = "preview_pat"
    private const val PRESENCE = "home_preview_mode"

    const val BOTTOM_CENTER = "bottom_center"
    const val RANDOM = "random"
    const val ALWAYS = "always"
    const val DYNAMIC = "dynamic"

    const val PREVIEW = "preview"
    const val STANDBY = "standby"
    const val STATIC_POOL = "static_pool"
    const val BLANK = "blank"

    /** App-wide selection of a *playing* Function pool, never a frozen image. */
    fun previewSource(context: Context): String {
        val store = prefs(context)
        return store.getString(PREVIEW_SOURCE, null)
            ?.takeIf { it in listOf(PREVIEW, STANDBY, STATIC_POOL, BLANK) }
            ?: if (store.contains(ANIMATE)) {
                // Existing disabled previews become blank; enabled previews keep
                // their former static-pool playback until the player chooses anew.
                if (store.getBoolean(ANIMATE, true)) STATIC_POOL else BLANK
            } else PREVIEW
    }

    fun setPreviewSource(context: Context, value: String) {
        require(value in listOf(PREVIEW, STANDBY, STATIC_POOL, BLANK))
        prefs(context).edit().putString(PREVIEW_SOURCE, value).apply()
    }

    fun previewFunctionPath(source: String): List<String>? = when (source) {
        PREVIEW -> listOf("menu", "preview")
        STANDBY -> listOf("standby", "pool")
        STATIC_POOL -> listOf("standby", "static_pool")
        else -> null
    }

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
        prefs(context).getString(PRESENCE, DYNAMIC)
            .takeIf { it == ALWAYS || it == DYNAMIC } ?: DYNAMIC

    fun setHomePreviewMode(context: Context, mode: String) {
        require(mode == ALWAYS || mode == DYNAMIC)
        prefs(context).edit().putString(PRESENCE, mode).apply()
    }
}
