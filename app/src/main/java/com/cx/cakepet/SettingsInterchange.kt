package com.cx.cakepet

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** Standalone human-readable settings format shared by role settings and habits. */
object SettingsInterchange {
    fun exportRole(ctx: Context, roleId: String): JSONObject = runBlocking {
        JSONObject()
            .put("type", "cakepet-settings")
            .put("source", "role")
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("config", PetConfig.forRole(ctx, roleId).exportSettings())
            .put("bounds", PetBounds.forRole(ctx, roleId).exportSettings())
    }

    fun importRole(ctx: Context, roleId: String, data: JSONObject): Int = runBlocking {
        require(data.optString("type") == "cakepet-settings") { "不是 CakePet 设置文件" }
        var applied = 0
        data.optJSONObject("config")?.let {
            applied += PetConfig.forRole(ctx, roleId).importSettings(it)
        }
        data.optJSONObject("bounds")?.let {
            applied += PetBounds.forRole(ctx, roleId).importSettings(it)
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
        val source = data.optString("source")
        if (source == "habits") {
            UserHabits.save(ctx, JSONObject()
                .put("config", data.optJSONObject("config") ?: JSONObject())
                .put("bounds", data.optJSONObject("bounds") ?: JSONObject())
                .put("configEnabled", data.optJSONArray("configEnabled") ?: org.json.JSONArray())
                .put("boundsEnabled", data.optJSONArray("boundsEnabled") ?: org.json.JSONArray()))
        } else {
            // Importing a complete role configuration as a personal preference:
            // enable all supplied items explicitly; no changes to existing roles.
            val config = data.optJSONObject("config") ?: JSONObject()
            val bounds = data.optJSONObject("bounds") ?: JSONObject()
            UserHabits.save(ctx, JSONObject()
                .put("config", config)
                .put("bounds", bounds)
                .put("configEnabled", org.json.JSONArray(PreferenceCodec.entries(config)))
                .put("boundsEnabled", org.json.JSONArray(PreferenceCodec.entries(bounds))))
        }
    }
}
