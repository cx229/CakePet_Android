package com.cx.cakepet

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.PopupWindow

/**
 * 设置页右上角“更多”浮窗：将「默认值与阈值」（打开阈值页）、「恢复用户默认」、
 * 「重置系统默认值与阈值」合并到右上角图标弹出的菜单中；主设置页额外提供
 * 「重置软件所有设置」（需确认）。图标资源视觉源自 assets/icon/more.svg。
 *
 * @param anchor            右上角的“更多”图标视图，浮窗在其下方弹出
 * @param onThreshold       点击「默认值与阈值」回调（打开阈值页）
 * @param onResetUserDefault 点击「恢复用户默认」回调
 * @param onResetSystem     点击「重置系统默认值与阈值」回调
 * @param onResetAll        点击「重置软件所有设置」回调（null 表示不显示该项）
 */
class SettingsMoreMenu(
    private val anchor: View,
    private val onThreshold: () -> Unit,
    private val onResetUserDefault: () -> Unit,
    private val onResetSystem: () -> Unit,
    private val onResetAll: (() -> Unit)? = null
) {
    private var popup: PopupWindow? = null

    fun show() {
        if (popup?.isShowing == true) {
            dismiss()
            return
        }
        val ctx = anchor.context
        val content = LayoutInflater.from(ctx)
            .inflate(R.layout.popup_settings_more, null)

        content.findViewById<View>(R.id.item_threshold).setOnClickListener {
            dismiss(); onThreshold()
        }
        content.findViewById<View>(R.id.item_reset_user).setOnClickListener {
            dismiss(); onResetUserDefault()
        }
        content.findViewById<View>(R.id.item_reset_system).setOnClickListener {
            dismiss(); onResetSystem()
        }

        // 「重置软件所有设置」仅主设置页提供
        if (onResetAll != null) {
            content.findViewById<View>(R.id.sep_reset_all).visibility = View.VISIBLE
            val itemAll = content.findViewById<View>(R.id.item_reset_all)
            itemAll.visibility = View.VISIBLE
            itemAll.setOnClickListener { dismiss(); onResetAll.invoke() }
        }

        popup = PopupWindow(
            content,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            elevation = 8f
            isOutsideTouchable = true
            isFocusable = true
            val xoff = (anchor.width - content.measuredWidthOrZero(ctx)).coerceAtMost(0)
            showAsDropDown(anchor, xoff, 4)
        }
    }

    fun dismiss() {
        popup?.dismiss()
        popup = null
    }

    private fun View.measuredWidthOrZero(context: Context): Int {
        measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        return measuredWidth
    }
}
