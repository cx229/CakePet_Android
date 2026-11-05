package com.cx.cakepet

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A UI-only fold mechanism, deliberately separate from effective feature switches.
 * A switch changes saved config; its adjacent chevron changes only expansion.
 *
 * Every row is addressed by its actual container, not by walking blindly to the
 * nearest parent after editing a child. Callers list whole setting rows or cards.
 */
class SettingsFoldController(private val host: Activity) {
    private data class Section(
        val id: Int,
        val switch: CompoundButton,
        val children: List<Int>,
        val ancestors: List<Int>,
        val dependency: (PetConfigData) -> Boolean,
        var expanded: Boolean,
        val arrow: TextView,
        val dependencyHint: TextView?
    )

    private val sections = mutableListOf<Section>()
    private val paddings = java.util.IdentityHashMap<View, Int>()
    private val margins = java.util.IdentityHashMap<View, Int>()
    private var config: PetConfigData? = null

    private fun dp(value: Int) = (host.resources.displayMetrics.density * value).toInt()

    private fun row(id: Int): View? {
        val child = host.findViewById<View>(id) ?: return null
        return if (child is ViewGroup) child else child.parent as? View
    }

    private fun indent(view: View, depth: Int) {
        // An identified whole section is indented by its card border, not by
        // pushing content inside a full-width card. Ordinary rows use padding.
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams
        if (view.id != View.NO_ID && view is ViewGroup && params != null) {
            val original = margins.getOrPut(view) { params.marginStart }
            params.marginStart = original + dp(depth * 10)
            view.layoutParams = params
        } else {
            val original = paddings.getOrPut(view) { view.paddingLeft }
            view.setPadding(original + dp(depth * 10),
                view.paddingTop, view.paddingRight, view.paddingBottom)
        }
    }

    fun add(
        masterId: Int,
        children: List<Int>,
        ancestors: List<Int> = emptyList(),
        depth: Int = 0,
        expandedInitially: Boolean = true,
        indentChildren: Boolean = true,
        dependency: (PetConfigData) -> Boolean = { true },
        unavailableText: String? = null
    ) {
        val sw = host.findViewById<View>(masterId) as? CompoundButton ?: return
        val header = row(masterId) as? ViewGroup ?: return
        val arrow = TextView(host).apply {
            textSize = 20f
            gravity = Gravity.CENTER
            contentDescription = "展开或收起详细设置"
            isClickable = true
            isFocusable = true
            setPadding(dp(5), 0, dp(5), 0)
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(44))
        }
        // Keep the toggle and the chevron as two independent touch targets.
        header.addView(arrow)
        val captionGroup = header.getChildAt(0) as? ViewGroup
        val hint = unavailableText?.let { message ->
            TextView(host).apply {
                text = message
                textSize = 11f
                setTextColor(0xFF888888.toInt())
                visibility = View.GONE
            }.also { captionGroup?.addView(it) }
        }
        val section = Section(masterId, sw, children, ancestors,
            dependency, expandedInitially, arrow, hint)
        sections.add(section)
        indent(header, depth)
        if (indentChildren) children.forEach { childId -> row(childId)?.let { indent(it, depth + 1) } }
        arrow.setOnClickListener {
            section.expanded = !section.expanded
            refresh()
        }
        refresh()
    }

    fun refresh(c: PetConfigData? = null) {
        if (c != null) config = c
        val snapshot = config ?: return
        val groups = sections.associateBy { it.id }
        for (section in sections) {
            val ancestorsExpanded = section.ancestors.all { groups[it]?.expanded != false }
            val ancestorsEffective = section.ancestors.all {
                val parent = groups[it]
                parent != null && parent.switch.isChecked && parent.dependency(snapshot)
            }
            val available = section.dependency(snapshot) && ancestorsEffective
            val effective = section.switch.isChecked && available
            section.dependencyHint?.visibility =
                if (available) View.GONE else View.VISIBLE
            section.arrow.text = if (section.expanded) "▾" else "▸"
            section.arrow.contentDescription =
                if (section.expanded) "收起详细设置" else "展开详细设置"
            // Gray out ineffective parameters but keep the original saved selections readable.
            for (childId in section.children) {
                val child = row(childId) ?: continue
                val visible = section.expanded && ancestorsExpanded
                child.visibility = if (visible) View.VISIBLE else View.GONE
                child.alpha = if (effective) 1f else 0.48f
                // Existing card headings must follow their card, without matching a
                // title through a blind generic parent walk.
                if (child.id == childId && child is ViewGroup) {
                    val parent = child.parent as? ViewGroup
                    val index = parent?.indexOfChild(child) ?: -1
                    if (index > 0) {
                        val before = parent?.getChildAt(index - 1)
                        if (before is TextView) {
                            before.visibility = child.visibility
                            before.alpha = child.alpha
                            // Existing card sections use a zero-height spacer before
                            // the heading. Fold it too to avoid empty 24dp gaps.
                            if (index > 1) {
                                val spacer = parent?.getChildAt(index - 2)
                                if (spacer != null && spacer.layoutParams?.height == 0) {
                                    spacer.visibility = child.visibility
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
