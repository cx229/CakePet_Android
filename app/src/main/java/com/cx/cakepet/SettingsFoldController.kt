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
 * A switch changes saved config; tapping the non-control portion of its row toggles expansion.
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
        val dependencyHint: TextView?,
        var locked: Boolean = false,
        var forceOff: Boolean = false,
        var previousExpansion: Boolean = true,
        var onLockedClick: (() -> Unit)? = null
    )

    private val sections = mutableListOf<Section>()
    private val paddings = java.util.IdentityHashMap<View, Int>()
    private var config: PetConfigData? = null

    private fun dp(value: Int) = (host.resources.displayMetrics.density * value).toInt()

    private fun row(id: Int): View? {
        val child = host.findViewById<View>(id) ?: return null
        return if (child is ViewGroup) child else child.parent as? View
    }

    private fun indent(view: View, depth: Int) {
        // Indentation belongs to the content, never to the outer card boundary.
        // In particular, four-direction switch groups must keep their full card width.
        val original = paddings.getOrPut(view) { view.paddingStart }
        view.setPaddingRelative(original + dp(depth * 10),
            view.paddingTop, view.paddingEnd, view.paddingBottom)
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
        (header as? SettingsRippleRow)?.managedByFoldController = true
        val arrow = TextView(host).apply {
            textSize = 20f
            gravity = Gravity.CENTER
            contentDescription = "展开或收起详细设置"
            isClickable = false
            isFocusable = false
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(44))
        }
        // The title X position is fixed by the row's semantic depth. The fold
        // chevron lives in the left padding strip as an overlay, not as a child:
        // its presence must never shift the title, note, switch, or row width.
        val captionGroup = header.getChildAt(0) as? ViewGroup
        header.overlay.add(arrow)
        header.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            val titleStart = view.paddingStart
            val arrowWidth = dp(18)
            val arrowHeight = dp(44)
            val x = (titleStart - arrowWidth).coerceAtLeast(0)
            val y = (view.height - arrowHeight) / 2
            arrow.layout(x, y, x + arrowWidth, y + arrowHeight)
        }
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
        // The parent row handles only unconsumed touches. Switches and other
        // interactive children consume their own events, preserving controls.
        header.isClickable = true
        header.setOnClickListener {
            if (section.locked) { section.onLockedClick?.invoke(); return@setOnClickListener }
            section.expanded = !section.expanded
            refresh()
        }
        // When forced ON, the main card opens diagnostics, while the existing
        // left chevron still folds/unfolds the fully usable child settings.
        header.setOnTouchListener { view, event ->
            if (!section.locked || section.forceOff ||
                event.x < arrow.left.toFloat() || event.x > arrow.right.toFloat() ||
                event.y < arrow.top.toFloat() || event.y > arrow.bottom.toFloat()) return@setOnTouchListener false
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> true
                android.view.MotionEvent.ACTION_UP -> {
                    section.expanded = !section.expanded
                    refresh()
                    true
                }
                android.view.MotionEvent.ACTION_CANCEL -> true
                else -> true
            }
        }
        refresh()
    }

    /**
     * Forced-on locks only the switch; its child controls remain usable.
     * Forced-off additionally folds the children, preserving previous fold state.
     * The parent setting row stays clickable as a repair entry in either case.
     */
    fun setForced(masterId: Int, state: Int, onClick: (() -> Unit)? = null) {
        val section = sections.firstOrNull { it.id == masterId } ?: return
        val wasForcedOff = section.locked && section.forceOff
        val nowForcedOff = state < 0
        if (!wasForcedOff && nowForcedOff) {
            section.previousExpansion = section.expanded
            section.expanded = false
        } else if (wasForcedOff && !nowForcedOff) {
            section.expanded = section.previousExpansion
        }
        section.locked = state != 0
        section.forceOff = nowForcedOff
        section.onLockedClick = if (state != 0) onClick else null
        section.switch.isEnabled = state == 0
        refresh()
    }

    fun setLocked(masterId: Int, locked: Boolean, onClick: (() -> Unit)? = null) =
        setForced(masterId, if (locked) -1 else 0, onClick)

    fun refresh(c: PetConfigData? = null) {
        if (c != null) config = c
        val stored = config ?: return
        // Forced movement-on must not gray out otherwise usable child controls.
        val snapshot = if (sections.any { it.id == R.id.sw_move_enabled && it.locked && !it.forceOff })
            stored.copy(moveEnabled = true) else stored
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
            section.arrow.text = if (section.expanded && !section.forceOff) "▾" else "▸"
            section.arrow.contentDescription =
                if (section.expanded) "收起详细设置" else "展开详细设置"
            // Gray out ineffective parameters but keep the original saved selections readable.
            for (childId in section.children) {
                val child = row(childId) ?: continue
                val visible = section.expanded && !section.forceOff && ancestorsExpanded
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
