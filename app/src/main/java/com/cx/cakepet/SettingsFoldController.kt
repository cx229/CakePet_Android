package com.cx.cakepet

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.TextView
import java.util.IdentityHashMap

/**
 * Presentation-only settings hierarchy. Neither folding nor ancestor visibility writes
 * configuration: all child switch/slider values survive closing and reopening a section.
 */
class SettingsFoldController(private val host: Activity) {
    private data class Section(
        val master: CompoundButton,
        val children: List<Int>,
        val ancestors: List<Int>,
        var expanded: Boolean,
        val title: TextView?,
        val originalTitle: String
    )

    private val sections = mutableListOf<Section>()
    private val initialPaddings = IdentityHashMap<View, Int>()
    private fun dp(value: Int) = (host.resources.displayMetrics.density * value).toInt()

    private fun rowOf(id: Int): View? =
        host.findViewById<View>(id)?.parent as? View

    private fun titleOf(root: View, master: CompoundButton): TextView? {
        if (root is TextView && root !== master && root !is CompoundButton) return root
        if (root is ViewGroup) for (i in 0 until root.childCount) {
            titleOf(root.getChildAt(i), master)?.let { return it }
        }
        return null
    }

    private fun indent(row: View, level: Int) {
        val base = initialPaddings.getOrPut(row) { row.paddingLeft }
        row.setPadding(base + dp(12 * level), row.paddingTop,
            row.paddingRight, row.paddingBottom)
    }

    fun add(
        masterId: Int,
        children: List<Int>,
        ancestors: List<Int> = emptyList(),
        depth: Int = 0,
        expandedInitially: Boolean = true
    ) {
        val switch = host.findViewById<View>(masterId) as? CompoundButton ?: return
        val row = rowOf(masterId) ?: return
        val title = titleOf(row, switch)
        val section = Section(switch, children, ancestors, expandedInitially,
            title, title?.text?.toString().orEmpty())
        sections.add(section)
        indent(row, depth)
        for (childId in children) rowOf(childId)?.let { indent(it, depth + 1) }
        fun updateArrow() {
            title?.text = (if (section.expanded) "▾  " else "▸  ") + section.originalTitle
        }
        updateArrow()
        row.isClickable = true
        row.setOnClickListener {
            section.expanded = !section.expanded
            updateArrow()
            refresh()
        }
    }

    fun refresh() {
        for (section in sections) {
            val ancestorsVisible = section.ancestors.all { id ->
                val ancestor = sections.firstOrNull { it.master.id == id }
                val masterOn = (host.findViewById<View>(id) as? CompoundButton)?.isChecked == true
                masterOn && (ancestor?.expanded ?: true)
            }
            val show = ancestorsVisible && section.master.isChecked && section.expanded
            rowOf(section.master.id)?.alpha = if (ancestorsVisible) 1f else 0.45f
            for (childId in section.children) {
                rowOf(childId)?.visibility = if (show) View.VISIBLE else View.GONE
            }
        }
    }
}
