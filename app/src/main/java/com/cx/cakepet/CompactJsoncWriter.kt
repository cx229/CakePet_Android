package com.cx.cakepet

import org.json.JSONArray
import org.json.JSONObject

/**
 * Human-readable v1.02 package writer.
 *
 * One frame per line, simple arrays inline, and nested Functions / Modes indented.
 * Comments are best-effort: whole-line comments preceding a uniquely named key
 * are carried over. Ambiguous annotations are not moved to potentially wrong fields.
 */
internal object CompactJsoncWriter {
    fun write(json: JSONObject, previousText: String = ""): String {
        val comments = commentsByUniqueKey(previousText)
        val out = StringBuilder()
        appendValue(out, json, 0, false, comments)
        return out.append('\n').toString()
    }

    private fun appendValue(
        out: StringBuilder, value: Any?, depth: Int, inline: Boolean,
        comments: Map<String, List<String>>
    ) {
        when (value) {
            is JSONObject -> {
                if (value.length() == 0) { out.append("{}"); return }
                if (inline) {
                    out.append("{")
                    val keys = value.keys().asSequence().toList()
                    keys.forEachIndexed { index, key ->
                        if (index > 0) out.append(", ")
                        out.append(JSONObject.quote(key)).append(": ")
                        appendValue(out, value.opt(key), depth + 1, true, emptyMap())
                    }
                    out.append("}")
                } else {
                    out.append("{\n")
                    val keys = value.keys().asSequence().toList()
                    keys.forEachIndexed { index, key ->
                        comments[key]?.forEach { comment ->
                            out.append("  ".repeat(depth + 1)).append(comment).append('\n')
                        }
                        out.append("  ".repeat(depth + 1))
                            .append(JSONObject.quote(key)).append(": ")
                        val child = value.opt(key)
                        // A frame object remains on one line; simple arrays
                        // (delays, action pools, groups) also stay on one line.
                        appendValue(out, child, depth + 1, false, comments)
                        if (index < keys.lastIndex) out.append(',')
                        out.append('\n')
                    }
                    out.append("  ".repeat(depth)).append('}')
                }
            }
            is JSONArray -> {
                if (value.length() == 0) { out.append("[]"); return }
                val primitivesOnly = (0 until value.length()).all {
                    value.opt(it) !is JSONObject && value.opt(it) !is JSONArray
                }
                val frameArray = (0 until value.length()).all { value.opt(it) is JSONObject } &&
                    // Only frames are serialized as one object per line.
                    // The caller marks them by passing an array of frame objects.
                    !inline
                if (inline || primitivesOnly) {
                    out.append('[')
                    for (i in 0 until value.length()) {
                        if (i > 0) out.append(", ")
                        appendValue(out, value.opt(i), depth + 1, true, emptyMap())
                    }
                    out.append(']')
                } else {
                    out.append("[\n")
                    for (i in 0 until value.length()) {
                        out.append("  ".repeat(depth + 1))
                        appendValue(out, value.opt(i), depth + 1, frameArray, comments)
                        if (i < value.length() - 1) out.append(',')
                        out.append('\n')
                    }
                    out.append("  ".repeat(depth)).append(']')
                }
            }
            else -> out.append(JSONObject.valueToString(value ?: JSONObject.NULL))
        }
    }

    private fun commentsByUniqueKey(source: String): Map<String, List<String>> {
        if (source.isBlank()) return emptyMap()
        // Conservative: preserve only standalone comments immediately before a key
        // whose name occurs once in this document. This avoids shifting a frame's
        // "delay" annotation to a different frame when frames are reordered.
        val keyLine = Regex("^\\s*\\\"((?:[^\\\"\\\\]|\\\\.)+)\\\"\\s*:")
        val commentLine = Regex("^\\s*(//.*|/\\*.*\\*/)[ \\t]*$")
        val lines = source.lines()
        val keys = lines.mapNotNull { keyLine.find(it)?.groupValues?.get(1) }
        val counts = keys.groupingBy { it }.eachCount()
        val result = mutableMapOf<String, List<String>>()
        val pending = mutableListOf<String>()
        for (line in lines) {
            val comment = commentLine.matchEntire(line)?.groupValues?.get(1)
            if (comment != null) {
                pending.add(comment)
                continue
            }
            val key = keyLine.find(line)?.groupValues?.get(1)
            if (key != null && pending.isNotEmpty() && counts[key] == 1) {
                result[key] = pending.toList()
            }
            pending.clear()
        }
        return result
    }
}
