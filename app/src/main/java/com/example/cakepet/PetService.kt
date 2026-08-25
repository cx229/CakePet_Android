package com.example.cakepet

import kotlin.random.Random
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
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
 * 宠物浮窗服务：ForegroundService + WindowManager + Handler 物理循环。
 * 对应 PC 端 pet_sesame_cake.py 的主循环，但安卓用 postDelayed 每帧驱动替代 Qt 事件循环。
 */
class PetService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var petView: PetView
    private lateinit var layoutParams: WindowManager.LayoutParams
    private lateinit var config: PetConfig

    // 长按弹出的浮窗菜单（TYPE_APPLICATION_OVERLAY），不进入 app task
    private var petMenu: PetMenu? = null

    // 调试信息浮层（左下角）
    private var debugView: android.widget.TextView? = null
    private var debugParams: WindowManager.LayoutParams? = null
    private var showDebug = false
    private var showRect = false
    // 四边边界偏移（像素）：正=向屏内收缩（宠物离屏边有间距），负=允许超出屏边，范围 -200~300
    private var boundOffsetTop = 0f
    private var boundOffsetBottom = 0f
    private var boundOffsetLeft = 0f
    private var boundOffsetRight = 0f

    // 碎碎念：屏幕底部居中的独立浮窗 TextView，水平居中、6dp 字体、不受宠物旋转/缩放/底部偏移影响
    private var trayMsgManager: TrayMsgManager? = null
    private var thinkingEnabled = false
    private var thinkingOffsetY = 0f   // 碎碎念距屏幕底部的偏移（像素），与 trayMsgManager 同步，供辅助线计算

    // 设置页调整“边界偏移/碎碎念偏移”时显示的黑色虚线辅助层（全屏透明 overlay）
    private var guideLineView: GuideLineView? = null
    private var guideParams: WindowManager.LayoutParams? = null

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

            // 物理推进：以固定步长（对齐 PC 3ms）累积，与显示帧率解耦，保证位移平滑连续
            physAccumulator += dtMs
            var physGuard = 0
            while (physAccumulator >= PHYS_STEP_MS && physGuard++ < 200) {
                petView.stepPhysics(PHYS_STEP_MS / 1000f)
                physAccumulator -= PHYS_STEP_MS
            }
            // 动画推进：用真实 dt（时延精确），绝不控制位置
            petView.tick(dt)
            // 单一位置写入点：由物理循环统一把锚点坐标换算成浮窗左上角。
            // 坐标语义：physics.x/y = 锚点在屏幕的坐标（对齐 PC image_meta），
            // 浮窗左上 = 锚点 - 锚点相对位图偏移。钳制已在 physics.setBounds 内完成。
            val (ax, ay) = petView.getAnchorScaled()
            val nx = petView.physics.x
            val ny = petView.physics.y
            layoutParams.x = (nx - ax).toInt()
            layoutParams.y = (ny - ay).toInt()
            // View 尺寸【同步】写入当前帧×scale（不 requestLayout 异步重排，避免掉帧）。
            // onDraw 铺满 View，故铺满即正确缩放、不拉伸；scale/锚点/贴边全部正确。
            // 仅尺寸变化时才改 width/height（避免每帧 measure 开销拖慢 overlay 合成）。
            val (w, h) = petView.getBitmapSize()
            if (w != lastW || h != lastH) {
                layoutParams.width = w
                layoutParams.height = h
                lastW = w
                lastH = h
            }
            try {
                windowManager.updateViewLayout(petView, layoutParams)
            } catch (_: Exception) {
            }

            // 每帧强制重绘：让物理位置与位图每帧都画出来。
            // 原 roll 版删掉了 invalidate，改靠“尺寸变化才改 layoutParams”间接触发 onDraw，
            // 但同尺寸相邻帧（Roll 序列很常见）不会触发重绘 → 位置算出来了屏幕却半秒才画一次 → 卡顿。
            petView.invalidate()

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
            append("${L("BL:")}(${fmt(left)}, ${fmt(bottom)})")
        }
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
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        config = PetConfig(this)
        // 碎碎念独立浮窗（屏幕底部居中，6dp 文字），与服务生命周期绑定
        trayMsgManager = TrayMsgManager(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        initPetView()
        loadConfig()
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
        petView = PetView(this)
        layoutParams = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            gravity = Gravity.TOP or Gravity.START
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
        }
        petView.onLongPress = { showMenu() }
        petView.onDoubleTap = {
            petView.playOnce(ImageModeManager.PAT_HEAD)
            // 摸头（双击）：碎碎念显示摸头文案，1-3s 后继续轮播池
            if (thinkingEnabled) {
                trayMsgManager?.showPat()
            }
        }
        petView.onSizeChanged = { recalcBounds() }
        // 输入法（键盘）显隐：view 级补充来源；与全局检测取较大值
        petView.onImeInsetChanged = { h ->
            viewImeInset = h.toFloat()
            applyImeInset()
        }
        petView.onPositionChanged = { cx, cy ->
            // 回调传的是锚点坐标（手指 + 抓取偏移）。拖拽期间物理 step 不跑，
            // 直接更新锚点并立即 clamp + 写出浮窗位置（跟手，且单一数据源 physics.x/y）。
            val (ax, ay) = petView.getAnchorScaled()
            petView.physics.x = cx
            petView.physics.y = cy
            petView.physics.clampToBounds()
            layoutParams.x = (petView.physics.x - ax).toInt()
            layoutParams.y = (petView.physics.y - ay).toInt()
            try { windowManager.updateViewLayout(petView, layoutParams) } catch (_: Exception) {}
        }
        // 提起（拖拽）开始/结束：驱动碎碎念“提起”持续文案
        petView.onDragStateChanged = { dragging ->
            if (thinkingEnabled) {
                if (dragging) {
                    trayMsgManager?.showLift()
                } else {
                    trayMsgManager?.clearLift()
                }
            }
        }
        // 真正落地（拖拽/抛掷触底，进入 JUMP_DOWN 动画那一刻）：显示落地文案，之后先空白再轮播
        petView.onLand = {
            if (thinkingEnabled) trayMsgManager?.showLand()
        }
        // 释放后由 updateAnimByState 根据状态自动切换动画，无需强制
        // 关键：addView 之前先把浮窗位置算到底部中心，避免先出现在左上角(0,0)再闪回底部。
        val vis = getScreenBounds()
        val (w, h) = petView.getBaseBitmapSize()
        val (ax, ay) = petView.getBaseAnchorScaled()
        petView.physics.x = (vis.left + ax + (vis.right - (w - ax))) / 2f
        petView.physics.y = vis.bottom.toFloat() - (h - ay)
        layoutParams.x = (petView.physics.x - ax).toInt()
        layoutParams.y = (petView.physics.y - ay).toInt()
        windowManager.addView(petView, layoutParams)

        // 调试信息浮层（左下角，初始隐藏）
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

        // 辅助线浮层（全屏透明，初始隐藏）：设置页调整偏移时显示黑色虚线
        guideLineView = GuideLineView(this)
        guideParams = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            gravity = Gravity.TOP or Gravity.START
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
        }
        windowManager.addView(guideLineView, guideParams)
        guideLineView?.visibility = android.view.View.GONE
    }

    private fun loadConfig() {
        config.normalizeBlocking() // 启动时清洗历史残留的非法四边重力组合
        val c = config.getBlocking()
        petView.setConfig(c.scale, c.gravity, c.reboundRatio,
            c.gravityTop, c.gravityBottom, c.gravityLeft, c.gravityRight,
            c.reboundTop, c.reboundBottom, c.reboundLeft, c.reboundRight,
            c.gravityEnabled, c.maxSpeed)
        petView.useStaticPool = !c.gravityEnabled   // 重力·抛掷关闭 -> 静止池
        petView.setPetScale(c.scale)
        showDebug = c.showDebug
        showRect = c.showRect
        boundOffsetTop = c.offsetTop
        boundOffsetBottom = c.offsetBottom
        boundOffsetLeft = c.offsetLeft
        boundOffsetRight = c.offsetRight
        debugView?.visibility = if (showDebug) android.view.View.VISIBLE else android.view.View.GONE
        petView.showRect = showRect
        petView.visibility = if (c.visible && c.enabled) android.view.View.VISIBLE else android.view.View.GONE
        petView.invalidate()
        petView.bounceVibrateEnabled = c.bounceVibrate
        // 碎碎念：独立浮窗，可见性跟随宠物（visible && enabled）
        thinkingEnabled = c.thinkingEnabled
        trayMsgManager?.setEnabled(c.thinkingEnabled)
        trayMsgManager?.setOffsetY(c.thinkingOffset)
        thinkingOffsetY = c.thinkingOffset
        trayMsgManager?.setVisible(c.visible && c.enabled)
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
            petView.physics.gravity = c.gravity
            petView.physics.reboundRatio = c.reboundRatio
            petView.physics.gravityTop = c.gravityTop
            petView.physics.gravityBottom = c.gravityBottom
            petView.physics.gravityLeft = c.gravityLeft
            petView.physics.gravityRight = c.gravityRight
            petView.physics.reboundTop = c.reboundTop
            petView.physics.reboundBottom = c.reboundBottom
            petView.physics.reboundLeft = c.reboundLeft
            petView.physics.reboundRight = c.reboundRight
            petView.physics.snapTop = c.snapTop
            petView.physics.snapBottom = c.snapBottom
            petView.physics.snapLeft = c.snapLeft
            petView.physics.snapRight = c.snapRight
            petView.physics.snapDist = c.snapThreshold   // 吸附判定距离阈值（像素）
            petView.physics.gravityEnabled = c.gravityEnabled
            petView.physics.maxSpeed = c.maxSpeed
            petView.useStaticPool = !c.gravityEnabled   // 重力·抛掷关闭 -> 静止池
            // 重力·抛掷关闭（吸附态除外）时，立刻清零速度并复位抛掷态，使物理停止位移。
            // 覆盖所有来源：设置页/菜单页手动关闭、随机模式改关等。
            // 注意：这里只清物理状态，不调 enterIdle()/cancelForced()——避免误杀菜单正在播放的
            // 非移动一次性动作（摸头/炸毛/摇头）。动画切回静坐由 PetView.updateAnimByState 每帧处理：
            // 当重力关且未播放一次性动作时才切 SIT_CLAM，播放中则保留动作（符合"仅播放非移动池"）。
            if (!c.gravityEnabled && !petView.physics.isSnapped) {
                petView.physics.vx = 0f
                petView.physics.vy = 0f
                petView.physics.isThrowing = false   // 复位抛掷态，避免 updateAnimByState 抢回 ROLL
                petView.physics.isDragging = false   // 复位拖拽态，避免关重力后 LIFT_UP 残留
            }
            boundOffsetTop = c.offsetTop
            boundOffsetBottom = c.offsetBottom
            boundOffsetLeft = c.offsetLeft
            boundOffsetRight = c.offsetRight
            petView.setPetScale(c.scale)
            showDebug = c.showDebug
            showRect = c.showRect
            debugView?.visibility = if (showDebug) android.view.View.VISIBLE else android.view.View.GONE
            petView.showRect = showRect
            petView.invalidate()
            petView.visibility = if (c.visible && c.enabled) android.view.View.VISIBLE else android.view.View.GONE
            petView.alpha = c.alpha.coerceIn(0.1f, 1f)
            applyClickThrough(c.clickThrough)
            petView.bounceVibrateEnabled = c.bounceVibrate
            // 碎碎念：独立浮窗，可见性跟随宠物（visible && enabled）
            // 仅在“碎碎念开关”真正变化时才调用 setEnabled，避免其他配置变更触发轮播重启
            if (c.thinkingEnabled != thinkingEnabled) {
                thinkingEnabled = c.thinkingEnabled
                trayMsgManager?.setEnabled(c.thinkingEnabled)
            }
            trayMsgManager?.setOffsetY(c.thinkingOffset)
            thinkingOffsetY = c.thinkingOffset
            trayMsgManager?.setVisible(c.visible && c.enabled)
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
            if (!c.enabled) {
                pause()
            } else {
                resume()
            }
        }.launchIn(scope)
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
                petView.physics.tiltGx = sx * k
                petView.physics.tiltGy = sy * k
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
                petView.physics.tiltGravity = enabled
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
                petView.physics.let { p ->
                    p.tiltGravity = false
                    p.tiltGx = 0f
                    p.tiltGy = 0f
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
        // 吸附态：忽略体感重力方向变化（不滚、不重算边界），等待用户手动拉出/召回才退出。
        if (petView.physics.isSnapped) return
        if (!tiltGravityEnabled) return
        // 重力·抛掷关闭时强制脚朝正下(0)，不随体感量化改朝向（onEach 已设 0）。
        if (!petView.physics.gravityEnabled) return
        val gx = petView.physics.tiltGx
        val gy = petView.physics.tiltGy
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
        if (target != petView.gravityDir) {
            if (petView.physics.isDragging) {
                // 提起态：直接更新脚朝向（=主重力方向），提起动画 LIFT_UP 实时旋转，
                // 不触发翻滚（roll 为独立逻辑，此处不动）。否则 enterTiltRoll 在拖动态会被
                // isDragging 守卫直接 return，导致 gravityDir 始终不更新、提起动画不旋转。
                petView.gravityDir = target
                recalcBounds()
            } else if (!petView.physics.isThrowing) {
                // 脚朝向突变：主动进入翻滚掩盖姿态硬切（方向变了=有新重力，翻滚符合物理直觉）。
                // 翻滚中(!isThrowing)才触发，避免方向高频变化时重复抢 ROLL 动画；
                // 翻滚期间再变只更新 gravityDir（姿态随 ROLL 自然转）。
                // 关键顺序：先 enterTiltRoll（切到居中对称的 ROLL 帧），再在 ROLL 内部设 gravityDir，
                // 避免先改重力旋转角导致【旧模式帧被新朝向硬转一帧】闪现，然后才切到 ROLL。
                petView.enterTiltRoll(target)
                // 旋转改变脚落点，重算边界使锚点活动范围与贴边/贴地对齐（开销极小）。
                // ROLL 锚点已特判为画布中心，rotateOffset 与 gravityDir 无关，顺序安全。
                recalcBounds()
            } else {
                petView.gravityDir = target
                recalcBounds()
            }
        }
    }

    private fun recalcBounds() {
        val vis = getScreenBounds()
        lastVisRect = vis
        // physics.x/y 为锚点坐标。要使图片四边紧贴可见区，需把锚点活动范围
        // 向内收缩一个“锚点到图片边界”的偏移：
        //   左界 = vis.left + ax         （图片左边贴 vis.left）
        //   右界 = vis.right - (w - ax)  （图片右边贴 vis.right）
        //   上界 = vis.top + ay          （图片上边贴 vis.top）
        //   下界 = vis.bottom - (h - ay) （图片下边贴 vis.bottom）
        // 【重要】必须用固定基准尺寸/锚点（getBase*），不能用当前帧尺寸（getBitmapSize）。
        // 否则 Roll/Walk 每帧尺寸/锚点不同会让边界逐帧抖动，并反复 clamp 位置 → 卡顿/横跳。
        val (w, h) = petView.getBaseBitmapSize()
        val (ax, ay) = petView.getBaseAnchorScaled()
        val minX = vis.left.toFloat() + ax + boundOffsetLeft
        val maxX = (vis.right.toFloat() - (w - ax) - boundOffsetRight).coerceAtLeast(minX)
        val minY = vis.top.toFloat() + ay + boundOffsetTop
        // 地面（下界）额外扣除输入法高度：键盘唤起时宠物落到键盘上方，不遮挡输入框；
        // 关闭键盘时 imeBottomInset=0，地面恢复。与用户设置的 boundOffsetBottom 叠加，互不覆盖。
        val imeBottomInset = maxOf(viewImeInset, globalImeInset)
        val maxY = (vis.bottom.toFloat() - (h - ay) - boundOffsetBottom - imeBottomInset).coerceAtLeast(minY)
        petView.physics.setBounds(minX, minY, maxX, maxY)
    }

    /**
     * 设置页辅助线：根据当前四边边界偏移，在屏幕对应边缘位置绘制黑色虚线，
     * 帮助用户理解“边界偏移”调整后宠物图片可贴合的实际屏幕边缘。
     * 坐标为物理屏幕像素（与 getScreenBounds() 同一坐标系）。
     */
    fun showBoundGuide() {
        val vis = getScreenBounds()
        val lines = listOf(
            // 左缘：x = 物理左 + 左偏移
            GuideLineView.Line(vis.left + boundOffsetLeft, vis.top.toFloat(), vis.left + boundOffsetLeft, vis.bottom.toFloat()),
            // 右缘：x = 物理右 - 右偏移
            GuideLineView.Line(vis.right - boundOffsetRight, vis.top.toFloat(), vis.right - boundOffsetRight, vis.bottom.toFloat()),
            // 上缘：y = 物理上 + 上偏移
            GuideLineView.Line(vis.left.toFloat(), vis.top + boundOffsetTop, vis.right.toFloat(), vis.top + boundOffsetTop),
            // 下缘：y = 物理下 - 下偏移
            GuideLineView.Line(vis.left.toFloat(), vis.bottom - boundOffsetBottom, vis.right.toFloat(), vis.bottom - boundOffsetBottom),
        )
        guideLineView?.setLines(lines)
        guideLineView?.visibility = android.view.View.VISIBLE
    }

    /**
     * 设置页辅助线：根据当前碎碎念偏移，在屏幕底部上方对应高度绘制一条水平黑色虚线，
     * 帮助用户理解碎碎念文字距屏幕底部的位置。
     */
    fun showTrayGuide() {
        val vis = getScreenBounds()
        val y = vis.bottom - thinkingOffsetY
        val lines = listOf(
            GuideLineView.Line(vis.left.toFloat(), y, vis.right.toFloat(), y)
        )
        guideLineView?.setLines(lines)
        guideLineView?.visibility = android.view.View.VISIBLE
    }

    /** 隐藏辅助线浮层 */
    fun hideGuide() {
        guideLineView?.setLines(emptyList())
        guideLineView?.visibility = android.view.View.GONE
    }

    /**
     * 点击穿透：开启后给浮窗窗口加 FLAG_NOT_TOUCHABLE，使其完全不接收触摸事件，
     * 点击/拖动/长按均穿透到下层应用；关闭则移除该 flag 恢复正常交互。
     */
    private fun applyClickThrough(enabled: Boolean) {
        val touchable = (layoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0
        if (enabled == !touchable) return  // 状态未变，避免无谓 updateViewLayout
        if (enabled) {
            layoutParams.flags = layoutParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            layoutParams.flags = layoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        try { windowManager.updateViewLayout(petView, layoutParams) } catch (_: Exception) {}
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
        return START_STICKY
    }

    /** 供浮窗菜单即时写入配置（如点击穿透 / 重力开关），写入即触发 applyConfig 生效 */
    fun updateConfig(block: PetConfigData.() -> PetConfigData) {
        scope.launch { config.update(block) }
    }

    /** 供浮窗菜单读取当前配置（初始化开关状态） */
    fun currentConfig(): PetConfigData = config.getBlocking()

    /** 处理菜单/广播/通知下发的动作（供浮窗菜单与 onStartCommand 共用） */
    fun performAction(action: String?) {
        // 吸附态：菜单里的动作切换一律忽略（探头常驻，仅用户手动拉出/召回才退出）。
        // 召回(RECALL)是用户主动退出，允许；隐藏(HIDE)只改可见性、不动吸附态，允许。
        if (petView.physics.isSnapped &&
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
                val p = petView.physics
                val cx = (p.minX + p.maxX) / 2f   // 底部中心（锚点屏幕坐标）
                val cy = p.maxY
                p.resetTo(cx, cy)
                petView.forceReset()
                // 若当前处于隐藏，同步修改设置与浮窗显示（与“显示宠物”开关同源同一标志）
                // 碎碎念跟随显示。
                trayMsgManager?.setVisible(thinkingEnabled)
                scope.launch {
                    config.update { if (!it.visible) it.copy(visible = true) else it }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        mainHandler.removeCallbacks(frameRunnable)
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        try { petMenu?.dismiss() } catch (_: Exception) {}
        // 注销体感重力传感器监听，避免泄漏
        try { sensorManager?.unregisterListener(tiltListener) } catch (_: Exception) {}
        try { if (debugView != null) windowManager.removeView(debugView) } catch (_: Exception) {}
        try { if (guideLineView != null) windowManager.removeView(guideLineView) } catch (_: Exception) {}
        guideLineView = null
        try { trayMsgManager?.destroy() } catch (_: Exception) {}
        trayMsgManager = null
        instance = null
        scope.cancel()
    }

    companion object {
        // 供设置页（同进程）直接驱动辅助线浮层，避免引入广播样板。
        var instance: PetService? = null
        const val CHANNEL_ID = "cakepet_channel"
        const val NOTIFICATION_ID = 1001
    }
}
