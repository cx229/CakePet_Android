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
    fun dir(ctx: Context, id: String): File {
        val live = File(ctx.filesDir, "roles/$id")
        val backup = File(live.parentFile, "$id.bak")
        if (!live.exists() && backup.exists()) backup.renameTo(live)
        return live
    }
    fun duplicate(ctx: Context, source: RoleDescriptor, displayName: String): String {
        require(displayName.isNotBlank() && displayName.length <= 60) { "请填写有效的素材包名称" }
        val id = newId()
        val target = dir(ctx, id)
        try {
            if (source.source == RoleSource.ASSETS) copyAssets(ctx, source.rootPath, target)
            else check(dir(ctx, source.rootPath).copyRecursively(target))
            val file = File(target, "modes.jsonc")
            val json = prepareEditable(target, id, displayName.trim())
            json.put("name", displayName.trim())
            File(target, "modes.jsonc").let { manifest ->
                manifest.writeText(CompactJsoncWriter.write(json, manifest.readText()))
            }
            // A copied template already has a name: the dialog input must win.
            json.put("name", displayName.trim())
            File(target, "modes.jsonc").let { manifest ->
                manifest.writeText(CompactJsoncWriter.write(json, manifest.readText()))
            }
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
    /**
     * Transport and editing accept incomplete packs. Preserve malformed original JSONC,
     * add only structural defaults, and never invent pictures or animation frames.
     */
    fun prepareEditable(directory: File, id: String, suggestedName: String): JSONObject {
        directory.mkdirs()
        val file = File(directory, "modes.jsonc")
        val source = if (file.isFile) file.readText() else ""
        val parsed = runCatching { parseJsonc(source) }.getOrNull()
        if (source.isNotBlank() && (parsed == null ||
            parsed.optString("version") != RolePackageContract.VERSION)) {
            val original = File(directory, "modes.original.jsonc")
            if (!original.exists()) original.writeText(source)
        }
        val json = parsed ?: JSONObject()
        json.put("version", RolePackageContract.VERSION)
        json.put("id", id)
        if (json.optString("name").isBlank()) json.put("name", suggestedName.ifBlank { id })
        if (json.optString("shortName").isBlank()) json.put("shortName", json.optString("name"))
        if (json.optJSONArray("authors") == null) json.put("authors", JSONArray().put("未知"))
        if (json.opt("decodeTarget") !is Number) json.put("decodeTarget", 256)
        if (json.opt("anchorRes") !is Number) json.put("anchorRes", 128)
        if (json.optJSONObject("functions") == null) json.put("functions", JSONObject())
        if (json.optJSONObject("modes") == null) json.put("modes", JSONObject())
        // Normalize malformed action containers for the visual editor, retaining
        // the untouched source in a sidecar for manual recovery.
        val modes = json.getJSONObject("modes")
        var malformed = false
        val actionIds = modes.keys().asSequence().toList()
        for (actionId in actionIds) {
            val action = modes.optJSONObject(actionId)
            if (action == null) {
                modes.put(actionId, JSONObject().put("frames", JSONArray()))
                malformed = true
            } else if (action.optJSONArray("frames") == null) {
                action.put("frames", JSONArray())
                malformed = true
            }
        }
        if (malformed && source.isNotBlank()) {
            val original = File(directory, "modes.original.jsonc")
            if (!original.exists()) original.writeText(source)
        }
        file.writeText(CompactJsoncWriter.write(json, if (parsed == null) "" else source))
        return json
    }

    fun validationErrorsForRole(ctx: Context, role: RoleDescriptor): List<String> {
        if (role.source == RoleSource.FILES) return validationErrors(dir(ctx, role.rootPath))
        val temp = File(ctx.cacheDir, "validate_${System.nanoTime()}")
        return try {
            copyAssets(ctx, role.rootPath, temp)
            validationErrors(temp)
        } finally { temp.deleteRecursively() }
    }

    fun validationErrors(directory: File): List<String> {
        val file = File(directory, "modes.jsonc")
        val json = try { parseJsonc(file.readText()) }
        catch (e: Exception) { return listOf("modes.jsonc 解析失败：${e.message}") }
        return RolePackageContract.validate(json).errors + RolePackageContract.verifyAssets(directory, json)
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
        val configFile = File(draft, "modes.jsonc")
        configFile.writeText(CompactJsoncWriter.write(json, configFile.readText()))
        val live = dir(ctx, id)
        val backup = File(live.parentFile, "$id.bak")
        backup.deleteRecursively()
        check(live.renameTo(backup)) { "无法备份素材包" }
        if (!draft.renameTo(live)) { backup.renameTo(live); error("保存失败") }
        backup.deleteRecursively()
        RoleStore.updateUserRole(ctx, id, json.optString("name"), json.optString("shortName"), parseAuthors(json))
        if (validationErrors(live).isEmpty()) PetService.instance?.refreshMaterialPack(id)
    }
    fun export(ctx: Context, role: RoleDescriptor, uri: Uri) {
        ctx.contentResolver.openOutputStream(uri)?.use { stream ->
            ZipOutputStream(stream).use { zip ->
                if (role.source == RoleSource.ASSETS) zipAssets(ctx, role.rootPath, "", zip)
                else {
                    val root = dir(ctx, role.rootPath)
                    val config = File(root, "modes.jsonc")
                    val original = config.takeIf { it.isFile }?.readText().orEmpty()
                    val normalized = runCatching { parseJsonc(original) }.getOrNull() ?: JSONObject()
                    normalized.put("version", RolePackageContract.VERSION)
                    normalized.put("id", role.id)
                    if (normalized.optString("name").isBlank()) normalized.put("name", role.displayName)
                    if (normalized.optString("shortName").isBlank()) normalized.put("shortName", role.shortName.ifBlank { role.displayName })
                    if (normalized.optJSONArray("authors") == null) normalized.put("authors", JSONArray().put("未知"))
                    if (normalized.opt("decodeTarget") !is Number) normalized.put("decodeTarget", 256)
                    if (normalized.opt("anchorRes") !is Number) normalized.put("anchorRes", 128)
                    if (normalized.optJSONObject("functions") == null) normalized.put("functions", JSONObject())
                    if (normalized.optJSONObject("modes") == null) normalized.put("modes", JSONObject())
                    zip.putNextEntry(ZipEntry("modes.jsonc"))
                    zip.write(CompactJsoncWriter.write(normalized, if (original.isBlank()) "" else original).toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                    if (root.isDirectory) root.walkTopDown().filter { it.isFile && it != config }.forEach { file ->
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
    /** Uniquely named folder / file, safe for imports from arbitrary filenames. */
    fun uniqueName(parent: File, proposed: String, suffix: String = ""): String {
        val base = proposed.trim().take(80).map { c ->
            if (c == '/' || c == '\\' || c.code < 32 || c == ':') '_' else c
        }.joinToString("").trim('.', ' ').ifBlank { "素材" }
        var name = base + suffix
        var number = 2
        while (File(parent, name).exists()) name = "${base}_${number++}$suffix"
        return name
    }

    /** Import GIF into its own named folder; retain original delays for later insertion. */
    fun gif(ctx: Context, uri: Uri, draft: File, parentPath: String,
            gifName: String, onWatermarkRemoved: (ImportWatermark.Corner) -> Unit = {}): JSONArray {
        val data = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("无法读取 GIF")
        require(data.size >= 13 && String(data, 0, 3, Charsets.US_ASCII) == "GIF")
        val movie = Movie.decodeByteArray(data, 0, data.size) ?: error("GIF 解码失败")
        require(movie.width() > 0 && movie.height() > 0 && movie.width().toLong() * movie.height() <= 8_000_000)
        val delays = delays(data)
        require(delays.isNotEmpty() && delays.size <= 300)
        val root = draft.canonicalFile
        val parent = File(root, parentPath).canonicalFile
        require(parent.toPath().startsWith(root.toPath())) { "无效目录" }
        parent.mkdirs()
        val folder = File(parent, uniqueName(parent, gifName.replace(Regex("(?i)\\.gif$"), "")))
        check(folder.mkdirs()) { "无法创建 GIF 文件夹" }
        val frames = JSONArray()
        val manifest = JSONArray()
        var time = 0
        val duration = movie.duration().coerceAtLeast(delays.sum())
        delays.forEachIndexed { i, delay ->
            val bmp = Bitmap.createBitmap(movie.width(), movie.height(), Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bmp)
                canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                movie.setTime(time.coerceAtMost((duration - 1).coerceAtLeast(0)))
                movie.draw(canvas, 0f, 0f)
                val file = File(folder, "${(i + 1).toString().padStart(4, '0')}.png")
                FileOutputStream(file).use { output ->
                    check(bmp.compress(Bitmap.CompressFormat.PNG, 100, output)) { "GIF 帧 PNG 编码失败" }
                }
                check(file.isFile && file.length() > 0L) { "GIF 帧写入失败：${file.name}" }
                // Calculate paths against the same canonical root used to create the files.
                // On Android /data/user/0 and /data/data can resolve to one directory;
                // mixing canonical children with a noncanonical base creates bad res paths.
                val relative = file.canonicalFile.relativeTo(root).invariantSeparatorsPath
                frames.put(JSONObject().put("res", relative).put("delay", delay))
                manifest.put(JSONObject().put("file", file.name).put("delay", delay))
            } finally { bmp.recycle() }
            time += delay
        }
        if (ImportWatermark.enabled(ctx)) {
            val outputFiles = (1..frames.length()).map { frame ->
                File(folder, "${frame.toString().padStart(4, '0')}.png")
            }
            ImportWatermark.cleanGif(outputFiles, ImportWatermark.level(ctx))?.let(onWatermarkRemoved)
        }
        // Keep the on-disk folder, manifest, and Mode frame references in lockstep.
        // A missing frame is an import failure, never a silently accepted resource.
        val manifestFile = File(folder, "frames.json")
        manifestFile.writeText(JSONObject().put("frames", manifest).toString(2))
        val savedManifest = JSONObject(manifestFile.readText()).getJSONArray("frames")
        check(savedManifest.length() == frames.length() && frames.length() == delays.size) {
            "GIF 帧索引数量与实际帧数量不一致"
        }
        for (index in 0 until frames.length()) {
            val frameName = savedManifest.getJSONObject(index).getString("file")
            val image = File(folder, frameName)
            val relative = frames.getJSONObject(index).getString("res")
            check(image.isFile && image.length() > 0 && image.canonicalFile ==
                File(root, relative).canonicalFile) {
                "GIF 文件夹中的帧未正确关联：$relative"
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(image.absolutePath, bounds)
            check(bounds.outWidth > 0 && bounds.outHeight > 0) {
                "GIF 已生成帧无法重新识别：$relative"
            }
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
