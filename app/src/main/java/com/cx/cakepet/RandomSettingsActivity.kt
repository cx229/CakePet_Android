package com.cx.cakepet

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.slider.RangeSlider
import com.google.android.material.slider.Slider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class RandomSettingsActivity : AppCompatActivity() {
    // 周期双滑块视图提升为字段，供 refreshUI / onResume 复用
    private lateinit var periodRange: RangeSlider
    private lateinit var periodVal: EditText
    // 随机项开关视图映射（提升为字段，供 doResetDefault 复用）
    private lateinit var switchViews: Map<Int, Switch>
    // 随机模式总开关（提升为字段，供 doResetDefault 复用）
    private lateinit var swRandomMode: Switch
    // 屏蔽随机模式总开关的监听器回调（提升为字段，供 doResetDefault 复用）
    private var suppressRandomSwitch = false
    private val config by lazy { PetConfig(this) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_random_settings)
        // 纯黑占位条填充状态栏高度，与标题栏黑底连成整体覆盖状态栏
        val spacer = findViewById<android.view.View>(R.id.status_bar_spacer)
        spacer.layoutParams.height = getStatusBarHeight()
        spacer.requestLayout()
        findViewById<ImageView>(R.id.iv_back).setOnClickListener { finish() }
        // 右上角“更多”图标：弹出菜单，含「恢复默认设置」与「打开阈值页」
        val randomBtnMore = findViewById<ImageView>(R.id.btn_more)
        val randomMoreMenu = SettingsMoreMenu(
            anchor = randomBtnMore,
            onThreshold = { startActivity(Intent(this, RandomThresholdActivity::class.java)) },
            onResetUserDefault = { doResetDefault() },
            onResetSystem = { doResetSystem() }
        )
        randomBtnMore.setOnClickListener { randomMoreMenu.show() }

        // 随机模式总开关（本页第一项）
        swRandomMode = findViewById(R.id.sw_random_mode)
        suppressRandomSwitch = false
        swRandomMode.setOnCheckedChangeListener { _, b ->
            if (suppressRandomSwitch) return@setOnCheckedChangeListener
            lifecycleScope.launch { config.update { it.copy(randomEnabled = b) } }
            if (b) PetService.instance?.triggerRandomNow()
        }

        // 17 个随机项开关：id → 位掩码
        val itemSwitches = listOf(
            R.id.sw_r_scale to RandomItemFlags.SCALE,
            R.id.sw_r_alpha to RandomItemFlags.ALPHA,
            R.id.sw_r_gravity_enabled to RandomItemFlags.GRAVITY_ENABLED,
            R.id.sw_r_tilt_gravity to RandomItemFlags.TILT_GRAVITY,
            R.id.sw_r_max_speed to RandomItemFlags.MAX_SPEED,
            R.id.sw_r_gravity to RandomItemFlags.GRAVITY,
            R.id.sw_r_rebound to RandomItemFlags.REBOUND,
            R.id.sw_r_gdir_v to RandomItemFlags.GDIR_V,
            R.id.sw_r_gdir_h to RandomItemFlags.GDIR_H,
            R.id.sw_r_rbound_top to RandomItemFlags.RBOUND_TOP,
            R.id.sw_r_rbound_bottom to RandomItemFlags.RBOUND_BOTTOM,
            R.id.sw_r_rbound_left to RandomItemFlags.RBOUND_LEFT,
            R.id.sw_r_rbound_right to RandomItemFlags.RBOUND_RIGHT
        )
        switchViews = itemSwitches.map { (id, flag) ->
            val sw = findViewById<Switch>(id)
            sw.setOnCheckedChangeListener { _, checked ->
                lifecycleScope.launch {
                    config.update { c ->
                        val items = if (checked) c.randomItems or flag else c.randomItems and flag.inv()
                        c.copy(randomItems = items)
                    }
                }
            }
            flag to sw
        }.toMap()

        // 周期：双滑块合并为 RangeSlider（天然保证 最小 <= 最大），业务范围来自 PetBounds
        periodRange = findViewById(R.id.period_range)
        periodVal = findViewById(R.id.period_value)
        refreshUI()

        // 「恢复默认设置」已移入右上角“更多”浮窗，逻辑封装为 doResetDefault()
    }

    /** 恢复本页默认设置（随机模式 + 随机项 + 周期），默认值取阈值页设定，供右上角“更多”浮窗调用。先弹确认框。 */
    private fun doResetDefault() {
        showConfirmDialog(
            title = "恢复用户默认",
            bodyHtml = "将<b>本页</b>的所有设置项目恢复为当前默认值？",
            subText = "可在“默认值与阈值页”修改默认值",
            positiveText = "恢复"
        ) { resetDefaultNow() }
    }

    /** 实际执行「恢复本页默认」：把阈值页记录的默认值写回 PetConfig 并刷新 UI。 */
    private fun resetDefaultNow() {
        lifecycleScope.launch { applyReset() }
    }

    /**
     * 统一由阈值默认重建本页当前值并刷新 UI。
     * randomItems 严格来自阈值开关默认（AppBounds.randomItemsFromBounds），
     * 使三条重置路径结果一致：恢复默认 / 重置系统 / 重置软件所有默认(=首次安装)。
     */
    private suspend fun applyReset() {
        try {
            config.resetFromBounds(PetBounds(this@RandomSettingsActivity), SettingsPage.RANDOM)
            val c = config.configFlow.first()
            val b = PetBounds(this@RandomSettingsActivity).getBlocking(SettingsPage.RANDOM).duals
                .firstOrNull { it.key == "randomPeriod" } ?: return
            suppressRandomSwitch = true
            // 按阈值默认值重建的位掩码同步各随机项开关
            switchViews.forEach { (flag, sw) -> sw.isChecked = (c.randomItems and flag) != 0 }
            swRandomMode.isChecked = c.randomEnabled
            val lo = c.randomPeriodMin.coerceIn(b.fromA.toInt(), b.toA.toInt())
            val hi = c.randomPeriodMax.coerceIn(b.fromB.toInt(), b.toB.toInt())
            val safeLo = kotlin.math.round(minOf(lo, hi).toFloat().coerceIn(periodRange.valueFrom, periodRange.valueTo))
            var safeHi = kotlin.math.round(maxOf(lo, hi).toFloat().coerceIn(periodRange.valueFrom, periodRange.valueTo))
            if (safeLo >= safeHi) safeHi = (safeLo + 1f).coerceAtMost(periodRange.valueTo)
            periodRange.values = listOf(safeLo, safeHi)
            EditSync.setText(periodVal, "${safeLo.toInt()} - ${safeHi.toInt()}")
            suppressRandomSwitch = false
        } catch (e: Exception) { e.printStackTrace() }
    }

    /** 重置系统默认值与阈值：先弹确认框，确认后恢复本页上下界、默认与当前值为系统出厂。 */
    private fun doResetSystem() {
        showConfirmDialog(
            title = "重置系统默认值与阈值",
            bodyHtml = "将<b>本页</b>的所有设置项目的当前值/默认值/阈值均重置为系统预设？",
            subText = "当前值、默认值和阈值都会重置",
            positiveText = "重置"
        ) { resetSystemNow() }
    }

    /** 实际执行「重置系统默认值与阈值」：bounds 先回落系统出厂，再按系统默认重建本页当前值并刷新 UI。 */
    private fun resetSystemNow() {
        lifecycleScope.launch {
            PetBounds(this@RandomSettingsActivity).resetSystem(SettingsPage.RANDOM)
            config.resetFromBounds(PetBounds(this@RandomSettingsActivity), SettingsPage.RANDOM)
            // 关键：必须走 refreshUI() 重绑周期双滑块的 valueFrom/valueTo 行程，否则阈值回落到更小范围后，
            // 滑块行程仍按「改过的旧上下界」映射，导致「超阈值重置」后双滑块范围/位置显示异常。
            refreshUI()
        }
    }

    // 周期双滑块绑定 + 值回填。抽成方法，onCreate 与 onResume 复用，
    // 使从阈值页返回时滑块行程（最新上下界）即时热更新。
    private fun refreshUI() {
        val bPeriod = PetBounds(this).getBlocking(SettingsPage.RANDOM).duals
            .firstOrNull { it.key == "randomPeriod" } ?: return
        periodRange.clearOnChangeListeners()
        // 关键：XML 默认 valueTo=180 使 values=[1,180]，若直接缩小 valueTo 会因现有 values 越界抛 IllegalArgumentException。
        // 必须先收窄 values 到新区间，再改 valueFrom/valueTo，顺序不可颠倒。
        // duals 已保证 上界 > 下界（最小跨度 1，见 BaseThresholdActivity.dualItem 钳制 + normalizeBounds），
        // 直接取 [fromA, toB] 即可；保留 maxOf(…, fromA+1f) 仅作极端退化兜底，不再用 ±1f 把范围塌成 2 单位。
        val rsFrom = bPeriod.fromA
        val rsTo = maxOf(bPeriod.toB, bPeriod.fromA + 1f)
        if (rsFrom >= rsTo) return
        periodRange.setValues(rsFrom, rsTo)
        periodRange.valueFrom = rsFrom
        periodRange.valueTo = rsTo
        fun onPeriodChanged() {
            PetService.instance?.requestRandomReset()
        }
        periodRange.addOnChangeListener { slider, _, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val min = slider.values[0].toInt()
            val max = slider.values[1].toInt()
            EditSync.setText(periodVal, "$min - $max")
            lifecycleScope.launch { config.update { it.copy(randomPeriodMin = min, randomPeriodMax = max) } }
            onPeriodChanged()
        }
        lifecycleScope.launch {
            try {
            val c = config.configFlow.first()
            // 随机总开关与各随机项：与 Snap/Settings 页一致，打开时回绑到 config（= AppDefaults.kt 默认），
            // 使随机项默认与总开关默认相互独立、互不牵连。
            suppressRandomSwitch = true
            swRandomMode.isChecked = c.randomEnabled
            switchViews.forEach { (flag, sw) -> sw.isChecked = (c.randomItems and flag) != 0 }
            suppressRandomSwitch = false
            val b = PetBounds(this@RandomSettingsActivity).getBlocking(SettingsPage.RANDOM).duals
                .firstOrNull { it.key == "randomPeriod" } ?: return@launch
            val lo = c.randomPeriodMin.coerceIn(b.fromA.toInt(), b.toA.toInt())
            val hi = c.randomPeriodMax.coerceIn(b.fromB.toInt(), b.toB.toInt())
            val safeLo = kotlin.math.round(minOf(lo, hi).toFloat().coerceIn(rsFrom, rsTo))
            var safeHi = kotlin.math.round(maxOf(lo, hi).toFloat().coerceIn(rsFrom, rsTo))
            if (safeLo >= safeHi) safeHi = (safeLo + 1f).coerceAtMost(rsTo)
            periodRange.values = listOf(safeLo, safeHi)
            EditSync.setText(periodVal, "${safeLo.toInt()} - ${safeHi.toInt()}")
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUI()
    }

    /** 获取系统真实状态栏高度（px），失败时回退 24dp */
    private fun getStatusBarHeight(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId)
        else (resources.displayMetrics.density * 24f).toInt()
    }

}
