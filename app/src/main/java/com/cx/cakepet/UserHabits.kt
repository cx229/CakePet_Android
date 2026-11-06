package com.cx.cakepet

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * Independent personal-default threshold store. Editing a habit never changes
 * an existing character. New instances receive all defaults at creation time;
 * applying a card to existing characters is always a separate confirmed action.
 */
object UserHabits {
    const val ROLE_ID = "__cakepet_user_habits__"

    private fun source(ctx: Context) = PetBounds.forRole(ctx, ROLE_ID)

    /** Human-readable export; defaults are sufficient to reconstruct live values. */
    fun load(ctx: Context): JSONObject = runBlocking {
        val bounds = source(ctx)
        // Materialize every default even if the user never edited it. Standalone
        // JSON and full backups must include a complete, readable habit snapshot.
        for (page in SettingsPage.entries) {
            bounds.updateBounds(page, bounds.getBlocking(page))
        }
        JSONObject().put("bounds", bounds.exportSettings())
    }

    /** Import human-readable default/threshold values without changing characters. */
    fun save(ctx: Context, data: JSONObject) {
        val raw = data.optJSONObject("bounds") ?: return
        runBlocking { source(ctx).importSettings(raw, replace = true) }
    }

    private fun merged(current: PageBounds, habit: PageBounds, key: String?): PageBounds {
        fun use(name: String) = key == null || key == name
        return current.copy(
            sliders = current.sliders.map { old ->
                habit.sliders.firstOrNull { use(old.key) && it.key == old.key } ?: old
            },
            duals = current.duals.map { old ->
                habit.duals.firstOrNull { use(old.key) && it.key == old.key } ?: old
            },
            switches = current.switches.map { old ->
                habit.switches.firstOrNull { use(old.key) && it.key == old.key } ?: old
            },
            colors = current.colors.map { old ->
                habit.colors.firstOrNull { use(old.key) && it.key == old.key } ?: old
            },
            choices = current.choices.map { old ->
                habit.choices.firstOrNull { use(old.key) && it.key == old.key } ?: old
            },
            keyboardModeDefault = if (use("imeMode")) habit.keyboardModeDefault
                ?: current.keyboardModeDefault else current.keyboardModeDefault
        )
    }

    /** Called once when the home instance is created, not on every summon. */
    fun applyToNewRole(ctx: Context, roleId: String) {
        apply(ctx, listOf(roleId), null, null)
    }

    /** A single card changes its own defaults, thresholds AND live value. */
    fun applyToExisting(ctx: Context, page: SettingsPage? = null, key: String? = null): Int {
        val roles = RoleStore.getHomeRoles(ctx).map { it.instanceId }
        apply(ctx, roles, page, key)
        return roles.size
    }

    private fun apply(ctx: Context, roles: List<String>, page: SettingsPage?, key: String?) {
        val habit = source(ctx)
        runBlocking {
            for (id in roles) {
                val target = PetBounds.forRole(ctx, id)
                val config = PetConfig.forRole(ctx, id)
                for (p in SettingsPage.entries) {
                    if (page != null && page != p) continue
                    val merged = merged(target.getBlocking(p), habit.getBlocking(p), key)
                    target.updateBounds(p, merged)
                    config.resetFromBounds(target, p, onlyKey = key)
                }
            }
        }
    }
}
