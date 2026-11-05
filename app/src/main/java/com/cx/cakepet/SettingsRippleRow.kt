package com.cx.cakepet

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.LinearLayout

/**
 * One ripple per settings *item*, including rows with sliders and editable fields.
 * dispatchTouchEvent observes but never consumes or alters child touch handling.
 */
class SettingsRippleRow @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {
    init {
        foreground = context.getDrawable(R.drawable.settings_row_ripple)
        isClickable = true
        isLongClickable = true
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drawableHotspotChanged(ev.x, ev.y)
                isPressed = true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> isPressed = false
        }
        return super.dispatchTouchEvent(ev)
    }
}
