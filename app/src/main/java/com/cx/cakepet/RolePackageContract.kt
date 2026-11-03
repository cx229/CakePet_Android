package com.cx.cakepet

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 资源包契约（v1.01）。
 *
 * v1.01 起引入 functions：以「功能路径 → 叶子（模式名 / 资源键）」声明动作用途，
 * 代码【不再直接引用模式名】，一律经功能路径寻址；模式名标准化，角色差异只体现在
 * 图片路径与池的构成上。新增角色动作只需改 JSON，不必改代码。
 *
 * v1.01 同时新增 menuPool（悬浮菜单可触发的动作），并把 resources 的键由包自定
 * （如鱼在蜂蜜猫里叫 fish），代码依 functions 的 [feed, food] 叶子取值，不写死键名。
 */
object RolePackageContract {
    const val VERSION = "1.02"

    /**
     * 必备功能路径（v1.01）。层数不限，必须按【完整路径】寻址：
     * [snap, pat_head, pat_head]（吸附态摸头）与 [pat_head, pat_head]（普通摸头）末段同名而含义不同。
     *
     * 叶子是候选列表（为将来"同一功能多个动作"预留）。叶子目标类型由功能语义决定：
     * 除 [feed, food] 指向 resources 外，其余均指向 modes。
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
        listOf("feed", "food")                   // 投食：食物资源（指向 resources）
    )

    /** 叶子指向 resources（而非 modes）的功能路径 */
    private val RESOURCE_LEAF_PATHS = setOf(listOf("feed", "food"))

    /** 必备顶层字段（v1.0）：模板默认提供，缺失即视为错误 */
    val REQUIRED_TOP_FIELDS = setOf(
        "version", "id", "name", "shortName",
        "decodeTarget", "anchorRes", "menuPool",
        "functions", "resources", "modes",
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

        // 4) 功能清单（v1.01）：必备路径必须齐全。
        //    必须提前解析：下一步 resources 的必备项由 [feed, food] 叶子决定。
        //    叶子指向的【模式】是否存在留到第 6 步（拿到 definedModes 后）再校验。
        val functions = parseFunctions(json)
        val missingFuncs = REQUIRED_FUNCTION_PATHS.filter { it !in functions }
        if (missingFuncs.isNotEmpty()) {
            errors.add("缺少必备功能：${missingFuncs.joinToString(", ") { it.joinToString("/") }}")
        }

        // 5) 资源清单（对象）：必备项由 functions 的 [feed, food] 叶子给出，键名由包自定
        val resObj = json.optJSONObject("resources")
        if (resObj == null) {
            if (!json.has("resources")) errors.add("缺少必备字段：resources")
            else errors.add("resources 不是整数（应为对象）")
        } else {
            for (key in functions[listOf("feed", "food")] ?: emptyList()) {
                val v = resObj.opt(key)
                if (v !is String || v.isBlank()) {
                    errors.add("资源清单(resources)缺少必备项：$key（应为非空路径）")
                }
            }
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
                val fr = m.optJSONArray("frames")
                if (fr == null) errors.add("动作 $name 缺少 frames")
                else if (fr.length() == 0) errors.add("动作 $name 没有帧数据")
                val align = m.optString("verticalAlign", "top")
                if (align != "top" && align != "center") {
                    errors.add("动作 $name verticalAlign 必须为 top 或 center")
                }
            }
        }

        // 6) 功能叶子完整性：指向 modes 的叶子必须是已定义动作。
        //    [feed, food] 的叶子指向 resources，已在第 5 步单独校验，此处跳过。
        for ((path, leaves) in functions) {
            if (path in RESOURCE_LEAF_PATHS) continue
            for (leaf in leaves) {
                if (leaf !in definedModes) {
                    errors.add("功能 ${path.joinToString("/")} 引用未定义动作：$leaf")
                }
            }
        }

        // 7) 菜单池仍是素材包定义；长期状态轮播池改用 Functions 候选列表。
        checkPool(json, "menuPool", definedModes, errors)
        for (path in listOf(listOf("standby", "pool"), listOf("static", "pool"))) {
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
                val prev = alignByResource.putIfAbsent(res, align)
                if (prev != null && prev != align) {
                    errors.add("图片 $res 被不同的 verticalAlign 规则引用")
                }
            }
        }

        return ValidationResult(errors.isEmpty(), versionStr, errors)
    }

    /** 文件系统级：检查 resources 各值与各动作 frames[].res 引用的图片是否真实存在。返回缺失路径列表。 */
    fun verifyAssets(dest: File, json: JSONObject): List<String> {
        val missing = mutableListOf<String>()
        json.optJSONObject("resources")?.let { res ->
            res.keys().forEach { k ->
                val p = res.optString(k, "")
                if (p.isNotBlank() && !File(dest, p).exists()) missing.add(p)
            }
        }
        json.optJSONObject("modes")?.let { modes ->
            modes.keys().forEach { name ->
                modes.optJSONObject(name)?.optJSONArray("frames")?.let { frames ->
                    for (i in 0 until frames.length()) {
                        val res = frames.optJSONObject(i)?.optString("res", null)
                        if (!res.isNullOrBlank() && !File(dest, res).exists()) missing.add(res)
                    }
                }
            }
        }
        return missing
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
