package com.cx.cakepet

import org.json.JSONObject

/** Reads a JSONC configuration, stripping line and block comments outside strings. */
fun parseJsonc(source: String): JSONObject {
    val result = StringBuilder(source.length)
    var index = 0
    var inString = false
    var escaped = false
    while (index < source.length) {
        val ch = source[index]
        if (inString) {
            result.append(ch)
            if (escaped) escaped = false
            else if (ch == '\\') escaped = true
            else if (ch == '"') inString = false
            index++
            continue
        }
        if (ch == '"') {
            inString = true
            result.append(ch)
            index++
            continue
        }
        if (ch == '/' && index + 1 < source.length && source[index + 1] == '/') {
            result.append("  ")
            index += 2
            while (index < source.length && source[index] != '\n' && source[index] != '\r') {
                result.append(' ')
                index++
            }
            continue
        }
        if (ch == '/' && index + 1 < source.length && source[index + 1] == '*') {
            result.append("  ")
            index += 2
            var closed = false
            while (index < source.length) {
                if (source[index] == '*' && index + 1 < source.length && source[index + 1] == '/') {
                    result.append("  ")
                    index += 2
                    closed = true
                    break
                }
                result.append(if (source[index] == '\n' || source[index] == '\r') source[index] else ' ')
                index++
            }
            require(closed) { "Unterminated JSONC block comment" }
            continue
        }
        result.append(ch)
        index++
    }
    return JSONObject(result.toString())
}
