package com.cx.cakepet

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.slider.RangeSlider
import com.google.android.material.slider.Slider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 碎碎念设置页：字号 / 透明度 / 偏移 / 颜色（候选色 + HSV 色轮 + 明暗）/ 空白时长（双滑块）。
 * 所有改动即时写入 DataStore，由 PetService 的 configFlow 监听同步到浮窗。
 */
class ThinkingSettingsActivity : AppCompatActivity() {

    // 候选色：与阈值页「背景颜色」共用 THINKING_PRESET_COLORS（黑/白/黄/绿/蓝/红，定义于 AppDefaults.kt）
    private val presetColors = THINKING_PRESET_COLORS.toIntArray()

    private val config by lazy { PetConfig(this) }

    // 滑块/值视图提升为字段，供 refreshUI / onResume 复用
    private lateinit var sizeBar: Slider
    private lateinit var sizeVal: EditText
    private lateinit var alphaBar: Slider
    private lateinit var alphaVal: EditText
    private lateinit var offsetBar: Slider
    private lateinit var offsetVal: EditText
    private lateinit var rangeSlider: RangeSlider
    private lateinit var rangeVal: TextView
    // 以下开关视图提升为字段，供 doResetDefault 复用
    private lateinit var swFlashIn: SwitchCompat
    private lateinit var swFlashOut: SwitchCompat
    private lateinit var swThinkingEnabled: SwitchCompat
    // 屏蔽总开关 listener 回调（提升为字段，供 doResetDefault 复用）
    private var suppressEnabled: Boolean = false
    // 显示/闪回/背景 卡片容器（提升为字段，供类方法 setCardsEnabled 复用）
    private lateinit var cardDisplay: LinearLayout
    private lateinit var cardFlash: LinearLayout
    private lateinit var cardBg: LinearLayout

    // 碎碎念背景：开关 / 透明度滑块 / 颜色块
    private lateinit var swBgEnabled: SwitchCompat
    private lateinit var seekBgAlpha: Slider
    private lateinit var editBgAlpha: EditText
    private lateinit var bgColorDot: View
    private var currentBgColor: Int = 0xFFFFFFFF.toInt()

    // 各滑块「待确认值」：记录用户最后拖到的滑块值（Int，0..100 滑块空间），用于挡住滞后的旧发射回流，
    // 避免松手后滑块/数字被在途旧值拉回（倒放 / 自动往返），并让「恢复默认/重置」不被旧发射覆盖。
    // 与通用设置页 SettingsActivity 同款机制。
    private var pendingTextSize: Int? = null
    private var pendingAlpha: Int? = null
    private var pendingOffset: Int? = null
    private var pendingBgAlpha: Int? = null
    // 空白时长双滑块「待确认值」：记录用户最后拖到的 (lo,hi)，作用同单滑块 pending，挡住滞后的旧发射回流，
    // 避免松手后双滑块继续自动滑动；并在 refreshUI 开头清空，避免「恢复默认/重置」被此前拖动的 pending 死锁。
    private var pendingEmpty: Pair<Int, Int>? = null

    // 预览自动收起延时（用于点击选色等无拖动起止事件的场景）
    private val previewHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val PREVIEW_AUTO_HIDE_MS = 1800L

    // 当前颜色（ARGB，alpha 固定 0xFF），由预设点击或取色盘写入
    private var currentColor: Int = 0xFF333333.toInt()

    // 颜色入口行右侧的当前颜色圆角色块
    private var colorDot: View? = null

    // 取色弹窗（同时只允许一个），用于避免重复弹出
    private var colorDialog: Dialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_thinking_settings)
        // 纯黑占位条填充状态栏高度，与标题栏黑底连成整体覆盖状态栏
        val spacer = findViewById<View>(R.id.status_bar_spacer)
        spacer.layoutParams.height = getStatusBarHeight()
        spacer.requestLayout()
        findViewById<ImageView>(R.id.iv_back).setOnClickListener { finish() }
        // 右上角“更多”图标：弹出菜单，含「恢复默认设置」与「打开阈值页」
        val thinkBtnMore = findViewById<ImageView>(R.id.btn_more)
        val thinkMoreMenu = SettingsMoreMenu(
            anchor = thinkBtnMore,
            onThreshold = { startActivity(Intent(this, ThinkingThresholdActivity::class.java)) },
            onResetUserDefault = { doResetDefault() },
            onResetSystem = { doResetSystem() }
        )
        thinkBtnMore.setOnClickListener { thinkMoreMenu.show() }

        sizeBar = findViewById(R.id.text_size_bar)
        sizeVal = findViewById(R.id.text_size_value)
        alphaBar = findViewById(R.id.text_alpha_bar)
        alphaVal = findViewById(R.id.text_alpha_value)
        offsetBar = findViewById(R.id.offset_bar)
        offsetVal = findViewById(R.id.offset_value)
        rangeSlider = findViewById(R.id.empty_range)
        rangeVal = findViewById(R.id.empty_range_value)

        colorDot = findViewById(R.id.color_dot)
        findViewById<View>(R.id.row_color).setOnClickListener { showColorPickerDialog() }

        swBgEnabled = findViewById(R.id.sw_thinking_bg_enabled)
        seekBgAlpha = findViewById(R.id.seek_thinking_bg_alpha)
        editBgAlpha = findViewById(R.id.edit_thinking_bg_alpha)
        bgColorDot = findViewById(R.id.bg_color_dot)
        findViewById<View>(R.id.row_bg_color).setOnClickListener { showBgColorPickerDialog() }
        swBgEnabled.setOnCheckedChangeListener { _, b ->
            if (suppressEnabled) return@setOnCheckedChangeListener
            lifecycleScope.launch { config.update { it.copy(thinkingBgEnabled = b) } }
        }

        swFlashIn = findViewById(R.id.sw_flash_in)
        swFlashOut = findViewById(R.id.sw_flash_out)
        swFlashIn.setOnCheckedChangeListener { _, b ->
            if (suppressEnabled) return@setOnCheckedChangeListener
            lifecycleScope.launch { config.update { it.copy(thinkingFlashIn = b) } }
        }
        swFlashOut.setOnCheckedChangeListener { _, b ->
            if (suppressEnabled) return@setOnCheckedChangeListener
            lifecycleScope.launch { config.update { it.copy(thinkingFlashOut = b) } }
        }

        // 碎碎念总开关：直接控制 thinkingEnabled；关闭时禁用下方「显示 / 闪回」两个模块
        swThinkingEnabled = findViewById(R.id.sw_thinking_enabled)
        cardDisplay = findViewById(R.id.card_display)
        cardFlash = findViewById(R.id.card_flash)
        cardBg = findViewById(R.id.card_bg)
        suppressEnabled = false
        swThinkingEnabled.setOnCheckedChangeListener { _, b ->
            if (suppressEnabled) return@setOnCheckedChangeListener
            lifecycleScope.launch { config.update { it.copy(thinkingEnabled = b) } }
            setCardsEnabled(b)
        }

        // ===== 滑块：业务范围来自 PetBounds（阈值页可改上下界），滑块内部 0..100 归一化 =====
        refreshUI()

        // 常驻观察 configFlow：阈值页修改上下界/默认值、或本页复位/钳制后，数值·滑块·开关·颜色自动同步刷新，
        // 取代冷读 configFlow.first()，避免竞态读到旧快照（通用设置页 SettingsActivity 同款机制）。
        lifecycleScope.launch {
            config.configFlow.collect { applyConfigToUi(it) }
        }

        // 「恢复默认设置」已移入右上角“更多”浮窗，逻辑封装为 doResetDefault()
    }

    /** 恢复本页默认设置（显示模块 + 闪回模块的碎碎念相关项），默认值取阈值页设定。先弹确认框。 */
    private fun doResetDefault() {
        showConfirmDialog(
            title = "恢复用户默认",
            bodyHtml = "将<b>本页</b>的所有设置项目恢复为当前默认值？",
            subText = "可在“默认值与阈值页”修改默认值",
            positiveText = "恢复"
        ) { resetDefaultNow() }
    }

    /** 把当前配置刷新到本页所有滑块/数值/开关/颜色。供 configFlow.collect 持续调用，取代冷读 configFlow.first()。 */
    private suspend fun applyConfigToUi(c: PetConfigData) {
        val b = PetBounds(this@ThinkingSettingsActivity).getBlocking(SettingsPage.THINKING)
        val bs2 = b.sliders.first { it.key == "thinkingTextSize" }
        val ba2 = b.sliders.first { it.key == "thinkingAlpha" }
        val bo2 = b.sliders.first { it.key == "thinkingOffset" }
        val be2 = b.duals.first { it.key == "thinkingEmpty" }

        if (sizeBar.setValueFromConfig(bizToSliderValue(c.thinkingTextSize, bs2.from, bs2.to), pendingTextSize)) {
            pendingTextSize = null
            EditSync.setText(sizeVal, String.format("%.1f", c.thinkingTextSize))
        }

        if (alphaBar.setValueFromConfig(bizToSliderValue(c.thinkingAlpha * 100f, ba2.from, ba2.to), pendingAlpha)) {
            pendingAlpha = null
            EditSync.setText(alphaVal, ((c.thinkingAlpha) * 100).toInt().toString())
        }

        if (offsetBar.setValueFromConfig(bizToSliderValue(c.thinkingOffset, bo2.from, bo2.to), pendingOffset)) {
            pendingOffset = null
            EditSync.setText(offsetVal, c.thinkingOffset.toInt().toString())
        }

        val lo = c.thinkingEmptyMin.coerceIn(be2.fromA.toInt(), be2.toA.toInt())
        val hi = c.thinkingEmptyMax.coerceIn(be2.fromB.toInt(), be2.toB.toInt())
        val safeLo = kotlin.math.round(minOf(lo, hi).toFloat().coerceIn(rangeSlider.valueFrom, rangeSlider.valueTo))
        val safeHi = kotlin.math.round(maxOf(lo, hi).toFloat().coerceIn(rangeSlider.valueFrom, rangeSlider.valueTo))
        // 待确认值保护：松手后滞后的在途旧发射（拖动中途位置）不写回双滑块，直到回流到用户最后拖到的 (lo,hi) 才放行，
        // 否则松手后双滑块会被旧发射拉着继续自动滑动（随机页双滑块因不常驻 collect 而不存在此问题）。
        if (pendingEmpty == null || (safeLo.toInt() == pendingEmpty!!.first && safeHi.toInt() == pendingEmpty!!.second)) {
            pendingEmpty = null
            rangeSlider.values = listOf(safeLo, safeHi)
            rangeVal.text = "${safeLo.toInt()} - ${safeHi.toInt()}"
        }

        // 统一抑制所有开关 listener：程序化回填不回写，避免并发改多个开关自激闪动
        suppressEnabled = true
        swFlashIn.isChecked = c.thinkingFlashIn
        swFlashOut.isChecked = c.thinkingFlashOut

        val bBg = b.sliders.first { it.key == "thinkingBgAlpha" }
        if (seekBgAlpha.setValueFromConfig(bizToSliderValue(c.thinkingBgAlpha.toFloat(), bBg.from, bBg.to), pendingBgAlpha)) {
            pendingBgAlpha = null
            EditSync.setText(editBgAlpha, c.thinkingBgAlpha.toString())
        }
        swBgEnabled.isChecked = c.thinkingBgEnabled
        currentBgColor = c.thinkingBgColor
        refreshBgColorDot()

        swThinkingEnabled.isChecked = c.thinkingEnabled
        setCardsEnabled(c.thinkingEnabled)
        suppressEnabled = false

        currentColor = c.thinkingColor
        refreshColorDot()
    }

    /** 恢复默认 / 重置系统：把阈值默认写回 PetConfig 并重绑滑块行程（来自/到界可能随重置系统变化）；
     *  数值/开关/颜色由 configFlow.collect → applyConfigToUi 自动刷新，彻底避免冷读旧快照。 */
    private fun reloadUiAfterReset() {
        lifecycleScope.launch {
            config.resetFromBounds(PetBounds(this@ThinkingSettingsActivity), SettingsPage.THINKING)
            refreshUI()
        }
    }

    /** 实际执行「恢复本页默认」：把阈值页记录的默认值写回 PetConfig 并刷新 UI。 */
    private fun resetDefaultNow() {
        reloadUiAfterReset()
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

    /** 实际执行「重置系统默认值与阈值」：bounds 回落系统出厂，再写回 PetConfig 并刷新本页（单协程顺序执行）。 */
    private fun resetSystemNow() {
        lifecycleScope.launch {
            PetBounds(this@ThinkingSettingsActivity).resetSystem(SettingsPage.THINKING)
            reloadUiAfterReset()
        }
    }

    /** 启用/禁用「显示」「闪回」两个模块卡片（碎碎念总开关关闭时禁用）。 */
    private fun setCardsEnabled(enabled: Boolean) {
        cardDisplay.isEnabled = enabled
        cardFlash.isEnabled = enabled
        cardBg.isEnabled = enabled
    }

    // 滑块绑定 + 滑块值回填。抽成方法，onCreate 与 onResume 复用，
    // 使从阈值页返回时滑块行程（最新上下界）即时热更新。
    private fun refreshUI() {
        // 重绑即放弃上一轮拖动在途值：清掉所有 pending，避免「恢复默认/重置系统」后 pending 死锁
        // （旧范围算出的 pending 永远追不平新范围算出的 v，锁死 applyConfigToUi 写回）。
        pendingTextSize = null
        pendingAlpha = null
        pendingOffset = null
        pendingBgAlpha = null
        pendingEmpty = null
        val bs = PetBounds(this).getBlocking(SettingsPage.THINKING)
        val bSize = bs.sliders.first { it.key == "thinkingTextSize" }
        val bAlpha = bs.sliders.first { it.key == "thinkingAlpha" }
        val bOffset = bs.sliders.first { it.key == "thinkingOffset" }
        val bEmpty = bs.duals.first { it.key == "thinkingEmpty" }

        // 字号：业务范围 bSize.from~bSize.to（默认 6-24），拖动时预览
        sizeBar.applyBizRange(bSize.from, bSize.to, bSize.default,
            formatter = { "%.1f".format(it) }) { s ->
            pendingTextSize = bizToSliderValue(s, bSize.from, bSize.to).toInt()
            EditSync.setText(sizeVal, String.format("%.1f", s))
            lifecycleScope.launch { config.update { it.copy(thinkingTextSize = s) } }
        }
        sizeBar.onUserTouch(onStart = { startPreview() }, onStop = { stopPreview() })
        EditSync.bind(
            sizeVal,
            parse = { it.toFloatOrNull() },
            clamp = { it.coerceIn(minOf(bSize.from, bSize.to), maxOf(bSize.from, bSize.to)) },
            format = { it.toInt().toString() },
            defaultValue = bSize.default
        ) { v ->
            sizeBar.value = bizToSliderValue(v, bSize.from, bSize.to)
            lifecycleScope.launch { config.update { it.copy(thinkingTextSize = v) } }
        }

        // 透明度：业务范围 0-100（百分比），config 存 0-1，需 /100 换算
        alphaBar.applyBizRange(bAlpha.from, bAlpha.to, bAlpha.default,
            formatter = { it.toInt().toString() }) { aPct ->
            pendingAlpha = bizToSliderValue(aPct, bAlpha.from, bAlpha.to).toInt()
            EditSync.setText(alphaVal, "%d%%".format(aPct.toInt()))
            lifecycleScope.launch { config.update { it.copy(thinkingAlpha = aPct / 100f) } }
        }
        alphaBar.onUserTouch(onStart = { startPreview() }, onStop = { stopPreview() })
        EditSync.bind(
            alphaVal,
            parse = { it.toFloatOrNull() },
            format = { it.toInt().toString() },
            clamp = { it.coerceIn(minOf(bAlpha.from, bAlpha.to), maxOf(bAlpha.from, bAlpha.to)) },
            defaultValue = bAlpha.default
        ) { v ->
            alphaBar.value = bizToSliderValue(v, bAlpha.from, bAlpha.to)
            lifecycleScope.launch { config.update { it.copy(thinkingAlpha = v / 100f) } }
        }

        // 背景透明度：业务范围 0-100（百分比），config 存 0-100（整数）
        val bBg = bs.sliders.first { it.key == "thinkingBgAlpha" }
        seekBgAlpha.applyBizRange(bBg.from, bBg.to, bBg.default,
            formatter = { it.toInt().toString() }) { aPct ->
            pendingBgAlpha = bizToSliderValue(aPct, bBg.from, bBg.to).toInt()
            EditSync.setText(editBgAlpha, "%d%%".format(aPct.toInt()))
            lifecycleScope.launch { config.update { it.copy(thinkingBgAlpha = aPct.toInt()) } }
        }
        seekBgAlpha.onUserTouch(onStart = { startPreview() }, onStop = { stopPreview() })
        EditSync.bind(
            editBgAlpha,
            parse = { it.toFloatOrNull() },
            format = { it.toInt().toString() },
            clamp = { it.coerceIn(minOf(bBg.from, bBg.to), maxOf(bBg.from, bBg.to)) },
            defaultValue = bBg.default
        ) { v ->
            seekBgAlpha.value = bizToSliderValue(v, bBg.from, bBg.to)
            lifecycleScope.launch { config.update { it.copy(thinkingBgAlpha = v.toInt()) } }
        }

        // 偏移：业务范围 bOffset.from~bOffset.to（默认 -200~300），拖动时显示碎碎念虚线辅助线 + 预览文字
        offsetBar.applyBlackStyle()
        offsetBar.trayGuideSlider(
            onShowPreview = { startPreview() },
            onHidePreview = { stopPreview() },
            bizFrom = bOffset.from,
            bizTo = bOffset.to
        ) { o: Float ->
            pendingOffset = bizToSliderValue(o, bOffset.from, bOffset.to).toInt()
            EditSync.setText(offsetVal, o.toInt().toString())
            lifecycleScope.launch { config.update { it.copy(thinkingOffset = o) } }
        }
        EditSync.bind(
            offsetVal,
            parse = { it.toFloatOrNull() },
            clamp = { it.coerceIn(minOf(bOffset.from, bOffset.to), maxOf(bOffset.from, bOffset.to)) },
            format = { it.toInt().toString() },
            defaultValue = bOffset.default
        ) { v ->
            offsetBar.value = bizToSliderValue(v, bOffset.from, bOffset.to)
            lifecycleScope.launch { config.update { it.copy(thinkingOffset = v) } }
        }

        // 空白时长：双滑块天然保证 lo <= hi（业务范围来自 bounds，默认 1-60 秒）
        rangeSlider.clearOnChangeListeners()
        // duals 已保证 上界 > 下界（最小跨度 1，见 BaseThresholdActivity.dualItem 钳制 + normalizeBounds），
        // 直接取 [fromA, toB] 即可；保留 maxOf(…, fromA+1f) 仅作极端退化兜底，不再用 ±1f 把范围塌成 2 单位。
        val rsFrom = bEmpty.fromA
        val rsTo = maxOf(bEmpty.toB, bEmpty.fromA + 1f)
        rangeSlider.valueFrom = rsFrom
        rangeSlider.valueTo = rsTo
        rangeSlider.addOnChangeListener { slider, _, fromUser ->
            val lo = slider.values[0].toInt()
            val hi = slider.values[1].toInt()
            rangeVal.text = "$lo - $hi"
            if (!fromUser) return@addOnChangeListener
            // 记录用户最后拖到的 (lo,hi)，挡住在途旧发射回流，避免松手后双滑块继续自动滑动
            pendingEmpty = lo to hi
            lifecycleScope.launch {
                config.update { it.copy(thinkingEmptyMin = lo, thinkingEmptyMax = hi) }
                PetService.instance?.requestTrayEmptyReset()
            }
        }
        // 关键：重绑后按最新阈值范围把「当前配置值」回填双滑块位置/数字（单滑块 applyBizRange 会定位，
        // 但双滑块走 boundGuideSlider/原生绑定不定位，需手动补）。否则「恢复默认/重置系统」后双滑块行程与
        // 位置不刷新。pendingEmpty 已在 refreshUI 开头清空，不会死锁 applyConfigToUi。
        val eCfg = config.getBlocking()
        val eLo = eCfg.thinkingEmptyMin.coerceIn(bEmpty.fromA.toInt(), bEmpty.toA.toInt())
        val eHi = eCfg.thinkingEmptyMax.coerceIn(bEmpty.fromB.toInt(), bEmpty.toB.toInt())
        val eSafeLo = kotlin.math.round(minOf(eLo, eHi).toFloat().coerceIn(rangeSlider.valueFrom, rangeSlider.valueTo))
        val eSafeHi = kotlin.math.round(maxOf(eLo, eHi).toFloat().coerceIn(rangeSlider.valueFrom, rangeSlider.valueTo))
        rangeSlider.values = listOf(eSafeLo, eSafeHi)
        rangeVal.text = "${eSafeLo.toInt()} - ${eSafeHi.toInt()}"

        // 数值/开关/颜色由 onCreate 的 configFlow.collect → applyConfigToUi 自动刷新，此处不再冷读回填。
    }

    override fun onResume() {
        super.onResume()
        refreshUI()
    }

    /** 刷新颜色入口行右侧的圆角色块（圆角 + 黑框） */
    private fun refreshColorDot() {
        colorDot?.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 6 * resources.displayMetrics.density
            setColor(currentColor)
            setStroke((1.5f * resources.displayMetrics.density).toInt(), 0xFF000000.toInt())
        }
    }

    private fun refreshBgColorDot() {
        bgColorDot?.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 6 * resources.displayMetrics.density
            setColor(currentBgColor)
            setStroke((1.5f * resources.displayMetrics.density).toInt(), 0xFF000000.toInt())
        }
    }

    private fun showColorPickerDialog() {
        openColorPicker(currentColor) { color ->
            currentColor = color
            refreshColorDot()
            lifecycleScope.launch { config.update { it.copy(thinkingColor = color) } }
        }
    }

    private fun showBgColorPickerDialog() {
        openColorPicker(currentBgColor) { color ->
            currentBgColor = color
            refreshBgColorDot()
            lifecycleScope.launch { config.update { it.copy(thinkingBgColor = color) } }
        }
    }

    /** 通用取色弹窗：候选色 + HSV 色轮 + 明暗滑条。取色即时回调 onApply，取消回滚到 initial。 */
    private fun openColorPicker(initial: Int, onApply: (Int) -> Unit) {
        if (colorDialog?.isShowing == true) return

        val view = layoutInflater.inflate(R.layout.dialog_color_picker, null)
        val wheel = view.findViewById<ColorWheelView>(R.id.color_wheel)
        val valueBar = view.findViewById<Slider>(R.id.value_bar)
        val preview = view.findViewById<View>(R.id.picker_preview)
        val hexText = view.findViewById<TextView>(R.id.picker_hex)
        val candidateRow = view.findViewById<LinearLayout>(R.id.candidate_row)

        // 打开前的颜色，用于「取消」回滚
        val originalColor = initial
        var tempColor = initial

        // 明暗渐变参考条：黑 → 当前色相饱和度的纯色（仅色相/饱和度变化时重建，避免拖动时频繁创建）
        val valueGradient = view.findViewById<View>(R.id.value_gradient)
        var lastPure = -1
        fun updateValueGradient(pure: Int) {
            if (pure == lastPure) return
            lastPure = pure
            valueGradient.background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.BLACK, pure)
            )
        }

        /** 应用临时颜色：写配置 + 刷新弹窗内预览 + 驱动浮窗预览 */
        fun applyTemp(color: Int) {
            onApply(color)
            preview.setBackgroundColor(color)
            hexText.text = String.format("#%06X", 0xFFFFFF and color)
            val hsv = FloatArray(3)
            Color.colorToHSV(color, hsv)
            updateValueGradient(Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], 1f)))
            // 取色期间保持浮窗预览可见（用户能直接在桌面看到颜色效果）
            startPreview()
        }

        // 候选色：6 个圆角黑框色块，横向等距排列
        val candidateViews = mutableListOf<View>()
        val size = (36 * resources.displayMetrics.density).toInt()
        val gap = (10 * resources.displayMetrics.density).toInt()
        presetColors.forEach { color ->
            val v = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size)
            }
            v.setOnClickListener {
                wheel.setColor(color)                     // 同步色轮指示器，便于微调
                valueBar.setValueFromConfig(wheel.getValue() * 100f)
                applyTemp(color)
                highlightCandidate(candidateViews, color)
            }
            candidateRow.addView(v)
            candidateViews.add(v)
            (v.layoutParams as LinearLayout.LayoutParams).marginEnd = gap
        }

        fun paintCandidate(v: View, color: Int, selected: Boolean) {
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 6 * resources.displayMetrics.density
                setColor(color)
                setStroke(
                    (if (selected) 3 else 1) * resources.displayMetrics.density.toInt(),
                    if (selected) 0xFF000000.toInt() else 0x33000000
                )
            }
        }

        fun refreshCandidates() {
            val idx = presetColors.indexOf(tempColor)
            candidateViews.forEachIndexed { i, v -> paintCandidate(v, presetColors[i], i == idx) }
        }
        // 初始回填：色轮 + 明暗 + 候选高亮
        wheel.setColor(tempColor)
        valueBar.setValueSafe(wheel.getValue() * 100f)
        preview.setBackgroundColor(tempColor)
        hexText.text = String.format("#%06X", 0xFFFFFF and tempColor)
        val initHsv = FloatArray(3)
        Color.colorToHSV(tempColor, initHsv)
        updateValueGradient(Color.HSVToColor(floatArrayOf(initHsv[0], initHsv[1], 1f)))
        refreshCandidates()

        // 色轮拖动取色
        wheel.onColorChanged = { color ->
            applyTemp(color)
            refreshCandidates()
        }

        // 明暗滑条：仅改明度，保留色轮上的色相与饱和度；气泡显示百分比
        valueBar.trackTouchState()
        valueBar.setLabelText { "%d%%".format(it.toInt()) }
        valueBar.onUserValueChange { v ->
            wheel.setValue(v / 100f)
            applyTemp(wheel.color)
            refreshCandidates()
        }

        // RGB 值标签（picker_hex）点击可编辑：弹出输入框，输入 #RGB / #RRGGBB 后
        // 同步色轮 + 明暗滑条 + 候选高亮，并实时应用。复用现有标签，不引入 EditText 控件。
        hexText.setOnClickListener {
            val input = android.widget.EditText(this).apply {
                setText(String.format("#%06X", 0xFFFFFF and tempColor))
                setSelection(text.length)
                inputType = android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                gravity = android.view.Gravity.CENTER
            }
            android.app.AlertDialog.Builder(this)
                .setTitle("输入 RGB 颜色")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定") { _, _ ->
                    val str = input.text.toString().trim().trimStart('#')
                    val parsed = when (str.length) {
                        3 -> try {
                            val r = str[0].toString().repeat(2).toInt(16)
                            val g = str[1].toString().repeat(2).toInt(16)
                            val b = str[2].toString().repeat(2).toInt(16)
                            Color.rgb(r, g, b)
                        } catch (_: Exception) { null }
                        6 -> try { Color.parseColor("#$str") } catch (_: Exception) { null }
                        else -> null
                    }
                    if (parsed != null) {
                        wheel.setColor(parsed)
                        valueBar.setValueSafe(wheel.getValue() * 100f)
                        applyTemp(parsed)
                        refreshCandidates()
                    }
                }
                .show()
        }

        // 注意：本 Activity 继承 ComponentActivity（非 AppCompatActivity），
        // 使用原生 android.app.AlertDialog 而非 AppCompat 版本，避免缺少 AppCompat 主题导致弹窗异常。
        val dialog = android.app.AlertDialog.Builder(this)
            .setView(view)
            .setNegativeButton("取消") { _, _ -> applyTemp(originalColor) }
            .setPositiveButton("确定") { _, _ -> /* 取色过程中已实时写入，直接保留 */ }
            .setOnCancelListener { applyTemp(originalColor) }
            .create()
        dialog.setOnDismissListener {
            stopPreview()
            colorDialog = null
        }
        colorDialog = dialog
        dialog.show()
        // 进入弹窗即开始预览，便于对照效果
        startPreview()
    }

    /** 高亮命中的候选色块（其余恢复细描边） */
    private fun highlightCandidate(views: List<View>, color: Int) {
        val idx = presetColors.indexOf(color)
        if (idx < 0) return
        views.forEachIndexed { i, v ->
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 6 * resources.displayMetrics.density
                setColor(presetColors[i])
                setStroke(
                    (if (i == idx) 3 else 1) * resources.displayMetrics.density.toInt(),
                    if (i == idx) 0xFF000000.toInt() else 0x33000000
                )
            }
        }
    }

    /** 获取系统真实状态栏高度（px），失败时回退 24dp */
    private fun getStatusBarHeight(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId)
        else (resources.displayMetrics.density * 24f).toInt()
    }

    /** 样式滑块（字号/透明度）：拖动开始时预览，松手结束预览 */
    private fun Slider.previewSlider(onChange: (Float) -> Unit) {
        onUserValueChange(onChange)
        onUserTouch(onStart = { startPreview() }, onStop = { stopPreview() })
    }

    // ===== 预览控制 =====

    private val previewHideRunnable = Runnable { PetService.instance?.hideTrayPreview() }

    /** 开始预览：取消可能挂起的自动隐藏，立即显示预览文案 */
    private fun startPreview() {
        previewHandler.removeCallbacks(previewHideRunnable)
        PetService.instance?.showTrayPreview()
    }

    /** 结束预览（松手时调用） */
    private fun stopPreview() {
        previewHandler.removeCallbacks(previewHideRunnable)
        PetService.instance?.hideTrayPreview()
    }

    /** 短暂预览（点击选色等无拖动场景）：显示后延时自动收起 */
    private fun showPreviewTemporarily() {
        previewHandler.removeCallbacks(previewHideRunnable)
        PetService.instance?.showTrayPreview()
        previewHandler.postDelayed(previewHideRunnable, PREVIEW_AUTO_HIDE_MS)
    }

    override fun onDestroy() {
        // 兜底：退出页面时确保预览结束、辅助线隐藏，避免预览文案常驻屏幕
        previewHandler.removeCallbacks(previewHideRunnable)
        PetService.instance?.hideTrayPreview()
        PetService.instance?.hideGuide()
        super.onDestroy()
    }

    /** dp → px */
    private fun dp(v: Float): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics
    ).toInt()
}
