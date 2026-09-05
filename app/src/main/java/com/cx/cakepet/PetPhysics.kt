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

    // 位置（左上角坐标，像素）
    var x = 0f
    var y = 0f

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
    private val stillThreshold = 30f

    var isDragging = false

    // 吸附态标志：拖拽松手低速近边时置位，宠物贴附该边、进入常驻探头(SNAP_HEAD)、忽略重力，
    // 直到用户再次拖拽拉出（isDragging 置位时清除）。
    var isSnapped = false
    // 被贴附的边（0=底,1=左,2=右,3=顶），脚朝向该边；吸附时同步给 gravityDir。
    var snapSide = 0

    // 吸附判定阈值（物理坐标，像素）：松手速度低于 snapSpeed 且距离某边 < snapDist 即吸附。
    private val snapSpeed = 800f
    // 吸附距离阈值（像素）：由 PetConfig.snapThreshold 注入，默认 100。
    var snapDist = 100f

    // 抛掷进行中标志：对应 PC 的 is_throw_follow，期间动画恒定 ROLL，不按速度切
    var isThrowing = false

    // 抛掷结束回调（对应 PC throw_end -> change_next_mode -> 掉落-跟随）
    var onThrowEnd: (() -> Unit)? = null

    // 边界反弹回调（side：反弹边；speed：反弹前速度绝对值，用于决定振动强度）
    // side 常量：BOUNCE_LEFT/RIGHT/TOP/BOTTOM
    var onBounce: ((side: Int, speed: Float) -> Unit)? = null

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
        when (side) {
            0 -> y = maxY      // 贴底
            3 -> y = minY      // 贴顶
            1 -> x = minX      // 贴左
            2 -> x = maxX      // 贴右
        }
    }

    /**
     * 返回当前最近且开启了吸附开关的边（在 snapDist 范围内），无则 -1。
     * 顺序：底>顶>左>右（优先底边，符合“脚朝下的默认落地”直觉）。
     */
    fun nearestSnapSide(): Int {
        if (!snapEnabled) return -1
        if (snapBottom && y >= maxY - snapDist) return 0
        if (snapTop && y <= minY + snapDist) return 3
        if (snapLeft && x <= minX + snapDist) return 1
        if (snapRight && x >= maxX - snapDist) return 2
        return -1
    }

    /** 松手是否满足吸附条件：速度低 + 某边开启且在吸附距离内 */
    fun shouldSnap(): Boolean {
        val sp = kotlin.math.hypot(vx, vy)
        return sp < snapSpeed && nearestSnapSide() != -1
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

        // --- 重力加速度（四边重力 / 体感重力）---
        // gravityEnabled=false 时完全不施加重力（拖动即静止，类似 PC）
        if (gravityEnabled) {
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
            // 滞回：仅当合成速度已低于退出阈值才真正退出翻滚；否则保留翻滚态，
            // 避免落地瞬间重力残余速度（仍在 200~500 区间）把宠物立刻又推回 ROLL。
            val spNow = kotlin.math.sqrt(vx * vx + vy * vy)
            if (tiltRolling && spNow > tiltRollExitSpeed) {
                // 仍在滞回区间内：不退出翻滚、不清零速度，交给下方贴边/重力继续演化，
                // 等速度自然衰减到退出阈值以下再走真正的落地收尾。
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
                if (reboundEnabled && reboundRight) {
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
                    // 反弹总开关关闭或右边无引力：停靠该边，水平速度清零（不反弹、不振动）
                    x = maxX
                    remX = 0f
                    vx = 0f
                }
            } else {
                x = next
            }
        } else if (vx < 0) {
            // 向左
            if (next <= minX) {
                if (reboundEnabled && reboundLeft) {
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
                if (reboundEnabled && reboundBottom) {
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
                    // 反弹总开关关闭或底边无引力：停靠该边，竖直速度清零（不反弹、不振动）
                    y = maxY
                    remY = 0f
                    vy = 0f
                }
            } else {
                y = next
            }
        } else if (vy < 0) {
            // 向上
            if (next <= minY) {
                if (reboundEnabled && reboundTop) {
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

    /** 把当前锚点坐标夹到活动范围（图片贴边范围）。供帧位移/拖拽后调用。 */
    fun clampToBounds() {
        if (x < minX) x = minX
        if (x > maxX) x = maxX
        if (y < minY) y = minY
        if (y > maxY) y = maxY
    }
}
