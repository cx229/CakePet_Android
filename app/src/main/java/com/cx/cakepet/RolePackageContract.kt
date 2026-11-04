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

    /** 先提取版本号，再校验。尽量列出所有错误（非强制全量），不遇错即停。 */
    fun validate(json: JSONObject): ValidationResult {
        val errors = mutableListOf<String>()
        // v1.02 只接受通用 Modes/Functions；旧 resources 字典和替代图片字段不再有效。
        if (json.has("menuPool")) errors.add("菜单动作池已移入 functions.menu.pool")
        if (json.has("resources")) errors.add("v1.02 不再支持 resources 字典")
        json.optJSONObject("modes")?.keys()?.forEach { id ->
            if (json.optJSONObject("modes")?.optJSONObject(id)?.has("fallback") == true) {
                errors.add("动画 $id 不支持 fallback 字段")
            }
        }

        // 1) 版本号：非空字符串且必须等于 VERSION（缺失/类型错也收集，不提前返回）
        val version = json.opt("version")
        val versionStr = if (version is String) version else {
            if (!json.has("version")) errors.add("缺少必备字段：version")
            else errors.add("version 不是整数（应为字符串）")
            ""
        }
        if (versionStr.isNotEmpty() && versionStr != VERSION) {
            errors.add("不支持的资源包版本：$versionStr（当前仅支持 $VERSION）")
        }

        // 2) 顶层必填字符串：id / name / shortName 必须非空字符串
        checkNonEmptyString(json, "id", errors)
        checkNonEmptyString(json, "name", errors)
        checkNonEmptyString(json, "shortName", errors)

        // 2.5) 作者列表（必填）：必须是非空数组，且每一项均为非空字符串
        val authorsArr = json.optJSONArray("authors")
        if (authorsArr == null) {
            if (!json.has("authors")) errors.add("缺少必备字段：authors")
            else errors.add("authors 不是数组（应为非空字符串数组）")
        } else if (authorsArr.length() == 0) {
            errors.add("authors 为空数组（至少需填写一位作者）")
        } else {
            for (i in 0 until authorsArr.length()) {
                val v = authorsArr.opt(i)
                if (v !is String || v.isBlank()) {
                    errors.add("authors 第 ${i + 1} 项不是非空字符串")
                    break
                }
            }
        }

        // 3) 整数：decodeTarget / anchorRes 必须为整数（JSON Int，非 String/缺失）
        checkInt(json, "decodeTarget", errors)
        checkInt(json, "anchorRes", errors)

        // 4) 功能清单：功能路径必须存在，所有候选目标均应是 Mode。
        val functions = parseFunctions(json)
        val missingFuncs = REQUIRED_FUNCTION_PATHS.filter { it !in functions }
        if (missingFuncs.isNotEmpty()) {
            errors.add("缺少必备功能：${missingFuncs.joinToString(", ") { it.joinToString("/") }}")
        }

        // 5) 允许自由命名 Modes；只强制 Functions 所引用的动作必须存在
        val modes = json.optJSONObject("modes")
        val definedModes = modes?.keys()?.asSequence()?.toSet() ?: emptySet()
        if (modes == null) {
            if (!json.has("modes")) errors.add("缺少必备字段：modes")
            else errors.add("modes 不是整数（应为对象）")
        } else {
            for (name in definedModes) {
                val m = modes.optJSONObject(name)
                if (m == null) {
                    errors.add("动作 $name 不是整数（应为对象）")
                    continue
                }
                if (m.has("label")) errors.add("动作 $name 不再支持 label：动作名称就是 Key")
                if (m.has("timeNext")) errors.add("动作 $name 不再支持 timeNext")
                val loop = m.opt("loop")
                if (loop !is Boolean) errors.add("动作 $name loop 必须是布尔值")
                if (loop == false && (m.has("duration") || m.has("finishCycle")))
                    errors.add("动作 $name 非循环状态不能设置 duration 或 finishCycle")
                if (loop == true) {
                    val d = m.opt("duration")
                    if (m.has("duration") && d != JSONObject.NULL) {
                        val range = m.optJSONObject("duration")
                        if (range == null || range.opt("min") !is Number || range.opt("max") !is Number ||
                            range.optLong("min") <= 0 || range.optLong("max") < range.optLong("min"))
                            errors.add("动作 $name duration 必须包含有效 min/max")
                    }
                    if (m.has("finishCycle") && m.opt("finishCycle") !is Boolean)
                        errors.add("动作 $name finishCycle 必须是布尔值")
                }
                val fr = m.optJSONArray("frames")
                if (fr == null) errors.add("动作 $name 缺少 frames")
                else if (fr.length() == 0) errors.add("动作 $name 没有帧数据")
                val align = m.optString("verticalAlign", "top")
                if (align != "top" && align != "center") {
                    errors.add("动作 $name verticalAlign 必须为 top 或 center")
                }
            }
        }

        // Mode 是完整动画：每帧必须声明图片、有效下一帧索引和正数延时。
        // 图片文件的存在性/有效性由 verifyAssets() 在导入时一次性校验。
        modes?.keys()?.forEach { modeId ->
            val mode = modes.optJSONObject(modeId) ?: return@forEach
            val frames = mode.optJSONArray("frames") ?: return@forEach
            for (idx in 0 until frames.length()) {
                val frame = frames.optJSONObject(idx)
                if (frame == null) {
                    errors.add("动画 $modeId 帧 $idx不是对象")
                    continue
                }
                val res = frame.opt("res")
                if (res !is String || res.isBlank()) errors.add("动画 $modeId 帧 $idx缺少图片路径")
                if (frame.has("next")) errors.add("动画 $modeId 帧 $idx 不支持 next")
                val delay = frame.opt("delay")
                val validDelay = when (delay) {
                    is Number -> delay.toLong() > 0
                    is JSONArray -> delay.length() == 2 &&
                        delay.opt(0) is Number && delay.opt(1) is Number &&
                        delay.optLong(0) > 0 && delay.optLong(1) >= delay.optLong(0)
                    else -> false
                }
                if (!validDelay) errors.add("动画 $modeId 帧 $idx的 delay 无效")
            }
            if (mode.has("nextMode") && !mode.isNull("nextMode")) {
                val nextMode = mode.opt("nextMode")
                if (nextMode !is String || nextMode !in definedModes) {
                    errors.add("动画 $modeId 引用未定义的 nextMode")
                }
            }
        }

        // 6) 全部功能叶子，包括食物，统一指向已定义的 Mode。
        for ((path, leaves) in functions) {
            for (leaf in leaves) {
                if (leaf !in definedModes) {
                    errors.add("功能 ${path.joinToString("/")} 引用未定义动作：$leaf")
                }
            }
        }

        // 7) 菜单动作池已并入 Functions。
        for (path in listOf(listOf("standby", "pool"), listOf("static", "pool"), listOf("menu", "pool"))) {
            if (functions[path].isNullOrEmpty()) {
                errors.add("功能 ${path.joinToString("/")} 必须提供非空动作池")
            }
        }
        // 同一张 PNG 被多个 Mode 复用时，填充规则必须一致（位图缓存按图片资源键）。
        val alignByResource = mutableMapOf<String, String>()
        modes?.keys()?.forEach { modeName ->
            val mode = modes.optJSONObject(modeName) ?: return@forEach
            val align = mode.optString("verticalAlign", "top")
            val frames = mode.optJSONArray("frames") ?: return@forEach
            for (i in 0 until frames.length()) {
                val res = frames.optJSONObject(i)?.optString("res", "") ?: continue
                if (res.isEmpty()) continue
                val prev = alignByResource[res]
                if (prev == null) {
                    alignByResource[res] = align
                } else if (prev != align) {
                    errors.add("图片 $res 被不同的 verticalAlign 规则引用")
                }
            }
        }

        return ValidationResult(errors.isEmpty(), versionStr, errors)
    }

    /** 素材包导入必须完整：检查全部 Mode 帧文件存在且可解码，不设运行时缺帧兜底。 */
    fun verifyAssets(dest: File, json: JSONObject): List<String> {
        val errors = mutableListOf<String>()
        val root = dest.canonicalFile
        val modes = json.optJSONObject("modes") ?: return errors
        val validated = mutableSetOf<String>()
        modes.keys().forEach { modeId ->
            val frames = modes.optJSONObject(modeId)?.optJSONArray("frames") ?: return@forEach
            for (i in 0 until frames.length()) {
                val res = frames.optJSONObject(i)?.optString("res", "") ?: ""
                if (res.isBlank() || !validated.add(res)) continue
                val file = File(root, res).canonicalFile
                if (!file.toPath().startsWith(root.toPath()) || !file.isFile || file.length() <= 0L) {
                    errors.add("缺失或非法图片：$res")
                    continue
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    errors.add("图片无法解码：$res")
                }
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
