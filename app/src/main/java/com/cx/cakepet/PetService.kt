package com.cx.cakepet

import androidx.appcompat.app.AppCompatDelegate
import kotlin.random.Random
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.Display
import android.view.Surface
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 单个宠物实例的封装：每个实例拥有独立的浮窗 View、物理、碎碎念浮窗与各自位置/交互状态。
 * 共享设置页配置（由 PetService 遍历同步），但「当前动作 / 位置 / 拖拽 / 吸附 / 碎碎念」相互独立。
 */
data class PetInstance(
    val view: PetView,
    var layoutParams: WindowManager.LayoutParams,
    var petMsg: TrayMsgManager,
    var lastW: Int = -1,
    var lastH: Int = -1
)

/**
 * 宠物浮窗服务：ForegroundService + WindowManager + Handler 物理循环。
 * 对应 PC 端 pet_sesame_cake.py 的主循环，但安卓用 postDelayed 每帧驱动替代 Qt 事件循环。
 * 支持多实例（petCount 个宠物同时显示），各实例相互独立，共享设置页配置。
 */
class PetService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var petView: PetView   // 主实例 pets[0].view 的别名，供既有主实例逻辑使用
    private lateinit var layoutParams: WindowManager.LayoutParams  // 主实例 pets[0] 的别名
    private lateinit var config: PetConfig

    // 多实例：所有宠物实例集合。pets[0] 为主实例（调试浮层 / 菜单动作 / 体感方向基准）。
    private val pets = mutableListOf<PetInstance>()

    // 上次生效的宠物数量，用于检测配置中 petCount 变化并触发重建
    private var lastPetCount = 1

    // 共享显示画布（单个全屏、固定、NOT_TOUCHABLE 窗口）：统一绘制所有宠物，
    // 消除“浮窗几何(WMS) vs View 绘制(RenderThread)”的 vsync 竞态；触摸由各自控制窗承接。
    private var stageView: PetStageView? = null
    private var stageParams: WindowManager.LayoutParams? = null

    // 碎碎念多行错位步长（像素）：开启多宠物时，第 i 个实例的碎碎念距屏幕底偏移 = 设置偏移 + i * TRAY_ROW_STEP，
    // 即第一个用设置的位置、第二个在上一行、第三个在上两行……互不重叠。
    private val TRAY_ROW_STEP = 40f

    // 长按弹出的浮窗菜单（TYPE_APPLICATION_OVERLAY），不进入 app task
    private var petMenu: PetMenu? = null

    // 调试信息浮层（左下角）
    private var debugView: android.widget.TextView? = null
    private var debugParams: WindowManager.LayoutParams? = null
    private var showDebug = false
    private var showImageBorder = false
    private var showControlBorder = false

    // 当前前台应用包名（由无障碍服务 CakePetAccessibilityService 回调写入；未授权/未开启时为 null）。
    // 仅用于调试浮窗展示，不参与任何交互逻辑。
    var currentForegroundApp: String? = null
        private set
    // 调试：在屏幕叠加「边界偏移线」（复用设置页边界辅助线，四边黑色虚线）。来自 showBoundOffset。
    private var showBoundOffsetFlag = false
    // 调试：在屏幕叠加「吸附边界」（复用设置页吸附辅助线，含边界线 + 吸附距离内缩框，完整）。来自 showSnapOffset。
    private var showSnapOffsetFlag = false
    // 调试辅助线固定 owner：与设置页 owner 区分，引用计数独立，避免与设置页「显示边界/吸附」互相干扰。
    private val DEBUG_BOUND_OWNER = Any()
    private val DEBUG_SNAP_OWNER = Any()
    // 四边边界偏移（像素）：正=向屏内收缩（宠物离屏边有间距），负=允许超出屏边，范围 -200~300
    private var boundOffsetTop = 0f
    private var boundOffsetBottom = 0f
    private var boundOffsetLeft = 0f
    private var boundOffsetRight = 0f

    // 碎碎念：屏幕底部居中的独立浮窗 TextView，水平居中、6dp 字体、不受宠物旋转/缩放/底部偏移影响
    private var trayMsgManager: TrayMsgManager? = null
    private var thinkingEnabled = false
    private var thinkingOffsetY = 0f   // 碎碎念距屏幕底部的偏移（像素），与 trayMsgManager 同步，供辅助线计算
    // 仅单个碎碎念：开启=仅主实例(index 0)显示；关闭=每个实例各自显示（关于页开关，默认开）
    private var singleTrayMsg = ConfigDefaults.SINGLE_TRAY_MSG
    // 资源包：当前是否“使用新资源”（关于页开关映射）及已构建的新包缓存（assets 扫描仅一次）。
    private var useNewSpriteFlag = false
    private var zhimasuSpritePack: SpritePack? = null

    // 设置页调整“边界偏移/碎碎念偏移”时显示的黑色虚线辅助层。
    // 【不再建独立窗口】：并入 stageView 绘制（见 PetStageView.guideView），避免 Android 16 上
    // “两个全屏 overlay 叠加导致触摸穿透失效、吞掉整屏点击”的问题。
    private var guideLineView: GuideLineView? = null

    // 吸附判定线【不再有独立 View/窗口】：每帧由主循环算出线段列表交给 stageView 绘制
    // （见 PetStageView.setSnapSegments），仅在吸附态且开关开启时有内容。
    // 是否显示吸附线（来自 PetConfig.showSnapLine）
    private var showSnapLineFlag = true

    // 体感重力（倾斜重力）：由手机重力传感器决定重力方向。无需权限。
    // 重力强度复用 config.gravity，倾斜越大等效重力越大。
    private var sensorManager: SensorManager? = null
    private var gravitySensor: Sensor? = null
    private var tiltGravityEnabled = false
    private var tiltStrength = 3000f   // 与设置页“重力强度”联动，作为传感器分量→加速度的缩放基准
    // 屏幕旋转：仅在主线程（注册时 / 配置变更时）读取并缓存，避免传感器后台线程访问 display 引发异常
    private var cachedRotation = Surface.ROTATION_0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var running = false
    // 退出标志：置位后 onStartCommand 返回 NOT_STICKY，阻止前台服务被 START_STICKY 重建。
    // 同时持久化到 SharedPreferences，因为内存标志会随进程被杀而丢失（详见 exitPrefs）。
    private var isExiting = false

    // 退出意图持久化：进程被杀后仍需记得“用户已退出”，避免被重新拉起时复活。
    private val exitPrefs by lazy { getSharedPreferences("cakepet_exit", MODE_PRIVATE) }
    private fun markExiting() { isExiting = true; exitPrefs.edit().putBoolean("exiting", true).apply() }
    // 同步落盘版本：在即将 killProcess 前使用，确保退出意图不丢失。
    private fun markExitingCommit() { isExiting = true; exitPrefs.edit().putBoolean("exiting", true).commit() }
    private fun clearExiting() { isExiting = false; exitPrefs.edit().putBoolean("exiting", false).apply() }
    private fun loadExiting() { isExiting = exitPrefs.getBoolean("exiting", false) }
    private var lastFrameTime = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    // 驱动间隔：对齐 error/show（已验证不卡）的 postDelayed 方案。
    // 16ms ≈ 60fps 甜点；比原 roll 的 8ms 更不易把主线程/overlay 合成压死导致掉到极低频。
    private val FRAME_DELAY = 16L
    // 物理固定步长：对齐 PC follow_update_interval=3ms，与屏幕刷新解耦，
    // 保证位移平滑连续（不随显示帧率波动，sub-pixel 余数在 physics 内累积）。
    private val PHYS_STEP_MS = 3L
    private var physAccumulator = 0L
    private var lastW = -1
    private var lastH = -1

    // 输入法（软键盘）高度（像素）：唤起键盘时抬高宠物地面，避免遮挡输入框；关闭时归零恢复。
    // 两个来源取较大值：view 级（onImeInsetChanged）+ 全局 currentWindowMetrics（主，跨机型可靠）。
    private var viewImeInset = 0f
    private var globalImeInset = 0f
    private var lastImeCheck = 0L
    // 输入法适应开关：关闭后不再因键盘弹出抬高宠物地面（imeAdapt=false 时不生效）。
    private var imeAdaptEnabled = true
    // 抬高偏移（px）：键盘抬高后的宠物地面再额外修正（正=更高）。仅 imeAdapt 生效时叠加。
    private var imeLiftOffset = 100f
    // 键盘适应-重置偏移：键盘弹出时无视上/下边界偏移，仅用键盘高度+抬高偏移决定活动下边界
    private var imeResetBottomOffset = false
    // 键盘时隐藏：键盘弹出时暂时隐藏「酥」且不可点击（不修改 visible 设置），键盘收起恢复
    private var imeHideEnabled = false
    // 当前软键盘是否处于弹出态（由 applyImeInset 维护），用于 imeHide 的瞬态显隐
    private var keyboardUp = false
    // 配置决定的宠物「应可见」终态（c.visible && c.enabled），imeHide 恢复时回写
    private var effectiveVisible = true

    // 可见区（扣除系统栏/手势条的绝对坐标矩形）
    private var lastVisRect = android.graphics.Rect(0, 0, 0, 0)

    private val frameRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.uptimeMillis()
            var dt = (now - lastFrameTime) / 1000f
            if (dt <= 0) dt = 1f / 60f
            if (dt > 0.1f) dt = 0.1f   // 限制大跳变（如息屏恢复）
            lastFrameTime = now
            val dtMs = (dt * 1000f).toLong().coerceAtLeast(1)

            // 检测输入法（软键盘）显隐（每 200ms 一次，开销极低）：唤起键盘则抬高宠物地面，
            // 关闭则恢复。全局 currentWindowMetrics 的 ime inset 跨机型可靠，不依赖浮窗焦点。
            if (now - lastImeCheck > 200) {
                lastImeCheck = now
                val imeH = getImeHeight().toFloat()
                if (imeH != globalImeInset) {
                    globalImeInset = imeH
                    applyImeInset()
                }
            }

            // 物理推进：以固定步长（对齐 PC 3ms）累积，与显示帧率解耦，保证位移平滑连续。
            // 多实例：每个实例独立推进物理（各自位置/速度/吸附/动画）。
            physAccumulator += dtMs
            var physGuard = 0
            while (physAccumulator >= PHYS_STEP_MS && physGuard++ < 200) {
                for (inst in pets) inst.view.stepPhysics(PHYS_STEP_MS / 1000f)
                physAccumulator -= PHYS_STEP_MS
            }
            // 动画推进：用真实 dt（时延精确），绝不控制位置。每实例独立。
            for (inst in pets) inst.view.tick(dt)
            // 吸附态：snapRotation 由 updateAnimByState 按贴附边赋值，边界必须用同一旋转重算，
            // 否则 snapTo 落点/钳制基于旧的重力态边界，浮窗与旋转后的图片错位。
            // 仅吸附态每帧轻量重算（仅几次加减），非吸附态边界已随配置/缩放变化触发，无需每帧。
            for (inst in pets) if (inst.view.physics.isSnapped) recalcBoundsFor(inst.view, getScreenBounds())
            // 单一位置写入点：由物理循环统一把锚点坐标换算成浮窗左上角。
            // 坐标语义：physics.x/y = 锚点在屏幕的坐标（对齐 PC image_meta），
            // 浮窗左上 = 锚点 - 锚点相对位图偏移。钳制已在 physics.setBounds 内完成。
            // 必须用 getBaseAnchorScaled()：与 recalcBounds 的边界计算（getBaseBitmapSize/getBaseAnchorScaled）
            // 完全同源。若改用 getAnchorScaled()，其窗口尺寸取自统一画布 (globalW×globalH)，
            // 与 recalcBounds 用的 sit 基准尺寸 (baseFrameW/H) 不一致，导致贴左/右/顶时浮窗错位
            // （矩形框紧贴偏移线、或离偏移线约两倍差值）。
            // 主实例尺寸 (w,h) 同时用于调试浮层/吸附线绘制。
            val v0 = petView
            val nx = v0.physics.x
            val ny = v0.physics.y
            // 主实例整帧尺寸（调试浮层/吸附线仍按整只宠物绘制，与命中模式无关）。
            // 必须用 getBaseBitmapSize()：与 recalcBounds/getBaseAnchorScaled（基准帧 baseFrameW/H）
            // 及 render/PetStageView 实际绘制窗口完全同源；getBitmapSize()(globalW) 与之不符会导致控制窗尺寸错位。
            val (w, h) = v0.getBaseBitmapSize()
            if (v0.hitMode == ConfigDefaults.HIT_CORE) {
                // 核心(脚底盒)：控制窗缩到脚盒（控制层几何，见 PetView.controlWindowRect），
                // 与全屏画布里的显示矩形无关；脚盒外区域由 isCoreHit 的 OBB 判定放穿。
                val r = v0.controlWindowRect()
                layoutParams.x = r.left.toInt()
                layoutParams.y = r.top.toInt()
                val rw = r.width().toInt()
                val rh = r.height().toInt()
                if (rw != lastW || rh != lastH) {
                    layoutParams.width = rw
                    layoutParams.height = rh
                    lastW = rw
                    lastH = rh
                }
            } else {
                // 像素/边界：窗口为整张位图全框（锚点落在 physics 坐标）。
                val (ax, ay) = v0.getBaseAnchorScaled()
                layoutParams.x = (nx - ax).toInt()
                layoutParams.y = (ny - ay).toInt()
                if (w != lastW || h != lastH) {
                    layoutParams.width = w
                    layoutParams.height = h
                    lastW = w
                    lastH = h
                }
            }
            try {
                windowManager.updateViewLayout(petView, layoutParams)
            } catch (_: Exception) {
            }
            // 其余实例：各自写位置 + 尺寸 + 刷新（不依赖主实例 lastW/lastH）。
            for (i in 1 until pets.size) {
                val inst = pets[i]
                val v = inst.view
                val nx = v.physics.x
                val ny = v.physics.y
                val ilp = inst.layoutParams
                if (v.hitMode == ConfigDefaults.HIT_CORE) {
                    // 核心：控制窗缩到脚盒（控制层几何），与显示矩形无关。
                    val r = v.controlWindowRect()
                    ilp.x = r.left.toInt()
                    ilp.y = r.top.toInt()
                    val rw = r.width().toInt()
                    val rh = r.height().toInt()
                    if (rw != inst.lastW || rh != inst.lastH) {
                        ilp.width = rw
                        ilp.height = rh
                        inst.lastW = rw
                        inst.lastH = rh
                    }
                } else {
                    val (iax, iay) = v.getBaseAnchorScaled()
                    ilp.x = (nx - iax).toInt()
                    ilp.y = (ny - iay).toInt()
                    val (iw, ih) = v.getBaseBitmapSize()
                    if (iw != inst.lastW || ih != inst.lastH) {
                        ilp.width = iw
                        ilp.height = ih
                        inst.lastW = iw
                        inst.lastH = ih
                    }
                }
                try {
                    windowManager.updateViewLayout(v, ilp)
                } catch (_: Exception) {
                }
            }

            // 每帧强制重绘：让物理位置与位图每帧都画出来。
            // 原 roll 版删掉了 invalidate，改靠“尺寸变化才改 layoutParams”间接触发 onDraw，
            // 但同尺寸相邻帧（Roll 序列很常见）不会触发重绘 → 位置算出来了屏幕却半秒才画一次 → 卡顿。
            // 共享画布：每帧仅重绘一次舞台（所有宠物在其中统一绘制）；
            // 控制窗(PetView) 本身不再自绘，故不再逐个 invalidate。
            stageView?.invalidate()

            // 吸附判定线：仅当【处于吸附态】且【吸附判定线开关(showSnapLine)开启】时显示。
            // 虚拟地面 = 用户在设置页拖动时看到的边界偏移线（boundLines），直接复用其坐标，
            // 位置与设置页偏移线完全一致；与宠物图片/帧锚点无关。
            // 遍历【所有实例】各算一段（修复：旧实现只驱动主实例，多宠物时其它宠物不显示）。
            // 只绘制「需要的长度」：水平线限宠物浮窗宽、竖线限宠物浮窗高。
            val snapSegs = ArrayList<PetStageView.SnapSeg>()
            if (showSnapLineFlag) {
                val vis = getScreenBounds()
                val bound = boundLines(
                    vis, boundOffsetTop, boundOffsetBottom, boundOffsetLeft, boundOffsetRight
                )
                for (inst in pets) {
                    val v = inst.view
                    val p = v.physics
                    if (!p.isSnapped) continue
                    // 该实例贴附边对应的那条边界偏移线（boundLines 顺序：0左,1右,2上,3下）。
                    // snapSide：0=底,1=左,2=右,3=顶。
                    val edge = when (p.snapSide) {
                        0 -> bound[3]   // 底 → 下缘
                        1 -> bound[0]   // 左 → 左缘
                        2 -> bound[1]   // 右 → 右缘
                        else -> bound[2] // 3=顶 → 上缘
                    }
                    // 该实例浮窗屏幕矩形：与上方帧循环同源（浮窗左上 = 锚点 - 锚点偏移），
                    // 尺寸取 getBaseBitmapSize()（与定位/绘制窗口同源；getBitmapSize()(globalW) 与之不符会裁错）。
                    val (iax, iay) = v.getBaseAnchorScaled()
                    val (iw, ih) = v.getBaseBitmapSize()
                    clipSnapSeg(edge, p.x - iax, p.y - iay, iw.toFloat(), ih.toFloat())
                        ?.let { snapSegs.add(it) }
                }
            }
            // 空列表 = 不绘制（未吸附 / 开关关闭），无需维护可见性或窗口状态。
            stageView?.setSnapSegments(snapSegs)

            if (showDebug) updateDebug(w, h)

            mainHandler.postDelayed(this, FRAME_DELAY)
        }
    }

    // 调试数字格式化：符号占 1 列（负号为'-'，正数为空格），数字 4 位、前导空格填充，
    // 总宽固定 5，避免数字位数变化（如 0↔100）导致调试浮窗宽度来回跳动。
    // 例：-1234 / " 1234" / "   12" / "    0"
    private fun fmt(n: Float): String = "% 5.0f".format(n)

    // 调试标签固定宽度左对齐：统一占 8 列，使各行冒号后的内容垂直对齐（避免标签长短不一难看）。
    // 最长标签 "center:" 恰为 8 字符，其余补空格。

    private fun updateDebug(w: Int, h: Int) {
        val dv = debugView ?: return
        val p = petView.physics
        val left = layoutParams.x.toFloat()
        val top = layoutParams.y.toFloat()
        val right = left + w
        val bottom = top + h
        val cx = left + w / 2f
        val cy = top + h / 2f
        val L = { s: String -> "%-8s".format(s) }  // 标签固定 8 列左对齐
        dv.text = buildString {
            append("${L("mode:")}${petView.currentModeName()}\n")
            append("${L("center:")}(${fmt(cx)}, ${fmt(cy)})\n")
            append("${L("v:")}(${fmt(p.vx)}, ${fmt(p.vy)})\n")
            append("${L("TL:")}(${fmt(left)}, ${fmt(top)})\n")
            append("${L("TR:")}(${fmt(right)}, ${fmt(top)})\n")
            append("${L("BR:")}(${fmt(right)}, ${fmt(bottom)})\n")
            append("${L("BL:")}(${fmt(left)}, ${fmt(bottom)})\n")
            // 当前前台应用包名（来自无障碍服务监听；未开启无障碍时为 null 显示 “-”）。
            val fg = currentForegroundApp ?: "-"
            append("${L("app:")}$fg")
        }
    }

    /**
     * 由 CakePetAccessibilityService 在检测到前台应用变化时回调，更新调试浮窗展示的包名。
     * 该回调与帧循环解耦：前台切换是低频事件，无需每帧刷新调试文本，仅记录最新值，
     * 由下一帧 updateDebug 自然带上。直接更新字段即可，无需额外 post/invalidate。
     */
    fun onForegroundAppChanged(packageName: String) {
        currentForegroundApp = packageName
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> pause()
                Intent.ACTION_SCREEN_ON -> resume()
                Intent.ACTION_CONFIGURATION_CHANGED -> {
                    recalcBounds()
                    refreshCachedRotation()  // 旋转/折叠屏变化后更新体感重力方向基准
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // 进程被杀后重启：若用户此前主动退出，不初始化、不 startForeground，直接停止。
        loadExiting()
        if (isExiting) {
            stopSelf()
            return
        }
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        config = PetConfig(this)
        // 碎碎念独立浮窗由各宠物实例（PetInstance）各自持有一个，与服务生命周期绑定，
        // 主实例的 petMsg 别名存于 trayMsgManager，由 initPetView() 赋值。
        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        initPetView()
        loadConfig()
        // 夜间模式已在 CakePetApplication.onCreate 中根据“系统夜间状态 + 用户强制开关”
        // 最早应用（全局生效，覆盖 Service/浮窗/Activity），此处仅做补偿性同步。
        applyNightMode(config.getBlocking())
        registerConfigObserver()
        registerScreenReceiver()

        // 初始位置已在 initPetView 的 addView 前算好（底部中心），此处仅同步可见区并刷新边界。
        lastVisRect = getScreenBounds()
        recalcBounds()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_CONFIGURATION_CHANGED)
        }
        registerReceiver(screenReceiver, filter)

        startLoop()
    }

    private fun initPetView() {
        // 先创建主实例（index 0），它承载调试浮层（吸附线 / 调试文本 / 辅助线）与菜单动作基准。
        // 启动瞬间：数量上限限 3（关于页允许设到 10，但启动只生成最多 3，避免一次性过多；
        // 启动后用户改数量走 registerConfigObserver，不受此上限，下次启动仍从 3 起）。
        val count = config.getBlocking().petCount.coerceIn(1, 3)
        for (i in 0 until count) {
            val inst = createPetInstance(i)
            pets.add(inst)
            if (i == 0) {
                petView = inst.view
                layoutParams = inst.layoutParams
                trayMsgManager = inst.petMsg
            }
        }

        // 共享显示画布：单个全屏固定、NOT_TOUCHABLE 窗口，统一绘制所有宠物。
        // 宠物不再各自建显示窗，由本画布一次性提交（位图+位置同帧），消除几何 vs 绘制竞态。
        // 层级置于宠物控制窗 / 吸附线之上、调试与辅助线浮层之下。
        stageView = PetStageView(this).apply { pets = this@PetService.pets.map { it.view } }
        stageParams = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            gravity = Gravity.TOP or Gravity.START
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
        }
        windowManager.addView(stageView, stageParams)

        // 调试信息浮层（左下角，初始隐藏）—— 仅主实例对应一份
        debugView = android.widget.TextView(this).apply {
            setTextColor(0xFFFFFF00.toInt())
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE  // 等宽字体：配合固定位宽数字，避免窗口跳动
            setBackgroundColor(0x80000000.toInt())
            setPadding(8, 4, 8, 4)
            visibility = android.view.View.GONE
        }
        debugParams = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            gravity = Gravity.BOTTOM or Gravity.START
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
        }
        windowManager.addView(debugView, debugParams)

        // 辅助线层（初始隐藏）：设置页调整偏移时显示黑色虚线。
        // 关键：这是【纯绘制层，不是独立窗口】——直接挂给 stageView 由其 onDraw 绘制。
        // 若单独建全屏 TYPE_APPLICATION_OVERLAY 窗口，Android 16 上与 stageView 形成
        // “两个全屏窗口叠加”，即便带 FLAG_NOT_TOUCHABLE 也会吞掉整屏点击（A12 正常）。
        guideLineView = GuideLineView(this).apply {
            visibility = android.view.View.GONE
            isClickable = false
            isFocusable = false
            isFocusableInTouchMode = false
        }
        stageView?.guideView = guideLineView
        // 记录已创建的实例数量（与 initPetView 生成数口径一致），避免 registerConfigObserver
        // 首个配置事件因 petCount 与初始值不同而立即重建
        lastPetCount = config.getBlocking().petCount.coerceIn(1, 10)
    }

    // 创建第 index 个宠物实例：独立 View / 物理 / 碎碎念 / 位置。主实例额外挂吸附线浮层。
    private fun createPetInstance(index: Int): PetInstance {
        val view = PetView(this)
        val lp = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            gravity = Gravity.TOP or Gravity.START
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
        }
        val petMsg = TrayMsgManager(this)

        view.onLongPress = { showMenu() }
        view.onDoubleTap = {
            // 摸头（双击）：吸附态下播放“吸附探头摸摸头”，结束后自动切回吸附探头；非吸附态播放普通摸头。
            if (view.physics.isSnapped) {
                view.playOnce(ImageModeManager.SNAP_PAT_HEAD)
            } else {
                view.playOnce(ImageModeManager.PAT_HEAD)
            }
            // 摸头（双击）：碎碎念显示摸头文案，1-3s 后继续轮播池
            if (thinkingEnabled) {
                petMsg.showPat()
            }
        }
        view.onSizeChanged = { recalcBounds() }
        // 拖拽拉出吸附态：PetView 归零残留朝向后会回调，按非吸附态重算活动范围（minX/maxX 等）
        view.onSnapExit = { recalcBounds() }
        // 输入法（键盘）显隐：view 级补充来源；与全局检测取较大值
        view.onImeInsetChanged = { h ->
            viewImeInset = h.toFloat()
            applyImeInset()
        }
        view.onPositionChanged = { cx, cy ->
            // 回调传的是锚点坐标（手指 + 抓取偏移）。拖拽期间物理 step 不跑，
            // 直接更新锚点并立即 clamp + 写出浮窗位置（跟手，且单一数据源 physics.x/y）。
            // 拖拽跟手：用 getBaseAnchorScaled() 与 recalcBounds 同源（非吸附态返回 sit 基准，
            // 与边界 clamp 一致，避免拖到边缘时浮窗与偏移线错位）。
            val (ax, ay) = view.getBaseAnchorScaled()
            view.physics.x = cx
            view.physics.y = cy
            view.physics.clampToBounds()
            lp.x = (view.physics.x - ax).toInt()
            lp.y = (view.physics.y - ay).toInt()
            try { windowManager.updateViewLayout(view, lp) } catch (_: Exception) {}
        }
        // 提起（拖拽）开始/结束：驱动本实例碎碎念“提起”持续文案
        view.onDragStateChanged = { dragging ->
            if (thinkingEnabled) {
                if (dragging) {
                    petMsg.showLift()
                } else {
                    petMsg.clearLift()
                }
            }
        }
        // 真正落地（拖拽/抛掷触底，进入 JUMP_DOWN 动画那一刻）：显示落地文案，之后先空白再轮播
        view.onLand = {
            if (thinkingEnabled) petMsg.showLand()
        }

        // 关键：addView 之前先把浮窗位置算到底部中心，避免先出现在左上角(0,0)再闪回底部。
        // 多个实例在水平方向轻微错开，避免初始完全重叠（各自物理独立，会自行散开）。
        val vis = getScreenBounds()
        val (w, h) = view.getBaseBitmapSize()
        val (ax, ay) = view.getBaseAnchorScaled()
        val baseX = (vis.left + ax + (vis.right - (w - ax))) / 2f + index * 12f
        val baseY = vis.bottom.toFloat() - (h - ay)
        view.physics.x = baseX
        view.physics.y = baseY
        if (view.hitMode == ConfigDefaults.HIT_CORE) {
            // 核心：控制窗缩到脚盒（控制层几何），与显示矩形无关。
            val r = view.controlWindowRect()
            lp.x = r.left.toInt()
            lp.y = r.top.toInt()
            lp.width = r.width().toInt()
            lp.height = r.height().toInt()
        } else {
            lp.x = (baseX - ax).toInt()
            lp.y = (baseY - ay).toInt()
            lp.width = w
            lp.height = h
        }

        // 注：吸附判定线不再随实例创建（它不属于某个宠物实例，而是 stageView 的绘制内容）。

        windowManager.addView(view, lp)
        return PetInstance(
            view = view,
            layoutParams = lp,
            petMsg = petMsg
        )
    }

    // 变更宠物数量（酥的数量）：移除所有现有实例与碎碎念，按新数量重建。
    // 重建后重新应用共享配置，各实例独立（位置/动作/吸附/碎碎念内容）随机散开。
    // 接收最新配置 c（来自 configFlow），避免重建时重新读 DataStore 造成竞态/重复重建。
    private fun rebuildPets(c: PetConfigData) {
        // 吸附判定线由主循环每帧重算，与宠物实例无关，无需在此清理。
        // 移除所有宠物实例浮窗与各自碎碎念浮窗
        for (inst in pets) {
            try { windowManager.removeView(inst.view) } catch (_: Exception) {}
            try { inst.petMsg.destroy() } catch (_: Exception) {}
        }
        pets.clear()
        val count = c.petCount.coerceIn(1, 10)
        for (i in 0 until count) {
            val inst = createPetInstance(i)
            pets.add(inst)
            if (i == 0) {
                petView = inst.view
                layoutParams = inst.layoutParams
                trayMsgManager = inst.petMsg
            }
        }
        // 重新应用当前共享配置（物理参数/缩放/碎碎念样式/点击穿透等）+ 边界
        applySharedConfig(c)
        // 修复 bug#3：重建后按当前资源开关重应用资源包，避免改数量时退回默认(旧)资源
        if (useNewSpriteFlag) {
            val pack = spritePackFor(true)
            pets.forEach { it.view.applySpritePack(pack) }
        }
        // 重建后舞台需指向新的 PetView 实例（旧控制窗已 removeView）
        stageView?.pets = pets.map { it.view }
        recalcBounds()
    }

    // 将共享配置同步到所有宠物实例（物理参数、缩放、命中模式、碎碎念样式/可见性、点击穿透等）。
    // 各实例的「当前动作 / 位置 / 拖拽 / 吸附 / 碎碎念内容」相互独立，不在此同步。
    // 碎碎念位置按实例序号错开（第 i 个 = 设置偏移 + i * TRAY_ROW_STEP），避免多宠物时重叠。
    private fun applySharedConfig(c: PetConfigData) {
        pets.forEachIndexed { index, inst ->
            val v = inst.view
            v.setConfig(c.scale, c.gravity, c.reboundRatio,
                c.gravityTop, c.gravityBottom, c.gravityLeft, c.gravityRight,
                c.reboundTop, c.reboundBottom, c.reboundLeft, c.reboundRight,
                c.gravityEnabled, c.maxSpeed)
            v.useStaticPool = !c.gravityEnabled   // 重力·抛掷关闭 -> 静止池
            v.setPetScale(c.scale)
            v.showImageBorder = showImageBorder
            v.showControlBorder = showControlBorder
            v.bounceVibrateEnabled = c.bounceVibrate
            v.hitMode = c.hitMode
            v.ctrlBoxWidth = c.ctrlBoxWidth
            v.ctrlBoxHeight = c.ctrlBoxHeight
            v.ctrlBoxVOffset = c.ctrlBoxVOffset
            v.alpha = c.alpha.coerceIn(0.1f, 1f)   // 透明度是 View 实例属性，需逐实例设置
            v.visibility = if (c.visible && c.enabled) android.view.View.VISIBLE else android.view.View.GONE
            v.invalidate()
            // 碎碎念：每个实例独立浮窗，可见性/样式跟随共享配置，但位置按序号错开。
            // 仅单个碎碎念（默认开）：开启时仅主实例(index 0)显示，其余实例隐藏；关闭则每个实例各自显示。
            val showForThis = thinkingEnabled && (index == 0 || !singleTrayMsg)
            inst.petMsg.setEnabled(showForThis)
            inst.petMsg.setOffsetY(c.thinkingOffset + index * TRAY_ROW_STEP)
            inst.petMsg.applyStyle(
                c.thinkingTextSize, c.thinkingAlpha, c.thinkingColor,
                c.thinkingEmptyMin, c.thinkingEmptyMax,
                c.thinkingFlashIn, c.thinkingFlashOut
            )
            inst.petMsg.applyBackground(c.thinkingBgEnabled, c.thinkingBgColor, c.thinkingBgAlpha)
            inst.petMsg.setVisible(showForThis && c.visible && c.enabled)
        }
        applyImeHide(keyboardUp)
        applyClickThrough(c.clickThrough)
    }

    /** 根据开关选择资源包：默认=旧图；开启=“芝麻酥”新图（assets 扫描仅一次并缓存）。 */
    private fun spritePackFor(useNew: Boolean): SpritePack =
        if (useNew) (zhimasuSpritePack ?: ImageModeManager.zhimasuPack(this).also { zhimasuSpritePack = it })
        else ImageModeManager.DEFAULT_SPRITE_PACK

    private fun loadConfig() {
        config.normalizeBlocking() // 启动时清洗历史残留的非法四边重力组合
        val c = config.getBlocking()
        showDebug = c.showDebug
        showImageBorder = c.showImageBorder
        showControlBorder = c.showControlBorder
        showSnapLineFlag = c.showSnapLine
        showBoundOffsetFlag = c.showBoundOffset
        showSnapOffsetFlag = c.showSnapOffset
        applyDebugGuides()
        boundOffsetTop = c.offsetTop.coerceIn(-300f, 2100f)
        boundOffsetBottom = c.offsetBottom.coerceIn(-300f, 2100f)
        boundOffsetLeft = c.offsetLeft.coerceIn(-300f, 1000f)
        boundOffsetRight = c.offsetRight.coerceIn(-300f, 1000f)
        debugView?.visibility = if (showDebug) android.view.View.VISIBLE else android.view.View.GONE
        thinkingEnabled = c.thinkingEnabled
        thinkingOffsetY = c.thinkingOffset
        singleTrayMsg = c.singleTrayMsg
        // 碎碎念：独立浮窗，可见性跟随宠物（visible && enabled），按实例错开高度
        applySharedConfig(c)
        // 资源包：根据“使用新资源”开关初始化（默认关=旧图）。pets 已在 initPetView 创建完毕。
        useNewSpriteFlag = c.useNewSprite
        if (useNewSpriteFlag) {
            val pack = spritePackFor(true)
            pets.forEach { it.view.applySpritePack(pack) }
        }
        // 体感重力：同步开关与强度，并按需启停传感器。
        // 有效体感 = 体感开关开启 且 重力·抛掷总开关开启；重力关时体感整体失效
        // （不注册传感器、不量化方向、不触发 roll），但【不改动】tiltGravity 存储值，
        // 开关 UI 显示保持原值，开启重力后体感自动恢复生效。
        tiltStrength = c.gravity
        val effectiveTilt = c.tiltGravity && c.gravityEnabled
        petView.physics.tiltGravity = effectiveTilt
        updateTiltGravity(effectiveTilt)
        // 重力朝向：非体感模式按四边开关算宠物脚朝向；体感模式初始朝下(0)，
        // 实际朝向由 tiltListener 每帧根据重力向量量化（左右/上主导时临时旋转脚朝左右/上）。
        // 重力朝向：重力·抛掷关闭时强制脚朝正下(0)，与四边开关/体感无关（吸附态仍独立保留朝向）。
        petView.gravityDir = if (c.gravityEnabled) (if (effectiveTilt) 0 else calcGravityDir(c)) else 0
        recalcBounds()
    }

    private fun registerConfigObserver() {
        config.configFlow.onEach { c ->
            // 宠物数量（酥的数量）变化：重建所有实例（各实例独立散开，共享配置重新应用）。
            if (c.petCount != lastPetCount) {
                lastPetCount = c.petCount
                rebuildPets(c)
            }
            // 物理参数同步：遍历所有实例（共享配置，各实例物理独立生效）。
            pets.forEach { inst ->
                val p = inst.view.physics
                p.gravity = c.gravity
                p.reboundRatio = c.reboundRatio
                p.gravityTop = c.gravityTop
                p.gravityBottom = c.gravityBottom
                p.gravityLeft = c.gravityLeft
                p.gravityRight = c.gravityRight
                p.reboundTop = c.reboundTop
                p.reboundBottom = c.reboundBottom
                p.reboundLeft = c.reboundLeft
                p.reboundRight = c.reboundRight
                p.snapTop = c.snapTop
                p.snapBottom = c.snapBottom
                p.snapLeft = c.snapLeft
                p.snapRight = c.snapRight
                p.snapEnabled = c.snapEnabled   // 吸附边缘总开关：关闭则完全不吸附
                p.reboundEnabled = c.reboundEnabled   // 反弹总开关：关闭则所有碰撞反弹失效（撞墙停靠、不振动）
                p.snapDist = c.snapThreshold   // 吸附判定距离阈值（像素）
                p.gravityEnabled = c.gravityEnabled
                p.maxSpeed = c.maxSpeed
                inst.view.useStaticPool = !c.gravityEnabled   // 重力·抛掷关闭 -> 静止池
            }
            // 重力·抛掷关闭（吸附态除外）时，立刻清零速度并复位抛掷态，使物理停止位移。
            // 覆盖所有来源：设置页/菜单页手动关闭、随机模式改关等。
            // 注意：这里只清物理状态，不调 enterIdle()/cancelForced()——避免误杀菜单正在播放的
            // 非移动一次性动作（摸头/炸毛/摇头）。动画切回静坐由 PetView.updateAnimByState 每帧处理：
            // 当重力关且未播放一次性动作时才切 SIT_CLAM，播放中则保留动作（符合"仅播放非移动池"）。
            if (!c.gravityEnabled) {
                pets.forEach { inst ->
                    val p = inst.view.physics
                    if (!p.isSnapped) {
                        p.vx = 0f
                        p.vy = 0f
                        p.isThrowing = false   // 复位抛掷态，避免 updateAnimByState 抢回 ROLL
                        p.isDragging = false   // 复位拖拽态，避免关重力后 LIFT_UP 残留
                    }
                }
            }
            boundOffsetTop = c.offsetTop
            boundOffsetBottom = c.offsetBottom
            boundOffsetLeft = c.offsetLeft
            boundOffsetRight = c.offsetRight
            imeAdaptEnabled = c.imeAdapt
            imeLiftOffset = c.imeLiftOffset
            imeResetBottomOffset = c.imeResetBottomOffset
            imeHideEnabled = c.imeHide
            effectiveVisible = c.visible && c.enabled
            showDebug = c.showDebug
            showImageBorder = c.showImageBorder
            showControlBorder = c.showControlBorder
            showSnapLineFlag = c.showSnapLine
            showBoundOffsetFlag = c.showBoundOffset
            showSnapOffsetFlag = c.showSnapOffset
            applyDebugGuides()
            debugView?.visibility = if (showDebug) android.view.View.VISIBLE else android.view.View.GONE
            petView.invalidate()
            // 碎碎念：开关变化才重启轮播；其余共享配置（样式/可见性/错开高度）走 applySharedConfig
            if (c.thinkingEnabled != thinkingEnabled) {
                thinkingEnabled = c.thinkingEnabled
            }
            singleTrayMsg = c.singleTrayMsg
            thinkingOffsetY = c.thinkingOffset
            applySharedConfig(c)
            // 资源包切换：仅当开关变化时才重建 ImageModeManager 并复位到静坐（低频，可接受短暂停顿）。
            if (c.useNewSprite != useNewSpriteFlag) {
                useNewSpriteFlag = c.useNewSprite
                val pack = spritePackFor(useNewSpriteFlag)
                pets.forEach { it.view.applySpritePack(pack) }
            }
            // 体感重力：强度随设置页“重力强度”联动；开关变化启停传感器。
            // 有效体感 = 体感开关开启 且 重力·抛掷总开关开启；重力关时体感整体失效，
            // 但 tiltGravity 存储值不变，开关 UI 显示保持原值。
            tiltStrength = c.gravity
            val effectiveTilt = c.tiltGravity && c.gravityEnabled
            petView.physics.tiltGravity = effectiveTilt
            updateTiltGravity(effectiveTilt)
            // 重力朝向：重力·抛掷关闭时强制脚朝正下(0)，与四边开关/体感无关（吸附态仍独立保留朝向）。
            // 注意：必须用 c.gravityEnabled 单独守卫，不能只依赖 effectiveTilt（=tiltGravity && gravityEnabled），
            // 否则关重力时 effectiveTilt=false 会落入下方 calcGravityDir 分支，用仍残留的旧四边方向算出
            // 非下方向并触发 enterTiltRoll，造成“先错误方向、再被 updateAnimByState 强制 0 纠正”的闪烁。
            if (c.gravityEnabled) {
                if (effectiveTilt) {
                    petView.gravityDir = 0
                } else {
                    // 吸附态：忽略脚朝向突变触发的 ROLL（等待用户手动拉出/召回才退出吸附）。
                    if (!petView.physics.isSnapped) {
                        val target = calcGravityDir(c)
                        // 脚朝向突变：主动进 ROLL 掩盖旧模式（非对称）帧被新旋转角硬转一帧的闪，
                        // 对齐体感 updateTiltGravityDir 行为。先切 ROLL（居中对称）→ 再在 enterTiltRoll
                        // 内部 setMode 后设方向，下一帧即 ROLL+新朝向，无闪烁。方向未变（如改其他设置）不进 ROLL。
                        if (target != petView.gravityDir) {
                            if (!petView.physics.isThrowing) petView.enterTiltRoll(target)
                            else petView.gravityDir = target   // 翻滚中仅更新方向，不重复抢 ROLL
                        }
                    }
                }
            } else {
                // 关重力：无条件脚朝正下(0)，且跳过方向突变 ROLL（updateAnimByState 每帧也会兜底纠正）。
                if (!petView.physics.isSnapped) {
                    petView.gravityDir = 0
                }
            }
            recalcBounds()  // 偏移/scale 变化需重算边界（固定基准，不会每帧抖动；与方向检测解耦，无条件重算）
            // 夜间模式强制开关变化：即时重应用（强制打开优先级 > 强制关闭 > 跟随系统）。
            applyNightMode(c)
            if (!c.enabled) {
                pause()
            } else {
                resume()
            }
        }.launchIn(scope)
    }

    /**
     * 根据配置应用夜间模式：
     * - forceNightOn 优先（强制开）；
     * - 其次 forceNightOff（强制关）；
     * - 两者皆关则跟随系统（MODE_NIGHT_FOLLOW_SYSTEM）。
     * 需在 UI 线程调用（AppCompatDelegate.setDefaultNightMode 要求）。
     */
    private fun applyNightMode(c: PetConfigData) {
        val mode = when {
            c.forceNightOn -> AppCompatDelegate.MODE_NIGHT_YES
            c.forceNightOff -> AppCompatDelegate.MODE_NIGHT_NO
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    private fun registerScreenReceiver() { /* 已在 onCreate 注册 */ }

    // ===== 体感重力（倾斜重力）=====
    // 开启后由手机重力传感器决定重力方向（覆盖四边定向重力，保留边界反弹）。
    // 重力强度复用设置页“重力强度”，倾斜越大等效重力越大。无需任何权限。
    // 体感专属增益：默认 3000 重力强度下原映射偏弱，乘此增益让小幅倾斜也明显受力。
    private val TILT_GAIN = 2.5f
    // 体感下根据传感器重力向量量化“脚朝向”的阈值：
    //   TILT_DIR_RATIO：左右分量占主导的比例（>0.6 即左右比上下大 50% 才算“远大于”），
    //     用于决定临时把宠物旋转到脚朝左/右（影响 sit/walk/蠕动/扒鱼等姿态与位移方向）。
    //   TILT_DIR_MIN_TOTAL：左右+上下分量合成加速度低于此值（平放/近水平）时不切方向，
    //     保持当前朝向，避免传感器噪声在边界反复横跳。按当前 k 缩放，与重力强度联动。
    private val TILT_DIR_RATIO = 0.6f
    private val TILT_DIR_MIN_TOTAL_RATIO = 0.06f

    /**
     * 由四边重力开关计算宠物“脚朝向”方向（重力朝向）：
     * 0=下(默认) 1=左 2=右 3=上。四边已互斥（左右/上下不同时开），
     * 故至多一个水平 + 一个垂直组合；夹角时侧向优先（站墙更突出）。
     * 仅用于非体感模式：体感重力方向由传感器连续决定，不在此映射。
     */
    private fun calcGravityDir(c: PetConfigData): Int {
        // 脚方向优先级：垂直(下/上) > 侧向(左/右)。多重力组合（如左+下、上+右）时，
        // 优先让脚朝向垂直方向（更自然，避免侧向压过上下）。同组(下/上、左/右)已互斥，顺序无关。
        return when {
            c.gravityBottom -> 0   // 下
            c.gravityTop -> 3      // 上
            c.gravityLeft -> 1     // 左
            c.gravityRight -> 2    // 右
            else -> 0
        }
    }
    private val tiltListener = object : SensorEventListener {
        private var dbgCount = 0
        override fun onSensorChanged(event: SensorEvent) {
            try {
                val t = event.sensor.type
                if (t != Sensor.TYPE_GRAVITY && t != Sensor.TYPE_ACCELEROMETER) return
                // 设备坐标系重力向量（指向真实地面下方，约 9.8 m/s^2 量级）
                val gx = event.values[0]
                val gy = event.values[1]
                // 把设备坐标投影到【屏幕平面】：忽略 z（垂直屏幕），用 x/y 分量作为屏幕重力方向。
                // 屏幕旋转使用主线程缓存的 cachedRotation（传感器后台线程不访问 display，避免异常）。
                //  - 竖屏(ROTATION_0)：设备 x -> 屏幕 x（右），设备 y -> 屏幕 y（下，因 Android y 向下）
                //  - 横屏(ROTATION_90)：设备 y -> 屏幕 -x，设备 x -> 屏幕 y
                //  - 横屏反向(ROTATION_270)：设备 y -> 屏幕 x，设备 x -> 屏幕 -y
                //  - 翻转(ROTATION_180)：设备 x -> 屏幕 -x，设备 y -> 屏幕 -y
                // Android 屏幕坐标系 y 轴向下为正，故“向下倾斜”应产生 +y 重力（宠物向下走），
                // 而传感器 gy 在手机顶部抬起（向后倾）时为负——符合直觉：手机前倾→宠物向下。
                val (sx, sy) = when (cachedRotation) {
                    Surface.ROTATION_90 -> Pair(-gy, gx)
                    Surface.ROTATION_270 -> Pair(gy, -gx)
                    Surface.ROTATION_180 -> Pair(-gx, -gy)
                    else -> Pair(-gx, gy) // ROTATION_0：竖屏下设备 x 轴符号与屏幕一致，实测需取反使左倾→向左重力
                }
                // 归一：传感器标称约 9.8。倾斜分量 / 9.8 得 [0,1] 倾斜比例，再乘以设置重力强度，
                // 得到屏幕坐标系下的加速度（像素/秒^2）。平放时 sx,sy≈0 -> 宠物几乎不受力。
                // TILT_GAIN：体感专属增益。默认 3000 重力强度下原映射偏弱（小幅倾斜分量小），
                // 乘增益让体感更跟手；该增益只作用于体感，不改变四边重力的 gravity 语义。
                val k = tiltStrength / 9.8f * TILT_GAIN
                pets.forEach { inst ->
                    inst.view.physics.tiltGx = sx * k
                    inst.view.physics.tiltGy = sy * k
                }
                if (dbgCount < 5) {
                    dbgCount++
                    Log.d("CakePetTilt", "sensor gx=$gx gy=$gy rot=$cachedRotation sx=$sx sy=$sy k=$k -> tiltGx=${petView.physics.tiltGx} tiltGy=${petView.physics.tiltGy}")
                }
                // 由重力向量量化“脚朝向”：左右分量远大于上下时脚朝左右，上分量主导时脚朝上，
                // 其余（下主导/平衡/平放）脚朝下。旋转会同步影响渲染姿态与 walk/蠕动/扒鱼位移方向，
                // 并在切换时重算边界（窗口为正方形，旋转后尺寸不变，仅锚点偏移随 rotateOffset 变化）。
                updateTiltGravityDir(k)
            } catch (e: Throwable) {
                // 兜底：任何异常都不应导致 App 闪退，仅打印日志便于定位
                Log.e("CakePetTilt", "onSensorChanged error", e)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    // 主线程读取并缓存当前屏幕旋转（注册时与配置变更时调用，传感器线程只读缓存）
    private fun refreshCachedRotation() {
        cachedRotation = try {
            // Service 不是 visual Context，不能调 getDisplay()（会抛 UnsupportedOperationException）。
            // 改用 WindowManager 取 defaultDisplay，它在 Service 中可用。
            @Suppress("DEPRECATION")
            val rotation = (getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
                ?.defaultDisplay?.rotation
            rotation ?: Surface.ROTATION_0
        } catch (e: Throwable) {
            Log.e("CakePetTilt", "refreshCachedRotation error", e)
            Surface.ROTATION_0
        }
    }

    // 启停体感重力：开启时注册传感器监听并强制关闭四边定向重力（避免两套重力叠加），
    // 关闭时注销监听并清零向量。
    private fun updateTiltGravity(enabled: Boolean) {
        tiltGravityEnabled = enabled
        Log.d("CakePetTilt", "updateTiltGravity called enabled=$enabled")
        try {
            if (enabled) {
                if (sensorManager == null) {
                    sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
                    gravitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
                }
                // 缓存屏幕旋转（主线程安全）
                refreshCachedRotation()
                // 优先用重力传感器；部分设备/模拟器无此传感器，fallback 到加速度计
                // （静止倾斜时加速度计读数即重力分量，桌宠场景足够）。
                if (gravitySensor == null) {
                    gravitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                }
                Log.d("CakePetTilt", "updateTiltGravity(true) gravityEnabled=${petView.physics.gravityEnabled} sensorType=${gravitySensor?.type} strength=$tiltStrength")
                // 体感模式下置 tiltGravity=enabled（即有效体感，已含 gravityEnabled 判断），
                // 【不改动】四边定向重力的存储值与物理字段，关闭体感后四边开关原值自动恢复生效。
                // step() 已用 if (gravityEnabled) 与 if (tiltGravity) 分支优先体感，四边值仅被忽略不被使用。
                // 体感方向是共享配置，广播给所有宠物实例。
                pets.forEach { inst -> inst.view.physics.tiltGravity = enabled }
                // 设备无重力传感器时不注册，避免异常
                if (gravitySensor != null) {
                    sensorManager?.registerListener(
                        tiltListener, gravitySensor, SensorManager.SENSOR_DELAY_GAME
                    )
                } else {
                    Log.w("CakePetTilt", "TYPE_GRAVITY sensor not available")
                }
            } else {
                sensorManager?.unregisterListener(tiltListener)
                pets.forEach { inst ->
                    inst.view.physics.let { p ->
                        p.tiltGravity = false
                        p.tiltGx = 0f
                        p.tiltGy = 0f
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e("CakePetTilt", "updateTiltGravity error", e)
        }
    }

    /**
     * 体感下根据当前重力向量（tiltGx/tiltGy）量化宠物“脚朝向” gravityDir：
     *   - 左右分量远大于上下（|tiltGx| 占比 > TILT_DIR_RATIO）：脚朝左右（gx>0 右 / gx<0 左），
     *     临时旋转宠物，使其脚指向屏幕左右，并影响 walk/蠕动/扒鱼等位移方向。
     *   - 否则若上分量主导（tiltGy < 0）：脚朝上（手机倒扣/上倾）。
     *   - 其余（下主导 / 平衡 / 平放）：脚朝下（默认）。
     * 带滞回：仅当方向真变化且合成加速度足够大时才切换并重算边界，避免传感器噪声抖动。
     * 窗口为正方形，旋转后尺寸不变，仅锚点偏移随 rotateOffset 变化，recalcBounds 即可对齐贴边。
     * 注意：仅体感开启时调用（由 tiltListener 每帧触发），非体感方向由 calcGravityDir 离散决定。
     */
    private fun updateTiltGravityDir(k: Float) {
        if (!tiltGravityEnabled) return
        // 体感方向是共享配置，广播给所有宠物实例（各实例独立按自身状态决定翻滚/重算边界）。
        pets.forEach { inst -> updateTiltGravityDirFor(inst.view, k) }
    }

    private fun updateTiltGravityDirFor(view: PetView, k: Float) {
        // 吸附态：忽略体感重力方向变化（不滚、不重算边界），等待用户手动拉出/召回才退出。
        if (view.physics.isSnapped) return
        // 重力·抛掷关闭时强制脚朝正下(0)，不随体感量化改朝向（onEach 已设 0）。
        if (!view.physics.gravityEnabled) return
        val gx = view.physics.tiltGx
        val gy = view.physics.tiltGy
        val magX = kotlin.math.abs(gx)
        val magY = kotlin.math.abs(gy)
        val total = magX + magY
        // 平放/近水平（合成加速度极小）：不切方向，保持当前朝向，防抖。
        if (total < TILT_DIR_MIN_TOTAL_RATIO * k) return
        val normX = magX / total
        val target = when {
            normX > TILT_DIR_RATIO -> if (gx > 0) 2 else 1   // 左右主导 -> 脚朝右/左
            gy < 0 -> 3                                       // 上主导 -> 脚朝上
            else -> 0                                         // 下主导/平衡 -> 脚朝下
        }
        if (target != view.gravityDir) {
            if (view.physics.isDragging) {
                // 提起态：直接更新脚朝向（=主重力方向），提起动画 LIFT_UP 实时旋转，
                // 不触发翻滚（roll 为独立逻辑，此处不动）。否则 enterTiltRoll 在拖动态会被
                // isDragging 守卫直接 return，导致 gravityDir 始终不更新、提起动画不旋转。
                view.gravityDir = target
                recalcBounds()
            } else if (!view.physics.isThrowing) {
                // 脚朝向突变：主动进入翻滚掩盖姿态硬切（方向变了=有新重力，翻滚符合物理直觉）。
                // 翻滚中(!isThrowing)才触发，避免方向高频变化时重复抢 ROLL 动画；
                // 翻滚期间再变只更新 gravityDir（姿态随 ROLL 自然转）。
                // 关键顺序：先 enterTiltRoll（切到居中对称的 ROLL 帧），再在 ROLL 内部设 gravityDir，
                // 避免先改重力旋转角导致【旧模式帧被新朝向硬转一帧】闪现，然后才切到 ROLL。
                view.enterTiltRoll(target)
                // 旋转改变脚落点，重算边界使锚点活动范围与贴边/贴地对齐（开销极小）。
                // ROLL 锚点已特判为画布中心，rotateOffset 与 gravityDir 无关，顺序安全。
                recalcBounds()
            } else {
                view.gravityDir = target
                recalcBounds()
            }
        }
    }

    // 所有宠物实例边界相同（共享屏幕范围与偏移配置），遍历重算。
    private fun recalcBounds() {
        val vis = getScreenBounds()
        lastVisRect = vis
        pets.forEach { inst -> recalcBoundsFor(inst.view, vis) }
    }

    // 为单个宠物实例计算并设置边界（探头/出界模型，详见 recalcBounds 旧注释）。
    private fun recalcBoundsFor(view: PetView, vis: android.graphics.Rect) {
        // physics.x/y 为【锚点（脚）坐标】。
        // 边界模型（探头/出界）：脚朝向的那一侧为【锚点边】——只约束“锚点(脚)不超可见区”，
        // 图底可越过屏幕外（被裁切=探头）；其余三侧仍为【PNG 四边】约束（图边不超可见区）。
        // 脚朝向：吸附态=snapSide(0底/1左/2右/3顶)，非吸附态=gravityDir(0下/1左/2右/3上)。
        // 例：脚朝下(0)→底部锚点边，maxY=屏幕底（脚贴底、图底下垂出界）；sit 同理脚贴边、图底透明区垂下。
        // 用旋转后包围盒：图片绕窗口中心旋转 rotDeg 后，水平半跨度=halfW、垂直半跨度=halfH；
        // 锚点到旋转后图片四缘的内缩 = halfW±acx / halfH±acy（acx=ax-w/2, acy=ay-h/2）。
        //   rotDeg=0 时退化为 ax,w-ax,ay,h-ay（与原公式等价）。
        // 浮窗定位 layoutParams = physics ∓ getBaseAnchorScaled（同源，含旋转），锚点精确落在 physics 坐标。
        // 【重要】边界锚点与渲染 getAnchorScaled 必须同源（都走 currentAnchor），否则探头错位/抵消。
        // 尺寸用固定基准 getBaseBitmapSize（globalMax×scale），不随帧变 → 不抖动/不横跳。
        val (w, h) = view.getBaseBitmapSize()
        val (ax, ay) = view.getBaseAnchorScaled()
        val rotDeg = if (view.physics.isSnapped) view.snapRotation else view.gravityRotation
        val (halfW, halfH) = view.getRotatedHalfExtents(w, h, rotDeg)
        val acx = ax - w / 2f
        val acy = ay - h / 2f
        // 脚朝向：吸附态=snapSide(0底/1左/2右/3顶)，非吸附态=gravityDir(0下/1左/2右/3上)。
        // 脚朝向的那一侧为【锚点边】：只约束“锚点(脚)不超可见区”，图底可越过屏幕外（探头/出界）；
        // 其余三侧仍为【PNG 四边】约束（图边不超可见区）。这样 sit 脚贴边、SNAP 脚贴边且身体下垂出界、
        // 未来任何动作按各自脚锚点贴边，互不干扰。
        val footDir = if (view.physics.isSnapped) view.physics.snapSide else view.gravityDir
        // 锚点→旋转后图片四缘的内缩（图边不超界时用的距离）
        val leftInset = halfW + acx   // 锚点→图左缘
        val rightInset = halfW - acx  // 锚点→图右缘
        val topInset = halfH + acy    // 锚点→图上缘
        val botInset = halfH - acy    // 锚点→图下缘
        // 锚点边清零内缩：让“脚”贴边而非“图边”贴边（图可出界）
        val leftIsAnchor = footDir == 1   // 脚朝左 → 左侧锚点边
        val rightIsAnchor = footDir == 2  // 脚朝右 → 右侧锚点边
        val topIsAnchor = footDir == 3    // 脚朝上 → 顶部锚点边
        val botIsAnchor = footDir == 0    // 脚朝下 → 底部锚点边
        val minX = vis.left.toFloat() + (if (leftIsAnchor) 0f else leftInset) + boundOffsetLeft
        val maxX = (vis.right.toFloat() - (if (rightIsAnchor) 0f else rightInset) - boundOffsetRight).coerceAtLeast(minX)
        // 仅当“输入法防误触=开”且键盘真实弹出（存在输入法内缩）时，底部才内缩，宠物落到键盘上方。
        val imeActive = imeAdaptEnabled && (viewImeInset > 0f || globalImeInset > 0f)
        val imeBottomInset = if (imeActive) maxOf(viewImeInset, globalImeInset) else 0f
        // 抬高偏移：仅在输入法防误触开启且键盘真实弹出时，才在键盘地面之上额外抬高，正=更高、负=更低。
        val lift = if (imeActive) imeLiftOffset else 0f
        // 键盘适应-重置偏移：键盘弹出时无视上/下边界偏移（boundOffsetTop/Bottom 归零），
        // 仅用键盘高度(imeBottomInset) + 抬高偏移(lift) 决定活动下边界，避免高底部偏移导致键盘弹起时宠物进一步位移。
        val imeResetActive = imeResetBottomOffset && imeActive
        val topOff = if (imeResetActive) 0f else boundOffsetTop
        val botOff = if (imeResetActive) 0f else boundOffsetBottom
        // 顶部为锚点边 → minY 直接 = 屏幕顶（脚贴顶，图顶可出界）；否则图顶不超屏幕顶
        val minY = vis.top.toFloat() + (if (topIsAnchor) 0f else topInset) + topOff
        // 底部为锚点边 → maxY 直接 = 屏幕底（脚贴底，图底可出界下垂）；否则图底不超屏幕底
        val maxY = (vis.bottom.toFloat() - (if (botIsAnchor) 0f else botInset) - botOff - imeBottomInset - lift).coerceAtLeast(minY)
        view.physics.setBounds(minX, minY, maxX, maxY)
    }

    /**
     * 记录正在请求辅助线的来源（弱引用，避免持有 Activity/View 造成泄漏）。
     * 值表示该来源最后一次请求的类型，用于在仍有其它来源存活时重绘正确内容。
     */
    private val guideOwners =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<Any, Boolean>())
    private val guideOwnerKinds = java.util.WeakHashMap<Any, GuideKind>()
    private enum class GuideKind { BOUND, TRAY, SNAP, IME, SIZE, REBOUND, SPEED, GRAVITY }

    /** 当前绘制中的辅助线即时参数（未提供的边回落到 PetService 中的配置字段）。 */
    private var boundGuideOverride: FloatArray? = null  // [top, bottom, left, right]
    private var trayGuideOverride: Float? = null
    private var snapGuideOverride: Float? = null        // 吸附阈值即时值
    private var imeLiftOverride: Float? = null          // 抬高偏移即时值（键盘预览）
    private var activeBoundIndex: Int = -1   // 当前正拖动的边界线索引（0左/1右/2上/3下，-1无），用于备注三层边框高亮
    private var emptyGuideWasEmpty: Boolean = false  // 上一次合围区域是否为空，用于振动跳变检测
    private var petWarnActiveKind: GuideKind? = null  // 当前红框归属的参数种类，用于红框↔振动跳变边沿检测
    // 吸附区域环带（SNAP 模式）：outer=活动范围大矩形，inner=吸附合围小矩形，empty=合围为空（全吸附）
    private var snapOuterRect: RectF? = null
    private var snapInnerRect: RectF? = null
    private var snapRingEmpty: Boolean = false
    private var snapWarnSpec: Pair<String, String>? = null  // 吸附距离调整红框三态文案（msg,sub）
    private var snapEmptyWasEmpty: Boolean = false  // 上一次吸附合围是否为空，用于振动跳变检测

    /**
     * 调试辅助线：根据 showBoundOffset / showSnapOffset 开关，复用设置页「显示边界 / 显示吸附」
     * 的同一套辅助线绘制（boundLines + 吸附内缩框 snapRing），使用独立 owner 与设置页互不干扰。
     * - 边界开关：仅叠加四边「边界偏移线」（BOUND 种类）。
     * - 吸附开关：叠加完整「吸附边界」（边界线 + 吸附距离内缩框，SNAP 种类）。
     * 两个可同时开启，drawCurrentGuide 会合并所有存活 owner 的线。
     */
    private fun applyDebugGuides() {
        if (showBoundOffsetFlag) showBoundGuide(DEBUG_BOUND_OWNER) else releaseGuide(DEBUG_BOUND_OWNER)
        if (showSnapOffsetFlag) showSnapGuide(DEBUG_SNAP_OWNER) else releaseGuide(DEBUG_SNAP_OWNER)
        drawCurrentGuide()
    }

    /**
     * 设置页辅助线：根据四边边界偏移，在屏幕对应边缘位置绘制黑色虚线。
     *
     * 四个偏移均可传入**即时值**（拖动中手指当前值）。不传则回落到服务内缓存的配置值。
     * 之所以支持即时值：配置写盘是异步 IO，若等 DataStore 回流再画线，
     * 辅助线会滞后于手指几十毫秒，快速拖动时明显跟不上。
     *
     * @param owner 请求来源，用于引用计数；同一来源重复调用只算一次
     */
    fun showBoundGuide(
        owner: Any,
        top: Float? = null, bottom: Float? = null,
        left: Float? = null, right: Float? = null,
        activeSide: Int = -1,
        imeLift: Float? = null
    ) {
        guideOwners.add(owner)
        if (imeLift != null) {
            // 键盘抬高预览来源：与边界滑块走完全相同的引用计数体系。
            guideOwnerKinds[owner] = GuideKind.IME
            imeLiftOverride = imeLift
        } else {
            guideOwnerKinds[owner] = GuideKind.BOUND
            // 记录当前正在拖动的边（0左/1右/2上/3下，与 boundLines 顺序一致），用于备注高亮
            activeBoundIndex = activeSide
            // 合并即时值：未提供的边沿用上次即时值，都没有则回落配置字段
            val prev = boundGuideOverride
            boundGuideOverride = floatArrayOf(
                top ?: prev?.get(0) ?: boundOffsetTop,
                bottom ?: prev?.get(1) ?: boundOffsetBottom,
                left ?: prev?.get(2) ?: boundOffsetLeft,
                right ?: prev?.get(3) ?: boundOffsetRight
            )
        }
        drawCurrentGuide()
    }

    /**
     * 设置页辅助线：根据碎碎念偏移，绘制文字**上下两条**水平虚线（文字顶边与底边）。
     *
     * 碎碎念浮窗以 Gravity.BOTTOM 定位，窗口底边 = 屏幕底边 - 偏移；
     * 顶边 = 底边 - 文字高度。两条线之间的区域即文字实际占位，便于直观判断间距与遮挡。
     *
     * @param owner 请求来源，用于引用计数
     * @param offset 即时偏移值（不传则用服务内缓存值）
     */
    fun showTrayGuide(owner: Any, offset: Float? = null) {
        guideOwners.add(owner)
        guideOwnerKinds[owner] = GuideKind.TRAY
        trayGuideOverride = offset ?: trayGuideOverride ?: thinkingOffsetY
        drawCurrentGuide()
    }

    /**
     * 设置页辅助线：吸附判定范围线。
     *
     * 吸附判定（PetPhysics.nearestSnapSide）用的是锚点坐标：
     *   底：y >= maxY - snapDist    顶：y <= minY + snapDist
     *   左：x <= minX + snapDist    右：x >= maxX - snapDist
     * 换算到屏幕即：宠物进入「边界线内侧 snapDist」的框内就会吸附。
     * 因此在四条边界线内侧 snapDist 处各画一条线，形成内缩矩形标示吸附触发范围。
     *
     * @param owner 请求来源，用于引用计数
     * @param threshold 即时阈值（不传则用服务内缓存值）
     */
    fun showSnapGuide(owner: Any, threshold: Float? = null) {
        guideOwners.add(owner)
        guideOwnerKinds[owner] = GuideKind.SNAP
        snapGuideOverride = threshold ?: snapGuideOverride ?: petView?.physics?.snapDist
            ?: DEFAULT_SNAP_DIST
        drawCurrentGuide()
    }

    /**
     * 宠物参数滑块辅助线：拖动「大小 / 反弹系数 / 最大速度 / 重力强度」滑块时显示，
     * 用于触发对应参数的越界红框彩蛋 + 振动（红框与振动成对出现）。
     * 该类辅助线不绘制偏移线，仅作为红框/振动的“当前正在调整哪个参数”的上下文。
     *
     * 关键：红框只在【正在调整该参数】时出现，而不是任何辅助线都触发——
     * 例如调大小才出大小红框，调反弹才出反弹红框，调边界偏移时绝不串味。
     *
     * @param kind 当前拖动的参数种类字符串（"size" / "rebound" / "speed" / "gravity"），
     *            对外用字符串以屏蔽内部 GuideKind 可见性。
     */
    fun showPetParamGuide(owner: Any, kind: String) {
        val k = when (kind) {
            "size" -> GuideKind.SIZE
            "rebound" -> GuideKind.REBOUND
            "speed" -> GuideKind.SPEED
            "gravity" -> GuideKind.GRAVITY
            else -> error("showPetParamGuide 不支持的参数种类: $kind")
        }
        guideOwners.add(owner)
        guideOwnerKinds[owner] = k
        drawCurrentGuide()
    }

    /**
     * 用户手动点击吸附边缘开关时，显示 1s 的吸附距离示意图（含环带/全吸附红底）。
     * 仅此路径会在「全吸附（吸附合围为空）」时振动一次；启动、重置、默认值设置均不触发。
     *
     * 显示期间若全吸附，则振动一次并红底提示；1s 后整体隐藏辅助线。
     * 若 1s 内再次点击其它开关，计时重置（取消旧回调、重新计时）。
     *
     * [side] 为本次点击的方向，[on] 为点击后的目标开关值。其余方向沿用当前物理状态。
     * 通过 snapPrev* 覆盖字段，使绘制立即反映“修改后”方向，不受 config 异步同步影响。
     */
    private val snapPreviewHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var snapPreviewRunnable: Runnable? = null
    // 手动预览期间各方向的开关覆盖（null=未覆盖，沿用当前物理状态）
    private var snapPrevTop: Boolean? = null
    private var snapPrevBottom: Boolean? = null
    private var snapPrevLeft: Boolean? = null
    private var snapPrevRight: Boolean? = null

    enum class SnapSide { TOP, BOTTOM, LEFT, RIGHT }

    fun showSnapPreviewOnce(owner: Any, side: SnapSide? = null, on: Boolean = false) {
        guideOwners.add(owner)
        guideOwnerKinds[owner] = GuideKind.SNAP
        snapGuideOverride = snapGuideOverride ?: petView?.physics?.snapDist ?: DEFAULT_SNAP_DIST
        if (side != null) {
            when (side) {
                SnapSide.TOP -> snapPrevTop = on
                SnapSide.BOTTOM -> snapPrevBottom = on
                SnapSide.LEFT -> snapPrevLeft = on
                SnapSide.RIGHT -> snapPrevRight = on
            }
        }
        drawCurrentGuide()
        // 全吸附振动由 drawCurrentGuide 的统一跳变检测处理（覆盖拖拽与点击），此处不再重复振动
        // 重置 1s 计时
        snapPreviewRunnable?.let { snapPreviewHandler.removeCallbacks(it) }
        snapPreviewRunnable = Runnable {
            // 清理覆盖，恢复物理真实状态
            snapPrevTop = null; snapPrevBottom = null; snapPrevLeft = null; snapPrevRight = null
            hideGuide()
            snapPreviewRunnable = null
        }
        snapPreviewHandler.postDelayed(snapPreviewRunnable!!, 1000L)
    }

    /**
     * 释放某个来源的辅助线请求。仅当所有来源都释放后才真正隐藏，
     * 避免多指同时拖动多个滑块时，先松手的那个把别人还在用的辅助线一起关掉。
     */
    fun releaseGuide(owner: Any) {
        guideOwners.remove(owner)
        val kind = guideOwnerKinds.remove(owner)
        if (kind == GuideKind.IME) imeLiftOverride = null
        // 边界滑块（含吸附 SNAP）松手即视为「本次拖动会话结束」：
        // 重置无范围振动标记，使下次再进入无范围能重新振动。
        // 仅在该 owner 是边界类、且移除后无剩余边界类来源时才解锁，避免与别的边界源互相干扰。
        val isBoundKind = kind == GuideKind.BOUND || kind == GuideKind.SNAP
        if (isBoundKind && !guideOwnerKinds.values.any { it == GuideKind.BOUND || it == GuideKind.SNAP }) {
            emptyGuideWasEmpty = false
        }
        if (guideOwners.isEmpty()) {
            // 无人再请求：清空即时值并隐藏
            boundGuideOverride = null
            trayGuideOverride = null
            snapGuideOverride = null
            imeLiftOverride = null
            activeBoundIndex = -1
            guideLineView?.setLines(emptyList())
            guideLineView?.visibility = android.view.View.GONE
        } else {
            // 仍有来源存活：按其中一个来源的类型重绘，避免残留错误的线
            drawCurrentGuide()
        }
    }

    /** 强制隐藏辅助线（页面销毁兜底，直接清空全部请求者） */
    fun hideGuide() {
        snapPreviewRunnable?.let { snapPreviewHandler.removeCallbacks(it) }
        snapPreviewRunnable = null
        snapPrevTop = null; snapPrevBottom = null; snapPrevLeft = null; snapPrevRight = null
        guideOwners.clear()
        guideOwnerKinds.clear()
        boundGuideOverride = null
        trayGuideOverride = null
        snapGuideOverride = null
        imeLiftOverride = null
        activeBoundIndex = -1
        emptyGuideWasEmpty = false
        snapOuterRect = null
        snapInnerRect = null
        snapRingEmpty = false
        snapEmptyWasEmpty = false
        guideLineView?.setLines(emptyList())
        guideLineView?.visibility = android.view.View.GONE
        // 隐藏全部辅助线后，按当前调试开关状态恢复“调试模块”的线（边界偏移线/吸附边界）。
        // 否则设置页/碎碎念页返回时 hideGuide() 会把 DEBUG 调试 owner 一并清空，
        // 导致已开启的偏移线/边界等被强制关闭。
        applyDebugGuides()
    }

    /** 合围区域为零时振动一次（兼容不同 Android 版本的 Vibrator API） */
    private fun vibrateEmpty() {
        val vm = getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator ?: return
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            vm.vibrate(android.os.VibrationEffect.createOneShot(120, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vm.vibrate(120)
        }
    }

    /**
     * 按当前即时值/配置值重绘辅助线。
     * 支持多个来源（设置页 + 调试开关）同时存活：遍历所有 owner 并合并其线。
     * - 各 owner 按自身 kind 产出线；SNAP 即「完整吸附边界」（边界线 + 吸附内缩框），
     *   已含边界线，故存在 SNAP owner 时 BOUND owner 的边界线不再重复叠加。
     * - TRAY（碎碎念）与边框类可共存，此时边框文字备注置空（TRAY 无边框概念）。
     */
    private fun drawCurrentGuide() {
        if (guideOwners.isEmpty()) return
        val vis = getScreenBounds()
        val hasSnap = guideOwnerKinds.values.any { it == GuideKind.SNAP }
        val hasBoundOnly = guideOwnerKinds.values.any { it == GuideKind.BOUND }
        val hasTray = guideOwnerKinds.values.any { it == GuideKind.TRAY }
        val hasIme = guideOwnerKinds.values.any { it == GuideKind.IME }

        val lines = mutableListOf<GuideLineView.Line>()
        // 合围「活动范围」是否为零（边界线围成矩形宽/高 ≤ 0）
        var empty = false
        // 收集边框类（BOUND / SNAP）owner 的线（SNAP 已含边界，避免 BOUND 重复叠加）
        // 各方向吸附开关（顺序：左、右、上、下），供 setSnapRing 逐侧绘制使用；默认开启
        var leftOn = true; var rightOn = true; var topOn = true; var botOn = true
        if (hasSnap) {
            // 同时画出「边界偏移线」与「吸附范围内缩框」：
            // 只有内缩框时无法判断它相对屏幕边缘的位置，缺少参照；叠加边界线才有空间概念。
            val o = boundGuideOverride
            val top = o?.get(0) ?: boundOffsetTop
            val bottom = o?.get(1) ?: boundOffsetBottom
            val left = o?.get(2) ?: boundOffsetLeft
            val right = o?.get(3) ?: boundOffsetRight
            val phys = petView?.physics
            val d = snapGuideOverride ?: phys?.snapDist ?: DEFAULT_SNAP_DIST
            topOn = snapPrevTop ?: phys?.snapTop ?: true
            botOn = snapPrevBottom ?: phys?.snapBottom ?: true
            leftOn = snapPrevLeft ?: phys?.snapLeft ?: true
            rightOn = snapPrevRight ?: phys?.snapRight ?: true
            val dt = if (topOn) d else 0f
            val db = if (botOn) d else 0f
            val dl = if (leftOn) d else 0f
            val dr = if (rightOn) d else 0f
            val bound = boundLines(vis, top, bottom, left, right)
            val topIn = vis.top + top + dt
            val bottomIn = vis.bottom - bottom - db
            val leftIn = vis.left + left + dl
            val rightIn = vis.right - right - dr
            val valid = topIn <= bottomIn && leftIn <= rightIn
            snapOuterRect = RectF(vis.left + left, vis.top + top, vis.right - right, vis.bottom - bottom)
            snapInnerRect = if (valid) RectF(leftIn, topIn, rightIn, bottomIn) else null
            snapRingEmpty = !valid
            // 屏幕级「边界偏移线 + 吸附内缩框」（所有宠物共用同一屏幕范围，画一遍作参照）
            lines += if (!valid) bound else bound + listOf(
                GuideLineView.Line(leftIn, vis.top.toFloat(), leftIn, vis.bottom.toFloat()),
                GuideLineView.Line(rightIn, vis.top.toFloat(), rightIn, vis.bottom.toFloat()),
                GuideLineView.Line(vis.left.toFloat(), topIn, vis.right.toFloat(), topIn),
                GuideLineView.Line(vis.left.toFloat(), bottomIn, vis.right.toFloat(), bottomIn)
            )
            // 注意：吸附距离示意图只画【屏幕级】的 4 条边界偏移线 + 4 条吸附位置线（共 8 条），
            // 与宠物数量、宠物位置、是否吸附【均无关】——吸附距离是相对边界偏移线的屏幕级概念。
            // 曾在此处额外为每个宠物画一套「自身吸附触发区」(4×N 条)：语义重复，且各宠物边界完全相同
            // 导致 N 遍重合叠加，显示为内侧一个多余的矩形；同时 kinds/labels 都只有 8 个，
            // 索引越界的线取不到种类而被当成 BOUND(黑白双线) 绘制。已移除。
            val leftX = vis.left + left
            val rightX = vis.right - right
            val topY = vis.top + top
            val botY = vis.bottom - bottom
            empty = rightX <= leftX || botY <= topY
            // 吸附距离调整时的红框彩蛋三态判定（仅 SNAP 模式显示）：
            //  A. 总开关关闭或四方向均关 → 没有任何可吸附方向
            //  B. 有可吸附方向，但活动范围不存在（上下左右合围为空）
            //  C. 活动范围存在，但吸附区域=活动范围（全吸附，snapRingEmpty）
            val snapMaster = phys?.snapEnabled ?: true
            val anyDirOn = topOn || botOn || leftOn || rightOn
            val rangeEmpty = rightX <= leftX || botY <= topY
            val snapWarn: Pair<String, String>? = when {
                !snapMaster || !anyDirOn ->
                    "都不让贴边边！\n「酥」怎么贴贴贴？" to "当前没有被打开的吸附方向"
                rangeEmpty ->
                    "都没有活动范围？\n「酥」只能一直贴边边了！" to "当前活动范围不存在，超容易吸附边缘。"
                snapRingEmpty ->
                    "贴边边太容易太舒服了，\n酥要一直贴边边！" to "活动范围内，全是吸附区域，超容易贴边边。"
                else -> null
            }
            this.snapWarnSpec = snapWarn
        } else if (hasBoundOnly) {
            // 纯边界偏移来源：计算「活动范围是否为零」，用于无范围振动 + 红框。
            // 注意：仅 BOUND 来源参与 empty 判定，IME 等其他来源拖动时不触发无范围振动/红框。
            val o = boundGuideOverride
            val topOff = o?.get(0) ?: boundOffsetTop
            val botOff = o?.get(1) ?: boundOffsetBottom
            val leftOff = o?.get(2) ?: boundOffsetLeft
            val rightOff = o?.get(3) ?: boundOffsetRight
            lines += boundLines(vis, topOff, botOff, leftOff, rightOff)
            val leftX = vis.left + leftOff
            val rightX = vis.right - rightOff
            val topY = vis.top + topOff
            val botY = vis.bottom - botOff
            empty = rightX <= leftX || botY <= topY
        } else if (hasIme) {
            // 键盘抬高预览来源：键盘态下真实边界随「重置偏移」开关归零（与 PetView 实际抬高逻辑一致），
            // 否则预览的“活动范围”与真实运行不一致，表现为偏移线映射错误。
            // 此处不计算 empty（不参与无范围振动/红框），仅画四边活动范围线。
            val (topOff, botOff) = if (imeResetBottomOffset) 0f to 0f
            else (boundGuideOverride?.get(0) ?: boundOffsetTop) to (boundGuideOverride?.get(1) ?: boundOffsetBottom)
            val o = boundGuideOverride
            val leftOff = o?.get(2) ?: boundOffsetLeft
            val rightOff = o?.get(3) ?: boundOffsetRight
            lines += boundLines(vis, topOff, botOff, leftOff, rightOff)
        }
        // 宠物参数异常红框彩蛋（大小/反弹/速度/重力越界时的「酥」吐槽）。
        //
        // 关键修正：
        // 1) 红框只在【正在调整对应参数】时出现，绝不串味——
        //    调大小才出大小红框，调反弹才出反弹红框，调边界偏移时绝不显示宠物参数红框。
        // 2) 红框与振动成对出现：出现红框的瞬间振动一次（跳变边沿触发，避免每帧狂震），
        //    同类异常持续期间不再重复震，松开滑块再调才会重新震。
        val activeKinds = guideOwnerKinds.values.toSet()
        val hasSize = GuideKind.SIZE in activeKinds
        val hasRebound = GuideKind.REBOUND in activeKinds
        val hasSpeed = GuideKind.SPEED in activeKinds
        val hasGravity = GuideKind.GRAVITY in activeKinds
        val cfg = config.getBlocking()
        // 仅判定“当前正在调整的参数”是否越界；未拖动的参数不参与。
        val petWarn: Pair<String, String>? = when {
            hasSize && cfg.scale < 0.5f ->
                "那么小的「酥」？\n 还能找的到吗？" to "超迷你的「酥」，很难被抓住的哦"
            hasSize && cfg.scale > 5f ->
                "那么大的「酥」？\n手机重重的！重重的！" to "超巨大的「酥」，手机不堪重负"
            hasRebound && cfg.reboundEnabled && cfg.reboundRatio > 1f ->
                "每次反弹「酥」都将踩下油门！\n冲冲冲！" to "每次反弹，速度都会变大，会超速的！"
            hasSpeed && cfg.maxSpeed > 14000f ->
                "「酥」掌握了超速的技巧。\n会吊销驾照的！" to "「酥」真的能达到那么快吗？"
            hasSpeed && cfg.maxSpeed < 500f ->
                "限速警告！" to "严禁超速！！！！！！！"
            hasGravity && cfg.gravity > 8000f ->
                "被重力狠狠抓住？\n「酥」将会狠狠的撞击手机" to "重力过大，每次弹起，都会迅速被拉会地面。"
            else -> null
        }
        // 红框 ↔ 振动：跳变边沿触发一次（与 drawWarnBox 红框同步）。
        // petWarnActiveKind 记录“当前红框归属的参数种类”，种类变化或红框由无到有才重新振动。
        val warnKind = when {
            hasSize && petWarn != null -> GuideKind.SIZE
            hasRebound && petWarn != null -> GuideKind.REBOUND
            hasSpeed && petWarn != null -> GuideKind.SPEED
            hasGravity && petWarn != null -> GuideKind.GRAVITY
            else -> null
        }
        if (warnKind != null && warnKind != petWarnActiveKind) {
            vibrateEmpty()
        }
        petWarnActiveKind = warnKind
        guideLineView?.setPetWarn(petWarn)
        if (hasTray) {
            val offset = trayGuideOverride ?: thinkingOffsetY
            val gl = guideLineView
            val measured = trayMsgManager?.getTextScreenBounds()
            if (gl != null && measured != null && gl.height > 0) {
                val g = IntArray(2)
                gl.getLocationOnScreen(g)
                val dy = thinkingOffsetY - offset
                val topY = measured[0] - g[1] + dy
                val bottomY = measured[1] - g[1] + dy
                val w = gl.width.toFloat()
                lines += listOf(
                    GuideLineView.Line(0f, bottomY, w, bottomY),
                    GuideLineView.Line(0f, topY, w, topY)
                )
            } else {
                val bottomY = vis.bottom - offset
                val topY = bottomY - (trayMsgManager?.getTextHeightPx() ?: 0f)
                lines += listOf(
                    GuideLineView.Line(vis.left.toFloat(), bottomY, vis.right.toFloat(), bottomY),
                    GuideLineView.Line(vis.left.toFloat(), topY, vis.right.toFloat(), topY)
                )
            }
        }
        // 四边偏移线小文字备注（顺序与 boundLines 对应：左、右、上、下）；
        // 仅纯边框模式（无 TRAY 混入）时绘制文字，避免与碎碎念线错位。
        val boundLabels = if ((hasSnap || hasBoundOnly || hasIme) && !hasTray) {
            // IME 模式下上下偏移备注随「重置偏移」归零（与预览线保持一致）
            val (topLbl, botLbl) = if (hasIme && imeResetBottomOffset) 0 to 0
            else (boundGuideOverride?.get(0) ?: boundOffsetTop) to (boundGuideOverride?.get(1) ?: boundOffsetBottom)
            val o = boundGuideOverride
            val base = listOf(
                "左部偏移 ${(o?.get(2) ?: boundOffsetLeft).toInt()}",
                "右部偏移 ${(o?.get(3) ?: boundOffsetRight).toInt()}",
                "顶部偏移 ${topLbl.toInt()}",
                "底部偏移 ${botLbl.toInt()}"
            )
            if (hasSnap) {
                val d = (snapGuideOverride ?: petView?.physics?.snapDist ?: DEFAULT_SNAP_DIST).toInt()
                val snapLabel = "吸附距离 $d"
                base + List(4) { snapLabel }
            } else base
        } else null
        // 每条线的种类：SNAP 模式下前 4 条为边界偏移线、后 4 条为吸附位置线（绿色）。
        // 严格 8 条，与上面 lines 的数量、以及 boundLabels 的 8 个标签一一对应。
        val kinds = if (hasSnap && !hasTray) {
            List(4) { GuideLineView.LineKind.BOUND } + List(4) { GuideLineView.LineKind.SNAP }
        } else {
            null
        }
        // 从「有区域」跳变到「无区域」时振动一次；反复有无可多次振动
        if (empty && !emptyGuideWasEmpty) vibrateEmpty()
        emptyGuideWasEmpty = empty
        // 吸附合围为空（全吸附）跳变时振动；反复有无可多次振动。
        val wasSnapEmpty = snapEmptyWasEmpty
        snapEmptyWasEmpty = snapRingEmpty
        if (snapRingEmpty && !wasSnapEmpty) vibrateEmpty()
        // 当前正拖动的线索引（边界线有 side 概念；吸附整体无单条边，沿用 activeBoundIndex 高亮边界线）
        guideLineView?.setLines(lines, boundLabels, kinds, activeBoundIndex, snapActive = hasSnap)
        // SNAP 模式：绘制吸附区域环带（outer/inner 由上面 SNAP 分支填充；非 SNAP 时清除）。
        // 逐方向开关 onFlags 顺序：左、右、上、下，关闭的方向不画其吸附内缩线与环带。
        if (hasSnap) {
            guideLineView?.setSnapRing(snapOuterRect, snapInnerRect, snapRingEmpty,
                booleanArrayOf(leftOn, rightOn, topOn, botOn), snapWarnSpec)
        } else {
            snapWarnSpec = null
            guideLineView?.setSnapRing(null, null, false)
        }
        // 键盘抬高预览：与边界线共用同一 guideLineView，由引用计数体系统一驱动显示/隐藏，
        // 不再走独立通道，彻底消除与边界滑块互相误杀导致的虚晃。
        if (hasIme) {
            guideLineView?.setKeyboardPreview(true, imeLiftOverride ?: 0f, active = true)
        } else {
            guideLineView?.setKeyboardPreview(false, 0f)
        }
        // 仅置可见标志：偏移线已并入 stageView 绘制，无独立窗口需要添加。
        guideLineView?.visibility = android.view.View.VISIBLE
    }

    /**
     * 把一条边界偏移线裁到宠物浮窗矩形范围内，得到该宠物脚下的那段吸附判定线。
     * - 水平线（贴底/贴顶）：x 限制在 [winLeft, winLeft+winW]
     * - 竖线（贴左/贴右）：y 限制在 [winTop, winTop+winH]
     * 无交集（宠物完全在该边之外）时返回 null，由调用方跳过。
     */
    private fun clipSnapSeg(
        line: GuideLineView.Line,
        winLeft: Float, winTop: Float, winW: Float, winH: Float
    ): PetStageView.SnapSeg? {
        return if (line.x1 == line.x2) {
            val y1 = maxOf(line.y1, winTop)
            val y2 = minOf(line.y2, winTop + winH)
            if (y2 <= y1) null else PetStageView.SnapSeg(line.x1, y1, line.x1, y2)
        } else {
            val x1 = maxOf(line.x1, winLeft)
            val x2 = minOf(line.x2, winLeft + winW)
            if (x2 <= x1) null else PetStageView.SnapSeg(x1, line.y1, x2, line.y1)
        }
    }

    /** 四条边界偏移线：宠物可贴合的实际屏幕边缘。 */
    private fun boundLines(
        vis: android.graphics.Rect,
        top: Float, bottom: Float, left: Float, right: Float
    ) = listOf(
        // 左缘：x = 物理左 + 左偏移
        GuideLineView.Line(vis.left + left, vis.top.toFloat(), vis.left + left, vis.bottom.toFloat()),
        // 右缘：x = 物理右 - 右偏移
        GuideLineView.Line(vis.right - right, vis.top.toFloat(), vis.right - right, vis.bottom.toFloat()),
        // 上缘：y = 物理上 + 上偏移
        GuideLineView.Line(vis.left.toFloat(), vis.top + top, vis.right.toFloat(), vis.top + top),
        // 下缘：y = 物理下 - 下偏移
        GuideLineView.Line(vis.left.toFloat(), vis.bottom - bottom, vis.right.toFloat(), vis.bottom - bottom)
    )

    /**
     * 点击穿透：开启后给浮窗窗口加 FLAG_NOT_TOUCHABLE，使其完全不接收触摸事件，
     * 点击/拖动/长按均穿透到下层应用；关闭则移除该 flag 恢复正常交互。
     */
    private fun applyClickThrough(enabled: Boolean) {
        // 点击穿透作用于所有宠物实例窗口。
        for (inst in pets) {
            val lp = inst.layoutParams
            val touchable = (lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0
            if (enabled == !touchable) continue  // 状态未变，避免无谓 updateViewLayout
            if (enabled) {
                lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            } else {
                lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            }
            try { windowManager.updateViewLayout(inst.view, lp) } catch (_: Exception) {}
        }
    }

    /**
     * 获取浮窗可活动范围（物理屏幕像素坐标，覆盖状态栏/导航栏）。
     * 需求：图片边缘紧贴手机真实显示边缘（最大边缘），忽略状态栏/导航栏/刘海。
     * 做法：以 displayMetrics 的真实物理分辨率作为基准，再用 WindowInsets 只取
     * 状态栏（顶部）与导航栏（底部）的占用高度，分别将活动区向上/向下扩展，
     * 使浮窗在 FLAG_LAYOUT_NO_LIMITS 下可越过系统栏、真正贴到物理屏幕最顶/最底。
     * 注意：左右无横向系统栏，保持 0..W，不可扩展，否则会超出屏幕。
     */
    internal fun getScreenBounds(): android.graphics.Rect {
        val dm = resources.displayMetrics
        val phW = dm.widthPixels
        val phH = dm.heightPixels
        // 仅取顶部状态栏高度与底部导航栏高度（左右用 0，不扩展）
        var topInset = 0
        var bottomInset = 0
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            val insets = metrics.getWindowInsets()
                .getInsetsIgnoringVisibility(
                    android.view.WindowInsets.Type.statusBars() or
                    android.view.WindowInsets.Type.navigationBars()
                )
            topInset = insets.top
            bottomInset = insets.bottom
        }
        // 浮窗坐标系原点相对物理屏幕下移了 statusBars.top，故底部需扣除该下移量，
        // 再下扩导航栏高度以覆盖导航栏、贴到物理最底。左右不变。
        return android.graphics.Rect(
            0,
            -topInset,
            phW,
            phH - topInset + bottomInset
        )
    }

    /**
     * 当前输入法（软键盘）可见高度（像素）。用全局 currentWindowMetrics 的 ime inset，
     * 跨机型可靠（不依赖浮窗是否有输入焦点）。未弹出或低版本（<R）返回 0。
     */
    private fun getImeHeight(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0
        return try {
            val insets = windowManager.currentWindowMetrics
                .getWindowInsets()
                .getInsets(android.view.WindowInsets.Type.ime())
            insets.bottom
        } catch (_: Exception) { 0 }
    }

    /**
     * 输入法高度变化（view 级或全局级任一更新）时调用：
     * 取两者较大值，若与当前生效值不同则重算边界抬高/恢复地面。
     */
    private fun applyImeInset() {
        val ime = maxOf(viewImeInset, globalImeInset)
        // 上一次生效的 ime 高度需与 recalcBounds 内的计算一致，故通过比较去重：
        // 直接用 recalcBounds 重算（开销极小，仅在键盘显隐瞬间发生，非每帧）。
        val prev = lastAppliedImeInset
        if (ime != prev) {
            lastAppliedImeInset = ime
            recalcBounds()
        }
        keyboardUp = ime > 0
        applyImeHide(keyboardUp)
    }

    // 键盘时隐藏：imeHide 开启且键盘弹出时，将所有宠物实例整体隐藏（GONE 即不可见且不可点击），
    // 不改写任何 config（visible/clickThrough 保持原样），键盘收起（keyboardUp=false）即按 effectiveVisible 恢复。
    private fun applyImeHide(kbUp: Boolean) {
        if (pets.isEmpty()) return
        val hide = imeHideEnabled && kbUp
        for (inst in pets) {
            inst.view.visibility =
                if (hide) android.view.View.GONE
                else if (effectiveVisible) android.view.View.VISIBLE
                else android.view.View.GONE
        }
    }

    // 记录上次实际生效的 IME 高度，避免 applyImeInset 重复且无变化地重算边界
    private var lastAppliedImeInset = -1f

    private fun startLoop() {
        if (running) return
        running = true
        lastFrameTime = SystemClock.uptimeMillis()
        physAccumulator = 0L
        mainHandler.postDelayed(frameRunnable, FRAME_DELAY)
        startRandomLoop()
    }

    /** 随机模式周期重置信号：收到后中断当前等待，立即进入下一轮随机。 */
    private val randomResetChannel = Channel<Unit>(Channel.CONFLATED)

    /**
     * 随机模式循环：开启后按配置的[最小,最大]分钟周期，
     * 对开启的随机项生成一组合法随机值并写回 DataStore（UI 自动同步）。
     */
    private fun startRandomLoop() {
        scope.launch {
            delay(2000L) // 起步稍等，等配置加载
            while (true) {
                val c = config.configFlow.first()
                if (!c.randomEnabled) {
                    // 未开启：阻塞等待重置信号（开启时由外部触发）再进入下一轮
                    randomResetChannel.receive()
                    continue
                }
                // 已开启：首轮【不立即随机】——app 启动若随机模式本就开启，不应在启动时随机一次。
                // 仅当用户主动开启（randomListener 调 triggerRandomNow）才立即随机。
                // 这里直接进入等待一个随机周期，到点后再随机，避免启动即随机的突兀感。
                // 等待期间可被 requestRandomReset（改周期/主动重置）中断，立即进入下一轮。
                val min = c.randomPeriodMin.coerceAtLeast(1)
                val max = c.randomPeriodMax.coerceAtLeast(min)
                val waitMin = min + Random.nextInt(max - min + 1)
                val interrupted = withTimeoutOrNull(waitMin * 60_000L) {
                    randomResetChannel.receive()
                } != null
                // 被重置信号中断（说明外部已主动触发随机/改周期），本轮跳过随机，重新读配置进入下一轮
                if (interrupted) continue
                // 周期自然到点：依据当前配置随机一组值并写回
                val c2 = config.configFlow.first()
                if (!c2.randomEnabled) continue
                val next = generateRandomConfig(c2)
                config.update { _ -> next }
                if (thinkingEnabled) trayMsgManager?.showRandomTrigger()   // 随机触发提示“变变变”
            }
        }
    }

    /** 请求重置随机周期（改周期或开启模式时调用）：中断等待立即进入下一轮。 */
    fun requestRandomReset() {
        randomResetChannel.trySend(Unit)
    }

    /** 碎碎念空白时长范围变更后：让浮窗立即按新范围重排下一次计时（若正处于空状态）。 */
    fun requestTrayEmptyReset() {
        trayMsgManager?.requestEmptyReset()
    }

    /** 碎碎念样式调整预览：显示固定文案「（酥的碎碎念）」，暂停轮播。 */
    fun showTrayPreview() {
        trayMsgManager?.showPreview()
    }

    /** 结束碎碎念样式预览，恢复轮播。 */
    fun hideTrayPreview() {
        trayMsgManager?.hidePreview()
    }

    /** 立即按当前配置随机一次并写盘（开启随机模式时调用，做到“打开即随机”）。 */
    fun triggerRandomNow() {
        scope.launch {
            // 在 update 内部读取最新配置生成，避免 configFlow.first() 竞态读回旧值导致不随机；
            // 若尚未开启则强制置为开启，确保本次随机落盘后 randomEnabled 仍为 true（不会写回 false）。
            config.update { c ->
                val base = if (c.randomEnabled) c else c.copy(randomEnabled = true)
                generateRandomConfig(base)
            }
            if (thinkingEnabled) trayMsgManager?.showRandomTrigger()   // 随机触发提示“变变变”
            requestRandomReset() // 重置等待计时，从本次开始重新计时
        }
    }

    /** 依据当前配置与随机项开关，生成下一组合法随机配置（四边重力上下/左右互斥）。 */
    private fun generateRandomConfig(c: PetConfigData): PetConfigData {
        val items = c.randomItems
        val r = Random
        fun on(flag: Int) = (items and flag) != 0
        var nc = c

        if (on(RandomItemFlags.SCALE)) {
            nc = nc.copy(scale = 0.6f + r.nextFloat() * (2f - 0.6f))
        }
        if (on(RandomItemFlags.ALPHA)) {
            nc = nc.copy(alpha = 0.30f + r.nextFloat() * (1.00f - 0.30f))
        }
        if (on(RandomItemFlags.GRAVITY_ENABLED)) {
            // 重力抛掷开关独立于四边重力：只决定 gravityEnabled，不碰四边方向、也不禁用四边随机
            nc = nc.copy(gravityEnabled = r.nextFloat() < 0.8f)
        }
        if (on(RandomItemFlags.TILT_GRAVITY)) {
            nc = nc.copy(tiltGravity = r.nextBoolean())
        }
        if (on(RandomItemFlags.MAX_SPEED)) {
            nc = nc.copy(maxSpeed = 2000f + r.nextFloat() * (20000f - 2000f))
        }
        if (on(RandomItemFlags.GRAVITY)) {
            nc = nc.copy(gravity = 500f + r.nextFloat() * (7000f - 500f))
        }
        if (on(RandomItemFlags.REBOUND)) {
            nc = nc.copy(reboundRatio = 0.45f + r.nextFloat() * (0.95f - 0.45f))
        }
        // 四边重力：V=「上下」随机权，H=「左右」随机权。与重力抛掷开关完全独立。
        // 规则：
        // 0. V、H 都没勾 → 不抽，保持原值。
        // 1. 仅 V 勾：若 H 原值本身为空，则 V 必上下选一个（防全空）；否则 V 可三态（上/下/空）。
        // 2. 仅 H 勾：对称处理。
        // 3. V、H 都勾：先抽 V（允许空），再抽 H —— 若 V 抽空则 H 必左右选一个，否则 H 可三态。
        // 任一轴有修改权时，最终结果至少 1 个方向（不会出现全空）。
        val vOn = on(RandomItemFlags.GDIR_V)
        val hOn = on(RandomItemFlags.GDIR_H)
        if (vOn || hOn) {
            var t = c.gravityTop; var b = c.gravityBottom
            var l = c.gravityLeft; var rr = c.gravityRight
            // 先抽 V（三态：0上 1下 2空）
            var vEmpty = false
            if (vOn) {
                when (r.nextInt(3)) {
                    0 -> { t = true;  b = false }
                    1 -> { t = false; b = true }
                    else -> { t = false; b = false; vEmpty = true }
                }
            }
            // 决定 H 时，看「另一轴此刻是否空」——未勾轴用原值判空，已勾轴用刚抽的结果判空
            val otherEmpty = if (vOn) vEmpty else (c.gravityTop && c.gravityBottom)
            if (hOn) {
                if (otherEmpty) {
                    // 另一轴空 → H 必须左右选一个（不空）
                    val left = r.nextBoolean(); l = left; rr = !left
                } else {
                    // 另一轴有方向 → H 可三态（0左 1右 2空）
                    when (r.nextInt(3)) {
                        0 -> { l = true;  rr = false }
                        1 -> { l = false; rr = true }
                        else -> { l = false; rr = false }
                    }
                }
            }
            nc = nc.copy(gravityTop = t, gravityBottom = b, gravityLeft = l, gravityRight = rr)
        }
        // 四边反弹：各边独立随机开关
        if (on(RandomItemFlags.RBOUND_TOP)) nc = nc.copy(reboundTop = r.nextBoolean())
        if (on(RandomItemFlags.RBOUND_BOTTOM)) nc = nc.copy(reboundBottom = r.nextBoolean())
        if (on(RandomItemFlags.RBOUND_LEFT)) nc = nc.copy(reboundLeft = r.nextBoolean())
        if (on(RandomItemFlags.RBOUND_RIGHT)) nc = nc.copy(reboundRight = r.nextBoolean())
        return nc
    }

    private fun pause() {
        if (!running) return
        running = false
        mainHandler.removeCallbacks(frameRunnable)
    }

    private fun resume() {
        if (running) return
        running = true
        lastFrameTime = SystemClock.uptimeMillis()
        physAccumulator = 0L
        mainHandler.postDelayed(frameRunnable, FRAME_DELAY)
    }

    private fun showMenu() {
        // 长按菜单：以 TYPE_APPLICATION_OVERLAY 浮窗承载（见 PetMenu），
        // 悬浮在其它应用之上，不进入 app task、不抢前台；仅“设置”项才进入 app。
        petMenu?.dismiss()
        petMenu = PetMenu(this, petView).apply { show() }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                CHANNEL_ID, "CakePet 浮窗",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CakePet 运行中")
            .setContentText("点击打开设置")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        performAction(intent?.action)
        // 退出态下返回 NOT_STICKY，避免 START_STICKY 在进程被回收后重建服务，
        // 造成“非活非死”（通知残留 + 服务反复复活）。
        return if (isExiting) START_NOT_STICKY else START_STICKY
    }

    /** 供浮窗菜单即时写入配置（如点击穿透 / 重力开关），写入即触发 applyConfig 生效 */
    fun updateConfig(block: PetConfigData.() -> PetConfigData) {
        scope.launch { config.update(block) }
    }

    /** 供浮窗菜单读取当前配置（初始化开关状态） */
    fun currentConfig(): PetConfigData = config.getBlocking()

    /** 处理菜单/广播/通知下发的动作（供浮窗菜单与 onStartCommand 共用） */
    fun performAction(action: String?) {
        // 退出(EXIT)必须最先处理：不依赖 petView 是否已初始化，否则一旦 petView 未就绪，
        // 下面的吸附态判断会抛异常，导致 exitApp() 永不执行、退出静默失败。
        if (action == MenuActivity.ACTION_EXIT) {
            exitApp()
            return
        }
        // 吸附态：菜单里的动作切换一律忽略（探头常驻，仅用户手动拉出/召回才退出）。
        // 召回(RECALL)是用户主动退出，允许；隐藏(HIDE)只改可见性、不动吸附态，允许。
        if (::petView.isInitialized && petView.physics.isSnapped &&
            action != MenuActivity.ACTION_RECALL && action != MenuActivity.ACTION_HIDE
        ) return
        when (action) {
            MenuActivity.ACTION_PAT -> petView.playOnce(ImageModeManager.PAT_HEAD)
            MenuActivity.ACTION_JUMP -> {
                petView.playOnce(ImageModeManager.JUMP_DOWN)
                // 给一个向上的初速度，模拟跳起（配合底部重力落回）
                petView.physics.vx = 0f
                petView.physics.vy = -900f
            }
            MenuActivity.ACTION_ROLL -> {
                petView.playOnce(ImageModeManager.ROLL)
                // 翻滚带水平位移
                petView.physics.vx = 300f
                petView.physics.vy = 0f
            }
            MenuActivity.ACTION_PULL_FISH -> {
                // 扒鱼：位移 mode，但位移非循环、仅一次（单循环）。
                // 对齐 PC PullFishMode：位移由【每帧 offset 累加】驱动（非物理速度），
                // 在 PetView 帧切换时把各帧 dx 累加到坐标，播完即停。
                // 镜像支持（类似 walk/蠕动）：随机面朝方向——图片资源朝右(flipX=false)，
                // 翻转(flipX=true)则朝左；位移方向随 flipX 反向、锚点对称翻转（见 PetView）。
                // 扒鱼结束后保留 flipX，使后续 LIE/SIT 也沿用该朝向，方向连贯。
                // 撞墙由 PetView 累加时 coerceIn 边界处理（夹边即停，不穿透/反弹）。
                petView.flipX = Random.nextBoolean()
                petView.playOnce(ImageModeManager.PULL_FISH)
            }
            MenuActivity.ACTION_WHITE -> petView.playOnce(ImageModeManager.WALK_WHITE)
            MenuActivity.ACTION_PUFFED -> petView.playOnce(ImageModeManager.SIT_PUFFED)
            MenuActivity.ACTION_SIT_CLAM -> petView.playOnce(ImageModeManager.SIT_CLAM)
            MenuActivity.ACTION_SHAKE_HEAD -> petView.playOnce(ImageModeManager.SHAKE_HEAD)
            // WALK / WRIGGLE 是常驻循环 + 行走位移模式（walkSpeed>0），
            // 必须用 setMode（常驻）激活，不能用 playOnce：
            // playOnce 会把模式放进 forcedSeq，而 isWalk() 要求 forcedSeq==null，
            // 导致行走分支不执行 → 原地踏步无位移。setMode 后 forcedSeq==null，位移生效。
            MenuActivity.ACTION_WALK -> petView.setMode(ImageModeManager.WALK)
            MenuActivity.ACTION_WRIGGLE -> petView.setMode(ImageModeManager.WRIGGLE)
            MenuActivity.ACTION_LIE -> petView.playOnce(ImageModeManager.LIE)
            MenuActivity.ACTION_PROBE_HEAD -> {
                petView.playOnce(ImageModeManager.PROBE_HEAD)
                // 摸头：碎碎念显示“喵喵喵/~o( =∩ω∩= )m/喵”三选一，1-3s 后继续轮播池
                if (thinkingEnabled) {
                    trayMsgManager?.showMomentary(
                        listOf("喵喵喵", "~o( =∩ω∩= )m", "喵"), 1000L, 3000L
                    )
                }
            }
            MenuActivity.ACTION_HIDE -> {
                // 隐藏 = 置 visible=false（与设置页“显示宠物”开关同源同一标志），
                // 保持服务运行，仅隐藏浮窗；不再 stopService，避免与设置页状态不同步。
                // 碎碎念跟随隐藏。
                trayMsgManager?.setVisible(false)
                scope.launch { config.update { it.copy(visible = false) } }
            }
            MenuActivity.ACTION_RECALL -> {
                // 召回：强制复位 —— 归位底部中心、静止、显示 sit-calm，
                // 并清除抛掷/滚动/速度/加速度/拖拽等所有运动状态。
                // 多实例：对所有宠物实例统一召回（而非只召回主实例 pets[0]）。
                // 顺序至关重要：先复位朝向 → 再重算边界 → 最后按新边界归位。
                // 背景：吸附期间 snapRotation 被设为贴附边朝向，帧循环【只对吸附实例】重算边界；
                // 退出吸附后不再重算，若直接用旧的 p.maxY 归位，用的其实是吸附态包围盒的地面，
                // 表现为“召回后位置/地面判断错误”（贴底时 snapRotation=0 恰好正确 → 呈概率性）。
                for (inst in pets) inst.view.forceReset()
                recalcBounds()   // 直立朝向下重算全部实例边界，maxY 才是真实地面
                for (inst in pets) {
                    val p = inst.view.physics
                    val cx = (p.minX + p.maxX) / 2f   // 底部中心（锚点屏幕坐标）
                    val cy = p.maxY
                    p.resetTo(cx, cy)
                }
                // 若当前处于隐藏，同步修改设置与浮窗显示（与“显示宠物”开关同源同一标志）
                // 碎碎念跟随显示。
                trayMsgManager?.setVisible(thinkingEnabled)
                scope.launch {
                    config.update { if (!it.visible) it.copy(visible = true) else it }
                }
            }
        }
    }

    /** 彻底退出：持久化退出意图，释放资源，停止服务并结束整个应用进程。 */
    private fun exitApp() {
        // 1) 持久化退出意图（commit 同步落盘，保证进程被 kill 前已写入，
        //    避免系统杀后台后 Service 因标志丢失而复活）。
        markExitingCommit()
        // 2) 先同步释放浮窗/监听/传感器等资源，不依赖后续的 onDestroy。
        cleanup()
        // 3) 停止前台服务、移除通知。
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        stopSelf()
        // 4) 兜底：直接结束整个应用进程，确保 MainActivity 等所有界面一并退出，
        //    彻底摆脱“非活非死”。资源已在 cleanup 中释放，此处无泄漏。
        try { Process.killProcess(Process.myPid()) } catch (_: Exception) {}
    }

    /** 任务栈被移除（用户从最近任务划掉）时的兜底退出，避免前台服务残留。 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!isExiting) exitApp()
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
    }

    /** 释放浮窗、监听、传感器等资源；可被 onDestroy 与 exitApp 复用。 */
    private fun cleanup() {
        // 兜底移除前台通知，避免退出后通知栏常驻造成“非活非死”观感。
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        running = false
        mainHandler.removeCallbacks(frameRunnable)
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        try { petMenu?.dismiss() } catch (_: Exception) {}
        // 注销体感重力传感器监听，避免泄漏
        try { sensorManager?.unregisterListener(tiltListener) } catch (_: Exception) {}
        // 移除所有宠物实例浮窗与各自碎碎念浮窗
        for (inst in pets) {
            try { windowManager.removeView(inst.view) } catch (_: Exception) {}
            try { inst.petMsg.destroy() } catch (_: Exception) {}
        }
        pets.clear()
        try { if (stageView != null) windowManager.removeView(stageView) } catch (_: Exception) {}
        stageView = null
        try { if (debugView != null) windowManager.removeView(debugView) } catch (_: Exception) {}
        guideLineView = null
        trayMsgManager = null
        instance = null
        try { scope.cancel() } catch (_: Exception) {}
    }

    companion object {
        // 供设置页（同进程）直接驱动辅助线浮层，避免引入广播样板。
        var instance: PetService? = null
        const val CHANNEL_ID = "cakepet_channel"
        const val NOTIFICATION_ID = 1001
        // 吸附阈值兜底值：与 PetConfigData.snapThreshold 默认值保持一致，
        // 仅在 petView 尚未初始化（服务刚启动）时用于绘制辅助线。
        private const val DEFAULT_SNAP_DIST = 100f
    }
}
