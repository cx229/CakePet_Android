package com.cx.cakepet

/**
 * 角色资源来源。
 * - [ASSETS]：打包进 APK 的预装角色（assets/<rootPath>/…）。
 * - [FILES]：用户导入/上传的角色（filesDir/roles/<id>/…，将来启用）。
 */
enum class RoleSource { ASSETS, FILES }

/**
 * 角色描述符（轻量元数据，不持有引擎实例）。
 * 预装角色写死；将来用户上传包 = 扫描 filesDir/roles/<id>/ 含合法 modes.json 者，
 * 转成同结构描述符并入 [RoleRegistry]，引擎（PetRole）完全无需改动。
 *
 * @param id        稳定唯一 id（也用作 DataStore 文件名后缀，如 pet_config_zhimasu / pet_bounds_zhimasu）。
 * @param displayName 首页卡片展示名。
 * @param rootPath  assets / 私有存储下该角色资源根目录（如 "芝麻酥" / "芝麻球促销"）。
 * @param packId    资源包逻辑标识（沿用旧 spritePack 映射：0=旧图 / 1=芝麻酥 / 2=芝麻球促销），
 *                  用于决定 decodeTarget 等包特性（FILES 上传包为 -1，按 rootPath 走私有存储）。
 * @param source    资源来源。
 * @param authors   作者列表（来自素材包 JSON 的 "authors" 数组）。当前仅为占位数据，
 *                   具体用途待定，故不参与校验、不影响素材包合法性。
 */
data class RoleDescriptor(
    val id: String,
    val displayName: String,
    val rootPath: String,
    val packId: Int,
    val shortName: String = "",   // 默认简称（来自素材包 JSON），实例可独立重命名覆盖
    val source: RoleSource = RoleSource.ASSETS,
    val authors: List<String> = emptyList()
)

/**
 * 解析素材包 JSON 的 "authors" 字段为字符串列表。
 * 该字段可选：缺失或非数组时返回空列表（不因它判定素材包非法，避免旧包被拒）。
 * 数组元素仅取字符串项，其余类型跳过。
 */
fun parseAuthors(root: org.json.JSONObject): List<String> {
    val arr = root.optJSONArray("authors") ?: return emptyList()
    val out = ArrayList<String>(arr.length())
    for (i in 0 until arr.length()) {
        val v = arr.opt(i) as? String ?: continue          // 仅取字符串项，其余类型跳过
        if (v.isNotBlank()) out += v
    }
    return out
}
