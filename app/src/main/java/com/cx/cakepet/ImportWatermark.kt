package com.cx.cakepet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import java.io.File

/**
 * Conservative, alpha-only corner component remover for imported resources.
 * Never changes the source URI; each import processes at most one candidate.
 */
object ImportWatermark {
    private const val PREFS = "cakepet_global_import"
    private const val KEY = "remove_corner_watermark"

    fun enabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, true)

    fun setEnabled(ctx: Context, value: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, value).apply()
    }

    enum class Corner(val label: String) {
        TOP_LEFT("左上角"), TOP_RIGHT("右上角"),
        BOTTOM_LEFT("左下角"), BOTTOM_RIGHT("右下角")
    }

    private data class Candidate(val corner: Corner, val pixels: IntArray,
                                 val x0: Int, val y0: Int, val x1: Int, val y1: Int)

    private fun detect(bitmap: Bitmap): Candidate? {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 24 || h < 24 || w.toLong() * h > 8_000_000L) return null
        val data = IntArray(w * h)
        bitmap.getPixels(data, 0, w, 0, 0, w, h)
        fun occupied(index: Int) = Color.alpha(data[index]) >= 48
        val total = data.count { Color.alpha(it) >= 48 }
        if (total < 120) return null

        val regionW = (w / 4).coerceAtLeast(1)
        val regionH = (h / 4).coerceAtLeast(1)
        val candidates = ArrayList<Candidate>()
        for (corner in Corner.entries) {
            val left = if (corner == Corner.TOP_LEFT || corner == Corner.BOTTOM_LEFT) 0 else w - regionW
            val top = if (corner == Corner.TOP_LEFT || corner == Corner.TOP_RIGHT) 0 else h - regionH
            val visited = BooleanArray(regionW * regionH)
            val queue = IntArray(regionW * regionH)
            for (yy in 0 until regionH) for (xx in 0 until regionW) {
                val seed = yy * regionW + xx
                if (visited[seed] || !occupied((top + yy) * w + left + xx)) continue
                var head = 0
                var tail = 1
                queue[0] = seed
                visited[seed] = true
                var xMin = xx; var xMax = xx; var yMin = yy; var yMax = yy
                var touchesInnerEdge = false
                val component = ArrayList<Int>()
                while (head < tail) {
                    val point = queue[head++]
                    val cx = point % regionW; val cy = point / regionW
                    component.add((top + cy) * w + left + cx)
                    xMin = minOf(xMin, cx); xMax = maxOf(xMax, cx)
                    yMin = minOf(yMin, cy); yMax = maxOf(yMax, cy)
                    if ((left == 0 && cx == regionW - 1) ||
                        (left != 0 && cx == 0) ||
                        (top == 0 && cy == regionH - 1) ||
                        (top != 0 && cy == 0)) touchesInnerEdge = true
                    for (dy in -1..1) for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = cx + dx; val ny = cy + dy
                        if (nx !in 0 until regionW || ny !in 0 until regionH) continue
                        val next = ny * regionW + nx
                        if (!visited[next] && occupied((top + ny) * w + left + nx)) {
                            visited[next] = true
                            queue[tail++] = next
                        }
                    }
                }
                val count = component.size
                val width = xMax - xMin + 1
                val height = yMax - yMin + 1
                if (!touchesInnerEdge &&
                    count >= 3 && count <= (w.toLong() * h / 250).coerceAtLeast(4) &&
                    width <= w / 6 && height <= h / 6 &&
                    total > count * 8 &&
                    // Require the component to be nearer an actual canvas corner.
                    minOf(xMin, regionW - 1 - xMax) <= regionW / 2 &&
                    minOf(yMin, regionH - 1 - yMax) <= regionH / 2) {
                    candidates.add(Candidate(corner, component.toIntArray(),
                        left + xMin, top + yMin, left + xMax, top + yMax))
                }
            }
        }
        // Reject ambiguous frames containing several independent corner accents.
        if (candidates.size != 1) return null
        return candidates.single()
    }

    /** Returns the original bitmap when there is no confident candidate. */
    fun clean(bitmap: Bitmap): Pair<Bitmap, Corner?> {
        val candidate = detect(bitmap) ?: return bitmap to null
        val mutable = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val w = mutable.width; val h = mutable.height
        val pixels = IntArray(w * h)
        mutable.getPixels(pixels, 0, w, 0, 0, w, h)
        candidate.pixels.forEach { pixels[it] = Color.TRANSPARENT }
        mutable.setPixels(pixels, 0, w, 0, 0, w, h)
        return mutable to candidate.corner
    }

    /**
     * On GIF imports, only delete a corner component if its exact pixels remain
     * effectively unchanged in every frame. The same mask is then applied to
     * every decoded frame. Return null if no confident fixed overlay exists.
     */
    fun cleanGif(frameFiles: List<File>): Corner? {
        if (frameFiles.size < 2) return null
        val first = android.graphics.BitmapFactory.decodeFile(frameFiles.first().path) ?: return null
        val candidate = try { detect(first) } finally { first.recycle() } ?: return null
        val reference = android.graphics.BitmapFactory.decodeFile(frameFiles.first().path) ?: return null
        val original = IntArray(candidate.pixels.size) { index ->
            val at = candidate.pixels[index]
            reference.getPixel(at % reference.width, at / reference.width)
        }
        reference.recycle()
        for (idx in 1 until frameFiles.size) {
            val sample = android.graphics.BitmapFactory.decodeFile(frameFiles[idx].path) ?: return null
            try {
                if (sample.width.toLong() * sample.height > 8_000_000L) return null
                var same = 0
                for (i in candidate.pixels.indices) {
                    val at = candidate.pixels[i]
                    if (sample.getPixel(at % sample.width, at / sample.width) == original[i]) same++
                }
                if (same * 100 < candidate.pixels.size * 98) return null
            } finally { sample.recycle() }
        }
        // Re-encode staged PNGs only after the entire frame consistency check.
        for (file in frameFiles) {
            val bitmap = android.graphics.BitmapFactory.decodeFile(file.path) ?: return null
            try {
                val copy = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                try {
                    for (at in candidate.pixels) copy.setPixel(at % copy.width, at / copy.width, Color.TRANSPARENT)
                    file.outputStream().use { output ->
                        check(copy.compress(Bitmap.CompressFormat.PNG, 100, output))
                    }
                } finally { copy.recycle() }
            } finally { bitmap.recycle() }
        }
        return candidate.corner
    }
}
