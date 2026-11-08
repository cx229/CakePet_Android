package com.cx.cakepet

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.graphics.Typeface
import android.view.Gravity
import android.view.TouchDelegate
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/** The label stays on the original title baseline; its hit box borrows the existing row padding. */
object SettingsMaterialLink {
    fun attach(activity: Activity, instanceId: String, switchId: Int,
               title: String, label: String, vararg paths: String) {
        val toggle = activity.findViewById<View>(switchId) ?: return
        val settingRow = toggle.parent as? SettingsRippleRow ?: return

        fun findTitle(node: View): TextView? {
            if (node is TextView && node.text.toString() == title) return node
            if (node is ViewGroup) for (index in 0 until node.childCount) {
                findTitle(node.getChildAt(index))?.let { return it }
            }
            return null
        }
        val heading = findTitle(settingRow) ?: return
        val owner = heading.parent as? LinearLayout ?: return
        val position = owner.indexOfChild(heading)
        val originalParams = heading.layoutParams
        owner.removeView(heading)

        // Text measurement determines height, not the auxiliary touch target.
        val inline = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        inline.addView(heading, LinearLayout.LayoutParams(-2, -2))
        val preset = RoleStore.descriptorForInstance(activity, instanceId)?.source == RoleSource.ASSETS
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density + .5f).toInt()
        val link = TextView(activity).apply {
            text = if (preset) "查看功能/动画 ›" else "编辑功能/动画 ›"
            textSize = 8.5f
            setTextColor(activity.getColor(R.color.cp_note))
            setTypeface(Typeface.DEFAULT, Typeface.NORMAL)
            gravity = Gravity.CENTER_VERTICAL
            includeFontPadding = false
            // No list-selector background: the parent has its own pressed ripple.
            // A small label must not make the whole setting flash during navigation.
            setPadding(dp(5), 0, 0, 0)
            isClickable = true
            isFocusable = true
            contentDescription = label + (if (preset) "，查看关联素材" else "，编辑关联素材")
            setOnClickListener {
                activity.startActivity(Intent(activity, RolePackLinkedActivity::class.java).apply {
                    putExtra("source_instance_id", instanceId)
                    putExtra("linked_title", label)
                    putStringArrayListExtra("linked_paths", ArrayList(paths.toList()))
                })
            }
        }
        inline.addView(link, LinearLayout.LayoutParams(-2, -2))
        owner.addView(inline, position, originalParams)

        // Only the touch rectangle grows. Neither the heading nor its note moves down.
        settingRow.post {
            if (!link.isAttachedToWindow) return@post
            val bounds = Rect(0, 0, link.width, link.height)
            settingRow.offsetDescendantRectToMyCoords(link, bounds)
            bounds.left = (bounds.left - dp(8)).coerceAtLeast(0)
            bounds.right = (bounds.right + dp(12)).coerceAtMost(toggle.left - dp(4))
            bounds.top = 0
            bounds.bottom = settingRow.height
            if (bounds.right > bounds.left) {
                settingRow.touchDelegate = TouchDelegate(bounds, link)
                settingRow.setAuxiliaryTouchBounds(bounds)
            }
        }
    }
}
