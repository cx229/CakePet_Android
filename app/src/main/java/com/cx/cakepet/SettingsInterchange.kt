package com.cx.cakepet

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** Standalone human-readable settings format shared by role settings and habits. */
object SettingsInterchange {
    data class ImportSummary(val applied: Int, val skipped: Int)
    fun exportRole(ctx: Context, roleId: String): JSONObject = runBlocking {
        val config = PetConfig.forRole(ctx, roleId)
        val bounds = PetBounds.forRole(ctx, roleId)
        // Include settings that are currently using implicit defaults, too.
        // This is a standalone copy of the role's complete effective settings.
        config.update { it }
        for (page in SettingsPage.entries) {
            bounds.updateBounds(page, bounds.getBlocking(page))
        }
        JSONObject()
            .put("type", "cakepet-settings")
            .put("source", "role")
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("config", config.exportSettings())
            .put("bounds", bounds.exportSettings())
    }

    /** Overlay only current, valid options; never clear unrelated settings. */
    fun importRole(ctx: Context, roleId: String, data: JSONObject): Int = runBlocking {
        require(data.optString("type") == "cakepet-settings") { "不是 CakePet 设置文件" }
        val config = PetConfig.forRole(ctx, roleId)
        val bounds = PetBounds.forRole(ctx, roleId)
        config.update { it }
        for (page in SettingsPage.entries) bounds.updateBounds(page, bounds.getBlocking(page))
        val selectedConfig = data.optJSONObject("config")?.let {
            PreferenceCodec.compatible(it, config.exportSettings(), config.supportedPreferenceKeys(),
                config::acceptsImportedValue)
        } ?: JSONObject()
        val selectedBounds = data.optJSONObject("bounds")?.let {
            PreferenceCodec.compatible(it, bounds.exportSettings(), bounds.supportedPreferenceKeys(),
                bounds::acceptsImportedValue)
        } ?: JSONObject()
        val count = selectedConfig.length() + selectedBounds.length()
        val ignored = (data.optJSONObject("config")?.length() ?: 0) +
            (data.optJSONObject("bounds")?.length() ?: 0) - count
        require(count > 0) { "没有当前版本可识别的设置项" }
        if (selectedConfig.length() > 0) config.importSettings(selectedConfig)
        if (selectedBounds.length() > 0) bounds.importSettings(selectedBounds)
        ImportSummary(count, ignored.coerceAtLeast(0))
    }

    fun exportHabits(ctx: Context): JSONObject =
        JSONObject(UserHabits.load(ctx).toString())
            .put("type", "cakepet-settings")
            .put("source", "habits")
            .put("appVersion", BuildConfig.VERSION_NAME)

    fun importHabits(ctx: Context, data: JSONObject) {
        require(data.optString("type") == "cakepet-settings") { "不是 CakePet 设置文件" }
        val incoming = data.optJSONObject("bounds") ?: return
        val target = PetBounds.forRole(ctx, UserHabits.ROLE_ID)
        runBlocking {
            for (page in SettingsPage.entries) target.updateBounds(page, target.getBlocking(page))
            val usable = PreferenceCodec.compatible(incoming, target.exportSettings(),
                target.supportedPreferenceKeys(), target::acceptsImportedValue)
            if (usable.length() > 0) target.importSettings(usable)
        }
    }
}
