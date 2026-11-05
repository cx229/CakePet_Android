package com.cx.cakepet

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Personal defaults for newly CREATED role instances, not a live global override.
 * Disabled fields retain their values; no material ZIP/asset file is modified.
 */
object UserHabits {
    private fun file(ctx: Context) = File(ctx.filesDir, "user_habits.json")

    fun load(ctx: Context): JSONObject =
        runCatching { JSONObject(file(ctx).readText(Charsets.UTF_8)) }
            .getOrElse { JSONObject().put("config", JSONObject()).put("bounds", JSONObject())
                .put("configEnabled", JSONArray()).put("boundsEnabled", JSONArray()) }

    fun save(ctx: Context, data: JSONObject) {
        val target = file(ctx)
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(data.toString(2), Charsets.UTF_8)
        check(tmp.renameTo(target)) { "保存用户习惯失败" }
    }

    fun enabled(doc: JSONObject, section: String): Set<String> {
        val arr = doc.optJSONArray(section + "Enabled") ?: return emptySet()
        return (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }.toSet()
    }

    fun setEnabled(doc: JSONObject, section: String, key: String, enabled: Boolean) {
        val selected = enabled(doc, section).toMutableSet()
        if (enabled) selected.add(key) else selected.remove(key)
        doc.put(section + "Enabled", JSONArray(selected.sorted()))
    }

    /**
     * Explicit, user-confirmed one-off application of personal values to
     * already existing roles. Does not change the inheritance policy.
     * section/key null means all currently enabled personal settings.
     */
    fun applyToExisting(ctx: Context, section: String? = null, key: String? = null): Int {
        val doc = load(ctx)
        val instances = RoleStore.getHomeRoles(ctx)
        runBlocking {
            for (instance in instances) {
                for (part in listOf("config", "bounds")) {
                    if (section != null && section != part) continue
                    val stored = doc.optJSONObject(part) ?: continue
                    val keys = if (key != null) listOf(key) else enabled(doc, part).toList()
                    val picked = JSONObject()
                    for (name in keys) {
                        if (part == "config" && name in setOf("sprite_pack", "use_new_sprite", "visible")) continue
                        stored.optJSONObject(name)?.let { picked.put(name, it) }
                    }
                    if (picked.length() == 0) continue
                    if (part == "config") PetConfig.forRole(ctx, instance.instanceId).importSettings(picked)
                    else PetBounds.forRole(ctx, instance.instanceId).importSettings(picked)
                }
            }
        }
        return instances.size
    }

    /** Runs once after adding a role to home, not when recalling or summoning it. */
    fun applyToNewRole(ctx: Context, roleId: String) {
        val doc = load(ctx)
        runBlocking {
            for (section in listOf("config", "bounds")) {
                val stored = doc.optJSONObject(section) ?: continue
                val selected = JSONObject()
                for (key in enabled(doc, section)) {
                    if (section == "config" && key in setOf("sprite_pack", "use_new_sprite", "visible")) continue
                    stored.optJSONObject(key)?.let { selected.put(key, it) }
                }
                if (selected.length() == 0) continue
                if (section == "config") PetConfig.forRole(ctx, roleId).importSettings(selected)
                else PetBounds.forRole(ctx, roleId).importSettings(selected)
            }
        }
    }
}
