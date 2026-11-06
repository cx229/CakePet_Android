package com.cx.cakepet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.File
import kotlin.math.abs

/**
 * Removes at most ONE isolated, transparent-background corner watermark group
 * from an imported bitmap/GIF. It does not edit the user's source document.
 */
object ImportWatermark {
    private const val PREFS = "cakepet_global_import"
    private const val ENABLED_KEY = "remove_corner_watermark"
    private const val LEVEL_KEY = "remove_corner_watermark_level"

    fun enabled(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED_KEY, true)

    fun setEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(ENABLED_KEY, enabled).apply()
    }

    enum class Level(val label: String, val region: Float, val maxWidth: Float,
                     val maxHeight: Float, val maxArea: Float, val gapRatio: Float,
                     val stableRatio: Float) {
        LOW("低", 0.20f, 0.19f, 0.12f, 0.012f, 0.012f, 0.995f),
        MEDIUM("中", 0.25f, 0.25f, 0.18f, 0.025f, 0.025f, 0.975f),
        HIGH("高", 0.30f, 0.33f, 0.24f, 0.045f, 0.045f, 0.93f)
    }

    fun level(ctx: Context): Level = runCatching {
        Level.valueOf(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(LEVEL_KEY, Level.MEDIUM.name) ?: Level.MEDIUM.name)
    }.getOrDefault(Level.MEDIUM)

    fun setLevel(ctx: Context, level: Level) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(LEVEL_KEY, level.name).apply()
    }

    enum class Corner(val label: String) {
        TOP_LEFT("左上角"), TOP_RIGHT("右上角"),
        BOTTOM_LEFT("左下角"), BOTTOM_RIGHT("右下角")
    }

    private data class Component(val x0: Int, val y0: Int, val x1: Int, val y1: Int,
                                 val area: Int)
    private data class Group(val corner: Corner, val x0: Int, val y0: Int,
                             val x1: Int, val y1: Int, val area: Int)

    /**
     * Analyze the four outer corners only. Flood-fill is local to each corner,
     * but any component touching its INNER boundary is rejected (it may belong
     * to the character outside the inspected region).
     *
     * Crucially, individual letters are NOT subjected to the final size test:
     * adjacent disconnected shapes are clustered before judging a watermark.
     */
    private fun detect(bitmap: Bitmap, level: Level): Group? {
        val w = bitmap.width
        val h = bitmap.height
        val size = w.toLong() * h
        if (w < 32 || h < 32 || size > 8_000_000L) return null
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        fun filled(index: Int) = Color.alpha(pixels[index]) >= 32
        val foreground = pixels.count { Color.alpha(it) >= 32 }
        if (foreground < 120) return null

        val cw = (w * level.region).toInt().coerceAtLeast(1)
        val ch = (h * level.region).toInt().coerceAtLeast(1)
        val gap = (minOf(w, h) * level.gapRatio).toInt().coerceAtLeast(3)
        val groups = mutableListOf<Group>()

        for (corner in Corner.entries) {
            val left = if (corner == Corner.TOP_LEFT || corner == Corner.BOTTOM_LEFT) 0 else w - cw
            val top = if (corner == Corner.TOP_LEFT || corner == Corner.TOP_RIGHT) 0 else h - ch
            val seen = BooleanArray(cw * ch)
            val queue = IntArray(cw * ch)
            val pieces = mutableListOf<Component>()

            for (sy in 0 until ch) for (sx in 0 until cw) {
                val start = sy * cw + sx
                if (seen[start] || !filled((top + sy) * w + left + sx)) continue
                var read = 0
                var end = 1
                queue[0] = start
                seen[start] = true
                var x0 = sx; var x1 = sx; var y0 = sy; var y1 = sy
                var inner = false
                while (read < end) {
                    val at = queue[read++]
                    val x = at % cw
                    val y = at / cw
                    x0 = minOf(x0, x); x1 = maxOf(x1, x)
                    y0 = minOf(y0, y); y1 = maxOf(y1, y)
                    if ((left == 0 && x == cw - 1) || (left != 0 && x == 0) ||
                        (top == 0 && y == ch - 1) || (top != 0 && y == 0)) inner = true
                    for (dy in -1..1) for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        val ny = y + dy
                        if (nx !in 0 until cw || ny !in 0 until ch) continue
                        val next = ny * cw + nx
                        if (!seen[next] && filled((top + ny) * w + left + nx)) {
                            seen[next] = true
                            queue[end++] = next
                        }
                    }
                }
                if (!inner) pieces.add(Component(left + x0, top + y0,
                    left + x1, top + y1, end))
            }

            // Join neighboring text glyphs / logo pieces into complete groups.
            // Large parts of the character are still filtered using final group
            // dimensions, occupied area and the inner-edge check above.
            val unassigned = pieces.indices.toMutableSet()
            while (unassigned.isNotEmpty()) {
                val cluster = mutableSetOf(unassigned.first())
                var changed: Boolean
                do {
                    changed = false
                    for (i in unassigned) {
                        if (i in cluster) continue
                        val p = pieces[i]
                        if (cluster.any { j ->
                            val q = pieces[j]
                            p.x0 <= q.x1 + gap && p.x1 + gap >= q.x0 &&
                                p.y0 <= q.y1 + gap && p.y1 + gap >= q.y0
                        }) {
                            cluster.add(i)
                            changed = true
                        }
                    }
                } while (changed)
                unassigned.removeAll(cluster)
                val x0 = cluster.minOf { pieces[it].x0 }
                val x1 = cluster.maxOf { pieces[it].x1 }
                val y0 = cluster.minOf { pieces[it].y0 }
                val y1 = cluster.maxOf { pieces[it].y1 }
                val area = cluster.sumOf { pieces[it].area }
                val boxW = x1 - x0 + 1
                val boxH = y1 - y0 + 1
                if (area < 16 || area > size * level.maxArea ||
                    boxW > w * level.maxWidth || boxH > h * level.maxHeight ||
                    foreground < area * 5 ||
                    // Candidate should remain close to both relevant outer edges.
                    (if (left == 0) x0 else w - 1 - x1) > cw / 2 ||
                    (if (top == 0) y0 else h - 1 - y1) > ch / 2) continue
                groups.add(Group(corner, x0, y0, x1, y1, area))
            }
        }
        if (groups.isEmpty()) return null
        // At most ONE watermark per import. Reject near-equal candidates rather
        // than deleting arbitrary accents in the corners.
        val sorted = groups.sortedByDescending { it.area }
        if (sorted.size > 1 && sorted[1].area > sorted[0].area * 0.5f) return null
        return sorted.first()
    }

    private fun bounds(group: Group, w: Int, h: Int): IntArray =
        intArrayOf((group.x0 - 1).coerceAtLeast(0),
            (group.y0 - 1).coerceAtLeast(0),
            (group.x1 + 1).coerceAtMost(w - 1),
            (group.y1 + 1).coerceAtMost(h - 1))

    /**
     * Clear the entire isolated watermark rectangle, including low-alpha
     * antialiasing, text holes, and separate letters on another line.
     */
    private fun erase(bitmap: Bitmap, group: Group): Bitmap {
        val copy = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val box = bounds(group, copy.width, copy.height)
        val width = box[2] - box[0] + 1
        val empty = IntArray(width) { Color.TRANSPARENT }
        for (y in box[1]..box[3]) {
            copy.setPixels(empty, 0, width, box[0], y, width, 1)
        }
        return copy
    }

    fun clean(bitmap: Bitmap, level: Level = Level.MEDIUM): Pair<Bitmap, Corner?> {
        val group = detect(bitmap, level) ?: return bitmap to null
        return erase(bitmap, group) to group.corner
    }

    /**
     * GIF: the same candidate mask must remain stable in ALL frames.
     * Compare alpha silhouette rather than exact RGB values, since GIF palettes
     * may change RGB even when the overlay stays in place.
     */
    fun cleanGif(files: List<File>, level: Level = Level.MEDIUM): Corner? {
        if (files.size < 2) return null
        val first = BitmapFactory.decodeFile(files.first().path) ?: return null
        val group = try { detect(first, level) } finally { first.recycle() } ?: return null
        val reference = BitmapFactory.decodeFile(files.first().path) ?: return null
        val box = bounds(group, reference.width, reference.height)
        val bw = box[2] - box[0] + 1
        val bh = box[3] - box[1] + 1
        val alpha = IntArray(bw * bh)
        try {
            reference.getPixels(alpha, 0, bw, box[0], box[1], bw, bh)
        } finally { reference.recycle() }
        // Require the overlay to contain a real opaque shape, not only noise.
        val baseline = alpha.map { Color.alpha(it) >= 32 }
        for (i in 1 until files.size) {
            val sample = BitmapFactory.decodeFile(files[i].path) ?: return null
            try {
                if (sample.width <= box[2] || sample.height <= box[3]) return null
                val current = IntArray(bw * bh)
                sample.getPixels(current, 0, bw, box[0], box[1], bw, bh)
                var same = 0
                for (j in current.indices) {
                    if ((Color.alpha(current[j]) >= 32) == baseline[j]) same++
                }
                if (same.toFloat() / current.size < level.stableRatio) return null
            } finally { sample.recycle() }
        }

        // Only write output after full verification. Original GIF URI is untouched.
        for (file in files) {
            val bitmap = BitmapFactory.decodeFile(file.path) ?: error("GIF 帧解码失败")
            try {
                val cleaned = erase(bitmap, group)
                try {
                    file.outputStream().use {
                        check(cleaned.compress(Bitmap.CompressFormat.PNG, 100, it)) { "去水印后的帧写入失败" }
                    }
                } finally { cleaned.recycle() }
            } finally { bitmap.recycle() }
        }
        return group.corner
    }
}
