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
        val ids = runCatching { RoleStore.getHomeRoles(ctx).map { it.instanceId }.toMutableSet() }
            .getOrDefault(mutableSetOf())
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

    /**
     * Full export is deliberately broader than import. Preserve currently
     * unrecognized preference keys as readable JSON for a future version or
     * manual inspection; never blindly apply them to the current runtime.
     */
    private fun rawPreferences(ctx: Context): JSONObject {
        val result = JSONObject()
        val dir = File(ctx.applicationInfo.dataDir, "shared_prefs")
        dir.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".xml") }
            .forEach { file ->
                val name = file.name.removeSuffix(".xml")
                if (name == "cakepet_exit") return@forEach // transient quit state
                val values = JSONObject()
                ctx.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (key, value) ->
                    val item: JSONObject? = when (value) {
                        is Boolean -> JSONObject().put("type", "boolean").put("value", value)
                        is Int -> JSONObject().put("type", "int").put("value", value)
                        is Long -> JSONObject().put("type", "long").put("value", value)
                        is Float -> if (value.isFinite()) JSONObject()
                            .put("type", "float").put("value", value.toDouble()) else null
                        is String -> JSONObject().put("type", "string").put("value", value)
                        is Set<*> -> if (value.all { it is String }) JSONObject()
                            .put("type", "strings").put("value", JSONArray(value.toList())) else null
                        else -> null
                    }
                    if (item != null) values.put(key, item)
                }
                result.put(name, values)
            }
        return result
    }

    /** Optional settings are independent: a bad value never rejects its siblings. */
    private fun restoreAppSettings(ctx: Context, settings: JSONObject): Int {
        var applied = 0
        settings.optJSONObject("personalization")?.let { p ->
            val spawn = p.opt("summonPosition") as? String
            if (spawn in listOf(PersonalizationPrefs.BOTTOM_CENTER, PersonalizationPrefs.RANDOM)) {
                PersonalizationPrefs.setSummonPosition(ctx, spawn!!)
                applied++
            }
            val source = p.opt("previewSource") as? String
            if (source in listOf(PersonalizationPrefs.PREVIEW, PersonalizationPrefs.STANDBY,
                    PersonalizationPrefs.STATIC_POOL, PersonalizationPrefs.BLANK)) {
                PersonalizationPrefs.setPreviewSource(ctx, source!!)
                applied++
            }
            (p.opt("previewPat") as? Boolean)?.let {
                PersonalizationPrefs.setPreviewPat(ctx, it)
                applied++
            }
            val mode = p.opt("homePreviewMode") as? String
            if (mode in listOf(PersonalizationPrefs.ALWAYS, PersonalizationPrefs.DYNAMIC)) {
                PersonalizationPrefs.setHomePreviewMode(ctx, mode!!)
                applied++
            }
        }
        settings.optJSONObject("watermark")?.let { w ->
            (w.opt("enabled") as? Boolean)?.let {
                ImportWatermark.setEnabled(ctx, it)
                applied++
            }
            val level = (w.opt("level") as? String)
                ?.let { name -> ImportWatermark.Level.entries.firstOrNull { it.name == name } }
            if (level != null) {
                ImportWatermark.setLevel(ctx, level)
                applied++
            }
        }
        settings.optJSONArray("summonedRoles")?.let { roles ->
            val known = RoleStore.getHomeRoles(ctx).map { it.instanceId }.toSet()
            val selected = (0 until roles.length()).mapNotNull { roles.opt(it) as? String }
                .filter { it in known }.toSet()
            // An explicitly empty source means 'no summoned pets'. A nonempty
            // but wholly incompatible list must not clear existing summon state.
            if (roles.length() == 0 || selected.isNotEmpty()) {
                if (ctx.getSharedPreferences("cakepet_background", Context.MODE_PRIVATE)
                    .edit().putStringSet("summoned_roles", selected).commit()) applied++
            }
        }
        return applied
    }

    private fun registryArray(registry: JSONObject, section: String, name: String): JSONArray? =
        runCatching {
            JSONArray(registry.optJSONObject(section)?.optJSONObject(name)?.getString("value"))
        }.getOrNull()

    private fun setRegistryArray(registry: JSONObject, section: String,
                                 name: String, values: JSONArray) {
        val store = registry.optJSONObject(section) ?: JSONObject().also { registry.put(section, it) }
        store.put(name, JSONObject().put("type", "string").put("value", values.toString()))
    }

    /**
     * Overlay only well-formed package groups and valid home references.
     * Preserve every existing entry and unrelated registry preference.
     * Imported packs stay unnormalized, including their unknown JSONC members.
     */
    private fun mergeRegistries(ctx: Context, current: JSONObject, source: JSONObject,
                                stagedRoles: File, liveRoles: File): Pair<JSONObject, Int> {
        val merged = JSONObject(current.toString())
        var applied = 0
        val installed = runCatching { RoleStore.getUserRoles(ctx).associateBy { it.id }.toMutableMap() }
            .getOrDefault(mutableMapOf())
        val candidates = registryArray(source, "userRoles", "user_roles")
        if (candidates != null) {
            for (i in 0 until candidates.length()) {
                val value = candidates.optJSONObject(i) ?: continue
                val id = value.optString("id")
                val relative = value.optString("rootPath")
                if (!id.matches(Regex("[a-zA-Z0-9_-]{1,100}")) ||
                    relative != id || value.optString("source") != "FILES") continue
                val sourceDir = File(stagedRoles, id)
                val manifest = File(sourceDir, "modes.jsonc")
                val parsed = runCatching { parseJsonc(manifest.readText()) }.getOrNull() ?: continue
                if (!parsed.has("modes") || !parsed.has("functions")) continue
                // An incomplete new pack may be imported for repair in the editor.
                // However, never replace a working pack used by existing roles
                // with a payload that cannot actually play its standby animation.
                val ready = RolePackageContract.validate(parsed).ok &&
                    MaterialFunctionSupport.available(parsed, MaterialFunctionSupport.NORMAL) { path ->
                        val root = sourceDir.canonicalFile
                        val file = File(root, path).canonicalFile
                        file.toPath().startsWith(root.toPath()) && file.isFile
                    }
                val existing = installed[id]
                if (existing != null && !ready) continue
                copyTree(sourceDir, File(liveRoles, id))
                val authors = value.optJSONArray("authors")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.opt(it) as? String }
                }.orEmpty()
                installed[id] = UserRoleTemplate(id,
                    value.optString("displayName").ifBlank { parsed.optString("name", id) },
                    id, -1, value.optString("shortName"), "FILES", authors)
                applied++
            }
        }
        val currentTemplates = registryArray(current, "userRoles", "user_roles")
        val mergedTemplates = JSONArray()
        val previous = mutableSetOf<String>()
        currentTemplates?.let { arr ->
            for (i in 0 until arr.length()) {
                val value = arr.optJSONObject(i) ?: continue
                val id = value.optString("id")
                if (id !in installed || !previous.add(id)) continue
                val t = installed.getValue(id)
                mergedTemplates.put(templateJson(t))
            }
        }
        installed.values.forEach { t ->
            if (previous.add(t.id)) mergedTemplates.put(templateJson(t))
        }
        setRegistryArray(merged, "userRoles", "user_roles", mergedTemplates)

        val eligible = installed.keys.filter { id ->
            val dir = File(liveRoles, id)
            runCatching {
                val config = parseJsonc(File(dir, "modes.jsonc").readText())
                MaterialFunctionSupport.available(config, MaterialFunctionSupport.NORMAL) { path ->
                    val root = dir.canonicalFile
                    val image = File(root, path).canonicalFile
                    image.toPath().startsWith(root.toPath()) && image.isFile
                }
            }.getOrDefault(false)
        }.toSet()
        val currentHome = runCatching { RoleStore.getHomeRoles(ctx).associateBy { it.instanceId }.toMutableMap() }
            .getOrDefault(mutableMapOf())
        val incomingHome = registryArray(source, "homeRoles", "home_roles")
        val importedHomeOrder = mutableListOf<String>()
        if (incomingHome != null) for (i in 0 until incomingHome.length()) {
            val obj = incomingHome.optJSONObject(i) ?: continue
            val id = obj.optString("instanceId")
            val template = obj.optString("templateId")
            if (!id.matches(Regex("[a-zA-Z0-9_-]{1,100}")) || template !in eligible) continue
            currentHome[id] = HomeRoleInstance(id, template,
                obj.optString("displayName"), obj.optString("shortName"))
            if (id !in importedHomeOrder) importedHomeOrder.add(id)
            applied++
        }
        val home = JSONArray()
        val sortedHome = importedHomeOrder.mapNotNull { currentHome[it] } +
            currentHome.values.filter { it.instanceId !in importedHomeOrder }
        sortedHome.forEach { item ->
            home.put(JSONObject().put("instanceId", item.instanceId)
                .put("templateId", item.templateId)
                .put("displayName", item.displayName).put("shortName", item.shortName))
        }
        setRegistryArray(merged, "homeRoles", "home_roles", home)

        // The catalog order is cosmetic; preserve current order, append valid new IDs.
        val oldOrder = runCatching { RoleStore.getCatalogOrder(ctx) }.getOrDefault(emptyList())
        val order = mutableListOf<String>()
        val incomingOrder = registryArray(source, "catalogOrder", "catalog_order")
        if (incomingOrder != null) for (i in 0 until incomingOrder.length()) {
            val id = incomingOrder.optString(i)
            if (id in installed && id !in order) order.add(id)
        }
        oldOrder.forEach { if (it in installed && it !in order) order.add(it) }
        setRegistryArray(merged, "catalogOrder", "catalog_order", JSONArray(order))
        return merged to applied
    }

    private fun templateJson(item: UserRoleTemplate): JSONObject =
        JSONObject().put("id", item.id).put("displayName", item.displayName)
            .put("rootPath", item.rootPath).put("packId", item.packId)
            .put("shortName", item.shortName).put("source", "FILES")
            .put("authors", JSONArray(item.authors))

    fun export(ctx: Context, uri: Uri) {
        val (config, bounds) = runBlocking { configs(ctx) }
        val registry = runBlocking { RoleStore.exportRegistries(ctx) }
        val manifest = metadata(ctx).put("summary", JSONObject()
            .put("materialPacks", runCatching { RoleStore.getUserRoles(ctx).size }.getOrDefault(-1))
            .put("roleInstances", runCatching { RoleStore.getHomeRoles(ctx).size }.getOrDefault(-1)))
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
                entry("README.txt", ("CakePet user-data archive\n" +
                    "Open as ordinary ZIP; images, JSONC and unknown extras are preserved.\n" +
                    "Import overlays only supported settings, packages and valid references.\n" +
                    "Unrecognized misc data stays in the ZIP; import does not apply it.\n" +
                    "Device and permission diagnostics are never restored.\n").toByteArray())
                json("registries.json", registry)
                json("role-config.json", config)
                json("role-bounds.json", bounds)
                json("user-habits.json", UserHabits.load(ctx))
                json("app-settings.json", appSettings(ctx))
                json("raw-shared-preferences.json", rawPreferences(ctx))
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
            // Individual version-specific sections are optional. An unsupported
            // section is skipped at import rather than rejecting the ZIP.
            // The manifest and archive paths are the only mandatory structure.
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
                .put("appSettingsIncluded", File(staged, "app-settings.json")
                    .takeIf { it.isFile }?.let {
                        runCatching { JSONObject(it.readText()) }.isSuccess
                    } == true)
        } finally {
            staged.deleteRecursively()
        }
    }

    /** Overlay backed-up settings without clearing current user data. */
    fun restore(ctx: Context, uri: Uri): String {
        val staged = extract(ctx, uri)
        val previousDir = File(ctx.cacheDir, "cakepet_previous_roles_" + System.nanoTime())
        val liveRoles = File(ctx.filesDir, "roles")
        var moved = false
        var assetsTouched = false
        try {
            fun section(name: String): JSONObject =
                runCatching { JSONObject(File(staged, name).readText()) }.getOrElse { JSONObject() }
            val registry = section("registries.json")
            val restoredConfigs = section("role-config.json")
            val restoredBounds = section("role-bounds.json")
            val restoredHabits = section("user-habits.json")
            val restoredApp = File(staged, "app-settings.json").takeIf { it.isFile }
                ?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
            val originals = runBlocking { RoleStore.exportRegistries(ctx) }
            val oldConfigs = runBlocking { configs(ctx) }
            val oldHabits = UserHabits.load(ctx)
            val oldApp = appSettings(ctx)
            val updatedRoles = mutableSetOf<String>()
            try {
                // Keep existing material packs intact, overlay only importable
                // packages and registry references, and retain an atomic
                // rollback copy of the previous directory.
                if (liveRoles.exists()) {
                    check(liveRoles.renameTo(previousDir)) { "无法暂存素材包" }
                    moved = true
                }
                assetsTouched = true
                check(liveRoles.mkdirs()) { "无法创建素材包目录" }
                if (moved) copyTree(previousDir, liveRoles)
                val (merged, catalogChanges) = mergeRegistries(ctx, originals, registry,
                    File(staged, "roles"), liveRoles)
                runBlocking { RoleStore.importRegistries(ctx, merged, replace = false) }

                var settingsCount = 0
                var skippedCount = 0
                val activeIds = RoleStore.getHomeRoles(ctx).map { it.instanceId }.toSet()
                val selectedIds = activeIds.filter { restoredConfigs.has(it) || restoredBounds.has(it) }
                runBlocking {
                    for (id in selectedIds) {
                        val config = PetConfig.forRole(ctx, id)
                        val bounds = PetBounds.forRole(ctx, id)
                        config.update { it }
                        for (page in SettingsPage.entries)
                            bounds.updateBounds(page, bounds.getBlocking(page))
                        val selectedConfig = restoredConfigs.optJSONObject(id)?.let {
                            PreferenceCodec.compatible(it, config.exportSettings(),
                                config.supportedPreferenceKeys(), config::acceptsImportedValue)
                        } ?: JSONObject()
                        val selectedBounds = restoredBounds.optJSONObject(id)?.let {
                            PreferenceCodec.compatible(it, bounds.exportSettings(),
                                bounds.supportedPreferenceKeys(), bounds::acceptsImportedValue)
                        } ?: JSONObject()
                        if (selectedConfig.length() > 0 || selectedBounds.length() > 0)
                            updatedRoles.add(id)
                        settingsCount += selectedConfig.length() + selectedBounds.length()
                        skippedCount += (restoredConfigs.optJSONObject(id)?.length() ?: 0) -
                            selectedConfig.length()
                        skippedCount += (restoredBounds.optJSONObject(id)?.length() ?: 0) -
                            selectedBounds.length()
                        if (selectedConfig.length() > 0) config.importSettings(selectedConfig)
                        if (selectedBounds.length() > 0) bounds.importSettings(selectedBounds)
                    }
                    val habitValues = restoredHabits.optJSONObject("bounds")
                    if (habitValues != null) {
                        val target = PetBounds.forRole(ctx, UserHabits.ROLE_ID)
                        for (page in SettingsPage.entries)
                            target.updateBounds(page, target.getBlocking(page))
                        val usable = PreferenceCodec.compatible(habitValues,
                            target.exportSettings(), target.supportedPreferenceKeys(),
                            target::acceptsImportedValue)
                        if (usable.length() > 0) target.importSettings(usable)
                        settingsCount += usable.length()
                        skippedCount += habitValues.length() - usable.length()
                    }
                }

                // Misc files are exported losslessly, but cannot be safely
                // interpreted as current settings. Do not overwrite active or
                // unknown files with unrecognized backup content.
                if (restoredApp != null) {
                    val imported = restoreAppSettings(ctx, restoredApp)
                    settingsCount += imported
                    val supplied = (restoredApp.optJSONObject("personalization")?.length() ?: 0) +
                        (restoredApp.optJSONObject("watermark")?.length() ?: 0) +
                        if (restoredApp.has("summonedRoles")) 1 else 0
                    skippedCount += (supplied - imported).coerceAtLeast(0)
                }
                for (id in PreferenceCodec.entries(restoredConfigs)) {
                    if (id !in activeIds)
                        skippedCount += restoredConfigs.optJSONObject(id)?.length() ?: 0
                }
                for (id in PreferenceCodec.entries(restoredBounds)) {
                    if (id !in activeIds)
                        skippedCount += restoredBounds.optJSONObject(id)?.length() ?: 0
                }
                // Revision-based preview caches must notice imported pack files.
                RolePackEditing.invalidateImported(RoleStore.getUserRoles(ctx).map { it.id })
                previousDir.deleteRecursively()
                return "导入完成：处理 ${catalogChanges} 条素材/角色记录、" +
                    "恢复 ${settingsCount} 项设置，跳过 ${skippedCount} 项不兼容设置。" +
                    "其他数据保留，系统权限未改变。"
            } catch (problem: Exception) {
                val rollback = runCatching {
                    if (assetsTouched) liveRoles.deleteRecursively()
                    if (moved) check(previousDir.renameTo(liveRoles)) {
                        "无法还原原素材包"
                    }
                    runBlocking {
                        RoleStore.importRegistries(ctx, originals, replace = true)
                        for (id in updatedRoles) {
                            PetConfig.forRole(ctx, id).importSettings(
                                oldConfigs.first.optJSONObject(id) ?: JSONObject(), replace = true)
                            PetBounds.forRole(ctx, id).importSettings(
                                oldConfigs.second.optJSONObject(id) ?: JSONObject(), replace = true)
                        }
                    }
                    UserHabits.save(ctx, oldHabits)
                    restoreAppSettings(ctx, oldApp)
                }
                if (rollback.isFailure) throw IllegalStateException(
                    "导入失败，且回滚未完全成功；旧数据副本仍保留在缓存。原错误：${problem.message}",
                    rollback.exceptionOrNull())
                throw problem
            }
        } finally {
            staged.deleteRecursively()
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
