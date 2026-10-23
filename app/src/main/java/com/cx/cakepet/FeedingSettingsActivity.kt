package com.cx.cakepet

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.cx.cakepet.EditSync
import com.cx.cakepet.PetBounds
import com.cx.cakepet.PetConfig
import com.cx.cakepet.PetConfigData
import com.cx.cakepet.SettingsPage
import com.cx.cakepet.PopupMenuHelper
import com.google.android.material.slider.RangeSlider
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 吃吃吃（投食）设置页：投食开关 / 大小 / 透明度 / 最大数量 / 频率 / 自动过期 + 过期时间 / 全屏刷新。 */
class FeedingSettingsActivity : AppCompatActivity() {
    private val roleId by lazy { intent?.getStringExtra("role_id") ?: RoleRegistry.DEFAULT.id }
    private val config by lazy { PetConfig.forRole(this, roleId) }
    // 每次调用重读最新 PetBounds（阈值页修改后即时生效），禁止缓存——否则 onResume 重绑行程仍是旧界。
    private fun bs() = PetBounds.forRole(this@FeedingSettingsActivity, roleId).getBlocking(SettingsPage.FEEDING).sliders.associateBy { it.key }
    private fun bd() = PetBounds.forRole(this@FeedingSettingsActivity, roleId).getBlocking(SettingsPage.FEEDING).duals.associateBy { it.key }

    private lateinit var statusBarSpacer: View
    private lateinit var ivBack: ImageView
    private lateinit var btnMore: ImageView
    private lateinit var swFeedingEnabled: Switch
    private lateinit var fishScaleBar: Slider
    private lateinit var fishScaleVal: EditText
    private lateinit var fishAlphaBar: Slider
    private lateinit var fishAlphaVal: EditText
    private lateinit var swFullScreen: Switch
    private lateinit var swEdgeRefresh: Switch
    private lateinit var fishMaxBar: Slider
    private lateinit var fishMaxVal: EditText
    private lateinit var periodRange: RangeSlider
    private lateinit var periodMinVal: EditText
    private lateinit var periodMaxVal: EditText
    private lateinit var swAutoExpire: Switch
    private lateinit var fishExpireBar: Slider
    private lateinit var fishExpireVal: EditText
    private lateinit var expireBlock: View
    private lateinit var swFeedGrow: Switch
    private lateinit var feedGrowBar: Slider
    private lateinit var feedGrowVal: EditText

    private var pendingScale = Float.NaN
    private var pendingAlpha = Float.NaN
    private var pendingMax = Float.NaN
    private var pendingExpire = Float.NaN
    private var pendingGrow = Float.NaN
    private var pendingPeriod: Pair<Int, Int>? = null
    private var suppressSwitches = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_feeding_settings)
        // 标题下方小字灰色：当前页面所属角色名
        findViewById<android.widget.TextView>(R.id.tv_title_sub).text = RoleStore.resolveDisplayName(this, roleId)

        statusBarSpacer = findViewById(R.id.status_bar_spacer)
        statusBarSpacer.layoutParams.height = getStatusBarHeight()
        ivBack = findViewById(R.id.iv_back)
        btnMore = findViewById(R.id.btn_more)
        swFeedingEnabled = findViewById(R.id.sw_feeding_enabled)
        fishScaleBar = findViewById(R.id.fish_scale_bar)
        fishScaleVal = findViewById(R.id.fish_scale_value)
        fishAlphaBar = findViewById(R.id.fish_alpha_bar)
        fishAlphaVal = findViewById(R.id.fish_alpha_value)
        swFullScreen = findViewById(R.id.sw_full_screen)
        swEdgeRefresh = findViewById(R.id.sw_edge_refresh)
        fishMaxBar = findViewById(R.id.fish_max_bar)
        fishMaxVal = findViewById(R.id.fish_max_value)
        periodRange = findViewById(R.id.period_range)
        periodMinVal = findViewById(R.id.period_min_value)
        periodMaxVal = findViewById(R.id.period_max_value)
        swAutoExpire = findViewById(R.id.sw_auto_expire)
        fishExpireBar = findViewById(R.id.fish_expire_bar)
        fishExpireVal = findViewById(R.id.fish_expire_value)
        expireBlock = findViewById(R.id.expire_block)
        swFeedGrow = findViewById(R.id.sw_feed_grow)
        feedGrowBar = findViewById(R.id.feed_grow_bar)
        feedGrowVal = findViewById(R.id.feed_grow_value)

        // 顶部导航
        ivBack.setOnClickListener { finish() }
        val moreMenu = PopupMenuHelper(
            anchor = btnMore,
            popupLayoutRes = R.layout.popup_settings_more_simple,
            items = mapOf(
                R.id.item_threshold to { startActivity(Intent(this, FeedingThresholdActivity::class.java).apply { putExtra("role_id", roleId) }) }
            )
        )
        btnMore.setOnClickListener { moreMenu.show() }

        // 滑块范围（跟随阈值页基础范围）：抽到 bindBounds()，onCreate 与 onResume 复用，
        // 使从阈值页返回时滑块行程（最新上下界）即时热更新。下方局部声明供 EditSync 的 defaultValue 使用。
        val bScale = bs()["feedScale"]!!
        val bAlpha = bs()["feedAlpha"]!!
        val bMax = bs()["feedMaxCount"]!!
        val bExpire = bs()["feedExpire"]!!
        val bGrow = bs()["feedGrow"]!!
        val dPeriod = bd()["feedPeriod"]!!
        bindBounds()

        // 总开关
        swFeedingEnabled.setOnCheckedChangeListener { _, b ->
            if (!suppressSwitches) lifecycleScope.launch { config.update { it.copy(feedingEnabled = b) } }
        }
        swFullScreen.setOnCheckedChangeListener { _, b ->
            if (!suppressSwitches) lifecycleScope.launch { config.update { it.copy(feedingFullScreen = b) } }
        }
        swEdgeRefresh.setOnCheckedChangeListener { _, b ->
            if (!suppressSwitches) lifecycleScope.launch { config.update { it.copy(feedingEdgeRefresh = b) } }
        }
        swAutoExpire.setOnCheckedChangeListener { _, b ->
            if (!suppressSwitches) {
                setExpireEnabled(b)
                lifecycleScope.launch { config.update { it.copy(feedingAutoExpire = b) } }
            }
        }
        swFeedGrow.setOnCheckedChangeListener { _, b ->
            if (!suppressSwitches) lifecycleScope.launch { config.update { it.copy(feedGrowEnabled = b) } }
        }

        // 大小
        fishScaleBar.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                pendingScale = value
                EditSync.setText(fishScaleVal, "%.2f×".format(value))
                lifecycleScope.launch { config.update { it.copy(feedingFishScale = value) } }
            }
        }
        EditSync.bind(fishScaleVal,
            parse = { it.replace("×", "").toFloatOrNull() },
            format = { "%.2f×".format(it) },
            clamp = { it.coerceIn(fishScaleBar.valueFrom, fishScaleBar.valueTo) },
            defaultValue = bScale.default) { v ->
            pendingScale = v
            fishScaleBar.value = v
            lifecycleScope.launch { config.update { it.copy(feedingFishScale = v) } }
        }

        // 透明度（存储为 0-100 百分比）
        fishAlphaBar.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                pendingAlpha = value
                EditSync.setText(fishAlphaVal, "%d%%".format(value.toInt()))
                lifecycleScope.launch { config.update { it.copy(feedingFishAlpha = value) } }
            }
        }
        EditSync.bind(fishAlphaVal,
            parse = { it.replace("%", "").toFloatOrNull() },
            format = { "%d%%".format(it.toInt()) },
            clamp = { it.coerceIn(fishAlphaBar.valueFrom, fishAlphaBar.valueTo) },
            defaultValue = bAlpha.default) { v ->
            pendingAlpha = v
            fishAlphaBar.value = v
            lifecycleScope.launch { config.update { it.copy(feedingFishAlpha = v) } }
        }

        // 最大数量
        fishMaxBar.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                pendingMax = value
                EditSync.setText(fishMaxVal, value.toInt().toString())
                lifecycleScope.launch { config.update { it.copy(feedingMaxCount = value.toInt()) } }
            }
        }
        EditSync.bind(fishMaxVal,
            parse = { it.toFloatOrNull() },
            format = { it.toInt().toString() },
            clamp = { it.coerceIn(fishMaxBar.valueFrom, fishMaxBar.valueTo) },
            defaultValue = bMax.default) { v ->
            pendingMax = v
            fishMaxBar.value = v
            lifecycleScope.launch { config.update { it.copy(feedingMaxCount = v.toInt()) } }
        }

        // 频率（双滑块）：滑块拖动 ↔ 双输入框联动；输入框提交时钳制保证 min≤max。
        val pFrom = dPeriod.fromA; val pTo = dPeriod.toB
        fun syncPeriodInputs(lo: Int, hi: Int) {
            EditSync.setText(periodMinVal, lo.toString())
            EditSync.setText(periodMaxVal, hi.toString())
        }
        periodRange.addOnChangeListener { slider, _, fromUser ->
            val lo = slider.values[0].toInt()
            val hi = slider.values[1].toInt()
            syncPeriodInputs(lo, hi)
            if (fromUser) {
                pendingPeriod = lo to hi
                lifecycleScope.launch { config.update { it.copy(feedingPeriodMin = lo, feedingPeriodMax = hi) } }
            }
        }
        // 频率双输入框联动：对齐阈值页 dualItem 语义（下界可抬高上界、上界可拉低下界）。
        EditSync.bind(
            periodMinVal,
            parse = { it.toFloatOrNull() },
            format = { it.toInt().toString() },
            clamp = { v -> v.coerceIn(pFrom, pTo) },
            defaultValue = dPeriod.defaultA
        ) { v ->
            val curMax = periodRange.values[1]
            val lo = v
            val hi = maxOf(curMax, v)   // 下界超过当前上界时，带动上界一同抬升
            periodRange.setValues(lo, hi)
            EditSync.setText(periodMaxVal, hi.toInt().toString())
            lifecycleScope.launch { config.update { it.copy(feedingPeriodMin = lo.toInt(), feedingPeriodMax = hi.toInt()) } }
        }
        EditSync.bind(
            periodMaxVal,
            parse = { it.toFloatOrNull() },
            format = { it.toInt().toString() },
            clamp = { v -> v.coerceIn(pFrom, pTo) },
            defaultValue = dPeriod.defaultB
        ) { v ->
            val curMin = periodRange.values[0]
            val hi = v
            val lo = minOf(curMin, v)   // 上界低于当前下界时，带下界一同下降
            periodRange.setValues(lo, hi)
            EditSync.setText(periodMinVal, lo.toInt().toString())
            lifecycleScope.launch { config.update { it.copy(feedingPeriodMin = lo.toInt(), feedingPeriodMax = hi.toInt()) } }
        }

        // 过期时间
        fishExpireBar.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                pendingExpire = value
                EditSync.setText(fishExpireVal, value.toInt().toString())
                lifecycleScope.launch { config.update { it.copy(feedingExpireTime = value.toInt()) } }
            }
        }
        EditSync.bind(fishExpireVal,
            parse = { it.replace("s", "").toFloatOrNull() },
            format = { it.toInt().toString() },
            clamp = { it.coerceIn(fishExpireBar.valueFrom, fishExpireBar.valueTo) },
            defaultValue = bExpire.default) { v ->
            pendingExpire = v
            fishExpireBar.value = v
            lifecycleScope.launch { config.update { it.copy(feedingExpireTime = v.toInt()) } }
        }

        // 投喂变大：变大系数
        feedGrowBar.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                pendingGrow = value
                EditSync.setText(feedGrowVal, "%.2f".format(value))
                lifecycleScope.launch { config.update { it.copy(feedGrowFactor = value) } }
            }
        }
        EditSync.bind(feedGrowVal,
            parse = { it.toFloatOrNull() },
            format = { "%.2f".format(it) },
            clamp = { it.coerceIn(feedGrowBar.valueFrom, feedGrowBar.valueTo) },
            defaultValue = bGrow.default) { v ->
            pendingGrow = v
            feedGrowBar.value = v
            lifecycleScope.launch { config.update { it.copy(feedGrowFactor = v) } }
        }

        refreshUI()
    }

    private fun applyConfigToUi(c: PetConfigData) {
        suppressSwitches = true
        swFeedingEnabled.isChecked = c.feedingEnabled
        swFullScreen.isChecked = c.feedingFullScreen
        swEdgeRefresh.isChecked = c.feedingEdgeRefresh
        swAutoExpire.isChecked = c.feedingAutoExpire
        setExpireEnabled(c.feedingAutoExpire)
        swFeedGrow.isChecked = c.feedGrowEnabled
        suppressSwitches = false

        if (pendingScale.isNaN()) {
            fishScaleBar.value = c.feedingFishScale.coerceIn(fishScaleBar.valueFrom, fishScaleBar.valueTo)
            EditSync.setText(fishScaleVal, "%.2f×".format(c.feedingFishScale))
        }
        if (pendingAlpha.isNaN()) {
            fishAlphaBar.value = c.feedingFishAlpha.coerceIn(fishAlphaBar.valueFrom, fishAlphaBar.valueTo)
            EditSync.setText(fishAlphaVal, "%d%%".format(c.feedingFishAlpha.toInt()))
        }
        if (pendingMax.isNaN()) {
            fishMaxBar.value = c.feedingMaxCount.toFloat().coerceIn(fishMaxBar.valueFrom, fishMaxBar.valueTo)
            EditSync.setText(fishMaxVal, c.feedingMaxCount.toString())
        }
        if (pendingExpire.isNaN()) {
            fishExpireBar.value = c.feedingExpireTime.toFloat().coerceIn(fishExpireBar.valueFrom, fishExpireBar.valueTo)
            EditSync.setText(fishExpireVal, c.feedingExpireTime.toString())
        }
        if (pendingGrow.isNaN()) {
            feedGrowBar.value = c.feedGrowFactor.coerceIn(feedGrowBar.valueFrom, feedGrowBar.valueTo)
            EditSync.setText(feedGrowVal, "%.2f".format(c.feedGrowFactor))
        }
        if (pendingPeriod == null) {
            val lo = c.feedingPeriodMin.toFloat().coerceIn(periodRange.valueFrom, periodRange.valueTo)
            val hi = c.feedingPeriodMax.toFloat().coerceIn(periodRange.valueFrom, periodRange.valueTo)
            periodRange.setValues(lo, hi)
            EditSync.setText(periodMinVal, c.feedingPeriodMin.toString())
            EditSync.setText(periodMaxVal, c.feedingPeriodMax.toString())
        }
    }

    private fun setExpireEnabled(enabled: Boolean) {
        fishExpireBar.isEnabled = enabled
        fishExpireVal.isEnabled = enabled
        expireBlock.alpha = if (enabled) 1f else 0.4f
    }

    private fun refreshUI() {
        applyConfigToUi(config.getBlocking())
    }

    /** 重绑所有滑块的行程（上下界/步长），取自最新 PetBounds（阈值页修改后即时生效）。 */
    private fun bindBounds() {
        val bScale = bs()["feedScale"]!!
        fishScaleBar.valueFrom = bScale.from; fishScaleBar.valueTo = bScale.to; fishScaleBar.stepSize = bScale.step
        val bAlpha = bs()["feedAlpha"]!!
        fishAlphaBar.valueFrom = bAlpha.from; fishAlphaBar.valueTo = bAlpha.to; fishAlphaBar.stepSize = bAlpha.step
        val bMax = bs()["feedMaxCount"]!!
        fishMaxBar.valueFrom = bMax.from; fishMaxBar.valueTo = bMax.to; fishMaxBar.stepSize = bMax.step
        val bExpire = bs()["feedExpire"]!!
        fishExpireBar.valueFrom = bExpire.from; fishExpireBar.valueTo = bExpire.to; fishExpireBar.stepSize = bExpire.step
        val bGrow = bs()["feedGrow"]!!
        feedGrowBar.valueFrom = bGrow.from; feedGrowBar.valueTo = bGrow.to; feedGrowBar.stepSize = bGrow.step
        val dPeriod = bd()["feedPeriod"]!!
        periodRange.valueFrom = dPeriod.fromA; periodRange.valueTo = dPeriod.toB; periodRange.stepSize = dPeriod.stepA
    }

    override fun onResume() {
        super.onResume()
        // 从阈值页返回时，重读最新上下界并回填当前配置值（按新界钳制），即时热更新。
        bindBounds()
        refreshUI()
    }

    private fun resumePending() {
        pendingScale = Float.NaN
        pendingAlpha = Float.NaN
        pendingMax = Float.NaN
        pendingExpire = Float.NaN
        pendingGrow = Float.NaN
        pendingPeriod = null
    }

    private fun getStatusBarHeight(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId) else 0
    }
}
