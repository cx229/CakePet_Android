package com.cx.cakepet

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.graphics.drawable.ColorDrawable

/** More-menu styling shared with Settings: rounded surface, ripple, separated compact rows. */
object UnifiedOptionMenu {
    fun show(anchor: View, options: List<String>, danger: Set<String> = emptySet(),
             onSelect: (String) -> Unit) {
        if (options.isEmpty()) return
        val ctx = anchor.context
        val density = ctx.resources.displayMetrics.density
        fun dp(n: Int) = (n * density + .5f).toInt()
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.menu_bg_popup)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val popup = PopupWindow(layout, dp(200), ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            elevation = dp(8).toFloat()
            isOutsideTouchable = true
            isFocusable = true
            setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        }
        val attrs = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        val ripple = try { attrs.getResourceId(0, 0) } finally { attrs.recycle() }
        options.forEachIndexed { index, label ->
            if (index > 0) layout.addView(View(ctx).apply {
                setBackgroundColor(ctx.getColor(R.color.cp_subtle))
            }, LinearLayout.LayoutParams(-1, dp(1)).apply {
                leftMargin = dp(8)
                rightMargin = dp(8)
            })
            layout.addView(TextView(ctx).apply {
                text = label
                textSize = 11f
                gravity = android.view.Gravity.CENTER_VERTICAL
                setTextColor(ctx.getColor(if (label in danger) R.color.cp_danger else R.color.cp_title))
                setPadding(dp(16), 0, dp(16), 0)
                if (ripple != 0) setBackgroundResource(ripple)
                setOnClickListener { popup.dismiss(); onSelect(label) }
            }, LinearLayout.LayoutParams(-1, dp(48)))
        }
        popup.showAsDropDown(anchor, (anchor.width - dp(200)).coerceAtMost(0), dp(4))
    }
}
