package com.cx.cakepet

import org.json.JSONArray
import org.json.JSONObject
import android.graphics.BitmapFactory
import java.io.File

/**
 * 资源包契约（v1.01）。
 *
 * v1.02 的 functions 只承担「功能路径 → Mode ID 列表」映射，
 * 代码【不再直接引用模式名】，一律经功能路径寻址；模式名标准化，角色差异只体现在
 * 图片路径与池的构成上。新增角色动作只需改 JSON，不必改代码。
 *
 * 任意 Mode 均可作为角色、食物或其他显示对象的动画序列。
 */
object RolePackageContract {
    const val VERSION = "1.02"

    /**
     * 必备功能路径（v1.01）。层数不限，必须按【完整路径】寻址：
     * [snap, pat_head, pat_head]（吸附态摸头）与 [pat_head, pat_head]（普通摸头）末段同名而含义不同。
     *
     * 所有叶子都是 Mode ID 候选列表。
     */
    val REQUIRED_FUNCTION_PATHS = listOf(
        listOf("static", "sit"),                 // 静止：静坐
        listOf("static", "pool"),                // 静止动作池
        listOf("standby", "pool"),               // 待机动作池
        listOf("touch", "roll"),                 // 触摸：滚动
        listOf("touch", "lift_up", "slow"),        // 提起：慢速
        listOf("touch", "lift_up", "left_medium"), // 提起：中速向左
        listOf("touch", "lift_up", "left_fast"),   // 提起：快速向左
        listOf("touch", "lift_up", "right_medium"),// 提起：中速向右
        listOf("touch", "lift_up", "right_fast"),  // 提起：快速向右
        listOf("touch", "jump_down"),            // 触摸：落地（跳下）
        listOf("touch", "walk"),                 // 触摸：走
        listOf("snap", "snap_head"),             // 吸附：探头
        listOf("snap", "pat_head", "pat_head"),  // 吸附：探头摸头
        listOf("snap", "feed", "eat"),           // 吸附：探头投食
        listOf("climb", "walk"),                 // 攀岩：走
        listOf("climb", "jump_up"),              // 攀岩：跳起
        listOf("pat_head", "pat_head"),          // 摸头：摸头
        listOf("feed", "eat"),                   // 投食：吃鱼动作（模式）
        listOf("feed", "food"),                  // 投食：食物动画（指向 modes）
        listOf("menu", "pool")                   // 菜单动作池
    )

    /** 必备顶层字段（v1.0）：模板默认提供，缺失即视为错误 */
    val REQUIRED_TOP_FIELDS = setOf(
        "version", "id", "name", "shortName",
        "decodeTarget", "anchorRes",
        "functions", "modes",
        "authors"
    )

    data class ValidationResult(
        val ok: Boolean,
        val version: String,
        val errors: List<String>
    )

    /**
     * Runtime readiness, NOT a full authoring/lint pass.
     *
     * Unused modes and inactive settings may contain arbitrary editor data.
     * Only modes selected by present Functions (plus their active nextMode
     * transitions) need to be playable. Package version is informational:
     * incoming packs are normalized to VERSION by prepareEditable().
     */
    fun validate(json: JSONObject): ValidationResult {
        val errors = mutableListOf<String>()
        val modes = json.optJSONObject("modes")
        val functionsObj = json.optJSONObject("functions")
        if (modes == null) errors.add("缺少可读取的动作列表 modes")
        if (functionsObj == null) errors.add("缺少可读取的功能映射 functions")
        if (modes == null || functionsObj == null)
            return ValidationResult(false, json.optString("version"), errors)

        val functions = runCatching { parseFunctions(json) }
            .getOrElse {
                errors.add("功能映射无法解析：${it.message}")
                return ValidationResult(false, json.optString("version"), errors)
            }
        val used = linkedSetOf<String>()
        // Missing function paths are optional. Do not force a full template.
        for ((path, refs) in functions) {
            for (name in refs) {
                if (name.isBlank()) continue
                if (modes.optJSONObject(name) == null) {
                    errors.add("功能 ${path.joinToString("/")} 引用不存在的动作：$name")
                } else used.add(name)
            }
        }
        if (used.isEmpty()) errors.add("至少需要一个可用功能及其动作")

        // Active nextMode chains are also part of a playable animation.
        val pending = java.util.ArrayDeque<String>()
        used.forEach { pending.add(it) }
        while (!pending.isEmpty()) {
            val name = pending.removeFirst()
            val mode = modes.optJSONObject(name) ?: continue
            val kind = playbackMode(mode)
            if (kind == "infinite") continue
            val next = mode.optString("nextMode").takeUnless { it.isBlank() || it == "null" }
                ?: continue
            if (modes.optJSONObject(next) == null) {
                errors.add("动作 $name 的后续动作不存在：$next")
            } else if (used.add(next)) pending.add(next)
        }

        for (name in used) {
            val mode = modes.optJSONObject(name) ?: continue
            val frames = mode.optJSONArray("frames")
            if (frames == null || frames.length() == 0) {
                errors.add("已使用动作 $name 没有可播放的帧")
                continue
            }
            for (i in 0 until frames.length()) {
                val frame = frames.optJSONObject(i)
                if (frame == null) {
                    errors.add("已使用动作 $name 的帧 $i 不是对象")
                    continue
                }
                if (frame.optString("res").isBlank())
                    errors.add("已使用动作 $name 的帧 $i 没有图片资源")
                // Delay is essential for frame progression; inactive settings
                // (including duration/finishCycle and other frame properties)
                // must not cause a whole package to be rejected.
                val delay = frame.opt("delay")
                val valid = when (delay) {
                    is Number -> delay.toLong() > 0
                    is JSONArray -> delay.length() == 2 &&
                        delay.opt(0) is Number && delay.opt(1) is Number &&
                        delay.optLong(0) > 0 && delay.optLong(1) >= delay.optLong(0)
                    else -> false
                }
                if (!valid) errors.add("已使用动作 $name 帧 $i 的播放延时无效")
            }
        }
        return ValidationResult(errors.isEmpty(), json.optString("version"), errors)
    }

    private fun playbackMode(mode: JSONObject): String {
        val explicit = mode.optString("loopMode")
        if (explicit in listOf("off", "timed", "infinite")) return explicit
        return when {
            !mode.optBoolean("loop", false) -> "off"
            mode.optJSONObject("duration") != null -> "timed"
            else -> "infinite"
        }
    }

    /** Only check images needed by mapped functions and active nextMode chains. */
    fun verifyAssets(dest: File, json: JSONObject): List<String> {
        val errors = mutableListOf<String>()
        val modes = json.optJSONObject("modes") ?: return errors
        val functions = runCatching { parseFunctions(json) }.getOrNull() ?: return errors
        val used = linkedSetOf<String>()
        for ((_, refs) in functions) for (name in refs) {
            if (modes.optJSONObject(name) != null) used.add(name)
        }
        val pending = java.util.ArrayDeque<String>()
        used.forEach { pending.add(it) }
        while (!pending.isEmpty()) {
            val mode = modes.optJSONObject(pending.removeFirst()) ?: continue
            if (playbackMode(mode) == "infinite") continue
            val next = mode.optString("nextMode")
            if (next.isNotBlank() && next != "null" &&
                modes.optJSONObject(next) != null && used.add(next)) pending.add(next)
        }

        val root = dest.canonicalFile
        val checked = mutableSetOf<String>()
        for (name in used) {
            val frames = modes.optJSONObject(name)?.optJSONArray("frames") ?: continue
            for (i in 0 until frames.length()) {
                val res = frames.optJSONObject(i)?.optString("res").orEmpty()
                if (res.isBlank() || !checked.add(res)) continue
                val file = File(root, res).canonicalFile
                if (!file.toPath().startsWith(root.toPath()) || !file.isFile || file.length() <= 0L) {
                    errors.add("缺失或非法图片：$res")
                    continue
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0)
                    errors.add("图片无法解码：$res")
            }
        }
        return errors
    }

    /** 校验字段为非空字符串（非布尔/数字/空串）。 */
    private fun checkNonEmptyString(json: JSONObject, key: String, errors: MutableList<String>) {
        val v = json.opt(key)
        when {
            !json.has(key) -> errors.add("缺少必备字段：$key")
            v !is String -> errors.add("$key 类型错误（应为非空字符串）")
            v.isBlank() -> errors.add("$key 不能为空")
        }
    }

    /** 校验字段为整数（JSON Int，非 String/布尔/缺失）。 */
    private fun checkInt(json: JSONObject, key: String, errors: MutableList<String>) {
        val v = json.opt(key)
        when {
            !json.has(key) -> errors.add("缺少必备字段：$key")
            v !is Int -> errors.add("$key 不是整数")
        }
    }

    /** 校验池为非空列表，且每个元素都是已定义动作名。 */
    private fun checkPool(json: JSONObject, key: String, definedModes: Set<String>, errors: MutableList<String>) {
        val arr = json.optJSONArray(key)
        when {
            arr == null -> {
                if (!json.has(key)) errors.add("缺少必备字段：$key")
                else errors.add("$key 不是列表")
            }
            arr.length() == 0 -> errors.add("$key 不能为空")
            else -> {
                for (i in 0 until arr.length()) {
                    val item = arr.optString(i, "")
                    if (item.isBlank()) errors.add("$key 第 ${i + 1} 项不是整数")
                    else if (definedModes.isNotEmpty() && !definedModes.contains(item)) {
                        errors.add("$key 含未定义动作：$item")
                    }
                }
            }
        }
    }
}
