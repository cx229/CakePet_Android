package com.cx.cakepet

import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.slider.Slider
import kotlinx.coroutines.runBlocking

/** Display - adaptation: standard XML subpage, retaining the original keyboard behavior. */
class DisplayAdaptActivity : AppCompatActivity() {
    private val roleId by lazy { intent.getStringExtra("role_id") ?: RoleRegistry.DEFAULT.id }
    private val config by lazy { PetConfig.forRole(this, roleId) }
    private val bounds by lazy { PetBounds.forRole(this, roleId) }
    private val previewOwner = Any()
    private lateinit var keyboard: Spinner
    private lateinit var rotation: Spinner
    private lateinit var reset: Switch
    private lateinit var liftBar: Slider
    private lateinit var liftInput: EditText

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        commitInputOnTouchOutside(event)
        return super.dispatchTouchEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_display_adapt)
        findViewById<TextView>(R.id.tv_title_sub).text = RoleStore.resolveDisplayName(this, roleId)
        findViewById<View>(R.id.status_bar_spacer).apply {
            layoutParams.height = statusBarHeight()
            requestLayout()
        }
        findViewById<ImageView>(R.id.iv_back).setOnClickListener { finish() }
        val more = findViewById<ImageView>(R.id.btn_more)
        val menu = PopupMenuHelper(
            anchor = more, popupLayoutRes = R.layout.popup_settings_more_simple,
            items = mapOf(R.id.item_threshold to {
                startActivity(Intent(this, RangeThresholdActivity::class.java).apply {
                    putExtra("role_id", roleId)
                    putExtra("adapt_page", true)
                })
            })
        )
        more.setOnClickListener { menu.show() }

        keyboard = findViewById(R.id.spinner_keyboard_mode)
        rotation = findViewById(R.id.spinner_rotation_mode)
        reset = findViewById(R.id.sw_ime_reset_bottom)
        liftBar = findViewById(R.id.ime_lift_bar)
        liftInput = findViewById(R.id.ime_lift_value)

        SettingsSpinnerBinder.bind(keyboard, AppBounds.KEYBOARD_MODES.map { it.first },
            AppBounds.KEYBOARD_MODES.indexOfFirst {
                it.second == config.getBlocking().keyboardMode()
            }.coerceAtLeast(0)) { position ->
            val mode = AppBounds.KEYBOARD_MODES[position].second
            if (config.getBlocking().keyboardMode() != mode) {
                runBlocking { config.update { it.copy(imeMode = mode) } }
            }
            updateKeyboardRows()
        }
        SettingsSpinnerBinder.bind(rotation, listOf("比例拉伸", "固定位置"),
            if (config.getBlocking().rotationAdaptMode == "fixed") 1 else 0) { position ->
            val value = if (position == 1) "fixed" else "stretch"
            if (config.getBlocking().rotationAdaptMode != value) {
                runBlocking { config.update { it.copy(rotationAdaptMode = value) } }
            }
            // This selector is deliberately a placeholder; no rotation physics changes.
        }
        reset.setOnCheckedChangeListener { _, value ->
            if (config.getBlocking().imeResetBottomOffset != value) {
                runBlocking { config.update { it.copy(imeResetBottomOffset = value) } }
            }
        }
        updateKeyboardRows()
    }

    private fun updateKeyboardRows() {
        val mode = config.getBlocking().keyboardMode()
        val needsLift = mode == "global" || mode == "local" || mode == "stretch"
        (reset.parent as View).visibility = if (needsLift) View.VISIBLE else View.GONE
        (liftBar.parent as View).visibility = if (needsLift) View.VISIBLE else View.GONE
    }

    override fun onResume() {
        super.onResume()
        val c = config.getBlocking()
        if (!::keyboard.isInitialized) return
        val mode = AppBounds.KEYBOARD_MODES.indexOfFirst { it.second == c.keyboardMode() }.coerceAtLeast(0)
        if (keyboard.selectedItemPosition != mode) keyboard.setSelection(mode)
        val rot = if (c.rotationAdaptMode == "fixed") 1 else 0
        if (rotation.selectedItemPosition != rot) rotation.setSelection(rot)
        reset.isChecked = c.imeResetBottomOffset
        updateKeyboardRows()

        val b = bounds.getBlocking(SettingsPage.RANGE).sliders.first { it.key == "imeLiftOffset" }
        liftBar.clearOnSliderTouchListeners()
        liftBar.applyBizRange(b.from, b.to, c.imeLiftOffset, { it.toInt().toString() }) { v ->
            EditSync.setText(liftInput, v.toInt().toString())
            runBlocking { config.update { it.copy(imeLiftOffset = v) } }
            if (liftBar.isUserTracking) PetService.instance?.showBoundGuide(previewOwner, imeLift = v)
        }
        liftBar.onUserTouch(
            onStart = { PetService.instance?.showBoundGuide(previewOwner, imeLift = config.getBlocking().imeLiftOffset) },
            onStop = { PetService.instance?.releaseGuide(previewOwner) }
        )
        EditSync.setText(liftInput, c.imeLiftOffset.toInt().toString())
        EditSync.bind(liftInput, parse = { it.toFloatOrNull() },
            format = { it.toInt().toString() },
            clamp = { it.coerceIn(b.from, b.to) }, defaultValue = b.default) { value ->
            liftBar.setValueSafe(bizToSliderValue(value, b.from, b.to))
            runBlocking { config.update { it.copy(imeLiftOffset = value) } }
        }
    }

    override fun onDestroy() {
        PetService.instance?.releaseGuide(previewOwner)
        super.onDestroy()
    }

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id)
            else (resources.displayMetrics.density * 24).toInt()
    }
}
