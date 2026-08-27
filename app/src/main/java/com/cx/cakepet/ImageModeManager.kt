package com.cx.cakepet

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlin.random.Random

/**
 * 动画模式管理：每帧时延严格对齐 PC（image_modes.py）。
 * - 每个模式有帧序列 + 与帧一一对应的时延列表（可为固定值或随机区间）。
 * - 帧序列按 next 链循环（loop=true）或在最后一帧结束后切到指定下一模式（一次性动画）。
 * - 常驻模式支持 PC 的 time_next：随机间隔后按概率切换到指定/随机下一模式。
 */
class ImageModeManager(private val ctx: android.content.Context) {

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
        const val LIE = "lie"
        const val PORTAL = "portal"
    }

    /** 单帧定义：资源名、下一帧索引（null=序列结束）、时延(ms)、锚点(图片像素坐标，对齐 PC image_meta) */
    private data class Frame(
        val resName: String,
        val nextIndex: Int?,            // 下一帧在 frames 中的下标，null 表示序列结束
        val delay: () -> Long,          // 该帧停留时长(ms)
        val anchorX: Int = -1,          // 图片像素锚点 X（<0 表示用图宽/2 居中）
        val anchorY: Int = -1,          // 图片像素锚点 Y（<0 表示用图高 底边对齐）
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
        val walkSpeed: Float = 0f               // 行走位移速度(px/s)，>0 时由物理引擎驱动水平位移（对齐 PC 帧偏移 QPoint）
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
    private val staticPool = listOf(
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
        // ============ 常驻：静坐-安静（SIT_CLAM）============
        // 帧链：1→2→3→2→1(loop)。delay 对齐 PC SitClamMode
        registerMode(ImageModeDef(
            SIT_CLAM, "sit_clam-1.png",
            listOf(
                Frame("sit_clam-1.png", 1, rnd(120, 10_000), anchorY = 81),
                Frame("sit_clam-2.png", 2, rnd(120, 160), anchorY = 81),
                Frame("sit_clam-3.png", 3, rnd(120, 160), anchorY = 81),
                Frame("sit_clam-2.png", 0, rnd(120, 160), anchorY = 81)
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
                Frame("sit_puffed-1.png", 1, rnd(120, 10_000), anchorY = 81),
                Frame("sit_puffed-2.png", 2, rnd(120, 160), anchorY = 81),
                Frame("sit_puffed-3.png", 3, rnd(120, 160), anchorY = 81),
                Frame("sit_puffed-4.png", 4, rnd(120, 160), anchorY = 81),
                Frame("sit_puffed-2.png", 0, rnd(120, 160), anchorY = 81)
            ),
            loop = true,
            timeNextEnabled = true,
            changeIntervalMin = 3000, changeIntervalMax = 25_000, changeProb = 1f
        ))

        // ============ 一次性：摸头（PAT_HEAD）→ 摇头 ============
        registerMode(ImageModeDef(
            PAT_HEAD, "sit_clam-1.png",
            listOf(
                Frame("pat_head-1.png", 1, rnd(105, 110), anchorY = 81),
                Frame("pat_head-2.png", 2, rnd(100, 110), anchorY = 81),
                Frame("pat_head-3.png", 3, rnd(100, 110), anchorY = 81),
                Frame("pat_head-4.png", 4, rnd(105, 110), anchorY = 81),
                Frame("pat_head-5.png", 1, rnd(105, 110), anchorY = 81)  // 闭合内部循环 1->2->3->4->5->1
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
                Frame("shake_head-1.png", 1, rnd(140, 180), anchorY = 81),  // -> 帧2
                Frame("shake_head-2.png", 0, rnd(140, 180), anchorY = 81)   // -> 帧1（闭合 1<->2 循环）
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
                Frame("jump_down-1.png", 1, rnd(50, 70), anchorX = 64, anchorY = 81),
                Frame("jump_down-2.png", 2, rnd(80, 90), anchorX = 64, anchorY = 81),
                // 落地帧（S3）：停留 200-250ms（对齐 PC）后切 WALK。
                // 采用"垂直贴底 padding"后，所有落地态（WALK/sit/jump_down 落地）的图底都对齐统一画布底边，
                // 地面线天然一致，无需手动偏移。jump_down 是 128 原生图无 padding，故锚点用图底 128，
                // 与 WALK 贴底地面线（127）连续、无跳变。
                // 期间可被拖拽/抛掷（cancelForced）或主动切换（菜单 ACTION_JUMP 等）打断。
                Frame("jump_down-3.png", null, rnd(200, 250), anchorX = 64, anchorY = 81)
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
                Frame("lift_up-1.png", 1, rnd(340, 370), anchorY = 71),  // S1 -> S2
                Frame("lift_up-2.png", 0, rnd(340, 370), anchorY = 71),  // S2 -> S1（轻晃闭环）
                Frame("lift_up-3.png", 0, fixed(200), anchorY = 71),     // S3 -> S1
                Frame("lift_up-4.png", 1, fixed(200), anchorY = 71),     // S4 -> S2
                Frame("lift_up-5.png", 2, fixed(300), anchorY = 71),     // S5 -> S3
                Frame("lift_up-6.png", 3, fixed(300), anchorY = 71)      // S6 -> S4
            ),
            loop = true   // 拖拽期间持续循环
        ))

        // ============ 一次性（抛掷中循环）：翻滚（ROLL）============
        // PC RollAction: 每帧 offset=QPoint(-50,0)，纯平移（非重力）。
        registerMode(ImageModeDef(
            ROLL, "roll-6.png",
            listOf(
                Frame("roll-1.png", 1, rnd(140, 180), anchorX = 45, anchorY = 80),
                Frame("roll-2.png", 2, rnd(140, 180), anchorX = 45, anchorY = 80),
                Frame("roll-3.png", 3, rnd(140, 180), anchorX = 45, anchorY = 80),
                Frame("roll-4.png", 4, rnd(140, 180), anchorX = 45, anchorY = 80),
                Frame("roll-5.png", 5, rnd(140, 180), anchorX = 45, anchorY = 80),
                Frame("roll-6.png", 0, rnd(140, 180), anchorX = 45, anchorY = 80)
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
                Frame("walk-1.png", 1, fixed(100), anchorX = 47, anchorY = 81),
                Frame("walk-2.png", 2, fixed(100), anchorX = 47, anchorY = 81),
                Frame("walk-3.png", 0, fixed(100), anchorX = 47, anchorY =81)
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
                Frame("walk_white-1.png", 1, rnd(200, 300), anchorX = 47, anchorY = 81),
                Frame("walk_white-2.png", 2, rnd(200, 300), anchorX = 47, anchorY = 81),
                Frame("walk_white-3.png", 3, rnd(200, 300), anchorX = 47, anchorY = 81),
                Frame("walk_white-4.png", 4, rnd(200, 300), anchorX = 47, anchorY = 81),
                Frame("walk_white-5.png", null, rnd(5000, 10_000), anchorX = 47, anchorY = 81)
            ),
            loop = false
            // 下一个无指定：对齐 PC，美白结束后回到待机随机池（菜单与待机池激活一致）
        ))

        // ============ 蠕动（WRIGGLE，一次性/常驻）============
        registerMode(ImageModeDef(
            WRIGGLE, "sit_clam-1.png",
            listOf(
                Frame("wriggle-1.png", 1, rnd(250, 300), anchorX = 54, anchorY = 81),
                Frame("wriggle-2.png", 0, rnd(250, 300), anchorX = 54, anchorY = 81)
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
                Frame("probe_head-1.png", 1, rnd(1000, 5000), anchorY = 66),
                Frame("probe_head-2.png", 0, rnd(150, 300), anchorY = 66)   // -> 帧1（闭合 1<->2 循环）
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
                Frame("probe_head-1.png", 1, rnd(1000, 5000), anchorY = 66),
                Frame("probe_head-2.png", 0, rnd(150, 300), anchorY = 66)   // -> 帧1（闭合 1<->2 循环）
            ),
            loop = true,                        // 常驻循环，不跳出
            nextModeName = null,
            timeNextEnabled = false,
            changeProb = 0f
        ))

        // ============ 趴着（LIE，常驻单帧）============
        // 复用 wriggle 的图片资源（无独立 lie 美术资源，PC 侧同理复用），
        // fallback 也指向同一图，避免回退到 sit_clam。
        registerMode(ImageModeDef(
            LIE, "wriggle-1.png",
            // 复用 wriggle-1.png，锚点必须与 wriggle 模式完全一致（anchorX=54, anchorY=42），
            // 否则同一张图在不同模式下锚点不同 → 图片位置偏移。lie 是趴姿静止，单帧即可。
            listOf(Frame("wriggle-1.png", null, rnd(3000, 7000), anchorX = 54, anchorY = 81)),
            loop = false
            // 下一个无指定：对齐 PC，趴着结束后回到待机随机池（菜单与待机池激活一致）
        ))

        // ============ 传送门（PORTAL，占位）============
        registerMode(ImageModeDef(
            PORTAL, "sit_clam-1.png",
            listOf(Frame("sit_clam-1.png", null, fixed(3000), anchorY = 110)),
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
                Frame("pull_fish-1.png", 1, rnd(300, 500), anchorX = 54, anchorY = 81),
                Frame("pull_fish-2.png", 2, rnd(130, 160), anchorX = 54, anchorY = 81),
                Frame("pull_fish-3.png", 3, rnd(160, 200), anchorX = 54, anchorY = 81),
                Frame("pull_fish-4.png", 4, rnd(130, 160), anchorX = 54, anchorY = 81),
                Frame("pull_fish-5.png", 5, fixed(70), anchorX = 54, anchorY = 81),
                // 第6帧：S6 基础锚点(67,39) + anchor_dy(70*ratio)；水平位移 130*ratio
                Frame("pull_fish-6.png", 6, fixed(70), anchorX = 67, anchorY = (67 + 70 * pfRatio).toInt(),
                    dx = (130 * pfRatio).toInt()),
                // 第7帧：复用 pull_fish-6.png（PC replace(anchor_dy=110*ratio)），锚点 X 同 S6=67，Y 下沉(110*ratio)；水平位移 125*ratio
                Frame("pull_fish-6.png", 7, fixed(70), anchorX = 67, anchorY = (67 + 110 * pfRatio).toInt(),
                    dx = (125 * pfRatio).toInt()),
                // 第8帧：复用 pull_fish-6.png（PC replace(anchor_dy=70*ratio)），锚点 X 同 S6=67，Y 回(70*ratio)；水平位移 120*ratio
                Frame("pull_fish-6.png", 8, fixed(70), anchorX = 67, anchorY = (67 + 70 * pfRatio).toInt(),
                    dx = (120 * pfRatio).toInt()),
                // 第9帧：收尾过渡，PC 用 Wriggle.S1（wriggle-1.png）+ anchor_dy=0 → 锚点(54,42)，水平位移 120*ratio，随后回到 LIE
                Frame("wriggle-1.png", null, fixed(500), anchorX = 54, anchorY = 81,
                    dx = (120 * pfRatio).toInt())
            ),
            loop = false,
            walkSpeed = 0f,        // 非循环位移：用帧 dx 驱动，而非物理 walkSpeed
            nextModeName = LIE
        ))

        currentName = DEFAULT_MODE
        currentDef = modes[DEFAULT_MODE]
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

    /** 当前帧位图尺寸（真实像素，随帧变化） */
    fun currentBitmapSize(): Pair<Int, Int> {
        val bmp = currentBitmap()
        return if (bmp != null) bmp.width to bmp.height else 160 to 160
    }

    /**
     * 仅解码取原始位图尺寸（不进入 bitmapCache、不引用 globalW/globalH），
     * 专供 init 阶段计算统一画布尺寸，避免与 globalW/globalH 的 init 形成初始化死循环。
     */
    private fun rawBitmapSize(resName: String): Pair<Int, Int> {
        return try {
            val istr = ctx.assets.open("img/$resName")
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(istr, null, opts)
            opts.outWidth to opts.outHeight
        } catch (_: Exception) {
            0 to 0
        }
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
     * 当前帧锚点（图片像素坐标，对齐 PC image_meta）：
     * - anchorX<0 => 图宽/2（水平居中）
     * - anchorY<0 => 图高（底边对齐）
     * 锚点需叠加【居中 padding 偏移】，因原图被居中 padding 到统一画布后，
     * 脚锚点在统一画布中的真实位置 = 原始 anchor + pad。否则 pad 后图内容偏移、
     * 锚点仍指原图坐标 → 脚落点与图不对齐（错位）。
     */
    fun currentAnchor(): Pair<Int, Int> {
        val def = forcedSeq ?: currentDef ?: return 80 to 160
        val frame = def.frames.getOrNull(frameIndex) ?: return 80 to 160
        val (w, h) = currentBitmapSize()   // padding 后尺寸 = globalW×globalH
        // 翻滚（ROLL）：原图 90×90 中心对称。体感重力下会随 gravityRotation 绕窗口中心旋转，
        // 若锚点偏离窗口中心，rotateOffset 会让浮窗左上角画圆 → 旋转抖动。
        // 故对 ROLL 强制把锚点对齐到统一画布中心（globalW/2, globalH/2），
        // 使任意旋转角下锚点恒等于窗口中心 → 浮窗位置不变、旋转视觉静止。
        // 中心对称图绕中心旋转外观不变，故此特判无视觉错位。
        if (def.name == ROLL) {
            return (globalW / 2) to (globalH / 2)
        }
        val axRaw = if (frame.anchorX >= 0) frame.anchorX else w / 2
        val ayRaw = if (frame.anchorY >= 0) frame.anchorY else h
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
    val globalW: Int
    val globalH: Int
    init {
        val (w, h) = globalMaxSize()
        globalW = w
        globalH = h
    }

    /** 每帧相对统一画布的偏移（px），供锚点补偿使用。水平居中、垂直贴底。 */
    private fun padOf(resName: String): Pair<Int, Int> {
        val bmp = bitmapCache[resName] ?: return 0 to 0
        val dx = (globalW - bmp.width).coerceAtLeast(0)
        val dy = (globalH - bmp.height).coerceAtLeast(0)
        return dx / 2 to dy   // y 贴底：原图底边对齐画布底边
    }

    private fun loadBitmap(resName: String): Bitmap? {
        bitmapCache[resName]?.let { return it }
        // 图片资源在 assets/img/ 下（文件名即 resName，如 sit_clam-1.png）
        val src = try {
            val istr = ctx.assets.open("img/$resName")
            BitmapFactory.decodeStream(istr)
        } catch (_: Exception) {
            null
        }
        val bmp = if (src != null && (src.width != globalW || src.height != globalH)) {
            // 统一画布：水平居中、垂直【贴底】填充（仅图片上方补透明）。
            // 这样所有图的"图底"都对齐统一画布底边 → 矩形框底即统一地面基线，
            // 用户可一眼判断某 mode 的脚/地面是否贴基线（间隙=锚点偏低，越界=不可能）。
            // 锚点补偿见 padOf：padY = globalH - src.height（不再 /2）。
            // 特例 ROLL（roll-*.png）：原图 90×90 中心对称，体感重力下绕窗口中心自转。
            // 若仍贴底填充，图中心(64,83)偏离旋转中心(64,64) → 旋转时图画圈跳动。
            // 故 ROLL 改为【居中】填充，使图中心恰好落在画布中心(64,64)，
            // 配合 currentAnchor 对 ROLL 返回画布中心，旋转时图原地自转、视觉静止。
            val isRoll = resName.startsWith("roll", ignoreCase = true)
            val yOff = if (isRoll) (globalH - src.height) / 2 else (globalH - src.height)
            val out = Bitmap.createBitmap(globalW, globalH, Bitmap.Config.ARGB_8888)
            val c = android.graphics.Canvas(out)
            c.drawBitmap(src, ((globalW - src.width) / 2).toFloat(), yOff.toFloat(), null)
            src.recycle()
            out
        } else {
            src
        }
        bitmapCache[resName] = bmp
        return bmp
    }

    /** 释放位图缓存（View 销毁时调用） */
    fun clearCache() {
        bitmapCache.clear()
    }
}
