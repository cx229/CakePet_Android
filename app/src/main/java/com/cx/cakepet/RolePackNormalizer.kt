package com.cx.cakepet

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Editor-side compatibility catalog, deliberately separate from runtime readiness.
 * Empty pools are editable placeholders, NOT compulsory functions or playable modes.
 */
internal object RolePackNormalizer {
    const val REPORT_FILE = ".cakepet-import-report.json"
    const val ORIGINAL_FILE = "modes.original.jsonc"

    // Existing runtime contract + additional active paths represented by the shipped v1.10 template.
    val supportedPaths: List<List<String>> = (
        // climb/move and climb/jump_up are legacy contract aliases, not active engine paths.
        RolePackageContract.REQUIRED_FUNCTION_PATHS.filterNot {
            it == listOf("climb", "move") || it == listOf("climb", "jump_up")
        } + listOf(
            listOf("climb", "leap", "leap"),
            listOf("climb", "leap", "landing"),
            listOf("climb", "forage", "move"),
            MaterialFunctionSupport.SUMMONED,
            MaterialFunctionSupport.PREVIEW
        )
    ).distinct()

    private val knownNodes: Set<List<String>> = buildSet {
        supportedPaths.forEach { path ->
            (1..path.size).forEach { add(path.take(it)) }
        }
    }
    private val leaves = supportedPaths.toSet()

    data class Issue(val type: String, val path: String, val detail: String, val raw: String? = null) {
        fun toJson(): JSONObject = JSONObject().put("type", type).put("path", path)
            .put("detail", detail).apply { if (raw != null) put("raw", raw) }
    }

    private fun kind(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> "Object"
        is JSONArray -> "Array"
        is String -> "String"
        is Number -> "Number"
        is Boolean -> "Boolean"
        else -> value.javaClass.simpleName
    }

    /**
     * Repair known structural types, but keep unknown keys untouched.
     * A backup is required whenever an incoming value has to be replaced.
     */
    fun normalize(root: JSONObject): List<Issue> {
        val issues = mutableListOf<Issue>()
        for ((field, default) in listOf(
            "functions" to { JSONObject() },
            "modes" to { JSONObject() }
        )) {
            val original = root.opt(field)
            if (original !is JSONObject) {
                if (root.has(field)) issues += Issue("格式不支持", field,
                    "预期 Object，实际 ${kind(original)}；已创建可编辑结构，原值保存在原始配置中",
                    original?.toString())
                else issues += Issue("已补全", field, "缺少顶层对象，已创建空结构")
                root.put(field, default())
            }
        }
        val modes = root.getJSONObject("modes")
        if (root.opt("modeGroups") !is JSONArray) {
            // Keep declared empty groups; referenced names are appended by syncOrders().
            val names = linkedSetOf<String>()
            modes.keys().forEach { key ->
                val groups = modes.optJSONObject(key)?.optJSONArray("groups")
                if (groups != null) for (i in 0 until groups.length()) {
                    val name = groups.optString(i)
                    if (name.isNotBlank() && name != "全部" && name != "未分组") names.add(name)
                }
            }
            if (root.has("modeGroups")) issues += Issue("格式不支持", "modeGroups",
                "预期 Array，已重新建立列表", root.opt("modeGroups")?.toString())
            else issues += Issue("已补全", "modeGroups", "已建立动画组列表")
            root.put("modeGroups", JSONArray().apply { names.forEach { put(it) } })
        }
        for (id in modes.keys().asSequence().toList()) {
            val raw = modes.opt(id)
            if (raw !is JSONObject) {
                issues += Issue("格式不支持", "modes/$id",
                    "预期 Object，实际 ${kind(raw)}；已用空动作占位，原值保存在原始配置中",
                    raw?.toString())
                modes.put(id, JSONObject().put("frames", JSONArray()))
            } else if (raw.optJSONArray("frames") == null) {
                val previous = raw.opt("frames")
                issues += Issue(if (raw.has("frames")) "格式不支持" else "已补全",
                    "modes/$id/frames",
                    if (raw.has("frames")) "预期 Array，实际 ${kind(previous)}；已替换为空帧列表"
                    else "已补全空帧列表（不会生成图片）", previous?.toString())
                raw.put("frames", JSONArray())
            }
        }
        val functions = root.getJSONObject("functions")
        // Normalize every declared function path, including ones missing from incoming JSON.
        for (path in supportedPaths) {
            var node = functions
            for (i in 0 until path.lastIndex) {
                val key = path[i]
                val prefix = path.take(i + 1).joinToString("/")
                val current = node.opt(key)
                if (current !is JSONObject || current.has("modes")) {
                    if (node.has(key)) issues += Issue("格式不支持",
                        "functions/$prefix",
                        "预期文件夹 Object，实际 ${kind(current)}；原值已备份",
                        current?.toString())
                    node.put(key, JSONObject())
                }
                node = node.getJSONObject(key)
            }
            val key = path.last()
            val value = node.opt(key)
            val valid = value is JSONArray || (value is JSONObject && value.optJSONArray("modes") != null)
            if (!valid) {
                val missing = !node.has(key)
                // An absent mapping is equivalent to an explicit empty list.
                if (!missing) issues += Issue("格式不支持",
                    "functions/${path.joinToString("/")}",
                    "预期 Array 或带 modes 数组的 Object，实际 ${kind(value)}；已创建空池，原值已备份",
                    value?.toString())
                node.put(key, JSONArray())
            }
        }
        fun scan(node: JSONObject, path: List<String>) {
            for (key in node.keys().asSequence().toList()) {
                if (key in setOf("note", "usage", "thinking", "modes")) continue
                val next = path + key
                val value = node.opt(key)
                if (next !in knownNodes) {
                    issues += Issue("未定义", "functions/${next.joinToString("/")}",
                        "当前软件没有对应的内置功能路径；已保留原值，是否执行取决于调用方",
                        value?.toString())
                }
                if (value is JSONObject && value.optJSONArray("modes") == null) scan(value, next)
            }
        }
        scan(functions, emptyList())
        return issues
    }

    /** Preserve declared empty groups, append undeclared memberships, and reconcile
     * the image directory order against the actual disk tree. Only directory names
     * are sorted; image files and frame references are never moved or rewritten.
     */
    fun syncOrders(root: JSONObject, directory: File) {
        fun names(array: JSONArray?): List<String> =
            if (array == null) emptyList() else (0 until array.length())
                .map { array.optString(it) }.filter { it.isNotBlank() }.distinct()
        fun groupOrder(field: String, catalog: JSONObject?) {
            val ordered = LinkedHashSet(names(root.optJSONArray(field)).filter {
                it != "全部" && it != "未分组"
            })
            catalog?.keys()?.asSequence()?.toList()?.sorted()?.forEach { id ->
                val refs = catalog?.optJSONObject(id)?.optJSONArray("groups")
                names(refs).filter { it != "全部" && it != "未分组" }
                    .forEach { ordered.add(it) }
            }
            root.put(field, JSONArray().apply { ordered.forEach { put(it) } })
        }
        groupOrder("modeGroups", root.optJSONObject("modes"))
        groupOrder("thinkingGroups", root.optJSONObject("thinkings"))

        // v1.10: nested folder objects; preserve source-text key order.
        ResourceFolderOrder.sync(root, directory)
    }

    fun writeReport(directory: File, sourceVersion: String?, issues: List<Issue>, parseError: String? = null) {
        val report = JSONObject()
            .put("sourceVersion", sourceVersion ?: "未知")
            .put("targetVersion", RolePackageContract.VERSION)
            .put("issues", JSONArray().apply { issues.forEach { put(it.toJson()) } })
        if (parseError != null) report.put("parseError", parseError)
        File(directory, REPORT_FILE).writeText(report.toString(2))
    }

    fun report(directory: File): JSONObject? =
        runCatching { JSONObject(File(directory, REPORT_FILE).readText()) }.getOrNull()

    fun original(directory: File): File = File(directory, ORIGINAL_FILE)
}
