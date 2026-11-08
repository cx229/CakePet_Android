package com.cx.cakepet

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/** Compact textual link with a touch target larger than its visible label. */
object SettingsMaterialLink {
    fun attach(activity: Activity, instanceId: String, switchId: Int,
               title: String, label: String, vararg paths: String) {
        val switch = activity.findViewById<View>(switchId) ?: return
        val outer = switch.parent as? ViewGroup ?: return
        fun findTitle(node: View): TextView? {
            if (node is TextView && node.text.toString() == title) return node
            if (node is ViewGroup) for (i in 0 until node.childCount) {
                findTitle(node.getChildAt(i))?.let { return it }
            }
            return null
        }
        val old = findTitle(outer) ?: return
        val owner = old.parent as? LinearLayout ?: return
        val position = owner.indexOfChild(old)
        val original = old.layoutParams
        owner.removeView(old)
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(old, LinearLayout.LayoutParams(0, -2, 1f))
        val dp = activity.resources.displayMetrics.density
        val pack = RoleStore.descriptorForInstance(activity, instanceId)
        val preset = pack?.source == RoleSource.ASSETS
        val link = TextView(activity).apply {
            text = if (preset) "查看功能/动画 ›" else "编辑功能/动画 ›"
            textSize = 8.5f
            setTextColor(activity.getColor(R.color.cp_note))
            setTypeface(Typeface.DEFAULT, Typeface.NORMAL)
            gravity = Gravity.CENTER
            minHeight = (44 * dp).toInt()
            setPadding((9 * dp).toInt(), 0, (3 * dp).toInt(), 0)
            isClickable = true
            isFocusable = true
            background = activity.getDrawable(android.R.drawable.list_selector_background)
            contentDescription = label + "，" + if (preset) "查看关联素材" else "编辑关联素材"
            setOnClickListener {
                activity.startActivity(Intent(activity, RolePackLinkedActivity::class.java).apply {
                    putExtra("source_instance_id", instanceId)
                    putExtra("linked_title", label)
                    putStringArrayListExtra("linked_paths", ArrayList(paths.toList()))
                })
            }
        }
        row.addView(link, LinearLayout.LayoutParams(-2, -2))
        owner.addView(row, position, original)
    }
}
