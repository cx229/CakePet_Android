package com.cx.cakepet

import android.content.Context
import org.json.JSONObject

/**
 * Shared function availability vocabulary. Missing keys and [] are equivalent.
 * Every path is checked independently; there is no inheritance or fallback.
 */
internal object MaterialFunctionSupport {
    val STATIC = listOf("standby", "static_pool")
    val NORMAL = listOf("standby", "pool")
    val THROW = listOf("touch", "roll")
    val SNAP = listOf("snap", "snap_head")
    val FEED = listOf("feed", "food")
    val LEAP = listOf("climb", "leap", "leap")
    val FORAGE = listOf("climb", "forage", "move")

    fun available(root: JSONObject, path: List<String>): Boolean {
        val modes = root.optJSONObject("modes") ?: return false
        val candidates = parseFunctions(root)[path].orEmpty()
        return candidates.any { name ->
            val frames = modes.optJSONObject(name)?.optJSONArray("frames") ?: return@any false
            frames.length() > 0 && (0 until frames.length()).all { index ->
                val frame = frames.optJSONObject(index) ?: return@all false
                frame.optString("res").isNotBlank() && frame.opt("delay").let { delay ->
                    when (delay) {
                        is Number -> delay.toLong() > 0
                        is org.json.JSONArray -> delay.length() == 2 &&
                            delay.opt(0) is Number && delay.opt(1) is Number &&
                            delay.optLong(0) > 0 && delay.optLong(1) >= delay.optLong(0)
                        else -> false
                    }
                }
            }
        }
    }

    fun read(ctx: Context, instanceId: String): JSONObject? {
        val descriptor = RoleStore.descriptorForInstance(ctx, instanceId) ?: return null
        return runCatching {
            val source = if (descriptor.source == RoleSource.ASSETS) {
                ctx.assets.open("${descriptor.rootPath}/modes.jsonc").bufferedReader().use { it.readText() }
            } else {
                java.io.File(ctx.filesDir, "roles/${descriptor.rootPath}/modes.jsonc").readText()
            }
            parseJsonc(source)
        }.getOrNull()
    }

    /** Only declare mandatory mappings established by actual runtime callers. */
    fun requirement(label: String): List<String>? = when (label) {
        "抛掷" -> THROW
        "吸附" -> SNAP
        "投食" -> FEED
        "飞跃" -> LEAP
        "自动觅食" -> FORAGE
        else -> null // Bare climbing has physical state but no mandatory climb-only animation.
    }

    fun missing(ctx: Context, instanceId: String, label: String): String? {
        val path = requirement(label) ?: return null
        val root = read(ctx, instanceId) ?: return "素材包配置无法读取，功能暂不可用"
        return if (available(root, path)) null
            else "当前素材包缺少必要动画（${path.joinToString("/")}），功能暂不可用"
    }
}
