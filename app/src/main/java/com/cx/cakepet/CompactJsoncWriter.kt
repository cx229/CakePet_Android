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


    /** Export-only formatting; editing and commit keep their existing writer. */
    fun writeExport(json: JSONObject, previousText: String = ""): String {
        val out = StringBuilder()
        val comments = commentsByUniqueKey(previousText)
        fun scalar(v: Any?): String = JSONArray().put(v ?: JSONObject.NULL).toString().let {
            it.substring(1, it.length - 1)
        }
        fun inline(v: Any?): String = when (v) {
            is JSONObject -> v.keys().asSequence().toList().joinToString(", ", "{", "}") {
                JSONObject.quote(it) + ": " + inline(v.opt(it))
            }
            is JSONArray -> (0 until v.length()).joinToString(", ", "[", "]") { inline(v.opt(it)) }
            else -> scalar(v)
        }
        fun ordered(obj: JSONObject, path: List<String>): List<String> {
            val keys = obj.keys().asSequence().toList()
            val preferred = when {
                path.isEmpty() -> listOf("version","id","name","shortName","authors",
                    "decodeTarget","anchorRes","note","usage","functions","modes","thinkings")
                path.size == 2 && path[0] == "modes" -> listOf("note","usage","loopMode",
                    "loop","duration","finishCycle","nextMode","groups","thinking","frames")
                else -> listOf("note","usage")
            }
            return preferred.filter { it in keys } + keys.filter { it !in preferred }
        }
        fun canInline(obj: JSONObject, path: List<String>): Boolean {
            if (path.isEmpty()) return false
            if (path.lastOrNull() == "duration") return true
            if (obj.keys().asSequence().any { obj.opt(it) is JSONObject }) return false
            if (path.size >= 4 && path[path.size - 2] == "frames") return true
            return inline(obj).length <= 110
        }
        val write = object {
            fun put(v: Any?, depth: Int, path: List<String>) {
                val pad = "  ".repeat(depth)
                when (v) {
                    is JSONObject -> {
                        if (canInline(v, path)) { out.append(inline(v)); return }
                        val keys = ordered(v, path)
                        if (keys.isEmpty()) { out.append("{}"); return }
                        out.append("{\n")
                        keys.forEachIndexed { i, key ->
                            comments[key]?.forEach { c ->
                                out.append("  ".repeat(depth + 1)).append(c).append('\n')
                            }
                            out.append("  ".repeat(depth + 1)).append(JSONObject.quote(key)).append(": ")
                            put(v.opt(key), depth + 1, path + key)
                            if (i != keys.lastIndex) out.append(',')
                            out.append('\n')
                        }
                        out.append(pad).append('}')
                    }
                    is JSONArray -> {
                        val primitives = (0 until v.length()).all { v.opt(it) !is JSONObject && v.opt(it) !is JSONArray }
                        if (v.length() == 0 || (primitives && inline(v).length <= 110)) {
                            out.append(inline(v)); return
                        }
                        out.append("[\n")
                        for (i in 0 until v.length()) {
                            out.append("  ".repeat(depth + 1))
                            put(v.opt(i), depth + 1, path + i.toString())
                            if (i < v.length()-1) out.append(',')
                            out.append('\n')
                        }
                        out.append(pad).append(']')
                    }
                    else -> out.append(scalar(v))
                }
            }
        }
        write.put(json, 0, emptyList())
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
            else -> out.append(JSONArray().put(value ?: JSONObject.NULL).toString().let { it.substring(1, it.length - 1) })
        }
    }

    private fun commentsByUniqueKey(source: String): Map<String, List<String>> {
        if (source.isBlank()) return emptyMap()
        // Conservative: preserve only standalone comments immediately before a key
        // whose name occurs once in this document. This avoids shifting a frame's
        // "delay" annotation to a different frame when frames are reordered.
        val keyLine = Regex("""^\s*"((?:[^"\\]|\\.)+)"\s*:""")
        val commentLine = Regex("""^\s*(//.*|/\*.*\*/)[ \t]*$""")
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
