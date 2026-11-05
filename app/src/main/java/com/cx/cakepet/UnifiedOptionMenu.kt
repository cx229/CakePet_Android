package com.cx.cakepet

import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView

/** Settings-style menu with viewport-aware placement above or below its anchor. */
object UnifiedOptionMenu {
    fun show(anchor: View, options: List<String>, danger: Set<String> = emptySet(),
             onSelect: (String) -> Unit) {
        if (options.isEmpty() || !anchor.isAttachedToWindow) return
        val ctx = anchor.context
        val density = ctx.resources.displayMetrics.density
        fun dp(n: Int) = (n * density + .5f).toInt()
        val width = dp(200)
        val gap = dp(4)
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val scroll = ScrollView(ctx).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            setBackgroundResource(R.drawable.menu_bg_popup)
            addView(layout, android.widget.FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val attrs = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        val ripple = try { attrs.getResourceId(0, 0) } finally { attrs.recycle() }
        val popup = PopupWindow(scroll, width, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            elevation = dp(8).toFloat()
            isOutsideTouchable = true
            isFocusable = true
            isClippingEnabled = true
            setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
        }
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
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(ctx.getColor(if (label in danger) R.color.cp_danger else R.color.cp_title))
                setPadding(dp(16), 0, dp(16), 0)
                if (ripple != 0) setBackgroundResource(ripple)
                setOnClickListener { popup.dismiss(); onSelect(label) }
            }, LinearLayout.LayoutParams(-1, dp(48)))
        }

        // Measure content before choosing an edge. The visible display frame excludes
        // system bars and the IME, unlike the raw full-screen display height.
        scroll.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val desiredHeight = scroll.measuredHeight
        val visible = Rect()
        anchor.getWindowVisibleDisplayFrame(visible)
        val position = IntArray(2)
        anchor.getLocationOnScreen(position)
        val anchorTop = position[1]
        val anchorBottom = anchorTop + anchor.height
        val spaceBelow = (visible.bottom - anchorBottom - gap).coerceAtLeast(0)
        val spaceAbove = (anchorTop - visible.top - gap).coerceAtLeast(0)
        val showAbove = spaceBelow < desiredHeight && spaceAbove > spaceBelow
        val availableHeight = if (showAbove) spaceAbove else spaceBelow
        val height = minOf(desiredHeight, availableHeight).coerceAtLeast(1)
        popup.height = height

        // Keep the right edge aligned with the three-dot button, including for cards
        // whose whole non-preview region triggers this same anchored menu.
        val desiredX = (position[0] + anchor.width - width)
            .coerceIn(visible.left, (visible.right - width).coerceAtLeast(visible.left))
        val xOffset = desiredX - position[0]
        val yOffset = if (showAbove) -(anchor.height + height + gap) else gap
        popup.showAsDropDown(anchor, xOffset, yOffset)
    }
}
