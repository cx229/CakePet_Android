package com.cx.cakepet

import android.content.Intent
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.slider.Slider
import kotlinx.coroutines.runBlocking

/**
 * Display - interaction. Uses the same XML row styles, SliderHelper and EditSync
 * as all existing CakePet settings screens. No runtime-generated form controls.
 */
class DisplayInteractionActivity : AppCompatActivity() {
    private val roleId by lazy { intent.getStringExtra("role_id") ?: RoleRegistry.DEFAULT.id }
    private val config by lazy { PetConfig.forRole(this, roleId) }
    private val bounds by lazy { PetBounds.forRole(this, roleId) }
    private val guideOwner = Any()

    private lateinit var modeSpinner: Spinner
    private lateinit var rangeSpinner: Spinner
    private lateinit var coreRows: View
    private lateinit var offsetRows: View
    private lateinit var alphaHint: View

    private val modeIds = listOf("display-opaque", "core-transparent", "core-opaque")
    private val modeLabels = listOf("显示-不透明", "核心-透明", "核心-不透明")

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        commitInputOnTouchOutside(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_display_interaction)
        findViewById<TextView>(R.id.tv_title_sub).text = RoleStore.resolveDisplayName(this, roleId)
        findViewById<View>(R.id.status_bar_spacer).apply {
            layoutParams.height = statusBarHeight()
            requestLayout()
        }
        findViewById<ImageView>(R.id.iv_back).setOnClickListener { finish() }
        val more = findViewById<ImageView>(R.id.btn_more)
        val menu = PopupMenuHelper(
            anchor = more,
            popupLayoutRes = R.layout.popup_settings_more_simple,
            items = mapOf(R.id.item_threshold to {
                startActivity(Intent(this, RangeThresholdActivity::class.java).apply {
                    putExtra("role_id", roleId)
                    putExtra("adapt_page", false)
                })
            })
        )
        more.setOnClickListener { menu.show() }

        modeSpinner = findViewById(R.id.spinner_hit_mode)
        rangeSpinner = findViewById(R.id.spinner_activity_range)
        coreRows = findViewById(R.id.group_core_scale)
        offsetRows = findViewById(R.id.group_range_offsets)
        alphaHint = findViewById(R.id.alpha_extra)

        SettingsSpinnerBinder.bind(modeSpinner, modeLabels,
            modeIds.indexOf(config.getBlocking().interactionMode).coerceAtLeast(0)) { index ->
            val choice = modeIds[index]
            val saved = config.getBlocking().interactionMode
            if (choice == saved) return@bind
            if (choice == "core-opaque" && CakePetAccessibilityService.active == null) {
                modeSpinner.setSelection(modeIds.indexOf(saved).coerceAtLeast(0))
                android.app.AlertDialog.Builder(this)
                    .setTitle("需要无障碍权限")
                    .setMessage("核心-不透明需要先开启 CakePet 无障碍服务。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("前往设置") { _, _ ->
                        startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }.show()
                return@bind
            }
            runBlocking {
                config.update { it.copy(
                    interactionMode = choice,
                    hitMode = if (choice == "display-opaque") ConfigDefaults.HIT_BOUNDARY else ConfigDefaults.HIT_CORE,
                    forceOpaque = choice == "display-opaque"
                ) }
            }
            updateVisibility()
        }
        SettingsSpinnerBinder.bind(rangeSpinner, listOf("全屏", "自定义"),
            if (config.getBlocking().rangeFullScreen) 0 else 1) { index ->
            val full = index == 0
            if (config.getBlocking().rangeFullScreen != full) {
                runBlocking { config.update { it.copy(rangeFullScreen = full) } }
            }
            updateVisibility()
        }
        updateVisibility()
    }

    private fun updateVisibility() {
        val cfg = config.getBlocking()
        coreRows.visibility = if (cfg.interactionMode == "display-opaque") View.GONE else View.VISIBLE
        alphaHint.visibility = if (cfg.interactionMode == "core-transparent") View.VISIBLE else View.GONE
        offsetRows.visibility = if (cfg.rangeFullScreen) View.GONE else View.VISIBLE
    }

    private fun bindNumber(
        key: String, barId: Int, inputId: Int, value: Float,
        percent: Boolean, save: (Float) -> Unit
    ) {
        val b = bounds.getBlocking(SettingsPage.RANGE).sliders.first { it.key == key }
        val bar = findViewById<Slider>(barId)
        val input = findViewById<EditText>(inputId)
        val fmt: (Float) -> String = { v ->
            if (percent) "${v.toInt()}%" else "%.2f×".format(v)
        }
        bar.clearOnSliderTouchListeners()
        bar.applyBizRange(b.from, b.to, value, fmt) { v ->
            EditSync.setText(input, fmt(v))
            save(v)
        }
        bar.trackTouchState()
        EditSync.setText(input, fmt(value))
        EditSync.bind(input,
            parse = { it.replace("%", "").replace("×", "").toFloatOrNull() },
            format = fmt,
            clamp = { it.coerceIn(b.hardMin, b.hardMax) },
            defaultValue = b.default
        ) { v ->
            bar.setValueSafe(bizToSliderValue(v, b.from, b.to))
            save(v)
        }
    }

    private fun bindOffset(key: String, barId: Int, inputId: Int, value: Float,
                           side: Int, save: (Float) -> Unit) {
        val b = bounds.getBlocking(SettingsPage.RANGE).sliders.first { it.key == key }
        val bar = findViewById<Slider>(barId)
        val input = findViewById<EditText>(inputId)
        fun preview(actual: Float) {
            val c = config.getBlocking()
            PetService.instance?.showBoundGuide(
                guideOwner,
                if (side == 2) actual else c.offsetTop,
                if (side == 3) actual else c.offsetBottom,
                if (side == 0) actual else c.offsetLeft,
                if (side == 1) actual else c.offsetRight,
                activeSide = side
            )
        }
        bar.clearOnSliderTouchListeners()
        bar.applyBizRange(b.from, b.to, value, { it.toInt().toString() }) { v ->
            EditSync.setText(input, v.toInt().toString())
            save(v)
            if (bar.isUserTracking) preview(v)
        }
        bar.onUserTouch(onStart = { preview(value) },
            onStop = { PetService.instance?.releaseGuide(guideOwner) })
        EditSync.setText(input, value.toInt().toString())
        EditSync.bind(input, parse = { it.toFloatOrNull() },
            format = { it.toInt().toString() },
            clamp = { it.coerceIn(b.from, b.to) }, defaultValue = b.default) { v ->
            bar.setValueSafe(bizToSliderValue(v, b.from, b.to))
            save(v)
        }
    }

    override fun onResume() {
        super.onResume()
        if (config.getBlocking().interactionMode == "core-opaque" &&
            CakePetAccessibilityService.active == null) {
            runBlocking { config.update { it.copy(
                interactionMode = "core-transparent",
                hitMode = ConfigDefaults.HIT_CORE, forceOpaque = false
            ) } }
        }
        val c = config.getBlocking()
        if (::modeSpinner.isInitialized) {
            val mode = modeIds.indexOf(c.interactionMode).coerceAtLeast(0)
            if (modeSpinner.selectedItemPosition != mode) modeSpinner.setSelection(mode)
            val range = if (c.rangeFullScreen) 0 else 1
            if (rangeSpinner.selectedItemPosition != range) rangeSpinner.setSelection(range)
            updateVisibility()
        }
        bindNumber("alpha", R.id.alpha_bar, R.id.alpha_value, c.alpha * 100f, true) { v ->
            runBlocking { config.update { it.copy(alpha = v / 100f) } }
        }
        bindNumber("coreHeight", R.id.core_height_bar, R.id.core_height_value,
            c.ctrlBoxHeight / ConfigDefaults.CTRL_BOX_HEIGHT, false) { v ->
            runBlocking { config.update { it.copy(ctrlBoxHeight = v * ConfigDefaults.CTRL_BOX_HEIGHT) } }
        }
        bindNumber("coreWidth", R.id.core_width_bar, R.id.core_width_value,
            c.ctrlBoxWidth / ConfigDefaults.CTRL_BOX_WIDTH, false) { v ->
            runBlocking { config.update { it.copy(ctrlBoxWidth = v * ConfigDefaults.CTRL_BOX_WIDTH) } }
        }
        bindOffset("offsetTop", R.id.off_top_bar, R.id.off_top_value, c.offsetTop, 2) { v ->
            runBlocking { config.update { it.copy(offsetTop = v) } }
        }
        bindOffset("offsetBottom", R.id.off_bottom_bar, R.id.off_bottom_value, c.offsetBottom, 3) { v ->
            runBlocking { config.update { it.copy(offsetBottom = v) } }
        }
        bindOffset("offsetLeft", R.id.off_left_bar, R.id.off_left_value, c.offsetLeft, 0) { v ->
            runBlocking { config.update { it.copy(offsetLeft = v) } }
        }
        bindOffset("offsetRight", R.id.off_right_bar, R.id.off_right_value, c.offsetRight, 1) { v ->
            runBlocking { config.update { it.copy(offsetRight = v) } }
        }
    }

    override fun onDestroy() {
        PetService.instance?.releaseGuide(guideOwner)
        super.onDestroy()
    }

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id)
            else (resources.displayMetrics.density * 24).toInt()
    }
}
