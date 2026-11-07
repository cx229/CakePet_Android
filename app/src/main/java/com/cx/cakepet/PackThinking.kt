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
        for (length in functionPath.size downTo 1) {
            functions[functionPath.take(length)]?.let { return it }
        }
        return ThinkingConfig()
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
