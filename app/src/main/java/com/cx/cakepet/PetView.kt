package com.cx.cakepet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
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
import kotlin.math.round

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

    /** 当前帧锚点【快照】：在 syncCurrentBitmap() 中与 currentBitmap 同一次、同帧写入，
     *  是“显示位图”与“定位锚点”的唯一真相源。之后所有位置计算只读它，不再回头 currentAnchor() 实时读，
     *  杜绝“显示切到帧 N+1、位置还在用帧 N 锚点”的异步错位（动作与位置不适配）。 */
    private var currentFrameAnchor: Pair<Int, Int>? = null
    // 上次已发出位置的锚点值：仅当锚点真正变化时才重发浮窗位置（避免每帧无谓 updateViewLayout）。
    private var lastEmittedAnchorX = -1
    private var lastEmittedAnchorY = -1
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // 触摸拖动状态
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var dragging = false
    private var movedDuringPress = false
    // 本次手势的 DOWN 是否命中（像素点）并被消费。透明起点（红框）时为 false，
    // 用于守卫 MOVE/UP：只有命中起点的手势才允许进入拖拽/提起，避免透明区误拖动导致位置跳变。
    private var hitDownConsumed = false
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
    // 拖拽拉出吸附态：残留朝向(rotation/flip/side)归零后通知外部重算活动范围，避免边界沿用吸附态包围盒
    var onSnapExit: (() -> Unit)? = null
    // 输入法（软键盘）显隐：回调当前键盘高度（已扣除导航栏），供外部抬高宠物地面避免遮挡
    var onImeInsetChanged: ((height: Int) -> Unit)? = null
    // 调试边框（两个独立开关）：显示边框=图片窗口黑线+脚锚点十字；控制边框=命中可视化（贴实际控制窗）
    var showImageBorder = false
    var showControlBorder = false
    var hitMode: Int = ConfigDefaults.HIT_BOUNDARY // 命中模式：像素/边界/核心(脚底盒)
    var ctrlBoxWidth: Float = ConfigDefaults.CTRL_BOX_WIDTH      // 核心命中区宽（128基数）
    var ctrlBoxHeight: Float = ConfigDefaults.CTRL_BOX_HEIGHT    // 核心命中区高（128基数）
    var ctrlBoxVOffset: Float = ConfigDefaults.CTRL_BOX_VOFFSET  // 核心命中区垂直偏移（128基数）

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
    internal var gravityRotation = 0f

    // 吸附态专属旋转角（度，顺时针）：仅 SNAP_HEAD 变体叠加的“脚朝向贴附边”旋转，
    // 不修改全局 gravityDir/gravityRotation，退出吸附即归零，不影响其他状态方向。
    internal var snapRotation = 0f

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
     * 把「窗口内本地锚点偏移」(offX, offY) 按指定旋转角（度，顺时针）绕 (cx, cy) 旋转，
     * 得到旋转后真实屏幕偏移。与 onDraw 的 canvas.rotate(deg, cx, cy) 一致（先旋转后镜像）。
     * 不处理 flipX（调用方传入的 off 已含镜像）。默认 0° 时原样返回（避免无谓三角函数）。
     * 默认旋转中心为窗口中心 (w/2, h/2)；ROLL 传入锚点自身 (offX, offY) 作为中心，
     * 使球状对称的 roll 绕脚（锚点）旋转、浮窗不抖，同时锚点用真实值、与其他动作统一。
     */
    /**
     * 二分测试开关：定位「像素级命中失效」是位图问题还是外部链路消费了点击。
     *   - null  : 正常使用像素级判定（isHitOnPet 真实返回值）
     *   - true  : 强制整窗命中（等价于当前"矩形内都可点击"）
     *   - false : 强制整窗不命中（透明/非透明都应穿透，整窗不可拖）
     * 实测：
     *   - 恒 true 与恒 false 行为【不同】 -> isHitOnPet 确实是闸门，问题在其真实返回值（位图/矩阵）
     *   - 恒 false 仍可拖 -> 是外部链路（而非 isHitOnPet）消费了 DOWN，问题在 PetService/窗口层
     * 伴生对象：全局统一，PetService 内所有真实浮窗实例都会遵守。
     */
    companion object {
        @Volatile
        @JvmStatic
        var FORCE_HIT_TEST: Boolean? = null
    }

    /** 当前帧 bitmap（供触摸像素命中判定使用） */
    private fun getFrameBitmap(): Bitmap? = currentBitmap

    /** 绘制/命中共用的渲染状态：强制 onDraw 与 isHitOnPet 在同一帧使用完全相同的参数，
     *  杜绝两者因各自计算绘制矩形/旋转/镜像导致的坐标系错位（整窗误判命中的根因）。 */
    private data class RenderState(
        val bmp: Bitmap,
        val dw: Float, val dh: Float,
        val left: Float, val top: Float,
        val cx: Float, val cy: Float,
        val rotation: Float, val flip: Boolean,
        val scale: Float
    )

    /** 由 currentBitmap + scaleFactor + 当前吸附/朝向状态计算渲染参数。
     *  注意：非吸附态不使用 snapRotation，故这里不读取 snapRotation（旧 onDraw 非吸附态会
     *  顺手写 snapRotation=0f，属于副作用，已移除，避免与命中判定读到不同中间状态）。 */
    private fun computeRenderState(): RenderState? {
        val bmp = currentBitmap ?: return null
        if (bmp.width <= 0 || bmp.height <= 0) return null
        // 逻辑画布尺寸（恒 128 基准；新包解码 256 超采样但画布逻辑仍为 128）。dw/dh 用逻辑尺寸，
        // 而非 bmp 像素（256），否则新包会被放大 2x。
        val (cw, ch) = imageManager.currentBitmapSize()
        val s = scaleFactor
        val dw = cw * s
        val dh = ch * s
        if (dw <= 0 || dh <= 0) return null
        // 渲染坐标系必须用固定整帧尺寸（与 stageView 的 getBaseAnchorScaled 同源），
        // 不可用控制窗实时尺寸：CORE 模式下控制窗被缩成脚底盒，用 width/height 会把位图算偏。
        val (fw, fh) = getBaseBitmapSize()
        val left = (fw - dw) / 2f
        val top = fh - dh
        val cx = fw / 2f
        val cy = fh / 2f
        // 绘制缩放：把【实际 bitmap 像素】映射为【屏幕逻辑矩形】。新包 bmp=256、dw=128*s ⇒ s/2（GPU 降采样，清晰）；
        // 旧包 bmp=128、dw=128*s ⇒ s（与原行为一致）。isHitOnPet 用同一 scale 反算，命中与显示严格对齐。
        val drawScale = if (bmp.width > 0) dw / bmp.width else s
        val rotation = if (physics.isSnapped) snapRotation else gravityRotation
        val flip = if (physics.isSnapped) snapFlipX else flipX
        return RenderState(bmp, dw, dh, left, top, cx, cy, rotation, flip, drawScale)
    }

    /**
     * 判断视图坐标 (vx, vy) 是否落在 pet 当前显示部分（非透明像素）。
     * 使用与 onDraw 完全相同的 RenderState（同一份尺寸/旋转/镜像/缩放参数）构建正向矩阵并求逆，
     * 保证命中坐标系与显示严格一致。透明区域返回 false → onTouchEvent 放行穿透。
     */
    /** 调试可视化：最近一次 DOWN 的命中判定结果（null=未点；true=命中非透明；false=透明穿透） */
    private var lastHitTest: Boolean? = null

    /** 调试可视化：PetView 实际收到的最近一次触摸 action（0=未收到, 0=DOWN,1=MOVE,2=UP） */
    private var lastAction: Int = -1

    // 供 PetStageView 调试层读取的只读通道（控制边框可视化用），不暴露写权限。
    val debugLastHitTest: Boolean? get() = lastHitTest
    val debugLastAction: Int get() = lastAction

    private fun isHitOnPet(vx: Float, vy: Float): Boolean {
        val rs = computeRenderState() ?: return false // 无图时不命中，整窗放行穿透
        val w = rs.bmp.width
        val h = rs.bmp.height
        // 正向矩阵（bitmap像素 -> View坐标）必须严格等于 onDraw 的变换链：
        //   canvas.rotate(R) → canvas.scale(-1,F) → drawBitmap(RectF = T∘S)
        // 即正向 M = R · F · T · S（R/F 绕中心，T=translate(left,top)，S=scale(s)）。
        // post* 为右乘（后调用先作用于点），故按"从右到左"顺序构造：
        //   postScale(s) → postTranslate(left,top) → postScale(-1,F) → postRotate(R)
        val m = Matrix()
        m.postScale(rs.scale, rs.scale)          // S
        m.postTranslate(rs.left, rs.top)          // T
        if (rs.flip) m.postScale(-1f, 1f, rs.cx, rs.cy)  // F（绕中心）
        if (rs.rotation != 0f) m.postRotate(rs.rotation, rs.cx, rs.cy) // R（绕中心）
        val inv = Matrix()
        if (!m.invert(inv)) return false
        val pts = floatArrayOf(vx, vy)
        inv.mapPoints(pts)
        val px = round(pts[0]).toInt()
        val py = round(pts[1]).toInt()
        if (px < 0 || py < 0 || px >= w || py >= h) {
            return false
        }
        val alpha = rs.bmp.getPixel(px, py).ushr(24)
        val hit = alpha != 0 // alpha != 0 即命中
        lastHitTest = hit
        return hit
    }

    private fun rotateOffsetWith(offX: Float, offY: Float, w: Int, h: Int, deg: Float,
                                 cx: Float = w / 2f, cy: Float = h / 2f): Pair<Float, Float> {
        if (deg == 0f) return offX to offY
        val rx = offX - cx
        val ry = offY - cy
        val rad = Math.toRadians(deg.toDouble())
        val cos = kotlin.math.cos(rad)
        val sin = kotlin.math.sin(rad)
        val nx = rx * cos - ry * sin
        val ny = rx * sin + ry * cos
        return (cx + nx.toFloat()) to (cy + ny.toFloat())
    }

    /**
     * 把「窗口内本地锚点偏移」(offX, offY) 按【重力朝向】绕窗口中心旋转（非吸附态用）。
     * 吸附态应使用 rotateOffsetWith(..., snapRotation)，与 onDraw 的 snapRotation 渲染一致。
     */
    private fun rotateOffset(offX: Float, offY: Float, w: Int, h: Int): Pair<Float, Float> {
        return rotateOffsetWith(offX, offY, w, h, gravityRotation)
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

    /** 吸附态渲染旋转角（度），与 onDraw 中应用的 snapRotation 一致。 */
    fun getSnapRotation(): Float = snapRotation

    /** 吸附态渲染水平镜像，与 onDraw 中应用的 snapFlipX 一致。 */
    fun getSnapFlipX(): Boolean = snapFlipX

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
            // syncCurrentBitmap() 内部已写入锚点快照、并按“锚点是否变化”原子重发浮窗位置
            // （与位图同帧），故切模式导致的锚点变化会立即反映到窗口位置，无需此处再发。
            // 拖拽/抛掷中由 touch 事件负责位置，syncCurrentBitmap 内部已跳过，不会覆盖抓取偏移语义。
            syncCurrentBitmap()
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
        // 锚点快照：与上面的 currentBitmap 同一次、同帧写入，是“显示位图”与“定位锚点”的唯一真相源。
        // 之后所有位置计算(getAnchorScaled/getAnchorRaw)只读此快照，不再回头 currentAnchor() 实时读，
        // 杜绝“显示切到帧 N+1、位置还在用帧 N 锚点”的异步错位。
        currentFrameAnchor = fax to fay
        // 锚点变化才按新锚点重发浮窗位置：与位图同步在同一调用内完成 → 动作与位置严格同帧更新。
        // 拖拽/抛掷期间由 touch 链路负责位置，此处跳过避免覆盖抓取偏移语义。
        if (!physics.isDragging && !physics.isThrowing) {
            if (fax != lastEmittedAnchorX || fay != lastEmittedAnchorY) {
                lastEmittedAnchorX = fax
                lastEmittedAnchorY = fay
                onPositionChanged?.invoke(physics.x, physics.y)
            }
        }
    }

    /**
     * 运行时切换资源包（关于页“使用新资源”开关触发）。
     * 重建 ImageModeManager 内部状态并复位到静坐，再重读基准帧尺寸、同步当前帧。
     */
    fun applySpritePack(pack: SpritePack) {
        imageManager.setPack(pack)
        val (gw, gh) = imageManager.globalMaxSize()
        baseFrameW = gw
        baseFrameH = gh
        syncCurrentBitmap()
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
                // 抢占一次性动作（如摸头），确保吸附探头不被打断；
                // 但允许“吸附探头摸摸头(SNAP_PAT_HEAD)”一次性动作完整播放，结束后由其 nextMode 切回 SNAP_HEAD。
                if (imageManager.isPlayingForced() && imageManager.getMode() != ImageModeManager.SNAP_PAT_HEAD) {
                    imageManager.cancelForced()
                }
                // 仅本变体叠加“朝向贴附边”的旋转，不修改全局 gravityDir（不影响其他状态/方向）。
                snapRotation = dirToRotation(physics.snapSide)
                val curMode = imageManager.getMode()
                if (curMode != ImageModeManager.SNAP_HEAD && curMode != ImageModeManager.SNAP_PAT_HEAD) {
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
     * 统一画布为【顶部填充、底部补透明】填充（非 roll 时图片置顶、透明在下方），
     * currentAnchor() 返回的 fay 已按该规则含 padY（脚离窗口顶距离），窗口内偏移 Y = fay*scale；
     * 不再叠加 (h-dh)/2 居中偏移（否则锚点被多下移半张图高，宠物悬空/出界）。
     * 水平方向图居中填充，偏移 X = (窗口宽-图宽)/2 + 锚点X*scale。
     * 浮窗左上 = physics.x - 此偏移，保证位图锚点精确落在屏幕 (physics.x, physics.y)。
     */
    fun getAnchorScaled(): Pair<Float, Float> {
        val (w, h) = getBitmapSize()          // 当前模式最大帧×scale（窗口尺寸）
        // 锚点取自【同帧快照】currentFrameAnchor，与 onDraw 显示的 currentBitmap 严格同帧，
        // 不再实时读 currentAnchor()（否则会与显示帧错位，导致动作/位置不适配）。
        val (fax, fay) = currentFrameAnchor ?: imageManager.currentAnchor()
        // 绘制宽度 = 窗口宽 w（逻辑 128*s，render 把 256 超采样源缩进此矩形铺满），
        // 不再用 bmp.width（新包=256 物理像素），否则横向偏移偏 64*s。
        val dw = w
        // 镜像：吸附态用专属 snapFlipX（与 onDraw 渲染一致），非吸附态用全局 flipX。
        // 镜像时图片以窗口中心翻转，图片像素 x 映射到 dw - x，
        // 故锚点在窗口内的偏移需对称翻转：offX = (w+dw)/2 - fax*scale（扒鱼等左右朝向正确）。
        val useFlip = if (physics.isSnapped) snapFlipX else flipX
        val offX = if (useFlip) {
            (w + dw) / 2f - fax * scaleFactor
        } else {
            (w - dw) / 2f + fax * scaleFactor
        }
        // 统一画布顶部填充、底部补透明，currentAnchor() 已按填充规则含 padY ⇒ 脚离窗口顶 = fay，直接乘 scale，
        // 不再叠加 (h-dh)/2 居中偏移（否则锚点被多下移半张图高，宠物悬空/出界）。
        val offY = fay * scaleFactor
        // 锚点偏移需随【渲染旋转】旋转，否则旋转 90/180/270 后脚没落在 physics 坐标上
        // （表现为悬浮高度/贴边错位）。吸附态渲染用 snapRotation（脚朝贴附边），
        // 非吸附态用 gravityRotation；必须用与 onDraw 完全相同的旋转角与旋转中心，否则定位与显示错位。
        // 所有非 ROLL 动作绕【窗口/矩形框中心】旋转，锚点(脚)随朝向旋转后定位浮窗。
        // ROLL 解构（避免因果循环）：球状对称的 roll 在脚方向改变时，图绕自身中心自转、
        // 视觉不变，但矩形框(浮窗)位置必须恒定——否则锚点偏移被 rot 旋转后回流到浮窗 y，
        // 导致脚方向一变框就跳（锚点↔位置循环因果）。
        // 引入中间值：roll 时浮窗定位用 rot=0 的【锚点基准偏移】（图中心/脚的 rot 无关投影），
        // 旋转只作用于 onDraw 的图自转，不参与浮窗定位。anchorY 仍真实参与 offY 计算
        // （它是位置输入：非 roll 动作切换正常用；roll 时也影响恒定偏移，用户可调），
        // 只是不再被 rot 旋转后回流，从而在“锚点仍生效”与“框不随 rot 跳变”间解耦。
        val rot = if (physics.isSnapped) snapRotation else gravityRotation
        val effRot = if (imageManager.getMode() == ImageModeManager.ROLL) 0f else rot
        return rotateOffsetWith(offX, offY, w, h, effRot)
    }

    /**
     * 当前帧锚点【在浮窗窗口内的偏移】× 缩放，但【未随重力朝向旋转】（= getAnchorScaled 的旋转前值）。
     * 供吸附线浮层在「与 PetView 完全相同的局部坐标系」中重放同样的 rotate/scale 变换，
     * 避免双重旋转导致线段错位。返回的是相对浮窗左上角的偏移 (offX, offY)。
     */
    /**
     * 当前帧锚点【在浮窗窗口内的偏移】× 缩放，但【未随重力朝向旋转】（= getAnchorScaled 的旋转前值）。
     * 供吸附线浮层在「与 PetView 完全相同的局部坐标系」中重放同样的 rotate/scale 变换，
     * 避免双重旋转导致线段错位。返回的是相对浮窗左上角的偏移 (offX, offY)。
     *
     * 关键：必须复用 imageManager.currentAnchor() 的【已含 pad 补偿】的锚点值，
     * 而非帧原始 anchorY 自己手算居中偏移——统一画布是【顶部填充、底部补透明】（非 roll 时 padY=0），
     * currentAnchor() 已按填充规则把 padY 加进锚点（ay=frame.anchorY+padY），手算 (h-dh)/2 会得到
     * 「居中」补偿（与顶部填充不符），使地面线偏低、偏离宠物真实脚底（贴墙接触线）。
     * 位图在窗口内由 onDraw 居中绘制到 (left,top)，故锚点窗口坐标 = left + ax*scale / top + ay*scale。
     */
    fun getAnchorRaw(): Pair<Float, Float> {
        val (w, h) = getBitmapSize()
        val (fax, fay) = currentFrameAnchor ?: imageManager.currentAnchor()  // 已含 pad 补偿的统一画布坐标（未乘 scale）
        // 绘制宽/高 = 窗口宽/高（逻辑 128*s，render 把 256 超采样源缩进此矩形铺满），
        // 不再用 bmp 物理像素（新包=256），否则 left/top 横纵各偏 64*s。
        val dw = w
        val dh = h
        val left = (w - dw) / 2f
        // 统一画布顶部填充、底部补透明（与 onDraw 一致）：位图在窗口内按 (left,top) 对齐绘制，
        // 图片在画布内（顶部），脚由 currentAnchor 的 fay 给出；若 dh≠h 则 top=h-dh 兜底对齐。
        val top = (h - dh)
        // 镜像(flipX)时图片以窗口中心翻转，锚点 X 对称：offX = w - (left + fax*scale)。
        // 与 onDraw 的 scale(-1,1,center) 完全一致，使吸附线 X 与宠物脚底/接触点对齐。
        val offX = if (flipX) {
            w - (left + fax * scaleFactor)
        } else {
            left + fax * scaleFactor
        }
        val offY = top + fay * scaleFactor
        return offX to offY
    }

    /**
     * 边界计算用的固定基准尺寸（全局最大帧尺寸 × scale），与动画帧无关。
     * 所有模式共用同一张统一画布（globalMaxSize），sit 与 SNAP_HEAD 尺寸相同，故恒定返回此值即可；
     * 使用它能避免每帧尺寸变化导致边界抖动、位置被反复 clamp。
     */
    fun getBaseBitmapSize(): Pair<Int, Int> {
        if (baseFrameW <= 0 || baseFrameH <= 0) return getBitmapSize()
        return (baseFrameW * scaleFactor).toInt() to (baseFrameH * scaleFactor).toInt()
    }

    /**
     * 给定窗口尺寸 (w,h) 与旋转角 rotDeg，返回图片绕中心旋转后【水平半跨度, 垂直半跨度】
     * （即旋转后图片在 x/y 方向占用的半尺寸）。rotDeg=0 时 = (w/2, h/2)。
     * 供 recalcBounds 在吸附/重力旋转态下按“旋转后包围盒”内缩，使图片边缘精确贴偏移线。
     */
    fun getRotatedHalfExtents(w: Int, h: Int, rotDeg: Float): Pair<Float, Float> {
        // 图片绕中心旋转 rotDeg（顺时针，与 onDraw 的 canvas.rotate 一致）后的 AABB 半跨度。
        // 标准公式：halfW = |w/2*cos| + |h/2*sin|，halfH = |w/2*sin| + |h/2*cos|。rotDeg=0 时=(w/2,h/2)。
        val rad = Math.toRadians(rotDeg.toDouble())
        val cos = abs(kotlin.math.cos(rad).toFloat())
        val sin = abs(kotlin.math.sin(rad).toFloat())
        val hw = (w / 2f) * cos + (h / 2f) * sin
        val hh = (w / 2f) * sin + (h / 2f) * cos
        return hw to hh
    }

    /**
     * 边界计算用的固定基准锚点（居中绘制下的窗口内偏移 × scale）。
     *
     * 与历史版本一致：吸附态也用 sit 基准 baseAnchorX/Y（=81），【不】改用 SNAP_HEAD 的
     * currentAnchor()(=66)。原因：recalcBounds 与浮窗定位都用此方法算"锚点→浮窗左上"，
     * 二者必须同源。若吸附态改用 66，则探头"虚拟地面"变成脚锚点(66)而非 png 底，
     * 图片整体下坠 15px、超出偏移线，且与其他动作(均用 81)不一致 → 只有探头错位。
     * 旋转：吸附态用 snapRotation（脚朝贴附边，与 onDraw 一致）、非吸附态用 gravityRotation，
     * 保证朝向正确（仅旋转图片，不改变"地面=基准锚点"的约定）。
     */
    /**
     * 边界/贴边计算用的锚点（居中绘制下的窗口内偏移 × scale）。
     *
     * 必须与渲染层 getAnchorScaled 同源：一律用 currentAnchor()（当前帧真实锚点，
     * sit=81 / SNAP_HEAD=66 / 其它动作各自值），吸附态/非吸附态都不切换。
     * 这样 recalcBounds 的 maxY 与浮窗定位 layoutParams=physics-ay 用【同一个 ay】，
     * 锚点(脚)才能精确落在 physics 坐标上。
     *
     * 探头/越界模型（见 recalcBounds）：边界只约束“脚朝向那一侧的锚点不超可见区”，
     * 其余三侧约束 PNG 四边不超界。故 SNAP(66) 脚的锚点贴边、图底自然下垂到屏幕外
     * （被裁切=探头），sit(81) 同理脚贴边、图底透明区垂下；各动作按各自脚锚点贴边，
     * 互不干扰。边界与渲染同源是探头成立的前提（之前改 66/81 来回抵消=同源被误删）。
     *
     * 旋转：吸附态 snapRotation、非吸附态 gravityRotation，保证朝向与 onDraw 一致。
     */
    fun getBaseAnchorScaled(): Pair<Float, Float> {
        val (fax, fay) = imageManager.currentAnchor()   // 当前帧真实锚点（随动作/模式变化）
        val (w, h) = getBaseBitmapSize()
        // 位图在窗口内铺满（render 把 256 物理超采样源缩进 128×s 逻辑矩形），绘制宽度 = 窗口宽 w，
        // 不再用 bmp.width（新包=256 物理像素），否则 offX 横向偏 64*s、所有动作浮窗整体右移。
        val dw = w
        val offX = (w - dw) / 2f + fax * scaleFactor
        // 顶部填充、底部补透明：currentAnchor() 已按填充规则含 padY ⇒ 脚离窗口顶 = fay，直接乘 scale，不加 (h-dh)/2。
        val offY = fay * scaleFactor
        val rot = if (physics.isSnapped) snapRotation else gravityRotation
        // 与 getAnchorScaled 同源：ROLL 同样用 rot=0 中间值，保证边界/贴边计算与浮窗定位一致
        // （框恒定，不随脚方向跳变）。
        val effRot = if (imageManager.getMode() == ImageModeManager.ROLL) 0f else rot
        return rotateOffsetWith(offX, offY, w, h, effRot)
    }

    /**
     * 控制窗矩形（屏幕绝对坐标）：控制层几何，独立于显示层（全屏画布只显示、不接触摸）。
     * - 像素/边界：整只宠物显示矩形（覆盖全宠以便整窗命中）。
     * - 核心：脚底盒（ctrlBox，屏幕单位，不乘图片 scale），随脚方向旋转；
     *   控制窗缩到该盒的轴对齐包围盒(AABB)，盒外区域由 isCoreHit 的 OBB 判定放穿。
     */
    fun controlWindowRect(): android.graphics.RectF {
        val (ax, ay) = getBaseAnchorScaled()
        val dispX = physics.x - ax
        val dispY = physics.y - ay
        return when (hitMode) {
            ConfigDefaults.HIT_CORE -> {
                val (bcx, bcy) = coreBoxCenterDisplayLocal()
                val rot = if (physics.isSnapped) snapRotation else gravityRotation
                val effRot = if (imageManager.getMode() == ImageModeManager.ROLL) 0f else rot
                val rad = Math.toRadians(effRot.toDouble())
                val c = Math.abs(Math.cos(rad)).toFloat()
                val s = Math.abs(Math.sin(rad)).toFloat()
                // ctrlBox 为 128 逻辑基数，× scaleFactor（宠物大小）转屏幕像素。
                val hw = ctrlBoxWidth * scaleFactor
                val hh = ctrlBoxHeight * scaleFactor
                val ahw = (hw / 2f) * c + (hh / 2f) * s
                val ahh = (hw / 2f) * s + (hh / 2f) * c
                val left = dispX + bcx - ahw
                val top = dispY + bcy - ahh
                android.graphics.RectF(left, top, left + 2 * ahw, top + 2 * ahh)
            }
            else -> {
                // 像素/边界：控制窗 = 整只宠物显示矩形。尺寸必须用 getBaseBitmapSize()
                // （render/PetStageView 实际绘制窗口），不可用 getBitmapSize()（全局最大帧），
                // 否则控制窗尺寸与显示矩形不符、触摸坐标映射错位。
                val (w, h) = getBaseBitmapSize()
                android.graphics.RectF(dispX, dispY, dispX + w, dispY + h)
            }
        }
    }

    /**
     * 控制窗调试轮廓（屏幕绝对坐标）：供 PetStageView 调试层绘制控制边框。
     * 几何与控制层严格同源：CORE 保留旋转 OBB（中心/半宽半高/旋转角，与 isCoreHit 一致），
     * 其余返回整帧 AABB。绝对坐标 = 显示窗左上(dispX,dispY) + 本地中心，独立于显示窗绘制。
     */
    data class ControlWindowOBB(val cx: Float, val cy: Float, val hw: Float, val hh: Float, val rot: Float)

    fun getControlWindowOBB(): ControlWindowOBB? {
        val (ax, ay) = getBaseAnchorScaled()
        val dispX = physics.x - ax
        val dispY = physics.y - ay
        return when (hitMode) {
            ConfigDefaults.HIT_CORE -> {
                // coreBoxCenterDisplayLocal 返回显示窗本地坐标，转绝对需 + dispX/dispY。
                // ctrlBoxWidth/Height 为全宽/全高（128 基数 × scaleFactor = 屏幕像素），
                // OBB 的 hw/hh 取半宽/半高，这样矩形中心在脚上方 (hh/2+vo)、底贴脚、整体在脚以上。
                val (bcx, bcy) = coreBoxCenterDisplayLocal()
                val hw = ctrlBoxWidth * scaleFactor / 2f
                val hh = ctrlBoxHeight * scaleFactor / 2f
                val rot = if (physics.isSnapped) snapRotation else gravityRotation
                val effRot = if (imageManager.getMode() == ImageModeManager.ROLL) 0f else rot
                ControlWindowOBB(dispX + bcx, dispY + bcy, hw, hh, effRot)
            }
            else -> {
                val (w, h) = getBaseBitmapSize()
                ControlWindowOBB(dispX + w / 2f, dispY + h / 2f, w / 2f, h / 2f, 0f)
            }
        }
    }

    /** 核心脚底盒中心（显示窗口本地坐标）：本地系内脚锚点正上方 (hh/2+vo)、身体中轴居中，经 effRot 旋转。
     *  镜像：与 render 的 canvas.scale(-1,1,center) 同源——脚锚点 X 对称翻转，
     *  脚底盒以“可见脚 X”为身体中轴居中，故盒子中心 X 必须随 flip 取镜像值（snapFlipX/flipX）。
     *  注意：定位仍用非镜像 getBaseAnchorScaled（与全屏画布窗口同源），仅此处盒子中心 X 镜像感知。 */
    private fun coreBoxCenterDisplayLocal(): Pair<Float, Float> {
        // ctrlBox 为 128 逻辑基数，× scaleFactor（宠物大小）转屏幕像素，与脚锚点 fay*scaleFactor 同坐标系。
        val hw = ctrlBoxWidth * scaleFactor
        val hh = ctrlBoxHeight * scaleFactor
        val vo = ctrlBoxVOffset * scaleFactor
        val (w, h) = getBaseBitmapSize()
        val (fax, fay) = imageManager.currentAnchor()
        val dw = w
        // 镜像感知的脚锚点 X（与 getAnchorScaled 的翻转公式一致，但绕 getBaseBitmapSize 窗口中心）。
        val useFlip = if (physics.isSnapped) snapFlipX else flipX
        val offX = if (useFlip) {
            (w + dw) / 2f - fax * scaleFactor
        } else {
            (w - dw) / 2f + fax * scaleFactor
        }
        val offY = fay * scaleFactor
        val bcxLocal = offX
        val bcyLocal = offY - (hh / 2f + vo)
        val rot = if (physics.isSnapped) snapRotation else gravityRotation
        val effRot = if (imageManager.getMode() == ImageModeManager.ROLL) 0f else rot
        return rotateOffsetWith(bcxLocal, bcyLocal, w, h, effRot)
    }

    /** 核心模式命中判定：view 本地坐标 (vx,vy) 是否在脚底盒 OBB 内（与控制窗/可视化严格同源）。 */
    private fun isCoreHit(vx: Float, vy: Float): Boolean {
        // ctrlBox 为 128 逻辑基数，× scaleFactor（宠物大小）转屏幕像素，与可视化/控制窗 AABB 同源。
        val hw = ctrlBoxWidth * scaleFactor
        val hh = ctrlBoxHeight * scaleFactor
        val rot = if (physics.isSnapped) snapRotation else gravityRotation
        val effRot = if (imageManager.getMode() == ImageModeManager.ROLL) 0f else rot
        val rad = Math.toRadians(effRot.toDouble())
        val c = Math.abs(Math.cos(rad))
        val s = Math.abs(Math.sin(rad))
        val ahw = (hw / 2f) * c + (hh / 2f) * s
        val ahh = (hw / 2f) * s + (hh / 2f) * c
        val dx = vx - ahw
        val dy = vy - ahh
        val cos = Math.cos(rad).toFloat()
        val sin = Math.sin(rad).toFloat()
        // 逆变换 R(-θ)（与 render 的 canvas.rotate(θ) 一致），半长判定。
        val lx = dx * cos + dy * sin
        val ly = -dx * sin + dy * cos
        return Math.abs(lx) <= hw / 2f && Math.abs(ly) <= hh / 2f
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
        // 吸附朝向复位：snapRotation/snapFlipX 只在吸附态每帧刷新，退出后不再更新。
        // 必须显式归零——边界重算(recalcBoundsFor)以当前朝向计算包围盒，
        // 若残留吸附态旋转，召回后的 maxY 仍是“脚朝左/右/顶”包围盒的值，导致地面判断错误。
        snapRotation = 0f
        snapFlipX = false
    }

    /** 仅切回静坐 sit_clam（清除一次性动画，但保留吸附态/物理位置），用于重力·抛掷关闭时进入静坐。 */
    fun enterIdle() {
        imageManager.forceReset()
    }

    // 渲染输出（共享画布专用）：由 PetStageView 在同一 onDraw 内统一调用，
    // 使“位图 + 位置”在同一次提交中完成，消除浮窗几何(WMS) 与 View 绘制(RenderThread) 的 vsync 竞态。
    // 调用方负责 canvas.translate 到本宠物窗口左上角；本方法在本地 (0,0)-(width,height) 空间内绘制。
    fun render(canvas: Canvas) {
        // 使用与 isHitOnPet 完全相同的渲染状态，确保命中坐标系与显示严格一致。
        // 固定整帧尺寸（与控制窗实时尺寸无关）：用于调试矩形与对齐，CORE 模式控制窗缩小也不受影响。
        val (fw, fh) = getBaseBitmapSize()
        val rs = computeRenderState() ?: return
        val dw = rs.dw
        val dh = rs.dh
        val left = rs.left
        val top = rs.top
        canvas.save()
        // 重力朝向：绕窗口中心旋转画布，使宠物“脚朝重力方向”（含 sit 等静态动作）。
        // 旋转在当前帧中心进行，浮窗位置由 physics 决定、图仅绕自身中心转，姿态正确且不重排。
        // 吸附态以 snapSide 为唯一方向来源（snapRotation 已在 updateAnimByState 的 isSnapped
        // 分支按贴附边赋值），【不叠加】拖动期可能被体感污染的 gravityRotation，避免吸附后方向异常。
        // 注意：非吸附态使用 gravityRotation，不再读取/写入 snapRotation（旧实现会顺手 snapRotation=0f，
        // 属于 onDraw 副作用，已移除，避免与命中判定在同一帧读到不同中间状态）。
        if (rs.rotation != 0f) {
            // 所有动作（含 ROLL）统一绕【窗口/矩形框中心】旋转，与 rotateOffsetWith 默认一致。
            // ROLL 为球状对称图，居中填充使图中心=窗口中心，绕窗口中心旋转即图绕自身中心自转，
            // 视觉静止、浮窗不抖；锚点(脚)用真实值随旋转角在定位中同步更新（rotateOffsetWith 已含）。
            canvas.rotate(rs.rotation, rs.cx, rs.cy)
        }
        // 镜像：吸附态用专属 snapFlipX（反向随机、隔离全局 flipX）；非吸附态用全局 flipX。
        // 两者互斥（吸附态不读 flipX），退出吸附后 snapFlipX 不再应用，零状态残留。
        if (rs.flip) {
            // 水平镜像：以中心为轴翻转画布（对齐 PC transform_flag 左右随机朝向），叠加在重力旋转之上
            canvas.scale(-1f, 1f, rs.cx, rs.cy)
        }
        canvas.drawBitmap(rs.bmp, null,
            android.graphics.RectF(left, top, left + dw, top + dh), paint)
        canvas.restore()
        // ===== 显示边框：图片窗口黑细线描边 + 脚锚点十字（横线同宽、纵线同高，锚点处交叉）=====
        if (showImageBorder) {
            val bp = android.graphics.Paint().apply {
                color = 0xFF000000.toInt()
                style = android.graphics.Paint.Style.STROKE
                strokeWidth = 1.5f
            }
            // 图片窗口矩形（固定整帧，用于判断图片显示位置是否准确）
            canvas.drawRect(1.5f, 1.5f, fw - 1.5f, fh - 1.5f, bp)
            // 脚锚点（图片窗口内坐标）：横线同宽、纵线同高，交叉于锚点。
            // 用红白黑白红交替细线（由外到内 5 层叠绘）突出十字，便于在任意底色上辨识锚点。
            val (ax, ay) = getBaseAnchorScaled()
            val anchorPaints = listOf(
                0xFFFF0000.toInt() to 5f,   // 红(外)
                0xFFFFFFFF.toInt() to 4f,   // 白
                0xFF000000.toInt() to 3f,   // 黑
                0xFFFFFFFF.toInt() to 2f,   // 白
                0xFFFF0000.toInt() to 1f    // 红(内)
            ).map { (c, w) ->
                android.graphics.Paint().apply {
                    color = c
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = w
                    isAntiAlias = true
                }
            }
            for (p in anchorPaints) {
                canvas.drawLine(1.5f, ay, fw - 1.5f, ay, p)   // 横线贯穿整框宽
                canvas.drawLine(ax, 1.5f, ax, fh - 1.5f, p)   // 纵线贯穿整框高
            }
        }
        // ===== 控制边框已迁移到 PetStageView.onDraw（绝对坐标、按需绘制，独立于显示窗）=====
        // 此处不再绘制控制边框，避免借显示窗局部坐标导致 CORE 脚盒与显示窗错位。
    }

    // 本类作为“控制窗”时不自绘（绘制已上移到 PetStageView.render），仅作透明触摸目标。
    override fun onDraw(canvas: Canvas) {
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 像素级命中：透明区域恒定不可点击，直接放行穿透（return false 且不进入任何消费逻辑）。
        // 必须放在最前，避免 gestureDetector 或其它分支对透明区 DOWN 也产生消费。
        lastAction = when (event.action) {
            MotionEvent.ACTION_DOWN -> 0
            MotionEvent.ACTION_MOVE -> 1
            MotionEvent.ACTION_UP -> 2
            else -> -1
        }
        // 命中模式：像素(全框+alpha) / 边界(整窗) / 核心(脚底盒 OBB)。
        // 像素/边界窗口为整帧（全屏画布显示），核心窗口缩到脚盒（控制层几何，见 controlWindowRect）。
        when (hitMode) {
            ConfigDefaults.HIT_PIXEL -> {
                if (event.action == MotionEvent.ACTION_DOWN && !isHitOnPet(event.x, event.y)) {
                    hitDownConsumed = false // 透明起点：本次手势不消费，后续 MOVE/UP 忽略
                    invalidate() // 刷新绿/红边框显示
                    return false
                }
            }
            ConfigDefaults.HIT_CORE -> {
                if (event.action == MotionEvent.ACTION_DOWN) {
                    if (!isCoreHit(event.x, event.y)) {
                        hitDownConsumed = false // 脚盒外：穿透
                        invalidate()
                        return false
                    }
                    hitDownConsumed = true
                }
            }
            else -> { // HIT_BOUNDARY：整窗命中
                if (event.action == MotionEvent.ACTION_DOWN) hitDownConsumed = true
            }
        }
        gestureDetector.onTouchEvent(event)
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                hitDownConsumed = true // 命中起点：允许后续拖拽/提起
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
                // 守卫：透明起点（未命中）的手势，即使系统仍派发 MOVE 也不消费、不改位置
                if (!hitDownConsumed) return true
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
                    // 用户拖拽拉出：仅在吸附态→非吸附态跳变时归零残留并触发重算（避免拖拽中重复）。
                    // 残留参数(snapSide/snapRotation/snapFlipX)只在吸附态每帧刷新、退出后不再更新；
                    // getBaseAnchorScaled 的边界重算按"当前朝向包围盒"得出 minX/maxX，若不归零并
                    // 重算，活动范围会沿用吸附态(脚朝左/右/顶)的窄高盒，导致地面/活动范围判断偏差。
                    val wasSnapped = physics.isSnapped
                    physics.clearSnap()
                    if (wasSnapped) {
                        physics.snapSide = 0
                        snapRotation = 0f
                        snapFlipX = false
                        onSnapExit?.invoke()   // 通知外部按非吸附态重算活动范围
                    }

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
                // 守卫：透明起点（未命中）的手势，UP/CANCEL 不执行任何拖拽结束逻辑
                if (!hitDownConsumed) return true
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
