package com.cx.cakepet

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * 长按宠物弹出的菜单：以 TYPE_APPLICATION_OVERLAY 浮窗承载，
 * 悬浮在其它应用之上（不进入 app task、不抢前台），仅“设置”项才进入 app。
 * 圆角背景见 R.drawable.menu_bg。
 */
class PetMenu(
    private val service: PetService,
    private val petView: PetView
) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private var root: FrameLayout? = null
    private var menuView: View? = null
    private var actionsView: View? = null
    private var interactView: View? = null
    private var freezeMoreView: View? = null
    private var feedMoreView: View? = null
    private var exitConfirmView: View? = null

    private val params: WindowManager.LayoutParams
        get() = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            format = PixelFormat.TRANSLUCENT
            // 可点击但点击外部穿透到下层，并监听 outside 关闭
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            gravity = Gravity.BOTTOM or Gravity.START
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
        }

    // 长按菜单是 TYPE_APPLICATION_OVERLAY 浮窗，使用 Service 上下文 inflate；而 AppCompat 的
    // setDefaultNightMode 只“伪装”Activity 自身的 resources，不会同步到 Service 的全局
    // resources，因此必须手动用覆盖 Configuration 的方式把 night 状态（系统夜间 + 用户强制开关）
    // 应用到 inflate 用的 Context，否则浮窗不会跟随夜间模式（尤其是“强制”开关）。
    private fun nightAwareContext(): Context {
        val cfg = service.currentConfig()
        val base = service
        val systemNight = (base.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val night = when {
            cfg.forceNightOn -> true
            cfg.forceNightOff -> false
            else -> systemNight
        }
        val cw = ContextThemeWrapper(base, R.style.Theme_CakePet)
        val config = Configuration(base.resources.configuration)
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        cw.applyOverrideConfiguration(config)
        return cw
    }

    fun show() {
        if (root != null) return
        val ctx = nightAwareContext()
        // 长按打开浮窗时记录当前宠物的显示信息（位置/当前自动帧/脚朝向）：定格用这份快照，
        // 而非再次让用户选择；之后活体继续动也不影响已定格图层。
        val snap = petView.captureFreeze()
        root = FrameLayout(ctx).apply {
            // 顶部圆角卡片背景 + 点击外部关闭
            setBackgroundResource(R.drawable.menu_bg)
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE) {
                    dismiss()
                    true
                } else false
            }
        }
        menuView = buildMenu(ctx)
        actionsView = buildActions(ctx).also { it.visibility = View.GONE }
        interactView = buildInteract(ctx, snap).also { it.visibility = View.GONE }
        freezeMoreView = buildFreezeMore(ctx, snap).also { it.visibility = View.GONE }
        feedMoreView = buildFeedMore(ctx).also { it.visibility = View.GONE }
        exitConfirmView = buildExitConfirm(ctx).also { it.visibility = View.GONE }
        root!!.addView(menuView)
        root!!.addView(actionsView)
        root!!.addView(interactView)
        root!!.addView(freezeMoreView)
        root!!.addView(feedMoreView)
        root!!.addView(exitConfirmView)

        // 底部全宽、纵向按需：贴底显示，y 为距底边偏移（0 贴底）
        val lp = params
        lp.x = 0
        lp.y = 0
        // 横向全屏：宽度取屏幕宽
        wm.addView(root, lp)
    }

    fun dismiss() {
        if (root != null) {
            try { wm.removeView(root) } catch (_: Exception) {}
            root = null
            menuView = null
            actionsView = null
            interactView = null
            freezeMoreView = null
            feedMoreView = null
            exitConfirmView = null
        }
    }

    private fun buildMenu(ctx: Context): View {
        val v = LayoutInflater.from(ctx).inflate(R.layout.sheet_menu, null)
        // 三个开关：点击穿透 / 重力·抛掷 / 体感重力（与设置页同数据源，即时生效）
        val cfg = service.currentConfig()
        val swCt = v.findViewById<android.widget.Switch>(R.id.sw_clickthrough)
        val swG = v.findViewById<android.widget.Switch>(R.id.sw_gravity)
        val swT = v.findViewById<android.widget.Switch>(R.id.sw_tilt)
        swCt.isChecked = cfg.clickThrough
        swG.isChecked = cfg.gravityEnabled
        swT.isChecked = cfg.tiltGravity
        v.findViewById<View>(R.id.menu_switch_clickthrough).setOnClickListener {
            val next = !swCt.isChecked
            swCt.isChecked = next
            service.updateConfig { copy(clickThrough = next) }
        }
        v.findViewById<View>(R.id.menu_switch_gravity).setOnClickListener {
            val next = !swG.isChecked
            swG.isChecked = next
            service.updateConfig { copy(gravityEnabled = next) }
        }
        v.findViewById<View>(R.id.menu_switch_tilt).setOnClickListener {
            val next = !swT.isChecked
            swT.isChecked = next
            // 体感重力只切换 tiltGravity 标志，不改动四边定向重力配置（见 PetPhysics/PetService）
            service.updateConfig { copy(tiltGravity = next) }
        }
        v.findViewById<View>(R.id.menu_pat).setOnClickListener {
            service.performAction(MenuActivity.ACTION_PAT, petView); dismiss()
        }
        // 交互：打开交互子页面（定格-咔嚓 / 清除所有定格）
        v.findViewById<View>(R.id.menu_interact).setOnClickListener {
            menuView?.visibility = View.GONE
            interactView?.visibility = View.VISIBLE
        }
        v.findViewById<View>(R.id.menu_actions).setOnClickListener {
            menuView?.visibility = View.GONE
            actionsView?.visibility = View.VISIBLE
        }
        v.findViewById<View>(R.id.menu_settings).setOnClickListener {
            dismiss()
            val intent = Intent(service, SettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            service.startActivity(intent)
        }
        v.findViewById<View>(R.id.menu_recall).setOnClickListener {
            service.performAction(MenuActivity.ACTION_RECALL, petView); dismiss()
        }
        v.findViewById<View>(R.id.menu_hide).setOnClickListener {
            service.performAction(MenuActivity.ACTION_HIDE, petView); dismiss()
        }
        v.findViewById<View>(R.id.menu_exit).setOnClickListener {
            // 不再直接退出：进入退出确认子页，由用户二次确认
            menuView?.visibility = View.GONE
            exitConfirmView?.visibility = View.VISIBLE
        }
        return v
    }

    private fun buildActions(ctx: Context): View {
        val v = LayoutInflater.from(ctx).inflate(R.layout.sheet_actions, null)
        fun bind(id: Int, action: String) {
            v.findViewById<View>(id).setOnClickListener {
                service.performAction(action, petView); dismiss()
            }
        }
        bind(R.id.act_sit_clam, MenuActivity.ACTION_SIT_CLAM)
        bind(R.id.act_shake_head, MenuActivity.ACTION_SHAKE_HEAD)
        bind(R.id.act_walk, MenuActivity.ACTION_WALK)
        bind(R.id.act_wriggle, MenuActivity.ACTION_WRIGGLE)
        bind(R.id.act_lie, MenuActivity.ACTION_LIE)
        bind(R.id.act_probe_head, MenuActivity.ACTION_PROBE_HEAD)
        bind(R.id.act_pull_fish, MenuActivity.ACTION_PULL_FISH)
        bind(R.id.act_white, MenuActivity.ACTION_WHITE)
        bind(R.id.act_puffed, MenuActivity.ACTION_PUFFED)
        v.findViewById<View>(R.id.act_back).setOnClickListener {
            actionsView?.visibility = View.GONE
            menuView?.visibility = View.VISIBLE
        }
        return v
    }

    /**
     * 交互子页面：定格-咔嚓（用长按时记录的快照定格当前宠物并退出浮窗）、
     * 清除所有定格（移除所有定格显示层并退出浮窗）。
     */
    private fun buildInteract(ctx: Context, snap: PetView.FreezeSnapshot?): View {
        val v = LayoutInflater.from(ctx).inflate(R.layout.sheet_interact, null)
        // 定格-交互：控制全部定格显示层是否可点击交互（默认关=纯显示不可点击），即时生效
        val swFd = v.findViewById<android.widget.Switch>(R.id.sw_freezeinteract)
        swFd.isChecked = service.currentConfig().freezeDraggable
        v.findViewById<View>(R.id.menu_switch_freezeinteract).setOnClickListener {
            val next = !swFd.isChecked
            swFd.isChecked = next
            service.updateConfig { copy(freezeDraggable = next) }
        }
        v.findViewById<View>(R.id.act_back).setOnClickListener {
            interactView?.visibility = View.GONE
            menuView?.visibility = View.VISIBLE
        }
        // 定格-咔嚓：用长按时记录的快照定格当前宠物并退出浮窗（互动页首行，其余后延）
        v.findViewById<View>(R.id.freeze_snap).setOnClickListener {
            snap?.let { s -> service.addFreezeLayer(s) }
            dismiss()
        }
        // 定格-更多：进入定格-更多子页（咔嚓/归位/清空/交互开关/设置）
        v.findViewById<View>(R.id.freeze_more).setOnClickListener {
            interactView?.visibility = View.GONE
            freezeMoreView?.visibility = View.VISIBLE
        }
        // 投食：立即刷出一条支鱼并重置随机刷出计时，随即关闭浮窗（与分身操作一致：避免浮窗遮挡）。
        v.findViewById<View>(R.id.feed_now).setOnClickListener {
            service.feedNow()
            dismiss()
        }
        // 重置大小：把宠物大小恢复为用户默认值（所有分身共享、即时生效），随即关闭浮窗
        v.findViewById<View>(R.id.reset_size).setOnClickListener {
            service.resetPetScaleToDefault()
            dismiss()
        }
        // 投食-刷新：开关投食随机掉落（与食物浮窗同逻辑）
        val swFr = v.findViewById<android.widget.Switch>(R.id.sw_feedrefresh)
        swFr.isChecked = service.currentConfig().feedingEnabled
        v.findViewById<View>(R.id.menu_switch_feedrefresh).setOnClickListener {
            val next = !swFr.isChecked
            swFr.isChecked = next
            service.updateConfig { copy(feedingEnabled = next) }
        }
        // 投食-更多：进入投食-更多子页（投食/重置大小/归位/清空/刷新开关/设置）
        v.findViewById<View>(R.id.feed_more).setOnClickListener {
            interactView?.visibility = View.GONE
            feedMoreView?.visibility = View.VISIBLE
        }
        // 分身 归零 / -1 / +1：修改酥的数量（petCount），逻辑与关于页一致，范围 1..20；
        // 到达上下界时不再变化，并用系统 Toast 提示（「酥」）。
        // 注：点击后立即关闭浮窗，否则 Toast 会被浮窗遮住看不到。
        val PET_COUNT_MIN = 1
        val PET_COUNT_MAX = 20
        fun toast(msg: String) =
            android.widget.Toast.makeText(service, msg, android.widget.Toast.LENGTH_SHORT).show()
        v.findViewById<View>(R.id.pet_zero).setOnClickListener {
            service.updateConfig { copy(petCount = PET_COUNT_MIN) }
            dismiss()
            toast("「酥」已归零")
        }
        v.findViewById<View>(R.id.pet_minus).setOnClickListener {
            val cur = service.currentConfig().petCount
            if (cur <= PET_COUNT_MIN) { dismiss(); toast("「酥」不能再少了"); return@setOnClickListener }
            service.updateConfig { copy(petCount = cur - 1) }
            dismiss()
            toast("「酥」数量：" + (cur - 1))
        }
        v.findViewById<View>(R.id.pet_plus).setOnClickListener {
            val cur = service.currentConfig().petCount
            if (cur >= PET_COUNT_MAX) { dismiss(); toast("「酥」不能再多了"); return@setOnClickListener }
            service.updateConfig { copy(petCount = cur + 1) }
            dismiss()
            toast("「酥」数量：" + (cur + 1))
        }
        return v
    }

    /**
     * 定格-更多子页面：定格-咔嚓（用长按快照定格并退出）、定格-归位（全部定格重置到屏幕中心）、
     * 定格-清空（清除所有定格）、定格-交互开关、设置（打开主设置）。
     */
    private fun buildFreezeMore(ctx: Context, snap: PetView.FreezeSnapshot?): View {
        val v = LayoutInflater.from(ctx).inflate(R.layout.sheet_freeze_more, null)
        val swFd = v.findViewById<android.widget.Switch>(R.id.sw_freezeinteract)
        swFd.isChecked = service.currentConfig().freezeDraggable
        v.findViewById<View>(R.id.menu_switch_freezeinteract).setOnClickListener {
            val next = !swFd.isChecked
            swFd.isChecked = next
            service.updateConfig { copy(freezeDraggable = next) }
        }
        v.findViewById<View>(R.id.fm_back).setOnClickListener {
            freezeMoreView?.visibility = View.GONE
            interactView?.visibility = View.VISIBLE
        }
        v.findViewById<View>(R.id.freeze_snap).setOnClickListener {
            snap?.let { s -> service.addFreezeLayer(s) }
            dismiss()
        }
        v.findViewById<View>(R.id.freeze_reset).setOnClickListener {
            service.resetAllFreezeToCenter()
            dismiss()
        }
        v.findViewById<View>(R.id.freeze_clear).setOnClickListener {
            service.clearAllFreeze()
            dismiss()
        }
        v.findViewById<View>(R.id.fm_settings).setOnClickListener {
            dismiss()
            val intent = Intent(service, SettingsActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            service.startActivity(intent)
        }
        return v
    }

    /**
     * 投食-更多子页面：投食（立即刷出一条支鱼）、重置大小、投食-归位（全部鱼重置到屏幕中心）、
     * 投食-清空（清除所有鱼）、投食-刷新开关（投食随机掉落）、投食-设置（打开投食设置页）。
     */
    private fun buildFeedMore(ctx: Context): View {
        val v = LayoutInflater.from(ctx).inflate(R.layout.sheet_feed_more, null)
        val swFr = v.findViewById<android.widget.Switch>(R.id.sw_feedrefresh)
        swFr.isChecked = service.currentConfig().feedingEnabled
        v.findViewById<View>(R.id.menu_switch_feedrefresh).setOnClickListener {
            val next = !swFr.isChecked
            swFr.isChecked = next
            service.updateConfig { copy(feedingEnabled = next) }
        }
        v.findViewById<View>(R.id.fdm_back).setOnClickListener {
            feedMoreView?.visibility = View.GONE
            interactView?.visibility = View.VISIBLE
        }
        v.findViewById<View>(R.id.feed_now).setOnClickListener {
            service.feedNow()
            dismiss()
        }
        v.findViewById<View>(R.id.reset_size).setOnClickListener {
            service.resetPetScaleToDefault()
            dismiss()
        }
        v.findViewById<View>(R.id.feed_reset).setOnClickListener {
            service.resetAllFishToCenter()
            dismiss()
        }
        v.findViewById<View>(R.id.feed_clear).setOnClickListener {
            service.clearAllFish()
            dismiss()
        }
        v.findViewById<View>(R.id.fdm_settings).setOnClickListener {
            dismiss()
            val intent = Intent(service, FeedingSettingsActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            service.startActivity(intent)
        }
        return v
    }

    /**
     * 退出确认子页面：返回/取消回到主菜单；点“退出”才真正执行退出动作。
     */
    private fun buildExitConfirm(ctx: Context): View {
        val v = LayoutInflater.from(ctx).inflate(R.layout.sheet_exit_confirm, null)
        // 返回 / 取消：回到主菜单
        val backToMenu: () -> Unit = {
            exitConfirmView?.visibility = View.GONE
            menuView?.visibility = View.VISIBLE
        }
        v.findViewById<View>(R.id.exit_back).setOnClickListener { backToMenu() }
        v.findViewById<View>(R.id.exit_cancel).setOnClickListener { backToMenu() }
        v.findViewById<View>(R.id.exit_confirm).setOnClickListener {
            // 真正退出：关闭浮窗并执行退出动作
            dismiss()
            service.performAction(MenuActivity.ACTION_EXIT, petView)
        }
        return v
    }
}
