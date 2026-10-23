package com.cx.cakepet

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow

/**
 * 通用“更多”浮窗：锚定到某个 View（通常是右上角/左上角的图标），在其下方弹出菜单。
 * 浮窗样式（圆角背景、菜单项 selectableItemBackground、分隔线）沿用 popup_settings_more 模板，
 * 复用同一套定位逻辑，仅菜单项与回调由调用方通过 [popupLayoutRes] + [items] 注入，
 * [onInflate] 可做条件可见性处理（如主设置页才显示「重置软件所有设置」）。
 *
 * @param anchor          触发浮窗的图标视图，浮窗在其下方弹出
 * @param popupLayoutRes  浮窗内容布局（如 R.layout.popup_settings_more / R.layout.popup_main_menu）
 * @param items           菜单项 id -> 点击回调 的映射，点击后自动关闭浮窗
 * @param onInflate       布局 inflate 后的可选预处理（如按条件显隐某项）
 */
class PopupMenuHelper(
    private val anchor: View,
    private val popupLayoutRes: Int,
    private val items: Map<Int, () -> Unit>,
    private val onInflate: ((View) -> Unit)? = null
) {
    private var popup: PopupWindow? = null

    fun show() {
        if (popup?.isShowing == true) {
            dismiss()
            return
        }
        val ctx = anchor.context
        val content = LayoutInflater.from(ctx).inflate(popupLayoutRes, null)

        onInflate?.invoke(content)
        items.forEach { (id, cb) ->
            content.findViewById<View>(id)?.setOnClickListener {
                dismiss()
                cb()
            }
        }

        popup = PopupWindow(
            content,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
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
