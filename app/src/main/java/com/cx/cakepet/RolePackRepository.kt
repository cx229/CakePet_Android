package com.cx.cakepet

import android.content.Context
import org.json.JSONObject
import org.json.JSONArray
import java.io.File

/** 角色素材包定位、加载及 Functions 解析。具体的 Mode 播放器不依赖此角色管理代码。 */

/**
 * 资源包目录：选择键(Int，持久化于 pet_config) -> (包 id, 资源根目录 base, 存储类型)。
 * 由 [RoleRegistry.refreshPresets] 启动时按其内唯一的 [PRESET_FOLDERS] 映射
 * （packId -> assets 目录）填入：遍历各目录读取 modes.jsonc 得到 id/base，其余元数据都来自 JSON。
 * 包级元数据（名称/尺寸/锚点/预览/鱼路径等）全部来自各包 modes.jsonc，不做预装特化。
 * 用户上传包将来由 RoleRegistry.scanUserRoles 扫描 filesDir/roles/<id>/ 发现，走同一加载路径。
 */
data class PackRef(val id: String, val base: String, val source: RoleSource)

/**
 * 预装包映射（运行时由 [setPresetPacks] 填充）。初始为空，[RoleRegistry.refreshPresets]
 * 在 Application 最早点扫描 assets 写入；[buildSpritePack] 也内置按 RoleRegistry 的动态回落，
 * 故即便早于填充被调用也不会崩。
 */
var PRESET_PACKS: Map<Int, PackRef> = emptyMap()
    internal set

/** 由 RoleRegistry 扫描 assets 后写入预装包映射。 */
internal fun setPresetPacks(map: Map<Int, PackRef>) { PRESET_PACKS = map }

/** 按选择键构建完整 SpritePack（包级元数据来自对应 modes.jsonc）。未知键回退默认包 / 或按 RoleRegistry 动态解析。 */
fun buildSpritePack(ctx: android.content.Context, packId: Int): SpritePack {
    val ref = PRESET_PACKS[packId]
        ?: RoleRegistry.PRESETS.firstOrNull { it.packId == packId }?.let { PackRef(it.id, it.rootPath, it.source) }
        ?: PRESET_PACKS[ConfigDefaults.DEFAULT_SPRITE_PACK]
        ?: RoleRegistry.DEFAULT.let { PackRef(it.id, it.rootPath, it.source) }
    return loadPackMeta(ctx, ref.base, ref.source)
}

/** 读取某包 modes.jsonc 的「包级元数据」并覆盖到 Skeleton；解析失败回退 Skeleton（默认 0/false/默认图）。 */
fun loadPackMeta(ctx: android.content.Context, base: String, source: RoleSource): SpritePack {
    val skeleton = SpritePack(
        id = base, base = base, source = source,
        resolve = { "$base/$it" }, configPath = "$base/modes.jsonc"
    )
    val raw = try {
        when (source) {
            RoleSource.ASSETS -> ctx.assets.open(skeleton.configPath!!).bufferedReader().use { it.readText() }
            RoleSource.FILES -> File(ctx.filesDir, "roles/${skeleton.configPath}").bufferedReader().use { it.readText() }
        }
    } catch (_: Exception) { return skeleton }
    val root = try { parseJsonc(raw) } catch (_: Exception) { return skeleton }
    return skeleton.copy(
        id = if (root.has("id")) root.optString("id", base) else base,
        decodeTarget = if (root.has("decodeTarget")) root.optInt("decodeTarget", 0) else 0,
        anchorRes = if (root.has("anchorRes")) root.optInt("anchorRes", 128) else 128,
        name = if (root.has("name")) root.optString("name", "") else "",
        shortName = if (root.has("shortName")) root.optString("shortName", "") else "",
        functions = parseFunctions(root),
        modeLoops = parseModeLoops(root)
    )
}

/**
 * 解析 v1.01 契约的 functions：递归收集「功能路径 → 候选叶子列表」。
 *
 * 结构（层数不限，叶子恒为数组）：
 *   "static": {"sit": ["sit_clam"]}
 *   "touch":  {"roll": ["roll"]}
 *   "snap":   {"pat_head": {"pat_head": ["snap_pat_head"]}}
 *
 * 路径是完整链路，用以区分同名不同义：
 *   [snap, pat_head, pat_head] -> snap_pat_head（吸附态摸头）
 *   [pat_head, pat_head]       -> pat_head     （普通摸头）
 * 故调用方必须按【完整路径】寻址，只取末段会撞车。
 *
 * 叶子是候选列表：当前多为单元素，为将来"同一功能多个动作（如多种候选动画）"预留。
 */
fun parseFunctions(root: JSONObject): Map<List<String>, List<String>> {
    val obj = root.optJSONObject("functions") ?: return emptyMap()
    val out = LinkedHashMap<List<String>, List<String>>()
    fun visit(node: Any?, path: List<String>) {
        when (node) {
            is JSONArray -> {
                val leaves = (0 until node.length())
                    .map { node.optString(it) }
                    .filter { it.isNotEmpty() }
                if (leaves.isNotEmpty()) out[path] = leaves
            }
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    visit(node.opt(k), path + k)
                }
            }
        }
    }
    visit(obj, emptyList())
    return out
}

/**
 * 解析各模式是否循环（modes 里每项的 loop）。
 * 长期播放的动画由循环策略控制；一次性播放由动作生命周期控制。
 * 由素材包声明，代码里不写死"哪些动作该常驻"。
 */
private fun parseModeLoops(root: JSONObject): Map<String, Boolean> {
    val modes = root.optJSONObject("modes") ?: return emptyMap()
    val out = LinkedHashMap<String, Boolean>()
    val kit = modes.keys()
    while (kit.hasNext()) {
        val name = kit.next()
        val obj = modes.optJSONObject(name) ?: continue
        if (obj.has("loop")) out[name] = obj.optBoolean("loop", false)
    }
    return out
}

/** 读取顶层字符串数组字段（缺失/类型不符时返回空表）。 */
private fun parseStringArray(root: JSONObject, field: String): List<String> {
    val arr = root.optJSONArray(field) ?: return emptyList()
    return (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }
}


/**
 * 按角色描述符构建精灵包：FILES（用户上传）直接用 rootPath 走私有存储；
 * ASSETS（预装）走包 id 映射 [PRESET_PACKS]。
 */
fun buildSpritePackForRole(ctx: android.content.Context, role: RoleDescriptor): SpritePack =
    if (role.source == RoleSource.FILES) loadPackMeta(ctx, role.rootPath, RoleSource.FILES)
    else buildSpritePack(ctx, role.packId)

/**
 * 按包基路径 + 存储类型打开某个资源文件流（FILES 走 filesDir/roles/<base>/<res>）。
 * 供 PetService 投喂支鱼等需要直接读包内资源的场景复用，避免硬编码 assets.open。
 */
fun openPackResource(ctx: android.content.Context, base: String, source: RoleSource, res: String): java.io.InputStream? {
    val resolved = "$base/$res"
    return try {
        if (source == RoleSource.FILES) {
            val f = File(ctx.filesDir, "roles/$resolved")
            if (!f.exists()) null else f.inputStream()
        } else {
            ctx.assets.open(resolved)
        }
    } catch (_: Exception) { null }
}
