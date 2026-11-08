package com.cx.cakepet

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/** A duration belongs to one Function/Mode -> text reference, not the text resource. */
data class ThinkingDuration(val minMs: Long = DEFAULT_THINKING_MIN_MS,
                            val maxMs: Long = DEFAULT_THINKING_MAX_MS) {
    fun sample(): Long {
        val lo = minMs.coerceAtLeast(0)
        val hi = maxMs.coerceAtLeast(lo)
        return if (hi == lo) lo else Random.nextLong(lo, hi + 1)
    }
}

const val DEFAULT_THINKING_MIN_MS = 500L
const val DEFAULT_THINKING_MAX_MS = 4000L

/** Function/Mode policy and concrete per-ID timing values. */
data class ThinkingConfig(
    val policy: String = "cycle",
    val pool: List<String> = emptyList(),
    val minMs: Long = DEFAULT_THINKING_MIN_MS,
    val maxMs: Long = DEFAULT_THINKING_MAX_MS,
    val durations: Map<String, ThinkingDuration> = emptyMap()
) {
    fun durationFor(id: String): ThinkingDuration =
        durations[id] ?: ThinkingDuration(minMs, maxMs)
    fun sampleDuration(id: String): Long = durationFor(id).sample()
}

data class PackThinking(
    val texts: Map<String, String> = emptyMap(),
    val functions: Map<List<String>, ThinkingConfig> = emptyMap(),
    val modes: Map<String, ThinkingConfig> = emptyMap()
) {
    fun resolve(modeId: String, functionPath: List<String>): ThinkingConfig {
        val mode = modes[modeId]
        if (mode != null && mode.policy != "inherit") return mode
        // A Function's thinking is defined by its concrete leaf only.
        // A Mode may still inherit that leaf's configuration.
        return functions[functionPath] ?: ThinkingConfig()
    }

    companion object {
        private fun config(obj: JSONObject, default: String): ThinkingConfig {
            val policy = obj.optString("policy", default).takeIf {
                it in listOf("cycle", "hold", "off") || (default == "inherit" && it == "inherit")
            } ?: default
            val min = obj.optLong("min", DEFAULT_THINKING_MIN_MS).coerceAtLeast(0)
            val max = obj.optLong("max", DEFAULT_THINKING_MAX_MS).coerceAtLeast(min)
            val ids = mutableListOf<String>()
            val times = linkedMapOf<String, ThinkingDuration>()
            when (val pool = obj.opt("pool")) {
                is JSONObject -> {
                    val it = pool.keys()
                    while (it.hasNext()) {
                        val id = it.next()
                        if (id.isBlank()) continue
                        ids.add(id)
                        val duration = pool.optJSONObject(id)
                        times[id] = ThinkingDuration(
                            duration?.optLong("min", min)?.coerceAtLeast(0) ?: min,
                            duration?.optLong("max", max)?.coerceAtLeast(0) ?: max
                        ).let { if (it.maxMs < it.minMs) it.copy(maxMs = it.minMs) else it }
                    }
                }
                is JSONArray -> for (i in 0 until pool.length()) {
                    val id = pool.optString(i)
                    if (id.isNotBlank() && id !in ids) {
                        ids.add(id)
                        times[id] = ThinkingDuration(min, max)
                    }
                }
            }
            return ThinkingConfig(policy, ids, min, max, times)
        }

        fun parse(root: JSONObject): PackThinking {
            val strings = linkedMapOf<String, String>()
            root.optJSONObject("thinkings")?.let { obj ->
                val it = obj.keys()
                while (it.hasNext()) {
                    val id = it.next()
                    val raw = obj.opt(id)
                    val value = when (raw) {
                        is JSONObject -> raw.optString("text")
                        is String -> raw
                        else -> ""
                    }
                    if (value.isNotBlank()) strings[id] = value
                }
            }
            val functions = linkedMapOf<List<String>, ThinkingConfig>()
            fun visit(node: JSONObject, path: List<String>) {
                if (node.optJSONArray("modes") != null) {
                    node.optJSONObject("thinking")?.let { functions[path] = config(it, "cycle") }
                    return
                }
                val it = node.keys()
                while (it.hasNext()) {
                    val key = it.next()
                    if (key != "thinking" && key != "modes")
                        node.optJSONObject(key)?.let { child -> visit(child, path + key) }
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

/** Upgrade imported thinking data in place; exported/current packs always carry explicit timing. */
fun normalizeThinkingPack(root: JSONObject) {
    val catalog = root.optJSONObject("thinkings") ?: JSONObject().also { root.put("thinkings", it) }
    for (id in catalog.keys().asSequence().toList()) {
        when (val raw = catalog.opt(id)) {
            is String -> catalog.put(id, JSONObject().put("text", raw).put("groups", JSONArray()))
            is JSONObject -> if (raw.optJSONArray("groups") == null) raw.put("groups", JSONArray())
        }
    }
    fun normalize(config: JSONObject) {
        val min = config.optLong("min", DEFAULT_THINKING_MIN_MS).coerceAtLeast(0)
        val max = config.optLong("max", DEFAULT_THINKING_MAX_MS).coerceAtLeast(min)
        val pool = config.opt("pool")
        val result = JSONObject()
        val ids = when (pool) {
            is JSONObject -> pool.keys().asSequence().toList()
            is JSONArray -> (0 until pool.length()).map { pool.optString(it) }.filter { it.isNotBlank() }
            else -> emptyList()
        }
        for (id in ids.distinct()) {
            val obj = (pool as? JSONObject)?.optJSONObject(id)
            val lo = obj?.optLong("min", min)?.coerceAtLeast(0) ?: min
            val hi = obj?.optLong("max", max)?.coerceAtLeast(lo) ?: max
            result.put(id, JSONObject().put("min", lo).put("max", hi))
        }
        config.put("pool", result)
        config.remove("min")
        config.remove("max")
    }
    fun functions(node: JSONObject) {
        node.optJSONObject("thinking")?.let(::normalize)
        for (key in node.keys().asSequence().toList()) {
            if (key == "thinking" || key == "modes") continue
            node.optJSONObject(key)?.let(::functions)
        }
    }
    root.optJSONObject("functions")?.let(::functions)
    root.optJSONObject("modes")?.let { modes ->
        for (key in modes.keys().asSequence().toList())
            modes.optJSONObject(key)?.optJSONObject("thinking")?.let(::normalize)
    }
}

/**
 * Remove dangling references only: an existing but broken Mode or blank text
 * resource remains editable, while IDs that have no target cannot be repaired
 * by clicking them. Does not delete resource definitions or instance settings.
 */
fun cleanPackReferences(root: JSONObject) {
    val modeIds = root.optJSONObject("modes")?.keys()?.asSequence()?.toSet().orEmpty()
    val thinkingIds = root.optJSONObject("thinkings")?.keys()?.asSequence()?.toSet().orEmpty()
    fun filterModes(values: JSONArray): JSONArray = JSONArray().also { out ->
        for (i in 0 until values.length()) {
            val raw = values.opt(i)
            if (raw is String && raw.isNotBlank() && raw in modeIds) out.put(raw)
        }
    }
    fun cleanThinking(node: JSONObject) {
        val thinking = node.optJSONObject("thinking") ?: return
        when (val pool = thinking.opt("pool")) {
            is JSONArray -> {
                val filtered = JSONArray()
                for (i in 0 until pool.length()) {
                    val value = pool.opt(i)
                    if (value is String && value.isNotBlank() && value in thinkingIds) filtered.put(value)
                }
                thinking.put("pool", filtered)
            }
            is JSONObject -> for (id in pool.keys().asSequence().toList())
                if (id.isBlank() || id !in thinkingIds) pool.remove(id)
        }
    }
    fun cleanFunction(node: JSONObject) {
        node.optJSONArray("modes")?.let { node.put("modes", filterModes(it)) }
        cleanThinking(node)
        for (key in node.keys().asSequence().toList()) {
            when (val value = node.opt(key)) {
                is JSONArray -> if (key != "modes" && key != "pool" && key != "groups" &&
                    key != "thinking" && key != "usage" &&
                    (0 until value.length()).all { value.opt(it) is String })
                    node.put(key, filterModes(value))
                is JSONObject -> if (key != "thinking") cleanFunction(value)
            }
        }
    }
    root.optJSONObject("functions")?.let(::cleanFunction)
    root.optJSONObject("modes")?.let { modes ->
        for (id in modes.keys().asSequence().toList()) {
            val mode = modes.optJSONObject(id) ?: continue
            cleanThinking(mode)
            val next = mode.optString("nextMode")
            if (next.isNotBlank() && next != "null" && next !in modeIds)
                mode.remove("nextMode")
        }
    }
}
