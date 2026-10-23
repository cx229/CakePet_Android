package com.cx.cakepet

import kotlin.math.abs

/**
 * 物理引擎（移植自 PC 端 speed_util.cal_throw_rebound_offset + MouseFollowController 抛掷逻辑）。
 *
 * 关键设计：
 * 1. 速度 vx/vy（像素/秒），每帧 dt 推进：offset += v * dt；v += gravity * dt
 * 2. 四边界反弹：碰到开启重力的边界则 v = -v * reboundRatio，并带 sub-pixel 余数累积
 *    （对应 PC 的 QPointF remainder，避免低速抖动丢帧）
 * 3. 四边重力：gravityX/gravityY 可正可负/零；由各边开关决定加速度方向
 * 4. 兜底：速度低于阈值且无重力时自动静止，省电
 */
class PetPhysics {

    // 位置（窗口左上角坐标，像素）——唯一位置真值，移动/速度/拖拽/抛掷直接作用此处，与锚点解耦。
    // 脚锚点(屏幕坐标) = (x + anchorOffX, y + anchorOffY)，仅在需要“脚语义”(贴边/吸附落点)时由 anchorOff 派生。
    var x = 0f
    var y = 0f

    // 脚锚点相对窗口左上角的像素偏移（= getBaseAnchorScaled），由 PetView 每帧写入。
    // 旋转/缩放/帧切换只改此偏移，不再牵动 x/y → 锚点变化不会触发窗口跳变。
    var anchorOffX = 0f
    var anchorOffY = 0f

    // 锚点固定(foot-fixed) 的【幂等基准】：记录“上一帧已应用平移的锚点偏移”与对应的 scaleFactor（<0 未初始化）。
    // 平移逻辑已移入 PetView.tick/syncCurrentBitmap（主线程、每帧渲染前必执行），用本基准作“旧锚点”：
    // 仅当 scaleFactor 真变时平移一次，使“尺寸刷新”与“位置修正”在同一帧原子完成，彻底消除投喂/直接输入等大跨度
    // 瞬变时“先变尺寸、左上角固定、过一瞬才跳到锚点对齐”的一帧错位（根因是旧实现把平移放在随配置异步触发的
    // recalcBoundsFor，后台协程写 scaleFactor 与主线程读 physics.x 存在非 volatile 可见性一帧滞后）。
    // 两次缩放间发生旋转时，基准每帧同步为当前旋转锚点，故缩放时旧基准与实时锚点同旋转、脚不跳位。
    var lastFixAnchorX = 0f
    var lastFixAnchorY = 0f
    var lastFixScale = -1f   // 最近一次已应用 foot-fixed 平移的 scale；<0 表示尚未初始化

    // 速度（像素/秒）
    var vx = 0f
    var vy = 0f

    // sub-pixel 余数累积（对应 PC QPointF remainder）
    private var remX = 0f
    private var remY = 0f

    // 屏幕边界（宠物可活动区域，需扣除宠物尺寸）
    var minX = 0f
    var maxX = 0f
    var minY = 0f
    var maxY = 0f

    // 纯偏移吸附边界线（与设置页“边界偏移线/吸附环带”同一几何，不含图片 inset、不随脚朝向变化）：
    // = 屏幕可见区 ± 用户边界偏移。吸附判定/落点钳制一律用这组，避免误用带 inset 的活动边界 minY/maxY。
    var snapMinX = 0f
    var snapMaxX = 0f
    var snapMinY = 0f
    var snapMaxY = 0f

    // 配置（实时更新）
    var gravity = 1500f
    var reboundRatio = 0.7f
    // 四边重力方向（控制加速度）
    var gravityTop = false
    var gravityBottom = true
    var gravityLeft = false
    var gravityRight = false
    // 四边弹力开关（独立可选：该边是否反弹，默认全开，对应 PC 默认四边都反弹）
    var reboundTop = true
    var reboundBottom = true
    var reboundLeft = true
    var reboundRight = true
    // 吸附边缘总开关：false 时完全不吸附（四边子开关失效）。
    var snapEnabled = true
    // 反弹总开关：false 时所有碰撞反弹失效（撞墙直接停靠、不反弹、不触发振动回调），
    // 四边反弹子开关与反弹系数均不再生效。与 snapEnabled 同模式。
    var reboundEnabled = true
    // 四边吸附开关（独立可选，默认全开）：拖拽松手低速近边时吸附成探头常驻态。
    var snapTop = true
    var snapBottom = true
    var snapLeft = true
    var snapRight = true
    // 重力/抛掷总开关：false 时拖动即静止，不施加重力、拖动不赋予抛掷速度（类似 PC）
    var gravityEnabled = true
    // 抛掷开关/系数：抛掷开关关闭后拖动松手不抛出（原地落下），系数放大抛掷初速（1=原速度）
    var throwEnabled = true
    var throwCoeff = 1f
    // 体感重力（倾斜重力）开关：开启后由 tiltGx/tiltGy 向量决定重力方向（来自手机重力传感器），
    // 覆盖四边定向重力（四边开关应置 false），但保留边界反弹。
    var tiltGravity = false
    // 体感重力向量（像素/秒^2，屏幕坐标系）：由 PetService 根据传感器实时写入。
    // 倾斜越大分量越大，等效重力越大；平放时两分量趋近 0。
    // @Volatile：传感器回调在后台线程写入，主线程 step() 读取，需保证跨线程可见性，
    // 否则主线程可能一直读到旧值 0，导致“体感无效”。
    @Volatile var tiltGx = 0f
    @Volatile var tiltGy = 0f
    // 速度上限（像素/秒），0-5000，防止高速穿透/抖动
    var maxSpeed = 2000f

    // 静止阈值：速度低于此值且无重力作用时停止
    private val stillThreshold = 10f

    var isDragging = false

    // 吸附态标志：拖拽松手低速近边时置位，宠物贴附该边、进入常驻探头(SNAP_HEAD)、忽略重力，
    // 直到用户再次拖拽拉出（isDragging 置位时清除）。
    var isSnapped = false
    // 被贴附的边（0=底,1=左,2=右,3=顶），脚朝向该边；吸附时同步给 gravityDir。
    var snapSide = 0

    // 攀爬态标志：独立于重力开关/体感的“贴墙走壁”专用状态位（替代旧版“篡改重力方向”做法）。
    // 攀爬时仅锁定“垂直接触轴”并完全不受重力，脚朝向由 climbSide 经 PetView.climbRotation 表达，
    // 从而攀岩与四边/体感重力互不干扰（拖拽松手不再残留错误重力方向）。
    var isClimbing = false
    // 所攀的墙（0=底,1=左,2=右,3=顶），与 snapSide 同义但用于攀爬态。
    var climbSide = 0
    // 好动飞跃飞行中：物理跳过重力（替代旧版“四边重力全关”做法，不再污染重力开关状态）。
    var inLeapFlight = false

    // 吸附判定阈值（物理坐标，像素）：松手速度低于 snapSpeed 且距离某边 < snapDist 即吸附。
    private val snapSpeed = 800f
    // 吸附距离阈值（像素）：由 PetConfig.snapThreshold 注入，默认 100。
    var snapDist = 100f

    // 抛掷进行中标志：对应 PC 的 is_throw_follow，期间动画恒定 ROLL，不按速度切
    var isThrowing = false

    // 边界反弹抑制（好动飞跃飞行中置 true）：飞行期间贴边不反弹、直接钳到边界并清零该轴速度，
    // 避免飞跃被 stepAxis 弹回导致无法落点。
    var suppressBounce = false

    // 抛掷结束回调（对应 PC throw_end -> change_next_mode -> 掉落-跟随）
    var onThrowEnd: (() -> Unit)? = null

    // 边界反弹回调（side：反弹边；speed：反弹前速度绝对值，用于决定振动强度）
    // side 常量：BOUNCE_LEFT/RIGHT/TOP/BOTTOM
    var onBounce: ((side: Int, speed: Float) -> Unit)? = null

    // 非反弹边“停靠”回调：宠物以【非反弹】方式停在某边（反弹总关/该边反弹关/非飞行）时上报，
    // 由上层决定落地行为（如开启攀岩则在落地点直接攀岩、或先判飞跃概率）。飞行(suppressBounce)不触发；
    // 抛掷/翻滚(ROLL)中撞到非反弹边也照常上报——上层 decideLanding 会先硬停 ROLL 再决策，否则 isThrowing
    // 在持续体感重力下永不结束、onThrowEnd 永不了触发，攀岩/飞跃永远进不去、且一直卡在 ROLL 动作。
    // side 采用 0底/1左/2右/3顶（与 landingWall/setClimb 一致）。
    var onNonReboundLand: ((side: Int) -> Unit)? = null
    // 非反弹边停靠去重：避免同一次停靠每帧重复回调。离开所有边后由 step 末尾复位。
    private var lastEdgeStopSide = -1

    // 上报非反弹边停靠（去重 + 排除飞行），供上层落地决策（攀岩/飞跃）。
    // 注意：抛掷/翻滚(ROLL)不在此屏蔽——由上层 decideLanding 硬停 ROLL 后处理，而非丢弃上报。
    private fun reportNonReboundLand(side: Int) {
        if (suppressBounce) return
        if (side == lastEdgeStopSide) return
        lastEdgeStopSide = side
        android.util.Log.d("CakePetDBG", "reportNonReboundLand side=$side isThrowing=$isThrowing tiltRolling=$tiltRolling lastEdgeStopSide(was set)=$side")
        onNonReboundLand?.invoke(side)
    }

    // 硬停翻滚（非反弹边停靠时由上层 decideLanding 调用）：终止 ROLL 生命周期，使上层落地决策
    // （攀岩/飞跃/换其他动作）能接管。区别于 step 自然结束（走 onThrowEnd→JUMP_DOWN），
    // 此处直接清状态、不播 JUMP_DOWN，由上层决定后续动作（主重力边除外，其保留滚动落地流程）。
    internal fun endTiltRoll() {
        isThrowing = false
        tiltRolling = false
        tiltRollCooldown = tiltRollCooldownMs   // 防立刻又进 ROLL
        vx = 0f; vy = 0f; remX = 0f; remY = 0f
    }

    // 体感翻滚阈值（像素/秒）：体感模式下合成速度超过此值即视为“滚得够快”，进入翻滚（ROLL）。
    // 默认 1500，可随手感调整。
    var tiltRollSpeed = 500f
    // 体感翻滚【退出】阈值（像素/秒）：低于此速度才允许真正退出翻滚（滞回）。
    // 配合 tiltRollSpeed 形成滞回区间，避免落地后重力残余速度在 500 附近反复进出翻滚。
    var tiltRollExitSpeed = 200f
    // 体感翻滚退出后的冷却时长（毫秒）：退出翻滚（落地播 JUMP_DOWN）后，在此期间即使合成速度
    // 再次超过 tiltRollSpeed 也不重新进入翻滚，避免“JUMP_DOWN ↔ ROLL”在持续变化重力下无限循环。
    var tiltRollCooldownMs = 600L
    // 体感翻滚状态变化回调：仅在【进入】翻滚（rolling=true）时触发，用于切到 ROLL 动画。
    // 退出翻滚不走此回调——复用抛掷的“落地自然结束”生命周期（step 检测到贴边静止后触发 onThrowEnd
    // 播放 JUMP_DOWN 再回常驻），避免速度回落时在抛物线顶点/半空硬切 SIT 的突兀感。
    var onTiltRoll: ((rolling: Boolean) -> Unit)? = null
    // 内部：记录当前是否处于体感翻滚态，避免每帧重复回调进入。
    private var tiltRolling = false
    // 主动进入翻滚：置抛掷态与翻滚态（复用抛掷生命周期）。供 PetView.enterTiltRoll 调用，
    // 封装对 private tiltRolling 的写访问。调用方需自行判断守卫（非拖拽、未处于抛掷）。
    internal fun beginTiltRoll() {
        isThrowing = true
        tiltRolling = true
        // 记录翻滚起始时间，用于最短存活时间守卫（见 step 退出块）：松手抛投（尤其近静止松手）
        // 速度极小，第一帧即 isLandedStill() 成立，原退出逻辑会立刻终止 ROLL 播 JUMP_DOWN，
        // 导致“松手不进 ROLL”。最短存活时间内即便落地静止也不退出，让 ROLL 动画至少播一段。
        tiltRollStart = System.currentTimeMillis()
    }
    // 内部：退出翻滚后的冷却剩余毫秒，>0 期间屏蔽重新进入。
    private var tiltRollCooldown = 0L
    // 内部：翻滚最短存活毫秒——进入翻滚后至少持续这么久才允许因“落地静止”退出，
    // 避免松手初速极小（近静止抛投）时 ROLL 被第一帧的 isLandedStill() 立刻掐掉。
    private val tiltRollMinMs = 350L
    private var tiltRollStart = 0L

    companion object {
        const val BOUNCE_LEFT = 0
        const val BOUNCE_RIGHT = 1
        const val BOUNCE_TOP = 2
        const val BOUNCE_BOTTOM = 3
    }

    /** 外部设置速度（抛掷），对应 PC 的 set_status(throw) */
    fun throwWith(vx: Float, vy: Float) {
        this.vx = vx
        this.vy = vy
        isThrowing = true
    }

    /**
     * 强制复位（召回）：清零所有运动状态并归位到指定锚点坐标。
     * 清除速度/加速度余数、抛掷与拖拽标志，使宠物立即静止，无残留动画惯性。
     * 调用方需保证 (x, y) 已在边界范围内（如底部中心）。
     */
    fun resetTo(x: Float, y: Float) {
        this.x = x
        this.y = y
        vx = 0f
        vy = 0f
        remX = 0f
        remY = 0f
        isThrowing = false
        isDragging = false
        isSnapped = false
        snapSide = 0
        isClimbing = false
        climbSide = 0
        inLeapFlight = false
    }

    /** 用户拖拽拉出时解除吸附态（由 setDragging/拖拽入口调用） */
    fun clearSnap() {
        isSnapped = false
    }

    /**
     * 吸附到指定边：置吸附态、清零速度、把位置 clamp 到该边、记录脚朝向(snapSide)。
     * side: 0=底,1=左,2=右,3=顶。
     */
    fun snapTo(side: Int) {
        isSnapped = true
        snapSide = side
        isThrowing = false
        vx = 0f
        vy = 0f
        // snapMin/Max 为“脚贴边”屏幕吸附线；窗口坐标 = 脚线 - 锚点偏移。
        // “基于锚点计算落点”：指定脚应到位置，反推窗口位置，移动与锚点解耦。
        val ox = anchorOffX
        val oy = anchorOffY
        when (side) {
            0 -> y = snapMaxY - oy   // 贴底
            3 -> y = snapMinY - oy   // 贴顶
            1 -> x = snapMinX - ox   // 贴左
            2 -> x = snapMaxX - ox   // 贴右
        }
    }

    /**
     * 在松手手指屏幕坐标 (fx, fy) 处，从「开启了吸附开关且手指落在 snapDist 内」的边里，
     * 选出手指到该边垂线距离最小的一条；无候选则 -1。
     * 注意：判定用【手指位置】而非脚锚点 (x/y)，与宠物尺寸/边框/朝向无关；
     * 选边用【最近距离】而非写死优先级，避免手指更近左边却被底边抢走。
     * 边沿用活动范围边界 (minY/maxY/minX/maxX)，与 snapTo 落点钳制同源、坐标一致。
     */
    fun nearestSnapSide(fx: Float, fy: Float): Int {
        // 先判吸附总开关（关则四边子开关失效）；各方向是否候选还需各自子开关。
        if (!snapEnabled) return -1
        var best = -1
        var bestD = Float.MAX_VALUE
        // 带符号距离：手指在偏移线【内侧】(朝屏心)=正，【外侧】(朝真实屏边)=负。
        // 外侧为负 → 必 ≤ snapDist(正) → 该方向开启即“必然吸附”候选；
        // 内侧为正 → 仅 d ≤ snapDist 才候选（需判断是否在吸附距离内）。
        // 统一取最小 d 即“最近边”，无需 abs、无需内外分支。
        // 注意：候选前提是【总开关开 + 该方向子开关开】，下方每个 if 已把关。
        if (snapTop) {
            val d = fy - snapMinY
            if (d <= snapDist && d < bestD) { bestD = d; best = 3 }
        }
        if (snapBottom) {
            val d = snapMaxY - fy
            if (d <= snapDist && d < bestD) { bestD = d; best = 0 }
        }
        if (snapLeft) {
            val d = fx - snapMinX
            if (d <= snapDist && d < bestD) { bestD = d; best = 1 }
        }
        if (snapRight) {
            val d = snapMaxX - fx
            if (d <= snapDist && d < bestD) { bestD = d; best = 2 }
        }
        return best
    }

    /** 松手是否满足吸附条件：速度低 + 某边开启且在吸附距离内 */
    fun shouldSnap(fx: Float, fy: Float): Boolean {
        val sp = kotlin.math.hypot(vx, vy)
        return sp < snapSpeed && nearestSnapSide(fx, fy) != -1
    }

    /** 是否已基本静止（用于决定是否继续动画/省电） */
    fun isSettled(): Boolean {
        // 已贴住某重力边且对应轴速度极小：视为静止（否则有重力时永远 false → 永不省电）。
        if (isLandedStill()) return true
        val anyGravity = gravity != 0f && (gravityTop || gravityBottom || gravityLeft || gravityRight)
        if (anyGravity) return false
        return abs(vx) < stillThreshold && abs(vy) < stillThreshold
    }

    /** 是否处于“已落地静止”状态：贴住某重力边且对应轴速度已极小，用于结束抛掷 */
    private fun isLandedStill(): Boolean {
        val stillVx = abs(vx) < stillThreshold
        val stillVy = abs(vy) < stillThreshold
        // 体感模式下四边定向重力保留原值但【不生效】（step 走 tiltGravity 分支），
        // 此处落地判定必须忽略四边，统一按“整体速度极小”处理，否则保留的四边勾选
        // 会导致贴边即误判落地、体感翻滚提前结束。
        if (tiltGravity) return stillVx && stillVy
        // 多重力下，落地/静止判定必须以【主重力】为准（垂直方向优先于侧向，对齐 calcGravityDir），
        // 次重力方向边单独贴住【不得】触发整体静止，否则“贴次重力侧墙但未到主重力边”时
        // 会被误判落地、抛掷提前结束、主重力失效（表现为“被次重力边错误变静止”）。
        val hasVertical = gravityBottom || gravityTop
        val hasSide = gravityLeft || gravityRight
        // 底边重力（主）：已贴底且竖直速度极小
        if (gravityBottom && y >= maxY - 0.5f && stillVy) return true
        // 顶边重力（主）：已贴顶且竖直速度极小
        if (gravityTop && y <= minY + 0.5f && stillVy) return true
        // 侧向重力仅在没有垂直重力时才作为落地判定（避免次重力边误判）；
        // 有垂直重力时侧向贴边不算落地，主重力继续主导。
        if (hasSide && !hasVertical) {
            if (gravityLeft && x <= minX + 0.5f && stillVx) return true
            if (gravityRight && x >= maxX - 0.5f && stillVx) return true
        }
        // 无重力：整体速度极小
        if (!hasVertical && !hasSide && stillVx && stillVy) return true
        return false
    }

    /**
     * 单帧推进（对应 PC cal_throw_rebound_offset）。
     * interval 单位秒。
     */
    fun step(interval: Float) {
        if (isDragging) return

        // 吸附态：贴附某边、忽略重力、保持静止。用户未拖拽时持续 clamp 到贴边、清零速度，
        // 不参与重力/抛掷/反弹演化（与“用户手动拉出才解除”的需求一致）。
        if (isSnapped) {
            vx = 0f
            vy = 0f
            when (snapSide) {
                0 -> y = maxY
                3 -> y = minY
                1 -> x = minX
                2 -> x = maxX
            }
            return
        }

        // 体感翻滚退出冷却倒计时（毫秒→秒）
        if (tiltRollCooldown > 0) {
            tiltRollCooldown -= (interval * 1000f).toLong()
            if (tiltRollCooldown < 0) tiltRollCooldown = 0
        }

        // 攀爬态：贴墙锁位 + 完全不受重力。仅锁“垂直接触轴”并清零其速度，
        // 平行轴速度由 walk 每帧写入，继续走下方 stepAxis 沿墙移动（与吸附同级的独立状态）。
        if (isClimbing) {
            when (climbSide) {
                0 -> { y = maxY; vy = 0f }
                3 -> { y = minY; vy = 0f }
                1 -> { x = minX; vx = 0f }
                2 -> { x = maxX; vx = 0f }
            }
        }

        // --- 重力加速度（四边重力 / 体感重力）---
        // gravityEnabled=false 时完全不施加重力（拖动即静止，类似 PC）
        // 攀爬态/飞跃飞行中跳过重力：攀爬由专属 climbSide 锁轴表达、不靠重力吸附；
        // 飞跃由 PetView 直接驱动位移，二者都不应受四边/体感重力干扰。
        if (gravityEnabled && !isClimbing && !inLeapFlight) {
            if (tiltGravity) {
                // 体感重力：重力方向由 tiltGx/tiltGy 向量（来自手机重力传感器）决定，连续无方向限制。
                // 倾斜越大分量越大，等效重力越大；平放时两分量趋近 0，宠物几乎不受力。
                // 贴边静止短路（按贴边方向细分）：
                // 仅当某轴“已贴住对应边”且 tiltG 的同向分量正把宠物【压向该边】时，才清零该轴速度——
                // 避免平放/微晃时持续注入的微小压边力造成站立呼吸式微抖（历史问题）。
                // 其余方向的分量【不归零】：如贴底时 tiltGx（水平）照常施加，使手机倾斜仍能把宠物
                // 横向推离/翻滚；贴左墙时 tiltGy（上下）照常，上倾仍能向上滑离。
                // 关键：先按当前贴边状态清零“压边”分量残速，再注入重力——否则先注入又被 stepAxis
                // 反弹分支反复清零，仍会残留 sub-pixel 抖动。
                // 注意：此处不短路“背离边”的分量（如贴底时 tiltGy<0 上倾），以保留“底部静止感知
                // 向上重力把宠物推离”的能力（同原设计意图）。
                // 贴边静止短路（按贴边方向细分，含速度门槛与抛掷守卫）：
                // 仅当某轴“已贴住对应边”且 tiltG 的同向分量正把宠物【压向该边】、
                // 且该轴速度极小（站立微抖噪声级）且【非抛掷态】时，才清零该轴速度——
                // 消除平放/微晃时持续注入的微小压边力造成的站立呼吸式微抖（历史问题）。
                // 门槛与守卫的必要性：
                //  - 速度门槛(stillThreshold)：只在“几乎静止”时清零；明显倾斜/重力积攒出的
                //    较大速度不触发，重力才能在数帧内积攒出推离/反弹/翻滚速度（否则反弹会消失）。
                //  - 抛掷守卫(!isThrowing)：抛掷/翻滚飞行中可能仍贴边一瞬，若清零会吃掉出手初速
                //    导致手感变差、ROLL 起始黏滞。站立微抖只发生在非抛掷静止态，符合诉求。
                // 其余方向分量【不归零】：贴底时 tiltGx（水平）照常施加，手机倾斜仍能把宠物
                // 横向推离/翻滚；贴左墙时 tiltGy（上下）照常，上倾仍能向上滑离。
                // 背离边分量不短路（贴底 tiltGy<0 上倾）：保留“底部静止感知向上重力推离”能力。
                if (!isThrowing) {
                    val atBottom = y >= maxY - 0.5f
                    val atTop = y <= minY + 0.5f
                    val atLeft = x <= minX + 0.5f
                    val atRight = x >= maxX - 0.5f
                    val stillVx2 = abs(vx) < stillThreshold
                    val stillVy2 = abs(vy) < stillThreshold
                    if (stillVy2 && ((atBottom && tiltGy > 0f) || (atTop && tiltGy < 0f))) vy = 0f
                    if (stillVx2 && ((atLeft && tiltGx < 0f) || (atRight && tiltGx > 0f))) vx = 0f
                }
                vx += tiltGx * interval
                vy += tiltGy * interval
                // 体感翻滚：合成速度超【进入】阈值才进入翻滚（带滞回：只有之前未滚动时才触发，
                // 防止阈值附近抖动反复进入）。进入时复用抛掷生命周期（置 isThrowing=true），
                // 后续恒定 ROLL 直到真正落地静止，由下方 isThrowing && isLandedStill 自然结束并播 JUMP_DOWN，
                // 不在速度回落时硬切，避免半空突兀。
                val sp = kotlin.math.sqrt(vx * vx + vy * vy)
                // 冷却期内（刚退出翻滚播 JUMP_DOWN）屏蔽重新进入，避免重力残余速度立刻又触发 ROLL。
                if (tiltRollCooldown <= 0 && !tiltRolling && sp > tiltRollSpeed) {
                    tiltRolling = true
                    isThrowing = true
                    onTiltRoll?.invoke(true)
                }
            } else {
                // 贴重力边“静止短路”：仅当速度【已离开该边或为零】且极小时才清零（视为停靠静止），
                // 跳过本帧重力施加，避免贴底每帧唤醒速度造成永久微抖。
                // 关键：若速度仍在【推向该边】(向外为负/正，取决于边)，说明是重力正把宠物压向墙、
                // 尚未真正反弹，此时【不清零】，交给下方 stepAxisX/Y 的反弹分支触发 onBounce 并反弹。
                // 这正是此前左/顶方向反弹振动失效的根因——旧逻辑只要贴边且速度极小就清零，
                // 而左/顶重力把宠物焊死在墙根、vx/vy 恒为 0，反弹分支永远进不去、onBounce 永不触发。
                val stillVx = abs(vx) < stillThreshold
                val stillVy = abs(vy) < stillThreshold
                // 底边：已贴底，且竖直速度≤0（已反弹向上或静止）且极小 -> 静止
                if (gravityBottom && y >= maxY - 0.5f && stillVy && vy <= 0f) {
                    vy = 0f
                } else if (gravityBottom) {
                    vy += gravity * interval   // 底边开启：向下加速
                }
                // 顶边：已贴顶，且竖直速度≥0（已反弹向下或静止）且极小 -> 静止
                if (gravityTop && y <= minY + 0.5f && stillVy && vy >= 0f) {
                    vy = 0f
                } else if (gravityTop) {
                    vy -= gravity * interval   // 顶边开启：向上加速（负方向）
                }
                // 右边：已贴右，且水平速度≤0（已反弹向左或静止）且极小 -> 静止
                if (gravityRight && x >= maxX - 0.5f && stillVx && vx <= 0f) {
                    vx = 0f
                } else if (gravityRight) {
                    vx += gravity * interval   // 右边开启：向右加速
                }
                // 左边：已贴左，且水平速度≥0（已反弹向右或静止）且极小 -> 静止
                if (gravityLeft && x <= minX + 0.5f && stillVx && vx >= 0f) {
                    vx = 0f
                } else if (gravityLeft) {
                    vx -= gravity * interval   // 左边开启：向左加速（负方向）
                }
                // 普通四边重力下的“空中速度达到阈值→翻滚”，与上方体感(tiltGravity)对称：
                // 仅在【未贴任何重力边（贴边含沿边行走）】且合成速度超进入阈值时切 ROLL，
                // 复用抛掷生命周期 + 滞回(tiltRollExitSpeed) + 冷却(tiltRollCooldown)守卫。
                // 用【位置】判定贴边（不依赖速度方向），避免沿边高速行走被误判为翻滚。
                val groundedNow = (gravityBottom && y >= maxY - 0.5f) ||
                                  (gravityTop && y <= minY + 0.5f) ||
                                  (gravityRight && x >= maxX - 0.5f) ||
                                  (gravityLeft && x <= minX + 0.5f)
                if (!groundedNow && tiltRollCooldown <= 0 && !tiltRolling && !isThrowing) {
                    val spAir = kotlin.math.sqrt(vx * vx + vy * vy)
                    if (spAir > tiltRollSpeed) {
                        tiltRolling = true
                        isThrowing = true
                        onTiltRoll?.invoke(true)
                    }
                }
            }
        }

        // --- 反弹系数>1 时的“单向速度抑制”（分级阈值） ---
        // 当反弹系数大于 1（加速反弹，会越弹越快）时，若某轴速度占总速度比例越高，
        // 该占优轴的反弹系数被压得越低（防止其被无限放大、最终变成单向飞出），
        // 另一轴不受影响（仍用完整 reboundRatio）。两轴都不超 70% 则都不修正。
        // 分级：>95% → *0.5；>90% → *0.6；>80% → *0.7；>70% → *0.8。
        val spForRatio = kotlin.math.sqrt(vx * vx + vy * vy)
        val xRatio = if (spForRatio > 0f) abs(vx) / spForRatio else 0f
        val yRatio = if (spForRatio > 0f) abs(vy) / spForRatio else 0f
        fun axisScale(ratio: Float): Float {
            if (reboundRatio <= 1f) return 1f
            return when {
                // 95% 与 90% 两档额外封顶，避免 reboundRatio 极大时仍被放得过高
                ratio > 0.95f -> minOf(reboundRatio * 0.5f, 0.75f)
                ratio > 0.90f -> minOf(reboundRatio * 0.6f, 0.9f)
                ratio > 0.80f -> reboundRatio * 0.7f
                ratio > 0.70f -> reboundRatio * 0.8f
                else -> 1f
            }
        }
        val xScale = axisScale(xRatio)
        val yScale = axisScale(yRatio)

        // --- X 轴移动 + 左右边界反弹 ---
        stepAxisX(interval, xScale)
        // --- Y 轴移动 + 上下边界反弹 ---
        stepAxisY(interval, yScale)

        // --- 速度上限钳制（0-5000）---
        val sp = kotlin.math.sqrt(vx * vx + vy * vy)
        if (sp > maxSpeed && sp > 0f) {
            val k = maxSpeed / sp
            vx *= k
            vy *= k
        }

        // 兜底：各轴独立——若该轴【没有】重力，且速度极低，则清零残速，
        // 避免无重力轴残留微小速度造成缓慢漂移（原逻辑用四轴 OR，只要任一轴有重力
        // 就整体不清零，导致默认 gravityBottom=true 时水平残速永不清除 → 贴底微动）。
        // 有重力的轴由上面的边界“贴边低速清零”处理，这里不再重复。
        // 注意：【抛掷进行中】(isThrowing) 不执行此兜底——否则拖拽滚动的松手瞬时
        // 横向速度若 < stillThreshold(30) 会被立刻清零，导致“只上下动、横向永远为 0”
        // （vy 因 gravityBottom 开启不受此行影响，故表现为 vx 恒 0 而 vy 正常）。
        // 抛掷结束由下方 isLandedStill() 统一清零残速。
        if (!isThrowing) {
            // 体感模式下 tiltGx/tiltGy 即“重力”，不应按四边重力缺失来清零，
            // 否则倾斜加速度每帧刚积累就被清掉，体感几乎无效。
            if (!tiltGravity && !gravityLeft && !gravityRight && abs(vx) < stillThreshold) vx = 0f
            if (!tiltGravity && !gravityTop && !gravityBottom && abs(vy) < stillThreshold) vy = 0f
        }

        // 多重力夹角吸收：当宠物【同时】贴住两个对应重力边（如左+下、上+右 的夹角），
        // 若仍按各轴独立反弹，会出现“一轴反弹离开→被重力拉回→再反弹”的死循环抖动（反复撞角）。
        // 此处双轴速度直接归零，使其真正静止在角点（仅剩另一未贴边轴仍可活动）；
        // 单轴贴墙（如仅左重力贴左墙）不受影响，仍走上方反弹分支保留撞击手感。
        // 注意：此吸收与 isLandedStill 的夹角判定一致——双轴都贴边即视为落地静止。
        val stuckX = (gravityLeft && x <= minX + 0.5f) || (gravityRight && x >= maxX - 0.5f)
        val stuckY = (gravityTop && y <= minY + 0.5f) || (gravityBottom && y >= maxY - 0.5f)
        if (stuckX && stuckY) {
            vx = 0f
            vy = 0f
            remX = 0f
            remY = 0f
        }

        // 抛掷终止：对应 PC throw_func 触底且速度归零 -> throw_end()
        if (isThrowing && isLandedStill()) {
            // 翻滚最短存活时间守卫：进入翻滚后前 tiltRollMinMs 内，即便已落地静止
            // （如近静止松手抛投、速度极小）也不退出翻滚，让 ROLL 动画至少播一段，
            // 否则会被第一帧的 isLandedStill() 直接掐掉、改播 JUMP_DOWN（松手不进 ROLL 的回归）。
            // 仅对 tiltRolling 生效；普通抛掷（非翻滚）不受此门影响，落地即正常收尾。
            if (tiltRolling && System.currentTimeMillis() - tiltRollStart < tiltRollMinMs) {
                // 最短存活期内：保持翻滚态，不清零速度（速度本就极小），交给下方贴边/重力演化。
                // 同时维持 isThrowing=true 让动画模式停在 ROLL。
            } else {
            isThrowing = false
            // 体感翻滚借用了 isThrowing 生命周期，落地结束同步清掉翻滚态，使下次超阈值可重新进入。
            // 滞回退出判据：统一按【主重力方向速度分量】判定（与 effectiveMainGravityDir 同口径）。
            // 体感重力开启时四边开关仅被 tiltGravity 分支优先接管，四个 b/l/r/t 标志仍保留用户原值，
            // 故体感同样有"主重力方向"，应与四边/多重力走同一逻辑（底/顶看 |vy|、左/右看 |vx|），
            // 而非沿用合成速度。若不区分、统一用总速度，则底边重力下水平残速 vx(地面无摩擦长期保留)
            // 与已衰减到 0 的竖直 vy 合成后总速度≈vx 仍 > 退出阈值，翻滚被无限续命 →
            // 重力方向已 0 速度还在地面一股脑平滚不落地(与"反弹衰减到重力方向≈0 就直接落地"预期相悖)。
            // 仅当四边标志全关(无主重力)时退回合成速度判据。
            val exitSp = if (!gravityBottom && !gravityTop && !gravityLeft && !gravityRight) {
                kotlin.math.sqrt(vx * vx + vy * vy)
            } else if (gravityBottom || gravityTop) {
                kotlin.math.abs(vy)
            } else {
                kotlin.math.abs(vx)
            }
            if (tiltRolling && exitSp > tiltRollExitSpeed) {
                // 仍在滞回区间内(重力方向分量仍超阈值)：不退出翻滚、不清零速度，
                // 交给下方贴边/重力继续演化，等速度自然衰减到退出阈值以下再走真正的落地收尾。
                isThrowing = true
            } else {
                tiltRolling = false
                // 退出翻滚后开启冷却，期间屏蔽重新进入（见体感翻滚进入逻辑），
                // 让 JUMP_DOWN 能安稳播完而不被持续变化的重力立刻打断成 ROLL。
                tiltRollCooldown = tiltRollCooldownMs
                // 落地后清除残余速度，避免“落地后左右/上下滑动”造成状态来回横跳
                vx = 0f
                vy = 0f
                remX = 0f
                remY = 0f
                onThrowEnd?.invoke()
            }
            }
        }
        // 离开所有边且未攀爬/未吸附/未飞行：复位非反弹边停靠去重标记，使下次停靠重新触发落地决策。
        if (!isClimbing && !isSnapped && !inLeapFlight &&
            x > minX + 0.5f && x < maxX - 0.5f && y > minY + 0.5f && y < maxY - 0.5f) {
            lastEdgeStopSide = -1
        }
    }

    private fun stepAxisX(interval: Float, reboundScale: Float) {
        if (vx == 0f && remX == 0f) return
        // 本帧位移（含上一帧 sub-pixel 余数），位置保持浮点连续，
        // 仅在最终写入浮窗时取整，避免低速时“每几帧才跳 1px”的台阶感。
        val move = vx * interval + remX
        remX = 0f
        var next = x + move

        if (vx > 0) {
            // 向右
            if (next >= maxX) {
                // 攀岩走壁态：撞界【绝不反弹】——语义上应走过相邻墙(setClimb)，由 PetView.tick 的 blocked 分支负责；
                // 若在此先反转速度，tick 就再也判不出 blocked → 上墙失败，且 onBounce 会误报振动。
                if (reboundEnabled && reboundRight && !suppressBounce && !isClimbing) {
                    x = maxX
                    // 右边无重力且速度极小：停靠静止，不再反弹出微小反向速度（消微抖）。
                    // 注意：有重力时始终反弹并回调，否则左/顶方向（重力推向墙）会被
                    // step 顶部的“贴边静止短路”永久清零 vx/vy，导致 onBounce 永不触发、
                    // 对应方向反弹振动完全失效。
                    if (!gravityRight && vx < stillThreshold) {
                        remX = 0f
                        vx = 0f
                    } else {
                        remX = -(next - maxX)
                        onBounce?.invoke(BOUNCE_RIGHT, vx)
                        vx = -vx * reboundRatio * reboundScale
                    }
                } else {
                    // 反弹总开关关闭 / 右边无引力 / 飞行抑制反弹：停靠该边，水平速度清零（不反弹、不振动）
                    x = maxX
                    remX = 0f
                    vx = 0f
                    reportNonReboundLand(2)   // 右(side=2，与 landingWall/setClimb 一致)
                }
            } else {
                x = next
            }
        } else if (vx < 0) {
            // 向左
            if (next <= minX) {
                // 攀岩走壁态：撞界不反弹，改为停靠+交由 tick 的 setClimb 上墙（同上，见 BOUNCE_RIGHT 注释）。
                if (reboundEnabled && reboundLeft && !suppressBounce && !isClimbing) {
                    x = minX
                    if (!gravityLeft && abs(vx) < stillThreshold) {
                        remX = 0f
                        vx = 0f
                    } else {
                        remX = -(next - minX)
                        onBounce?.invoke(BOUNCE_LEFT, vx)
                        vx = -vx * reboundRatio * reboundScale
                    }
                } else {
                    x = minX
                    remX = 0f
                    vx = 0f
                    reportNonReboundLand(1)   // 左(side=1)
                }
            } else {
                x = next
            }
        }
        // 夹紧边界（安全）
        if (x < minX) { x = minX; remX = 0f }
        if (x > maxX) { x = maxX; remX = 0f }
    }

    private fun stepAxisY(interval: Float, reboundScale: Float) {
        if (vy == 0f && remY == 0f) return
        val move = vy * interval + remY
        remY = 0f
        var next = y + move

        if (vy > 0) {
            // 向下
            if (next >= maxY) {
                // 攀岩走壁态：撞界【绝不反弹】——应走过相邻墙(setClimb)，由 PetView.tick 统一处理，
                // 否则宠物被弹离边界后 tick 判不出 blocked → 上墙失败且误报振动（见 stepAxisX 说明）。
                if (reboundEnabled && reboundBottom && !suppressBounce && !isClimbing) {
                    y = maxY
                    // 底边无重力且速度极小：停靠静止，不再反弹出微小反向速度（消微抖）。
                    // 有重力时始终反弹并回调（避免左/顶方向反弹振动失效，见 stepAxisX 说明）。
                    if (!gravityBottom && vy < stillThreshold) {
                        remY = 0f
                        vy = 0f
                    } else {
                        remY = -(next - maxY)
                        onBounce?.invoke(BOUNCE_BOTTOM, vy)
                        vy = -vy * reboundRatio * reboundScale
                    }
                } else {
                    // 反弹总开关关闭 / 底边无引力 / 飞行抑制反弹：停靠该边，竖直速度清零（不反弹、不振动）
                    y = maxY
                    remY = 0f
                    vy = 0f
                    reportNonReboundLand(0)   // 底(side=0)
                }
            } else {
                y = next
            }
        } else if (vy < 0) {
            // 向上
            if (next <= minY) {
                // 攀岩走壁态：撞界不反弹，改为停靠+交由 tick 的 setClimb 上墙（同上）。
                if (reboundEnabled && reboundTop && !suppressBounce && !isClimbing) {
                    y = minY
                    if (!gravityTop && abs(vy) < stillThreshold) {
                        remY = 0f
                        vy = 0f
                    } else {
                        remY = -(next - minY)
                        onBounce?.invoke(BOUNCE_TOP, vy)
                        vy = -vy * reboundRatio * reboundScale
                    }
                } else {
                    y = minY
                    remY = 0f
                    vy = 0f
                    reportNonReboundLand(3)   // 顶(side=3)
                }
            } else {
                y = next
            }
        }
        if (y < minY) { y = minY; remY = 0f }
        if (y > maxY) { y = maxY; remY = 0f }
    }

    fun setBounds(minX: Float, minY: Float, maxX: Float, maxY: Float) {
        this.minX = minX
        this.minY = minY
        this.maxX = maxX
        this.maxY = maxY
        // 夹紧当前位置
        clampToBounds()
    }

    /** 设置纯偏移吸附边界线（与边界偏移线/吸附环带同几何）。 */
    fun setSnapBounds(sMinX: Float, sMinY: Float, sMaxX: Float, sMaxY: Float) {
        snapMinX = sMinX
        snapMinY = sMinY
        snapMaxX = sMaxX
        snapMaxY = sMaxY
    }

    /** 把当前锚点坐标夹到活动范围（图片贴边范围）。供帧位移/拖拽后调用。 */
    fun clampToBounds() {
        if (x < minX) x = minX
        if (x > maxX) x = maxX
        if (y < minY) y = minY
        if (y > maxY) y = maxY
    }
}
