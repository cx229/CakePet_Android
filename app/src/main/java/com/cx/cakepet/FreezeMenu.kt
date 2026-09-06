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
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 长按定格显示层弹出的菜单（样式与长按宠物 PetMenu 一致）：TYPE_APPLICATION_OVERLAY 浮窗，
 * 仅在“定格-交互”开启（定格层可点击）时由 FreezeLayer 长按触发。
 * 行1=定格时刻系统时间；行2=定格-清除（本层）；行3=定格-清除所有；行4=定格-交互 开关。
 */
class FreezeMenu(
    private val service: PetService,
    private val layer: PetService.FreezeLayer
) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private var root: FrameLayout? = null
    private var menuView: View? = null
    private var noteEdit: EditText? = null

    private val params: WindowManager.LayoutParams
        get() = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            format = PixelFormat.TRANSLUCENT
            // 去掉 NOT_FOCUSABLE：备注输入框需要可获取焦点、弹出软键盘并编辑
            flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
            gravity = Gravity.BOTTOM or Gravity.START
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
        }

    // 与 PetMenu 同：手动把夜间模式（系统 + 用户强制）应用到 inflate 用的 Context，
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
                    blurNote()   // 点击浮窗外部：备注失焦并保存
                    dismiss()
                    true
                } else false
            }
        }
        menuView = buildMenu(ctx)
        root!!.addView(menuView)
        val lp = params
        lp.x = 0
        lp.y = 0
        wm.addView(root, lp)
    }

    fun dismiss() {
        if (root != null) {
            // 退出前确保备注已保存（失焦之外的兜底）
            noteEdit?.let { service.updateFreezeNote(layer, it.text.toString()) }
            try { wm.removeView(root) } catch (_: Exception) {}
            root = null
            menuView = null
            noteEdit = null
        }
    }

    /** 备注失焦：保存当前文本（clearFocus 会触发 onFocusChange 收起键盘）。 */
    private fun blurNote() {
        noteEdit?.clearFocus()
    }

    private fun buildMenu(ctx: Context): View {
        val v = LayoutInflater.from(ctx).inflate(R.layout.sheet_freeze, null)
        // 行1：定格时刻系统时间（格式 xxxx年xx月xx日 xx:xx:xx）
        val fmt = SimpleDateFormat("yyyy年MM月dd日 HH:mm:ss", Locale.getDefault())
        v.findViewById<TextView>(R.id.freeze_time).text = fmt.format(Date(layer.time))
        // 行1.5：可编辑备注（默认取自随机碎碎念），失焦自动保存到该定格层（随层清除而丢失）
        val noteEdit = v.findViewById<EditText>(R.id.freeze_note)
        noteEdit.setText(layer.note)
        noteEdit.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                service.updateFreezeNote(layer, noteEdit.text.toString())
                // 失焦即收起软键盘
                val imm = service.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(noteEdit.windowToken, 0)
            }
        }
        this.noteEdit = noteEdit
        // 点击浮窗内输入框以外任意处（含按钮/背景）均视为失焦：保存备注并收起键盘
        v.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                val ne = noteEdit
                val loc = IntArray(2)
                ne.getLocationOnScreen(loc)
                val x = event.rawX.toInt()
                val y = event.rawY.toInt()
                val inside = x >= loc[0] && x <= loc[0] + ne.width && y >= loc[1] && y <= loc[1] + ne.height
                if (!inside) blurNote()
            }
            false  // 不消费事件，正常触发按钮点击
        }
        // 行2：定格-清除（仅本层），然后退出浮窗
        v.findViewById<View>(R.id.freeze_clear).setOnClickListener {
            service.removeFreezeLayer(layer); dismiss()
        }
        // 行3：定格-清除所有，然后退出浮窗
        v.findViewById<View>(R.id.freeze_clear_all).setOnClickListener {
            service.clearAllFreeze(); dismiss()
        }
        // 行3：定格-交互 开关（单行，标题+开关同线；与“点击穿透”等竖排开关不同布局）
        val sw = v.findViewById<android.widget.Switch>(R.id.sw_freezeinteract)
        sw.isChecked = service.currentConfig().freezeDraggable
        v.findViewById<View>(R.id.menu_switch_freezeinteract).setOnClickListener {
            val next = !sw.isChecked
            sw.isChecked = next
            service.updateConfig { copy(freezeDraggable = next) }
        }
        // 行4：设置（跳转应用设置页，与宠物浮窗一致）
        v.findViewById<View>(R.id.freeze_settings).setOnClickListener {
            dismiss()
            val intent = Intent(service, SettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            service.startActivity(intent)
        }
        return v
    }
}
