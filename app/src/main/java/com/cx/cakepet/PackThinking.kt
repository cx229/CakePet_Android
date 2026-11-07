package com.cx.cakepet

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/** Material-pack thinking data. IDs are opaque and only unique within a pack. */
data class ThinkingConfig(
    val policy: String = "cycle",
    val pool: List<String> = emptyList(),
    val minMs: Long = 500L,
    val maxMs: Long = 4000L
) {
    fun sampleDuration(): Long {
        val lo = minMs.coerceAtLeast(0L)
        val hi = maxMs.coerceAtLeast(lo)
        return if (hi == lo) lo else Random.nextLong(lo, hi + 1)
    }
}

data class PackThinking(
    val texts: Map<String, String> = emptyMap(),
    val functions: Map<List<String>, ThinkingConfig> = emptyMap(),
    val modes: Map<String, ThinkingConfig> = emptyMap()
) {
    fun resolve(modeId: String, functionPath: List<String>): ThinkingConfig {
        val mode = modes[modeId]
        if (mode != null && mode.policy != "inherit") return mode
        // Function metadata may be placed at a common parent of several function paths.
        for (length in functionPath.size downTo 1) {
            functions[functionPath.take(length)]?.let { return it }
        }
        return ThinkingConfig()
    }

    companion object {
        private fun ids(arr: JSONArray?): List<String> =
            if (arr == null) emptyList()
            else (0 until arr.length()).mapNotNull { i ->
                arr.optString(i).takeIf { it.isNotBlank() }
            }.distinct()

        private fun config(obj: JSONObject, default: String): ThinkingConfig {
            val policy = obj.optString("policy", default).takeIf {
                it == "cycle" || it == "hold" || it == "off" || (default == "inherit" && it == "inherit")
            } ?: default
            return ThinkingConfig(policy, ids(obj.optJSONArray("pool")),
                obj.optLong("min", 500L).coerceAtLeast(0L),
                obj.optLong("max", 4000L).coerceAtLeast(0L))
        }

        fun parse(root: JSONObject): PackThinking {
            val strings = linkedMapOf<String, String>()
            root.optJSONObject("thinkings")?.let { obj ->
                val it = obj.keys()
                while (it.hasNext()) {
                    val id = it.next()
                    val value = obj.optString(id)
                    if (value.isNotBlank()) strings[id] = value
                }
            }
            val functions = linkedMapOf<List<String>, ThinkingConfig>()
            fun visit(node: JSONObject, path: List<String>) {
                node.optJSONObject("thinking")?.let { functions[path] = config(it, "cycle") }
                val it = node.keys()
                while (it.hasNext()) {
                    val key = it.next()
                    if (key != "thinking") node.optJSONObject(key)?.let { child -> visit(child, path + key) }
                }
            }
            root.optJSONObject("functions")?.let { visit(it, emptyList()) }
            val modes = linkedMapOf<String, ThinkingConfig>()
            root.optJSONObject("modes")?.let { obj ->
                val it = obj.keys()
                while (it.hasNext()) {
                    val id = it.next()
                    obj.optJSONObject(id)?.optJSONObject("thinking")?.let { setting ->
                        modes[id] = config(setting, "inherit")
                    }
                }
            }
            return PackThinking(strings, functions, modes)
        }
    }
}
