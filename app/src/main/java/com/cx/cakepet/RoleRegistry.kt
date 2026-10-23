package com.cx.cakepet

import android.content.Context
import org.json.JSONObject

/**
 * 角色注册表：当前仅预装角色（顺序即首页卡片顺序）。
 * 将来用户上传包 = 在 [scanUserRoles] 里扫描 filesDir/roles/<id>/ 含合法 modes.json 的包，
 * 并入 [PRESETS]；引擎（PetRole）与 [RoleDescriptor] 结构不变，故无需为未知角色改动底层。
 */
object RoleRegistry {

    /** 预装角色：启动时扫描 assets 下「含合法 modes.json」的素材目录自动注册（packId 取自 JSON）。不再写死。 */
    private var presets: List<RoleDescriptor> = emptyList()

    /** 默认角色（首启动 / 旧版 pet_config 迁移目标）= 预装中 packId 最小者（即 assets 排序的首个）。 */
    val DEFAULT: RoleDescriptor
        get() = PRESETS.first()

    /** 用户上传素材模板缓存（由 [refreshUserRoles] 从 RoleStore 载入，避免每次走 DataStore）。 */
    private var cachedUser: List<RoleDescriptor> = emptyList()

    /** 程序启动最早点调用：扫描预装包 + 载入用户上传模板。 */
    fun init(ctx: Context) {
        refreshPresets(ctx)
        refreshUserRoles(ctx)
    }

    /**
     * 预装角色清单：软件内唯一一处写死，且只表达最小必要信息——
     * 「packId(持久化于旧 pet_config 的资源包选择键) -> assets 素材目录名」。
     * 角色 id / 显示名 / 默认简称 / 尺寸 / 锚点 / 预览 / 鱼路径等全部来自该目录的 modes.json，
     * 与用户上传包走完全相同的加载路径，因此 modes.json 不做任何预装特化、不含 packId 等多余字段。
     * 新增预装包：在此加一行 + assets 放带合法 modes.json 的目录即可。
     */
    private val PRESET_FOLDERS: Map<Int, String> = mapOf(
        1 to "芝麻酥",
        2 to "芝麻球促销",
        3 to "白酥"
    )

    /**
     * 按 [PRESET_FOLDERS] 逐目录读取 modes.json 注册预装角色。
     * 角色 id / 显示名 / 默认简称 全部取自 JSON（不在软件里重复），packId 取自上面的单一映射。
     * 同时把 packId -> (id, rootPath) 映射同步给 [ImageModeManager]（即其 PRESET_PACKS），
     * 供 pet_config 持久化的资源包选择键解析。
     */
    fun refreshPresets(ctx: Context) {
        val list = mutableListOf<RoleDescriptor>()
        for ((packId, folder) in PRESET_FOLDERS) {
            val text = try {
                ctx.assets.open("$folder/modes.json").bufferedReader().use { it.readText() }
            } catch (_: Exception) { continue }   // 该目录无合法 modes.json，跳过
            val root = try { JSONObject(text) } catch (_: Exception) { continue }
            val id = root.optString("id", "")
            if (id.isBlank()) continue
            list += RoleDescriptor(
                id = id,
                displayName = root.optString("name", id),
                rootPath = folder,
                packId = packId,
                shortName = root.optString("shortName", ""),
                source = RoleSource.ASSETS,
                authors = parseAuthors(root)
            )
        }
        presets = list.sortedBy { it.packId }
        setPresetPacks(presets.associate { it.packId to PackRef(it.id, it.rootPath, RoleSource.ASSETS) })
    }

    /** 预装角色列表（懒扫描兜底：若早于 init 被访问且尚未填充，则立即按 appCtx 扫描一次）。 */
    val PRESETS: List<RoleDescriptor>
        get() {
            if (presets.isEmpty()) CakePetApplication.appCtx?.let { refreshPresets(it) }
            return presets
        }

    /** 从 RoleStore 重新载入用户上传素材模板。应用启动 / 导入 / 删除后调用。 */
    fun refreshUserRoles(ctx: Context) {
        cachedUser = RoleStore.getUserRoles(ctx).map { t ->
            RoleDescriptor(
                id = t.id,
                displayName = t.displayName,
                rootPath = t.rootPath,
                packId = t.packId,
                shortName = t.shortName,
                source = RoleSource.FILES,
                authors = t.authors
            )
        }
    }

    /** 全部角色（预装 ∪ 用户上传）。 */
    fun all(): List<RoleDescriptor> = PRESETS + cachedUser

    fun byId(id: String): RoleDescriptor? =
        PRESETS.firstOrNull { it.id == id } ?: cachedUser.firstOrNull { it.id == id }
}
