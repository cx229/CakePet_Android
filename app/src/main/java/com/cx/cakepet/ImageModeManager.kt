package com.cx.cakepet

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlin.math.roundToInt
import kotlin.random.Random
import org.json.JSONArray
import org.json.JSONObject

/**
 * 图片资源包：抽象“不同套美术资源”的加载策略。
 * - [resolve]：resName(如 "sit_clam-1.png") -> assets 相对路径(如 "img/sit_clam-1.png"
 *   / "芝麻酥/安静/sit_clam-1.png")；返回 null 表示该包不含此帧（加载时回退到 fallback）。
 * - [decodeTarget]：物理解码目标边长(px)；0 = 保持原始尺寸（默认包，原生 128）。
 *   新包原图 1000×1000，设为 256 ⇒ 解码期超采样到 256（最长边），作 2x 抗锯齿源；
 *   但【逻辑/锚点/画布】空间仍由 [anchorRes]=128 决定，渲染时把 256 位图按 ratio 缩进 128 逻辑矩形，
 *   既复用现有 128 逻辑（JSON 锚点/边界/scaleFactor 零改动），又比硬压 128 清晰 2x。
 * - [anchorRes]：逻辑分辨率（锚点/画布/UI 坐标系的“1”单位，恒为 128）。
 *   与 decodeTarget 解耦：decodeTarget=256、anchorRes=128 ⇒ 解码超采样 2x、逻辑仍 128。
 *   默认包 decodeTarget=0（原生=128=逻辑），此字段不生效。
 * - [forceBottomCenterAnchor]：true 时忽略逐帧 anchorX/anchorY，统一用“图底中心”。
 *   （先期约定：新包所有动作锚点都先假设在图片底部中心。）
 */
data class SpritePack(
    val id: String,
    val resolve: (String) -> String?,
    val configPath: String? = null,   // 该包的动画/帧/锚点配置（assets 内相对路径），如 "img/modes.json" / "芝麻酥/modes.json"
    val decodeTarget: Int = 0,         // 物理超采样解码边长（0=原生）；新包 256
    val anchorRes: Int = 128,          // 逻辑分辨率（锚点/画布/UI 坐标系单位），恒 128
    val forceBottomCenterAnchor: Boolean = false
)

/**
 * 动画模式管理：每帧时延严格对齐 PC（image_modes.py）。
 * - 每个模式有帧序列 + 与帧一一对应的时延列表（可为固定值或随机区间）。
 * - 帧序列按 next 链循环（loop=true）或在最后一帧结束后切到指定下一模式（一次性动画）。
 * - 常驻模式支持 PC 的 time_next：随机间隔后按概率切换到指定/随机下一模式。
 */
class ImageModeManager(
    private val ctx: android.content.Context,
    initialPack: SpritePack = DEFAULT_SPRITE_PACK
) {

    companion object {
        const val DEFAULT_MODE = "sit_clam"
        const val PAT_HEAD = "pat_head"
        const val SHAKE_HEAD = "shake_head"
        const val JUMP_DOWN = "jump_down"
        const val LIFT_UP = "lift_up"
        const val SIT_CLAM = "sit_clam"
        const val SIT_PUFFED = "sit_puffed"
        const val ROLL = "roll"
        const val WALK = "walk"
        const val WALK_WHITE = "walk_white"
        const val WRIGGLE = "wriggle"
        const val PULL_FISH = "pull_fish"
        const val PROBE_HEAD = "probe_head"
        const val SNAP_HEAD = "snap_head"
        const val SNAP_PAT_HEAD = "snap_pat_head"   // 吸附探头摸摸头：吸附态下的“摸头”动作，结束后回 snap_head
        const val LIE = "lie"
        const val PORTAL = "portal"

        /** 默认资源包：assets/img/ 下按原图尺寸加载，沿用逐帧锚点。 */
        val DEFAULT_SPRITE_PACK = SpritePack(
            id = "default",
            resolve = { "img/$it" },
            configPath = "img/modes.json"
        )

        /**
         * 构建“芝麻酥”新资源包：扫描 assets/芝麻酥/ 下所有 png，
         * 以文件名(含扩展名)为 key 映射到完整相对路径（按子目录归类，如 安静/sit_clam-1.png）。
         * 仅加载与默认包【同名】的帧（多出来的 sit_thinking/probe_pat_head/sir_look/jump/side_walk 暂不接入）。
         */
        /**
         * “芝麻酥”资源包：modes.json 中的 res 直接以“子目录/文件名”给出（如 "安静/sit_clam-1.png"），
         * 这里只拼 “芝麻酥/” 前缀即可定位，无需在初始化时递归扫描 assets 目录。
         */
        @Suppress("UNUSED_PARAMETER")
        fun zhimasuPack(ctx: android.content.Context): SpritePack {
            return SpritePack(
                id = "zhimasu",
                resolve = { "芝麻酥/$it" },
                configPath = "芝麻酥/modes.json",
                decodeTarget = 256,                // 物理超采样 2x（逻辑仍为 anchorRes=128）
                anchorRes = 128,
                forceBottomCenterAnchor = true
            )
        }
    }

    /** 单帧定义：资源名、下一帧索引（null=序列结束）、时延(ms)、锚点(图片像素坐标，对齐 PC image_meta) */
    private data class Frame(
        val resName: String,
        val nextIndex: Int?,            // 下一帧在 frames 中的下标，null 表示序列结束
        val delay: () -> Long,          // 该帧停留时长(ms)
        val anchorX: Int = -1,          // 图片像素锚点 X（<0 表示用图宽/2 居中）
        // 图片像素锚点 Y（距【底】边，左下角原点，Y 向上）。
        // 合法范围 [-128, 128]：0=贴底，128=贴顶，负数=脚在图底下方（图外，如 walk/wriggle 的脚实际在地面以下）。
        // null 表示未设置：当 pack 强制底部中心(forceBottomCenterAnchor)时按 0(贴底)处理，否则也按 0。
        // 注意：当 pack.forceBottomCenterAnchor=true 时，仅当本帧在 JSON 显式给出 anchorY 才覆盖默认底中，否则沿用底中。
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
    private lateinit var currentName: String
    private var currentDef: ImageModeDef? = null
    private var frameIndex = 0
    private var acc = 0L          // 当前帧已累积时间(ms)
    private var forcedSeq: ImageModeDef? = null   // 一次性动画
    private var forcedOnEnd: (() -> Unit)? = null
    private var timeNextAcc = 0L
    private var timeNextTarget = 0L
    /** 当前生效的资源包；默认=默认包，可由 setPack 运行时切换（关于页“使用新资源”）。 */
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
     * 默认包含多个固定位常驻模式，使宠物不会一直停留在 sit_clam。
     * 后续可按状态（如关闭重力）裁剪此池，不含位移/物理驱动类模式。
     */
    private var standbyPool = mutableListOf(
        SIT_CLAM, SIT_PUFFED, WRIGGLE, PROBE_HEAD, SHAKE_HEAD, LIE, WALK_WHITE, WALK,
        PULL_FISH   // 扒鱼：待机可随机触发（一次性位移动作，播完回 LIE）
    )

    /**
     * 静止池：待机池去除位移/物理驱动类模式（蠕动、walk、扒鱼）。
     * 当“重力·抛掷”关闭（gravitEnabled=false）时使用，宠物保持在原地的小动作/静坐。
     */
    private var staticPool = listOf(
        SIT_CLAM, SIT_PUFFED, PROBE_HEAD, SHAKE_HEAD, LIE, WALK_WHITE
    )

    /**
     * true 时使用静止池 staticPool（去除蠕动/walk/扒鱼）；false 使用完整待机池。
     * 由 PetService 根据 gravityEnabled 同步。
     */
    var useStaticPool: Boolean = false

    // 根据开关选择当前待机池
    private fun currentStandbyPool(): List<String> =
        if (useStaticPool) staticPool else standbyPool

    init {
        var loaded = false
        if (currentPack.configPath != null) {
            try {
                loadModesFromJson(currentPack.configPath!!)
                loaded = true
            } catch (_: Exception) {
                // 解析失败：回退到下方写死的内建模式（保持旧行为，避免宠物崩溃）
            }
        }
        if (!loaded) {
        // ============ 常驻：静坐-安静（SIT_CLAM）============
        // 帧链：1→2→3→2→1(loop)。delay 对齐 PC SitClamMode
        registerMode(ImageModeDef(
            SIT_CLAM, "sit_clam-1.png",
            listOf(
                Frame("sit_clam-1.png", 1, rnd(120, 10_000), anchorY = 1),
                Frame("sit_clam-2.png", 2, rnd(120, 160), anchorY = 1),
                Frame("sit_clam-3.png", 3, rnd(120, 160), anchorY = 1),
                Frame("sit_clam-2.png", 0, rnd(120, 160), anchorY = 1)
            ),
            loop = true,
            timeNextEnabled = true,
            changeIntervalMin = 3000, changeIntervalMax = 25_000, changeProb = 1f,
            // PC：sit 之间随机切换（常驻池），由 time_next 触发后随机选下一模式
            onSequenceEnd = { _ -> } // 占位，实际切换在 timeNext 里处理
        ))

        // ============ 常驻：静坐-炸毛（SIT_PUFFED）============
        registerMode(ImageModeDef(
            SIT_PUFFED, "sit_puffed-1.png",
            listOf(
                Frame("sit_puffed-1.png", 1, rnd(120, 10_000), anchorY = 1),
                Frame("sit_puffed-2.png", 2, rnd(120, 160), anchorY = 1),
                Frame("sit_puffed-3.png", 3, rnd(120, 160), anchorY = 1),
                Frame("sit_puffed-4.png", 4, rnd(120, 160), anchorY = 1),
                Frame("sit_puffed-2.png", 0, rnd(120, 160), anchorY = 1)
            ),
            loop = true,
            timeNextEnabled = true,
            changeIntervalMin = 3000, changeIntervalMax = 25_000, changeProb = 1f
        ))

        // ============ 一次性：摸头（PAT_HEAD）→ 摇头 ============
        registerMode(ImageModeDef(
            PAT_HEAD, "sit_clam-1.png",
            listOf(
                Frame("pat_head-1.png", 1, rnd(105, 110), anchorY = 1),
                Frame("pat_head-2.png", 2, rnd(100, 110), anchorY = 1),
                Frame("pat_head-3.png", 3, rnd(100, 110), anchorY = 1),
                Frame("pat_head-4.png", 4, rnd(105, 110), anchorY = 1),
                Frame("pat_head-5.png", 1, rnd(105, 110), anchorY = 1)  // 闭合内部循环 1->2->3->4->5->1
            ),
            loop = false,                       // 循环由 timeNext 机制驱动，而非帧链 loop
            nextModeName = SHAKE_HEAD,          // 概率跳出后的下一个模式（对齐 PC）
            timeNextEnabled = true,             // 启用内部循环 + 概率跳出
            changeIntervalMin = 3000,
            changeIntervalMax = 4000,
            changeProb = 0.95f                  // 每 3~4s 以 95% 概率跳出到 SHAKE_HEAD
        ))

        // ============ 一次性：摇头（SHAKE_HEAD）→ 炸毛 ============
        // 对齐 PC ShakeHeadMode：两帧内部循环(1->2->1) + timeNext(1~2s, prob=1) 跳出到炸毛。
        // PC: conf S1->S2->S1, delay randint(140,180)；change_interval 1000~2000, change_prob=1,
        //     next=SitPuffed。
        registerMode(ImageModeDef(
            SHAKE_HEAD, "sit_clam-1.png",
            listOf(
                Frame("shake_head-1.png", 1, rnd(140, 180), anchorY = 1),  // -> 帧2
                Frame("shake_head-2.png", 0, rnd(140, 180), anchorY = 1)   // -> 帧1（闭合 1<->2 循环）
            ),
            loop = false,                       // 循环由 timeNext 机制驱动，而非帧链 loop
            nextModeName = SIT_PUFFED,
            timeNextEnabled = true,             // 启用内部循环 + 概率跳出
            changeIntervalMin = 1000,
            changeIntervalMax = 2000,
            changeProb = 1f                      // 每 1~2s 必跳出到 SIT_PUFFED（对齐 PC change_prob=1）
        ))

        // ============ 一次性：掉落（JUMP_DOWN）-> 落地停留 200~250ms -> 行走 ============
        // 对齐 PC ThrowFallStandFollowMode(JumpDown)：帧 S1->S2->S3，delay randint(50,70)/randint(80,90)/
        //     randint(200,250)；time_next 关闭；change_mode 依 throw_follow_enabled 决定落地后
        //     回 Walk(默认 True) 或 Lie。安卓无跟随开关，取 PC 默认(True) → 回行走(WALK)。
        registerMode(ImageModeDef(
            JUMP_DOWN, "sit_clam-1.png",
            listOf(
                // 空中下落帧（S1/S2）：PC 默认 (宽/2, 高)=(64,128)，即"图片底部当成假设地面线"，
                // 宠物随物理整体下落，脚相对图位置不变，表现下落途中。jump_down 是原生 128 图无 padding。
                Frame("jump_down-1.png", 1, rnd(50, 70), anchorX = 64, anchorY = 1),
                Frame("jump_down-2.png", 2, rnd(80, 90), anchorX = 64, anchorY = 1),
                // 落地帧（S3）：停留 200-250ms（对齐 PC）后切 WALK。
                // 采用"顶部填充、底部补透明"后，各落地态（WALK/sit/jump_down 落地）的脚锚点 fay 在统一画布内
                // 由 currentAnchor 按填充规则统一给出，地面线天然一致，无需手动偏移。
                // jump_down 是 128 原生图（无 padding，bh=128），anchorY=1 ⇒ fay=127，与 WALK 连续、无跳变。
                // 期间可被拖拽/抛掷（cancelForced）或主动切换（菜单 ACTION_JUMP 等）打断。
                Frame("jump_down-3.png", null, rnd(200, 250), anchorX = 64, anchorY = 1)
            ),
            loop = false,
            nextModeName = WALK
        ))

        // ============ 一次性（拖拽中循环）：提（LIFT_UP）============
        // 帧链严格对齐 PC DragFollowMode（注意 S1/S2 的 next 与 S3~S6 的 next）：
        //  - S1(0)->S2(1)，S2(1)->S1(0)：慢速轻晃闭环 0↔1（即 PC 的 0101）。
        //  - S3(2)->S1(0)，S4(3)->S2(1)，S5(4)->S3(2)，S6(5)->S4(3)：
        //    高速甩动从 S3/S4/S5/S6 起点沿链 3→4→5→6→（S6.next=S4，即回 S4 再走 4→5→6）后，
        //    经 S3->S1 / S4->S2 自然回落到轻晃链 0↔1（对齐 PC 3→1/4→2 的回归）。
        //  delay 对齐 PC：S1/S2 randint(340,370)；S3/S4 200；S5/S6 300。
        registerMode(ImageModeDef(
            LIFT_UP, "sit_clam-1.png",
            listOf(
                Frame("lift_up-1.png", 1, rnd(340, 370), anchorY = 43),  // S1 -> S2
                Frame("lift_up-2.png", 0, rnd(340, 370), anchorY = 43),  // S2 -> S1（轻晃闭环）
                Frame("lift_up-3.png", 0, fixed(200), anchorY = 43),     // S3 -> S1
                Frame("lift_up-4.png", 1, fixed(200), anchorY = 43),     // S4 -> S2
                Frame("lift_up-5.png", 2, fixed(300), anchorY = 43),     // S5 -> S3
                Frame("lift_up-6.png", 3, fixed(300), anchorY = 43)      // S6 -> S4
            ),
            loop = true   // 拖拽期间持续循环
        ))

        // ============ 一次性（抛掷中循环）：翻滚（ROLL）============
        // PC RollAction: 每帧 offset=QPoint(-50,0)，纯平移（非重力）。
        registerMode(ImageModeDef(
            ROLL, "roll-6.png",
            listOf(
                Frame("roll-1.png", 1, rnd(140, 180), anchorX = 45, anchorY = 15),
                Frame("roll-2.png", 2, rnd(140, 180), anchorX = 45, anchorY = 15),
                Frame("roll-3.png", 3, rnd(140, 180), anchorX = 45, anchorY = 15),
                Frame("roll-4.png", 4, rnd(140, 180), anchorX = 45, anchorY = 15),
                Frame("roll-5.png", 5, rnd(140, 180), anchorX = 45, anchorY = 15),
                Frame("roll-6.png", 0, rnd(140, 180), anchorX = 45, anchorY = 15)
            ),
            loop = true   // 抛掷期间持续循环
        ))

        // ============ 行走（WALK，常驻/跟随循环）============
        // PC WalkMode: 每帧 offset=QPoint(-5,0)。
        // 注：PC 原始 4 帧(walk-1..4)，但 Android 资源仅有 walk-1/2/3.png（无 walk-4.png），
        // 故改为 3 帧循环（1→2→3→1），anchor (47,59) 保持与 PC 一致，每帧 100ms。
        // 缺失帧不引用，避免 loadBitmap 回退到 fallback 重复显示 walk-1 造成节奏错位。
        registerMode(ImageModeDef(
            WALK, "walk-1.png",
            listOf(
                Frame("walk-1.png", 1, fixed(100), anchorX = 47, anchorY = 1),
                Frame("walk-2.png", 2, fixed(100), anchorX = 47, anchorY = 1),
                Frame("walk-3.png", 0, fixed(100), anchorX = 47, anchorY = 1)
            ),
            loop = true,
            timeNextEnabled = true,                       // 常驻行走，过段时间随机切换到其他待机模式
            changeIntervalMin = 1000,
            changeIntervalMax = 10_000,
            walkSpeed = 90f                               // 水平位移速度(px/s)，方向随机（对齐 PC QPoint(-5,0)）
        ))

        // ============ 美白（WALK_WHITE，一次性）============
        registerMode(ImageModeDef(
            WALK_WHITE, "sit_clam-1.png",
            listOf(
                Frame("walk_white-1.png", 1, rnd(200, 300), anchorX = 47, anchorY = 1),
                Frame("walk_white-2.png", 2, rnd(200, 300), anchorX = 47, anchorY = 1),
                Frame("walk_white-3.png", 3, rnd(200, 300), anchorX = 47, anchorY = 1),
                Frame("walk_white-4.png", 4, rnd(200, 300), anchorX = 47, anchorY = 1),
                Frame("walk_white-5.png", null, rnd(5000, 10_000), anchorX = 47, anchorY = 1)
            ),
            loop = false
            // 下一个无指定：对齐 PC，美白结束后回到待机随机池（菜单与待机池激活一致）
        ))

        // ============ 蠕动（WRIGGLE，一次性/常驻）============
        registerMode(ImageModeDef(
            WRIGGLE, "sit_clam-1.png",
            listOf(
                Frame("wriggle-1.png", 1, rnd(250, 300), anchorX = 54, anchorY = 1),
                Frame("wriggle-2.png", 0, rnd(250, 300), anchorX = 54, anchorY = 1)
            ),
            loop = true,
            timeNextEnabled = true,
            changeIntervalMin = 3000, changeIntervalMax = 4000, changeProb = 0.5f,
            walkSpeed = 45f                                // 蠕动缓慢位移(px/s)，方向随机（对齐 PC QPoint(-3,0)）
        ))

        // ============ 探头（PROBE_HEAD，一次性）============
        // 对齐 PC ProbeHeadMode：帧 S1->S2->S1 内部循环（图2 后回图1），
        // delay randint(1000,5000)/randint(150,300)；timeNext 5~10s、prob=0.5 跳出到 SitClam。
        registerMode(ImageModeDef(
            PROBE_HEAD, "sit_clam-1.png",
            listOf(
                Frame("probe_head-1.png", 1, rnd(1000, 5000), anchorY = 16),
                Frame("probe_head-2.png", 0, rnd(150, 300), anchorY = 16)   // -> 帧1（闭合 1<->2 循环）
            ),
            loop = false,                       // 循环由 timeNext 机制驱动，而非帧链 loop
            nextModeName = SIT_CLAM,
            timeNextEnabled = true,
            changeIntervalMin = 5000,
            changeIntervalMax = 10_000,
            changeProb = 0.5f
        ))

        // ============ 吸附探头（SNAP_HEAD，常驻循环）============
        // 吸附态复用的“探头”动作：帧 S1->S2->S1 内部循环（与 PROBE_HEAD 同资源），
        // 但 loop=true 永久循环、不跳出到 SitClam，脚朝向被贴附的边（由 gravityDir 表达）。
        // 退出吸附（用户拖出）由 PetView 切回其他状态负责。
        registerMode(ImageModeDef(
            SNAP_HEAD, "sit_clam-1.png",
            listOf(
                Frame("probe_head-1.png", 1, rnd(1000, 5000), anchorY = 16),
                Frame("probe_head-2.png", 0, rnd(150, 300), anchorY = 16)   // -> 帧1（闭合 1<->2 循环）
            ),
            loop = true,                        // 常驻循环，不跳出
            nextModeName = null,
            timeNextEnabled = false,
            changeProb = 0f
        ))

        // ============ 吸附探头摸摸头（SNAP_PAT_HEAD，吸附态专属）============
        // 旧资源无独立“探头摸摸头”美术，复用 probe_head 帧；新资源用 探头摸摸头/probe_pat_head-*。
        // 与 PAT_HEAD 类似：一次性动作，播完后由 nextModeName 回到吸附探头(SNAP_HEAD)，不落入 sit_clam。
        registerMode(ImageModeDef(
            SNAP_PAT_HEAD, "probe_head-1.png",
            listOf(
                Frame("probe_head-1.png", 1, rnd(1000, 5000), anchorY = 16),
                Frame("probe_head-2.png", 0, rnd(150, 300), anchorY = 16)   // -> 帧1（闭合 1<->2 循环）
            ),
            loop = false,
            nextModeName = SNAP_HEAD,
            timeNextEnabled = true,
            changeIntervalMin = 5000,
            changeIntervalMax = 10_000,
            changeProb = 0.5f
        ))

        // ============ 趴着（LIE，常驻单帧）============
        // 复用 wriggle 的图片资源（无独立 lie 美术资源，PC 侧同理复用），
        // fallback 也指向同一图，避免回退到 sit_clam。
        registerMode(ImageModeDef(
            LIE, "wriggle-1.png",
            // 复用 wriggle-1.png，锚点必须与 wriggle 模式完全一致（anchorX=54, anchorY=42），
            // 否则同一张图在不同模式下锚点不同 → 图片位置偏移。lie 是趴姿静止，单帧即可。
            listOf(Frame("wriggle-1.png", null, rnd(3000, 7000), anchorX = 54, anchorY = 1)),
            loop = false
            // 下一个无指定：对齐 PC，趴着结束后回到待机随机池（菜单与待机池激活一致）
        ))

        // ============ 传送门（PORTAL，占位）============
        registerMode(ImageModeDef(
            PORTAL, "sit_clam-1.png",
            listOf(Frame("sit_clam-1.png", null, fixed(3000), anchorY = -28)),
            loop = false,
            nextModeName = SIT_CLAM
        ))

        // ============ 扒鱼（PULL_FISH，帧偏移位移 + 一次性）============
        // 对齐 PC PullFishMode：位移由【每帧 offset 累加】驱动（非物理速度），属于单循环位移。
        // PC 第6~9帧带 QPoint(130/125/120/120 * ratio, 0) 水平偏移 + anchor_dy 垂直锚点变化。
        // ratio 取 0.7~1.5 随机（与 PC 一致）。dx 为每帧水平位移，dy 为垂直位移（本模式仅水平）。
        // 扒鱼支持镜像（类似 walk/蠕动）：图片资源朝右，flipX=true 翻转朝左；
        // 位移方向随 flipX 反向、锚点对称翻转（见 PetView）。撞墙夹边（对齐 PC adjust_offset_screen）。
        val pfRatio = Random.nextFloat() * 0.8f + 0.7f   // 0.7 ~ 1.5
        registerMode(ImageModeDef(
            PULL_FISH, "sit_clam-1.png",
            listOf(
                Frame("pull_fish-1.png", 1, rnd(300, 500), anchorX = 54, anchorY = 1),
                Frame("pull_fish-2.png", 2, rnd(130, 160), anchorX = 54, anchorY = 1),
                Frame("pull_fish-3.png", 3, rnd(160, 200), anchorX = 54, anchorY = 1),
                Frame("pull_fish-4.png", 4, rnd(130, 160), anchorX = 54, anchorY = 1),
                Frame("pull_fish-5.png", 5, fixed(70), anchorX = 54, anchorY = 1),
                // 第6帧：S6 基础锚点(67,39) + anchor_dy(70*ratio)；水平位移 130*ratio
                // 转换器：距底 = rawH(90) - 距顶(67+70*ratio) = 23 - 70*ratio
                Frame("pull_fish-6.png", 6, fixed(70), anchorX = 67, anchorY = (23 - 70 * pfRatio).toInt(),
                    dx = (130 * pfRatio).toInt()),
                // 第7帧：复用 pull_fish-6.png（PC replace(anchor_dy=110*ratio)），距底 = 90 - (67+110*ratio) = 23 - 110*ratio
                Frame("pull_fish-6.png", 7, fixed(70), anchorX = 67, anchorY = (23 - 110 * pfRatio).toInt(),
                    dx = (125 * pfRatio).toInt()),
                // 第8帧：复用 pull_fish-6.png（PC replace(anchor_dy=70*ratio)），距底 = 23 - 70*ratio
                Frame("pull_fish-6.png", 8, fixed(70), anchorX = 67, anchorY = (23 - 70 * pfRatio).toInt(),
                    dx = (120 * pfRatio).toInt()),
                // 第9帧：收尾过渡，PC 用 Wriggle.S1（wriggle-1.png）+ anchor_dy=0 → 距底 = 43 - 81 = -38
                Frame("wriggle-1.png", null, fixed(500), anchorX = 54, anchorY = 1,
                    dx = (120 * pfRatio).toInt())
            ),
            loop = false,
            walkSpeed = 0f,        // 非循环位移：用帧 dx 驱动，而非物理 walkSpeed
            nextModeName = LIE
        ))

        } // end if(!loaded)

        currentName = DEFAULT_MODE
        currentDef = modes[DEFAULT_MODE] ?: modes.values.firstOrNull()
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
        val text = ctx.assets.open(path).bufferedReader().use { it.readText() }
        val root = JSONObject(text)
        // 包级：是否强制底部中心锚点（覆盖 currentPack 默认值，便于 JSON 驱动）
        val forceBottom = if (root.has("forceBottomCenterAnchor"))
            root.getBoolean("forceBottomCenterAnchor") else currentPack.forceBottomCenterAnchor
        currentPack = currentPack.copy(forceBottomCenterAnchor = forceBottom)
        // 清空旧模式，按 JSON 重建
        modes.clear()
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
            val framesArr = m.getJSONArray("frames")
            val frames = mutableListOf<Frame>()
            for (i in 0 until framesArr.length()) {
                val f = framesArr.getJSONObject(i)
                val res = f.getString("res")
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
        // 待机池 / 静止池
        if (root.has("standbyPool")) {
            standbyPool.clear()
            val sp = root.getJSONArray("standbyPool")
            for (i in 0 until sp.length()) standbyPool.add(sp.getString(i))
        }
        if (root.has("staticPool")) {
            val arr = root.getJSONArray("staticPool")
            staticPool = List(arr.length()) { arr.getString(it) }
        }
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

    /**
     * 提起(LIFT_UP)模式下，按拖动速度/方向跳到对应系列起点帧（对齐 PC DragFollowMode.drag_func）：
     *  - 慢速：轻晃链起点（lift_up-1，index 0），由帧链自然循环 0→1→0（S1↔S2）；
     *  - 中速向左：index 3（lift_up-4 = S4）；快速向左：index 5（lift_up-6 = S6）；
     *  - 中速向右：index 2（lift_up-3 = S3）；快速向右：index 4（lift_up-5 = S5）。
     * PC 设起点后按 nextIndex 链继续播放（S3→S4→S5→S6→S1…），安卓 nextFrame 同此链式推进，
     * 故“跳到起点”即可自动沿用链。仅当当前确为 LIFT_UP 才生效，避免误改其他模式帧序。
     * series<0 表示“不主动跳”（由调用方守护，慢速时保持轻晃链），此函数忽略。
     */
    /**
     * 提起(LIFT_UP)模式下按拖动速度/方向定到对应帧（对齐 PC DragFollowMode.drag_func）：
     *  - 慢速：不调用（由帧链 0↔1 自然轻晃）。
     *  - 中/快：由 PetView 每帧调用，定住对应甩动帧（S3/S4/S5/S6 之一，不播链），
     *    返回 true 表示帧下标确实发生变化（调用方据此【当帧立即】刷新位图，
     *    绕过 33ms 节流，避免切帧视觉延迟）；frameIndex/acc 已强制设好。
     */
    fun setLiftSeries(series: Int): Boolean {
        if (forcedSeq != null) return false
        if (currentName != LIFT_UP) return false
        if (series < 0 || series >= (currentDef?.frames?.size ?: 0)) return false
        if (frameIndex == series && acc == 0L) return false
        frameIndex = series
        acc = 0L
        return true
    }

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
            // 回到常驻默认模式，确保后续 setMode 能正常切换
            if (currentName == forcedSeq?.name) setMode(DEFAULT_MODE)
        }
    }

    /**
     * 强制复位（召回）：无论当前在播放一次性动画还是常驻模式，立即清除动作并切回静坐（sit_clam），
     * 与 PetPhysics.resetTo 配合实现“召回归位、静止、显示 sit-calm”。
     */
    fun forceReset() {
        forcedSeq = null
        forcedOnEnd = null
        setMode(DEFAULT_MODE)
    }

    /**
     * 运行时切换资源包（关于页“使用新资源”开关触发）。
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
            } catch (_: Exception) {
                // 换包配置解析失败：保留当前已加载的 modes，避免空模式导致宠物卡死
            }
        }
        val (w, h) = globalMaxSize()
        globalW = w
        globalH = h
        forcedSeq = null
        forcedOnEnd = null
        currentName = DEFAULT_MODE
        currentDef = modes[DEFAULT_MODE] ?: modes.values.firstOrNull()
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
    fun playOnce(name: String, endMode: String = DEFAULT_MODE) {
        val def = modes[name] ?: return
        val next = def.nextModeName
        forcedSeq = def
        forcedOnEnd = {
            forcedSeq = null
            forcedOnEnd = null
            if (next != null) {
                setMode(next)
            } else {
                // 无指定下一模式：对齐 PC change_mode，结束回待机随机池
                val pool = currentStandbyPool().filter { it != currentName }
                if (pool.isNotEmpty()) setMode(pool.random()) else setMode(DEFAULT_MODE)
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
                val istr = ctx.assets.open(path)
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(istr, null, opts)
                istr.close()
                // 逻辑尺寸：按【anchorRes=128】保比例算，而非物理 decodeTarget(256)。
                // 锚点/画布/边界全部活在 128 逻辑空间，decodeTarget 只影响解码清晰度、不影响坐标。
                scaledContentSize(opts.outWidth, opts.outHeight, currentPack.anchorRes)
            } catch (_: Exception) {
                currentPack.anchorRes to currentPack.anchorRes   // 读不到边界：回退逻辑方块尺寸，避免下游异常
            }
        } else {
            try {
                val istr = ctx.assets.open(path)
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
        // 默认底部中心(forceBottomCenterAnchor=true)时，逐帧 anchorX/anchorY 一般被忽略；
        // 但若某帧在 JSON 里显式给出 anchorX(>=0) / anchorY(非 null)，则该帧覆盖默认、使用自定义锚点。
        val forced = currentPack.forceBottomCenterAnchor
        val frameHasAx = frame.anchorX >= 0           // JSON 给了非负 anchorX 即视为显式
        val frameHasAy = frame.anchorY != null        // JSON 给了 anchorY 即视为显式
        val axIn = if (forced && !frameHasAx) -1 else frame.anchorX
        val ayIn = if (forced && !frameHasAy) 0 else (frame.anchorY ?: 0)
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
                    setMode(def.nextModeName)
                    return FrameStep(true)
                } else {
                    // 无指定下一模式（美白、趴，对齐 PC "下一个无指定"）：
                    // 与 playOnce 的 forcedOnEnd 一致，结束回待机随机池，
                    // 保证「菜单激活」与「待机池自然选中」后续流转完全统一。
                    val pool = currentStandbyPool().filter { it != currentName }
                    if (pool.isNotEmpty()) setMode(pool.random())
                    else setMode(DEFAULT_MODE)
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
        if (def.timeNextEnabled) {
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
                        // 无指定：待机池随机切换（排除当前模式）
                        val pool = currentStandbyPool().filter { it != currentName }
                        if (pool.isNotEmpty()) {
                            setMode(pool.random())
                            return FrameStep(true)
                        }
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

    /**
     * 单图资源相对统一画布的填充偏移 (padX, padY)：【唯一】填充规则来源。
     * 同时供 loadBitmap（像素落点）与 padOf/currentAnchor（锚点补偿）使用，
     * 保证“填充方式”与“锚点计算”永远是同一套规则，不会因改填充而锚点错位。
     *
     * 规则（以图片资源名为单位，粒度 = resName.startsWith("roll") 为唯一例外）：
     *  - 水平：始终居中，padX = (globalW - bw) / 2。
     *  - 垂直：ROLL 类（球状对称）【居中】填充（padY = (globalH - bh)/2）；
     *          其余图片【顶部】填充（padY = 0，图片置顶，透明空白补在图片【下方】至画布底），
     *          即“除 roll 外，其他图片为底部填充透明”——用下方透明把图撑到目标尺寸 globalH。
     *
     * 锚点传导（关键）：currentAnchor 经 padOf 复用本规则，fay = (rawH - 距底) + padY；
     * 非 roll 的 padY=0 ⇒ fay = rawH - 距底，即脚在画布内的【距顶】距离，画布底到脚之间是透明。
     * 浮窗定位只认 fay（脚），透明区不参与，故各帧脚在世界坐标仍按锚点对齐；
     * 但“矩形框底 = 统一地面基线”不再成立（画布底在脚下方、是透明）。
     *
     * 必须用【原始】PNG 尺寸（bw/bh）算 pad，不能读 bitmapCache——
     * 缓存里存的是已 padding 到统一画布(globalW×globalH)的位图，若用其尺寸则 padY=globalH-globalH=0，
     * 但此处非 roll 本就 padY=0，读缓存反而掩盖了真实原始高度，故仍禁止。
     */
    private fun fillOffset(resName: String, bw: Int, bh: Int, canvasW: Int = globalW, canvasH: Int = globalH): Pair<Int, Int> {
        if (bw <= 0 || bh <= 0) return 0 to 0
        val padX = ((canvasW - bw).coerceAtLeast(0)) / 2
        // roll：垂直居中；其余置顶（padY=0，透明填充在图片【下方】）。
        val isRoll = resName.startsWith("roll", ignoreCase = true)
        val padY = if (isRoll) (canvasH - bh) / 2 else 0
        return padX to padY
    }

    /** 每帧相对统一画布的偏移（px），供锚点补偿使用。直接复用 fillOffset（单一规则来源）。 */
    private fun padOf(resName: String): Pair<Int, Int> {
        val (bw, bh) = rawBitmapSize(resName)
        return fillOffset(resName, bw, bh)
    }

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
                val istr = ctx.assets.open(path)
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
        val istr1 = try { ctx.assets.open(path) } catch (_: Exception) { return null }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(istr1, null, bounds)
        istr1.close()
        val realW = bounds.outWidth.takeIf { it > 0 } ?: return null
        val realH = bounds.outHeight.takeIf { it > 0 } ?: return null
        val (outW, outH) = scaledContentSize(realW, realH, target)   // 保比例：最长边 = target
        val longSide = if (realW > realH) realW else realH
        val inSample = ((longSide + target - 1) / target).coerceAtLeast(1)
        val istr2 = try { ctx.assets.open(path) } catch (_: Exception) { return null }
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
