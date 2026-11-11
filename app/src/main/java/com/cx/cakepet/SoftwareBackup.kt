package com.cx.cakepet

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Open ZIP of ordinary JSON and unchanged material files. Source system and
 * permission information is diagnostic only: it is NEVER restored or used to
 * gate importing a backup from another app version or device.
 */
object SoftwareBackup {
    private fun roleIds(ctx: Context): Set<String> {
        val ids = RoleStore.getHomeRoles(ctx).map { it.instanceId }.toMutableSet()
        ids += RoleRegistry.DEFAULT.id
        File(ctx.filesDir, "datastore").listFiles().orEmpty().forEach {
            val name = it.name
            if (name.startsWith("pet_config_") && name.endsWith(".preferences_pb")) {
                ids += name.removePrefix("pet_config_").removeSuffix(".preferences_pb")
            }
            if (name.startsWith("pet_bounds_") && name.endsWith(".preferences_pb")) {
                ids += name.removePrefix("pet_bounds_").removeSuffix(".preferences_pb")
            }
        }
        return ids.filter { it.isNotBlank() }.toSet()
    }

    private fun metadata(ctx: Context): JSONObject {
        val enabledService = ComponentName(ctx, CakePetAccessibilityService::class.java).flattenToString()
        val accessibility = Settings.Secure.getString(
            ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(":")?.any { it.equals(enabledService, ignoreCase = true) } == true
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return JSONObject()
            .put("type", "cakepet-backup")
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("createdAt", java.time.OffsetDateTime.now().toString())
            .put("device", JSONObject()
                .put("manufacturer", Build.MANUFACTURER)
                .put("brand", Build.BRAND)
                .put("model", Build.MODEL)
                .put("androidVersion", Build.VERSION.RELEASE)
                .put("androidApi", Build.VERSION.SDK_INT))
            .put("permissions", JSONObject()
                .put("overlay", Settings.canDrawOverlays(ctx))
                .put("accessibility", accessibility)
                .put("notifications", NotificationManagerCompat.from(ctx).areNotificationsEnabled())
                .put("batteryOptimizationExempt", pm.isIgnoringBatteryOptimizations(ctx.packageName)))
            .put("note", "device/permissions are only debug records, never restored")
    }

    private suspend fun configs(ctx: Context): Pair<JSONObject, JSONObject> {
        val config = JSONObject()
        val bounds = JSONObject()
        for (id in roleIds(ctx)) {
            val roleConfig = PetConfig.forRole(ctx, id)
            val roleBounds = PetBounds.forRole(ctx, id)
            // A full backup must include effective defaults, not only options
            // that have been explicitly changed since installation.
            roleConfig.update { it }
            for (page in SettingsPage.entries) {
                roleBounds.updateBounds(page, roleBounds.getBlocking(page))
            }
            config.put(id, roleConfig.exportSettings())
            bounds.put(id, roleBounds.exportSettings())
        }
        return config to bounds
    }

    /** These global options live in SharedPreferences OUTSIDE filesDir.
     * The misc/ file tree cannot capture or restore them.
     * Runtime exit flags and Android permissions are intentionally excluded.
     */
    private fun appSettings(ctx: Context): JSONObject {
        val background = ctx.getSharedPreferences("cakepet_background", Context.MODE_PRIVATE)
        return JSONObject()
            .put("personalization", JSONObject()
                .put("summonPosition", PersonalizationPrefs.summonPosition(ctx))
                .put("previewSource", PersonalizationPrefs.previewSource(ctx))
                .put("previewPat", PersonalizationPrefs.previewPat(ctx))
                .put("homePreviewMode", PersonalizationPrefs.homePreviewMode(ctx)))
            .put("watermark", JSONObject()
                .put("enabled", ImportWatermark.enabled(ctx))
                .put("level", ImportWatermark.level(ctx).name))
            .put("summonedRoles", JSONArray(
                background.getStringSet("summoned_roles", emptySet()).orEmpty().sorted()))
    }

    /** Validate optional global settings before the destructive confirmation.
     * A malformed optional entry must not stop the running pets after approval.
     */
    private fun validateAppSettings(settings: JSONObject) {
        settings.optJSONObject("personalization")?.let { p ->
            val spawn = p.optString("summonPosition", PersonalizationPrefs.BOTTOM_CENTER)
            require(spawn in listOf(PersonalizationPrefs.BOTTOM_CENTER, PersonalizationPrefs.RANDOM)) {
                "召唤位置设置无效"
            }
            val source = p.optString("previewSource", PersonalizationPrefs.STATIC_POOL)
            require(source in listOf(PersonalizationPrefs.PREVIEW, PersonalizationPrefs.STANDBY,
                PersonalizationPrefs.STATIC_POOL, PersonalizationPrefs.BLANK)) {
                "预览动画来源设置无效"
            }
            val mode = p.optString("homePreviewMode", PersonalizationPrefs.DYNAMIC)
            require(mode in listOf(PersonalizationPrefs.ALWAYS, PersonalizationPrefs.DYNAMIC)) {
                "召唤后预览方式无效"
            }
            if (p.has("previewPat")) require(p.opt("previewPat") is Boolean) {
                "摸摸头设置类型无效"
            }
        }
        settings.optJSONObject("watermark")?.let { w ->
            if (w.has("enabled")) require(w.opt("enabled") is Boolean) {
                "去水印开关类型无效"
            }
            if (w.has("level")) require(
                ImportWatermark.Level.entries.any { it.name == w.optString("level") }) {
                "去水印强度设置无效"
            }
        }
        settings.optJSONArray("summonedRoles")?.let { arr ->
            for (index in 0 until arr.length()) require(arr.opt(index) is String) {
                "召唤角色列表格式无效"
            }
        }
    }

    /** Restore through the preference APIs so their in-process caches update too. */
    private fun restoreAppSettings(ctx: Context, settings: JSONObject) {
        settings.optJSONObject("personalization")?.let { p ->
            if (p.has("summonPosition"))
                PersonalizationPrefs.setSummonPosition(ctx, p.getString("summonPosition"))
            if (p.has("previewSource"))
                PersonalizationPrefs.setPreviewSource(ctx, p.getString("previewSource"))
            if (p.has("previewPat"))
                PersonalizationPrefs.setPreviewPat(ctx, p.getBoolean("previewPat"))
            if (p.has("homePreviewMode"))
                PersonalizationPrefs.setHomePreviewMode(ctx, p.getString("homePreviewMode"))
        }
        settings.optJSONObject("watermark")?.let { w ->
            if (w.has("enabled")) ImportWatermark.setEnabled(ctx, w.getBoolean("enabled"))
            if (w.has("level"))
                ImportWatermark.setLevel(ctx, ImportWatermark.Level.valueOf(w.getString("level")))
        }
        settings.optJSONArray("summonedRoles")?.let { roles ->
            val selected = (0 until roles.length()).map { roles.getString(it) }.toSet()
            check(ctx.getSharedPreferences("cakepet_background", Context.MODE_PRIVATE)
                .edit().putStringSet("summoned_roles", selected).commit()) {
                "无法恢复已召唤角色状态"
            }
        }
    }

    fun export(ctx: Context, uri: Uri) {
        val (config, bounds) = runBlocking { configs(ctx) }
        val registry = runBlocking { RoleStore.exportRegistries(ctx) }
        val manifest = metadata(ctx).put("summary", JSONObject()
            .put("materialPacks", RoleStore.getUserRoles(ctx).size)
            .put("roleInstances", RoleStore.getHomeRoles(ctx).size))
        ctx.contentResolver.openOutputStream(uri)?.use { output ->
            ZipOutputStream(output).use { zip ->
                fun entry(name: String, bytes: ByteArray) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
                fun json(name: String, obj: JSONObject) =
                    entry(name, obj.toString(2).toByteArray(Charsets.UTF_8))
                json("manifest.json", manifest)
                entry("README.txt", ("CakePet full persistent-data backup\n" +
                    "Open as ordinary ZIP; images and JSONC resources are unchanged.\n" +
                    "Device and permission information in manifest.json is diagnostic only.\n" +
                    "Restore replaces the app's saved roles and settings, not system permissions.\n").toByteArray())
                json("registries.json", registry)
                json("role-config.json", config)
                json("role-bounds.json", bounds)
                json("user-habits.json", UserHabits.load(ctx))
                json("app-settings.json", appSettings(ctx))
                val root = File(ctx.filesDir, "roles")
                if (root.isDirectory) writeTree(zip, root, "roles/")
                // Extra persistent files are kept readable too; active DataStore
                // binary files must never be copied because their cache is live.
                ctx.filesDir.listFiles().orEmpty().filter {
                    it.name !in setOf("datastore", "roles", "user_habits.json") &&
                        !it.name.endsWith(".tmp")
                }.forEach { writeTree(zip, it, "misc/" + it.name) }
            }
        } ?: error("无法创建备份文件")
    }

    private fun writeTree(zip: ZipOutputStream, file: File, path: String) {
        if (file.isDirectory) {
            file.listFiles().orEmpty().forEach { child ->
                writeTree(zip, child, path.trimEnd('/') + "/" + child.name)
            }
        } else if (file.isFile) {
            zip.putNextEntry(ZipEntry(path))
            file.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }

    private fun extract(ctx: Context, uri: Uri): File {
        val temp = File(ctx.cacheDir, "cakepet_restore_" + System.nanoTime())
        check(temp.mkdirs())
        val root = temp.canonicalFile
        var size = 0L
        var count = 0
        try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input).use { zip ->
                    while (true) {
                        val item = zip.nextEntry ?: break
                        count++
                        require(count <= 50000) { "备份文件数量过多" }
                        val relative = item.name.replace('\\', '/')
                        require(relative.isNotEmpty() && !relative.startsWith("/") &&
                            !relative.split('/').contains("..") && !relative.contains(':')) { "非法备份路径" }
                        val target = File(root, relative).canonicalFile
                        require(target.path.startsWith(root.path + File.separator)) { "备份路径越界" }
                        if (item.isDirectory) target.mkdirs() else {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { output ->
                                val buffer = ByteArray(32768)
                                while (true) {
                                    val n = zip.read(buffer)
                                    if (n < 0) break
                                    size += n
                                    require(size <= 2_000_000_000L) { "备份文件过大" }
                                    output.write(buffer, 0, n)
                                }
                            }
                        }
                        zip.closeEntry()
                    }
                }
            } ?: error("无法打开备份")
            val meta = JSONObject(File(root, "manifest.json").readText())
            require(meta.optString("type") == "cakepet-backup") { "不是 CakePet 软件备份" }
            for (name in listOf("registries.json", "role-config.json", "role-bounds.json", "user-habits.json")) {
                JSONObject(File(root, name).readText())
            }
            // Backups from before the explicit app-wide settings record remain readable.
            File(root, "app-settings.json").takeIf { it.isFile }?.let {
                validateAppSettings(JSONObject(it.readText()))
            }
            return root
        } catch (e: Exception) {
            root.deleteRecursively()
            throw e
        }
    }

    /** Preflight validation before asking for destructive restore confirmation. */
    fun inspect(ctx: Context, uri: Uri): JSONObject {
        val staged = extract(ctx, uri)
        return try {
            JSONObject(File(staged, "manifest.json").readText())
        } finally {
            staged.deleteRecursively()
        }
    }

    /** All input is parsed and extracted before editing live state. */
    fun restore(ctx: Context, uri: Uri): String {
        val staged = extract(ctx, uri)
        val previousDir = File(ctx.cacheDir, "cakepet_previous_roles_" + System.nanoTime())
        val previousMisc = File(ctx.cacheDir, "cakepet_previous_misc_" + System.nanoTime())
        val liveRoles = File(ctx.filesDir, "roles")
        var moved = false
        var assetsTouched = false
        var miscMoved = false
        try {
            val registry = JSONObject(File(staged, "registries.json").readText())
            val restoredConfigs = JSONObject(File(staged, "role-config.json").readText())
            val bounds = JSONObject(File(staged, "role-bounds.json").readText())
            val habits = JSONObject(File(staged, "user-habits.json").readText())
            val restoredAppSettings = File(staged, "app-settings.json").takeIf { it.isFile }
                ?.let { JSONObject(it.readText()) }
            val oldAppSettings = appSettings(ctx)
            val originals = runBlocking { RoleStore.exportRegistries(ctx) }
            val oldConfigs: Pair<JSONObject, JSONObject> = runBlocking { configs(ctx) }
            val oldHabits = UserHabits.load(ctx)
            try {
                // Prepared assets replace the old directory as one unit.
                if (liveRoles.exists()) {
                    check(liveRoles.renameTo(previousDir)) { "无法暂存旧素材包" }
                    moved = true
                }
                assetsTouched = true
                val incoming = File(staged, "roles")
                if (incoming.exists()) {
                    check(incoming.renameTo(liveRoles)) { "无法安装素材包" }
                } else liveRoles.mkdirs()
                runBlocking {
                    RoleStore.importRegistries(ctx, registry, replace = true)
                    // Explicitly clear previously known role prefs not in backup.
                    val desired = RoleStore.getHomeRoles(ctx).map { it.instanceId }.toSet() +
                        PreferenceCodec.entries(restoredConfigs) + PreferenceCodec.entries(bounds)
                    val allIds = roleIds(ctx) + PreferenceCodec.entries(oldConfigs.first) +
                        PreferenceCodec.entries(restoredConfigs) + PreferenceCodec.entries(bounds)
                    for (id in allIds) {
                        // A retired role is removed. A setting absent from an
                        // older backup retains its current value (or app default).
                        val config = restoredConfigs.optJSONObject(id)
                        val bound = bounds.optJSONObject(id)
                        if (id !in desired) {
                            PetConfig.forRole(ctx, id).importSettings(JSONObject(), replace = true)
                            PetBounds.forRole(ctx, id).importSettings(JSONObject(), replace = true)
                        } else {
                            if (config != null) PetConfig.forRole(ctx, id).importSettings(config, replace = true)
                            if (bound != null) PetBounds.forRole(ctx, id).importSettings(bound, replace = true)
                        }
                    }
                }
                UserHabits.save(ctx, habits)
                // Replace other persistent files as a group (excluding live
                // DataStore files and the separately restored roles/habits).
                check(previousMisc.mkdirs()) { "无法创建临时备份目录" }
                val miscFiles = ctx.filesDir.listFiles().orEmpty().filter {
                    it.name !in setOf("datastore", "roles", "user_habits.json")
                }
                // Copy ALL originals first, before removing even one file.
                // A partial safety-copy failure must leave every original intact.
                miscFiles.forEach { old -> copyTree(old, File(previousMisc, old.name)) }
                miscMoved = true
                miscFiles.forEach { old ->
                    check(old.deleteRecursively()) { "无法替换持久化文件：${old.name}" }
                }
                File(staged, "misc").takeIf { it.isDirectory }?.listFiles().orEmpty()
                    .forEach { copyTree(it, File(ctx.filesDir, it.name)) }
                // Restore app-wide preferences after all file operations have
                // succeeded, so a failed restore can reinstate the old values.
                if (restoredAppSettings != null) restoreAppSettings(ctx, restoredAppSettings)
                previousMisc.deleteRecursively()
                previousDir.deleteRecursively()
                return if (restoredAppSettings == null)
                    "软件备份已恢复。素材包和角色设置已替换；此备份不含全局个性化及去水印设置；系统权限保持不变。"
                else
                    "软件备份已恢复。素材包、角色设置及全局偏好已恢复；系统权限保持不变。"
            } catch (problem: Exception) {
                val rollback = runCatching {
                    if (assetsTouched) liveRoles.deleteRecursively()
                    if (moved) check(previousDir.renameTo(liveRoles)) {
                        "无法还原原素材目录"
                    }
                    if (miscMoved) {
                        ctx.filesDir.listFiles().orEmpty().filter {
                            it.name !in setOf("datastore", "roles", "user_habits.json")
                        }.forEach { it.deleteRecursively() }
                        previousMisc.listFiles().orEmpty().forEach { old ->
                            check(old.renameTo(File(ctx.filesDir, old.name))) {
                                "无法还原原有文件：${old.name}"
                            }
                        }
                    }
                    runBlocking {
                        RoleStore.importRegistries(ctx, originals, replace = true)
                        for (id in PreferenceCodec.entries(oldConfigs.first) +
                            PreferenceCodec.entries(oldConfigs.second)) {
                            PetConfig.forRole(ctx, id).importSettings(
                                oldConfigs.first.optJSONObject(id) ?: JSONObject(), replace = true)
                            PetBounds.forRole(ctx, id).importSettings(
                                oldConfigs.second.optJSONObject(id) ?: JSONObject(), replace = true)
                        }
                    }
                    UserHabits.save(ctx, oldHabits)
                    restoreAppSettings(ctx, oldAppSettings)
                }
                if (rollback.isFailure) {
                    throw IllegalStateException(
                        "恢复失败，且自动回滚未完全成功；旧数据安全副本仍保留在应用缓存目录。原错误：${problem.message}",
                        rollback.exceptionOrNull()
                    )
                }
                throw problem
            }
        } finally {
            staged.deleteRecursively()
            // Keep any failed rollback's safety copy rather than deleting it.
        }
    }

    private fun copyTree(source: File, dest: File) {
        if (source.isDirectory) {
            dest.mkdirs()
            source.listFiles().orEmpty().forEach { copyTree(it, File(dest, it.name)) }
        } else {
            dest.parentFile?.mkdirs()
            source.copyTo(dest, overwrite = true)
        }
    }
}
