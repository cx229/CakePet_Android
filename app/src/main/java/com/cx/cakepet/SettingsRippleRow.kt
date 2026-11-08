package com.cx.cakepet

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.LinearLayout

/**
 * Settings row ripple observes touch without consuming child controls.
 * The small material link uses its own enlarged touch area and must not
 * light up the whole row while opening a dialog.
 */
class SettingsRippleRow @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {
    private var auxiliaryTouchBounds: Rect? = null

    /** A fold-managed row routes diagnostic taps through its fold controller. */
    var managedByFoldController: Boolean = false
    var diagnosticClickInstalled: Boolean = false

    init {
        foreground = context.getDrawable(R.drawable.settings_row_ripple)
        isClickable = true
        isLongClickable = true
    }

    fun setAuxiliaryTouchBounds(bounds: Rect) {
        auxiliaryTouchBounds = Rect(bounds)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val auxiliary = auxiliaryTouchBounds?.contains(ev.x.toInt(), ev.y.toInt()) == true
                drawableHotspotChanged(ev.x, ev.y)
                isPressed = !auxiliary
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> isPressed = false
        }
        return super.dispatchTouchEvent(ev)
    }
}
