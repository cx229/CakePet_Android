package com.cx.cakepet

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 资源包契约（v1.0）。
 * 资源包最少需要包含的字段与动作。后续版本在此扩展，适配问题以后再处理。
 */
object RolePackageContract {
    const val VERSION = "1.0"

    /** 必备动作（v1.0）：待机 / 运动 / 投食 / 摸摸头 / 吸附（运动类本就无吸附态） */
    val REQUIRED_MODES = setOf(
        "sit_clam",      // 待机-静坐
        "lift_up",       // 运动-拖动
        "roll",          // 运动-滚动
        "jump_down",     // 运动-落地
        "walk",          // 运动-走
        "feed_fish",     // 投食-投食
        "pat_head",      // 摸摸头-摸摸头
        "shake_head",    // 摸摸头-摇头
        "sit_puffed",    // 摸摸头-炸毛
        "snap_head",     // 吸附-探头
        "snap_pat_head", // 吸附-探头摸摸头
        "snap_feed_fish" // 吸附-探头投食
    )

    /** 必备顶层字段（v1.0）：模板默认提供，缺失即视为错误 */
    val REQUIRED_TOP_FIELDS = setOf(
        "version", "id", "name", "shortName",
        "decodeTarget", "anchorRes", "resources", "standbyPool", "staticPool",
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

        // 4) 资源清单（对象）：必含 food（非空字符串路径）
        val resObj = json.optJSONObject("resources")
        if (resObj == null) {
            if (!json.has("resources")) errors.add("缺少必备字段：resources")
            else errors.add("resources 不是整数（应为对象）")
        } else {
            val food = resObj.opt("food")
            if (food !is String || food.isBlank()) errors.add("资源清单(resources)缺少必备项：food（应为非空路径）")
        }

        // 5) 动作清单：必含 REQUIRED_MODES，且每个动作 frames 非空
        val modes = json.optJSONObject("modes")
        val definedModes = modes?.keys()?.asSequence()?.toSet() ?: emptySet()
        if (modes == null) {
            if (!json.has("modes")) errors.add("缺少必备字段：modes")
            else errors.add("modes 不是整数（应为对象）")
        } else {
            val missing = REQUIRED_MODES.filter { !definedModes.contains(it) }
            if (missing.isNotEmpty()) {
                errors.add("缺少必备动作：${missing.joinToString(", ")}")
            }
            for (name in definedModes) {
                val m = modes.optJSONObject(name)
                if (m == null) {
                    errors.add("动作 $name 不是整数（应为对象）")
                    continue
                }
                val fr = m.optJSONArray("frames")
                if (fr == null) errors.add("动作 $name 缺少 frames")
                else if (fr.length() == 0) errors.add("动作 $name 没有帧数据")
            }
        }

        // 6) 池：standbyPool / staticPool 必须非空列表，且元素须为已定义动作
        checkPool(json, "standbyPool", definedModes, errors)
        checkPool(json, "staticPool", definedModes, errors)

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
