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

    fun available(root: JSONObject, path: List<String>, imageAvailable: (String) -> Boolean = { true }): Boolean {
        val modes = root.optJSONObject("modes") ?: return false
        val candidates = parseFunctions(root)[path].orEmpty()
        return candidates.any { name ->
            val frames = modes.optJSONObject(name)?.optJSONArray("frames") ?: return@any false
            frames.length() > 0 && (0 until frames.length()).all { index ->
                val frame = frames.optJSONObject(index) ?: return@all false
                frame.optString("res").let { it.isNotBlank() && imageAvailable(it) } && frame.opt("delay").let { delay ->
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
        val descriptor = RoleStore.descriptorForInstance(ctx, instanceId)
            ?: return "无法定位当前素材包，功能暂不可用"
        val imageCache = mutableMapOf<String, Boolean>()
        val playable = available(root, path) { res ->
            imageCache.getOrPut(res) {
                runCatching {
                    val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    if (descriptor.source == RoleSource.ASSETS) {
                        ctx.assets.open("${descriptor.rootPath}/$res").use {
                            android.graphics.BitmapFactory.decodeStream(it, null, options)
                        }
                    } else {
                        val base = java.io.File(ctx.filesDir, "roles/${descriptor.rootPath}").canonicalFile
                        val image = java.io.File(base, res).canonicalFile
                        if (!image.toPath().startsWith(base.toPath()) || !image.isFile) return@getOrPut false
                        android.graphics.BitmapFactory.decodeFile(image.absolutePath, options)
                    }
                    options.outWidth > 0 && options.outHeight > 0
                }.getOrDefault(false)
            }
        }
        return if (playable) null
            else "当前素材包缺少可播放的必要动画（${path.joinToString("/")}），功能暂不可用"
    }
}
