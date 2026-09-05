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
        root!!.addView(menuView)
        root!!.addView(actionsView)

        // 底部全宽、纵向按需：贴底显示，y 为距底边偏移（0 贴底）
        val lp = params
        val vis = service.getScreenBounds()
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
        }
    }

    private fun buildMenu(ctx: Context): View {
        val v = LayoutInflater.from(ctx).inflate(R.layout.sheet_menu, null)
        // 两个开关：点击穿透 / 重力·抛掷（与设置页同数据源，即时生效）
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
            service.performAction(MenuActivity.ACTION_PAT); dismiss()
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
            service.performAction(MenuActivity.ACTION_RECALL); dismiss()
        }
        v.findViewById<View>(R.id.menu_hide).setOnClickListener {
            service.performAction(MenuActivity.ACTION_HIDE); dismiss()
        }
        v.findViewById<View>(R.id.menu_exit).setOnClickListener {
            dismiss(); service.performAction(MenuActivity.ACTION_EXIT)
        }
        return v
    }

    private fun buildActions(ctx: Context): View {
        val v = LayoutInflater.from(ctx).inflate(R.layout.sheet_actions, null)
        fun bind(id: Int, action: String) {
            v.findViewById<View>(id).setOnClickListener {
                service.performAction(action); dismiss()
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
}
