package com.cx.cakepet

import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.util.WeakHashMap

/**
 * v1.10 resourceOrder: nested folder objects, whose source-text key order is the UI order.
 * JSONObject does not preserve insertion order on Android, so use a linked tree for edits
 * and replace only the resourceOrder value when writing JSONC.
 */
internal object ResourceFolderOrder {
    private class Node(val children: LinkedHashMap<String, Node> = linkedMapOf())
    private val pending = WeakHashMap<JSONObject, Node>()

    private fun parse(text: String): Node {
        val start = Regex("\\\"resourceOrder\\\"\\s*:\\s*\\{").find(text)?.range?.last ?: return Node()
        var pos = start + 1
        fun skip() {
            while (pos < text.length && (text[pos].isWhitespace() || text[pos] == ',')) pos++
        }
        fun string(): String {
            val begin = pos
            pos++
            var escaped = false
            while (pos < text.length) {
                val ch = text[pos++]
                if (escaped) escaped = false
                else if (ch == '\\') escaped = true
                else if (ch == '"') break
            }
            return JSONTokener(text.substring(begin, pos)).nextValue() as String
        }
        fun node(): Node {
            val result = Node()
            skip()
            while (pos < text.length && text[pos] != '}') {
                if (text[pos] != '"') break
                val key = string()
                skip()
                if (pos >= text.length || text[pos++] != ':') break
                skip()
                if (pos >= text.length || text[pos++] != '{') break
                result.children[key] = node()
                skip()
            }
            if (pos < text.length && text[pos] == '}') pos++
            return result
        }
        return node()
    }

    private fun load(directory: File): Node =
        File(directory, "modes.jsonc").takeIf { it.isFile }
            ?.let { runCatching { parse(it.readText()) }.getOrNull() } ?: Node()

    private fun child(root: Node, path: String): Node? =
        path.split('/').filter { it.isNotEmpty() }.fold(root as Node?) { parent, name ->
            parent?.children?.get(name)
        }

    fun names(directory: File, path: String): List<String> =
        child(load(directory), path)?.children?.keys?.toList().orEmpty()

    private fun align(node: Node, folder: File) {
        val actual = folder.listFiles().orEmpty()
            .filter { it.isDirectory && !it.name.startsWith(".") }
            .sortedBy { it.name.lowercase() }
        val known = node.children.toMap()
        node.children.clear()
        actual.map { it.name }.toSet().let { existing ->
            known.forEach { (name, subtree) -> if (name in existing) node.children[name] = subtree }
        }
        actual.forEach { if (it.name !in node.children) node.children[it.name] = Node() }
        actual.forEach { align(node.children.getValue(it.name), it) }
    }

    private fun Node.toJson(): JSONObject = JSONObject().also { objectValue ->
        children.forEach { (name, nested) -> objectValue.put(name, nested.toJson()) }
    }

    fun sync(json: JSONObject, directory: File) {
        val tree = pending[json] ?: load(directory)
        align(tree, directory)
        pending[json] = tree
        json.put("resourceOrder", tree.toJson())
    }

    fun reorder(json: JSONObject, directory: File, parentPath: String, names: List<String>) {
        val tree = load(directory)
        val parent = child(tree, parentPath) ?: return
        val previous = parent.children.toMap()
        parent.children.clear()
        names.forEach { if (it in previous) parent.children[it] = previous.getValue(it) }
        previous.forEach { (name, value) -> if (name !in parent.children) parent.children[name] = value }
        pending[json] = tree
        json.put("resourceOrder", tree.toJson())
    }

    fun rename(json: JSONObject, directory: File, path: String, newName: String) {
        val tree = load(directory)
        val parent = child(tree, path.substringBeforeLast('/', "")) ?: return
        val oldName = path.substringAfterLast('/')
        val updated = LinkedHashMap<String, Node>()
        parent.children.forEach { (name, node) ->
            updated[if (name == oldName) newName else name] = node
        }
        parent.children.clear()
        parent.children.putAll(updated)
        pending[json] = tree
        json.put("resourceOrder", tree.toJson())
    }

    fun dissolve(json: JSONObject, directory: File, path: String, moved: Map<String, String>) {
        val tree = load(directory)
        val parent = child(tree, path.substringBeforeLast('/', "")) ?: return
        val old = path.substringAfterLast('/')
        val updated = LinkedHashMap<String, Node>()
        parent.children.forEach { (name, node) ->
            if (name == old) {
                node.children.forEach { (childName, subtree) ->
                    val from = "$path/$childName"
                    val targetName = moved[from]?.substringAfterLast('/') ?: childName
                    updated[targetName] = subtree
                }
            } else updated[name] = node
        }
        parent.children.clear()
        parent.children.putAll(updated)
        pending[json] = tree
        json.put("resourceOrder", tree.toJson())
    }

    private fun render(tree: Node, depth: Int): String {
        if (tree.children.isEmpty()) return "{}"
        val pad = "  ".repeat(depth)
        val inner = "  ".repeat(depth + 1)
        return tree.children.entries.joinToString(",\n", "{\n", "\n$pad}") { (key, node) ->
            inner + JSONObject.quote(key) + ": " + render(node, depth + 1)
        }
    }

    /** Write just this subtree from its linked order, preserving all other package data. */
    fun serialize(json: JSONObject, written: String): String {
        val tree = pending[json] ?: return written
        val match = Regex("\\\"resourceOrder\\\"\\s*:\\s*\\{").find(written) ?: return written
        val open = match.range.last
        var depth = 1
        var at = open + 1
        var quoted = false
        var escaped = false
        while (at < written.length && depth > 0) {
            val ch = written[at++]
            if (quoted) {
                if (escaped) escaped = false
                else if (ch == '\\') escaped = true
                else if (ch == '"') quoted = false
            } else when (ch) {
                '"' -> quoted = true
                '{' -> depth++
                '}' -> depth--
            }
        }
        if (depth != 0) return written
        return written.substring(0, open) + render(tree, 1) + written.substring(at)
    }
}
