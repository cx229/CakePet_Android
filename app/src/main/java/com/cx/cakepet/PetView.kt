package com.cx.cakepet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.os.SystemClock
import android.os.Vibrator
import android.os.VibrationEffect
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import kotlin.math.abs
import kotlin.math.max

/**
 * 宠物浮窗视图：绘制当前帧、处理触摸拖动、长按弹出菜单。
 * 对应 PC 端 FollowAndDragWidget（拖拽 + 触摸交互）。
 */
class PetView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    val physics = PetPhysics()
    private val imageManager = ImageModeManager(context)
    private var currentBitmap: Bitmap? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // 触摸拖动状态
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var dragging = false
    private var movedDuringPress = false
    // 长按手势状态：
    // - pressing：手指按下中（尚未判定为拖拽或长按）
    // - longPressTriggered：已达长按阈值且未明显移动 -> 候选打开菜单（已振动）
    // - longPressConsumed：本次按下已处理过长按（防重复）
    private var pressing = false
    private var longPressTriggered = false
    private var longPressConsumed = false
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

    // 反弹振动开关（由设置页控制）：开启后边界反弹触发轻微振动，强度随反弹速度变化。
    // 注：Android 标准 Vibrator 不支持方向性振动（硬件无多轴执行器），
    // 故“方位”无法影响实际振动方向，仅以振幅强度近似反弹力度。
    var bounceVibrateEnabled = false

    // 反弹振动最小速度阈值（px/s）：低于此值的贴边微抖不触发振动，避免“已停但持续振”
    private var bounceMinSpeed = 100f

    // 抓取偏移：按下时手指与宠物中心的差值，拖动时保持，避免跳动
    private var grabOffsetX = 0f
    private var grabOffsetY = 0f

    // 抛掷速度计算
    private var lastMoveX = 0f
    private var lastMoveY = 0f
    private var lastMoveTime = 0L

    // 回调
    var onLongPress: (() -> Unit)? = null
    var onDoubleTap: (() -> Unit)? = null
    var onPositionChanged: ((x: Float, y: Float) -> Unit)? = null
    var onDragStateChanged: ((dragging: Boolean) -> Unit)? = null
    // 落地（抛掷/拖拽结束触底，进入 JUMP_DOWN 动画那一刻）
    var onLand: (() -> Unit)? = null
    // 尺寸（缩放/帧）变化后通知外部重新计算边界
    var onSizeChanged: (() -> Unit)? = null
    // 输入法（软键盘）显隐：回调当前键盘高度（已扣除导航栏），供外部抬高宠物地面避免遮挡
    var onImeInsetChanged: ((height: Int) -> Unit)? = null
    // 调试：是否叠加红色矩形（大小与当前显示一致）
    var showRect = false

    // 行走朝向镜像（水平翻转）：朝右走时 true（对齐 PC transform_flag）。
    // 公开：供 PetService 在一次性位移模式（如扒鱼）读取当前面朝方向以设位移初速度。
    var flipX = false

    /**
     * 重力朝向（脚朝向）：0=下(默认) 1=左 2=右 3=上。
     * 非体感模式下由 PetService 根据四边重力开关计算并同步；体感模式保持为下(0)。
     * 渲染时按此朝向绕窗口中心旋转画布，使宠物“脚朝重力方向”站立（含 sit 等静态动作）。
     */
    var gravityDir = 0
        set(value) {
            field = value
            gravityRotation = when (value) {
                1 -> 90f    // 左：脚朝左（屏幕 -x）。Android rotate(90) 顺时针使原下方(+y)指向 -x
                2 -> 270f   // 右：脚朝右（+x）
                3 -> 180f   // 上：脚朝上（-y）
                else -> 0f  // 下：正常
            }
        }
    // 当前渲染旋转角（度，顺时针），由 gravityDir 推导。
    private var gravityRotation = 0f

    // 吸附态专属旋转角（度，顺时针）：仅 SNAP_HEAD 变体叠加的“脚朝向贴附边”旋转，
    // 不修改全局 gravityDir/gravityRotation，退出吸附即归零，不影响其他状态方向。
    private var snapRotation = 0f

    // 吸附态专属镜像（水平翻转）：吸附探头时左右/上下随机朝向（不修改全局 flipX）。
    // 仅吸附态渲染时应用，退出吸附即不生效，零状态残留、不污染 walk/扒鱼/静置等朝向逻辑。
    private var snapFlipX = false

    // 提起(LIFT_UP)系列上次应用值（-1=未设/慢速轻晃链），用于守护：系列未变则不重调 setLiftSeries，
    // 避免每帧把 frameIndex 重置回起点导致动画卡在第一帧。
    private var lastLiftSeries = -1

    // 方向(0下/1左/2右/3上) -> 渲染旋转角（度，顺时针），与 gravityDir setter 映射一致。
    private fun dirToRotation(dir: Int): Float = when (dir) {
        1 -> 90f
        2 -> 270f
        3 -> 180f
        else -> 0f
    }

    /**
     * 把动画帧的【资源本地】位移 (lx, ly) 映射到屏幕位移 (sx, sy)。
     * 资源本身朝右为 +x（前进）、向下为 +y；重力朝向通过绕窗口中心旋转画布表达，
     * 故位移必须同步旋转，否则出现“图朝左走但位朝右”（顶部重力 rotate180）等错位。
     * 约定与 onDraw 的 canvas.rotate(gravityRotation) 完全一致（顺时针）：
     *   gravityDir=0(下,0°)   -> (lx, ly)
     *   gravityDir=1(左,90°)  -> (-ly, lx)   本地 +x(前进) 旋转后指向屏幕 -y（向上沿墙）
     *   gravityDir=2(右,270°) -> (ly, -lx)   本地 +x 旋转后指向屏幕 +y（向下沿墙）
     *   gravityDir=3(上,180°) -> (-lx, -ly)  本地 +x 旋转后指向屏幕 -x（向左）
     * 调用方负责把 flipX 镜像预算进 lx（镜像即 lx 取反），本函数只做旋转。
     */
    private fun mapLocalToScreen(lx: Float, ly: Float): Pair<Float, Float> {
        return when (gravityDir) {
            1 -> -ly to lx
            2 -> ly to -lx
            3 -> -lx to -ly
            else -> lx to ly
        }
    }

    /**
     * 把「窗口内本地锚点偏移」(offX, offY) 按重力朝向绕窗口中心旋转，得到旋转后真实屏幕偏移。
     * 不处理 flipX（调用方传入的 off 已含镜像），只叠加重力旋转，与 onDraw(先 rotate 后 flip) 一致。
     * 默认 0° 时原样返回（避免无谓三角函数）。
     */
    private fun rotateOffset(offX: Float, offY: Float, w: Int, h: Int): Pair<Float, Float> {
        if (gravityRotation == 0f) return offX to offY
        val cx = w / 2f
        val cy = h / 2f
        val rx = offX - cx
        val ry = offY - cy
        val rad = Math.toRadians(gravityRotation.toDouble())
        val cos = kotlin.math.cos(rad)
        val sin = kotlin.math.sin(rad)
        val nx = rx * cos - ry * sin
        val ny = rx * sin + ry * cos
        return (cx + nx.toFloat()) to (cy + ny.toFloat())
    }

    private var scaleFactor = 1.0f
    // 边界计算用的固定基准：取首次初始化时的静止帧尺寸/锚点（与动画帧无关），
    // 保证边界不随 Roll/Walk 每帧尺寸变化而抖动，位置不被反复 clamp。
    private var baseFrameW = 0
    private var baseFrameH = 0
    private var baseAnchorX = 0
    private var baseAnchorY = 0
    // 记录上一帧位图尺寸，用于检测帧大小变化（不同动画帧尺寸不同）
    private var lastFrameW = 0
    private var lastFrameH = 0
    // 记录上一帧锚点，用于检测锚点变化（不同帧锚点不同，影响贴边范围）
    private var lastAnchorX = -1
    private var lastAnchorY = -1
    // 动画帧推进节流：让“位图切帧”与“物理位置刷新”解耦（对齐 error/show）。
    // 位置每帧高频更新，位图按固定间隔推进，避免每帧推进序列造成主线程过载。
    private var frameAccumulator = 0f
    private val FRAME_INTERVAL = 0.033f  // 33ms 切一帧，约 30fps 动画，顺滑且不卡（窗口尺寸已固定，无重排开销）

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
            // 仅当按住期间未发生明显移动（不是拖拽）才视为“有效长按候选”。
            // 此时先振动一次提醒，并标记 longPressTriggered；
            // 真正打开菜单延后到 ACTION_UP，若用户中途移开则取消本次打开。
            if (longPressConsumed) return
            if (!dragging && pressing) {
                longPressTriggered = true
                longPressConsumed = true
                vibrateOnce()
            }
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            onDoubleTap?.invoke()
            return true
        }
    })

    /** 长按达到阈值时的一次性振动提示 */
    private fun vibrateOnce() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(30)
        }
    }

    /**
     * 边界反弹振动：强度随反弹速度变化（速度越大，振幅越大、时长略增）。
     * Android 标准 Vibrator 不支持方向性振动，故方位不参与实际触感，仅以振幅近似力度。
     * 防护：低于最小速度阈值或距上次振动不足最小间隔时不触发，避免“已停但持续振”。
     */
    private fun vibrateBounce(speed: Float) {
        // 注：必须用 abs() 取速度绝对值！PetPhysics 传入的 speed 是该轴撞墙前的【带符号】速度：
        //  - 底/右反弹：vy>0 / vx>0（正方向撞墙）
        //  - 顶/左反弹：vy<0 / vx<0（负方向撞墙）
        // 旧代码用 coerceAtLeast(0f)，把左/顶的负速度直接夹成 0，结合 bounceMinSpeed 阈值
        // 导致“左/顶永远不振动”——这才是之前失效的真正根因（与速度大小无关，顶部重力 speed 同样很大）。
        // 修复后取绝对值，四方向速度均正确参与映射。
        val v = abs(speed)
        // 贴边微抖：速度过低不振动，否则静止贴边时每帧重力累积会反复触发“持续振”
        if (v < bounceMinSpeed) return
        // 振幅：10%（轻微）~ 60%（强烈）按速度线性映射（0 -> 10%, 5000 -> 60%）
        val ratio = (v / 5000f).coerceIn(0f, 1f)
        val amplitude = (0.1f + ratio * 0.5f) * 255f
        // 时长：15ms（轻）~ 45ms（重）
        val duration = (15 + ratio * 30).toLong()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val amp = amplitude.toInt().coerceIn(1, 255)
            vibrator?.vibrate(VibrationEffect.createOneShot(duration, amp))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(duration)
        }
    }

    init {
        currentBitmap = imageManager.currentBitmap()
        val (fw, fh) = imageManager.currentBitmapSize()
        val (fax, fay) = imageManager.currentAnchor()
        lastFrameW = fw
        lastFrameH = fh
        lastAnchorX = fax
        lastAnchorY = fay
        // 固定基准：取【全局最大帧尺寸】作为窗口基准，进程生命周期内恒定不变（仅随用户缩放 scale 变化）。
        // 这样任何模式（SIT/LIFT/ROLL/WALK…）切换都只是“换图 + 居中重绘”，绝不改系统窗口尺寸 → 不重排 → 流畅；
        // 且抛掷/滚动过程中绝不再重算边界、绝不 clamp 物理位置，避免初始帧被尺寸跳变篡改导致轨迹歪掉。
        // 用户选择大小（scale）时，getBaseBitmapSize 按 scaleFactor 缩放，窗口随之变化（一次，非每帧）。
        val (gw, gh) = imageManager.globalMaxSize()
        baseFrameW = gw
        baseFrameH = gh
        baseAnchorX = fax
        baseAnchorY = fay
        isClickable = true
        isFocusable = true
        // 输入法（软键盘）显隐监听：键盘弹出时把键盘高度回传给外部，用于抬高宠物地面。
        // 浮窗无输入焦点，view 级 ime inset 多数机型为 0，这里仅作为【补充来源】，
        // 主检测在 PetService 用全局 currentWindowMetrics 的 ime inset（跨机型可靠）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            setOnApplyWindowInsetsListener { _, insets ->
                val ime = insets.getInsets(WindowInsets.Type.ime()).bottom
                val nav = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                onImeInsetChanged?.invoke(max(0, ime - nav))
                insets
            }
        }
        // 抛掷结束（触底静止）→ 播放落地蹲下动画，再回常驻静坐（对齐 PC ThrowFallStandFollowMode）
        physics.onThrowEnd = {
            playOnce(ImageModeManager.JUMP_DOWN)
            onLand?.invoke()   // 真正落地（脚着地）瞬间，外部可显示落地碎碎念
        }
        // 边界反弹振动：仅当开关开启时触发，振幅随反弹速度强度变化（方位不支持，仅强度近似）。
        physics.onBounce = { _, speed ->
            if (bounceVibrateEnabled) vibrateBounce(speed)
        }
        // 体感翻滚：仅【进入】时切 ROLL（物理层已置 isThrowing 复用抛掷生命周期）。
        // 退出由物理落地自然触发 onThrowEnd(播放 JUMP_DOWN 再回常驻)，此处不处理，
        // 避免速度回落（如抛物线顶点）时半空硬切 SIT 的突兀感。
        physics.onTiltRoll = { r ->
            if (r) enterTiltRoll()
        }
        // 行走状态变化：
        //  - 进入 walk 类模式(speed>0)：用模式速度覆盖水平速度 + 镜像朝向；
        //  - 离开 walk 模式(speed==0)：【不要】在此清零 physics.vx！
        //    否则拖拽抛掷松手后 updateAnimByState 切到 ROLL/LIFT_UP（均非 walk）会触发此回调，
        //    把用户赋予的横向抛掷初速无条件清成 0（而 vy 不受影响）→ 表现为“横向永远不滚、只下落”。
        //    仅在宠物确实处于静止待机（既非拖拽也非抛掷）时才真正清零 vx，
        //    拖动/抛掷过程中的横向惯性交由物理引擎自行管理（落地后由 step 兜底清零）。
        imageManager.onWalkStateChanged = { speed, dir ->
            if (speed > 0f) {
                // 本地前进向量 (speed*dir, 0) 经重力旋转矩阵映射到屏幕速度；
                // 左右重力下前进落在屏幕 y 轴（沿墙垂直走），下/上重力落在 x 轴（水平游走），
                // 与图片渲染绕中心旋转一致，彻底告别“写死水平位移撞墙”。
                // 只写「前进轴」速度，另一轴【不】清零，留给物理重力每帧累加，
                // 否则进入 walk 瞬间把另一轴的重力分量抹成 0（walk 免疫重力）。
                val sv = mapLocalToScreen(speed * dir, 0f)
                if (gravityDir == 1 || gravityDir == 2) {
                    physics.vy = sv.second
                } else {
                    physics.vx = sv.first
                }
                // 图片资源朝右为基准，dir>0 时翻转显示朝右（镜像），与 onDraw 的 scale(-1) 一致。
                flipX = dir > 0
            } else if (!physics.isThrowing && !physics.isDragging) {
                physics.vx = 0f
                physics.vy = 0f
            }
        }
    }

    fun setConfig(scale: Float, gravity: Float, rebound: Float,
                  gTop: Boolean, gBottom: Boolean, gLeft: Boolean, gRight: Boolean,
                  rTop: Boolean, rBottom: Boolean, rLeft: Boolean, rRight: Boolean,
                  gravityEnabled: Boolean = true, maxSpeed: Float = 2000f) {
        physics.gravity = gravity
        physics.reboundRatio = rebound
        physics.gravityTop = gTop
        physics.gravityBottom = gBottom
        physics.gravityLeft = gLeft
        physics.gravityRight = gRight
        physics.reboundTop = rTop
        physics.reboundBottom = rBottom
        physics.reboundLeft = rLeft
        physics.reboundRight = rRight
        physics.gravityEnabled = gravityEnabled
        physics.maxSpeed = maxSpeed
    }

    fun setPetScale(scale: Float) {
        // 仅更新缩放系数；View 尺寸由 frameRunnable 同步写入 layoutParams，
        // 下一帧即生效（不 requestLayout 异步重排，避免掉帧）。
        // scale 变化会影响边界范围，需通知外部重算边界（但仅在 scale 真正变化时才触发）。
        val changed = scaleFactor != scale
        scaleFactor = scale
        if (changed) onSizeChanged?.invoke()
    }

    fun getScale(): Float = scaleFactor

    /**
     * 由物理循环每帧调用（~16ms，ms 级）。
     * 职责严格分离：
     *  - 物理 step：重力/抛掷速度/拖拽 是唯一真实位移来源，连续改锚点坐标（physics.x/y）。
     *  - 动画 nextFrame：只管切图（Roll/Walk 等帧切换），绝不控制位置。
     * 两者完全解耦（对齐 PC：img_move_by_offset 由物理速度驱动，set_image 仅算左上角）。
     */
    /**
     * 物理步进：重力/抛掷速度/拖拽 是唯一真实位移来源，连续改锚点坐标（physics.x/y）。
     * 由 frameRunnable 以【固定步长】调用（对齐 PC follow_update_interval=3ms），
     * 与屏幕刷新率解耦，保证位移平滑连续（不随显示帧率波动）。
     */
    fun stepPhysics(fixedDt: Float) {
        physics.step(fixedDt)
    }

    /**
     * 动画推进：只管切图（Roll/Walk 等帧切换），绝不控制位置，使用真实经过时间保证时延精确。
     * 与物理步进完全解耦（对齐 PC：img_move_by_offset 由物理速度驱动，set_image 仅算左上角）。
     */
    fun tick(dt: Float) {
        updateAnimByState()
        // 注意：窗口尺寸 = 统一画布 × scale（恒定），切模式只是换 currentBitmap + 居中重绘，
        // 不触发 WindowManager resize（避免落地连续切模式导致 overlay 重排卡顿）。
        // 所有帧已在 ImageModeManager 加载时居中 padding 到统一画布，居中偏移恒为 0、锚点连续，
        // 因此切模式不再有“尺寸差导致锚点跳变/图被裁切”的错位。
        // “切换模式”后必须【当帧立即刷新位图】，否则要等下方 33ms 节流点才换图，
        // 导致拖拽已跟手、滚动已启动却还显示旧图（慢几拍）。
        val modeChanged = imageManager.didModeChange()
        // 动画推进：只切图，不与位置耦合。
        // 帧推进按固定间隔节流（FRAME_INTERVAL），使“位图切帧”与“物理位置每帧高频刷新”解耦。
        frameAccumulator += dt
        // 模式切换的当帧：强制立即推一帧，让 提起/滚动 首帧即时显示，不被节流延迟。
        if (modeChanged) {
            frameAccumulator = 0f
            syncCurrentBitmap()
            // 模式切换会改变当前锚点（各模式锚点基准不同，如扒鱼 52 / LIE 42 / SIT 81），
            // 浮窗位置 = physics.{x,y} - 锚点偏移。锚点变了必须立即按新锚点重算并写出浮窗，
            // 否则切到 dx=0 的静止模式（LIE/SIT）时不刷新位置，导致图相对地面错位、
            // 甚至被推出屏幕；直到后续某次位移才偶然归位（见扒鱼后 sit 的 bug）。
            // 拖拽/抛掷中由 touch 事件负责位置，此处跳过避免覆盖抓取偏移语义。
            // 注意：此处【不】调 onSizeChanged（窗口尺寸恒定，无需 resize），避免卡顿。
            if (!physics.isDragging && !physics.isThrowing) {
                onPositionChanged?.invoke(physics.x, physics.y)
            }
        } else if (frameAccumulator >= FRAME_INTERVAL) {
            frameAccumulator = 0f
            val dtMs = (FRAME_INTERVAL * 1000f).toLong().coerceAtLeast(1)
            if (imageManager.nextFrame(dtMs).changed) {
                syncCurrentBitmap()
                // 帧偏移位移（对齐 PC anchor_pos += offset）：扒鱼这类一次性位移模式
                // 每帧切换时把当前帧的 dx/dy 累加到物理坐标，实现单循环帧驱动位移。
                // 镜像支持：图片资源朝右，flipX=true 时显示朝左，位移方向也需反向
                // （dir = if (flipX) -1 else 1），使"朝左扒却向右移"的错位消失、与镜像显示一致。
                // 撞墙：累加后夹到物理边界（撞墙即停，不穿透、不反弹，对齐 PC adjust_offset_screen）。
                val dx = imageManager.curDx()
                val dy = imageManager.curDy()
                if (dx != 0 || dy != 0) {
                    val dir = if (flipX) -1 else 1   // 镜像时本地位移 x 反向（对齐图片朝向）
                    // 本地位移：资源朝右前进为 +x（flipX 镜像则取反），dy 为资源自身竖直分量。
                    // 经重力朝向旋转矩阵映射到屏幕位移，四个方向（含顶部 rotate180、左右 rotate90/270）
                    // 统一用 mapLocalToScreen 处理，不再用 sideways 特判，
                    // 彻底消除“顶部图朝左却位朝右 / 左右方向错乱”的错位。
                    val (moveX, moveY) = mapLocalToScreen((dx * dir).toFloat(), dy.toFloat())
                    // 撞墙取消：四边重力下若某轴已贴【对应】重力边，则本次动画位移该轴直接跳过，
                    // 只保留重力（避免反复撞墙抖动死循环）；体感模式四边标志为 false，不触发。
                    val blockedX = (physics.gravityLeft && physics.x <= physics.minX + 0.5f)
                        || (physics.gravityRight && physics.x >= physics.maxX - 0.5f)
                    val blockedY = (physics.gravityTop && physics.y <= physics.minY + 0.5f)
                        || (physics.gravityBottom && physics.y >= physics.maxY - 0.5f)
                    if (moveX != 0f && !blockedX) {
                        physics.x = (physics.x + moveX).coerceIn(physics.minX, physics.maxX)
                    }
                    if (moveY != 0f && !blockedY) {
                        physics.y = (physics.y + moveY).coerceIn(physics.minY, physics.maxY)
                    }
                }
            }
        }
        // 行走位移同步：物理引擎已在 stepPhysics 中推进 physics.x 并做了左右边界反弹
        // （reboundLeft/Right 默认开启）。
        // 关键：walk 期间【每帧强制维护】水平速度 = walkDir * walkSpeed。
        // 不能只依赖 setMode 时一次性注入的 onWalkStateChanged，因为：
        //   1) 物理 step 的兜底清零（isThrowing=false 时）或任何路径都可能把 vx 清掉；
        //   2) walk 撞墙反弹后 vx 已被物理反转（取绝对值变小），需要重新归一为 ±walkSpeed，
        //      否则宠物撞墙后“速度衰减成 0”并停在墙边（与重力概念不同，walk 应持续匀速来回）。
        // 此处用 physics.vx 的当前符号判定方向（撞墙反弹后由物理反转），再乘 walkSpeed 归一，
        // 同时同步镜像朝向，并刷新浮窗位置（对齐 PC 边界回弹 + transform_flag）。
        if (imageManager.isWalk() && physics.gravityEnabled) {
            // 行走位移同步：物理引擎已在 stepPhysics 中推进位置并做了边界反弹。
            // 每帧强制维护速度 = 本地前进(walkSpeed*walkDir, 0) 经重力旋转矩阵映射后的屏幕速度，
            // 使左右重力下沿墙垂直走、下/上重力下水平走，与图片旋转朝向完全对齐。
            // 不能只依赖 setMode 时一次性注入，因物理 step 的兜底清零/反弹可能改写速度，
            // 需每帧归一为匀速 ±walkSpeed（撞墙反弹后也立即恢复回走，对齐 PC 边界回弹）。
            var dir = imageManager.walkDir
            val lw = imageManager.walkSpeed * dir
            val sv = mapLocalToScreen(lw, 0f)
            // 撞屏幕边界回走：前进轴对应屏幕分量朝边界时，反转方向（不压墙、不抖）。
            // 用矩阵给出的屏幕速度分量 (sv.x, sv.y) 直接判定，无需按重力方向特判。
            val blockedX = (physics.x <= physics.minX + 0.5f && sv.first < 0f)
                || (physics.x >= physics.maxX - 0.5f && sv.first > 0f)
            val blockedY = (physics.y <= physics.minY + 0.5f && sv.second < 0f)
                || (physics.y >= physics.maxY - 0.5f && sv.second > 0f)
            if (blockedX || blockedY) {
                // 到达边界：反转方向回走，不施加速度（保留重力），下一帧离开边界。
                dir = -dir
                imageManager.walkDir = dir
                flipX = dir > 0
            } else {
                // 只写「前进轴」速度，另一轴留给物理重力累加，避免 walk 免疫重力。
                //   - 下/上重力：前进轴是屏幕 X 轴（矩阵 sv.first=±speed, sv.second=0），只写 vx，vy 留给重力；
                //   - 左/右重力：前进轴是屏幕 Y 轴（矩阵 sv.first=0, sv.second=±speed），只写 vy，vx 留给重力。
                // 这样散步时宠物既沿墙游走，又被重力持续吸向脚下（贴边不漂、自然落地）。
                if (gravityDir == 1 || gravityDir == 2) {
                    physics.vy = sv.second
                } else {
                    physics.vx = sv.first
                }
            }
            onPositionChanged?.invoke(physics.x, physics.y)
        } else if (imageManager.curDx() != 0 || imageManager.curDy() != 0) {
            // 一次性帧偏移位移模式（扒鱼）：位移由帧 dx/dy 在帧切换时累加驱动（见上），
            // 不走物理速度。此处确保无遗留 vx（避免非拖拽/抛掷时被其它分支清零前的横移），
            // 刷新浮窗位置（对齐 PC 位移后窗口跟随）。图片固定朝右扒不镜像，
            // 期间朝向保持不变（撞墙夹边也不反转）。
            if (!physics.isDragging && !physics.isThrowing && !physics.tiltGravity && (physics.vx != 0f || physics.vy != 0f)) {
                physics.vx = 0f
                physics.vy = 0f
            }
            onPositionChanged?.invoke(physics.x, physics.y)
        } else {
            // 非行走模式：若宠物不在拖拽/抛掷（这些有独立的横向速度语义），
            // 主动清零遗留的 vx/vy，避免从 walk/蠕动切走到其他模式后还在横移/竖移。
            // （setMode 切走时 onWalkStateChanged 的清零可能因 forcedSeq!=null 等路径被跳过，
            //  在 tick 每帧兜底处理最稳妥；拖拽/抛掷态不受影响。）
            if (!physics.isDragging && !physics.isThrowing && !physics.tiltGravity) {
                physics.vx = 0f
                physics.vy = 0f
            }
        }
    }

    /**
     * 把 ImageModeManager 的当前帧同步到 currentBitmap（含尺寸/锚点快照）。
     * 不触发任何边界重算 / 窗口尺寸变更（窗口恒定）。
     */
    private fun syncCurrentBitmap() {
        val (fw, fh) = imageManager.currentBitmapSize()
        val (fax, fay) = imageManager.currentAnchor()
        currentBitmap = imageManager.currentBitmap()
        lastFrameW = fw
        lastFrameH = fh
        lastAnchorX = fax
        lastAnchorY = fay
    }

    /**
     * 由“拖拽/抛掷状态”驱动动画（对齐 PC 状态机）：
     * - 拖拽中：提（LIFT_UP）
     * - 抛掷进行中：翻滚（ROLL），恒定保持，不按速度切（避免抛物线顶点误判）
     * - 其他：常驻静坐（由 forced 一次性动画或 SIT_CLAM）
     */
    private fun updateAnimByState() {
        // 重力·抛掷关闭（且非吸附态）：物理上不再位移，动画应回到非移动池。
        // 诉求：别位移 + 仅播放非移动动作（摸头/炸毛/摇头等菜单动作仍可用）。
        // 因此这里【不取消】一次性动作（否则会误杀菜单非移动动作），仅在「当前没在播
        // 一次性动作」时才切回 SIT_CLAM 静坐；移动态（ROLL/LIFT_UP）由下方 when 守卫拦截。
        if (!physics.gravityEnabled && !physics.isSnapped) {
            // 重力关闭时脚朝向恒为下(0)：宠物不移动，残留的贴墙/倒挂方向会让姿态难看。
            // 每帧纠正，确保任何来源（设置页/随机/菜单）关闭重力后姿态都朝下；吸附态仍独立保留朝向。
            // 注意：这里【只】纠正朝向、并退出“移动态”，不抢常驻模式——
            //   - 关重力时 ROLL/LIFT_UP 这类移动态必须强制退出：ROLL 是恒定循环、物理不位移时
            //     永远触不了底，会永久空转跳不出（如 ROLL 中触发随机/改设置关重力）。复位到 SIT_CLAM
            //     后由 ImageModeManager 自然轮换到摇头/炸毛坐等常驻模式。
            //   - 但【不】对摇头/炸毛坐等常驻模式做 setMode，否则每帧拉回 SIT_CLAM 会让它们“闪一下消失”。
            gravityDir = 0
            val m = imageManager.getMode()
            if ((m == ImageModeManager.ROLL || m == ImageModeManager.LIFT_UP) && !imageManager.isPlayingForced()) {
                imageManager.setMode(ImageModeManager.SIT_CLAM)
            }
        }
        when {
            physics.isDragging && physics.gravityEnabled -> {
                // 用户开始拖拽：抢占一次性动作，否则动作播放期间 setMode 被忽略，
                // 导致拖动时 mode 仍为 sit_clam（状态错乱）。
                if (imageManager.isPlayingForced()) imageManager.cancelForced()
                if (imageManager.getMode() != ImageModeManager.LIFT_UP) {
                    imageManager.setMode(ImageModeManager.LIFT_UP)
                }
                // 每帧根据拖动速度/方向切换提起系列（对齐 PC DragFollowMode.drag_func 每帧判定）：
                //  - 慢速：不调 setLiftSeries，由帧链 0↔1 自然轻晃（PC 的 0101）。
                //  - 中/快向左：定帧 S6(idx5)/S4(idx3)；中/快向右：定帧 S5(idx4)/S3(idx2)。
                //    PC 高速是直接 set 到对应帧（不播链），故每帧定帧、绕过 next 链回拉，
                //    且 setLiftSeries 返回 true 时【当帧立即】syncCurrentBitmap 刷新位图，
                //    避免被 33ms 节流延迟（旧实现只在 MOVE 事件切帧、位图不即时刷新）。
                //    vx 为 px/s 瞬时水平速度；阈值按浮窗宽度比例取（已调低，更易触发）。
                val vx = physics.vx
                val thMid = width * 1.5f   // 中速阈值
                val thHigh = width * 3.5f  // 快速阈值
                val series = when {
                    vx <= -thHigh -> 5   // 快速向左 -> S6
                    vx <= -thMid  -> 3   // 中速向左 -> S4
                    vx >= thHigh  -> 4   // 快速向右 -> S5
                    vx >= thMid   -> 2   // 中速向右 -> S3
                    else          -> -1  // 慢速：保持轻晃链，不主动跳
                }
                if (series >= 2) {
                    if (imageManager.setLiftSeries(series)) {
                        syncCurrentBitmap()   // 帧确实变化才立即刷新，绕过节流
                    }
                    lastLiftSeries = series
                } else {
                    // 慢速：不干预，让 nextFrame 的 0↔1 轻晃链自由推进；
                    // 复位守护标记，使下次提速能正确触发。
                    lastLiftSeries = -1
                }
            }
            physics.isThrowing && physics.gravityEnabled -> {
                // 抛起/滚动：重力关闭时不进入移动态（物理不施加重力，ROLL 不应持续）
                // 同样抢占一次性动作，确保翻滚动画生效。
                if (imageManager.isPlayingForced()) imageManager.cancelForced()
                if (imageManager.getMode() != ImageModeManager.ROLL) {
                    imageManager.setMode(ImageModeManager.ROLL)
                }
            }
            physics.isSnapped -> {
                // 吸附态：常驻探头（脚朝向被贴附的边），循环播放、忽略重力。
                // 抢占一次性动作（如摸头），确保吸附探头不被打断。
                if (imageManager.isPlayingForced()) imageManager.cancelForced()
                // 仅本变体叠加“朝向贴附边”的旋转，不修改全局 gravityDir（不影响其他状态/方向）。
                snapRotation = dirToRotation(physics.snapSide)
                if (imageManager.getMode() != ImageModeManager.SNAP_HEAD) {
                    // 刚进入吸附的这一帧：振动一次提示（getMode 已为 SNAP_HEAD 后不再重复触发）。
                    imageManager.setMode(ImageModeManager.SNAP_HEAD)
                    // 反向镜像随机：吸附探头脚朝向固定（snapRotation），但左右/上下随机翻转，
                    // 仅存于 snapFlipX，不污染全局 flipX（退出吸附后自然失效，零状态残留）。
                    snapFlipX = kotlin.random.Random.nextBoolean()
                    vibrateOnce()
                }
            }
            // 其余状态（静置/落地后）：
            // 若正在播放一次性动作（如摸头）则不打断；
            // 物理空闲且非 forced 时，不再强制回 SIT_CLAM，交由 ImageModeManager
            // 的 timeNext / nextModeName 机制决定下一个模式（自动轮换 / 动作链），
            // 否则摇头、炸毛坐等非 sit 的常驻模式会被每帧拉回 SIT_CLAM。
            // 仅对“物理动作残留”的 LIFT_UP 做兜底复位（提起态不应在空闲时停留）。
            else -> {
                if (imageManager.getMode() == ImageModeManager.LIFT_UP && !imageManager.isPlayingForced()) {
                    imageManager.setMode(ImageModeManager.SIT_CLAM)
                }
            }
        }
    }

    /**
     * View 尺寸（统一画布基准，恒定）。
     * 所有帧在 ImageModeManager 加载时已被居中 padding 到统一画布 (globalW×globalH)，
     * 因此此处窗口尺寸 = 统一画布 × scale，恒定不变 → 切模式不 resize、不卡顿；
     * 居中偏移 (w-dw)/2 恒为 0，锚点经 pad 补偿后跨模式连续 → 不错位。
     * 边界 clamp 用 getBaseBitmapSize（同基准）保持一致。
     */
    fun getBitmapSize(): Pair<Int, Int> {
        return ((imageManager.globalW * scaleFactor).toInt().coerceAtLeast(1)) to
               ((imageManager.globalH * scaleFactor).toInt().coerceAtLeast(1))
    }

    /**
     * 当前帧锚点【在浮窗窗口内的偏移】× 缩放（对齐 PC image_meta）。
     * onDraw 把当前帧【居中】绘制到固定窗口（窗口 = 全局最大帧 × scale，进程内恒定），
     * 因此位图锚点在窗口中的 X = (窗口宽 - 位图宽)/2 + 锚点X*scale。
     * 浮窗左上 = physics.x - 此偏移，保证位图锚点精确落在屏幕 (physics.x, physics.y)。
     */
    fun getAnchorScaled(): Pair<Float, Float> {
        val (w, h) = getBitmapSize()          // 当前模式最大帧×scale（窗口尺寸）
        val (fax, fay) = imageManager.currentAnchor()  // 当前帧图片像素锚点
        val bmp = currentBitmap
        val dw = (bmp?.width ?: 0) * scaleFactor
        val dh = (bmp?.height ?: 0) * scaleFactor
        // 镜像(flipX)时图片以窗口中心翻转，图片像素 x 映射到 dw - x。
        // 故锚点在窗口内的偏移需对称翻转：offX = (w+dw)/2 - fax*scale。
        // 这样扒鱼等镜像模式脚底/接触点对称正确（对齐 PC transform_flag 左右朝向）。
        val offX = if (flipX) {
            (w + dw) / 2f - fax * scaleFactor
        } else {
            (w - dw) / 2f + fax * scaleFactor
        }
        val offY = (h - dh) / 2f + fay * scaleFactor
        // 锚点偏移需随重力朝向旋转，否则旋转 90/180/270 后脚没落在 physics 坐标上
        // （表现为悬浮高度/贴边错位）。与 onDraw 的 canvas.rotate 一致。
        return rotateOffset(offX, offY, w, h)
    }

    /**
     * 边界计算用的固定基准尺寸（初始化静止帧 × scale），与动画帧无关。
     * 使用它能避免 Roll/Walk 每帧尺寸变化导致边界抖动、位置被反复 clamp。
     */
    fun getBaseBitmapSize(): Pair<Int, Int> {
        if (baseFrameW <= 0 || baseFrameH <= 0) return getBitmapSize()
        return (baseFrameW * scaleFactor).toInt() to (baseFrameH * scaleFactor).toInt()
    }

    /**
     * 边界计算用的固定基准锚点（初始化静止帧，居中绘制下的窗口内偏移 × scale）。
     */
    fun getBaseAnchorScaled(): Pair<Float, Float> {
        if (baseAnchorX <= 0 || baseAnchorY <= 0) return getAnchorScaled()
        val (w, h) = getBaseBitmapSize()
        val (fax, fay) = baseAnchorX to baseAnchorY
        val bmp = currentBitmap
        val dw = (bmp?.width ?: 0) * scaleFactor
        val dh = (bmp?.height ?: 0) * scaleFactor
        val offX = (w - dw) / 2f + fax * scaleFactor
        val offY = (h - dh) / 2f + fay * scaleFactor
        // 与 getAnchorScaled 一致：锚点偏移随重力朝向旋转，保证边界/贴边计算与显示对齐。
        return rotateOffset(offX, offY, w, h)
    }

    /** 当前动画模式名（供调试显示） */
    fun currentModeName(): String = imageManager.getMode()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val (w, h) = getBitmapSize()
        setMeasuredDimension(
            resolveSize(w.coerceAtLeast(1), widthMeasureSpec),
            resolveSize(h.coerceAtLeast(1), heightMeasureSpec)
        )
    }

    fun playOnce(name: String) {
        imageManager.playOnce(name)
    }

    fun setMode(name: String) {
        imageManager.setMode(name)
    }

    /** 重力·抛掷关闭时使用静止池（去除蠕动/walk/扒鱼），由 PetService 据 gravityEnabled 同步 */
    var useStaticPool: Boolean
        get() = imageManager.useStaticPool
        set(v) { imageManager.useStaticPool = v }

    /** 强制复位（召回）：清除所有动画并切回静坐 sit_clam */
    fun forceReset() {
        imageManager.forceReset()
        physics.clearSnap()   // 召回同时退出吸附态
        physics.snapSide = 0
    }

    /** 仅切回静坐 sit_clam（清除一次性动画，但保留吸附态/物理位置），用于重力·抛掷关闭时进入静坐。 */
    fun enterIdle() {
        imageManager.forceReset()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = currentBitmap ?: return
        // View 尺寸固定为【当前模式最大帧】尺寸（不随帧变 → 不重排 → 流畅）。
        // 每帧位图按【居中】绘制到窗口内（铺满窗口宽/高进行等比缩放），onDraw 只负责“完整显示当前帧”，
        // 不做锚点负偏移——锚点对齐由 PetService 写 layoutParams 时负责（浮窗左上 = physics.x - 锚点偏移）。
        // 这样小帧/大帧都能完整显示，不会因负偏移被推出窗口只剩边角。
        val dw = (bmp.width * scaleFactor).toInt()
        val dh = (bmp.height * scaleFactor).toInt()
        if (dw <= 0 || dh <= 0) return
        val left = (width - dw) / 2f
        val top = (height - dh) / 2f
        canvas.save()
        // 重力朝向：绕窗口中心旋转画布，使宠物“脚朝重力方向”（含 sit 等静态动作）。
        // 旋转在当前帧中心进行，浮窗位置由 physics 决定、图仅绕自身中心转，姿态正确且不重排。
        // 吸附态以 snapSide 为唯一方向来源（snapRotation 已在 updateAnimByState 的 isSnapped
        // 分支按贴附边赋值），【不叠加】拖动期可能被体感污染的 gravityRotation，避免吸附后方向异常。
        val effRotation = if (physics.isSnapped) {
            snapRotation
        } else {
            snapRotation = 0f
            gravityRotation
        }
        if (effRotation != 0f) {
            canvas.rotate(effRotation, width / 2f, height / 2f)
        }
        // 镜像：吸附态用专属 snapFlipX（反向随机、隔离全局 flipX）；非吸附态用全局 flipX。
        // 两者互斥（吸附态不读 flipX），退出吸附后 snapFlipX 不再应用，零状态残留。
        if (physics.isSnapped) {
            if (snapFlipX) {
                canvas.scale(-1f, 1f, width / 2f, height / 2f)
            }
        } else if (flipX) {
            // 水平镜像：以中心为轴翻转画布（对齐 PC transform_flag 左右随机朝向），叠加在重力旋转之上
            canvas.scale(-1f, 1f, width / 2f, height / 2f)
        }
        canvas.drawBitmap(bmp, null,
            android.graphics.RectF(left, top, left + dw, top + dh), paint)
        canvas.restore()
        // 调试：红色矩形 = View（浮窗）真实边界，与屏幕位置严格对齐，稳定不跳动
        if (showRect) {
            val rectPaint = android.graphics.Paint().apply {
                color = 0xFFFF0000.toInt()
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 3f
            }
            canvas.drawRect(1.5f, 1.5f, width - 1.5f, height - 1.5f, rectPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                dragging = false
                movedDuringPress = false
                pressing = true
                longPressTriggered = false
                longPressConsumed = false
                lastTouchX = event.rawX
                lastTouchY = event.rawY
                lastMoveX = event.rawX
                lastMoveY = event.rawY
                lastMoveTime = SystemClock.uptimeMillis()
                // 注意：此处【不】立即设 physics.isDragging=true。
                // 旧逻辑在 DOWN 就设 isDragging，导致长按期间被 updateAnimByState 切到 LIFT_UP（提起）
                // 而松手若未移动则既不抛掷也不恢复，使 LIFT_UP 永久滞留（提起卡死）。
                // 改为仅在 MOVE 超过阈值（真正拖拽）时才设 isDragging，长按期间宠物保持当前动作。
                // 抓取偏移：按下时锚点坐标 - 手指位置（保持抓取点相对图片不变，拖拽不跳）
                grabOffsetX = physics.x - event.rawX
                grabOffsetY = physics.y - event.rawY
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - lastTouchX
                val dy = event.rawY - lastTouchY
                if (abs(dx) > 2 || abs(dy) > 2) movedDuringPress = true
                // 明显移动 -> 视为拖拽而非长按：取消本次长按打开（需求：长按后移开则不打开菜单）
                if (abs(dx) > 8 || abs(dy) > 8) {
                    if (!dragging) onDragStateChanged?.invoke(true)  // 提起开始
                    dragging = true
                    longPressTriggered = false
                }
                // 速度采样（用于抛掷初速）。重力关闭时拖动即静止，不采样/不赋予抛掷速度。
                val now = SystemClock.uptimeMillis()
                val dt = (now - lastMoveTime) / 1000f
                if (physics.gravityEnabled) {
                    // 仅真实拖拽(dragging)时采样，避免长按微抖污染；
                    // dt 用 max(...,1ms) 防止相邻事件间隔 <1ms 导致 dt≈0 跳过采样，
                    // 否则快速甩动时若某次 dt 恰好为 0，vx/vy 会沿用陈旧值甚至保持 0。
                    if (dragging && movedDuringPress && dt > 0f) {
                        physics.vx = (event.rawX - lastMoveX) / dt
                        physics.vy = (event.rawY - lastMoveY) / dt
                    }
                } else {
                    // 重力关闭：拖动期间速度恒为 0，松手即静止
                    physics.vx = 0f
                    physics.vy = 0f
                }
                lastMoveX = event.rawX
                lastMoveY = event.rawY
                lastMoveTime = now
                if (dragging) {
                    physics.isDragging = true
                    physics.clearSnap()   // 用户拖拽拉出：解除吸附态
                    // 提起动画系列随速度切换的逻辑已移到 tick()（每帧驱动，对齐 PC drag_func 每帧判定），
                    // 此处仅跟随手指与速度采样，不再直接切帧。

                    // 锚点跟随手指（保持抓取点相对图片不变）：锚点 = 手指 + 抓取偏移
                    val ax = event.rawX + grabOffsetX
                    val ay = event.rawY + grabOffsetY
                    onPositionChanged?.invoke(ax, ay)
                }
                lastTouchX = event.rawX
                lastTouchY = event.rawY
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pressing = false
                physics.isDragging = false
                if (dragging) {
                    onDragStateChanged?.invoke(false)
                    if (physics.gravityEnabled) {
                        // 吸附优先：松手速度低且靠近某（开启了吸附开关的）边 → 吸附成常驻探头，
                        // 忽略重力直到用户再次拖拽拉出。否则才进入抛掷（恒定 ROLL 直到触底静止）。
                        // 注意：长按进菜单走的是另一条手势路径（dragging 为 false），不会误触发吸附。
                        if (physics.shouldSnap()) {
                            physics.snapTo(physics.nearestSnapSide())
                            // 动画由 updateAnimByState 的 isSnapped 分支切到 SNAP_HEAD，
                            // 并同步脚朝向 gravityDir=snapSide。
                        } else {
                            // 释放即进入抛掷状态（对齐 PC 拖拽结束 -> ThrowFollowMode），
                            // 速度已由 MOVE 采样，恒定 ROLL 直到触底静止。
                            // 无论松手速度大小都强制进入 ROLL 动画（用户诉求：松手即 ROLL），
                            // 不再依赖 step 里的 sp>tiltRollSpeed 速度阈值——体感模式下轻甩/近静止
                            // 松手时该阈值不满足会导致不进 ROLL 的回归问题。
                            physics.throwWith(physics.vx, physics.vy)
                            enterTiltRoll(force = true)
                        }
                    } else {
                        // 重力关闭：拖动即静止，清空速度，不做抛掷
                        physics.vx = 0f
                        physics.vy = 0f
                        physics.isThrowing = false
                        // 重力关闭仍允许吸附：松手贴边则吸附成常驻探头，否则静止
                        if (physics.shouldSnap()) {
                            physics.snapTo(physics.nearestSnapSide())
                        }
                    }
                    dragging = false
                    lastLiftSeries = -1   // 重置提起系列守护，下次提起从慢速轻晃链起点开始
                } else if (longPressTriggered) {
                    // 有效长按且松手时未明显移动 -> 打开菜单（需求：振动后若未取消则打开）
                    longPressTriggered = false
                    onLongPress?.invoke()
                } else {
                    // 纯点击/短按（非拖拽、非长按）：若因历史原因停在 LIFT_UP，复位避免卡死
                    if (imageManager.getMode() == ImageModeManager.LIFT_UP && !imageManager.isPlayingForced()) {
                        imageManager.setMode(ImageModeManager.SIT_CLAM)
                    }
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        imageManager.clearCache()
    }

    // 主动进入翻滚：置物理抛掷态（复用抛掷生命周期，落地后由 onThrowEnd 播 JUMP_DOWN 收尾），
    // 并切到 ROLL 动画。
    // 两个触发来源：
    //  1) 体感下“脚朝向突变”掩盖姿态硬切（dir 传入）：仅在非拖拽且当前未处于抛掷/翻滚时生效。
    //  2) 拖拽松手抛投（force=true）：用户期望“松手即 ROLL，与速度无关”，故即使 isThrowing
    //     已被 throwWith 置位也强制进入，不受速度阈值/冷却限制。
    // dir：可选的目标脚朝向。传入时在【切好 ROLL 之后】再设置，避免先改 gravityRotation 导致
    // 旧模式（非对称）帧被新旋转角硬转一帧“闪一下”，再才切到居中对称的 ROLL 帧。
    internal fun enterTiltRoll(dir: Int? = null, force: Boolean = false) {
        // 吸附态：只允许“用户手动拉出/召回”退出，任何重力/方向变化触发的翻滚都忽略，
        // 否则体感重力改变脚朝向会误触发 ROLL 破坏吸附探头。
        if (physics.isSnapped) return
        if (physics.isDragging || (physics.isThrowing && !force)) return
        physics.beginTiltRoll()
        if (imageManager.isPlayingForced()) imageManager.cancelForced()
        if (imageManager.getMode() != ImageModeManager.ROLL) {
            imageManager.setMode(ImageModeManager.ROLL)
        }
        // 先切 ROLL（居中对称，旋转外观不变），再改方向：下一帧 onDraw 直接看到 ROLL+新朝向，
        // 不会让旧模式帧先被新旋转角硬转一帧。
        if (dir != null) gravityDir = dir
    }
}
