package com.cx.cakepet

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlin.math.roundToInt
import kotlin.random.Random
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 图片资源包：抽象“不同套美术资源”的加载策略。
 * - [resolve]：帧图片相对路径（如 "安静/sit_clam-1.png"）解析为本素材包中的实际路径；
 *   返回 null 表示素材缺失（播放时回退到当前 Mode 的 fallback）。
 * - [decodeTarget]：物理解码目标边长(px)；0 = 保持原始尺寸（默认包，原生 128）。
 *   新包原图 1000×1000，设为 256 ⇒ 解码期超采样到 256（最长边），作 2x 抗锯齿源；
 *   但【逻辑/锚点/画布】空间仍由 [anchorRes]=128 决定，渲染时把 256 位图按 ratio 缩进 128 逻辑矩形，
 *   既复用现有 128 逻辑（JSON 锚点/边界/scaleFactor 零改动），又比硬压 128 清晰 2x。
 * - [anchorRes]：逻辑分辨率（锚点/画布/UI 坐标系的“1”单位，恒为 128）。
 *   与 decodeTarget 解耦：decodeTarget=256、anchorRes=128 ⇒ 解码超采样 2x、逻辑仍 128。
 *   默认包 decodeTarget=0（原生=128=逻辑），此字段不生效。
 * - 锚点完全由逐帧 anchorX/anchorY 决定（JSON 未给 anchorY 时回退贴底 0），不假设“图底中心”。
 */
data class SpritePack(
    val id: String,
    val base: String = "",                           // 资源根目录（assets/<base> 或 filesDir/roles/<base>）
    val source: RoleSource = RoleSource.ASSETS,      // 存储类型（预装=ASSETS / 用户上传=FILES）
    val resolve: (String) -> String?,
    val configPath: String? = null,   // 本包 modes.json 路径（assets 或私有角色包目录）
    val decodeTarget: Int = 0,         // 物理超采样解码边长（0=原生）；新包 256（由 modes.json 覆盖）
    val anchorRes: Int = 128,          // 逻辑分辨率（锚点/画布/UI 坐标系单位），恒 128（由 modes.json 覆盖）
    val name: String = "",             // 资源包中文名（modes.json 指定，v1.0 必填），仅展示用
    val shortName: String = "",         // 资源包默认简称（modes.json 指定，v1.0 必填）：每个角色各自定义，不全局写死
    // v1.02：所有功能路径均映射到通用 Mode ID；Mode 可用于角色、食物及其他动画。
    // 键为【功能路径】（如 [climb, walk]、[snap, pat_head, pat_head]），层数不限；
    // 值为候选列表（当前多为单元素，为将来"同一功能多个动作"预留）。
    val functions: Map<List<String>, List<String>> = emptyMap(),
    // Mode 定义里的首帧图片，仅用于当前仍为静态位图的食物显示；不是独立资源字典。
    val modeFirstFrames: Map<String, String> = emptyMap(),
    // 悬浮菜单可触发的模式（v1.01 必填，由 modes.json 的 menuPool 提供）
    val menuPool: List<String> = emptyList(),
    // 模式名 → 显示名（modes 里每项的 label），供菜单等 UI 取中文名，代码不写死文案
    val modeLabels: Map<String, String> = emptyMap(),
    // 模式名 → 是否循环（modes 里每项的 loop）。循环类（走/蠕动等）须用 setMode 常驻，
    // 一次性类用 playOnce；由素材包声明，代码不写死哪些动作该常驻。
    val modeLoops: Map<String, Boolean> = emptyMap()
) {
    /** 当前食物仍是单张位图：通过 Function → Mode → frames[0].res 查找。 */
    val foodFramePath: String
        get() = functions[listOf("feed", "food")]?.firstNotNullOfOrNull { modeFirstFrames[it] } ?: ""
}


/**
 * 资源包目录：选择键(Int，持久化于 pet_config) -> (包 id, 资源根目录 base, 存储类型)。
 * 由 [RoleRegistry.refreshPresets] 启动时按其内唯一的 [PRESET_FOLDERS] 映射
 * （packId -> assets 目录）填入：遍历各目录读取 modes.json 得到 id/base，其余元数据都来自 JSON。
 * 包级元数据（名称/尺寸/锚点/预览/鱼路径等）全部来自各包 modes.json，不做预装特化。
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

/** 按选择键构建完整 SpritePack（包级元数据来自对应 modes.json）。未知键回退默认包 / 或按 RoleRegistry 动态解析。 */
fun buildSpritePack(ctx: android.content.Context, packId: Int): SpritePack {
    val ref = PRESET_PACKS[packId]
        ?: RoleRegistry.PRESETS.firstOrNull { it.packId == packId }?.let { PackRef(it.id, it.rootPath, it.source) }
        ?: PRESET_PACKS[ConfigDefaults.DEFAULT_SPRITE_PACK]
        ?: RoleRegistry.DEFAULT.let { PackRef(it.id, it.rootPath, it.source) }
    return loadPackMeta(ctx, ref.base, ref.source)
}

/** 读取某包 modes.json 的「包级元数据」并覆盖到 Skeleton；解析失败回退 Skeleton（默认 0/false/默认图）。 */
fun loadPackMeta(ctx: android.content.Context, base: String, source: RoleSource): SpritePack {
    val skeleton = SpritePack(
        id = base, base = base, source = source,
        resolve = { "$base/$it" }, configPath = "$base/modes.json"
    )
    val raw = try {
        when (source) {
            RoleSource.ASSETS -> ctx.assets.open(skeleton.configPath!!).bufferedReader().use { it.readText() }
            RoleSource.FILES -> File(ctx.filesDir, "roles/${skeleton.configPath}").bufferedReader().use { it.readText() }
        }
    } catch (_: Exception) { return skeleton }
    val root = try { JSONObject(raw) } catch (_: Exception) { return skeleton }
    return skeleton.copy(
        id = if (root.has("id")) root.optString("id", base) else base,
        decodeTarget = if (root.has("decodeTarget")) root.optInt("decodeTarget", 0) else 0,
        anchorRes = if (root.has("anchorRes")) root.optInt("anchorRes", 128) else 128,
        name = if (root.has("name")) root.optString("name", "") else "",
        shortName = if (root.has("shortName")) root.optString("shortName", "") else "",
        functions = parseFunctions(root),
        modeFirstFrames = parseModeFirstFrames(root),
        menuPool = parseStringArray(root, "menuPool"),
        modeLabels = parseModeLabels(root),
        modeLoops = parseModeLoops(root)
    )
}

/** 从模式定义读取首帧，供目前的单图食物显示使用，不创建另一套资源清单。 */
private fun parseModeFirstFrames(root: JSONObject): Map<String, String> {
    val modes = root.optJSONObject("modes") ?: return emptyMap()
    val out = mutableMapOf<String, String>()
    val keys = modes.keys()
    while (keys.hasNext()) {
        val id = keys.next()
        val frame = modes.optJSONObject(id)?.optJSONArray("frames")?.optJSONObject(0)
        val res = frame?.optString("res", "") ?: ""
        if (res.isNotEmpty()) out[id] = res
    }
    return out
}

/**
 * 解析各模式的显示名（modes 里每项的 label）。
 * 菜单等 UI 用它取中文名，代码里不写死任何动作文案。
 */
private fun parseModeLabels(root: JSONObject): Map<String, String> {
    val modes = root.optJSONObject("modes") ?: return emptyMap()
    val out = LinkedHashMap<String, String>()
    val kit = modes.keys()
    while (kit.hasNext()) {
        val name = kit.next()
        val label = modes.optJSONObject(name)?.optString("label", "") ?: ""
        if (label.isNotEmpty()) out[name] = label
    }
    return out
}

/**
 * 解析 v1.01 契约的 functions：递归收集「功能路径 → 候选叶子列表」。
 *
 * 结构（层数不限，叶子恒为数组）：
 *   "static": {"sit": ["sit_clam"]}
 *   "touch":  {"roll": ["roll"], "walk": ["walk"]}
 *   "snap":   {"pat_head": {"pat_head": ["snap_pat_head"]}}
 *
 * 路径是完整链路，用以区分同名不同义：
 *   [snap, pat_head, pat_head] -> snap_pat_head（吸附态摸头）
 *   [pat_head, pat_head]       -> pat_head     （普通摸头）
 * 故调用方必须按【完整路径】寻址，只取末段会撞车。
 *
 * 叶子是候选列表：当前多为单元素，为将来"同一功能多个动作（如多种 walk）"预留。
 */
fun parseFunctions(root: JSONObject): Map<List<String>, List<String>> {
    val obj = root.optJSONObject("functions") ?: return emptyMap()
    val out = LinkedHashMap<List<String>, List<String>>()
    fun walk(node: Any?, path: List<String>) {
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
                    walk(node.opt(k), path + k)
                }
            }
        }
    }
    walk(obj, emptyList())
    return out
}

/**
 * 解析各模式是否循环（modes 里每项的 loop）。
 * 循环类（walk / wriggle 等带位移的常驻动作）须用 setMode；一次性类用 playOnce。
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
 * 动画模式管理：每帧时延严格对齐 PC（image_modes.py）。
 * - 每个模式有帧序列 + 与帧一一对应的时延列表（可为固定值或随机区间）。
 * - 帧序列按 next 链循环（loop=true）或在最后一帧结束后切到指定下一模式（一次性动画）。
 * - 常驻模式支持 PC 的 time_next：随机间隔后按概率切换到指定/随机下一模式。
 */
class ImageModeManager(
    private val ctx: android.content.Context,
    // 默认资源包=白酥。
    initialPack: SpritePack = packForId(ctx, ConfigDefaults.DEFAULT_SPRITE_PACK)
) {

    /**
     * 本角色【独立】的随机源（每角色一个实例，天然隔离）。
     * 种子按包 id 派生：既保证多角色之间抽选序列互不干扰（角色 A 抽一次不会推进角色 B 的序列），
     * 又便于按 id 复现问题。绝不使用 Random.Default / 全局种子。
     */
    private val rng = Random(initialPack.id.hashCode())

    companion object {
        /**
         * v1.01 起【不再定义任何动作名常量】。
         *
         * 背景：旧版这里写死了 sit_clam / walk / lie / pull_fish 等一整套动作名，
         * 而各角色的动作差异很大（如蜂蜜猫没有 walk_white，却有专属的 load），
         * 硬编码会让新角色要么动作缺失、要么切到不存在的模式而卡住。
         *
         * 现状：动作一律经【功能路径】寻址（modeOf("climb","walk")），
         * 具体名字由素材包 functions 提供；待机/静止/菜单池亦然。
         * 本 companion 只保留与动作名无关的通用能力。
         */

        /**
         * 按资源包 id 取得对应资源包；未知键回退默认白酥。
         * 资源包位置/存储类型在 [PRESET_PACKS] 唯一映射，包级元数据（尺寸/锚点/预览/鱼路径等）全部来自各包 modes.json，
         * 实现资源与程序解耦——新增/上传素材包无需改动此文件。
         */
        fun packForId(ctx: android.content.Context, packId: Int): SpritePack = buildSpritePack(ctx, packId)
    }

    /** 单帧定义：资源名、下一帧索引（null=序列结束）、时延(ms)、锚点(图片像素坐标，对齐 PC image_meta) */
    private data class Frame(
        val resName: String,
        val nextIndex: Int?,            // 下一帧在 frames 中的下标，null 表示序列结束
        val delay: () -> Long,          // 该帧停留时长(ms)
        val anchorX: Int = -1,          // 图片像素锚点 X（<0 表示用图宽/2 居中）
        // 图片像素锚点 Y（距【底】边，左下角原点，Y 向上）。
        // 合法范围 [-128, 128]：0=贴底，128=贴顶，负数=脚在图底下方（图外，如 walk/wriggle 的脚实际在地面以下）。
        // null 表示未设置：回退贴底 0（脚贴地）。锚点完全由逐帧 anchorX/anchorY 决定，不假设“图底中心”。
        val anchorY: Int? = null,
        // 帧位移偏移(px)，对齐 PC 的 QPoint(x,0) offset：每帧把该偏移累加到锚点坐标，
        // 实现“拉鱼/扒鱼”这类【帧驱动】位移（区别于物理速度驱动的 walk/roll）。
        // 单循环：一次性动画播完即停，不反弹（对齐 PC PullFishMode 第6~9帧的 offset）。
        val dx: Int = 0,
        val dy: Int = 0
    )

    /** 帧推进结果：changed=是否换帧。动画只管切图，绝不控制位置（位移由物理循环负责）。 */
    data class FrameStep(
        val changed: Boolean
    )

    /** 一个动画模式 */
    private data class ImageModeDef(
        val name: String,
        val fallback: String,           // 资源缺失时的兜底图
        val frames: List<Frame>,
        val loop: Boolean,              // true: 到末尾后回到首帧（常驻循环）；false: 播完触发 onSequenceEnd
        val timeNextEnabled: Boolean = false,   // 是否启用随机定时切换下一模式（PC time_next_enabled）
        val changeIntervalMin: Long = 3000,
        val changeIntervalMax: Long = 4000,
        val changeProb: Float = 0.5f,
        val nextModeName: String? = null,       // 指定下一模式（PC next_mode_name）
        val onSequenceEnd: ((() -> Unit) -> Unit)? = null,  // 序列结束回调（用于 SIT 间随机切换等）
        val walkSpeed: Float = 0f,              // 行走位移速度(px/s)，>0 时由物理引擎驱动水平位移（对齐 PC 帧偏移 QPoint）
        val label: String = ""                  // 中文名：仅供阅读 modes.json 用，不参与任何代码逻辑
    )

    private fun rnd(min: Long, max: Long): () -> Long = { Random.nextLong(min, max + 1) }
    private fun fixed(v: Long): () -> Long = { v }

    private val modes = mutableMapOf<String, ImageModeDef>()
    /** 资源文件的垂直填充方式，取自其所属 Mode，而非文件命名。 */
    private val verticalAlignOfResource = mutableMapOf<String, String>()
    private lateinit var currentName: String
    private var currentDef: ImageModeDef? = null
    private var frameIndex = 0
    private var acc = 0L          // 当前帧已累积时间(ms)
    private var forcedSeq: ImageModeDef? = null   // 一次性动画
    private var forcedOnEnd: (() -> Unit)? = null
    private var timeNextAcc = 0L
    private var timeNextTarget = 0L
    /** 当前生效的资源包；默认=白酥包，可由 setPack 运行时按角色资源包切换。 */
    var currentPack: SpritePack = initialPack

    /** 当前行走方向与位移速度（>0 表示正在行走位移，由物理引擎驱动水平移动） */
    var walkDir = 1
    var walkSpeed = 0f
        private set

    /**
     * 行走状态变化回调：进入 walk 类模式时通知外部设置物理水平速度 + 镜像朝向；
     * 离开（切到非 walk 模式）时以 (0, 1) 通知清零速度并取消镜像。
     * PetView 注入此回调，将 (speed, dir) 映射到 physics.vx 与 flipX。
     */
    var onWalkStateChanged: ((speed: Float, dir: Int) -> Unit)? = null

    /**
     * 待机自动轮换池：物理空闲时，按时间随机切换到池中（排除当前）的某个模式。
     * 内容由【素材包】的 functions.standby.pool 提供（v1.02 必备），代码不写死任何模式名；
     * 载入配置后再按该包实际定义的动作裁剪，避免引用包里不存在的模式（旧版曾因此切到
     * 空模式而卡住）。包未提供时为空表 → 不做待机轮换（优于切到不存在的动作）。
     */
    private var standbyPool: List<String> = emptyList()

    /**
     * 静止池：待机池去除位移/物理驱动类模式（蠕动、walk、扒鱼）。
     * 当“重力·抛掷”关闭（gravityEnabled=false）时使用，宠物保持在原地的小动作/静坐。
     * 同样由素材包的 functions.static.pool 提供，不再硬编码。
     */
    private var staticPool: List<String> = emptyList()

    /**
     * true 时使用静止池 staticPool（去除蠕动/walk/扒鱼）；false 使用完整待机池。
     * 由 PetService 根据 gravityEnabled 同步。
     */
    var useStaticPool: Boolean = false

    /**
     * 好动飞跃开关：由 PetService 同步为 leapEnabled。
     * 仅当开启且当前处于攀岩态（inClimbing）时，每次从池子捞新动作按 leapProbability 概率直接跳起。
     */
    var leapEnabled: Boolean = false

    /** 自动觅食开关：由 PetView 每帧同步（仅当攀岩态且已锁定食物时为真）。
     *  优先级高于飞跃：觅食中从动作池捞动作时直接返回 WALK 继续沿墙走，不跳起。 */
    var forageActive: Boolean = false

    /** 飞跃概率（0-1 小数）：由 PetService 同步为 leapProbability。 */
    var leapProbability: Float = 0.10f

    /** 当前是否处于攀岩态：由 PetView 每帧同步。决定捞新动作时是否掷骰跳起。 */
    var inClimbing: Boolean = false

    /** 当前是否处于飞跃飞行态：由 PetView 每帧同步。飞行中锁定为 JUMP_UP，禁止 nextFrame 切换动作。 */
    var isFlying: Boolean = false

    // 根据开关选择当前待机池：静止池 > 完整待机池
    private fun currentPool(): List<String> =
        if (useStaticPool) staticPool else standbyPool

    /**
     * 按【功能路径】取该功能的候选叶子（v1.01 契约）。
     * 返回空表表示素材包未提供该功能——调用方应据此【跳过】，不要发空 setMode。
     */
    fun leavesOf(vararg path: String): List<String> = currentPack.functions[path.toList()] ?: emptyList()

    /**
     * 按功能路径取【模式名】（从候选里挑一个）。
     * 候选当前多为单元素（行为不变）；将来同一功能有多个动作时，由本角色的独立随机源挑选，
     * 各角色序列互不干扰。素材包未提供该功能时返回 null。
     */
    fun modeOf(vararg path: String): String? = leavesOf(*path).let { if (it.isEmpty()) null else it.random(rng) }

    /** 功能候选集判断，不调用随机选择器。 */
    fun isFunctionMode(name: String, vararg path: String): Boolean = name in leavesOf(*path)

    fun isDragMode(name: String = getMode()): Boolean =
        listOf("slow", "left_medium", "left_fast", "right_medium", "right_fast")
            .any { isFunctionMode(name, "touch", "lift_up", it) }

    /**
     * 从池子捞一个新动作（排除当前动作）；若开启好动飞跃且处于攀岩态，按 leapProbability 概率直接跳起。
     * 供：一次性动画收尾、主循环收尾、定时随机切换 三处统一调用。
     */
    fun pickStandbyMode(exclude: String): String {
        // 自动觅食优先于飞跃：觅食中锁定食物则继续沿墙行走，不跳起（不拦截其他逻辑，仅插队）。
        // 动作名由素材包 functions 提供：包未提供攀岩行走功能则不作处理，走常规池子。
        if (forageActive) {
            modeOf("climb", "walk")?.let { return it }
        }
        // 先按飞跃概率判断是否直接跳起；不飞跃再正常从池子捞其他动作
        if (leapEnabled && inClimbing && Random.nextFloat() < leapProbability) {
            modeOf("climb", "jump_up")?.let { return it }
        }
        val pool = currentPool().filter { it != exclude }
        return if (pool.isNotEmpty()) pool.random() else currentName
    }

    init {
        var loaded = false
        if (currentPack.configPath != null) {
            try {
                loadModesFromJson(currentPack.configPath!!)
                loaded = true
            } catch (e: Exception) {
                // v1.01：modes.json 是必备字段（缺失/损坏的包在导入时即被拒），
                // 故【不再】回退到内建硬编码动作——那会让新角色拿到默认角色的动作而串图。
                // 载入失败时 modes 为空，宠物无动作可播，属素材包本身问题，应修包而非静默兜底。
                Log.e("ImageModeManager", "载入 ${currentPack.configPath} 失败：该包 modes.json 缺失或非法", e)
            }
        }

        // 起始动作由素材包决定（优先 [static, sit]），不再写死 sit_clam。
        // 若该包未定义，退而取包内第一个动作；仍无则留空（无动作可播），由调用方按空处理。
        currentName = if (loaded) {
            (currentPack.functions[listOf("static", "sit")]?.firstOrNull()
                ?: modes.keys.firstOrNull()) ?: ""
        } else ""
        currentDef = modes[currentName]
        frameIndex = 0
        acc = 0L
        resetTimeNext()
    }

    private fun resetTimeNext() {
        // 用即将播放的模式（forcedSeq 优先）的间隔，避免进入一次性动画时
        // 仍沿用上一常驻模式的超大间隔（如 sit 的 3000~25000），导致长时间不跳出。
        val base = forcedSeq ?: currentDef
        timeNextAcc = 0L
        timeNextTarget = Random.nextLong(
            base?.changeIntervalMin ?: 3000,
            (base?.changeIntervalMax ?: 4000) + 1
        )
    }

    /**
     * 从 assets 下的 JSON 配置加载动画模式（帧序列/延迟/锚点/待机池等）。
     * 解析失败由调用方捕获并回退到内建模式，本函数不抛未处理异常。
     */
    private fun loadModesFromJson(path: String) {
        val text = openPackStream(path)?.bufferedReader()?.use { it.readText() } ?: return
        val root = JSONObject(text)
        // 包级元数据：全部来自 modes.json 根节点（覆盖 currentPack/Skeleton 默认值，实现资产与程序解耦）。
        val decodeTarget = if (root.has("decodeTarget")) root.optInt("decodeTarget", currentPack.decodeTarget) else currentPack.decodeTarget
        val anchorRes = if (root.has("anchorRes")) root.optInt("anchorRes", currentPack.anchorRes) else currentPack.anchorRes
        val id = if (root.has("id")) root.optString("id", currentPack.id) else currentPack.id
        val name = if (root.has("name")) root.optString("name", currentPack.name) else currentPack.name
        currentPack = currentPack.copy(
            id = id, decodeTarget = decodeTarget, anchorRes = anchorRes, name = name
        )
        // 清空旧模式，按 JSON 重建
        modes.clear()
        verticalAlignOfResource.clear()
        val modesObj = root.getJSONObject("modes")
        val kit = modesObj.keys()
        while (kit.hasNext()) {
            val name = kit.next()
            val m = modesObj.getJSONObject(name)
            val fallback = if (m.has("fallback")) m.getString("fallback") else ""
            val loop = m.optBoolean("loop", false)
            val tn = if (m.has("timeNext")) m.getJSONObject("timeNext") else null
            val timeNextEnabled = tn?.optBoolean("enabled", false) ?: false
            val changeIntervalMin = tn?.optLong("min", 3000) ?: 3000
            val changeIntervalMax = tn?.optLong("max", 4000) ?: 4000
            val changeProb = (tn?.optDouble("prob", 0.5) ?: 0.5).toFloat()
            val nextModeName = if (m.has("nextMode") && !m.isNull("nextMode")) m.getString("nextMode") else null
            val walkSpeed = m.optDouble("walkSpeed", 0.0).toFloat()
            val label = if (m.has("label")) m.getString("label") else name
            val verticalAlign = m.optString("verticalAlign", "top")
            val framesArr = m.getJSONArray("frames")
            val frames = mutableListOf<Frame>()
            for (i in 0 until framesArr.length()) {
                val f = framesArr.getJSONObject(i)
                val res = f.getString("res")
                verticalAlignOfResource[res] = verticalAlign
                val nextIndex = if (f.has("next") && !f.isNull("next")) f.getInt("next") else null
                val delay = parseDelay(f.get("delay"))
                val anchorX = if (f.has("anchorX")) f.getInt("anchorX") else -1
                val anchorY = if (f.has("anchorY")) f.getInt("anchorY") else null
                val dx = if (f.has("dx")) f.getInt("dx") else 0
                val dy = if (f.has("dy")) f.getInt("dy") else 0
                frames.add(Frame(res, nextIndex, delay, anchorX, anchorY, dx, dy))
            }
            registerMode(ImageModeDef(name, fallback, frames, loop, timeNextEnabled,
                changeIntervalMin, changeIntervalMax, changeProb, nextModeName, null, walkSpeed, label))
        }
        // 待机池 / 静止池：内容取自素材包，并【按本包实际定义的动作裁剪】。
        // 池里若出现包中未定义的动作（素材包版本升级时的常见疏漏），会在轮换时切到不存在的
        // 模式而卡住/静止，故载入即剔除——与 RolePackageContract 的"引用集 ⊆ modes"一致。
        val defined = buildSet {
            val kit = modesObj.keys()
            while (kit.hasNext()) add(kit.next())
        }
        // v1.02: 长期轮播范围在 Functions 声明，调度仍沿用 timeNext / nextMode。
        standbyPool = leavesOf("standby", "pool").filter { it in defined }
        staticPool = leavesOf("static", "pool").filter { it in defined }
    }

    /** delay 字段：[min,max]=随机区间；数字=固定值(ms)。 */
    private fun parseDelay(d: Any?): () -> Long = when (d) {
        is JSONArray -> {
            val min = d.getLong(0)
            val max = d.getLong(1)
            rnd(min, max)
        }
        is Int -> fixed(d.toLong())
        is Long -> fixed(d)
        is Double -> fixed(d.toLong())
        else -> fixed(100)
    }

    private fun registerMode(mode: ImageModeDef) {
        modes[mode.name] = mode
    }

    /** 切换常驻循环模式（如 sit_clam / sit_puffed / walk 循环） */
    fun setMode(name: String) {
        if (forcedSeq != null) return   // 一次性动画播放中，忽略常驻切换
        val def = modes[name] ?: return
        if (currentName != name) modeChangedFlag = true
        currentName = name
        currentDef = def
        frameIndex = 0
        acc = 0L
        resetTimeNext()
        // 行走位移：进入 walkSpeed>0 的模式时随机方向，由物理引擎驱动水平移动；
        // 离开时清零速度并取消镜像（对齐 PC transform_flag 随机 + 边界回弹）。
        if (def.walkSpeed > 0f) {
            walkDir = if (Random.nextBoolean()) 1 else -1
            walkSpeed = def.walkSpeed
        } else {
            walkDir = 1
            walkSpeed = 0f
        }
        onWalkStateChanged?.invoke(walkSpeed, walkDir)
    }

    /** 当前是否处于行走位移模式（供 PetView 每帧同步朝向左镜像/位置） */
    fun isWalk(): Boolean = walkSpeed > 0f && forcedSeq == null

    fun getMode(): String = forcedSeq?.name ?: currentName
    fun isPlayingForced(): Boolean = forcedSeq != null

    /** 当前模式是否属于待机动作清单（standbyPool / staticPool）。供投喂等判断是否可切"坐姿投食"。 */
    fun isStandbyMode(name: String = currentName): Boolean = currentPool().contains(name)

    /** 当前帧的水平位移偏移(px)，对齐 PC 帧 offset.x；扒鱼等一次性位移模式据此累加位移 */
    fun curDx(): Int =
        (forcedSeq ?: currentDef)?.frames?.getOrNull(frameIndex)?.dx ?: 0

    /** 当前帧的垂直位移偏移(px)，对齐 PC 帧 offset.y */
    fun curDy(): Int =
        (forcedSeq ?: currentDef)?.frames?.getOrNull(frameIndex)?.dy ?: 0

    /**
     * 取消正在播放的一次性动作（forcedSeq）。用于用户开始拖拽/抛掷时抢占控制：
     * 否则在动作播放期间 setMode 被忽略（见 setMode 顶部），拖动/翻滚的 LIFT_UP/ROLL 模式
     * 无法生效，导致“图像是动作、mode 却仍是 sit_clam”的状态错乱（拖动时 mode 异常）。
     */
    fun cancelForced() {
        if (forcedSeq != null) {
            forcedSeq = null
            forcedOnEnd = null
            // 回到常驻静止动作（由素材包 functions 的 [static, sit] 提供），确保后续 setMode 能正常切换
            if (currentName == forcedSeq?.name) modeOf("static", "sit")?.let { setMode(it) }
        }
    }

    /**
     * 强制复位（召回）：无论当前在播放一次性动画还是常驻模式，立即清除动作并切回静坐（sit_clam），
     * 与 PetPhysics.resetTo 配合实现“召回归位、静止、显示 sit-calm”。
     */
    fun forceReset() {
        forcedSeq = null
        forcedOnEnd = null
        // 静止动作由素材包 functions 的 [static, sit] 提供，代码不写死 sit_clam
        modeOf("static", "sit")?.let { setMode(it) }
    }

    /**
     * 运行时切换资源包（按角色资源包触发）。
     * 清空位图/尺寸缓存，重建统一画布尺寸，并复位到默认静坐模式。
     * 注意：切换会打断当前一次性动画并回到 sit_clam（配置切换属低频操作，可接受短暂停顿）。
     */
    fun setPack(pack: SpritePack) {
        if (currentPack.id == pack.id) return
        currentPack = pack
        bitmapCache.clear()
        rawSizeCache.clear()
        if (pack.configPath != null) {
            try {
                loadModesFromJson(pack.configPath!!)
            } catch (e: Exception) {
                // 换包配置解析失败：保留当前已加载的 modes，避免空模式导致宠物卡死。
                // v1.01 下 modes.json 是必备字段，此属素材包本身问题，打日志便于定位。
                Log.e("ImageModeManager", "换包载入 ${pack.configPath} 失败", e)
            }
        }
        val (w, h) = globalMaxSize()
        globalW = w
        globalH = h
        forcedSeq = null
        forcedOnEnd = null
        // 起点动作由素材包决定（优先 [static, sit]），不再写死 sit_clam
        currentName = (currentPack.functions[listOf("static", "sit")]?.firstOrNull()
            ?: modes.keys.firstOrNull()) ?: ""
        currentDef = modes[currentName]
        frameIndex = 0
        acc = 0L
        resetTimeNext()
        modeChangedFlag = true
    }

    // 供 View 检测“是否切到了新动画序列”：每次 nextFrame 后由 didModeChange() 取一次并清除标记
    private var modeChangedFlag = false
    private var reportedName: String? = null

    /** 本次 nextFrame 是否切换了动画模式/序列（用于 View 仅在切模式时更新固定尺寸） */
    fun didModeChange(): Boolean {
        val c = modeChangedFlag
        modeChangedFlag = false
        reportedName = currentName
        return c
    }

    /**
     * 播放一次性动画。结束后的目标模式：
     * - 模式自身定义了 nextModeName（如摸头 -> 摇头）：跳到该指定模式；
     * - 模式无 nextModeName（如美白、趴，对齐 PC 的"下一个无指定"）：结束回到待机随机池，
     *   而非固定 sit_clam（避免菜单多次激活美白后总是落回静坐）。
     * 这样「摸头 → 摇头 → 炸毛坐」等链式跳转由模式定义驱动，与 PC 一致。
     */
    fun playOnce(name: String, endMode: String? = null) {
        val def = modes[name] ?: return
        // 结束后目标：优先模式自身定义的 nextModeName；其次调用方指定；
        // 都没有则回待机随机池（由 pickStandbyMode 决定），不再固定写死 sit_clam。
        val next = def.nextModeName ?: endMode
        forcedSeq = def
        forcedOnEnd = {
            forcedSeq = null
            forcedOnEnd = null
            if (next != null) {
                setMode(next)
            } else {
                // 无指定下一模式：对齐 PC change_mode，结束回待机池（攀岩态可能按概率跳起）
                setMode(pickStandbyMode(currentName))
            }
        }
        frameIndex = 0
        acc = 0L
        modeChangedFlag = true   // 关键：与 setMode 一致，确保 tick() 里 didModeChange() 返回 true，
                                 // 当帧立即 syncCurrentBitmap() 刷新位图；否则位图要等节流点 +
                                 // 首帧 delay（如探头 1~5s / lie 3~7s）才更新，
                                 // 表现为“还显示旧图 sit_clam，延迟或失败切换”。
        resetTimeNext()   // 一次性动画也支持内部循环 + 概率跳出，需重置定时
    }

    fun currentBitmap(): Bitmap? {
        val def = forcedSeq ?: currentDef ?: return null
        val frame = def.frames.getOrNull(frameIndex) ?: return null
        return loadBitmap(frame.resName) ?: loadBitmap(def.fallback)
    }

    /** 预览自治播放用：返回当前帧的 resName（按帧序列顺序），供缓存键与重绘判定。 */
    fun currentResName(): String? {
        val def = forcedSeq ?: currentDef ?: return null
        return def.frames.getOrNull(frameIndex)?.resName
    }

    /**
     * 预览用：返回某帧经本包“复杂变换”后的位图——即 [loadBitmap] 产物：
     * 按包 decodeTarget 物理超采样 + 统一画布 padding（roll 居中、其余顶部对齐底部补透明）。
     * 首页预览借此让不同资源图（1000px 高清 / 128px 原生）在预览框内尺寸一致、锚点正确。
     */
    fun previewFrame(resName: String): Bitmap? = loadBitmap(resName)

    /** 当前帧【逻辑画布】尺寸（恒 128 基准；新包解码 256 超采样但画布逻辑仍为 128）。
     *  窗口/锚点 X 居中/渲染矩形均以画布计，而非逐帧 bitmap 像素（256 超采样源）。 */
    fun currentBitmapSize(): Pair<Int, Int> {
        return if (globalW > 0 && globalH > 0) globalW to globalH else 160 to 160
    }

    /** 原始位图尺寸记忆化：同一 resName 只解一次，消除 currentAnchor() 每次调用都 open+decode 的 I/O 抖动。 */
    private val rawSizeCache = mutableMapOf<String, Pair<Int, Int>>()

    /**
     * 仅解码取原始位图尺寸（不进入 bitmapCache、不引用 globalW/globalH），
     * 专供 init 阶段计算统一画布尺寸，避免与 globalW/globalH 的 init 形成初始化死循环。
     * 结果按 resName 记忆化：同一帧尺寸只解一次，既消除重复 I/O，也保证“位置求锚点”与“显示求锚点”
     * 取到的是同一份已缓存尺寸、不会因重读原始文件而错位。
     */
    // 取原图尺寸（用于锚点/ padding 计算）。缓存避免重复解码头。
    // 新包：返回「保比例下采样」后的真实内容尺寸（最长边 = decodeTarget，如 1000×500 → 128×64），
    // 而非原始的 1000×1000 也非方块 128×128（否则非正方形图锚点会算到错误处/脚落到透明区导致错位）。
    private fun rawBitmapSize(resName: String): Pair<Int, Int> {
        rawSizeCache[resName]?.let { return it }
        val path = currentPack.resolve(resName)
        if (path == null) {
            rawSizeCache[resName] = 0 to 0
            return 0 to 0
        }
        val r = if (currentPack.decodeTarget > 0) {
            try {
                val istr = openPackStream(path)
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(istr, null, opts)
                istr?.close()
                // 逻辑尺寸：按【anchorRes=128】保比例算，而非物理 decodeTarget(256)。
                // 锚点/画布/边界全部活在 128 逻辑空间，decodeTarget 只影响解码清晰度、不影响坐标。
                scaledContentSize(opts.outWidth, opts.outHeight, currentPack.anchorRes)
            } catch (_: Exception) {
                currentPack.anchorRes to currentPack.anchorRes   // 读不到边界：回退逻辑方块尺寸，避免下游异常
            }
        } else {
            try {
                val istr = openPackStream(path)
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(istr, null, opts)
                opts.outWidth to opts.outHeight
            } catch (_: Exception) {
                0 to 0
            }
        }
        rawSizeCache[resName] = r
        return r
    }

    /**
     * 所有模式所有帧的全局最大【原始】尺寸（真实像素，未 padding）。用于固定统一画布尺寸，
     * 保证任何动画帧（Roll/Walk/探头/落地等）都能完整显示且窗口尺寸恒定 → 不重排。
     * 用 rawBitmapSize（仅取边界）而非 loadBitmap，避免初始化期引用尚未赋值的 globalW/globalH。
     */
    fun globalMaxSize(): Pair<Int, Int> {
        var mw = 0
        var mh = 0
        for (def in modes.values) {
            for (f in def.frames) {
                val (bw, bh) = rawBitmapSize(f.resName)
                if (bw > mw) mw = bw
                if (bh > mh) mh = bh
            }
        }
        return if (mw > 0 && mh > 0) mw to mh else currentBitmapSize()
    }

    /**
     * 当前帧锚点（【转换器】内部使用，对外输入见各 frame.anchorX/Y 注释）。
     *
     * 对外输入约定（方便美术/新增动作直接按图确认地面点）：
     *   - anchorX：距图片【左】边的像素（<0 表示水平居中 = 图宽/2）
     *   - anchorY：距图片【底】边的像素（左下角原点，Y 向上）。null = 未设置（贴底 = 0）；
     *     可为负值（脚在图底下方，如 walk/wriggle），转换器照常计算 rawH - anchorY，不截断。
     * 背景：原 PC 锚点是“距顶 + 贴底 padding 偏移”的复合值，难直观。现改为按 PNG 左下角
     * 直接填地面点，转换器在内部还原为旧版等价坐标，保证渲染/边界效果【与改动前完全一致】。
     *
     * 还原推导（padY 由 fillOffset 给出：非 roll = 0 顶部填充、roll = (globalH-H_orig)/2 居中）：
     *   新版输出 = (anchorX距左 + padX) , (anchorY距顶 + padY)
     *   新版输入 = 距底 = H_orig - 距顶  ⇒  距顶 = H_orig - anchorY
     *   ⇒ 新版输出 = (anchorX + padX) , ((H_orig - anchorY) + padY)
     *             = (anchorX + padX) , (anchorY + (H_orig + padY - anchorY))  // 与旧距顶版等价
     *   即：ay = (H_orig - anchorY) + padY，X 不变（距左语义一致）；
     *   非 roll 时 padY=0 ⇒ ay = H_orig - anchorY（脚距画布顶 = 图内脚距顶），画布底到脚为透明。
     *   ROLL 不再特判，使用与所有动作相同的真实 anchorY；其旋转一致性由 PetView 在
     *   rotateOffsetWith / onDraw 中对 ROLL 改绕锚点自身旋转来保证（球状对称，外观不变）。
     */
    fun currentAnchor(): Pair<Int, Int> {
        val def = forcedSeq ?: currentDef ?: return 80 to 160
        val frame = def.frames.getOrNull(frameIndex) ?: return 80 to 160
        val (w, _) = currentBitmapSize()   // padding 后尺寸 = globalW×globalH（仅用 w 供锚点 X 居中）
        val (_, rawH) = rawBitmapSize(frame.resName)   // 原始 PNG 尺寸（未 padding），仅用高
        // 锚点完全由逐帧 anchorX/anchorY 决定；JSON 未给 anchorY 时回退贴底 0，不再用「图片底部中心」强统一。
        val axIn = frame.anchorX
        val ayIn = frame.anchorY ?: 0
        val axRaw = if (axIn >= 0) axIn else w / 2   // 距左（<0 居中）
        // 输入 anchorY=距底 ⇒ 转距顶 = rawH - anchorY。合法范围 [-128,128]，负值(脚在图底下方)照常参与计算。
        val ayFromBottom = ayIn
        val ayRaw = rawH - ayFromBottom
        val (padX, padY) = padOf(frame.resName)
        return (axRaw + padX) to (ayRaw + padY)
    }

    /**
     * 时间驱动推进。dtMs：距上次调用的毫秒数。
     * 返回 FrameStep：changed 是否换帧。
     *
     * 动画【只管切图】，绝不控制位置——位移完全由物理循环（重力/抛掷/拖拽）负责，
     * 与动画帧解耦（对齐 PC：img_move_by_offset 由物理速度驱动，set_image 仅算左上角）。
     *
     * 采用 while 循环：当一帧累积时间超过当前帧 delay 时推进，并继续检查下一帧，
     * 直到剩余时间不足一帧。这样即便因卡顿/高帧率导致 dt 较大，也不会漏帧，
     * 每帧停留时长严格等于其各自的 delay（对齐 PC）。
     */
    fun nextFrame(dtMs: Long): FrameStep {
        val def = forcedSeq ?: currentDef ?: return FrameStep(false)
        acc += dtMs
        var changed = false
        var guard = 0
        while (guard++ < 256) {
            val frame = def.frames.getOrNull(frameIndex) ?: return FrameStep(changed)
            val delay = frame.delay()
            if (acc < delay) break
            acc -= delay
            changed = true

            val next = frame.nextIndex
            if (next == null) {
                // 序列结束
                if (forcedSeq != null) {
                    // 一次性动画结束
                    forcedOnEnd?.invoke()
                    return FrameStep(true)
                }
                if (def.loop) {
                    frameIndex = 0
                } else if (def.nextModeName != null) {
                    // 有明确下一模式（如摇头 -> 炸毛）：跳指定模式
                    if (!isFlying) setMode(def.nextModeName)
                    return FrameStep(true)
                } else {
                    // 无指定下一模式（美白、趴，对齐 PC "下一个无指定"）：
                    // 与 playOnce 的 forcedOnEnd 一致，结束回待机池（攀岩态可能按概率跳起）。
                    if (!isFlying) setMode(pickStandbyMode(currentName))
                    return FrameStep(true)
                }
            } else {
                frameIndex = next
            }
        }

        // 随机定时切换 / 概率跳出（timeNextEnabled），对齐 PC：
        // 命中概率时优先使用模式自身定义的 nextModeName（如摸头->摇头），
        // 保证即使 forcedSeq 因拖拽被 cancel，也能正确跳到指定下一模式而非回 sit；
        // 若模式无 nextModeName（纯常驻 sit 类），则在待机池 standbyPool 中随机切换。
        if (def.timeNextEnabled && !isFlying) {
            timeNextAcc += dtMs
            if (timeNextAcc >= timeNextTarget) {
                timeNextAcc = 0L
                timeNextTarget = Random.nextLong(def.changeIntervalMin, def.changeIntervalMax + 1)
                if (Random.nextFloat() < def.changeProb) {
                    val next = def.nextModeName
                    if (next != null) {
                        // 有指定下一模式：一次性动画走 forcedOnEnd（已含置空+setMode），
                        // 常驻则直接 setMode（对齐 PC change_mode 用 next_mode_name）。
                        if (forcedSeq != null) {
                            forcedOnEnd?.invoke()
                        } else {
                            setMode(next)
                        }
                        return FrameStep(true)
                    } else {
                        // 无指定：待机池随机切换（攀岩态可能按概率跳起）
                        setMode(pickStandbyMode(currentName))
                        return FrameStep(true)
                    }
                }
            }
        }
        return FrameStep(changed)
    }

    private val bitmapCache = mutableMapOf<String, Bitmap?>()

    /**
     * 统一画布尺寸（全局最大帧）。所有帧加载后都被居中 padding 到此尺寸，
     * 使每张帧的位图宽高恒等于 [globalW,globalH]：
     *  - getAnchorScaled 的居中偏移 (w-dw)/2 恒为 0，窗口尺寸恒定 → 切模式不 resize、不卡顿；
     *  - 锚点 (ax,ay) 经 pad 偏移后仍指向「宠物脚在原图内的相对位置」，跨模式连续 → 不错位。
     * 这解决了“各 mode 画布被裁切到不同尺寸、锚点像素值未同步”导致的全链路错位。
     */
    var globalW: Int = 0
    var globalH: Int = 0
    init {
        val (w, h) = globalMaxSize()
        globalW = w
        globalH = h
    }

    /** 图片的垂直填充由 Mode.verticalAlign 显式指定，默认 top。 */
    private fun fillOffset(resName: String, bw: Int, bh: Int, canvasW: Int = globalW, canvasH: Int = globalH): Pair<Int, Int> {
        if (bw <= 0 || bh <= 0) return 0 to 0
        val padX = ((canvasW - bw).coerceAtLeast(0)) / 2
        val padY = if (verticalAlignOfResource[resName] == "center") (canvasH - bh) / 2 else 0
        return padX to padY
    }

    /** 每帧相对统一画布的偏移（px），供锚点补偿使用。直接复用 fillOffset（单一规则来源）。 */
    private fun padOf(resName: String): Pair<Int, Int> {
        val (bw, bh) = rawBitmapSize(resName)
        return fillOffset(resName, bw, bh)
    }

    /** 按当前资源包打开任意资源流；ASSETS 走 assets.open，FILES 走 filesDir/roles/<base>/...。 */
    private fun openPackStream(resolvedPath: String): java.io.InputStream? = try {
        when (currentPack.source) {
            RoleSource.ASSETS -> ctx.assets.open(resolvedPath)
            RoleSource.FILES -> File(ctx.filesDir, "roles/$resolvedPath").inputStream()
        }
    } catch (_: Exception) { null }

    // 解析并缓存单帧位图。解码产物 pad 进「解码画布」(decodeTarget 边长；旧包=逻辑画布 globalW×globalH)，
    // 居中绘制（水平居中，垂直置顶、底部补透明）。
    // 新包：按 decodeTarget=256 保比例下采样（最长边 → 256，如 1000×500 → 256×128）作 2x 超采样源，
    // pad 进 256×256 解码画布；逻辑/锚点空间仍是 anchorRes=128，渲染时再把 256 位图缩进 128 逻辑矩形（见 PetView）。
    private fun loadBitmap(resName: String): Bitmap? {
        bitmapCache[resName]?.let { return it }
        val path = currentPack.resolve(resName) ?: return null
        val src = try {
            if (currentPack.decodeTarget > 0) {
                decodeAssetScaled(path, currentPack.decodeTarget)
            } else {
                val istr = openPackStream(path)
                BitmapFactory.decodeStream(istr)
            }
        } catch (_: Exception) {
            null
        } ?: return null
        // 解码画布：新包用 decodeTarget(256) 超采样画布；旧包用逻辑画布 globalW×globalH(128)。
        // 解码产物与该画布同尺寸则直接复用，否则经 fillOffset 填充（roll 居中，其余顶部对齐、底部补透明）。
        // 非 roll 图片置顶、透明补在下方 → 矩形框底不是地面基线（脚在画布内、下方为透明），
        // 浮窗定位改由 currentAnchor 的 fay（脚距顶）驱动，与填充规则共用 fillOffset 永不错位。
        // ROLL 例外：roll 为球状对称图，按【居中】填充使图中心恰好落在画布中心，旋转时图绕自身中心自转、视觉静止。
        val canvasRes = if (currentPack.decodeTarget > 0) currentPack.decodeTarget else globalW
        val bmp = if (src.width == canvasRes && src.height == canvasRes) {
            src
        } else {
            val (padX, padY) = fillOffset(resName, src.width, src.height, canvasRes, canvasRes)
            val out = Bitmap.createBitmap(canvasRes, canvasRes, Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(out)
            c.drawBitmap(src, padX.toFloat(), padY.toFloat(), null)
            src.recycle()
            out
        }
        bitmapCache[resName] = bmp
        return bmp
    }

    /**
     * 下采样解码（仅新包 decodeTarget>0 用）：先以 inJustDecodeBounds 取原始尺寸算 inSampleSize
     * （按最长边向上取整到目标尺寸，避免欠采样发虚），粗降后【保比例】精确缩放，使最长边 = target，
     * 而非压成 target×target 方块（否则非正方形图会被拉伸变形）。
     * 真实内容尺寸由 scaledContentSize 计算，与 rawBitmapSize 共用，保证锚点/画布一致。
     */
    private fun decodeAssetScaled(path: String, target: Int): Bitmap? {
        val istr1 = try { openPackStream(path) } catch (_: Exception) { return null }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(istr1, null, bounds)
        istr1?.close()
        val realW = bounds.outWidth.takeIf { it > 0 } ?: return null
        val realH = bounds.outHeight.takeIf { it > 0 } ?: return null
        val (outW, outH) = scaledContentSize(realW, realH, target)   // 保比例：最长边 = target
        val longSide = if (realW > realH) realW else realH
        val inSample = ((longSide + target - 1) / target).coerceAtLeast(1)
        val istr2 = try { openPackStream(path) } catch (_: Exception) { return null }
        val opts = BitmapFactory.Options().apply { inSampleSize = inSample }
        val base = BitmapFactory.decodeStream(istr2, null, opts) ?: return null
        return if (base.width == outW && base.height == outH) base
        else Bitmap.createScaledBitmap(base, outW, outH, true).also { if (it != base) base.recycle() }
    }

    /**
     * 计算「最长边缩到 target、保比例」后的内容尺寸（与 decodeAssetScaled 产物严格一致）。
     * 例：1000×500, target=128 → 128×64；128×64 再经 fillOffset 顶部对齐、底部补透明到统一画布。
     */
    private fun scaledContentSize(realW: Int, realH: Int, target: Int): Pair<Int, Int> {
        if (realW <= 0 || realH <= 0) return target to target
        val longSide = if (realW > realH) realW else realH
        val scale = target.toFloat() / longSide
        val outW = (realW * scale).roundToInt().coerceAtLeast(1)
        val outH = (realH * scale).roundToInt().coerceAtLeast(1)
        return outW to outH
    }

    /** 释放位图缓存（View 销毁时调用） */
    fun clearCache() {
        bitmapCache.clear()
    }
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
