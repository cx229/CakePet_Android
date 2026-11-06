package com.cx.cakepet

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** Standalone human-readable settings format shared by role settings and habits. */
object SettingsInterchange {
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

    fun importRole(ctx: Context, roleId: String, data: JSONObject): Int = runBlocking {
        require(data.optString("type") == "cakepet-settings") { "不是 CakePet 设置文件" }
        var applied = 0
        for (section in listOf("config", "bounds")) {
            val obj = data.optJSONObject(section) ?: continue
            // User habits are now complete defaults; no opt-in selection.
            val selected = JSONObject(obj.toString())
            // These are internal material identity and retired visibility keys,
            // not portable user settings for a different character.
            if (section == "config") {
                listOf("sprite_pack", "use_new_sprite", "visible").forEach { selected.remove(it) }
            }
            applied += if (section == "config")
                PetConfig.forRole(ctx, roleId).importSettings(selected)
            else PetBounds.forRole(ctx, roleId).importSettings(selected)
        }
        require(applied > 0) { "没有可恢复的设置项" }
        applied
    }

    fun exportHabits(ctx: Context): JSONObject =
        JSONObject(UserHabits.load(ctx).toString())
            .put("type", "cakepet-settings")
            .put("source", "habits")
            .put("appVersion", BuildConfig.VERSION_NAME)

    fun importHabits(ctx: Context, data: JSONObject) {
        require(data.optString("type") == "cakepet-settings") { "不是 CakePet 设置文件" }
        val bounds = data.optJSONObject("bounds") ?: JSONObject()
        if (data.optString("source") == "habits") {
            UserHabits.save(ctx, JSONObject().put("bounds", bounds))
        } else {
            // Role bounds are a complete set of defaults in the shared format.
            UserHabits.save(ctx, JSONObject().put("bounds", bounds))
        }
    }
}
