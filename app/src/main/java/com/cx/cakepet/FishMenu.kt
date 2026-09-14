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
import android.widget.TextView

/**
 * 长按支鱼浮窗弹出的菜单（样式与 FreezeMenu / PetMenu 一致）：TYPE_APPLICATION_OVERLAY 浮窗。
 * 行1=标题“投喂”；行2=清除（本鱼）/清除全部；行3=投喂设置（跳转 FeedingSettingsActivity）。
 * 拖动给酥时鱼会直接消失（纯清除，不弹菜单），长按才弹此菜单。
 */
class FishMenu(
    private val service: PetService,
    private val layer: PetService.FishLayer
) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private var root: FrameLayout? = null

    private val params: WindowManager.LayoutParams
        get() = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            gravity = Gravity.BOTTOM or Gravity.START
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
        }

    // 与 FreezeMenu 同：手动把夜间模式（系统 + 用户强制）应用到 inflate 用的 Context，
    // 否则 Service 上下文 inflate 的浮窗不跟随夜间模式。
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
            setBackgroundResource(R.drawable.menu_bg)
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE) {
                    dismiss()
                    true
                } else false
            }
        }
        val menuView = buildMenu(ctx)
        root!!.addView(menuView)
        val lp = params
        lp.x = 0
        lp.y = 0
        wm.addView(root, lp)
    }

    fun dismiss() {
        if (root != null) {
            try { wm.removeView(root) } catch (_: Exception) {}
            root = null
        }
    }

    private fun buildMenu(ctx: Context): View {
        val v = LayoutInflater.from(ctx).inflate(R.layout.sheet_fish, null)
        // 顶部：打开浮窗时宠物实时大小，格式「1.「酥」的大小 1.40x」
        val scale = service.currentConfig().scale
        v.findViewById<TextView>(R.id.fish_scale).text =
            "「酥」的大小 %.2fx".format(scale)
        // 投食-清除：仅本鱼，然后退出浮窗
        v.findViewById<View>(R.id.fish_clear).setOnClickListener {
            service.removeFish(layer); dismiss()
        }
        // 投食-清空：清除全部鱼，然后退出浮窗
        v.findViewById<View>(R.id.fish_clear_all).setOnClickListener {
            service.clearAllFish(); dismiss()
        }
        // 重置大小：把宠物大小恢复为用户默认值（所有分身共享、即时生效），退出浮窗
        v.findViewById<View>(R.id.fish_reset_size).setOnClickListener {
            service.resetPetScaleToDefault(); dismiss()
        }
        // 投食-刷新：投食随机掉落开关（即时生效）
        val swFr = v.findViewById<android.widget.Switch>(R.id.sw_feedrefresh)
        swFr.isChecked = service.currentConfig().feedingEnabled
        v.findViewById<View>(R.id.menu_switch_feedrefresh).setOnClickListener {
            val next = !swFr.isChecked
            swFr.isChecked = next
            service.updateConfig { copy(feedingEnabled = next) }
        }
        // 投食-设置：跳转投食设置页
        v.findViewById<View>(R.id.fish_settings).setOnClickListener {
            dismiss()
            val intent = Intent(service, FeedingSettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            service.startActivity(intent)
        }
        return v
    }
}
