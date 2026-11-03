package com.cx.cakepet

import android.content.Context
import android.graphics.*
import android.net.Uri
import org.json.*
import java.io.*
import java.util.UUID
import java.util.zip.*

/** File-only functions for the v1.02 material editor. */
object RolePackEditing {
    fun newId() = "custom_" + UUID.randomUUID().toString().replace("-", "").take(16)
    fun dir(ctx: Context, id: String) = File(ctx.filesDir, "roles/$id")
    fun duplicate(ctx: Context, source: RoleDescriptor): String {
        val id = newId()
        val target = dir(ctx, id)
        try {
            if (source.source == RoleSource.ASSETS) copyAssets(ctx, source.rootPath, target)
            else check(dir(ctx, source.rootPath).copyRecursively(target))
            val file = File(target, "modes.jsonc")
            val json = parseJsonc(file.readText())
            json.put("id", id)
            json.put("name", json.optString("name") + " 副本")
            file.writeText(json.toString(2))
            verify(target, json)
            RoleStore.addUserRole(ctx, UserRoleTemplate(id, json.getString("name"), id, -1,
                json.optString("shortName"), "FILES", parseAuthors(json)))
            return id
        } catch (e: Exception) { target.deleteRecursively(); throw e }
    }
    private fun copyAssets(ctx: Context, path: String, out: File) {
        val children = ctx.assets.list(path).orEmpty()
        if (children.isEmpty()) {
            out.parentFile?.mkdirs()
            ctx.assets.open(path).use { input -> out.outputStream().use { input.copyTo(it) } }
        } else {
            out.mkdirs()
            children.forEach { copyAssets(ctx, "$path/$it", File(out, it)) }
        }
    }
    fun verify(directory: File, json: JSONObject) {
        val errors = RolePackageContract.validate(json).errors + RolePackageContract.verifyAssets(directory, json)
        require(errors.isEmpty()) { errors.take(12).joinToString("\n") }
    }
    fun draft(ctx: Context, id: String): File {
        val out = File(ctx.cacheDir, "edit_${id}_${System.nanoTime()}")
        check(dir(ctx, id).copyRecursively(out))
        return out
    }
    fun commit(ctx: Context, id: String, draft: File, json: JSONObject) {
        require(json.optString("id") == id) { "角色 ID 不可变更" }
        File(draft, "modes.jsonc").writeText(json.toString(2))
        verify(draft, json)
        val live = dir(ctx, id)
        val backup = File(live.parentFile, "$id.bak")
        backup.deleteRecursively()
        check(live.renameTo(backup)) { "无法备份素材包" }
        if (!draft.renameTo(live)) { backup.renameTo(live); error("保存失败") }
        backup.deleteRecursively()
        RoleStore.updateUserRole(ctx, id, json.optString("name"), json.optString("shortName"), parseAuthors(json))
    }
    fun export(ctx: Context, role: RoleDescriptor, uri: Uri) {
        ctx.contentResolver.openOutputStream(uri)?.use { stream ->
            ZipOutputStream(stream).use { zip ->
                if (role.source == RoleSource.ASSETS) zipAssets(ctx, role.rootPath, "", zip)
                else {
                    val root = dir(ctx, role.rootPath)
                    root.walkTopDown().filter { it.isFile }.forEach { file ->
                        zip.putNextEntry(ZipEntry(file.relativeTo(root).invariantSeparatorsPath))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
        } ?: error("无法写入 ZIP")
    }
    private fun zipAssets(ctx: Context, path: String, relative: String, zip: ZipOutputStream) {
        val children = ctx.assets.list(path).orEmpty()
        if (children.isEmpty()) {
            zip.putNextEntry(ZipEntry(relative))
            ctx.assets.open(path).use { it.copyTo(zip) }
            zip.closeEntry()
        } else children.forEach { zipAssets(ctx, "$path/$it",
            if (relative.isEmpty()) it else "$relative/$it", zip) }
    }
    /** Movie composites GIF frames; graphic control blocks provide frame delays. */
    fun gif(ctx: Context, uri: Uri, draft: File, mode: String): JSONArray {
        val data = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("无法读取 GIF")
        require(data.size >= 13 && String(data, 0, 3, Charsets.US_ASCII) == "GIF")
        val movie = Movie.decodeByteArray(data, 0, data.size) ?: error("GIF 解码失败")
        require(movie.width() > 0 && movie.height() > 0 && movie.width().toLong() * movie.height() <= 8_000_000)
        val delays = delays(data)
        require(delays.isNotEmpty() && delays.size <= 300)
        val folder = File(draft, "gif_${mode.filter { it.isLetterOrDigit() || it == '_' }}_${System.nanoTime()}")
        folder.mkdirs()
        val frames = JSONArray()
        var time = 0
        val duration = movie.duration().coerceAtLeast(delays.sum())
        delays.forEachIndexed { i, delay ->
            val bmp = Bitmap.createBitmap(movie.width(), movie.height(), Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bmp)
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                movie.setTime(time.coerceAtMost((duration - 1).coerceAtLeast(0)))
                movie.draw(canvas, 0f, 0f)
                val file = File(folder, "frame_${i.toString().padStart(4, '0')}.png")
                FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                frames.put(JSONObject().put("res", "${folder.name}/${file.name}")
                    .put("delay", delay).put("next", (i + 1) % delays.size))
            } finally { bmp.recycle() }
            time += delay
        }
        return frames
    }
    private fun delays(b: ByteArray): List<Int> {
        fun u(i: Int) = b[i].toInt() and 255
        fun skip(start: Int): Int {
            var i = start
            while (i < b.size) {
                val n = u(i++)
                if (n == 0) return i
                i += n
                require(i <= b.size)
            }
            error("GIF 不完整")
        }
        var i = 13
        if ((u(10) and 128) != 0) i += 3 * (1 shl ((u(10) and 7) + 1))
        val times = mutableListOf<Int>()
        var delay = 100
        while (i < b.size) {
            when (u(i++)) {
                0x3b -> break
                0x21 -> {
                    val label = u(i++)
                    if (label == 0xf9) {
                        require(u(i) == 4)
                        delay = ((u(i+2) or (u(i+3) shl 8)) * 10).coerceAtLeast(20)
                        i += 6
                    } else i = skip(i)
                }
                0x2c -> {
                    val local = u(i+8)
                    i += 9
                    if ((local and 128) != 0) i += 3 * (1 shl ((local and 7) + 1))
                    i++
                    i = skip(i)
                    times.add(delay)
                    delay = 100
                    require(times.size <= 300)
                }
                else -> error("GIF 数据无效")
            }
        }
        return times
    }
}
