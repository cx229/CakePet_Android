package com.cx.cakepet

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * 角色持久化层（两个 app 级 DataStore）：
 * - [user_roles]：全部玩家素材包清单（source=FILES），包括首次安装的白酥。
 * - [home_roles]：首页实例清单。每个实例绑定某素材模板（templateId），但 pet_config_<instanceId> /
 *   pet_bounds_<instanceId> 完全独立，故同模板可多次导入成互不干扰的多个实例。
 *
 * 实例 id 每次导入都用全新 UUID，与模板 id 无关（模板删除时按 templateId 级联清其全部实例）。
 */
private val Context.userRolesStore by preferencesDataStore("user_roles")
private val Context.homeRolesStore by preferencesDataStore("home_roles")
private val Context.catalogOrderStore by preferencesDataStore("catalog_order")

data class UserRoleTemplate(
    val id: String,
    val displayName: String,
    val rootPath: String,
    val packId: Int,
    val shortName: String = "",   // 素材包默认简称（来自 JSON）
    val source: String,
    val authors: List<String> = emptyList()   // 作者列表（来自 JSON "authors" 数组），用途待定
)

data class HomeRoleInstance(
    val instanceId: String,
    val templateId: String,
    val displayName: String = "",   // 空 = 回落模板 displayName（重命名独立，不写回素材包）
    val shortName: String = ""      // 空 = 回落素材包默认简称；重命名后独立生效
)

object RoleStore {

    /**
     * The APK's Bai Su pack is only a source for initial installation and for
     * creating new packs. Once installed it is an ordinary FILES package.
     * The marker is independent of catalog/home emptiness: deleting everything
     * must never silently recreate a character on the next launch.
     */
    @Synchronized fun ensureInitialRole(ctx: Context) {
        val prefs = runBlocking { ctx.homeRolesStore.data.first() }
        if (prefs[KEY_INITIAL_ROLE_CREATED] == true) return

        // Keep the package ID until both the catalog entry and home instance are ready.
        // If the process dies halfway through, the next attempt can remove just that package.
        val pendingId = prefs[KEY_INITIAL_ROLE_PENDING]
            ?.takeIf { it.matches(Regex("custom_[0-9a-f]{16}")) }
        val id = pendingId ?: RolePackEditing.newId()
        if (pendingId == null) {
            runBlocking { ctx.homeRolesStore.edit { it[KEY_INITIAL_ROLE_PENDING] = id } }
        } else {
            removeIncompleteInitialRole(ctx, id)
        }
        try {
            val template = RoleRegistry.DEFAULT
            RolePackEditing.duplicate(ctx, template, template.displayName, id)
            val instanceId = "inst_" + id.removePrefix("custom_")
            addHomeRole(ctx, HomeRoleInstance(instanceId, id))
            runBlocking {
                ctx.homeRolesStore.edit {
                    it[KEY_INITIAL_ROLE_CREATED] = true
                    it.remove(KEY_INITIAL_ROLE_PENDING)
                }
            }
        } catch (error: Exception) {
            try {
                removeIncompleteInitialRole(ctx, id)
            } catch (cleanupError: Exception) {
                error.addSuppressed(cleanupError)
            }
            throw error
        }
    }

    private fun removeIncompleteInitialRole(ctx: Context, id: String) {
        removeHomeRolesByTemplate(ctx, id)
        removeUserRole(ctx, id)
        check(RolePackEditing.dir(ctx, id).deleteRecursively()) { "Cannot remove incomplete starter pack" }
    }

    /** Include app-level registry metadata and initial-install marker. */
    suspend fun exportRegistries(ctx: Context): JSONObject = JSONObject().apply {
        put("userRoles", PreferenceCodec.snapshot(ctx.userRolesStore))
        put("homeRoles", PreferenceCodec.snapshot(ctx.homeRolesStore))
        put("catalogOrder", PreferenceCodec.snapshot(ctx.catalogOrderStore))
    }

    /** Restore through active stores to avoid DataStore stale-cache corruption. */
    suspend fun importRegistries(ctx: Context, json: JSONObject, replace: Boolean = false) {
        json.optJSONObject("userRoles")?.let {
            PreferenceCodec.apply(ctx.userRolesStore, it, replace)
            RoleRegistry.refreshUserRoles(ctx)
        }
        json.optJSONObject("homeRoles")?.let { PreferenceCodec.apply(ctx.homeRolesStore, it, replace) }
        json.optJSONObject("catalogOrder")?.let { PreferenceCodec.apply(ctx.catalogOrderStore, it, replace) }
    }

    /** 读取用户上传素材模板清单。 */
    fun getUserRoles(ctx: Context): List<UserRoleTemplate> = runBlocking {
        val raw = ctx.userRolesStore.data.first()[KEY_USER_ROLES] ?: "[]"
        parseTemplates(JSONArray(raw))
    }

    /** 新增一个素材模板，并刷新 RoleRegistry 缓存。 */
    fun addUserRole(ctx: Context, t: UserRoleTemplate) = runBlocking {
        ctx.userRolesStore.edit { prefs ->
            val arr = JSONArray(prefs[KEY_USER_ROLES] ?: "[]")
            arr.put(templateToJson(t))
            prefs[KEY_USER_ROLES] = arr.toString()
        }
        RoleRegistry.refreshUserRoles(ctx)
    }

    /** Update the catalog name after a valid package edit; keep the stable template identity. */
    fun updateUserRole(ctx: Context, id: String, name: String, shortName: String, authors: List<String>) = runBlocking {
        ctx.userRolesStore.edit { prefs ->
            val arr = JSONArray(prefs[KEY_USER_ROLES] ?: "[]")
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                if (obj.optString("id") == id) {
                    obj.put("displayName", name)
                    obj.put("shortName", shortName)
                    obj.put("authors", JSONArray(authors))
                }
            }
            prefs[KEY_USER_ROLES] = arr.toString()
        }
        RoleRegistry.refreshUserRoles(ctx)
    }

    /** 删除一个素材模板（仅 FILES），并刷新 RoleRegistry 缓存。 */
    fun removeUserRole(ctx: Context, id: String) = runBlocking {
        ctx.userRolesStore.edit { prefs ->
            val arr = JSONArray(prefs[KEY_USER_ROLES] ?: "[]")
            val out = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.getString("id") != id) out.put(o)
            }
            prefs[KEY_USER_ROLES] = out.toString()
        }
        RoleRegistry.refreshUserRoles(ctx)
    }

    /** Home instances exist only when explicitly created, including first installation. */
    fun getHomeRoles(ctx: Context): List<HomeRoleInstance> = runBlocking {
        val raw = ctx.homeRolesStore.data.first()[KEY_HOME_ROLES] ?: ""
        if (raw.isEmpty()) {
            // An intentionally empty home screen remains empty after deletion.
            emptyList()
        } else {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                HomeRoleInstance(
                    instanceId = o.getString("instanceId"),
                    templateId = o.getString("templateId"),
                    displayName = o.optString("displayName", ""),
                    shortName = o.optString("shortName", "")
                )
            }
            // Preserve unresolved templates; never erase instance-specific settings.
        }
    }

    private fun instancesToJson(list: List<HomeRoleInstance>): JSONArray = JSONArray().apply {
        list.forEach { inst ->
            put(JSONObject().apply {
                put("instanceId", inst.instanceId)
                put("templateId", inst.templateId)
                put("displayName", inst.displayName)
                put("shortName", inst.shortName)
            })
        }
    }

    /** 往首页追加一个实例（同模板可重复，instanceId 唯一）。 */
    fun addHomeRole(ctx: Context, inst: HomeRoleInstance) {
        runBlocking {
        ctx.homeRolesStore.edit { prefs ->
            val arr = JSONArray(prefs[KEY_HOME_ROLES] ?: "[]")
            arr.put(JSONObject().apply {
                put("instanceId", inst.instanceId)
                put("templateId", inst.templateId)
                put("displayName", inst.displayName)
                put("shortName", inst.shortName)
            })
            prefs[KEY_HOME_ROLES] = arr.toString()
        }
        }
        UserHabits.applyToNewRole(ctx, inst.instanceId)
    }

    /** 级联删除：移除某模板下的全部首页实例。 */
    fun removeHomeRolesByTemplate(ctx: Context, templateId: String) = runBlocking {
        ctx.homeRolesStore.edit { prefs ->
            val arr = JSONArray(prefs[KEY_HOME_ROLES] ?: "[]")
            val out = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (o.getString("templateId") != templateId) out.put(o)
            }
            prefs[KEY_HOME_ROLES] = out.toString()
        }
    }

    /**
     * 给定首页实例 id，解析其对应的素材模板描述符（PetService 启动角色 / 预览用）。
     * 找不到实例时回退到 RoleRegistry.byId（兼容 instanceId == templateId 的情形）。
     */
    fun descriptorForInstance(ctx: Context, instanceId: String): RoleDescriptor? {
        val inst = getHomeRoles(ctx).firstOrNull { it.instanceId == instanceId }
            ?: return RoleRegistry.byId(instanceId)
        return RoleRegistry.byId(inst.templateId)
    }

    internal fun setHomeRoles(ctx: Context, list: List<HomeRoleInstance>) = runBlocking {
        ctx.homeRolesStore.edit { prefs ->
            val arr = JSONArray()
            list.forEach {
                arr.put(JSONObject().apply {
                    put("instanceId", it.instanceId)
                    put("templateId", it.templateId)
                    put("displayName", it.displayName)
                    put("shortName", it.shortName)
                })
            }
            prefs[KEY_HOME_ROLES] = arr.toString()
        }
    }

    /** 清空用户上传素材库（user_roles）。 */
    fun clearUserRoles(ctx: Context) {
        try { runBlocking { ctx.userRolesStore.edit { it.clear() } } }
        catch (e: Exception) { android.util.Log.e("RoleStore", "clearUserRoles failed", e) }
    }

    /** 清空素材顺序（catalog_order）。 */
    fun clearCatalogOrder(ctx: Context) {
        try { runBlocking { ctx.catalogOrderStore.edit { it.clear() } } }
        catch (e: Exception) { android.util.Log.e("RoleStore", "clearCatalogOrder failed", e) }
    }

    /** Clear home instances and the first-install marker; reset explicitly reinstalls once. */
    fun clearHomeRoles(ctx: Context) {
        try { runBlocking { ctx.homeRolesStore.edit { it.clear() } } }
        catch (e: Exception) { android.util.Log.e("RoleStore", "clearHomeRoles failed", e) }
    }

    /**
     * 就地重置所有角色的【配置 PetConfig】与【阈值 PetBounds】：
     * 经各自【活跃的 DataStore 实例】写回系统默认值，而不是删除文件。
     *
     * 为什么不能删文件：PetConfig/PetBounds 均以 ConcurrentHashMap 按 roleId 缓存进程级单例，
     * 删文件不会清该缓存 → 旧实例仍持有旧内存快照，表现为「设置项看不出重置、点某项才突变、
     * 上下界不重置」，且后续任意写入会把旧值【写回磁盘】彻底污染重置结果。
     * 就地写回则 DataStore 的 data Flow 自动发射新值，内存与磁盘天然一致，且不会
     * 触发 "multiple DataStores active"（未新建第二个实例）。
     *
     * 与设置页「重置角色」（resetSystem + reset）走完全相同的正规路径。
     */
    fun resetAllRoleConfigAndBoundsInPlace(ctx: Context) {
        val ids = knownRoleIds(ctx)
        runBlocking {
            for (rid in ids) {
                try {
                    val bounds = PetBounds.forRole(ctx, rid)
                    SettingsPage.entries.forEach { page -> bounds.resetSystem(page) }
                    PetConfig.forRole(ctx, rid).reset()
                } catch (e: Exception) {
                    android.util.Log.e("RoleStore", "reset role in place failed: $rid", e)
                }
            }
        }
    }

    /** Only actual home instances own per-character settings. */
    private fun knownRoleIds(ctx: Context): List<String> =
        getHomeRoles(ctx).map { it.instanceId }.distinct()

    /**
     * A reset is the recovery boundary, not an import. Clear *all* saved
     * role stores using their live DataStore instances, including orphaned
     * IDs and user-habit defaults. Deleting open .preferences_pb files would
     * leave stale in-process caches behind.
     */
    fun resetAllSoftwareData(ctx: Context) {
        val names = mutableSetOf<String>()
        names += knownRoleIds(ctx)
        val dataDir = ctx.filesDir.resolve("datastore")
        dataDir.listFiles().orEmpty().forEach { file ->
            val name = file.name
            if (name.startsWith("pet_config_") && name.endsWith(".preferences_pb"))
                names += name.removePrefix("pet_config_").removeSuffix(".preferences_pb")
            if (name.startsWith("pet_bounds_") && name.endsWith(".preferences_pb"))
                names += name.removePrefix("pet_bounds_").removeSuffix(".preferences_pb")
        }
        names += UserHabits.ROLE_ID
        runBlocking {
            for (id in names) {
                PetConfig.forRole(ctx, id).importSettings(JSONObject(), replace = true)
                PetBounds.forRole(ctx, id).importSettings(JSONObject(), replace = true)
            }
            ctx.userRolesStore.edit { it.clear() }
            ctx.catalogOrderStore.edit { it.clear() }
            ctx.homeRolesStore.edit { it.clear() }
        }
        RoleRegistry.refreshUserRoles(ctx)
    }

    /** 素材页拖拽排序后的模板 id 顺序（仅素材页展示用，不影响首页实例排序）。 */
    fun getCatalogOrder(ctx: Context): List<String> = runBlocking {
        val raw = ctx.catalogOrderStore.data.first()[KEY_CATALOG_ORDER] ?: ""
        if (raw.isEmpty()) emptyList()
        else JSONArray(raw).let { (0 until it.length()).map { i -> it.getString(i) } }
    }

    /** 持久化素材页拖拽排序后的模板 id 顺序。 */
    fun setCatalogOrder(ctx: Context, order: List<String>) = runBlocking {
        ctx.catalogOrderStore.edit { prefs ->
            prefs[KEY_CATALOG_ORDER] = JSONArray(order).toString()
        }
    }

    /**
     * 按素材页持久化顺序返回模板清单：顺序中仍存在者按序排在前，
     * 不在顺序里的新模板（预装/后续导入）追加在末尾。仅素材页展示用，
     * 与首页实例排序完全独立（“这个排序仅在这一页起效”）。
     */
    fun orderedCatalog(ctx: Context): List<RoleDescriptor> {
        val order = getCatalogOrder(ctx)
        val all = RoleRegistry.all()
        val byId = all.associateBy { it.id }
        val result = mutableListOf<RoleDescriptor>()
        for (id in order) byId[id]?.let { result.add(it) }
        val seen = result.map { it.id }.toSet()
        all.forEach { if (it.id !in seen) result.add(it) }
        return result
    }

    /** 重命名某个首页实例：仅改实例记录里的 displayName/shortName，互不影响。 */
    fun updateHomeRoleRename(
        ctx: Context, instanceId: String,
        displayName: String, shortName: String
    ) {
        val list = getHomeRoles(ctx).map { h ->
            if (h.instanceId == instanceId)
                h.copy(displayName = displayName, shortName = shortName)
            else h
        }
        setHomeRoles(ctx, list)
    }

    /** 从首页移除某个实例（召唤/堆叠不再包含它）。 */
    fun removeHomeRole(ctx: Context, instanceId: String) {
        setHomeRoles(ctx, getHomeRoles(ctx).filter { it.instanceId != instanceId })
    }

    /**
     * 取实例简称，回落链：实例独立重命名 → 素材包 modes.json 的 shortName（缩写）→ 素材包 name。
     * name / shortName 都来自素材包 JSON，本就是为简称准备的，故不再用“显示名末字 / 角色”这类凑数兜底。
     * 终极兜底 "角色" 仅在 JSON 连 name 都缺失时触发（正常不会发生）。
     */
    fun resolveShortName(ctx: Context, instanceId: String): String {
        val inst = getHomeRoles(ctx).firstOrNull { it.instanceId == instanceId }
        inst?.shortName?.takeIf { it.isNotBlank() }?.let { return it }
        val tmpl = descriptorForInstance(ctx, instanceId)
        return tmpl?.shortName?.takeIf { it.isNotBlank() } ?: tmpl?.displayName ?: "角色"
    }

    /** 取实例显示名（优先独立重命名，否则回退模板名）。 */
    fun resolveDisplayName(ctx: Context, instanceId: String): String {
        val inst = getHomeRoles(ctx).firstOrNull { it.instanceId == instanceId }
        val renamed = inst?.displayName?.takeIf { it.isNotBlank() }
        if (renamed != null) return renamed
        return descriptorForInstance(ctx, instanceId)?.displayName ?: "未知角色"
    }

    private fun templateToJson(t: UserRoleTemplate) = JSONObject().apply {
        put("id", t.id)
        put("displayName", t.displayName)
        put("rootPath", t.rootPath)
        put("packId", t.packId)
        put("shortName", t.shortName)
        put("source", t.source)
        put("authors", JSONArray().apply { t.authors.forEach { put(it) } })
    }

    private fun parseTemplates(arr: JSONArray): List<UserRoleTemplate> =
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            UserRoleTemplate(
                id = o.getString("id"),
                displayName = o.getString("displayName"),
                rootPath = o.getString("rootPath"),
                packId = o.optInt("packId", -1),
                shortName = o.optString("shortName", ""),
                source = o.optString("source", "FILES"),
                authors = parseAuthors(o)
            )
        }

    private val KEY_USER_ROLES = stringPreferencesKey("user_roles")
    private val KEY_HOME_ROLES = stringPreferencesKey("home_roles")
    private val KEY_INITIAL_ROLE_CREATED = booleanPreferencesKey("initial_role_created")
    private val KEY_INITIAL_ROLE_PENDING = stringPreferencesKey("initial_role_pending")
    private val KEY_CATALOG_ORDER = stringPreferencesKey("catalog_order")
}
