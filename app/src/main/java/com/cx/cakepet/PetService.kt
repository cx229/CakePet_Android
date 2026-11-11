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
import android.graphics.drawable.BitmapDrawable
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.MotionEvent
import android.widget.ImageView
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Surface
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
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
    var displayView: PetDisplayView?,
    var displayLp: WindowManager.LayoutParams?,
    var displayAdded: Boolean,
    var controlAdded: Boolean,
    var petMsg: TrayMsgManager,
    var roleId: String = "",
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
    // 当前操作中角色的 config（每次角色操作/手势交互前同步为该角色，供既有 currentConfig 使用）
    private lateinit var config: PetConfig
    // 当前操作中角色 id（operating role），供 currentBounds 回落；手势/操作时同步。
    private var currentRoleId: String = RoleRegistry.DEFAULT.id
    // 当前操作角色的简称（随实例独立重命名）；辅助线/红框吐槽等文案按此显示，避免硬编码“酥”。
    private var currentShortName: String = "角色"

    // 多角色：每个已召唤角色一份完全独立的运行态（各自 config/bounds/配置观察者/随机循环），互不干扰。
    private data class RoleRuntime(
        val roleId: String,
        var config: PetConfig,
        var bounds: PetBounds,
        // 资源包状态：按角色独立（不再用 PetService 全局变量，避免多角色互相覆盖导致全部渲染成同一角色）。
        var spritePackId: Int = ConfigDefaults.DEFAULT_SPRITE_PACK,
        var descriptor: RoleDescriptor? = null,
        var configJob: Job? = null,
        var randomJob: Job? = null,
        var tiltStrength: Float = 3000f,  // 本角色「重力强度」，体感按角色独立量化
        // 投喂（支鱼）：完全按角色独立（各自刷出循环 / 频率 / 数量 / 倍率 / 透明度，互不干扰）
        var fishSpawning: Boolean = false,
        var fishSpawnRunnable: Runnable? = null,
        var lastFeedingEnabled: Boolean = ConfigDefaults.FEEDING_ENABLED,
        var lastFeedingFishScale: Float = ConfigDefaults.FEEDING_FISH_SCALE,
        var lastFeedingFishAlpha: Float = ConfigDefaults.FEEDING_FISH_ALPHA,
        var lastFeedingMaxCount: Int = ConfigDefaults.FEEDING_MAX_COUNT,
        var lastFeedingAutoExpire: Boolean = ConfigDefaults.FEEDING_AUTO_EXPIRE,
        var lastFeedingExpireTime: Int = ConfigDefaults.FEEDING_EXPIRE_TIME,
        var lastFeedingFullScreen: Boolean = ConfigDefaults.FEEDING_FULL_SCREEN,
        var lastFeedingBorderShow: Boolean = ConfigDefaults.FEEDING_BORDER_SHOW,
        var lastFeedingPeriodMin: Int = ConfigDefaults.FEEDING_PERIOD_MIN,
        var lastFeedingPeriodMax: Int = ConfigDefaults.FEEDING_PERIOD_MAX,
        // 定格（咔嚓）：完全按角色独立（可拖动 / 边框按角色各自设置）
        var lastFreezeDraggable: Boolean = ConfigDefaults.FREEZE_DRAGGABLE,
        var lastFreezeBorderShow: Boolean = ConfigDefaults.FREEZE_BORDER_SHOW,
        // 随机切换周期：按角色独立检测变更
        var lastRandomPeriodMin: Int = ConfigDefaults.RANDOM_PERIOD_MIN,
        var lastRandomPeriodMax: Int = ConfigDefaults.RANDOM_PERIOD_MAX
    )
    private val engines = LinkedHashMap<String, RoleRuntime>()

    // 多实例：所有宠物实例集合（跨角色共存）。pets[0] 为主实例（调试浮层 / 菜单动作 / 体感方向基准）。
    private val pets = mutableListOf<PetInstance>()

    // 上次生效的宠物数量，用于检测配置中 petCount 变化并触发重建
    private var lastPetCount = 1

    // 定格显示层：长按菜单“定格-咔嚓”创建的纯展示浮窗（静态 Bitmap，不可点击、不拦截交互）。
    // 是否可拖动由全局开关 freezeDraggable 控制：关→NOT_TOUCHABLE（纯显示），开→可点击拖动。
    data class FreezeLayer(
    val iv: ImageView,
    val lp: WindowManager.LayoutParams,
    val time: Long,
    val note: String = "",
    val borderView: View? = null,  // 显示定格边框开关开启时的红色真实窗口边框（NOT_TOUCHABLE，不拦截交互）
    val roleId: String = ""   // 归属角色：应用「可拖动/边框」时按角色独立，避免串到其它角色
    )
    private val freezeLayers = mutableListOf<FreezeLayer>()
    // 投喂（支鱼）浮窗：使用 Functions 映射到通用 Mode 的动画；拖动给酥后按原有逻辑移除。
    data class FishLayer(
        val iv: ImageView,
        val lp: WindowManager.LayoutParams,
        val time: Long = System.currentTimeMillis(),  // 投食（支鱼）掉落的时刻，供食物浮窗顶部展示
        val borderView: View? = null,   // 投喂边框开关开启时的红色真实窗口边框（NOT_TOUCHABLE）
        var expireRunnable: Runnable? = null,
        var crop: Bitmap? = null,       // 当前 Mode 全部帧共用的固定大小画布，保证拖动/碰撞尺寸稳定
        var animation: ImageModeManager.ModeClip? = null, // 每条食物自己的完整 Mode 播放位置
        var modeId: String = "",         // 素材作者自定的 Mode ID（由 Functions 获取，不由代码指定）
        val packId: Int = 1,
        val roleId: String = ""
    )
    private val fishLayers = mutableListOf<FishLayer>()
    // 当前正被用户手指拖动的鱼。用于自动觅食吃判定：拖动中不吃(在手上)，放生才可能吃到。
    // 同一时刻仅一根手指，故单值；不挂在 FishLayer(data class) 上，以免被多处 copy() 重置。
    private var draggingFish: FishLayer? = null
    // 自动觅食：每只宠物(per-PetView)当前偏好目标食物引用（滞回后的“锁定”）。按角色独立，互不影响。
    // 非硬锁：每帧在本宠物活动范围内重选最近食物，仅当新目标比当前目标近 ≥1×(显示层宽+高) 才换（防墙角锚点抖动横跳）。
    private val forageFoodByView = mutableMapOf<PetView, FishLayer?>()
    // 每角色复用同一个现有动画管理器（Modes 解析和图片缓存共享），食物实例仅持有各自播放游标。
    private val modeAssetManagers = mutableMapOf<String, ImageModeManager>()
    // 注：投喂 / 定格 / 随机的「上次生效值缓存」「刷出循环状态」已下沉到 RoleRuntime（按角色独立），
    // 不再使用全局单例，避免多角色并存时互相覆盖（见 registerConfigObserver / loadConfig / teardownRole）。

    // 定格层拖动 + 长按：可拖动时既能跟随手指位移（delta，不触发提起/抛掷），又能长按弹出定格菜单。
    private class FreezeDragState {
        var lastX = 0f
        var lastY = 0f
        var moved = false          // 是否已判定为拖动（移动超阈值）
        var longPressFired = false // 长按候选是否已达成（达阈值且未移动）；真正打开延后到松手
        var feedOverPet = false    // 拖动中当前是否悬停在宠物命中区（用于投喂成功提示的边沿检测：进入即振动，离开复位后可再次振动）
        var entered = false        // 本段拖动中是否曾进入过宠物命中区（用于记录首次进入点）
        var enterX = 0f            // 首次进入宠物判定区的指针坐标（振动点），用于按相对左右决定镜像
        var enterY = 0f
        var pending: Runnable? = null
    }

    // 长按到位振动（仅作候选提醒，与宠物一致；真正打开菜单在松手时）
    private fun vibrateLongPress() {
        val vm = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vm?.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION") vm?.vibrate(30)
        }
    }

    private val freezeDragListener = View.OnTouchListener { v, e ->
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return@OnTouchListener false
        val st = (v.tag as? FreezeDragState) ?: FreezeDragState().also { v.tag = it }
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                st.lastX = e.rawX; st.lastY = e.rawY
                st.moved = false; st.longPressFired = false
                // 长按检测：400ms 内无明显移动→置候选并振动（不立即弹窗，延后到松手判定）
                val r = Runnable {
                    if (!st.moved) {
                        st.longPressFired = true
                        vibrateLongPress()
                    }
                }
                st.pending = r
                mainHandler.postDelayed(r, 400)
                true
            }
            MotionEvent.ACTION_MOVE -> {
                pets.firstOrNull { it.view === v }?.roleId?.let { syncRole(it) }
                val dx = e.rawX - st.lastX
                val dy = e.rawY - st.lastY
                st.lastX = e.rawX; st.lastY = e.rawY
                // 超过阈值视为拖动：取消长按候选（与宠物：拖动清 triggered 一致），允许跟手位移
                if (kotlin.math.abs(dx) > 8f || kotlin.math.abs(dy) > 8f) {
                    st.moved = true
                    st.pending?.let { mainHandler.removeCallbacks(it) }
                    st.pending = null
                    st.longPressFired = false
                }
                lp.x += dx.toInt()
                // 静态边界钳制使用【该定格层所属角色】的设置，而非全局 currentConfig()，保证定格按角色独立。
                val frid = freezeLayers.firstOrNull { it.iv === v }?.roleId
                lp.y = clampStaticY(lp.y + dy.toInt(), lp.height, roleConfig(frid).staticTopBound, roleConfig(frid).staticBottomBound)
                try { windowManager.updateViewLayout(v, lp) } catch (_: Exception) {}
                // 同步红色边框（若存在）跟随定格层移动
                freezeLayers.firstOrNull { it.iv === v }?.borderView?.let { bv ->
                    val blp = bv.layoutParams as? WindowManager.LayoutParams ?: return@let
                    blp.x = lp.x
                    blp.y = lp.y
                    try { windowManager.updateViewLayout(bv, blp) } catch (_: Exception) {}
                }
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                st.pending?.let { mainHandler.removeCallbacks(it) }
                st.pending = null
                // 松手且未移动→打开定格菜单（与宠物：长按候选 + 松手判定一致）
                if (st.longPressFired && !st.moved) openFreezeMenu(v)
                true
            }
            else -> false
        }
    }

    // 支鱼拖动 + 长按：与定格层一致；额外松手时若落在宠物显示层矩形内则消失（纯清除）。
    private val fishDragListener = View.OnTouchListener { v, e ->
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return@OnTouchListener false
        val st = (v.tag as? FreezeDragState) ?: FreezeDragState().also { v.tag = it }
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                draggingFish = fishLayers.firstOrNull { it.iv === v }   // 记录正被拖动的鱼(供觅食吃判定排除)
                st.lastX = e.rawX; st.lastY = e.rawY
                st.moved = false; st.longPressFired = false; st.feedOverPet = false
                st.entered = false; st.enterX = 0f; st.enterY = 0f
                // 长按检测：400ms 内无明显移动→置候选并振动（延后到松手判定打开菜单）
                val r = Runnable {
                    if (!st.moved) {
                        st.longPressFired = true
                        vibrateLongPress()
                    }
                }
                st.pending = r
                mainHandler.postDelayed(r, 400)
                true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - st.lastX
                val dy = e.rawY - st.lastY
                st.lastX = e.rawX; st.lastY = e.rawY
                if (kotlin.math.abs(dx) > 8f || kotlin.math.abs(dy) > 8f) {
                    st.moved = true
                    st.pending?.let { mainHandler.removeCallbacks(it) }
                    st.pending = null
                    st.longPressFired = false
                }
                lp.x += dx.toInt()
                lp.y += dy.toInt()
                // 关全屏→钳进所属角色活动范围(边界偏移矩形)，与宠物移动范围完全一致且跟着变；
                // 开全屏→不钳(任意位置)。范围比鱼小则 coerceIn 自动拉到最近边。
                val fishRid = fishLayers.firstOrNull { it.iv === v }?.roleId
                fishRid?.let { rid ->
                    fishAllowedRect(rid)?.let { rect ->
                        val wpx = lp.width; val hpx = lp.height
                        lp.x = lp.x.coerceIn(rect.left, (rect.right - wpx).coerceAtLeast(rect.left))
                        lp.y = lp.y.coerceIn(rect.top, (rect.bottom - hpx).coerceAtLeast(rect.top))
                    }
                }
                try { windowManager.updateViewLayout(v, lp) } catch (_: Exception) {}
                fishLayers.firstOrNull { it.iv === v }?.borderView?.let { bv ->
                    val blp = bv.layoutParams as? WindowManager.LayoutParams ?: return@let
                    blp.x = lp.x
                    blp.y = lp.y
                    try { windowManager.updateViewLayout(bv, blp) } catch (_: Exception) {}
                }
                // 拖动中实时判定是否命中任一宠物：进入命中区即振动一次；离开后复位，下次再进入再次振动
                val overPet = fishDroppedOnPet(e.rawX, e.rawY)
                if (overPet && !st.feedOverPet) {
                    st.feedOverPet = true
                    st.entered = true; st.enterX = e.rawX; st.enterY = e.rawY
                    vibrateLongPress()
                    // Touching a pet with a held fish offers one opportunity to
                    // reselect its normal function. The pet engine decides whether
                    // that selection would be forage; never force a mode here.
                    findPetAt(e.rawX, e.rawY)?.view?.requestForageLure()
                } else if (!overPet) {
                    st.feedOverPet = false
                }
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                draggingFish = null   // 松手/取消：不再处于拖动中，恢复可被吃
                st.pending?.let { mainHandler.removeCallbacks(it) }
                st.pending = null
                if (st.longPressFired && !st.moved) {
                    openFishMenu(v)
                } else if (st.moved && !st.longPressFired) {
                    // 拖动给酥：松手点落在宠物显示层矩形内则消失，并触发投食动作
                    val layer = fishLayers.firstOrNull { it.iv === v } ?: return@OnTouchListener true
                    if (fishDroppedOnPet(e.rawX, e.rawY)) {
                        val inst = findPetAt(e.rawX, e.rawY) ?: return@OnTouchListener true
                        // 先记录角色：食物属于谁（系数来源）、谁吃下（放大对象），removeFish 后 layer 仍可取字段
                        val foodRoleId = layer.roleId
                        val eatRoleId = inst.roleId
                        removeFish(layer)
                        // 投喂成功：用鱼首次进入宠物判定区（振动点）相对宠物中心的左右决定镜像；
                        // 未记录到进入点（理论不会，因投喂必经 MOVE 进入）则用松手点兜底。
                        val ix = if (st.entered) st.enterX else e.rawX
                        val iy = if (st.entered) st.enterY else e.rawY
                        val dlp = inst.displayLp
                        val (relX, relY) = if (dlp != null) {
                            (ix - (dlp.x + dlp.width / 2f)) to (iy - (dlp.y + dlp.height / 2f))
                        } else (0f to 0f)
                        triggerFeedAction(inst, relX, relY)
                        // 谁吃谁变大；倍率/开关取决于【被吃食物】的角色（吃什么用什么倍率）
                        growPetOnFeed(eatRoleId, foodRoleId)
                    }
                }
                true
            }
            else -> false
        }
    }

    /** 长按定格层：找到对应层并弹出定格菜单（样式与长按宠物一致）。 */
    private fun openFreezeMenu(v: View) {
        val layer = freezeLayers.firstOrNull { it.iv === v } ?: return
        // 先把当前角色切到该定格层所属角色，菜单内 currentConfig()/updateConfig 才会落到正确角色。
        syncRole(layer.roleId)
        FreezeMenu(this, layer).show()
    }

    /** 在指定位置创建一个静态 Bitmap 定格显示层（纯展示；可拖动性由【该角色】freezeDraggable 决定）。 */
    fun addFreezeLayer(roleId: String, snap: PetView.FreezeSnapshot) {
        // 读【该角色自身】配置：定格可拖动 / 边框 / 静态边界都随角色独立，不再用全局 currentConfig()。
        val rc = roleConfig(roleId)
        val draggable = rc.freezeDraggable
        val iv = ImageView(this).apply {
            setImageBitmap(snap.bmp)
            scaleType = ImageView.ScaleType.FIT_XY
        }
        val lp = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            format = PixelFormat.TRANSLUCENT
            // FLAG_LAYOUT_NO_LIMITS：定格层可放置在屏幕任意位置（含屏外），拖动时不受屏幕/活动范围约束。
            // FLAG_LAYOUT_IN_SCREEN：窗口 (0,0) 以物理屏幕左上角为原点（含状态栏区），而非活动窗口(状态栏下方)，
            //   使 getScreenBounds() 的物理坐标 1:1 映射到屏幕、不受状态栏显隐影响（否则会被整体下移状态栏高度并随其跳变）。
            // 可拖动性由 freezeDraggable 决定：开→可点击拖动；关→NOT_TOUCHABLE 纯显示、不拦截交互。
            flags = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    if (draggable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            gravity = Gravity.TOP or Gravity.START
            width = snap.w
            height = snap.h
            x = snap.left
            y = clampStaticY(snap.top, snap.h, rc.staticTopBound, rc.staticBottomBound)
        }
        if (draggable) iv.setOnTouchListener(freezeDragListener)
        try { windowManager.addView(iv, lp) } catch (_: Exception) { snap.bmp.recycle(); return }
        val layer = FreezeLayer(iv, lp, snap.time, thinkingPetMsg(roleId)?.samplePackText().orEmpty(), roleId = roleId)
        freezeLayers.add(layer)
        // 显示定格边框开关开启时，叠加红色真实窗口边框（调试用，不拦截交互）
        if (rc.freezeBorderShow) setFreezeBorder(layer, true)
        if (keyboardUp) { recalcBounds(); syncKeyboardAuxiliary(roleId, rc, true) }
    }

    /** 为指定定格层创建红色真实窗口边框（NOT_TOUCHABLE，不拦截拖动/长按）。 */
    private fun createFreezeBorderView(layer: FreezeLayer): View {
        val bv = View(this).apply { background = getDrawable(R.drawable.freeze_border) }
        val blp = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            format = PixelFormat.TRANSLUCENT
            // FLAG_LAYOUT_IN_SCREEN：使窗口原点对齐物理屏幕左上角，坐标不受状态栏显隐影响。
            flags = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            gravity = Gravity.TOP or Gravity.START
            width = layer.lp.width
            height = layer.lp.height
            x = layer.lp.x
            y = layer.lp.y
        }
        try { windowManager.addView(bv, blp) } catch (_: Exception) {}
        return bv
    }

    /** 为指定定格层开启/关闭红色真实窗口边框（调试用）。 */
    private fun setFreezeBorder(layer: FreezeLayer, on: Boolean) {
        val idx = freezeLayers.indexOf(layer)
        if (idx < 0) return
        val cur = freezeLayers[idx]
        if (on && cur.borderView == null) {
            freezeLayers[idx] = cur.copy(borderView = createFreezeBorderView(cur))
        } else if (!on && cur.borderView != null) {
            try { windowManager.removeView(cur.borderView) } catch (_: Exception) {}
            freezeLayers[idx] = cur.copy(borderView = null)
        }
    }

    /** 清除定格显示层（回收 Bitmap，防泄漏）。roleId 为 null 时清除全部；否则仅清除【该角色】的定格层。 */
    fun clearAllFreeze(roleId: String? = null) {
        val list = if (roleId == null) freezeLayers.toList() else freezeLayers.filter { it.roleId == roleId }
        for (layer in list) {
            try { windowManager.removeViewImmediate(layer.iv) } catch (e: Exception) {
                android.util.Log.w("PetService", "移除定格浮窗失败：${layer.roleId}", e)
            }
            (layer.iv.drawable as? BitmapDrawable)?.bitmap?.recycle()
            layer.borderView?.let { try { windowManager.removeViewImmediate(it) } catch (_: Exception) {} }
        }
        freezeLayers.removeAll(list)
        list.forEach { imeFreezeOffsets.remove(it.iv) }
    }

    /** 全部定格显示层重置位置到屏幕中心（y 受静态边界约束）。roleId 为 null 时重置全部；否则仅重置【该角色】的定格层。 */
    fun resetAllFreezeToCenter(roleId: String? = null) {
        val (sw, sh) = screenRealSize()
        val cfg = roleConfig(roleId)
        val list = if (roleId == null) freezeLayers.toList() else freezeLayers.filter { it.roleId == roleId }
        for (layer in list) {
            val cx = (sw - layer.lp.width) / 2
            val cy = clampStaticY((sh - layer.lp.height) / 2, layer.lp.height, cfg.staticTopBound, cfg.staticBottomBound)
            layer.lp.x = cx
            layer.lp.y = cy
            try { windowManager.updateViewLayout(layer.iv, layer.lp) } catch (_: Exception) { }
            try { layer.borderView?.let { windowManager.updateViewLayout(it, layer.lp) } } catch (_: Exception) { }
        }
    }

    /** 仅清除指定定格显示层（回收其 Bitmap）。 */
    fun removeFreezeLayer(layer: FreezeLayer) {
        try { windowManager.removeView(layer.iv) } catch (_: Exception) {}
        (layer.iv.drawable as? BitmapDrawable)?.bitmap?.recycle()
        layer.borderView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        freezeLayers.remove(layer)
        imeFreezeOffsets.remove(layer.iv)
    }

    /** 更新指定定格层的备注文本（失焦自动保存，仅存于内存，随层清除而丢失）。 */
    fun updateFreezeNote(layer: FreezeLayer, note: String) {
        val idx = freezeLayers.indexOf(layer)
        if (idx < 0) return
        freezeLayers[idx] = freezeLayers[idx].copy(note = note)
    }

    /** 定格-拖动开关变化：即时作用于【该角色】已有定格层（开→可点击拖动；关→纯显示不可点击），不串其它角色。 */
    private fun applyFreezeDraggable(roleId: String, on: Boolean) {
        for (layer in freezeLayers.filter { it.roleId == roleId }) {
            // 始终保持 FLAG_LAYOUT_NO_LIMITS：定格层位置不受屏幕/活动范围约束，可任意摆放。
            layer.lp.flags = if (on) WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                             else WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            if (on) layer.iv.setOnTouchListener(freezeDragListener) else layer.iv.setOnTouchListener(null)
            if (keyboardUp && roleCfg(roleId)?.keyboardMode() in listOf("pass", "hide"))
                layer.lp.flags = layer.lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            try { windowManager.updateViewLayout(layer.iv, layer.lp) } catch (_: Exception) {}
        }
    }

    // 显示/控制分离（主战场为安卓，鸿蒙尽力）：每只宠物一个独立显示窗（PetDisplayView），
    // 纯展示层用 NOT_TOUCHABLE【不接任何点击、事件穿透给下层 app】，由控制窗承接拖动；
    // 控制窗 NOT_TOUCH_MODAL 仅在自身命中区（脚盒/整帧）消费点击。因安卓端小 NOT_TOUCHABLE 窗正常穿透，
    // 显示层不再“偷吃”宠物身上的点击（CORE 模式点身体也能穿透）。鸿蒙对小 NOT_TOUCHABLE 窗穿透属已知未知，尽力兼容。
    // 仅设置期/调试用的大尺寸辅助线（边框/偏移/键盘示意）走独立 guide 窗（NOT_TOUCHABLE、显式尺寸）。
    private var guideParams: WindowManager.LayoutParams? = null
    private var guideAdded = false

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
    // 用户「点击穿透」：语义上等同于“隐藏控制层”（显示窗本就 NOT_TOUCHABLE，屏蔽控制窗即可让点击
    // 穿透到下层 app 且宠物仍可见），统一走 syncControlWindows。调试「强制无透明」：开启时显示窗去掉
    // FLAG_NOT_TOUCHABLE（配合宠物窗口 alpha=1）以避开系统对“NOT_TOUCHABLE 且 alpha>0.8 必钳半透明”
    // 的逻辑，使宠物 100% 不透明；与“隐藏控制层/点击穿透”互斥（二者任一开显示窗须恢复 NOT_TOUCHABLE）。
    //
    // ⚠️ 这些开关（hideControlLayer / clickThrough / forceOpaque / hideStage）必须【按角色独立】，
    // 不得共享全局变量：原全局镜像会导致多角色并存时一个角色改值污染另一个角色的窗口状态。
    // 改为按 roleId 直接读该角色自己的 PetConfig（engines[roleId].config），调用点均按 roleId 过滤。
    private fun roleCfg(roleId: String): PetConfigData? = engines[roleId]?.config?.getBlocking()
    /** Only this decides whether the pet exists visually and can receive touches.
     *  Main-page hide is persistent; keyboard hide is temporary and per-role.
     *  Do not confuse either with hideStage (a display-layer diagnostic switch).
     */
    private fun rolePetVisible(roleId: String): Boolean =
        roleCfg(roleId)?.let { c ->
            c.visible && c.enabled && !(keyboardUp && c.keyboardMode() == "hide")
        } ?: true

    /** 该角色控制窗是否应隐藏：隐藏控制层 或 点击穿透，二者任一即隐藏。 */
    private fun roleControlHidden(roleId: String): Boolean =
        roleCfg(roleId)?.let {
            it.hideControlLayer || it.clickThrough || (keyboardUp && it.keyboardMode() == "pass")
        } ?: false
    /** 该角色显示窗是否应带 FLAG_NOT_TOUCHABLE：控制层隐藏/穿透/隐藏画布 或 未强制无透明 → 带（穿透）；
     *  强制无透明且控制层可见 → 不带（100% 不透明，代价是显示层吃宠物区点击）。 */
    private fun roleDisplayNotTouchable(roleId: String): Boolean =
        roleControlHidden(roleId) || roleCfg(roleId)?.interactionMode != "display-opaque"

    private fun roleDisplayType(roleId: String): Int =
        if (roleCfg(roleId)?.interactionMode == "core-opaque" &&
            CakePetAccessibilityService.active != null)
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    /** Accessibility overlays are owned by the accessibility service window context. */
    private fun addDisplay(view: PetDisplayView, lp: WindowManager.LayoutParams): Boolean =
        if (lp.type == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
            CakePetAccessibilityService.active?.attachPetDisplay(view, lp) == true
        else try { windowManager.addView(view, lp); true } catch (_: Exception) { false }

    private fun removeDisplay(view: PetDisplayView, lp: WindowManager.LayoutParams) {
        if (lp.type == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY &&
            CakePetAccessibilityService.active != null)
            CakePetAccessibilityService.active?.detachPetDisplay(view)
        else try { windowManager.removeViewImmediate(view) } catch (_: Exception) {}
    }

    private fun updateDisplay(view: PetDisplayView, lp: WindowManager.LayoutParams) {
        if (lp.type == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
            CakePetAccessibilityService.active?.updatePetDisplay(view, lp)
        else try { windowManager.updateViewLayout(view, lp) } catch (_: Exception) {}
    }

    /** Service revoked mid-session: persist the safe fallback, without changing alpha or core sizes. */
    fun onAccessibilityUnavailable() {
        engines.values.forEach { rt ->
            if (rt.config.getBlocking().interactionMode == "core-opaque") scope.launch {
                rt.config.update { it.copy(interactionMode = "core-transparent",
                    hitMode = ConfigDefaults.HIT_CORE, forceOpaque = false) }
            }
        }
    }
    /** 该角色是否隐藏画布（调试：隐藏显示窗）。 */
    private fun roleHideStage(roleId: String): Boolean = roleCfg(roleId)?.hideStage ?: false
    // 宠物整体透明度（0.1~1，1=完全不透明），需逐显示窗设置；单独记账以便 hideStage 重挂后保持。
    private var petAlpha = 1f

    /**
     * 按【该角色】displayNotTouchable 实时增删其各显示窗的 FLAG_NOT_TOUCHABLE（按 roleId 独立，不串其它角色）。
     * 配合宠物窗口 alpha=1：去掉该 flag 即避开 InputDispatcher 对“NOT_TOUCHABLE 且 alpha>0.8 必然钳制半透明”的逻辑，
     * 使该角色宠物可 100% 不透明；恢复该 flag 则回到点击穿透/控制层隐藏生效的默认态。运行时改配置即时生效，无需重建窗口。
     */
    private fun applyDisplayTouchFlags(roleId: String) {
        for (inst in pets.filter { it.roleId == roleId }) {
            val dlp = inst.displayLp ?: continue
            val disp = inst.displayView ?: continue
            val newFlags = if (roleDisplayNotTouchable(roleId)) {
                dlp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            } else {
                dlp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            }
            if (dlp.flags != newFlags) {
                dlp.flags = newFlags
                updateDisplay(disp, dlp)
            }
        }
    }

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
    // 仅单个碎碎念：开启=仅主实例(index 0)显示；关闭=每个实例各自显示（调试页开关，默认开）
    private var singleTrayMsg = ConfigDefaults.SINGLE_TRAY_MSG
    // 资源包缓存：各预装资源包构建缓存（assets 扫描仅一次，按 packId 缓存，与具体角色无关）。
    private val spritePackCache = mutableMapOf<Int, SpritePack>()
    // 注意：当前“资源包 id / 角色素材描述符”已下放到每角色 RoleRuntime（spritePackId / descriptor），
    // 不再用 PetService 全局变量，避免多角色并存时互相覆盖导致全部渲染成同一角色（详见 resolveCurrentSpritePack）。

    /** 解析指定角色的精灵包：FILES 上传包按 rootPath 走私有存储；预装按 packId 走映射。 */
    private fun resolveCurrentSpritePack(rt: RoleRuntime): SpritePack {
        val d = rt.descriptor
        return if (d != null && d.source == RoleSource.FILES) buildSpritePackForRole(this, d)
        else spritePackFor(rt.spritePackId)
    }

    // 设置页调整“边界偏移/碎碎念偏移”时显示的黑色虚线辅助层（GuideLineView）。
    // 它是【独立窗口】（NOT_TOUCHABLE、显式尺寸），由 syncGuideWindow 按需挂载/卸载，
    // 仅设置期/调试出现，低频，不波及日常其它 app。
    private var guideLineView: GuideLineView? = null

    // 体感重力（倾斜重力）：由手机重力传感器决定重力方向。无需权限。
    // 重力强度复用 config.gravity，倾斜越大等效重力越大。
    private var sensorManager: SensorManager? = null
    private var gravitySensor: Sensor? = null
    private var tiltGravityEnabled = false
    private var tiltStrength = 3000f   // 与设置页“重力强度”联动，作为传感器分量→加速度的缩放基准
    // 屏幕旋转基准：唯一权威来源 = DisplayManager 真实屏幕旋转（onDisplayChanged 回调 + 体感每帧回填），
    // 与渲染/边界/脚方向同源，横屏方向严格对应屏幕。@Volatile 保证传感器后台线程可见性。
    @Volatile private var cachedRotation = Surface.ROTATION_0
    // 屏幕旋转监听：用 DisplayManager.DisplayListener 监听真实屏幕旋转变化（后台/overlay 可靠，优于 OrientationEventListener）。
    private var displayListener: DisplayManager.DisplayListener? = null

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

    // Persist the set of summoned roles for system-initiated service recreation.
    private val backgroundPrefs by lazy { getSharedPreferences("cakepet_background", MODE_PRIVATE) }

    /** Persist the summoned instances, not merely the first home-card instance. */
    private fun saveSummonedRoles() {
        backgroundPrefs.edit()
            .putStringSet("summoned_roles", engines.keys.toSet())
            .apply()
    }
    private fun restoreSummonedRoles() {
        val active = RoleStore.getHomeRoles(this).map { it.instanceId }.toSet()
        val remembered = backgroundPrefs.getStringSet("summoned_roles", emptySet())
            .orEmpty().filter { it in active }
        remembered.forEach { if (!engines.containsKey(it)) startRole(it) }
    }
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
    private var lastLocalImeCheck = 0L
    // 输入法适应开关：关闭后不再因键盘弹出抬高宠物地面（imeAdapt=false 时不生效）。
    private var imeAdaptEnabled = true
    // 抬高偏移（px）：键盘抬高后的宠物地面再额外修正（正=更高）。仅 imeAdapt 生效时叠加。
    private var imeLiftOffset = 0f
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
            // Local lift is an event-based alignment, not a periodic physics correction.
            // Do not re-snap a moving / climbing pet each 200ms into a fake fall.

            // 自动觅食驱动（攀岩走壁子开关）：在动画推进前计算每只宠物的觅食状态，
            // 选最近食物/沿墙走/吃到投喂。优先级高于飞跃但不拦截（无食物则回落飞跃/选池）。
            for (inst in pets) {
                val v = inst.view
                val rc = roleConfig(inst.roleId)
                // 生效条件：攀岩已开(上级移动+攀岩) 且 本开关开 且 当前攀岩态 且 非吸附态（吸附态不喜乱动）。
                v.forageMultiplier = rc.forageMultiplier   // 自动觅食倍率：放大当前帧 dx/dy，不注入额外导航速度
                v.autoForage = rc.autoForage && v.hasFunction("climb", "forage", "move")     // 自动觅食开关：供 PetView 落地决策判定是否插队进入攀岩态
                val effForage = v.wallClimb && rc.autoForage && !v.physics.isSnapped &&
                    !v.physics.isThrowing && !v.physics.isDragging
                if (!effForage) {
                    forageFoodByView.remove(v)
                    v.setForage(false, 0f, 0f)
                    continue
                }
                // 候选过滤 + 目标选择：本层只过滤活动范围，坐标交给 PetView.tick，
                // 由其与「转方向」同帧、直接用【沿墙两段剩余弧长】的最小值选目标并定向（无任何阈值）。
                val dispW = inst.displayLp?.width?.toFloat() ?: 0f
                val dispH = inst.displayLp?.height?.toFloat() ?: 0f
                val pcx = (inst.displayLp?.x ?: 0) + dispW / 2f
                val pcy = (inst.displayLp?.y ?: 0) + dispH / 2f
                // 纯静态判定：只看「鱼中心」是否落在【本角色活动范围】矩形内。
                // 该矩形由「边界偏移」算出，与宠物当前位置/朝向/动画帧锚点【完全无关】，
                // 因此结果稳定，不会时而能追、时而不能。
                val act = roleActivityRect(inst.roleId)
                val inRange: (FishLayer) -> Boolean = { fl ->
                    val fcx = fl.lp.x + fl.lp.width / 2f
                    val fcy = fl.lp.y + fl.lp.height / 2f
                    fcx >= act.left && fcx <= act.right && fcy >= act.top && fcy <= act.bottom
                }
                // 候选：仅本宠物活动范围内可达（跨角色、含拖动中鱼，均为既有特性）
                val cand = fishLayers.filter(inRange)
                // 无食物：不拦截，回落飞跃/选池。
                if (cand.isEmpty()) {
                    forageFoodByView.remove(v)
                    v.setForage(false, 0f, 0f)
                    continue
                }
                val xs = FloatArray(cand.size) { cand[it].lp.x + cand[it].lp.width / 2f }
                val ys = FloatArray(cand.size) { cand[it].lp.y + cand[it].lp.height / 2f }
                v.setForageCandidates(true, xs, ys)
                // Discover candidates before selection, but do not chase or eat until
                // the normal action pool has actually selected the forage behavior.
                if (!v.isForageTravelActive()) {
                    forageFoodByView.remove(v)
                    continue
                }
                // 回读 tick 选中的目标（按坐标原样匹配，避免候选数组下标因增删而错位）
                val cX = v.forageChosenX
                val cY = v.forageChosenY
                val locked = if (!cX.isNaN()) cand.firstOrNull {
                    (it.lp.x + it.lp.width / 2f) == cX && (it.lp.y + it.lp.height / 2f) == cY
                } else null
                forageFoodByView[v] = locked
                if (locked == null) continue
                val fcx = locked.lp.x + locked.lp.width / 2f
                val fcy = locked.lp.y + locked.lp.height / 2f
                // 吃到判定：复用正常投喂的「显示层边界命中」逻辑（与拖鱼投喂同一套 findPetAt），
                // 不再自写 50px 中心距；宠物显示层矩形覆盖到食物中心即视为吃到，方向/投食动作/变大均走正常链路。
                val eaten = findPetAt(fcx, fcy) == inst && locked !== draggingFish
                if (eaten) {
                    // 吃到：正常投喂+变大，移除食物，解除锁定 → 下一帧继续选池（可能有下一个食物）。
                    removeFish(locked)
                    triggerFeedAction(inst, fcx - pcx, fcy - pcy)
                    growPetOnFeed(inst.roleId, locked.roleId)
                    forageFoodByView.remove(v)
                    v.setForage(false, 0f, 0f)
                } else {
                    // 未吃到：朝该食物中心沿墙走去（PetView.tick 内按所攀墙轴向转向）。
                    v.setForage(true, fcx, fcy)
                }
            }
            // 动画推进：用真实 dt（时延精确），绝不控制位置。每实例独立。
            for (inst in pets) inst.view.tick(dt)
            tickFishModes(dtMs)
            // 边界重算统一在本帧（tick 已刷新 currentFrameAnchor 快照之后）执行：
            // 传感器线程置位的 boundsDirty 在此落到主线程，与下方窗口定位用同一份锚点快照，
            // 避免脚方向变化时锚点/位置被跨线程改写后重算造成的跳变。
            if (boundsDirty) {
                boundsDirty = false
                recalcBounds()
            }
            // 吸附态：snapRotation 由 updateAnimByState 按贴附边赋值，边界必须用同一旋转重算，
            // 否则 snapTo 落点/钳制基于旧的重力态边界，浮窗与旋转后的图片错位。
            // 仅吸附态每帧轻量重算（仅几次加减），非吸附态边界已随配置/缩放变化触发，无需每帧。
            for (inst in pets) if (inst.view.physics.isSnapped) recalcBoundsFor(inst.view, getScreenBounds(), inst.roleId)
            // 单一位置写入点：物理循环统一写入窗口左上角（physics.x/y 即浮窗左上角真值）。
            // 坐标语义：physics.x/y = 浮窗左上角（屏幕坐标）；脚锚点 = physics ∓ anchorOff 仅贴边/snap 时派生。
            // 钳制已在 physics.setBounds 内完成（min/max 为窗口范围，由 recalcBoundsFor 用 getBaseAnchorScaled
            // 同源换算）。移动/速度/拖拽/抛掷直接改 physics.x/y，与锚点解耦 → 锚点变化不再牵动位置。
            // 主实例尺寸 (w,h) 同时用于调试浮层/吸附线绘制。
            // 主实例 = 实时 pets[0]，禁止再用缓存别名 petView/layoutParams。
            // 别名仅在“最后召唤角色”创建时赋值一次，多角色并存或召回后易与 pets[0] 错位，
            // 会导致主实例控制窗被帧循环漏更（且被 i>=1 克隆循环跳过）→ 控制窗卡死、显示窗照动。
            val mainInst = pets.getOrNull(0)
            val v0 = mainInst?.view ?: petView
            val mlp = mainInst?.layoutParams ?: layoutParams
            // 主实例整帧尺寸（调试浮层/吸附线仍按整只宠物绘制，与命中模式无关）。
            // 必须用 getBaseBitmapSize()：与 recalcBounds/getBaseAnchorScaled（基准帧 baseFrameW/H）
            // 及 render/PetStageView 实际绘制窗口完全同源；getBitmapSize()(globalW) 与之不符会导致控制窗尺寸错位。
            // 控制窗定位：单一数据源 placeControlWindow（CORE=脚盒、其余=整帧），尺寸变化才写 width/height。
            if (placeControlWindow(v0, mlp, lastW, lastH)) {
                lastW = mlp.width
                lastH = mlp.height
            }
            // 主实例整帧尺寸（调试浮层 updateDebug 仍按整只宠物绘制，与命中模式无关）。
            val (w, h) = v0.getBaseBitmapSize()
            try {
                windowManager.updateViewLayout(v0, mlp)
            } catch (_: Exception) {
            }
            // 主实例显示窗同步（位置/尺寸与位图矩形一致；窗外穿透由 NOT_TOUCH_MODAL 保证）。
            mainInst?.let { syncDisplayWindow(it) }
            // 其余实例：各自写位置 + 尺寸 + 刷新（不依赖主实例 lastW/lastH）。
            for (i in 1 until pets.size) {
                val inst = pets[i]
                val v = inst.view
                val ilp = inst.layoutParams
                // 控制窗定位：与主实例同源 placeControlWindow（CORE=脚盒、其余=整帧）。
                if (placeControlWindow(v, ilp, inst.lastW, inst.lastH)) {
                    inst.lastW = ilp.width
                    inst.lastH = ilp.height
                }
                try {
                    windowManager.updateViewLayout(v, ilp)
                } catch (_: Exception) {
                }
                syncDisplayWindow(inst)
            }

            // 每帧强制重绘：让物理位置与位图每帧都画出来。
            // 原 roll 版删掉了 invalidate，改靠“尺寸变化才改 layoutParams”间接触发 onDraw，
            // 但同尺寸相邻帧（Roll 序列很常见）不会触发重绘 → 位置算出来了屏幕却半秒才画一次 → 卡顿。
            // 这里每帧对各显示窗统一刷新位置并 invalidate（syncDisplayWindow 已做），控制窗(PetView) 本身不再自绘。

            // 吸附判定线：仅当【处于吸附态】且【吸附判定线开关(showSnapLine)开启】时显示。
            // 虚拟地面 = 用户在设置页拖动时看到的边界偏移线（boundLines），直接复用其坐标，
            // 位置与设置页偏移线完全一致；与宠物图片/帧锚点无关。
            // 遍历【所有实例】各算一段（修复：旧实现只驱动主实例，多宠物时其它宠物不显示）。
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
                    if (engines.isNotEmpty()) {
                        recalcBounds()
                        refreshCachedRotation()  // 旋转/折叠屏变化后更新体感重力方向基准
                    }
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
        // 引擎启动在 onCreate 末尾由 summonRole 统一完成（config 在 startEngine 内按角色创建）
        // 碎碎念独立浮窗由各宠物实例（PetInstance）各自持有一个，与服务生命周期绑定，
        // 主实例的 petMsg 别名存于 trayMsgManager，由 initPetView() 赋值。
        createNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        setupDebugWindows()
        initDisplayRotationListener()

        // 初始位置已在 initPetView 的 addView 前算好（底部中心），此处仅同步可见区并刷新边界。
        // 初始位置/边界在 startEngine 内算（initPetView 的 addView 前已算好底部中心）

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_CONFIGURATION_CHANGED)
        }
        registerReceiver(screenReceiver, filter)

        // 角色召唤交由 onStartCommand 处理（指定角色或按玩家上次实际召唤状态恢复）。
    }

    // 新召唤在最终定位前，禁止挂载宠物窗口。
    private val pendingInitialPlacement = mutableSetOf<String>()

    private fun initPetView(roleId: String) {
        // 先创建主实例（index 0），它承载调试浮层（吸附线 / 调试文本 / 辅助线）与菜单动作基准。
        // 启动瞬间：数量上限限 3（调试页允许设到 10，但启动只生成最多 3，避免一次性过多；
        // 启动后用户改数量走 registerConfigObserver，不受此上限，下次启动仍从 3 起）。
        val count = config.getBlocking().petCount.coerceIn(1, 3)
        for (i in 0 until count) {
            val inst = createPetInstance(i, roleId)
            inst.roleId = roleId
            pets.add(inst)
            if (i == 0) {
                petView = inst.view
                layoutParams = inst.layoutParams
                trayMsgManager = inst.petMsg
            }
        }

        // 诊断开关 hideStage：开启时完全不创建/移除显示窗（宠物仅剩透明控制窗，即 9.27 几何），
        // 用于二分点击拦截来源。显示窗在 createPetInstance 中按 hideStage 决定是否挂载。
        // 调试浮层与辅助线浮窗改为在 setupDebugWindows() 中一次性创建（避免角色切换时重复 addView），
        // 不再在此处创建。下方 lastPetCount 仍在此记录。
        // 记录已创建的实例数量（与 initPetView 生成数口径一致），避免 registerConfigObserver
        // 首个配置事件因 petCount 与初始值不同而立即重建
        lastPetCount = config.getBlocking().petCount.coerceIn(1, 20)
    }

    // 创建第 index 个宠物实例：独立 View / 物理 / 碎碎念 / 位置。主实例额外挂吸附线浮层。
    private fun createPetInstance(index: Int, roleId: String): PetInstance {
        val view = PetView(this)
        val lp = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            format = PixelFormat.TRANSLUCENT
            // FLAG_LAYOUT_IN_SCREEN：控制窗 (0,0) 对齐物理屏幕左上角，与显示窗(已加该 flag)共用物理坐标系，
            // 使 placeControlWindow 的 lp.x/y(来自 physics.x/y 物理坐标) 与显示窗落点一致；
            // 否则控制窗原点在活动窗口(状态栏下方)，会相对显示窗整体下移状态栏高度，导致点击/拖动坐标偏移。
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            gravity = Gravity.TOP or Gravity.START
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
        }
        val petMsg = TrayMsgManager(this, roleId)
        view.onThinkingModeEntered = { config, texts -> petMsg.applyModeThinking(config, texts) }

        view.onLongPress = { showMenu(view) }
        // 交互前先切到本实例所属角色（长按不拖动也会触发，确保菜单/双击等都落在正确角色配置上）。
        view.setOnTouchListener { v, e ->
            if (e.action == MotionEvent.ACTION_DOWN) {
                pets.firstOrNull { it.view === v }?.roleId?.let { syncRole(it) }
            }
            false // 返回 false，仍交由 PetView 自身 onTouchEvent 处理拖拽/双击等
        }
        view.onDoubleTap = {
            // 摸头（双击）：吸附态选用对应的摸头功能动作，其余（含攀岩态）播放普通摸头，
            // 攀岩态下由 climbRotation 把脚旋向所攀墙、播完仍回攀岩态。
            // 模式名由素材包 functions 提供（v1.01），代码只按【功能路径】寻址：
            // 包未提供对应功能则整段跳过，不做回退、不发空 setMode。
            if (view.physics.isSnapped) {
                view.modeOf("snap", "pat_head", "pat_head")?.let { view.playOnce(it) }
            } else {
                view.modeOf("pat_head", "pat_head")?.let { view.playOnce(it) }
            }
        }
        view.onSizeChanged = { recalcBounds() }
        // 拖拽拉出吸附态：PetView 归零残留朝向后会回调，按非吸附态重算活动范围（minX/maxX 等）
        view.onSnapExit = { recalcBounds() }
        // 帧锚点偏移或重力方向变化（非拖拽/非吸附）：吸附拉出后 SNAP_HEAD 旋转帧切到 SIT/ROLL 0° 帧，
        // anchorOff 剧变使旧边界盒失效、脚会被夹到屏幕外；此回调按新帧重算并 clamp 窗口，脚回到界内。
        view.onBoundsMaybeDirty = { recalcBounds() }
        // 输入法（键盘）显隐：view 级补充来源；与全局检测取较大值
        view.onImeInsetChanged = { h ->
            viewImeInset = h.toFloat()
            applyImeInset()
        }
        view.onPositionChanged = { cx, cy ->
            // 回调传的是锚点坐标（手指 + 抓取偏移）。拖拽期间物理 step 不跑，
            // 直接更新锚点并立即 clamp + 写出浮窗位置（跟手，且单一数据源 physics.x/y）。
            // 控制窗定位用 placeControlWindow（与帧循环同源）：CORE=脚盒、其余=整帧，
            // 杜绝拖拽时 CORE 控制窗被错复位到整帧左上（旧 onPositionChanged 用 physics-anchor 的 bug）。
            view.physics.x = cx
            view.physics.y = cy
            view.physics.clampToBounds()
            placeControlWindow(view, lp, lastW, lastH)
            lastW = lp.width
            lastH = lp.height
            try { windowManager.updateViewLayout(view, lp) } catch (_: Exception) {}
            // 同一触摸事件内同步移动显示窗：消除“控制层随触摸即时动、显示层随主循环晚一帧”的
            // 双时钟微抖。显示窗永远整帧（与 hit 模式无关），位置 = physics - 整帧锚点；
            // 仅位置级 updateViewLayout（绝不 removeView+addView 重建 Surface，无卡顿）；
            // hideStage 未挂显示窗时跳过。displayLeft/displayTop 与主循环同源，避免吸附线错位。
            pets.find { it.view === view }?.let { inst ->
                val d = inst.displayView ?: return@let
                val dlp = inst.displayLp ?: return@let
                if (inst.displayAdded) {
                    // physics.x/y 已是窗口左上角真值，直接放置显示窗（无需锚点减法）。
                    dlp.x = view.physics.x.toInt()
                    dlp.y = view.physics.y.toInt()
                    d.displayLeft = view.physics.x
                    d.displayTop = view.physics.y
                    try { windowManager.updateViewLayout(d, dlp) } catch (_: Exception) {}
                }
            }
        }
        // 角色碎碎念由当前 Mode 配置驱动；拖动不再单独覆盖文字。
        // 轻点/长按（非拖拽）命中本宠物：原用于把本宠窗组置顶，使被压住宠物可被点中。
        // 已移除置顶——removeView+addView 重建 Surface 卡顿，优先级低于流畅度；层级问题牺牲。
        view.onTap = {}
        // 落地文字同样由落地 Mode 的 thinking 策略管理。

        // 关键：addView 之前先把浮窗位置算到底部中心，避免先出现在左上角(0,0)再闪回底部。
        // 多个实例在水平方向轻微错开，避免初始完全重叠（各自物理独立，会自行散开）。
        val vis = getScreenBounds()
        val (w, h) = view.getBaseBitmapSize()
        // physics.x/y = 窗口左上角真值。脚落屏幕底部中心 → 窗口 = 脚位置 - 锚点偏移。
        // 等价于原“脚在 vis.bottom - (h - ay)、窗口 = 脚 - ay = vis.bottom - h”，保持落点与原来一致。
        val randomStart = PersonalizationPrefs.summonPosition(this) == PersonalizationPrefs.RANDOM
        val baseX: Float
        val baseY: Float
        if (randomStart) {
            // Choose valid top-left coordinates within the current physical canvas.
            // Keep display and control windows anchored to the same location.
            val minX = vis.left.toFloat()
            val maxX = (vis.right - w).toFloat().coerceAtLeast(minX)
            val minY = vis.top.toFloat()
            val maxY = (vis.bottom - h).toFloat().coerceAtLeast(minY)
            baseX = if (maxX <= minX) minX else Random.nextFloat() * (maxX - minX) + minX
            baseY = if (maxY <= minY) minY else Random.nextFloat() * (maxY - minY) + minY
        } else {
            baseX = (vis.left + vis.right) / 2f - w / 2f + index * 12f
            baseY = vis.bottom.toFloat() - h
        }
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
            // physics.x/y 已是窗口左上角，直接作为浮窗左上角（无需锚点减法）。
            lp.x = baseX.toInt()
            lp.y = baseY.toInt()
            lp.width = w
            lp.height = h
        }

        // 每宠物独立显示窗（替代共享全屏画布）：纯展示层，NOT_TOUCHABLE 不接任何点击，
        // 事件穿透给下层 app（安卓端小 NOT_TOUCHABLE 窗正常穿透；鸿蒙尽力兼容）。
        // 位置/尺寸与位图矩形一致，初始算到底部中心（与 view 同源）。
        val disp = PetDisplayView(this, view)
        val dispLp = WindowManager.LayoutParams().apply {
            type = roleDisplayType(roleId)
            format = PixelFormat.TRANSLUCENT
            // FLAG_LAYOUT_IN_SCREEN：显示窗 (0,0) 对齐物理屏幕左上角，baseX/baseY(来自 getScreenBounds 物理坐标)
            //   1:1 映射，状态栏显隐不再让宠物整体移位（否则落点原点在活动窗口=状态栏下，会下移状态栏高度并随其跳变）。
            // NOT_TOUCHABLE 是否保留由 displayNotTouchable 决定：默认/穿透/隐藏控制层 → 带(穿透)；
            //   强制无透明且控制层可见 → 去掉 NOT_TOUCHABLE 以避开系统对透明悬浮窗的半透明钳制（宠物可 100% 不透明）。
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    (if (roleDisplayNotTouchable(roleId)) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0)
            gravity = Gravity.TOP or Gravity.START
            // physics.x/y（baseX/baseY）为窗口左上角真值，直接作为浮窗左上角。
            x = baseX.toInt()
            y = baseY.toInt()
            width = w
            height = h
        }
        disp.displayLeft = baseX
        disp.displayTop = baseY
        // 透明度折进位图绘制（render 的 paint.alpha），窗口保持不透明（alpha=1），
        // 避免每帧移动时 View.setAlpha 触发合成器半透明离屏层重建导致卡顿。
        view.petAlpha = petAlpha
        // 诊断开关 hideStage：开启时不挂显示窗（仅透明控制窗，即 9.27 几何），用于二分点击拦截来源。
        var dispAdded = false
        if (roleId !in pendingInitialPlacement && !roleHideStage(roleId) && rolePetVisible(roleId)) {
            dispAdded = addDisplay(disp, dispLp)
        }

        // 控制窗是否挂载：调试“隐藏控制层”或用户“点击穿透”任一为真即不挂（二者语义等价，
        // 显示窗本就 NOT_TOUCHABLE，屏蔽控制窗后点击穿透到下层 app 且宠物仍可见）。
        var controlAdded = false
        if (roleId !in pendingInitialPlacement && !roleControlHidden(roleId) && rolePetVisible(roleId)) {
            windowManager.addView(view, lp)
            controlAdded = true
        }
        return PetInstance(
            view = view,
            layoutParams = lp,
            displayView = disp,
            displayLp = dispLp,
            displayAdded = dispAdded,
            controlAdded = controlAdded,
            petMsg = petMsg
        )
    }

    // 变更宠物数量（酥的数量）：多退少补，绝不整体重建。
    // - 目标 > 当前：末尾追加新实例（保留现有宠物，不重置其位置/动画），index 用真实序号（createPetInstance 偏移错开）。
    // - 目标 < 当前：从末尾移除多余实例（保留 index 0 主实例，petView/layoutParams/trayMsgManager 不变）；
    //   移除时**同时清理控制窗与显示窗**（旧 rebuildPets 漏删显示窗，导致改数量后残留旧画面 + 位图/Surface 泄漏）。
    // - 新实例经 applySharedConfig + 资源包拿到共享配置（与现有一致）；现有实例重应用幂等、位置/物理不复位。
    // 接收最新配置 c（来自 configFlow），避免重读 DataStore 造成竞态/重复重建。
    private fun adjustPetCount(roleId: String, c: PetConfigData) {
        val target = c.petCount.coerceIn(1, 20)
        val rolePets = pets.filter { it.roleId == roleId }.toMutableList()
        // 多退：从该角色末尾移除多余实例（始终保留 index 0 主实例）。
        while (rolePets.size > target) {
            val inst = rolePets.removeAt(rolePets.lastIndex)
            // 显示窗（PetDisplayView）必须显式 removeView，否则残留屏幕且强引用旧 PetView/位图 → 泄漏。
            if (inst.displayAdded) inst.displayView?.let { d ->
            inst.displayLp?.let { removeDisplay(d, it) }
        }
            if (inst.controlAdded) try { windowManager.removeView(inst.view) } catch (_: Exception) {}
            try { inst.petMsg.destroy() } catch (_: Exception) {}
            pets.remove(inst)
        }
        // 少补：该角色末尾追加新实例，index 用真实 rolePets.size（createPetInstance 依 index 做水平错开）。
        while (rolePets.size < target) {
            val inst = createPetInstance(rolePets.size, roleId)
            inst.roleId = roleId
            rolePets.add(inst)
            pets.add(inst)
            if (pets.size == 1) {   // 安全兜底：若此前为空，首位即主实例
                petView = inst.view
                layoutParams = inst.layoutParams
                trayMsgManager = inst.petMsg
            }
        }
        // 重新应用本角色共享配置（物理参数/缩放/碎碎念样式/点击穿透等），新实例据此拿到配置、现有实例幂等刷新。
        val rt = engines[roleId] ?: return
        // New instances must load their own role's pack before capability checks.
        val pack = resolveCurrentSpritePack(rt)
        pets.filter { it.roleId == roleId }.forEach { it.view.applySpritePack(pack) }
        applySharedConfig(rt)
        recalcBounds()
        // 实例数量变化（新增/移除）后，重新置顶辅助浮层（宠物窗叠放保持召唤顺序、不再重排）。
        reapplyZOrder()
    }

    // 将共享配置同步到所有宠物实例（物理参数、缩放、命中模式、碎碎念样式/可见性、点击穿透等）。
    // 各实例的「当前动作 / 位置 / 拖拽 / 吸附 / 碎碎念内容」相互独立，不在此同步。
    // 碎碎念位置按实例序号错开（第 i 个 = 设置偏移 + i * TRAY_ROW_STEP），避免多宠物时重叠。
    private fun applySharedConfig(rt: RoleRuntime) {
        val c = rt.config.getBlocking()
        // 仅把配置应用到本角色实例；全局快照字段（petAlpha/thinkingEnabled 等）仍保留供 createPetInstance 取初始值。
        petAlpha = c.alpha.coerceIn(0f, 1f)
        thinkingEnabled = c.thinkingEnabled
        singleTrayMsg = c.singleTrayMsg
        showImageBorder = c.showImageBorder
        showControlBorder = c.showControlBorder
        tiltStrength = c.gravity
        rt.tiltStrength = c.gravity
        pets.filter { it.roleId == rt.roleId }.forEachIndexed { index, inst ->
            val v = inst.view
            val effectiveMove = c.moveEnabled || !v.hasFunction("standby", "static_pool")
            val effectiveGravity = c.gravityEnabled && effectiveMove
            v.setConfig(c.scale, c.gravity, c.reboundRatio,
                c.gravityTop, c.gravityBottom, c.gravityLeft, c.gravityRight,
                c.reboundTop, c.reboundBottom, c.reboundLeft, c.reboundRight,
                effectiveGravity, c.maxSpeed,
                c.throwEnabled && v.hasFunction("touch", "roll"), c.throwCoeff)
            // 层级门控：移动（总开关）→ 攀岩 → 飞跃。上级关则下级一并失效。
            val effClimb = effectiveMove && c.wallClimb          // 攀岩是移动下级：移动关则攀岩关
            val effLeap  = effClimb && c.leapEnabled && v.hasFunction("climb", "leap", "leap")             // 飞跃开关依赖攀岩功能，但起飞不要求角色已处于攀岩状态
            v.useStaticPool = !effectiveMove   // 静止池只看"移动"总开关（重力关仅关重力，不进静止池）
            v.wallClimb = effClimb            // 攀岩走壁开关（已被移动总开关门控）
            v.setLeapParams(effLeap, c.leapProbability)  // 好动飞跃：在正常待机动作选择边界按概率起飞
            v.syncClimbState(effClimb)   // 攀岩走壁开启即进攀爬态（平地=贴地墙），好动飞跃方可触发
            if (v.useStaticPool && v.climbWall >= 0) v.restoreBaseGravity()  // 移动关：退出攀爬还原
            v.setPetScale(c.scale)
            v.showImageBorder = showImageBorder
            v.showControlBorder = showControlBorder
            v.bounceVibrateEnabled = c.bounceVibrate
            v.hitMode = c.hitMode
            v.ctrlBoxWidth = c.ctrlBoxWidth
            v.ctrlBoxHeight = c.ctrlBoxHeight
            v.ctrlBoxVOffset = c.ctrlBoxVOffset
            petAlpha = c.alpha.coerceIn(0f, 1f)
            // 透明度折进位图绘制（render 的 paint.alpha），窗口保持不透明，避免移动卡顿。
            v.petAlpha = petAlpha
            v.visibility = if (rolePetVisible(rt.roleId)) android.view.View.VISIBLE else android.view.View.GONE
            // The sprite is painted by a separate display window, not by PetView.
            inst.displayView?.visibility = v.visibility
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
            inst.petMsg.setVisible(showForThis && rolePetVisible(rt.roleId))
        }
        // 按角色独立应用窗口开关：控制层增删 / 显示层增删 / 显示窗 NOT_TOUCHABLE（强制无透明）。
        // 均按 rt.roleId 过滤，且各 sync 幂等（先比对当前窗口状态再决定是否 removeView/addView），
        // 多角色各自独立、互不污染（修复旧全局变量导致的跨角色串味）。
        // Display must attach before control, or force-opaque display would cover touch.
        syncDisplayWindows(rt.roleId)
        syncControlWindows(rt.roleId)
        applyDisplayTouchFlags(rt.roleId)
        applyImeHide(keyboardUp)
    }

    /** 按资源包 id 取得角色素材包；未知旧版预装 id 回退白酥。 */
    private fun spritePackFor(packId: Int): SpritePack {
        spritePackCache[packId]?.let { return it }
        val pack = buildSpritePack(this, packId)
        spritePackCache[packId] = pack
        return pack
    }

    // ======================== 投喂（支鱼）浮窗 ========================

    /** 获取素材包对应的通用动画资源与图像缓存；不区分角色、鱼、肉或道具素材。 */
    private fun modeAssetsForRole(roleId: String): ImageModeManager =
        modeAssetManagers.getOrPut(roleId) {
            val d = descriptorForRole(roleId)
            val pack = if (d != null) buildSpritePackForRole(this, d)
                       else buildSpritePack(this, ConfigDefaults.DEFAULT_SPRITE_PACK)
            ImageModeManager(this, pack)
        }

    /** 投喂实体的显示仍归引擎管理；图片及裁剪从通用 Mode 图像缓存取得。 */
    private fun fishFrame(layer: FishLayer): Bitmap? {
        val clip = layer.animation ?: return layer.crop
        return modeAssetsForRole(layer.roleId).modeVisual(layer.modeId, alphaTrimThreshold = 8)
            ?.frames?.get(clip.currentVisualKey)
    }

    private fun tickFishModes(dtMs: Long) {
        for (layer in fishLayers) {
            if (layer.animation?.advance(dtMs)?.changed == true) {
                fishFrame(layer)?.let { layer.iv.setImageBitmap(it) }
            }
        }
    }

    // 解析某角色对应的素材模板描述符（上传包按实例 id 映射到模板，预装按 id 直达）。
    private fun descriptorForRole(roleId: String): RoleDescriptor? {
        return RoleStore.descriptorForInstance(this, roleId) ?: RoleRegistry.byId(roleId)
    }

    // 支鱼显示尺寸：源已缩到 256（长边）仅作解码清晰度缓冲，1x 显示基准与宠物一致为 128（长边），
    // 故在 256 空间像素上乘以 0.5 再乘倍率。
    private fun fishDisplaySize(crop: Bitmap, c: PetConfigData): Pair<Int, Int> {
        if (crop.width <= 0 || crop.height <= 0) return Pair(1, 1)
        val scale = c.feedingFishScale.coerceAtLeast(0.01f) * 0.5f
        val wpx = (crop.width * scale).toInt().coerceAtLeast(1)
        val hpx = (crop.height * scale).toInt().coerceAtLeast(1)
        return wpx to hpx
    }

    // 资源包切换时重载食物 Mode 与帧缓存（避免不同角色之间串图）。
    /** 素材切换后重新加载食物动画。roleId 为 null 时作用于全部角色，保持各角色的缩放和透明度。 */
    private fun reloadFishBitmap(roleId: String? = null) {
        val list = if (roleId == null) fishLayers.toList() else fishLayers.filter { it.roleId == roleId }
        val reloadIds = if (roleId == null) list.map { it.roleId }.distinct() else listOf(roleId)
        for (id in reloadIds) {
            modeAssetManagers.remove(id)
            
        }
        for (layer in list) {
            val manager = modeAssetsForRole(layer.roleId)
            val selected = manager.modeOf("feed", "food") ?: continue
            val visual = manager.modeVisual(selected, alphaTrimThreshold = 8) ?: continue
            layer.modeId = selected
            layer.animation = manager.createClip(selected)
            layer.crop = visual.first
            layer.iv.setImageBitmap(fishFrame(layer))
            val c = roleConfig(layer.roleId)
            val (w, h) = fishDisplaySize(visual.first, c)
            layer.lp.width = w
            layer.lp.height = h
            layer.iv.imageAlpha = (c.feedingFishAlpha / 100f * 255).toInt().coerceIn(0, 255)
            try { windowManager.updateViewLayout(layer.iv, layer.lp) } catch (_: Exception) {}
            layer.borderView?.let { bv ->
                val blp = bv.layoutParams as? WindowManager.LayoutParams
                blp?.width = layer.lp.width; blp?.height = layer.lp.height
                blp?.x = layer.lp.x; blp?.y = layer.lp.y
                try { windowManager.updateViewLayout(bv, blp) } catch (_: Exception) {}
            }
        }
    }

    /**
     * 该角色宠物的【活动范围】矩形（屏幕绝对坐标，FLAG_LAYOUT_IN_SCREEN 原点=物理(0,0)）。
     * 纯静态：仅由「边界偏移」+ 可见屏 + 屏幕旋转决定，
     * 与宠物【当前位置/朝向/动画帧锚点】完全无关（= physics.snapMin/Max，不含图片 inset、不随脚朝向变）。
     * 故判定结果稳定，不会时好时坏。无宠物实例时按本角色偏移配置同源兜底。
     */
    private fun roleActivityRect(roleId: String): Rect {
        val cfg = roleConfig(roleId)
        val sb = pets.firstOrNull { it.roleId == roleId }?.view?.physics
        if (sb != null) {
            return Rect(sb.snapMinX.toInt(), sb.snapMinY.toInt(), sb.snapMaxX.toInt(), sb.snapMaxY.toInt())
        }
        val vis = getScreenBounds()
        val ob = rotatedBoundOffsets(effectiveRangeOffsets(cfg))
        return Rect(
            (vis.left + ob[3]).toInt(),
            (vis.top + ob[0]).toInt(),
            (vis.right - ob[1]).toInt(),
            (vis.bottom - ob[2]).toInt()
        )
    }

    // 支鱼刷新范围(屏幕绝对坐标，与宠物浮窗同源 FLAG_LAYOUT_IN_SCREEN、原点=物理(0,0))：
    // 全屏=物理屏整块(含系统栏)；关闭全屏=改用【该角色的活动范围】= 与宠物移动/吸附边界同源的「边界偏移矩形」
    // (physics.snapMin/Max)，即 getScreenBounds() 叠加本角色 offsetTop/Bottom/Left/Right 后的矩形。
    // 这样鱼只在该角色真实可活动区内刷新，与宠物移动、吸附边界完全同一坐标系(不再用系统栏 inset 这种不同基准)。
    private fun feedSpawnArea(c: PetConfigData, roleId: String): Rect {
        if (c.feedingFullScreen) {
            val (W, H) = screenRealSize()
            return Rect(0, 0, W, H)
        }
        // 关全屏：取该角色宠物的「边界偏移矩形」(snap 边界，不含图片 inset、不随脚朝向变，与设置页边界偏移线同几何)。
        val sb = pets.firstOrNull { it.roleId == roleId }?.view?.physics
        if (sb != null) {
            return Rect(sb.snapMinX.toInt(), sb.snapMinY.toInt(), sb.snapMaxX.toInt(), sb.snapMaxY.toInt())
        }
        // 兜底(理论上该角色已有宠物实例)：按本角色偏移配置直接算 snap 边界(与 recalcBoundsFor 同源)。
        val vis = getScreenBounds()
        val ob = rotatedBoundOffsets(effectiveRangeOffsets(c))
        return Rect(
            (vis.left + ob[3]).toInt(),
            (vis.top + ob[0]).toInt(),
            (vis.right - ob[1]).toInt(),
            (vis.bottom - ob[2]).toInt()
        )
    }

    /**
     * 鱼当前允许的【活动范围】矩形（屏幕绝对坐标，FLAG_LAYOUT_IN_SCREEN 原点=物理(0,0)，与宠物 snap 边界同源）。
     * 返回 null 表示「全屏范围」开启：不钳制（任意位置）。
     * 关全屏：= 所属角色宠物的活动范围（边界偏移矩形 physics.snapMin/Max）；无宠物实例时按本角色偏移配置同源兜底。
     * 仅作为【钳制】基准，不含系统栏/静态边界 inset，严格等于宠物可活动区（与「鱼活动范围=宠物活动范围」需求一致）。
     */
    private fun fishAllowedRect(roleId: String): Rect? {
        val cfg = roleConfig(roleId)
        if (cfg.feedingFullScreen) return null
        val sb = pets.firstOrNull { it.roleId == roleId }?.view?.physics
        if (sb != null) {
            return Rect(sb.snapMinX.toInt(), sb.snapMinY.toInt(), sb.snapMaxX.toInt(), sb.snapMaxY.toInt())
        }
        // 兜底(理论上该角色已有宠物实例)：按本角色偏移配置直接算 snap 边界(与 recalcBoundsFor 同源)。
        val vis = getScreenBounds()
        val ob = rotatedBoundOffsets(effectiveRangeOffsets(cfg))
        return Rect(
            (vis.left + ob[3]).toInt(),
            (vis.top + ob[0]).toInt(),
            (vis.right - ob[1]).toInt(),
            (vis.bottom - ob[2]).toInt()
        )
    }

    /**
     * 把全部角色的支鱼重钳进各自允许活动范围（关全屏时）；开全屏则不钳（保持当前位置）。
     * 范围比鱼还小时强制拉到最近边(coerceIn)。仅在「范围变化」后调用一次（recalcBounds 末尾 / 归位按钮），不每帧。
     * 已在区间内的鱼跳过 updateViewLayout，避免无谓重排。
     */
    private fun reclampAllFish() {
        for (layer in fishLayers) {
            val rect = fishAllowedRect(layer.roleId) ?: continue
            val lp = layer.lp
            val wpx = lp.width; val hpx = lp.height
            val nx = lp.x.coerceIn(rect.left, (rect.right - wpx).coerceAtLeast(rect.left))
            val ny = lp.y.coerceIn(rect.top, (rect.bottom - hpx).coerceAtLeast(rect.top))
            if (nx == lp.x && ny == lp.y) continue
            lp.x = nx; lp.y = ny
            try { windowManager.updateViewLayout(layer.iv, lp) } catch (_: Exception) {}
            try { layer.borderView?.let { windowManager.updateViewLayout(it, lp) } } catch (_: Exception) {}
        }
    }

    // 长按支鱼：振动并弹出菜单（清除 / 清除全部 / 投喂设置）。
    private fun openFishMenu(v: View) {
        val layer = fishLayers.firstOrNull { it.iv === v } ?: return
        // 先把当前角色切到该支鱼所属角色，菜单内 currentConfig()/updateConfig 才会落到正确角色。
        syncRole(layer.roleId)
        FishMenu(this, layer).show()
    }

    // 鱼松手点是否落在任一宠物【显示层】（不含定格）矩形内（拖动给酥判定）。
    // 使用触摸松手屏幕坐标、宠物显示层矩形，不依赖控制层——即使开启点击穿透（关闭控制层）也能成功投喂。
    private fun fishDroppedOnPet(rawX: Float, rawY: Float): Boolean {
        for (inst in pets) {
            val dlp = inst.displayLp ?: continue
            if (!inst.displayAdded) continue
            val lx = dlp.x; val ly = dlp.y; val lw = dlp.width; val lh = dlp.height
            if (rawX in lx.toFloat()..(lx + lw).toFloat() && rawY in ly.toFloat()..(ly + lh).toFloat()) return true
        }
        return false
    }

    // 与 fishDroppedOnPet 同判定，但返回命中的宠物实例（用于对其单独播放投食动作 + 读其朝向算镜像）。
    private fun findPetAt(rawX: Float, rawY: Float): PetInstance? {
        for (inst in pets) {
            val dlp = inst.displayLp ?: continue
            if (!inst.displayAdded) continue
            val lx = dlp.x; val ly = dlp.y; val lw = dlp.width; val lh = dlp.height
            if (rawX in lx.toFloat()..(lx + lw).toFloat() && rawY in ly.toFloat()..(ly + lh).toFloat()) return inst
        }
        return null
    }

    // 本地 +x（资源朝右）经重力旋转映射到屏幕的前向向量 Fg（脚朝向决定）：
    // 脚朝下(0)=(+1,0)；脚朝左(1)=(0,+1)；脚朝右(2)=(0,-1)；脚朝上(3)=(-1,0)。
    private fun feedForward(gravityDir: Int): Pair<Float, Float> = when (gravityDir) {
        0 -> 1f to 0f
        1 -> 0f to 1f
        2 -> 0f to -1f
        3 -> -1f to 0f
        else -> 1f to 0f
    }

    /**
     * 投喂成功后触发投食动作：
     * - 吸附态 → 对应投喂功能动作（按配置衔接后续动作）；
     * - 待机清单内 → 坐姿投食（结束回待机随机池）；
     * - 其余（roll/lift/摸头等）不切动作。
     * 镜像：以鱼【首次进入宠物判定区】的位置（振动点）相对宠物显示中心的向量 D，
     * 与宠物"本地右"轴 Rg（脚前向 Fg 顺时针旋转 90°：(x,y)->(-y,x)）做点积决定：
     *   D·Rg < 0 → 鱼在宠物左侧 → 镜像（flip）；
     *   D·Rg > 0 → 鱼在宠物右侧 → 不镜像。
     * Rg 由 Fg 旋转得到，故脚朝左/右/上时自动按宠物自身左右适配。死区内沿用当前朝向。
     * 投食结束【不还原】镜像，下一动作沿用该朝向。
     */
    private fun triggerFeedAction(inst: PetInstance?, relX: Float, relY: Float) {
        if (inst == null) return
        val view = inst.view
        // 投食动作由素材包 functions 提供（v1.01）：吸附态走 [snap, feed, eat]，待机态走 [feed, eat]。
        // 两者末段同为 eat 但路径不同，故必须按完整路径寻址；包未提供则跳过投食动作。
        val name = when {
            view.physics.isSnapped -> view.modeOf("snap", "feed", "eat")
            view.isStandbyMode() -> view.modeOf("feed", "eat")
            else -> null
        } ?: return
        // 脚前向 Fg（屏幕向量）：非吸附用 gravityDir 映射，吸附用 snapRotation。
        // 镜像目标：让宠物"面朝鱼"。鱼在 Fg 反方向（D·Fg<0，鱼在宠物身后）则镜像。
        // 上/左/右朝向由 Fg 自身适配，无需额外旋转轴。
        val (fx, fy) = if (view.physics.isSnapped) {
            val r = Math.toRadians(view.snapRotation.toDouble())
            Math.cos(r).toFloat() to Math.sin(r).toFloat()
        } else feedForward(view.gravityDir)
        val proj = relX * fx + relY * fy
        val flip = if (kotlin.math.abs(proj) < 12f) {
            // 鱼落在宠物正前方/正中附近：死区，沿用当前朝向，避免乱翻
            if (view.physics.isSnapped) view.getSnapFlipX() else view.flipX
        } else proj > 0f
        if (view.physics.isSnapped) view.setSnapFlipX(flip) else view.flipX = flip
        view.playOnce(name)
    }

    /**
     * 投喂成功后按「投喂变大」真实增大宠物尺寸：scale += factor（绝对值相加），
     * 上限取用户在「大小」项设置的上界（PetBounds MAIN 页 scale 的 to，阈值页可改），
     * 达到该上界后投喂不再变大；scale 为所有分身共享，故投喂一只全部变大。
     *
     * 多角色语义：系数/开关取自【被吃的食物】所属角色（吃什么用什么倍率，a 用 a 的、b 用 b 的），
     * 而放大的对象是该【吃者宠物】所属角色（谁吃谁变大）。两者一般是不同角色
     * （如 B 吃 A 的 a：B 变大、用 A 的倍率），故必须分别传参，绝不能再依赖全局别名 config。
     *
     * @param eatRoleId  吃下食物的那只宠物所属角色（被放大者）
     * @param foodRoleId 被吃食物所属角色（系数/开关来源）
     */
    private fun growPetOnFeed(eatRoleId: String, foodRoleId: String) {
        val foodRt = engines[foodRoleId] ?: return
        val foodCfg = foodRt.config.getBlocking()
        if (!foodCfg.feedGrowEnabled) return
        val factor = foodCfg.feedGrowFactor.coerceAtLeast(0.01f)
        val eatRt = engines[eatRoleId] ?: return
        val eatCfg = eatRt.config.getBlocking()
        // 投喂变大的上限 = 【被投喂宠物（吃者）】自己「大小」设定的上界（不是绝对阈值）。
        // 被放大的是吃者，故必须受吃者自身上界约束；此前误取【食物角色】(foodRt) 的上界，
        // 会出现"吃了别角色的食物后突破自身上界"的越界放大。
        // 阈值页调整后此处随之生效。
        val scaleUserMax = eatRt.bounds.getBlocking(SettingsPage.MAIN)
            .sliders.firstOrNull { it.key == "scale" }?.to ?: ConfigDefaults.FEED_GROW_MAX_SCALE
        val newScale = (eatCfg.scale + factor).coerceAtMost(scaleUserMax)
        if (newScale > eatCfg.scale) scope.launch { eatRt.config.update { it.copy(scale = newScale) } }
    }

    /** 重置宠物大小为用户默认值（ConfigDefaults.SCALE，所有分身共享，即时生效）。 */
    fun resetPetScaleToDefault() {
        scope.launch { config.update { it.copy(scale = ConfigDefaults.SCALE) } }
    }

    // 按静态边界钳制静态资源（定格/食物）的竖向位置：仅钳 y，横向左右无系统栏保持原样。
    // top=顶边最小 y；bottom=底边距屏幕底的保留距离；h=资源像素高。范围由调用方在 -200..400 内约束。
    private fun clampStaticY(y: Int, h: Int, top: Int, bottom: Int): Int {
        val H = screenRealSize().second
        val minY = top
        val maxY = (H - bottom - h).coerceAtLeast(minY) // 极端小屏兜底，避免下界高于上界
        return y.coerceIn(minY, maxY)
    }

    // 刷出一条食物：通过 functions.feed.food 选择完整 Mode，并保留原有位置/拖动/过期规则。
    private fun spawnFish(c: PetConfigData, roleId: String) {
        val manager = modeAssetsForRole(roleId)
        val modeId = manager.modeOf("feed", "food") ?: return
        val visual = manager.modeVisual(modeId, alphaTrimThreshold = 8) ?: return
        val clip = manager.createClip(modeId) ?: return
        val crop = visual.first
        val (wpx, hpx) = fishDisplaySize(crop, c)
        val iv = ImageView(this).apply {
            setImageBitmap(visual.frames[clip.currentVisualKey])
            scaleType = ImageView.ScaleType.FIT_XY
            imageAlpha = (c.feedingFishAlpha / 100f * 255).toInt().coerceIn(0, 255)
        }
        val lp = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            gravity = Gravity.TOP or Gravity.START
            width = wpx
            height = hpx
        }
        // 刷新范围：全屏=物理屏整块(含系统栏)；关闭全屏=该角色活动范围(边界偏移矩形，与宠物移动/吸附边界同源)。
        // 是否贴边由 feedingEdgeRefresh 单独决定，与全屏无关。
        val area = feedSpawnArea(c, roleId)
        val areaW = (area.right - area.left).coerceAtLeast(1)
        val areaH = (area.bottom - area.top).coerceAtLeast(1)
        if (c.feedingEdgeRefresh) {
            // 边缘刷新：支鱼紧贴活动区边界（全屏=物理屏四边；非全屏=活动范围四边），随机选一边，鱼边贴边。
            // 刷新位置直接落在 area(角色活动范围) 内，不再叠加调试页「静态边界」(staticTop/Bottom) 二次钳制；
            // 否则竖向会被屏幕物理绝对坐标的静态边界切割，导致刷新范围 ≠ 活动范围(且坐标系不一致)。
            when (Random.nextInt(4)) {
                0 -> { // 上边：鱼顶贴活动区上边
                    lp.x = area.left + Random.nextInt(0, (areaW - wpx).coerceIn(1, 99999))
                    lp.y = area.top
                }
                1 -> { // 下边：鱼底贴活动区下边
                    lp.x = area.left + Random.nextInt(0, (areaW - wpx).coerceIn(1, 99999))
                    lp.y = area.bottom - hpx
                }
                2 -> { // 左边：鱼左贴活动区左边
                    lp.x = area.left
                    lp.y = area.top + Random.nextInt(0, (areaH - hpx).coerceIn(1, 99999))
                }
                else -> { // 右边：鱼右贴活动区右边
                    lp.x = area.right - wpx
                    lp.y = area.top + Random.nextInt(0, (areaH - hpx).coerceIn(1, 99999))
                }
            }
        } else {
            lp.x = area.left + Random.nextInt(0, (areaW - wpx).coerceIn(1, 99999))
            lp.y = area.top + Random.nextInt(0, (areaH - hpx).coerceIn(1, 99999))
        }
        iv.setOnTouchListener(fishDragListener)
        try { windowManager.addView(iv, lp) } catch (_: Exception) { return }
        val layer = FishLayer(iv, lp, crop = crop, animation = clip, modeId = modeId,
            packId = descriptorForRole(roleId)?.packId ?: ConfigDefaults.DEFAULT_SPRITE_PACK, roleId = roleId)
        fishLayers.add(layer)
        if (c.feedingBorderShow) setFishBorder(layer, true)
        if (c.feedingAutoExpire) scheduleFishExpire(layer, c.feedingExpireTime)
        if (keyboardUp) { recalcBounds(); syncKeyboardAuxiliary(roleId, c, true) }
    }

    private fun scheduleFishExpire(layer: FishLayer, secs: Int) {
        val idx = fishLayers.indexOfFirst { it.iv === layer.iv }
        if (idx < 0) return
        val runnable = Runnable { removeFish(layer) }
        fishLayers[idx] = fishLayers[idx].copy(expireRunnable = runnable)
        mainHandler.postDelayed(runnable, secs.coerceAtLeast(1) * 1000L)
    }

    // 移除一条支鱼（取消过期计时；不回收共享 crop 位图）。
    // 注意：FishLayer 是 data class，equals 含 expireRunnable 字段，copy 替换后会失配；
    // 故一律按 ImageView 引用定位，避免过期 runnable 持有旧引用时 indexOf 返回 -1 导致永不删除。
    fun removeFish(layer: FishLayer) {
        val idx = fishLayers.indexOfFirst { it.iv === layer.iv }
        if (idx < 0) return
        val cur = fishLayers[idx]
        cur.expireRunnable?.let { mainHandler.removeCallbacks(it) }
        try { windowManager.removeViewImmediate(cur.iv) } catch (e: Exception) {
            android.util.Log.w("PetService", "移除食物浮窗失败：${cur.roleId}", e)
        }
        cur.borderView?.let { try { windowManager.removeViewImmediate(it) } catch (_: Exception) {} }
        fishLayers.removeAt(idx)
        imeFishOffsets.remove(cur.iv)
        if (draggingFish?.iv === cur.iv) draggingFish = null
        // A removed fish must not remain the forage target of any surviving pet.
        forageFoodByView.entries.removeAll { it.value?.iv === cur.iv }
    }

    // 清除支鱼浮窗。roleId 为 null 时清除全部；否则仅清除【该角色】的支鱼（含其过期计时与边框）。
    fun clearAllFish(roleId: String? = null) {
        val list = if (roleId == null) fishLayers.toList() else fishLayers.filter { it.roleId == roleId }
        for (layer in list) removeFish(layer)
    }

    /** 全部支鱼浮窗重置位置：开全屏→屏幕中心；关全屏→所属角色活动范围中心（范围比鱼小则拉到最近边）。
     *  roleId 为 null 时重置全部；否则仅重置【该角色】的支鱼。 */
    fun resetAllFishToCenter(roleId: String? = null) {
        val (sw, sh) = screenRealSize()
        val list = if (roleId == null) fishLayers.toList() else fishLayers.filter { it.roleId == roleId }
        for (layer in list) {
            val w = layer.lp.width; val h = layer.lp.height
            val cx = (sw - w) / 2
            val cy = (sh - h) / 2
            val rect = fishAllowedRect(layer.roleId)
            if (rect == null) {
                // 全屏范围：回到屏幕中心，范围=整屏(比鱼小兜底用 coerceAtLeast)。
                layer.lp.x = cx.coerceIn(0, (sw - w).coerceAtLeast(0))
                layer.lp.y = cy.coerceIn(0, (sh - h).coerceAtLeast(0))
            } else {
                // 关全屏：回到活动范围中心；范围比鱼小则 coerceIn 落到最近边。
                layer.lp.x = cx.coerceIn(rect.left, (rect.right - w).coerceAtLeast(rect.left))
                layer.lp.y = cy.coerceIn(rect.top, (rect.bottom - h).coerceAtLeast(rect.top))
            }
            try { windowManager.updateViewLayout(layer.iv, layer.lp) } catch (_: Exception) { }
            try { layer.borderView?.let { windowManager.updateViewLayout(it, layer.lp) } } catch (_: Exception) { }
        }
    }

    private fun setFishBorder(layer: FishLayer, on: Boolean) {
        val idx = fishLayers.indexOfFirst { it.iv === layer.iv }
        if (idx < 0) return
        val cur = fishLayers[idx]
        if (on && cur.borderView == null) {
            fishLayers[idx] = cur.copy(borderView = createFishBorderView(cur))
        } else if (!on && cur.borderView != null) {
            try { windowManager.removeView(cur.borderView) } catch (_: Exception) {}
            fishLayers[idx] = cur.copy(borderView = null)
        }
    }

    private fun createFishBorderView(layer: FishLayer): View {
        val bv = View(this).apply { background = getDrawable(R.drawable.freeze_border) }
        val blp = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            gravity = Gravity.TOP or Gravity.START
            width = layer.lp.width
            height = layer.lp.height
            x = layer.lp.x
            y = layer.lp.y
        }
        try { windowManager.addView(bv, blp) } catch (_: Exception) {}
        return bv
    }

    // 倍率 / 透明度变化：即时作用于该角色食物浮窗；同一动画的全部帧使用固定裁剪尺寸。
    private fun applyFishScaleAlpha(rt: RoleRuntime) {
        val c = rt.config.getBlocking()
        for (layer in fishLayers.filter { it.roleId == rt.roleId }) {
            val crop = layer.crop ?: continue
            val (wpx, hpx) = fishDisplaySize(crop, c)
            layer.lp.width = wpx
            layer.lp.height = hpx
            layer.iv.imageAlpha = (c.feedingFishAlpha / 100f * 255).toInt().coerceIn(0, 255)
            try { windowManager.updateViewLayout(layer.iv, layer.lp) } catch (_: Exception) {}
            layer.borderView?.let { bv ->
                val blp = bv.layoutParams as? WindowManager.LayoutParams
                blp?.width = layer.lp.width; blp?.height = layer.lp.height
                try { windowManager.updateViewLayout(bv, blp) } catch (_: Exception) {}
            }
        }
    }

    // 自动过期开关 / 过期时间变化：重新排程【该角色】支鱼的过期计时。
    private fun applyFishAutoExpire(rt: RoleRuntime) {
        val c = rt.config.getBlocking()
        for (layer in fishLayers.filter { it.roleId == rt.roleId }) {
            layer.expireRunnable?.let { mainHandler.removeCallbacks(it) }
            val idx = fishLayers.indexOfFirst { it.iv === layer.iv }
            if (idx < 0) continue
            fishLayers[idx] = if (c.feedingAutoExpire) {
                val runnable = Runnable { removeFish(layer) }
                mainHandler.postDelayed(runnable, c.feedingExpireTime.coerceAtLeast(1) * 1000L)
                fishLayers[idx].copy(expireRunnable = runnable)
            } else {
                fishLayers[idx].copy(expireRunnable = null)
            }
        }
    }

    // 随机刷出循环：【每个角色独立】一个循环，周期在 [频率下限, 频率上限] 秒之间随机（参数取自该角色自身配置）。
    private fun scheduleFishSpawn(rt: RoleRuntime) {
        val c = rt.config.getBlocking()
        val lo = c.feedingPeriodMin.coerceAtMost(c.feedingPeriodMax)
        val hi = c.feedingPeriodMax.coerceAtLeast(c.feedingPeriodMin)
        val period = if (hi <= lo) hi else Random.nextInt(lo, hi + 1)
        rt.fishSpawnRunnable = Runnable {
            if (!rt.fishSpawning) return@Runnable
            val cc = rt.config.getBlocking()
            if (cc.feedingEnabled && fishLayers.count { it.roleId == rt.roleId } < cc.feedingMaxCount) {
                spawnFish(cc, rt.roleId)
            }
            scheduleFishSpawn(rt)
        }
        mainHandler.postDelayed(rt.fishSpawnRunnable!!, period * 1000L)
    }

    private fun startFishSpawning(rt: RoleRuntime) {
        if (rt.fishSpawning) return
        rt.fishSpawning = true
        scheduleFishSpawn(rt)
    }

    // 交互菜单「投食」：立即刷出一条【本角色】支鱼（受该角色最大数量约束），并重置该角色随机刷出计时（关闭浮窗由调用方处理）。
    fun feedNow(roleId: String? = null) {
        val rt = roleId?.let { engines[it] } ?: return
        val c = rt.config.getBlocking()
        if (fishLayers.count { it.roleId == rt.roleId } < c.feedingMaxCount) {
            spawnFish(c, rt.roleId)
        }
        // 仅当该角色自动刷出运行中才重置计时：取消待触发的随机刷出，从当前时刻重新排程。
        if (rt.fishSpawning) {
            rt.fishSpawnRunnable?.let { mainHandler.removeCallbacks(it) }
            scheduleFishSpawn(rt)
        }
    }

    // 停止某角色的随机刷出循环，并仅清除该角色的支鱼（不串其它角色）。
    private fun stopFishSpawning(rt: RoleRuntime) {
        rt.fishSpawning = false
        rt.fishSpawnRunnable?.let { mainHandler.removeCallbacks(it) }
        rt.fishSpawnRunnable = null
        clearAllFish(rt.roleId)
    }

    private fun loadConfig(roleId: String) {
        val rt = engines[roleId] ?: return
        if (rt.config.getBlocking().interactionMode == "core-opaque" &&
            CakePetAccessibilityService.active == null) onAccessibilityUnavailable()
        config.normalizeBlocking() // 启动时清洗历史残留的非法四边重力组合
        val c = config.getBlocking()
        showDebug = c.showDebug
        showImageBorder = c.showImageBorder
        showControlBorder = c.showControlBorder
        // 控制窗/显示窗开关（隐藏控制层、点击穿透、强制无透明、隐藏画布）均为【按角色独立】设置，
        // 其窗口增删与 NOT_TOUCHABLE 标志的实时应用已统一在 applySharedConfig(rt) 末尾按 roleId 执行，
        // 不再依赖全局变量，避免多角色时一个角色改值污染另一个角色的窗口状态。
        showBoundOffsetFlag = c.showBoundOffset
        showSnapOffsetFlag = c.showSnapOffset
        applyDebugGuides()
        boundOffsetTop = (if (c.rangeFullScreen) 0f else c.offsetTop).coerceIn(-300f, 2100f)
        boundOffsetBottom = (if (c.rangeFullScreen) 0f else c.offsetBottom).coerceIn(-300f, 2100f)
        boundOffsetLeft = (if (c.rangeFullScreen) 0f else c.offsetLeft).coerceIn(-300f, 1000f)
        boundOffsetRight = (if (c.rangeFullScreen) 0f else c.offsetRight).coerceIn(-300f, 1000f)
        debugView?.visibility = if (showDebug) android.view.View.VISIBLE else android.view.View.GONE
        thinkingEnabled = c.thinkingEnabled
        thinkingOffsetY = c.thinkingOffset
        singleTrayMsg = c.singleTrayMsg
        // 碎碎念：独立浮窗，可见性跟随宠物（visible && enabled），按实例错开高度
        // Resolve this role's material before applying function-specific runtime gates.
        rt.spritePackId = c.spritePack
        val pack = resolveCurrentSpritePack(rt)
        pets.filter { it.roleId == roleId }.forEach { it.view.applySpritePack(pack) }
        applySharedConfig(rt)
        // 体感重力：同步开关与强度，并按需启停传感器。
        // 有效体感 = 体感开关开启 且 重力·抛掷总开关开启；重力关时体感整体失效
        // （不注册传感器、不量化方向、不触发 roll），但【不改动】tiltGravity 存储值，
        // 开关 UI 显示保持原值，开启重力后体感自动恢复生效。
        tiltStrength = c.gravity
        val effectiveTilt = c.tiltGravity && c.gravityEnabled
        updateTiltGravity(roleId, effectiveTilt)
        // 重力朝向：非体感模式按四边开关算宠物脚朝向；体感模式初始朝下(0)，
        // 实际朝向由 tiltListener 每帧根据重力向量量化（左右/上主导时临时旋转脚朝左右/上）。
        // 重力朝向：重力·抛掷关闭时强制脚朝正下(0)，与四边开关/体感无关（吸附态仍独立保留朝向）。
        // 广播给所有宠物实例（不再只写主实例 petView）：每只独立设置自身脚朝向，驱动各自渲染旋转与位移方向。
        val gTarget = if (c.gravityEnabled) (if (effectiveTilt) 0 else calcGravityDir(c)) else 0
        pets.filter { it.roleId == roleId }.forEach { inst ->
            if (inst.view.climbWall < 0 && !inst.view.flying) inst.view.gravityDir = gTarget
        }
        // 投喂（支鱼）：初始化投喂状态并启动随机刷出（开关开启时）。各角色独立缓存上次生效值。
        rt.lastFeedingEnabled = c.feedingEnabled
        rt.lastFeedingFishScale = c.feedingFishScale
        rt.lastFeedingFishAlpha = c.feedingFishAlpha
        rt.lastFeedingMaxCount = c.feedingMaxCount
        rt.lastFeedingAutoExpire = c.feedingAutoExpire
        rt.lastFeedingExpireTime = c.feedingExpireTime
        rt.lastFeedingFullScreen = c.feedingFullScreen
        rt.lastFeedingBorderShow = c.feedingBorderShow
        rt.lastFreezeDraggable = c.freezeDraggable
        rt.lastFreezeBorderShow = c.freezeBorderShow
        if (c.feedingEnabled) startFishSpawning(rt)
        recalcBounds()
    }

    private fun registerConfigObserver(rt: RoleRuntime) {
        rt.config.configFlow.onEach { c ->
            // 宠物数量（酥的数量）变化：多退少补，保留现有宠物，仅增删差异部分（见 adjustPetCount）。
            if (c.petCount != lastPetCount) {
                lastPetCount = c.petCount
                adjustPetCount(rt.roleId, c)
            }
            // 定格-拖动开关变化：即时作用于【该角色】已有定格层（开→可点击拖动；关→纯显示不可点击），不串其它角色。
            if (c.freezeDraggable != rt.lastFreezeDraggable) {
                rt.lastFreezeDraggable = c.freezeDraggable
                applyFreezeDraggable(rt.roleId, c.freezeDraggable)
            }
            // 显示定格边框开关变化：即时叠加/移除【该角色】已有定格层的红色真实窗口边框
            if (c.freezeBorderShow != rt.lastFreezeBorderShow) {
                rt.lastFreezeBorderShow = c.freezeBorderShow
                for (layer in freezeLayers.filter { it.roleId == rt.roleId }) setFreezeBorder(layer, c.freezeBorderShow)
            }
            // 投喂（支鱼）：开关变化启停【该角色】随机刷出；其余参数变化即时作用于该角色已有浮窗（互不干扰）。
            if (c.feedingEnabled != rt.lastFeedingEnabled) {
                rt.lastFeedingEnabled = c.feedingEnabled
                if (c.feedingEnabled) startFishSpawning(rt) else stopFishSpawning(rt)
            }
            if (c.feedingFishScale != rt.lastFeedingFishScale || c.feedingFishAlpha != rt.lastFeedingFishAlpha) {
                rt.lastFeedingFishScale = c.feedingFishScale
                rt.lastFeedingFishAlpha = c.feedingFishAlpha
                applyFishScaleAlpha(rt)
            }
            if (c.feedingMaxCount != rt.lastFeedingMaxCount) {
                rt.lastFeedingMaxCount = c.feedingMaxCount
                while (fishLayers.count { it.roleId == rt.roleId } > c.feedingMaxCount) {
                    removeFish(fishLayers.last { it.roleId == rt.roleId })
                }
            }
            if (c.feedingAutoExpire != rt.lastFeedingAutoExpire || c.feedingExpireTime != rt.lastFeedingExpireTime) {
                rt.lastFeedingAutoExpire = c.feedingAutoExpire
                rt.lastFeedingExpireTime = c.feedingExpireTime
                applyFishAutoExpire(rt)
            }
            if (c.feedingFullScreen != rt.lastFeedingFullScreen) {
                rt.lastFeedingFullScreen = c.feedingFullScreen
                // 仅影响下一次刷出位置范围，无需即时处理。
            }
            if (c.feedingBorderShow != rt.lastFeedingBorderShow) {
                rt.lastFeedingBorderShow = c.feedingBorderShow
                for (layer in fishLayers.filter { it.roleId == rt.roleId }) setFishBorder(layer, c.feedingBorderShow)
            }
            if (c.feedingPeriodMin != rt.lastFeedingPeriodMin || c.feedingPeriodMax != rt.lastFeedingPeriodMax) {
                rt.lastFeedingPeriodMin = c.feedingPeriodMin
                rt.lastFeedingPeriodMax = c.feedingPeriodMax
                // 频率周期变化：若该角色投食正在运行，立即用新频率重排下一次掉落的计时器；
                // 否则旧周期会跑完才生效（用户诉求：当前开启该功能时，修改应即时生效）。各角色独立重排。
                if (rt.fishSpawning) {
                    rt.fishSpawnRunnable?.let { mainHandler.removeCallbacks(it) }
                    scheduleFishSpawn(rt)
                }
            }
            if (c.randomPeriodMin != rt.lastRandomPeriodMin || c.randomPeriodMax != rt.lastRandomPeriodMax) {
                rt.lastRandomPeriodMin = c.randomPeriodMin
                rt.lastRandomPeriodMax = c.randomPeriodMax
                // 随机切换周期变化：若随机模式正在运行，立即打断当前等待、以新周期重新计时；
                // 否则要等旧周期跑完才生效。仅开启时重排，未开启不打扰。
                if (c.randomEnabled) requestRandomReset()
            }
            // 物理参数同步：仅作用于本角色实例（各实例物理独立生效）。
            // 有效重力 = 重力·抛掷开关 且 移动总开关（移动关闭则整体静止，与“关闭后「酥」静悄悄地”一致）。
            val effectiveGravity = c.gravityEnabled && (c.moveEnabled || pets.any { it.roleId == rt.roleId && !it.view.hasFunction("standby", "static_pool") })
            pets.filter { it.roleId == rt.roleId }.forEach { inst ->
                val p = inst.view.physics
                p.gravity = c.gravity
                p.reboundRatio = c.reboundRatio
                p.gravityTop = c.gravityTop
                p.gravityBottom = c.gravityBottom
                p.gravityLeft = c.gravityLeft
                p.gravityRight = c.gravityRight
                // 攀岩开关（或移动总开关）关闭时若仍在墙上，立即退出攀爬态（仅清专用状态位，绝不碰全局重力/体感）。
                if (inst.view.climbWall >= 0 && !((c.moveEnabled || !inst.view.hasFunction("standby", "static_pool")) && c.wallClimb)) {
                    inst.view.restoreBaseGravity()
                }
                p.reboundTop = c.reboundTop
                p.reboundBottom = c.reboundBottom
                p.reboundLeft = c.reboundLeft
                p.reboundRight = c.reboundRight
                p.snapTop = c.snapTop
                p.snapBottom = c.snapBottom
                p.snapLeft = c.snapLeft
                p.snapRight = c.snapRight
                p.snapEnabled = c.snapEnabled && inst.view.hasFunction("snap", "snap_head")   // 吸附边缘总开关：关闭则完全不吸附
                p.reboundEnabled = c.reboundEnabled   // 反弹总开关：关闭则所有碰撞反弹失效（撞墙停靠、不振动）
                p.snapDist = c.snapThreshold   // 吸附判定距离阈值（像素）
                p.gravityEnabled = effectiveGravity
                p.maxSpeed = c.maxSpeed
                p.throwEnabled = c.throwEnabled && inst.view.hasFunction("touch", "roll")
                p.throwCoeff = c.throwCoeff
                inst.view.useStaticPool = !(c.moveEnabled || !inst.view.hasFunction("standby", "static_pool"))   // 静止池只看"移动"总开关（重力关仅关重力，不进静止池）
            }
            // 进入静止模式（移动总开关关闭）时，立刻清零速度并复位抛掷态，使物理停止位移。
            // 仅由"移动"总开关决定；关重力不动这里——关重力只关重力、宠物仍在正常池可抛掷滚动。
            // 覆盖所有来源：设置页/菜单页手动关闭、随机模式改关等。
            // 注意：这里只清物理状态，不调 enterIdle()/cancelForced()——避免误杀菜单正在播放的
            // 非移动一次性动作（摸头/炸毛/摇头）。动画切回静坐由 PetView.updateAnimByState 每帧处理：
            // 当移动关且未播放一次性动作时才切 SIT_CLAM，播放中则保留动作（符合"仅播放非移动池"）。
            if (!c.moveEnabled && pets.any { it.roleId == rt.roleId && it.view.useStaticPool }) {
                pets.filter { it.roleId == rt.roleId && it.view.useStaticPool }.forEach { inst ->
                    val p = inst.view.physics
                    if (inst.view.flying) {
                        // 飞行中关闭移动总开关：强制停飞并切静坐（而非继续飞到目的地才落地），
                        // 与滚动态关闭移动即停下的表现一致。
                        inst.view.stopLeapFlightToIdle()
                    } else if (!p.isSnapped) {
                        p.vx = 0f
                        p.vy = 0f
                        p.isThrowing = false   // 复位抛掷态，避免 updateAnimByState 抢回 ROLL
                        p.isDragging = false   // 复位拖拽态，避免关重力后 LIFT_UP 残留
                    }
                }
            }
            // 与 recalcBounds/loadConfig 一致的 coerce 范围，避免越界值进入全局回落（辅助线用）。
            val selectedOffsets = effectiveRangeOffsets(c)
            boundOffsetTop = selectedOffsets[0]
            boundOffsetBottom = selectedOffsets[1]
            boundOffsetLeft = selectedOffsets[2]
            boundOffsetRight = selectedOffsets[3]
            imeAdaptEnabled = c.imeAdapt
            imeLiftOffset = c.imeLiftOffset
            imeHideEnabled = c.imeHide
            effectiveVisible = c.visible && c.enabled
            showDebug = c.showDebug
            showImageBorder = c.showImageBorder
            showControlBorder = c.showControlBorder
            // 控制窗/显示窗开关（隐藏控制层、点击穿透、强制无透明、隐藏画布）均为【按角色独立】设置，
            // 其窗口增删与 NOT_TOUCHABLE 标志的实时应用已统一在 applySharedConfig(rt) 末尾按 roleId 执行，
            // 不再依赖全局变量，避免多角色时一个角色改值污染另一个角色的窗口状态。
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
            applySharedConfig(rt)
            // 资源包切换：仅当包 id 变化时才重建 ImageModeManager 并复位到静坐（低频，可接受短暂停顿）。
            if (c.spritePack != rt.spritePackId) {
                rt.spritePackId = c.spritePack
                val pack = resolveCurrentSpritePack(rt)
                pets.filter { it.roleId == rt.roleId }.forEach { it.view.applySpritePack(pack) }
                // The preceding applySharedConfig used the old role pack; recompute
                // effective function switches without changing stored preferences.
                applySharedConfig(rt)
                reloadFishBitmap(rt.roleId)
            }
            // 体感重力：强度随设置页“重力强度”联动；开关变化启停传感器。
            // 有效体感 = 体感开关开启 且 重力·抛掷总开关开启；重力关时体感整体失效，
            // 但 tiltGravity 存储值不变，开关 UI 显示保持原值。
            tiltStrength = c.gravity
            val effectiveTilt = c.tiltGravity && effectiveGravity
            updateTiltGravity(rt.roleId, effectiveTilt)
            // 重力朝向：重力·抛掷关闭时强制脚朝正下(0)，与四边开关/体感无关（吸附态仍独立保留朝向）。
            // 注意：必须用 c.gravityEnabled 单独守卫，不能只依赖 effectiveTilt（=tiltGravity && gravityEnabled），
            // 否则关重力时 effectiveTilt=false 会落入下方 calcGravityDir 分支，用仍残留的旧四边方向算出
            // 非下方向并触发 enterTiltRoll，造成“先错误方向、再被 updateAnimByState 强制 0 纠正”的闪烁。
            // 脚朝向广播给所有宠物实例（不再只写主实例 petView）：每只独立设置/进 ROLL，
            // 吸附态各自保留朝向、忽略全局方向突变。
            if (effectiveGravity) {
                if (effectiveTilt) {
                    pets.filter { it.roleId == rt.roleId }.forEach { inst -> if (!inst.view.physics.isSnapped && !inst.view.physics.isClimbing) inst.view.gravityDir = 0 }
                } else {
                    val target = calcGravityDir(c)
                    pets.filter { it.roleId == rt.roleId }.forEach { inst ->
                        val v = inst.view
                        // 吸附态/攀岩态：忽略脚朝向突变触发的 ROLL（等待用户手动拉出/召回才退出对应态）。
                        if (v.physics.isSnapped || v.physics.isClimbing) return@forEach
                        // 脚朝向突变：主动进 ROLL 掩盖旧模式（非对称）帧被新旋转角硬转一帧的闪，
                        // 对齐体感 updateTiltGravityDir 行为。方向未变（如改其他设置）不进 ROLL。
                        if (target != v.gravityDir) {
                            if (!v.physics.isThrowing) v.enterTiltRoll(target)
                            else v.gravityDir = target   // 翻滚中仅更新方向，不重复抢 ROLL
                        }
                    }
                }
            } else {
                // 关重力：无条件脚朝正下(0)，且跳过方向突变 ROLL（updateAnimByState 每帧也会兜底纠正）。
                pets.filter { it.roleId == rt.roleId }.forEach { inst -> if (!inst.view.physics.isSnapped && !inst.view.physics.isClimbing) inst.view.gravityDir = 0 }
            }
            recalcBounds()  // 偏移/scale 变化需重算边界（固定基准，不会每帧抖动；与方向检测解耦，无条件重算）
            // 夜间模式强制开关变化：即时重应用（强制打开优先级 > 强制关闭 > 跟随系统）。
            applyNightMode(c)
            if (!c.enabled) {
                pause()
            } else {
                resume()
            }
        }.launchIn(scope).also { rt.configJob = it }
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
    //     用于决定临时把宠物旋转到脚朝左/右（影响 静止及其他普通动画等姿态与位移方向）。
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
                // 把设备坐标重力投影到屏幕平面。统一为“重力向量”语义：
                // 重力传感器读数即指向地心；加速度计静止读数 = -重力（指向天空），需取反，
                // 否则无重力传感器设备会整体符号相反 → “部分手机左右颠倒”。
                // 屏幕旋转用权威来源 cachedRotation（DisplayManager 真实屏幕旋转，@Volatile 可见）。
                // 此处每帧回读一次真实屏幕旋转作为兜底，确保旋转切换时 cachedRotation 不被旧值卡住
                // （DisplayManager 回调已在主线程更新它，这里仅作双保险，开销极小）。
                val curRot = readDisplayRotation()
                if (curRot != cachedRotation) {
                    if (dbgCount % 30 == 0) Log.d("CakePetTilt", "sensor-thread refresh rot $cachedRotation -> $curRot")
                    cachedRotation = curRot
                }
                val sign = if (t == Sensor.TYPE_ACCELEROMETER) -1f else 1f
                val gx = event.values[0] * sign
                val gy = event.values[1] * sign
                // 投影用 Android 官方 SensorManager.remapCoordinateSystem 的 axis 映射（跨设备符号一致），
                // 把“屏幕 x/y 轴在设备传感器坐标(x右,y上,z出屏)中的单位向量”与重力点乘得到屏幕平面分量。
                // 再按竖屏(ROTATION_0)实测校准做一次性全局翻转：屏幕右正用 -projX（竖屏即原 Pair(-gx,gy)），
                // 屏幕下正用 +projY。原横屏映射表 sx 符号漏校，导致横屏左右颠倒，此处一并修正。
                val (ax, ay) = tiltScreenAxes(cachedRotation)
                val projX = gx * ax[0] + gy * ax[1]
                val projY = gx * ay[0] + gy * ay[1]
                val sx = -projX
                val sy = projY
                // 归一：传感器标称约 9.8。倾斜分量 / 9.8 得 [0,1] 倾斜比例，再乘以设置重力强度，
                // 得到屏幕坐标系下的加速度（像素/秒^2）。平放时 sx,sy≈0 -> 宠物几乎不受力。
                // TILT_GAIN：体感专属增益，只作用于体感，不改变四边重力的 gravity 语义。
                pets.forEach { inst ->
                    // 仅本角色开启体感才写入设备重力向量；未开启的角色不受影响。
                    if (!inst.view.physics.tiltGravity) return@forEach
                    val kr = (engines[inst.roleId]?.tiltStrength ?: tiltStrength) / 9.8f * TILT_GAIN
                    inst.view.physics.tiltGx = sx * kr
                    inst.view.physics.tiltGy = sy * kr
                }
                dbgCount++
                if (dbgCount % 30 == 0) {
                    Log.d("CakePetTilt", "sensor gx=$gx gy=$gy rot=$cachedRotation sx=$sx sy=$sy -> tiltGx=${petView.physics.tiltGx} tiltGy=${petView.physics.tiltGy}")
                }
                // 由重力向量量化“脚朝向”：左右分量远大于上下时脚朝左右，上分量主导时脚朝上，
                // 其余（下主导/平衡/平放）脚朝下。旋转会同步影响渲染姿态与 普通动画位移方向，
                // 并在切换时重算边界（窗口为正方形，旋转后尺寸不变，仅锚点偏移随 rotateOffset 变化）。
                updateTiltGravityDir()
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
            // 统一用 DisplayManager 真实屏幕旋转作为权威来源；失败回退 WindowManager defaultDisplay。
            readDisplayRotation()
        } catch (e: Throwable) {
            Log.e("CakePetTilt", "refreshCachedRotation error", e)
            Surface.ROTATION_0
        }
        // 旋转/折叠屏变化后画布尺寸也变了，重设全屏尺寸并 updateViewLayout。
        updateGuideSize()
    }

    /** 读真实屏幕旋转（权威来源）：DisplayManager 的默认 Display 旋转与渲染/边界/脚方向同源。
     *  后台/overlay Service 也能可靠读到当前屏幕实际旋转；失败时回退 defaultDisplay.rotation。 */
    private fun readDisplayRotation(): Int {
        return try {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val d = dm?.getDisplay(Display.DEFAULT_DISPLAY)
            if (d != null) d.rotation else windowManager.defaultDisplay.rotation
        } catch (e: Throwable) {
            Log.e("CakePetTilt", "readDisplayRotation error: ${e.message}")
            windowManager.defaultDisplay.rotation
        }
    }

    /** 屏幕旋转监听：用 DisplayManager.DisplayListener 监听“真实屏幕旋转”变化（后台/overlay 可靠，
     *  优于 OrientationEventListener 在 Service 后台被节流/语义不一致的问题）。旋转变化即刷新体感方向
     *  基准与活动边界（对所有宠物均有益）。 */
    private fun initDisplayRotationListener() {
        cachedRotation = readDisplayRotation()   // 初始即用真实屏幕旋转
        val dm = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return
        displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY) return
                val r = readDisplayRotation()
                val previousRotation = cachedRotation
                val previousScreen = Rect(lastVisRect)
                val currentScreen = getScreenBounds()
                if (r != previousRotation || previousScreen != currentScreen) {
                    cachedRotation = r
                    adaptCanvasOnScreenChange(previousScreen, currentScreen, previousRotation, r)
                    Log.d("CakePetTilt", "display rotation changed -> rot=$r (recalcBounds)")
                    recalcBounds()   // 横屏/折叠屏切换：同步刷新活动边界与窗口尺寸
                    // 旋转真正生效后才回调此处，此刻 screenRealSize() 已反映新朝向；
                    // 必须同步刷新辅助线窗口尺寸，否则窗口仍是旧朝向尺寸、横屏画线被裁切(显示不完全)。
                    updateGuideSize()
                }
            }
        }
        dm.registerDisplayListener(displayListener, mainHandler)
    }

    /** 屏幕 x/y 轴在【设备传感器坐标】(x右, y上, z出屏)中的单位向量，来自官方
     *  SensorManager.remapCoordinateSystem 的 axis 映射，跨设备符号一致。 */
    private fun tiltScreenAxes(rotation: Int): Pair<FloatArray, FloatArray> {
        return when (rotation) {
            Surface.ROTATION_90 -> Pair(floatArrayOf(0f, -1f, 0f), floatArrayOf(1f, 0f, 0f))    // MINUS_Y, X
            Surface.ROTATION_270 -> Pair(floatArrayOf(0f, 1f, 0f), floatArrayOf(-1f, 0f, 0f))   // Y, MINUS_X
            Surface.ROTATION_180 -> Pair(floatArrayOf(-1f, 0f, 0f), floatArrayOf(0f, -1f, 0f))  // MINUS_X, MINUS_Y
            else -> Pair(floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f))                    // X, Y
        }
    }

    /** 真实物理屏尺寸（官方推荐的 Display.getRealSize 替代）。 */
    private fun screenRealSize(): Pair<Int, Int> {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val b = windowManager.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            @Suppress("DEPRECATION")
            val dm = resources.displayMetrics
            dm.widthPixels to dm.heightPixels
        }
    }

    /** 创建并挂上画布窗口（NOT_TOUCHABLE 全屏）。 */
    /** 同步单只宠物的显示窗位置/尺寸并刷新（每帧调用）。显示窗始终按整张位图矩形摆放，与控制窗命中模式无关。 */
    /**
     * 控制窗定位（屏幕绝对坐标）的单一数据源：帧循环与拖拽触摸回调都走这里，避免双路径分歧。
     * - 核心(脚底盒)：控制窗缩到脚盒（控制层几何，见 PetView.controlWindowRect），与显示矩形无关；
     *   脚盒外由 isCoreHit 的 OBB 判定放穿。尺寸随脚盒变化（旋转/脚向变化）才写 width/height。
     * - 像素/边界：窗口为整张位图全框（锚点落在 physics 坐标）。尺寸随整帧变化才写 width/height。
     * 仅改 x/y 与（必要时）width/height，绝不 removeView+addView。
     * 返回是否真改了尺寸（调用方用于更新 lastW/lastH；本函数不直接写 lastW/lastH，保持无副作用）。
     * 注：CORE 下必须用 controlWindowRect() 而非 physics-anchor，否则拖拽时控制窗会错复位到整帧左上。
     */
    /**
     * 锚点固定(foot-fixed)：缩放使窗口尺寸变化时，反向平移 physics.x/y 使【脚锚点屏幕位置不变】
     * （脚 = 左上角 + anchorOff，符号恒 +）。必须在【渲染定位处、与 getBaseBitmapSize() 同一函数/同一条主线程执行流】
     * 内调用：这样“尺寸(scaleFactor)”与“位置修正(physics.x)”在同一帧原子完成，彻底消除投喂/直接输入等大跨度瞬变时
     * “先变尺寸、左上角固定、过一瞬才跳到锚点对齐”的一帧竞态（后台协程写 scaleFactor 若落在 tick 与定位之间，会让该帧
     * 尺寸新、位置旧）。仅 scaleFactor 真变时平移一次，用每帧同步的 lastFixAnchor 作旧基准（含旋转更新）→ 两次缩放间
     * 旋转也正确；动画/旋转帧切换不算缩放 → 不平移（锚点变化由绘图层容忍，符合原设计）。
     */
    private fun applyFootFixed(v: PetView) {
        val ph = v.physics
        val newScale = v.getScale()
        val (ax, ay) = v.getBaseAnchorScaled()
        if (ph.lastFixScale < 0f) {
            ph.lastFixAnchorX = ax
            ph.lastFixAnchorY = ay
            ph.lastFixScale = newScale
        } else if (kotlin.math.abs(newScale - ph.lastFixScale) > 1e-4f) {
            ph.x += (ph.lastFixAnchorX - ax)
            ph.y += (ph.lastFixAnchorY - ay)
            ph.lastFixAnchorX = ax
            ph.lastFixAnchorY = ay
            ph.lastFixScale = newScale
        } else {
            ph.lastFixAnchorX = ax
            ph.lastFixAnchorY = ay
            ph.lastFixScale = newScale
        }
        ph.clampToBounds()
    }

    private fun placeControlWindow(
        v: PetView, lp: WindowManager.LayoutParams,
        lastW: Int, lastH: Int
    ): Boolean {
        applyFootFixed(v)   // 与下方 getBaseBitmapSize() 同帧同流：尺寸/位置原子完成，杜绝一帧竞态
        if (v.hitMode == ConfigDefaults.HIT_CORE) {
            val r = v.controlWindowRect()
            lp.x = r.left.toInt()
            lp.y = r.top.toInt()
            val rw = r.width().toInt()
            val rh = r.height().toInt()
            if (rw != lastW || rh != lastH) {
                lp.width = rw
                lp.height = rh
                return true
            }
        } else {
            // physics.x/y 已是窗口左上角真值，直接作为浮窗左上角（无需再做锚点减法）。
            lp.x = v.physics.x.toInt()
            lp.y = v.physics.y.toInt()
            val (w, h) = v.getBaseBitmapSize()
            if (w != lastW || h != lastH) {
                lp.width = w
                lp.height = h
                return true
            }
        }
        return false
    }

    private fun syncDisplayWindow(inst: PetInstance) {
        val disp = inst.displayView ?: return
        val dlp = inst.displayLp ?: return
        if (!inst.displayAdded) return
        val v = inst.view
        applyFootFixed(v)   // 与下方 getBaseBitmapSize() 同帧同流：尺寸/位置原子完成，杜绝一帧竞态
        val (iw, ih) = v.getBaseBitmapSize()
        // physics.x/y 已是窗口左上角真值，直接作为浮窗左上角（无需锚点减法）。
        val nx = v.physics.x.toInt()
        val ny = v.physics.y.toInt()
        // 仅当位置/尺寸真正变化才 relayout（updateViewLayout 是跨进程重排，开销远大于 invalidate；
        // 静止宠物每帧 relayout 是拆分小窗架构卡顿的元凶，去重后可省）。动画/拖拽时位置必变，照常更新。
        if (dlp.x != nx || dlp.y != ny || dlp.width != iw || dlp.height != ih) {
            dlp.x = nx
            dlp.y = ny
            dlp.width = iw
            dlp.height = ih
            disp.displayLeft = v.physics.x
            disp.displayTop = v.physics.y
            updateDisplay(disp, dlp)
        }
        // 重绘仍需每帧（动画帧在变）；静止帧重绘只是相同位图 blit，开销小。
        disp.invalidate()
    }

    /** 按 hideStage 开关增删各宠物的显示窗（诊断用，实时生效）。 */
    private fun syncDisplayWindows(roleId: String) {
        if (roleId in pendingInitialPlacement) return
        val wantedType = roleDisplayType(roleId)
        for (inst in pets.filter { it.roleId == roleId }) {
            val disp = inst.displayView ?: continue
            val dlp = inst.displayLp ?: continue
            val shouldShow = !roleHideStage(roleId) && rolePetVisible(roleId)
            if (inst.displayAdded && (!shouldShow || dlp.type != wantedType)) {
                removeDisplay(disp, dlp)
                inst.displayAdded = false
            }
            dlp.type = wantedType
            // Trusted accessibility displays must always be non-touchable.
            dlp.flags = if (wantedType == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY ||
                    roleDisplayNotTouchable(roleId))
                dlp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            else dlp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            if (shouldShow && !inst.displayAdded) {
                inst.displayAdded = addDisplay(disp, dlp)
                if (!inst.displayAdded && wantedType == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) {
                    onAccessibilityUnavailable()
                }
            }
        }
    }

    /** 按【该角色】controlHidden（hideControlLayer || clickThrough）增删各宠物控制窗，按 roleId 独立，无需整组重建。 */
    private fun syncControlWindows(roleId: String) {
        if (roleId in pendingInitialPlacement) return
        for (inst in pets.filter { it.roleId == roleId }) {
            if ((roleControlHidden(roleId) || !rolePetVisible(roleId)) && inst.controlAdded) {
                try { windowManager.removeView(inst.view) } catch (_: Exception) {}
                inst.controlAdded = false
            } else if (!roleControlHidden(roleId) && rolePetVisible(roleId) && !inst.controlAdded) {
                try { windowManager.addView(inst.view, inst.layoutParams) } catch (_: Exception) {}
                inst.controlAdded = true
            }
        }
    }

    /**
     * 挂载/卸载辅助线 guide 窗（显式全屏尺寸、NOT_TOUCHABLE）：仅设置期/调试显示偏移线/边框时挂载，
     * 用显式尺寸而非 MATCH_PARENT/全屏标志，避免“两个全屏 NOT_TOUCHABLE 窗叠加吞点击”的 Android 16 问题，
     * 也规避全屏标志在部分系统（如鸿蒙）上的穿透异常。低频出现，不波及日常其它 app。
     */
    private fun syncGuideWindow() {
        val g = guideLineView ?: return
        val visible = g.visibility == android.view.View.VISIBLE
        if (visible && !guideAdded) {
            // 每次挂载都按当前屏幕尺寸重测窗口宽高，避免复用“一次创建时”的旧朝向尺寸
            // （如先竖屏创建、后旋转，窗口仍按竖屏尺寸导致横屏画线被裁切）。
            val (sw, sh) = screenRealSize()
            if (guideParams == null) {
                guideParams = WindowManager.LayoutParams().apply {
                    type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    format = PixelFormat.TRANSLUCENT
                    // FLAG_LAYOUT_IN_SCREEN：辅助线窗 (0,0) 对齐物理屏幕左上角，与 getScreenBounds 物理坐标同源，
                    //   偏移线/吸附线不受状态栏显隐影响（否则窗口原点在活动窗口=状态栏下，线整体下移并随其跳变）。
                    flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    gravity = Gravity.TOP or Gravity.START
                    x = 0
                    y = 0
                }
            }
            guideParams!!.width = sw
            guideParams!!.height = sh
            try { windowManager.addView(g, guideParams) } catch (_: Exception) {}
            guideAdded = true
        } else if (!visible && guideAdded) {
            try { windowManager.removeView(g) } catch (_: Exception) {}
            guideAdded = false
        }
    }

    /** 重设 guide 窗为完整物理屏尺寸（旋转/折叠屏后调用）。 */
    private fun updateGuideSize() {
        if (!guideAdded) return
        val g = guideLineView ?: return
        val (sw, sh) = screenRealSize()
        guideParams?.let { p ->
            p.width = sw
            p.height = sh
            try { windowManager.updateViewLayout(g, p) } catch (_: Exception) {}
        }
    }

    // 启停体感重力：开启时注册传感器监听并强制关闭四边定向重力（避免两套重力叠加），
    // 关闭时注销监听并清零向量。
    private fun updateTiltGravity(roleId: String, enabled: Boolean) {
        // 仅本角色实例开启/关闭体感（其余角色不受影响）。
        for (inst in pets.filter { it.roleId == roleId }) {
            inst.view.physics.tiltGravity = enabled
        }
        // 体感传感器为共享单例：任意角色体感有效即需注册；全部关闭才整体注销。
        val anyEnabled = engines.values.any { rt ->
            val c = rt.config.getBlocking()
            c.tiltGravity && c.gravityEnabled
        }
        if (anyEnabled == tiltGravityEnabled) return
        tiltGravityEnabled = anyEnabled
        Log.d("CakePetTilt", "updateTiltGravity anyEnabled=$anyEnabled")
        try {
            if (anyEnabled) {
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
                // 体感模式下置 tiltGravity=enabled（即有效体感，已含 gravityEnabled 判断），
                // 【不改动】四边定向重力的存储值与物理字段，关闭体感后四边开关原值自动恢复生效。
                // step() 已用 if (gravityEnabled) 与 if (tiltGravity) 分支优先体感，四边值仅被忽略不被使用。
                // 体感方向按各角色自身状态独立量化（见 updateTiltGravityDir）。
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
     *     临时旋转宠物，使其脚指向屏幕左右，并影响 普通动画等位移方向。
     *   - 否则若上分量主导（tiltGy < 0）：脚朝上（手机倒扣/上倾）。
     *   - 其余（下主导 / 平衡 / 平放）：脚朝下（默认）。
     * 带滞回：仅当方向真变化且合成加速度足够大时才切换并重算边界，避免传感器噪声抖动。
     * 窗口为正方形，旋转后尺寸不变，仅锚点偏移随 rotateOffset 变化，recalcBounds 即可对齐贴边。
     * 注意：仅体感开启时调用（由 tiltListener 每帧触发），非体感方向由 calcGravityDir 离散决定。
     */
    private fun updateTiltGravityDir() {
        if (!tiltGravityEnabled) return
        // 体感方向按各角色自身重力强度独立量化（其余状态各实例按自身决定翻滚/重算边界）。
        pets.forEach { inst ->
            val kr = (engines[inst.roleId]?.tiltStrength ?: tiltStrength) / 9.8f * TILT_GAIN
            updateTiltGravityDirFor(inst.view, kr)
        }
    }

    private fun updateTiltGravityDirFor(view: PetView, k: Float) {
        // 吸附态/攀岩态：忽略体感重力方向变化（不滚、不重算边界），等待用户手动拉出/召回才退出对应态。
        if (view.physics.isSnapped || view.physics.isClimbing) return
        // 重力·抛掷关闭时强制脚朝正下(0)，不随体感量化改朝向（onEach 已设 0）。
        if (!view.physics.gravityEnabled) return
        // 仅本角色开启体感才按设备方向量化脚朝向；未开启体感的角色脚方向保持自身设置（四边/固定），
        // 不随共享传感器被全局串成同一朝向。
        if (!view.physics.tiltGravity) return
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
        if (target != view.gravityDir && view.climbWall < 0 && !view.flying) {
            if (view.physics.isDragging) {
                // 提起态：直接更新脚朝向（=主重力方向），提起动画 LIFT_UP 实时旋转，
                // 不触发翻滚（roll 为独立逻辑，此处不动）。否则 enterTiltRoll 在拖动态会被
                // isDragging 守卫直接 return，导致 gravityDir 始终不更新、提起动画不旋转。
                view.gravityDir = target
                boundsDirty = true
            } else if (!view.physics.isThrowing) {
                // 脚朝向突变：主动进入翻滚掩盖姿态硬切（方向变了=有新重力，翻滚符合物理直觉）。
                // 翻滚中(!isThrowing)才触发，避免方向高频变化时重复抢 ROLL 动画；
                // 翻滚期间再变只更新 gravityDir（姿态随 ROLL 自然转）。
                // 关键顺序：先 enterTiltRoll（切到居中对称的 ROLL 帧），再在 ROLL 内部设 gravityDir，
                // 避免先改重力旋转角导致【旧模式帧被新朝向硬转一帧】闪现，然后才切到 ROLL。
                view.enterTiltRoll(target)
                // 旋转改变脚落点，重算边界使锚点活动范围与贴边/贴地对齐（开销极小）。
                // 实际重算延后到帧循环统一执行（见 boundsDirty），避免传感器线程改 physics 与帧循环竞争。
                boundsDirty = true
            } else {
                view.gravityDir = target
                boundsDirty = true
            }
        }
    }

    // 边界重算请求标志：由传感器线程（onSensorChanged→updateTiltGravityDirFor）在脚方向变化时置位，
    // 实际重算由帧循环统一执行。避免传感器后台线程直接 clamp physics.x/y 与帧循环竞争，
    // 且保证 recalcBounds 与窗口定位在同一帧、用同一份锚点快照（currentFrameAnchor 由 tick 刷新），
    // 消除“脚方向变化→锚点变→位置被改→又重算”的跳变/抖动回归。
    @Volatile private var boundsDirty = false

    // Keyboard adaptation changes coordinates only; animation and physics states remain intact.
    // Separate offsets per object prevent one pet from dragging every fish / freeze with it.
    private val imeShiftY = mutableMapOf<String, Float>()
    private val imePetOffsets = mutableMapOf<PetView, Int>()
    private val imeFishOffsets = mutableMapOf<ImageView, Int>()
    private val imeFreezeOffsets = mutableMapOf<ImageView, Int>()
    private val imeLastMode = mutableMapOf<String, String>()
    private val imeLastTop = mutableMapOf<String, Float>()

    private fun imePosition(
        currentY: Int, height: Int, applied: Int, mode: String,
        keyboardTop: Float, vis: Rect, active: Boolean, localClosing: Boolean,
        previousTop: Float?, stretchTop: Float, stretchSourceBottom: Float,
        stretchTargetBottom: Float
    ): Pair<Int, Int> {
        val baseY = currentY - applied
        if (localClosing) {
            // By design, closing local lift gathers objects at the old keyboard
            // top back to the canvas bottom, regardless of why they got there.
            val atKeyboardTop = previousTop != null &&
                kotlin.math.abs(currentY + height - previousTop) <= 12f * resources.displayMetrics.density
            val newY = if (atKeyboardTop) vis.bottom - height else currentY
            return newY to 0
        }
        if (!active) return baseY to 0
        val targetY = when (mode) {
            "global" -> baseY + (keyboardTop - vis.bottom).toInt()
            "stretch" -> {
                // Scale configured canvas geometry rather than the raw full screen.
                val sourceHeight = (stretchSourceBottom - stretchTop).coerceAtLeast(1f)
                val targetHeight = (stretchTargetBottom - stretchTop).coerceAtLeast(1f)
                val center = baseY + height / 2f
                val mappedCenter = stretchTop + (center - stretchTop) * targetHeight / sourceHeight
                (mappedCenter - height / 2f).toInt().coerceIn(
                    stretchTop.toInt(), (stretchTargetBottom.toInt() - height).coerceAtLeast(stretchTop.toInt())
                )
            }
            "local" -> minOf(baseY, keyboardTop.toInt() - height)
            else -> baseY
        }
        return targetY to (targetY - baseY)
    }

    private fun moveAuxiliary(layer: ImageView, lp: WindowManager.LayoutParams, dy: Int, border: View?) {
        if (dy != 0) {
            lp.y += dy
            try { windowManager.updateViewLayout(layer, lp) } catch (_: Exception) {}
            border?.let { bv ->
                val blp = bv.layoutParams as? WindowManager.LayoutParams
                if (blp != null) {
                    blp.x = lp.x
                    blp.y = lp.y
                    try { windowManager.updateViewLayout(bv, blp) } catch (_: Exception) {}
                }
            }
        }
    }

    private fun recalcBounds() {
        val vis = getScreenBounds()
        lastVisRect = vis
        for ((rid, rt) in engines) {
            val c = rt.config.getBlocking()
            val selectedOffsets = effectiveRangeOffsets(c)
            boundOffsetTop = selectedOffsets[0]
            boundOffsetBottom = selectedOffsets[1]
            boundOffsetLeft = selectedOffsets[2]
            boundOffsetRight = selectedOffsets[3]
            val mode = c.keyboardMode()
            val kb = maxOf(viewImeInset, globalImeInset)
            val active = kb > 0f && mode in listOf("global", "stretch", "local")
            val rawKeyboardTop = vis.bottom - kb - (if (active) c.imeLiftOffset else 0f)
            val rotatedBottomOffset = rotatedBoundOffsets(floatArrayOf(
                boundOffsetTop, boundOffsetBottom, boundOffsetLeft, boundOffsetRight
            ))[2]
            val keyboardTop = rawKeyboardTop
            val rotatedTopOffset = rotatedBoundOffsets(floatArrayOf(
                boundOffsetTop, boundOffsetBottom, boundOffsetLeft, boundOffsetRight
            ))[0]
            val stretchTop = vis.top + rotatedTopOffset
            val stretchSourceBottom = vis.bottom - rotatedBottomOffset
            val stretchTargetBottom = rawKeyboardTop - rotatedBottomOffset
            val wasLocal = imeLastMode[rid] == "local"
            val closingLocal = kb <= 0f && wasLocal
            val previousTop = imeLastTop[rid]
            // Bounds reset uses the raw keyboard translation. The extra bottom-offset
            // compensation applies only to object positions, otherwise the floor moves twice.
            val newGlobalShift = if (active && mode == "global") rawKeyboardTop - vis.bottom else 0f
            imeShiftY[rid] = newGlobalShift
            val oldRect = if (c.feedingFullScreen) null else activityRectOf(rid)
            val gluedEdges = if (oldRect != null) {
                fishLayers.filter { it.roleId == rid }.map { it to detectGlueEdge(oldRect, it.lp) }
            } else emptyList()

            val grounded = mutableMapOf<PetView, Boolean>()
            pets.filter { it.roleId == rid }.forEach { inst ->
                val p = inst.view.physics
                grounded[inst.view] = p.maxY > vis.top + 10f &&
                    kotlin.math.abs(p.y - p.maxY) < 1.5f
                val applied = imePetOffsets[inst.view] ?: 0
                val oldY = Math.round(p.y)
                val imageHeight = inst.view.height.takeIf { it > 0 }
                    ?: inst.view.getBaseBitmapSize().second
                val (newY, newOffset) = imePosition(oldY, imageHeight, applied,
                    mode, keyboardTop, vis, active, closingLocal, previousTop,
                    stretchTop, stretchSourceBottom, stretchTargetBottom)
                if (newY != oldY) p.y += newY - oldY
                if (newOffset == 0) imePetOffsets.remove(inst.view)
                else imePetOffsets[inst.view] = newOffset
            }
            fishLayers.filter { it.roleId == rid }.forEach { fl ->
                val applied = imeFishOffsets[fl.iv] ?: 0
                val (mappedY, newOffset) = imePosition(fl.lp.y, fl.lp.height, applied,
                    mode, keyboardTop, vis, active, closingLocal, previousTop,
                    stretchTop, stretchSourceBottom, stretchTargetBottom)
                // Food is clamped to the role's offset canvas floor after IME mapping.
                // It can differ from the raw keyboard top by more than the 12dp tolerance.
                // Restore lifted food (or food at that effective floor) on local close;
                // the final reclamp applies normal canvas offsets again.
                val oldEffectiveTop = previousTop?.minus(
                    if (c.feedingFullScreen) 0f else rotatedBottomOffset)
                val atEffectiveTop = closingLocal && oldEffectiveTop != null &&
                    kotlin.math.abs(fl.lp.y + fl.lp.height - oldEffectiveTop) <=
                        12f * resources.displayMetrics.density
                val newY = if (closingLocal && (applied != 0 || atEffectiveTop))
                    vis.bottom - fl.lp.height else mappedY
                moveAuxiliary(fl.iv, fl.lp, newY - fl.lp.y, fl.borderView)
                if (newOffset == 0) imeFishOffsets.remove(fl.iv) else imeFishOffsets[fl.iv] = newOffset
            }
            freezeLayers.filter { it.roleId == rid }.forEach { fl ->
                val applied = imeFreezeOffsets[fl.iv] ?: 0
                val (newY, newOffset) = imePosition(fl.lp.y, fl.lp.height, applied,
                    mode, keyboardTop, vis, active, closingLocal, previousTop,
                    stretchTop, stretchSourceBottom, stretchTargetBottom)
                moveAuxiliary(fl.iv, fl.lp, newY - fl.lp.y, fl.borderView)
                if (newOffset == 0) imeFreezeOffsets.remove(fl.iv) else imeFreezeOffsets[fl.iv] = newOffset
            }
            pets.filter { it.roleId == rid }.forEach { inst ->
                val p = inst.view.physics
                recalcBoundsFor(inst.view, vis, rid)
                // A grounded pet must remain on its new effective floor.
                // Adjust coordinates and the bookkeeping offset, never its animation/state.
                if (active && grounded[inst.view] == true) {
                    val correction = p.maxY - p.y
                    if (kotlin.math.abs(correction) > 0.5f) {
                        p.y = p.maxY
                        imePetOffsets[inst.view] = (imePetOffsets[inst.view] ?: 0) + Math.round(correction)
                    }
                }
            }
            val newRect = if (c.feedingFullScreen) null else activityRectOf(rid)
            if (oldRect != null && newRect != null && oldRect != newRect && mode == "global") {
                repinGluedFish(gluedEdges, newRect)
            }
            if (kb > 0f) {
                imeLastTop[rid] = keyboardTop
                imeLastMode[rid] = mode
            } else {
                imeLastTop.remove(rid)
                imeLastMode.remove(rid)
            }
        }
        reclampAllFish()
    }

    /** 该角色宠物的活动范围矩形（physics.snapMin/Max，屏幕绝对坐标），无宠物实例时返回 null。 */
    private fun activityRectOf(roleId: String): Rect? {
        val sb = pets.firstOrNull { it.roleId == roleId }?.view?.physics ?: return null
        return Rect(sb.snapMinX.toInt(), sb.snapMinY.toInt(), sb.snapMaxX.toInt(), sb.snapMaxY.toInt())
    }

    /** 判定鱼的窗口相对活动区贴的是哪条边：2=下 1=上 3=左 4=右 0=非贴边(随机位)。容差 2px。 */
    private fun detectGlueEdge(rect: Rect, lp: WindowManager.LayoutParams): Int {
        val gluedBottom = Math.abs((lp.y + lp.height) - rect.bottom) <= EDGE_GLUE_TOL
        val gluedTop = Math.abs(lp.y - rect.top) <= EDGE_GLUE_TOL
        val gluedLeft = Math.abs(lp.x - rect.left) <= EDGE_GLUE_TOL
        val gluedRight = Math.abs((lp.x + lp.width) - rect.right) <= EDGE_GLUE_TOL
        // 角落鱼可能同时贴两边：按「下→上→左→右」取首选，确保底部鱼优先被重钉到底边
        // Prefer bottom, then top, left, right when a fish touches multiple edges.
        return when {
            gluedBottom -> 2
            gluedTop -> 1
            gluedLeft -> 3
            gluedRight -> 4
            else -> 0
        }
    }

    /** 贴边判定容差(px)：鱼边与活动区边距离 ≤ 此值即视为「贴边刷新」产生的贴边鱼。 */
    private val EDGE_GLUE_TOL = 2

    /**
     * 把「贴边」的鱼重钉到【新】活动区的对应边（非贴边/随机位鱼保持原平移，不动）。
     * Keep previously edge-pinned fish aligned when the keyboard changes their activity region.
     */
    private fun repinGluedFish(glued: List<Pair<FishLayer, Int>>, newRect: Rect) {
        for ((fl, edge) in glued) {
            if (edge == 0) continue
            val lp = fl.lp
            val nx = when (edge) {
                3 -> newRect.left
                4 -> newRect.right - lp.width
                else -> lp.x
            }
            val ny = when (edge) {
                1 -> newRect.top
                2 -> newRect.bottom - lp.height
                else -> lp.y
            }
            if (nx == lp.x && ny == lp.y) continue
            lp.x = nx; lp.y = ny
            try { windowManager.updateViewLayout(fl.iv, lp) } catch (_: Exception) {}
            fl.borderView?.let { bv ->
                val blp = bv.layoutParams as? WindowManager.LayoutParams
                if (blp != null) {
                    blp.x = lp.x; blp.y = lp.y
                    try { windowManager.updateViewLayout(bv, blp) } catch (_: Exception) {}
                }
            }
        }
    }

    // 用户配置的边界偏移是“物理”语义（竖屏自然朝向）：
    //   boundOffsetTop=物理上(状态栏)、boundOffsetBottom=物理下(导航栏)、boundOffsetLeft=物理左、boundOffsetRight=物理右。
    // 横/竖屏切换时用真实屏幕旋转 cachedRotation 把它指派到对应的“屏幕边”，保证状态栏/导航栏内缩
    // 始终落在物理栏所在的屏幕边（而非当前屏幕上下），消除横屏时“上下误用 140、左右被压出界”。
    // 返回 [屏Top, 屏Right, 屏Bottom, 屏Left] 四个屏幕边偏移。
    /** Map picture centers between effective canvas rectangles, once per screen change.
     * Same center-based mapping as the keyboard canvas stretch, extended to both axes.
     */
    private fun adaptCanvasOnScreenChange(oldScreen: Rect, newScreen: Rect, oldRotation: Int, newRotation: Int) {
        if (oldScreen.width() <= 0 || oldScreen.height() <= 0 ||
            newScreen.width() <= 0 || newScreen.height() <= 0) return
        val turns = (newRotation - oldRotation + 4) % 4
        fun canvas(c: PetConfigData, screen: Rect, rot: Int): Rect {
            val margins = rotatedBoundOffsets(effectiveRangeOffsets(c, screen), rot)
            return Rect((screen.left + margins[3]).toInt(), (screen.top + margins[0]).toInt(),
                (screen.right - margins[1]).toInt(), (screen.bottom - margins[2]).toInt())
        }
        fun mapped(cx: Float, cy: Float, source: Rect, dest: Rect, rotate: Boolean): Pair<Float, Float> {
            val u = ((cx - source.left) / source.width().coerceAtLeast(1)).coerceIn(0f, 1f)
            val v = ((cy - source.top) / source.height().coerceAtLeast(1)).coerceIn(0f, 1f)
            val (tx, ty) = if (!rotate) u to v else when (turns) {
                1 -> v to (1f - u)   // physical bottom becomes screen right
                2 -> (1f - u) to (1f - v)
                3 -> (1f - v) to u
                else -> u to v
            }
            return (dest.left + tx * dest.width()) to (dest.top + ty * dest.height())
        }
        fun rotateSide(side: Int): Int {
            var s = side
            repeat(turns) {
                s = when (s) { 0 -> 2; 2 -> 3; 3 -> 1; else -> 0 }
            }
            return s
        }
        for ((roleId, runtime) in engines) {
            val c = runtime.config.getBlocking()
            val source = canvas(c, oldScreen, oldRotation)
            val dest = canvas(c, newScreen, newRotation)
            val followDevice = c.rotationAdaptMode == "device"
            pets.filter { it.roleId == roleId }.forEach { inst ->
                val p = inst.view.physics
                val (w, h) = inst.view.getBaseBitmapSize()
                val (cx, cy) = mapped(p.x + w / 2f, p.y + h / 2f, source, dest, followDevice)
                p.x = cx - w / 2f
                p.y = cy - h / 2f
                if (followDevice && turns != 0) {
                    if (p.isClimbing) inst.view.remapClimbWallForRotation(rotateSide(p.climbSide))
                    if (p.isSnapped) p.snapSide = rotateSide(p.snapSide)
                    val vx = p.vx; val vy = p.vy
                    val (nx, ny) = when (turns) {
                        1 -> vy to -vx
                        2 -> -vx to -vy
                        3 -> -vy to vx
                        else -> vx to vy
                    }
                    p.vx = nx; p.vy = ny
                }
            }
            fishLayers.filter { it.roleId == roleId }.forEach { fish ->
                val lp = fish.lp
                val (cx, cy) = mapped(lp.x + lp.width / 2f, lp.y + lp.height / 2f, source, dest, followDevice)
                lp.x = kotlin.math.round(cx - lp.width / 2f).toInt()
                lp.y = kotlin.math.round(cy - lp.height / 2f).toInt()
                try { windowManager.updateViewLayout(fish.iv, lp) } catch (_: Exception) {}
                fish.borderView?.let { border ->
                    val blp = border.layoutParams as? WindowManager.LayoutParams
                    if (blp != null) {
                        blp.x = lp.x; blp.y = lp.y
                        try { windowManager.updateViewLayout(border, blp) } catch (_: Exception) {}
                    }
                }
            }
            freezeLayers.filter { it.roleId == roleId }.forEach { layer ->
                val lp = layer.lp
                val (cx, cy) = mapped(lp.x + lp.width / 2f, lp.y + lp.height / 2f, source, dest, followDevice)
                lp.x = kotlin.math.round(cx - lp.width / 2f).toInt()
                lp.y = kotlin.math.round(cy - lp.height / 2f).toInt()
                try { windowManager.updateViewLayout(layer.iv, lp) } catch (_: Exception) {}
                layer.borderView?.let { border ->
                    val blp = border.layoutParams as? WindowManager.LayoutParams
                    if (blp != null) {
                        blp.x = lp.x; blp.y = lp.y
                        try { windowManager.updateViewLayout(border, blp) } catch (_: Exception) {}
                    }
                }
            }
        }
    }

    private fun effectiveRangeOffsets(c: PetConfigData, screen: Rect = getScreenBounds()): FloatArray {
        if (c.rangeFullScreen || (screen.width() > screen.height() && c.landscapeRangeMode == "full")) {
            return floatArrayOf(0f, 0f, 0f, 0f)
        }
        return floatArrayOf(
            c.offsetTop.coerceIn(-300f, 2100f), c.offsetBottom.coerceIn(-300f, 2100f),
            c.offsetLeft.coerceIn(-300f, 1000f), c.offsetRight.coerceIn(-300f, 1000f)
        )
    }

    /** Rotation argument lets the old and new canvas use their own physical edges. */
    private fun rotatedBoundOffsets(src: FloatArray? = null, rotation: Int = cachedRotation): FloatArray {
        val t = src?.getOrElse(0) { boundOffsetTop } ?: boundOffsetTop
        val b = src?.getOrElse(1) { boundOffsetBottom } ?: boundOffsetBottom
        val l = src?.getOrElse(2) { boundOffsetLeft } ?: boundOffsetLeft
        val r = src?.getOrElse(3) { boundOffsetRight } ?: boundOffsetRight
        return when (rotation) {
            Surface.ROTATION_0 -> floatArrayOf(t, r, b, l)   // 原样：屏Top=物理上、屏Right=物理右…
            // ROTATION_90：设备逆时针转 90° → 物理顶(状态栏)→屏左、物理底(充电口)→屏右、
            //   物理左→屏下、物理右→屏上（注意：物理左/右落在屏幕上下，且物理左在屏下、物理右在屏上）。
            Surface.ROTATION_90 -> floatArrayOf(r, b, l, t)
            Surface.ROTATION_180 -> floatArrayOf(b, l, t, r) // 物理下→屏上、物理左→屏右、物理上→屏下、物理右→屏左
            // ROTATION_270：设备顺时针转 90° → 物理顶(状态栏)→屏右、物理底(充电口)→屏左、
            //   物理左→屏上、物理右→屏下（与 ROTATION_90 左右镜像，物理左在屏上、物理右在屏下）。
            else -> floatArrayOf(l, t, r, b)
        }
    }

    // 为单个宠物实例计算并设置边界（探头/出界模型，详见 recalcBounds 旧注释）。
    private fun recalcBoundsFor(view: PetView, vis: android.graphics.Rect, roleId: String) {
        // 多角色隔离：边界偏移与键盘适应均取自【本角色】配置，绝不再读服务级全局别名
        // （boundOffset*/imeAdaptEnabled 等为单例 scratch，多角色下会被最后加载角色覆盖，导致串角色）。
        val rt = engines[roleId] ?: return
        val rc = rt.config.getBlocking()
        val selectedOffsets = effectiveRangeOffsets(rc, vis)
        val offTop = selectedOffsets[0]
        val offBottom = selectedOffsets[1]
        val offLeft = selectedOffsets[2]
        val offRight = selectedOffsets[3]
        // physics.x/y 为【窗口左上角坐标】（位置真值），脚锚点 = physics ∓ anchorOff 仅在贴边/snap 时派生。
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
        // 旋转/脚朝向必须与实际渲染严格同源（stateRotation: flying>isClimbing>isSnapped>重力）。
        // 之前只特判了吸附态，漏掉攀爬态——攀爬渲染用 climbRotation/climbSide（图被旋 90/270°、脚朝侧墙），
        // 边界却按重力直立算，导致“哪条边是锚点边”错位：左右墙向下沿墙移动 时图底越出下边界才换墙。
        val rotDeg = when {
            view.physics.isSnapped -> view.snapRotation
            view.physics.isClimbing -> view.climbRotation
            else -> view.gravityRotation
        }
        val (halfW, halfH) = view.getRotatedHalfExtents(w, h, rotDeg)
        val acx = ax - w / 2f
        val acy = ay - h / 2f
        // 脚朝向：吸附态=snapSide(0底/1左/2右/3顶)，攀爬态=climbSide(同上)，非二者=gravityDir(0下/1左/2右/3上)。
        // 脚朝向的那一侧为【锚点边】：只约束“锚点(脚)不超可见区”，图底可越过屏幕外（探头/出界）；
        // 其余三侧仍为【PNG 四边】约束（图边不超可见区）。这样 sit 脚贴边、SNAP 脚贴边且身体下垂出界、
        // 攀爬脚朝墙、身体沿墙、图边按旋转后包围盒精确贴边，互不干扰。
        val footDir = when {
            view.physics.isSnapped -> view.physics.snapSide
            view.physics.isClimbing -> view.physics.climbSide
            else -> view.gravityDir
        }
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
        // 用户偏移按真实屏幕旋转重映射到屏幕边：[屏Top, 屏Right, 屏Bottom, 屏Left]。
        // 这样横屏时状态栏(物理上)内缩落在屏幕左/右，屏幕上下用 0，左右溢出与“上下误缩”一并消失。
        val ob = rotatedBoundOffsets(floatArrayOf(offTop, offBottom, offLeft, offRight))
        val rotLeft = ob[3]
        val rotRight = ob[1]
        val rotTop = ob[0]
        val rotBottom = ob[2]
        val minX = vis.left.toFloat() + (if (leftIsAnchor) 0f else leftInset) + rotLeft
        val maxX = (vis.right.toFloat() - (if (rightIsAnchor) 0f else rightInset) - rotRight).coerceAtLeast(minX)
        // 键盘适应（新版「画布整体顶起」模型）：不再【削小】活动范围(仅抬下界)，
        // 而是把【整个坐标系】(宠物当前位置/活动范围/食物当前位置/食物活动范围/范围辅助线)统一上移 shiftY。
        // shiftY=-(键盘高度+lift)：键盘出现即为负(上移)，消失归零；范围大小不变、仅整体抬升，
        // 宠物/食物/范围线保持相对位置一起被键盘顶起，键盘消失 shiftY=0 整体复原。
        // 注意：宠物 physics.y 与该角色全部食物 lp.y 的同步位移由 recalcBounds 按 delta(新shiftY-旧shiftY)施加，
        // 使其与范围同向同量移动；本函数只负责按 shiftY 平移【范围本身】。
        val keyboardMode = rc.keyboardMode()
        val imeActive = keyboardMode in listOf("global", "stretch", "local") &&
            (viewImeInset > 0f || globalImeInset > 0f)
        val imeBottomInset = if (imeActive) maxOf(viewImeInset, globalImeInset) else 0f
        // 抬高偏移：仅在输入法防误触开启且键盘真实弹出时，才在键盘地面之上额外抬高，正=更高、负=更低。
        val lift = if (imeActive) rc.imeLiftOffset else 0f
        // Keep user-defined boundary offsets in every keyboard mode.
        val topOff = rotTop
        val botOff = rotBottom
        // 活动范围【基底】(不含键盘)：顶/底按锚点边与用户偏移照常计算。
        val minY0 = vis.top.toFloat() + (if (topIsAnchor) 0f else topInset) + topOff
        val maxY0 = (vis.bottom.toFloat() - (if (botIsAnchor) 0f else botInset) - botOff).coerceAtLeast(minY0)
        // All effective bounds use the same geometry as the object mapping.
        // Global translation preserves the pre-keyboard distance to both floors.
        // Local/stretch changes the available bottom edge but not the top edge.
        val shiftY = if (keyboardMode == "global" && imeActive) imeShiftY[roleId] ?: 0f else 0f
        val rawMinY = minY0 + shiftY
        val rawMaxY = maxY0 + shiftY
        val adaptedBottom = vis.bottom.toFloat() - imeBottomInset - lift - botOff
        val maxY = if (imeActive && keyboardMode in listOf("local", "stretch"))
            minOf(rawMaxY, adaptedBottom - (if (botIsAnchor) 0f else botInset))
                .coerceAtLeast(rawMinY)
        else rawMaxY
        val minY = rawMinY
        // physics.x/y = 窗口左上角真值；活动范围换算为窗口范围 = 脚范围(上面 min/max) − 锚点偏移(ax,ay)。
        // 旋转/缩放只改该范围、不再改写 physics.x/y → 锚点变化不会触发窗口跳变（脚锚点按需派生）。
        // 活动范围 = 脚范围(min/max) − 锚点偏移(ax,ay)。此处【只设定活动范围，不钳制 physics.x/y】。
        // 原因：缩放变更时若先 clamp，会破坏缩放前原始脚锚点的屏幕位置，导致随后 applyFootFixed 的脚固定平移
        // 基于“已被钳制过的位置”去平移，最终窗口被推离边界、留下空隙（放大/瞬变最明显）。
        // 统一由 applyFootFixed 在脚固定平移之后做一次钳制（每帧、布局前执行）：窗口紧贴边界、无空隙，且仍防越界。
        view.physics.setBounds(minX - ax, minY - ay, maxX - ax, maxY - ay)
        // 纯偏移吸附边界线（与边界偏移线/吸附环带同几何，不含图片 inset、不随脚朝向变）：
        // = 屏幕可见区 ± 用户偏移（按旋转重映射后的屏幕边偏移）。
        view.physics.setSnapBounds(
            vis.left.toFloat() + rotLeft,
            vis.top.toFloat() + topOff + shiftY,
            vis.right.toFloat() - rotRight,
            if (imeActive && keyboardMode in listOf("local", "stretch")) adaptedBottom
            else vis.bottom.toFloat() - botOff + shiftY
        )
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
        // 调试控制边框画在控制窗自身（与命中区几何严格同源，CORE 脚盒/整帧都精确贴合，
        // 且不受 hideStage 影响；hideControlLayer 时控制窗被移除、边框一并隐藏，符合“隐藏控制层”语义）。
        // 注意：控制边框是【按角色独立】设置（applySharedConfig 已逐实例写入 inst.view.showControlBorder），
        // 禁止用服务级全局 showControlBorder 广播覆盖，否则改 A 角色边框会串到全部角色。
        for (inst in pets) {
            inst.view.invalidate()
        }
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
            syncGuideWindow()
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
        syncGuideWindow()
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
        // 键盘适应「画布整体顶起」：辅助线(活动范围/吸附/键盘预览)随真实运行一起上移 shiftY，
        // 与宠物/食物/活动范围保持同一坐标系，避免键盘弹起时辅助线仍画在原屏幕位置。
        var vis = getScreenBounds()
        val gc = config.getBlocking()
        val gImeActive = gc.imeAdapt && (viewImeInset > 0f || globalImeInset > 0f)
        val gKb = if (gImeActive) maxOf(viewImeInset, globalImeInset) else 0f
        val gLift = if (gImeActive) gc.imeLiftOffset else 0f
        val gShiftY = if (gImeActive) -(gKb + gLift) else 0f
        if (gShiftY != 0f) vis = android.graphics.Rect(vis.left, (vis.top + gShiftY).toInt(), vis.right, (vis.bottom + gShiftY).toInt())
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
            // 用户偏移是“物理”语义，按真实屏幕旋转重映射到屏幕边后再画，与运行时边界一致。
            val rob = rotatedBoundOffsets(o)
            val top = rob[0]; val right = rob[1]; val bottom = rob[2]; val left = rob[3]
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
            val s = currentShortName
            val snapWarn: Pair<String, String>? = when {
                !snapMaster || !anyDirOn ->
                    "都不让贴边边！\n「${s}」怎么贴贴贴？" to "当前没有被打开的吸附方向"
                rangeEmpty ->
                    "都没有活动范围？\n「${s}」只能一直贴边边了！" to "当前活动范围不存在，超容易吸附边缘。"
                snapRingEmpty ->
                    "贴边边太容易太舒服了，\n${s}要一直贴边边！" to "活动范围内，全是吸附区域，超容易贴边边。"
                else -> null
            }
            this.snapWarnSpec = snapWarn
        } else if (hasBoundOnly) {
            // 纯边界偏移来源：计算「活动范围是否为零」，用于无范围振动 + 红框。
            // 注意：仅 BOUND 来源参与 empty 判定，IME 等其他来源拖动时不触发无范围振动/红框。
            val o = boundGuideOverride
            // 用户偏移是“物理”语义，按真实屏幕旋转重映射到屏幕边后再画，与运行时边界一致。
            val rob = rotatedBoundOffsets(o)
            val topOff = rob[0]; val botOff = rob[2]; val leftOff = rob[3]; val rightOff = rob[1]
            lines += boundLines(vis, topOff, botOff, leftOff, rightOff)
            val leftX = vis.left + leftOff
            val rightX = vis.right - rightOff
            val topY = vis.top + topOff
            val botY = vis.bottom - botOff
            empty = rightX <= leftX || botY <= topY
        } else if (hasIme) {
            // Preview uses the same user-defined boundaries as the running pet.
            val rob = rotatedBoundOffsets(boundGuideOverride)
            val topOff = rob[0]; val botOff = rob[2]; val leftOff = rob[3]; val rightOff = rob[1]
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
                "那么小的「${currentShortName}」？\n 还能找的到吗？" to "超迷你的「${currentShortName}」，很难被抓住的哦"
            hasSize && cfg.scale > 5f ->
                "那么大的「${currentShortName}」？\n手机重重的！重重的！" to "超巨大的「${currentShortName}」，手机不堪重负"
            hasRebound && cfg.reboundEnabled && cfg.reboundRatio > 1f ->
                "每次反弹「${currentShortName}」都将踩下油门！\n冲冲冲！" to "每次反弹，速度都会变大，会超速的！"
            hasSpeed && cfg.maxSpeed > 14000f ->
                "「${currentShortName}」掌握了超速的技巧。\n会吊销驾照的！" to "「${currentShortName}」真的能达到那么快吗？"
            hasSpeed && cfg.maxSpeed < 500f ->
                "限速警告！" to "严禁超速！！！！！！！"
            hasGravity && cfg.gravity > 8000f ->
                "被重力狠狠抓住？\n「${currentShortName}」将会狠狠的撞击手机" to "重力过大，每次弹起，都会迅速被拉会地面。"
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
            // 备注按真实屏幕旋转与线一同旋转：每条屏幕边线携带「实际作用到它的物理偏移」名（顶部/底部/左部/右部），
            // 与 rotatedBoundOffsets 同源，保证横屏下“改上偏移→移动的那条线备注就是物理顶部偏移（落在屏幕左/右）”，
            // 不再出现“改上偏移却挪动一条被错标成左部的线”。
            val o = boundGuideOverride
            // Physical offsets [top,bottom,left,right] remain active for IME previews.
            val tV = o?.get(0) ?: boundOffsetTop
            val bV = o?.get(1) ?: boundOffsetBottom
            val lV = o?.get(2) ?: boundOffsetLeft
            val rV = o?.get(3) ?: boundOffsetRight
            val rob = rotatedBoundOffsets(floatArrayOf(tV, bV, lV, rV))
            // 边顺序 [左,右,上,下] 对应的物理名（随 cachedRotation）；值与上面画线 rob 完全一致。
            // 边顺序 [左,右,上,下] 对应的物理名，须与上面修正后的 rotatedBoundOffsets 一致：
            //   ROT_90：屏左=物上、屏右=物下、屏上=物右、屏下=物左
            //   ROT_270：屏左=物下、屏右=物上、屏上=物左、屏下=物右
            val names = when (cachedRotation) {
                Surface.ROTATION_90 -> listOf("顶部偏移", "底部偏移", "右部偏移", "左部偏移")
                Surface.ROTATION_180 -> listOf("右部偏移", "左部偏移", "底部偏移", "顶部偏移")
                Surface.ROTATION_270 -> listOf("底部偏移", "顶部偏移", "左部偏移", "右部偏移")
                else -> listOf("左部偏移", "右部偏移", "顶部偏移", "底部偏移")
            }
            // 左=rob[3]、右=rob[1]、上=rob[0]、下=rob[2]（rotatedBoundOffsets 返回 [屏Top,屏Right,屏Bottom,屏Left]）
            val vals = listOf(rob[3], rob[1], rob[0], rob[2])
            val base = names.zip(vals) { n, v -> "$n ${v.toInt()}" }
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
        // 偏移线/边框已交由独立 guide 窗绘制，置可见后由 syncGuideWindow 挂载窗口（显式尺寸、按需）。
        guideLineView?.visibility = android.view.View.VISIBLE
        syncGuideWindow()
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
    /**
     * 获取浮窗可活动范围（物理屏幕像素坐标，覆盖状态栏/导航栏）。
     * 需求：图片边缘紧贴手机真实显示边缘（最大边缘），忽略状态栏/导航栏/刘海，且坐标不受状态栏显隐影响。
     * 做法：用 maximumWindowMetrics.bounds 取真实物理屏尺寸（官方推荐的 Display.getRealSize 替代），
     * 即完整物理屏（含状态栏/导航栏），原点即物理 (0,0)、不受状态栏弹起影响。
     * 配合各浮窗的 FLAG_LAYOUT_IN_SCREEN，窗口落点原点同样对齐物理 (0,0)，二者坐标 1:1 一致，
     * 故活动范围 = 真实显示尺寸，无需任何 inset 平移。左右无横向系统栏，保持 0..W。
     */
    internal fun getScreenBounds(): android.graphics.Rect {
        // 各浮窗已加 FLAG_LAYOUT_IN_SCREEN + FLAG_LAYOUT_NO_LIMITS：窗口覆盖完整物理屏、原点即物理 (0,0)，
        // 故活动范围 = maximumWindowMetrics.bounds（真实显示尺寸，含状态栏/导航栏），无需 inset 平移或状态栏补偿。
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val b = windowManager.maximumWindowMetrics.bounds
            android.graphics.Rect(b.left, b.top, b.right, b.bottom)
        } else {
            @Suppress("DEPRECATION")
            val dm = resources.displayMetrics
            android.graphics.Rect(0, 0, dm.widthPixels, dm.heightPixels)
        }
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
        val wasKeyboardUp = keyboardUp
        keyboardUp = ime > 0
        applyImeHide(keyboardUp)
        // Rebuild touch windows only on a real keyboard show/hide transition.
        if (wasKeyboardUp != keyboardUp) {
            for (roleId in engines.keys) {
                syncDisplayWindows(roleId)
                syncControlWindows(roleId)
                applyDisplayTouchFlags(roleId)
            }
        }
    }

    /** Update all visible surfaces together. Keyboard hide never overwrites the
     *  persistent visible switch, and restoring the keyboard restores only pets
     *  that are still enabled and visible in their own role configuration.
     *  Window attach/detach is handled by syncDisplayWindows/syncControlWindows.
     */
    /** Apply keyboard canvas behavior to food/freeze; mutters are not canvas objects. */
    private fun syncKeyboardAuxiliary(roleId: String, c: PetConfigData, kbUp: Boolean) {
        val hide = kbUp && c.keyboardMode() == "hide"
        val pass = kbUp && c.keyboardMode() == "pass"
        freezeLayers.filter { it.roleId == roleId }.forEach { layer ->
            layer.iv.visibility = if (hide) View.GONE else View.VISIBLE
            layer.borderView?.visibility = if (hide) View.GONE else View.VISIBLE
            val flags = layer.lp.flags
            layer.lp.flags = if (hide || pass || !c.freezeDraggable)
                flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            else flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            try { windowManager.updateViewLayout(layer.iv, layer.lp) } catch (_: Exception) {}
        }
        fishLayers.filter { it.roleId == roleId }.forEach { layer ->
            layer.iv.visibility = if (hide) View.GONE else View.VISIBLE
            layer.borderView?.visibility = if (hide) View.GONE else View.VISIBLE
            val flags = layer.lp.flags
            layer.lp.flags = if (hide || pass)
                flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            else flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            try { windowManager.updateViewLayout(layer.iv, layer.lp) } catch (_: Exception) {}
        }
    }

    private fun applyImeHide(kbUp: Boolean) {
        for ((roleId, rt) in engines) {
            val c = rt.config.getBlocking()
            val shown = c.visible && c.enabled && !(kbUp && c.keyboardMode() == "hide")
            syncKeyboardAuxiliary(roleId, c, kbUp)
            pets.filter { it.roleId == roleId }.forEachIndexed { index, inst ->
                inst.view.visibility = if (shown) android.view.View.VISIBLE else android.view.View.GONE
                inst.displayView?.visibility =
                    if (shown) android.view.View.VISIBLE else android.view.View.GONE
                val showMutter = c.thinkingEnabled && (index == 0 || !c.singleTrayMsg)
                inst.petMsg.setVisible(c.visible && c.enabled && showMutter)
            }
        }
    }

    // 记录上次实际生效的 IME 高度，避免 applyImeInset 重复且无变化地重算边界
    private var lastAppliedImeInset = -1f

    private fun startLoop() {
        if (engines.isEmpty()) return   // 无任何已召唤角色时不启动空帧循环
        if (running) return
        running = true
        lastFrameTime = SystemClock.uptimeMillis()
        physAccumulator = 0L
        mainHandler.postDelayed(frameRunnable, FRAME_DELAY)
    }

    /** 随机模式周期重置信号：收到后中断当前等待，立即进入下一轮随机。 */
    private val randomResetChannel = Channel<Unit>(Channel.CONFLATED)

    /**
     * 随机模式循环：开启后按配置的[最小,最大]分钟周期，
     * 对开启的随机项生成一组合法随机值并写回 DataStore（UI 自动同步）。
     */
    private fun startRandomLoop(rt: RoleRuntime) {
        rt.randomJob = scope.launch {
            delay(2000L) // 起步稍等，等配置加载
            while (true) {
                val c = rt.config.configFlow.first()
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
                val c2 = rt.config.configFlow.first()
                if (!c2.randomEnabled) continue
                val next = generateRandomConfig(c2)
                rt.config.update { _ -> next }
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

    private fun showMenu(target: PetView) {
        // 长按菜单：以 TYPE_APPLICATION_OVERLAY 浮窗承载（见 PetMenu），
        // 悬浮在其它应用之上，不进入 app task、不抢前台；仅“设置”项才进入 app。
        // target = 触发长按的宠物，菜单动作只作用于该宠物（多数量下不再错误落到主实例 index 0）。
        petMenu?.dismiss()
        val roleId = pets.firstOrNull { it.view === target }?.roleId ?: currentRoleId
        petMenu = PetMenu(this, target, roleId).apply { show() }
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
        val action = intent?.action
        val roleId = intent?.getStringExtra(EXTRA_ROLE_ID)
        val noAutoSummon = intent?.getBooleanExtra(EXTRA_NO_AUTO_SUMMON, false) ?: false
        when {
            action != null -> performAction(action)
            roleId != null -> summonRole(roleId)
            // 「重置软件」重启：只重新初始化服务，不召唤任何角色（重置后屏幕上不该冒出宠物，
            // 由用户在首页卡片自行召唤）。仅【系统重启/START_STICKY 重建】才走下面的默认角色恢复。
            noAutoSummon -> Unit
            // START_STICKY recreation: restore only the roles the player actually summoned.
            else -> restoreSummonedRoles()
        }
        // 退出态下返回 NOT_STICKY，避免 START_STICKY 在进程被回收后重建服务，
        // 造成“非活非死”（通知残留 + 服务反复复活）。
        return if (isExiting) START_NOT_STICKY else START_STICKY
    }

    /** 供浮窗菜单即时写入配置（如点击穿透 / 重力开关），写入即触发 applyConfig 生效 */
    fun updateConfig(block: PetConfigData.() -> PetConfigData) {
        scope.launch { (engines[currentRoleId]?.config ?: config).update(block) }
    }

    /** 供浮窗菜单读取当前配置（初始化开关状态） */
    fun currentConfig(): PetConfigData = config.getBlocking()

    /** 取指定角色的实时配置（按角色独立），缺省回退到当前操作角色配置。供定格/拖拽等按归属角色取设置。 */
    private fun roleConfig(roleId: String?): PetConfigData =
        (roleId?.let { engines[it]?.config } ?: config).getBlocking()

    /** 取指定角色（缺省当前操作角色）的「碎碎念」开关，供各宠物独立判断其碎碎念是否显示。 */
    private fun thinkingOn(roleId: String?): Boolean =
        roleId?.let { engines[it]?.config?.getBlocking()?.thinkingEnabled } ?: thinkingEnabled

    /** 取指定角色（缺省主实例）的碎碎念浮窗，供各宠物独立显示其内容。 */
    private fun thinkingPetMsg(roleId: String?): TrayMsgManager? =
        roleId?.let { rid -> pets.firstOrNull { it.roleId == rid }?.petMsg } ?: trayMsgManager

    /**
     * Compatibility entry point for non-menu actions. The long-press menu now
     * uses returnHome(roleId) and recall(roleId) directly, matching home cards.
     * The old persistent HIDE action has been removed.
     */
    fun performAction(action: String?, target: PetView? = null) {
        when (action) {
            MenuActivity.ACTION_EXIT -> exitApp()
            MenuActivity.ACTION_RECALL -> {
                val role = target?.let { targetView ->
                    pets.firstOrNull { it.view === targetView }?.roleId
                } ?: currentRoleId
                if (role.isNotBlank()) recall(role)
            }
        }
    }

    /** 按包 id 取该角色的 SpritePack（ASSETS 预装 / FILES 上传包均支持）。 */
    fun spritePackForRole(roleId: String): SpritePack =
        descriptorForRole(roleId)?.let { buildSpritePackForRole(this, it) }
            ?: buildSpritePack(this, ConfigDefaults.DEFAULT_SPRITE_PACK)

    /**
     * 播放【本角色 menuPool 中的某个模式】（v1.01 契约：悬浮菜单项由素材包决定）。
     *
     * 与 performAction 的区别：这里直接接受【模式名】而非固定的 ACTION 常量，
     * 菜单项因此完全由素材包驱动（角色差异 = 改 JSON，不动代码）。
     *
     * 所选 Mode 自行控制循环与 nextMode；菜单触发只负责启动交互。
     * 不通过 loop 属性猜测用户想要的功能生命周期。
     */
    fun performMenuMode(roleId: String, mode: String?, target: PetView?) {
        if (mode.isNullOrEmpty()) return
        val tv = target ?: pets.firstOrNull { it.roleId == roleId }?.view ?: return
        // 吸附态下菜单动作一律忽略（探头常驻）
        if (tv.physics.isSnapped) return
        // Menu invocation is an interaction regardless of the selected Mode's
        // loop policy. The Mode itself decides whether/when playback ends.
        tv.playOnce(mode)
    }

    /** 彻底退出：持久化退出意图，释放资源，停止服务并结束整个应用进程。 */
    private fun exitApp() {
        backgroundPrefs.edit().remove("summoned_roles").apply()
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

    override fun onDestroy() {
        super.onDestroy()
        cleanup()
    }

    /** 释放浮窗、监听、传感器等资源；可被 onDestroy 与 exitApp 复用。 */
    /**
     * 多角色引擎入口（对应之前在 onCreate 直接初始化的逻辑）：
     * 为指定角色按 pet_config_<id>/pet_bounds_<id> 构造配置，建宠物窗与物理循环并启动。
     */
    /** 首次召唤和归位共用最终物理边界的底部中心。 */
    private fun placeAtHome(inst: PetInstance) {
        val p = inst.view.physics
        p.resetTo((p.minX + p.maxX) / 2f, p.maxY)
    }

    /** 同步控制窗和显示窗；尚未挂载时仅准备坐标与尺寸。 */
    private fun alignPetWindows(inst: PetInstance) {
        val view = inst.view
        placeControlWindow(view, inst.layoutParams, inst.lastW, inst.lastH)
        if (inst.controlAdded) {
            try { windowManager.updateViewLayout(view, inst.layoutParams) } catch (_: Exception) {}
        }
        val disp = inst.displayView
        val dlp = inst.displayLp
        if (disp != null && dlp != null) {
            val (w, h) = view.getBaseBitmapSize()
            dlp.x = view.physics.x.toInt()
            dlp.y = view.physics.y.toInt()
            dlp.width = w
            dlp.height = h
            disp.displayLeft = view.physics.x
            disp.displayTop = view.physics.y
            if (inst.displayAdded) {
                try { windowManager.updateViewLayout(disp, dlp) } catch (_: Exception) {}
            }
        }
    }

    /** 配置、素材、缩放及边界就绪后定位，取消极大 Y 的钳制绕行。 */
    private fun positionNewlySummonedRole(roleId: String) {
        val random = PersonalizationPrefs.summonPosition(this) == PersonalizationPrefs.RANDOM
        pets.filter { it.roleId == roleId }.forEach { inst ->
            val p = inst.view.physics
            if (random) {
                val minX = p.minX
                val maxX = p.maxX.coerceAtLeast(minX)
                val minY = p.minY
                val maxY = p.maxY.coerceAtLeast(minY)
                val x = if (maxX > minX) minX + Random.nextFloat() * (maxX - minX) else minX
                val y = if (maxY > minY) minY + Random.nextFloat() * (maxY - minY) else minY
                p.resetTo(x, y)
            } else {
                placeAtHome(inst)
            }
            alignPetWindows(inst)
        }
    }

    private fun startRole(roleId: String) {
        // External intents and service restoration must obey the same minimum
        // requirement as the home-screen summon button; never erase the instance.
        if (!MaterialFunctionSupport.hasPlayable(this, roleId, MaterialFunctionSupport.NORMAL)) return
        val descriptor = RoleStore.descriptorForInstance(this, roleId) ?: RoleRegistry.byId(roleId) ?: return
        val rt = engines.getOrPut(roleId) {
            RoleRuntime(roleId, PetConfig.forRole(this, roleId), PetBounds.forRole(this, roleId))
        }
        rt.descriptor = descriptor
        // Summoning always shows the character. Clear any old persistent
        // visible=false before windows are created, rather than migrating an
        // obsolete hide/show setting or leaving an invisible active runtime.
        if (!rt.config.getBlocking().visible) {
            kotlinx.coroutines.runBlocking {
                rt.config.update { it.copy(visible = true) }
            }
        }
        config = rt.config
        currentRoleId = roleId
        currentShortName = RoleStore.resolveShortName(this, roleId)
        pendingInitialPlacement.add(roleId)
        initPetView(roleId)
        loadConfig(roleId)
        applyNightMode(config.getBlocking())
        registerConfigObserver(rt)
        lastVisRect = getScreenBounds()
        // Initialize the final animation/anchor snapshot before calculating
        // physical bounds. Only newly summoned pets take the outside-bottom
        // placement path; explicit Return Home remains unchanged.
        if (PersonalizationPrefs.summonPosition(this) != PersonalizationPrefs.RANDOM) {
            pets.filter { it.roleId == roleId }.forEach { it.view.forceReset() }
        }
        recalcBounds()
        positionNewlySummonedRole(roleId)
        pendingInitialPlacement.remove(roleId)
        syncDisplayWindows(roleId)
        syncControlWindows(roleId)
        applyDisplayTouchFlags(roleId)
        startLoop()
        startRandomLoop(rt)
        saveSummonedRoles()
        // 通知首页：该角色已进入在屏状态（覆盖“服务异步启动”场景，避免卡片不刷新）。
        onActiveRolesChanged?.invoke()
        // 新角色召唤后，重新置顶辅助浮层（宠物窗叠放保持召唤顺序、不再重排）。
        reapplyZOrder()
    }

    private fun teardownRole(roleId: String) {
        val rt = engines[roleId]
        // Cleanup is idempotent: even an already-detached runtime may leave auxiliary
        // windows behind. Never remove another role's fish, freezes or message window.
        if (rt != null) {
            rt.configJob?.cancel(); rt.configJob = null
            rt.randomJob?.cancel(); rt.randomJob = null
            stopFishSpawning(rt)
        }
        else clearAllFish(roleId)
        clearAllFreeze(roleId)
        modeAssetManagers.remove(roleId)
        pendingInitialPlacement.remove(roleId)

        val removed = pets.filter { it.roleId == roleId }
        for (inst in removed) {
            inst.displayView?.let { display ->
                inst.displayLp?.let { removeDisplay(display, it) }
            }
            try {
                if (inst.view.isAttachedToWindow) windowManager.removeViewImmediate(inst.view)
            } catch (e: Exception) {
                android.util.Log.w("PetService", "召回控制窗失败：$roleId", e)
            }
            try { inst.petMsg.destroy() } catch (e: Exception) {
                android.util.Log.w("PetService", "召回碎碎念失败：$roleId", e)
            }
            imePetOffsets.remove(inst.view)
            forageFoodByView.remove(inst.view)
        }
        pets.removeAll(removed)
        engines.remove(roleId)
        imeShiftY.remove(roleId)
        imeLastMode.remove(roleId)
        imeLastTop.remove(roleId)

        // Service-level aliases must refer only to surviving roles. In particular,
        // don't leave the mutter manager pointing at a destroyed role.
        val survivor = pets.firstOrNull()
        if (survivor != null) {
            petView = survivor.view
            layoutParams = survivor.layoutParams
            trayMsgManager = survivor.petMsg
            val nextId = if (currentRoleId == roleId || currentRoleId !in engines)
                survivor.roleId else currentRoleId
            syncRole(nextId)
        } else {
            trayMsgManager = null
            currentRoleId = ""
            currentShortName = "角色"
        }

        if (engines.isEmpty()) {
            running = false
            mainHandler.removeCallbacks(frameRunnable)
            try { sensorManager?.unregisterListener(tiltListener) } catch (_: Exception) {}
            // These are service-level debug windows, not owned by a pet instance.
            // With no role remaining, they must not linger on the desktop.
            releaseGuide(DEBUG_BOUND_OWNER)
            releaseGuide(DEBUG_SNAP_OWNER)
            debugView?.visibility = View.GONE
            if (guideAdded) {
                guideLineView?.visibility = View.GONE
                syncGuideWindow()
            }
        }
        saveSummonedRoles()
        onActiveRolesChanged?.invoke()
    }

    /** 一次性创建调试浮层与四边吸附辅助线（不在 initPetView 内，避免随角色切换重复 addView）。 */
    private fun setupDebugWindows() {
        debugView = android.widget.TextView(this).apply {
            setTextColor(0xFFFFFF00.toInt())
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setBackgroundColor(0x80000000.toInt())
            setPadding(8, 4, 8, 4)
            visibility = android.view.View.GONE
        }
        debugParams = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            gravity = Gravity.BOTTOM or Gravity.START
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
        }
        windowManager.addView(debugView, debugParams)

        guideLineView = GuideLineView(this).apply {
            visibility = android.view.View.GONE
            isClickable = false
            isFocusable = false
            isFocusableInTouchMode = false
        }
        // guideLineView 由 syncGuideWindow() 按需挂载（含 guideAdded 标志，避免重复 addView）
    }

    /** 当前操作中角色的阈值配置（pet_bounds_<id>）。 */
    private fun currentBounds(): PetBounds =
        engines[currentRoleId]?.bounds ?: PetBounds.forRole(this, currentRoleId)

    /** 把“当前操作角色”切换为指定角色（手势/菜单交互前调用，使 currentConfig/currentBounds 指向正确角色）。 */
    private fun syncRole(roleId: String) {
        engines[roleId]?.let { config = it.config; currentRoleId = roleId }
        currentShortName = RoleStore.resolveShortName(this, roleId)
    }

    // —— 多角色公开 API（供 MainActivity 角色卡片驱动） ——

    /** Apply a saved material pack to only the active instances using that template. */
    fun refreshMaterialPack(templateId: String) {
        val affected = RoleStore.getHomeRoles(this)
            .filter { it.templateId == templateId && engines.containsKey(it.instanceId) }
            .map { it.instanceId }
        if (affected.isEmpty()) return
        affected.forEach { teardownRole(it) }
        affected.forEach { startRole(it) }
        reapplyZOrder()
    }

    /** 角色是否已在屏（已召唤）。 */
    fun isRoleActive(roleId: String): Boolean = engines.containsKey(roleId)
    fun activeRoleCount(): Int = engines.size

    /**
     * 召唤指定角色（多角色并存，互不干扰）：
     * - 若角色已在屏 → 归位（纯 reposition，窗仍在）。
     * - 若角色未在屏 → 新建一份独立运行态并召唤（已召唤的其他角色不受影响）。
     * 同时清除“已退出”标记，确保退出后仍能重新召唤。
     */
    fun summonRole(roleId: String) {
        clearExiting()
        if (engines.containsKey(roleId)) { returnHome(roleId); return }
        startRole(roleId)
    }

    /** 归位：回到屏幕底部中心、静止（窗仍在，无状态变化）。卡片“召唤/归位”处于已召唤态时触发。
     *  纯归位，不翻转角色 visible 设置（与菜单 ACTION_RECALL 不同）。 */
    fun returnHome(roleId: String) {
        val roleInstances = pets.filter { it.roleId == roleId }
        for (inst in roleInstances) inst.view.forceReset()
        recalcBounds()
        for (inst in roleInstances) {
            placeAtHome(inst)
            alignPetWindows(inst)
        }
    }

    /** 召回（退场）：撤该角色所有窗、停其循环，不影响其他在屏角色。不卸载、不丢配置/描述符。 */
    fun recall(roleId: String) {
        teardownRole(roleId)
        // 角色移除后，重新置顶辅助浮层（宠物窗叠放保持召唤顺序、不再重排）。
        reapplyZOrder()
    }

    /**
     * 宠物窗堆叠顺序固定为「召唤/创建顺序」（先加显示窗、后加控制窗 → 控制层恒在该宠物显示层之上），
     * 不再按 home_roles 做 remove/add 重排：既避免重排的 remove/add 开销与潜在闪烁，
     * 也从根本上杜绝「显示窗被翻到控制窗之上」导致强制不透明下触摸失效的整类问题。
     * 角色管理页里自定义的置顶顺序不再影响屏幕叠放（按需求回到默认召唤顺序）。
     *
     * 本函数现在只负责：在召唤/召回/改数量后，把各类辅助浮层（调试文字/辅助线/定格/投食/碎碎念）
     * 重新置顶，避免被新创建的宠物窗盖住。宠物窗本身不再重排。
     */
    fun reapplyZOrder() {
        raiseAuxiliaryWindows()
    }

    /** 重排宠物窗后，把所有辅助浮层重新置顶，避免被宠物窗盖住（调试文字/辅助线/定格/投食/碎碎念）。 */
    private fun raiseAuxiliaryWindows() {
        // 定格层
        for (layer in freezeLayers) {
            try { windowManager.removeView(layer.iv) } catch (_: Exception) {}
            try { windowManager.addView(layer.iv, layer.lp) } catch (_: Exception) {}
        }
        // 投食层
        for (layer in fishLayers) {
            try { windowManager.removeView(layer.iv) } catch (_: Exception) {}
            try { windowManager.addView(layer.iv, layer.lp) } catch (_: Exception) {}
        }
        // 辅助线（仅已挂载时）
        if (guideAdded) {
            guideLineView?.let { g ->
                guideParams?.let { lp ->
                    try { windowManager.removeView(g) } catch (_: Exception) {}
                    try { windowManager.addView(g, lp) } catch (_: Exception) {}
                }
            }
        }
        // 调试文字
        debugView?.let { d ->
            debugParams?.let { lp ->
                try { windowManager.removeView(d) } catch (_: Exception) {}
                try { windowManager.addView(d, lp) } catch (_: Exception) {}
            }
        }
        // 碎碎念（每个实例各自一个窗，全部置于宠物之上）
        for (p in pets) {
            try { p.petMsg.bringToFront() } catch (_: Exception) {}
        }
    }

    /** 重命名后刷新简称缓存，避免辅助线/红框吐槽等文案滞后。
     * 默认刷新「当前操作角色」，也可传入具体角色 id（如从设置页改名当前实例）。 */
    fun refreshShortName(roleId: String = currentRoleId) {
        currentShortName = RoleStore.resolveShortName(this, roleId)
    }

    private fun cleanup() {
        // 兜底移除前台通知，避免退出后通知栏常驻造成“非活非死”观感。
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        running = false
        for (rt in engines.values) { rt.configJob?.cancel(); rt.randomJob?.cancel() }
        engines.clear()
        mainHandler.removeCallbacks(frameRunnable)
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        try { petMenu?.dismiss() } catch (_: Exception) {}
        // 注销体感重力传感器监听，避免泄漏
        try { sensorManager?.unregisterListener(tiltListener) } catch (_: Exception) {}
        // 注销屏幕旋转监听（DisplayManager），避免泄漏
        try {
            val dm = getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            if (displayListener != null) dm?.unregisterDisplayListener(displayListener!!)
        } catch (_: Exception) {}
        displayListener = null
        // 移除所有宠物实例：控制窗 + 各自显示窗 + 碎碎念浮窗
        for (inst in pets) {
            if (inst.displayAdded) inst.displayView?.let { d -> inst.displayLp?.let { removeDisplay(d, it) } }
            try { windowManager.removeView(inst.view) } catch (_: Exception) {}
            try { inst.petMsg.destroy() } catch (_: Exception) {}
        }
        pets.clear()
        // 移除独立 guide 辅助线窗（若有）
        try { if (guideAdded && guideLineView != null) windowManager.removeView(guideLineView) } catch (_: Exception) {}
        guideAdded = false
        try { if (debugView != null) windowManager.removeView(debugView) } catch (_: Exception) {}
        guideLineView = null
        // 停止所有角色的投食循环（按角色独立持有的 runnable），并清除全部支鱼（回收 Bitmap，防泄漏）
        for (rt in engines.values) {
            rt.fishSpawnRunnable?.let { mainHandler.removeCallbacks(it) }
            rt.fishSpawnRunnable = null
            rt.fishSpawning = false
        }
        clearAllFish()
        
        modeAssetManagers.clear()
        // 清除所有定格显示层（回收 Bitmap，防泄漏）
        clearAllFreeze()
        trayMsgManager = null
        instance = null
        try { scope.cancel() } catch (_: Exception) {}
    }

    companion object {
        // 供设置页（同进程）直接驱动辅助线浮层，避免引入广播样板。
        var instance: PetService? = null
        /** 角色在屏状态（召唤/召回）变化回调：首页 MainActivity 注册以即时刷新卡片。
         *  用 companion 级静态回调，使服务尚未创建（instance 为 null）时的异步召唤也能通知到已打开的页面。 */
        @JvmStatic var onActiveRolesChanged: (() -> Unit)? = null
        const val CHANNEL_ID = "cakepet_channel"
        const val NOTIFICATION_ID = 1001
        const val EXTRA_ROLE_ID = "role_id"
        // 「重置软件」重启服务的标记：仅用于重新初始化服务（清内存态、重读已清空的角色数据），
        // 明确【不要】自动召唤任何角色。缺省 false，故系统重启/START_STICKY 重建不受影响。
        const val EXTRA_NO_AUTO_SUMMON = "no_auto_summon"
        // 吸附阈值兜底值：与 PetConfigData.snapThreshold 默认值保持一致，
        // 仅在 petView 尚未初始化（服务刚启动）时用于绘制辅助线。
        private const val DEFAULT_SNAP_DIST = 100f
    }
}
