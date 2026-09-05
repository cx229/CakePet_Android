package com.cx.cakepet

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.slider.Slider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * 显示-其他：活动范围（上下左右偏移）+ 键盘适应（输入法防误触 + 抬高偏移）。
 * 风格与碎碎念/随机等其他二级页保持一致（黑底状态栏占位 + 卡片）。
 * 偏移类滑块拖动时显示桌面边界判定线（PetService.showBoundGuide）。
 * 滑块业务范围来自 PetBounds（阈值页可改上下界）。
 */
class RangeSettingsActivity : AppCompatActivity() {

    // 点输入框以外区域即让当前输入框失焦，触发「失焦即提交」
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        commitInputOnTouchOutside(ev)
        return super.dispatchTouchEvent(ev)
    }

    private val config by lazy { PetConfig(this) }

    // 抬高偏移预览来源标记（与四边边界判定线共用引用计数体系，互不干扰）
    private val imeOwner = Any()

    // 视图引用提升为字段，供 refreshUI / onResume / 恢复默认 复用（避免 onResume 重绑监听时重复 findViewById）
    private lateinit var offTopBar: Slider
    private lateinit var offTopVal: EditText
    private lateinit var offBottomBar: Slider
    private lateinit var offBottomVal: EditText
    private lateinit var offLeftBar: Slider
    private lateinit var offLeftVal: EditText
    private lateinit var offRightBar: Slider
    private lateinit var offRightVal: EditText
    private lateinit var swImeAdapt: Switch
    private lateinit var swImeResetBottom: Switch
    private lateinit var swImeHide: Switch
    private lateinit var imeLiftBar: Slider
    private lateinit var imeLiftVal: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_range_settings)

        // 纯黑占位条填充状态栏高度，与标题栏黑底连成整体覆盖状态栏
        val spacer = findViewById<View>(R.id.status_bar_spacer)
        spacer.layoutParams.height = getStatusBarHeight()
        spacer.requestLayout()
        findViewById<ImageView>(R.id.iv_back).setOnClickListener { finish() }
        // 右上角“更多”图标：弹出菜单，含「默认值与阈值」「恢复用户默认」「重置系统默认值与阈值」
        val rangeBtnMore = findViewById<ImageView>(R.id.btn_more)
        val rangeMoreMenu = SettingsMoreMenu(
            anchor = rangeBtnMore,
            onThreshold = { startActivity(Intent(this, RangeThresholdActivity::class.java)) },
            onResetUserDefault = { doResetDefault() },
            onResetSystem = { doResetSystem() }
        )
        rangeBtnMore.setOnClickListener { rangeMoreMenu.show() }

        offTopBar = findViewById(R.id.off_top_bar)
        offTopVal = findViewById(R.id.off_top_value)
        offBottomBar = findViewById(R.id.off_bottom_bar)
        offBottomVal = findViewById(R.id.off_bottom_value)
        offLeftBar = findViewById(R.id.off_left_bar)
        offLeftVal = findViewById(R.id.off_left_value)
        offRightBar = findViewById(R.id.off_right_bar)
        offRightVal = findViewById(R.id.off_right_value)
        swImeAdapt = findViewById(R.id.sw_ime_adapt)
        swImeResetBottom = findViewById(R.id.sw_ime_reset_bottom)
        swImeHide = findViewById(R.id.sw_ime_hide)
        imeLiftBar = findViewById(R.id.ime_lift_bar)
        imeLiftVal = findViewById(R.id.ime_lift_value)

        // 偏移滑块绑定 + 初始回填（抽成 refreshUI，onResume 复用实现热更新）
        refreshUI()

        // 「恢复默认设置」已移入右上角“更多”浮窗，逻辑封装为 doResetDefault()
    }

    /** 恢复本页默认设置（活动范围四边偏移 + 键盘适应三项），默认值取阈值页设定。先弹确认框。 */
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
        runBlocking { config.resetFromBounds(PetBounds(this@RangeSettingsActivity), SettingsPage.RANGE) }
        val c = runBlocking { config.configFlow.first() }
        val b = PetBounds(this@RangeSettingsActivity).getBlocking(SettingsPage.RANGE)
        val bo = { key: String -> b.sliders.first { it.key == key } }
        suppressRefresh = true
        offTopBar.value = bizToSliderValue(c.offsetTop, bo("offsetTop").from, bo("offsetTop").to); EditSync.setText(offTopVal, c.offsetTop.toInt().toString())
        offBottomBar.value = bizToSliderValue(c.offsetBottom, bo("offsetBottom").from, bo("offsetBottom").to); EditSync.setText(offBottomVal, c.offsetBottom.toInt().toString())
        offLeftBar.value = bizToSliderValue(c.offsetLeft, bo("offsetLeft").from, bo("offsetLeft").to); EditSync.setText(offLeftVal, c.offsetLeft.toInt().toString())
        offRightBar.value = bizToSliderValue(c.offsetRight, bo("offsetRight").from, bo("offsetRight").to); EditSync.setText(offRightVal, c.offsetRight.toInt().toString())
        swImeAdapt.isChecked = c.imeAdapt
        swImeResetBottom.isChecked = c.imeResetBottomOffset
        swImeHide.isChecked = c.imeHide
        imeLiftBar.value = bizToSliderValue(c.imeLiftOffset, bo("imeLiftOffset").from, bo("imeLiftOffset").to); EditSync.setText(imeLiftVal, c.imeLiftOffset.toInt().toString())
        suppressRefresh = false
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

    /** 实际执行「重置系统默认值与阈值」：bounds 回落系统出厂并联动刷新本页（重绑滑块行程到新阈值）。 */
    private fun resetSystemNow() {
        runBlocking {
            PetBounds(this@RangeSettingsActivity).resetSystem(SettingsPage.RANGE)
            config.resetFromBounds(PetBounds(this@RangeSettingsActivity), SettingsPage.RANGE)
        }
        // 关键：必须走 refreshUI 重绑滑块行程（setupOffset 闭包里的 from/to），
        // 否则滑块仍按「先在阈值页改过的旧上下界」映射，重置后范围不更新、显示异常。
        refreshUI()
    }

    // 四边边界判定线来源标记（所有偏移滑块共用，单指拖动下安全）
    private val boundOwner = Any()

    // 恢复默认设置时，程序化回填滑块/开关值，需抑制监听触发（避免误显示边界/键盘预览）
    private var suppressRefresh = false

    /**
     * 单个偏移滑块绑定：回填初始值 + 拖动时写配置 + 刷新边界判定线。
     * 滑块内部 0..100 归一化，业务范围由 bounds 决定（阈值页可改）。
     */
    private fun setupOffset(
        bar: Slider,
        valueView: EditText,
        b: SliderBound,
        initial: Float,
        activeSide: Int,
        applyBoundGuide: (Int) -> Unit,
        writeConfig: (Float) -> Unit
    ) {
        bar.apply {
            valueFrom = 0f
            valueTo = 100f
            stepSize = 1f
            applyBlackStyle()
            setLabelText { v -> bar.bizValue(b).toInt().toString() }
            value = bizToSliderValue(initial, b.from, b.to)
        }
        EditSync.setText(valueView, initial.toInt().toString())
        bar.addOnChangeListener { _, v, _ ->
            if (suppressRefresh) return@addOnChangeListener
            val o = b.from + v / 100f * (b.to - b.from)
            EditSync.setText(valueView, o.toInt().toString())
            applyBoundGuide(activeSide)
            writeConfig(o)
        }
        bar.trackTouchState()
        bar.onUserTouch(
            onStart = { applyBoundGuide(activeSide) },
            onStop = { PetService.instance?.releaseGuide(boundOwner) }
        )
        // 输入框 → 滑块/配置 同步（仅更新滑块与配置，不触发边界辅助线显示）
        EditSync.bind(
            valueView,
            parse = { it.toFloatOrNull() },
            format = { it.toInt().toString() },
            clamp = { it.coerceIn(minOf(b.from, b.to), maxOf(b.from, b.to)) },
            defaultValue = b.default
        ) { v ->
            bar.value = bizToSliderValue(v, b.from, b.to)
            writeConfig(v)
        }
    }

    /** 滑块 0..100 值换算为业务值 */
    private fun Slider.bizValue(b: SliderBound): Float = b.from + value / 100f * (b.to - b.from)

    /** 获取系统真实状态栏高度（px），失败时回退 24dp */
    private fun getStatusBarHeight(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId)
        else (resources.displayMetrics.density * 24f).toInt()
    }

    override fun onDestroy() {
        // 兜底：退出页面时确保边界判定线、键盘预览都已释放，避免常驻屏幕
        PetService.instance?.releaseGuide(boundOwner)
        PetService.instance?.releaseGuide(imeOwner)
        super.onDestroy()
    }

    // 偏移滑块绑定 + 初始回填。抽成方法，onCreate 与 onResume 复用，
    // 使从阈值页返回时滑块行程（最新上下界）即时热更新。
    // 重绑前 clear 旧监听器，避免 addOnChangeListener 叠加。
    private fun refreshUI() {
        val bs = PetBounds(this).getBlocking(SettingsPage.RANGE)
        val bOf = { key: String -> bs.sliders.first { it.key == key } }

        // 拖动四边偏移时，根据四个滑块当前业务值刷新桌面边界判定线
        val applyBoundGuide: (Int) -> Unit = { side ->
            PetService.instance?.showBoundGuide(
                boundOwner,
                offTopBar.bizValue(bOf("offsetTop")),
                offBottomBar.bizValue(bOf("offsetBottom")),
                offLeftBar.bizValue(bOf("offsetLeft")),
                offRightBar.bizValue(bOf("offsetRight")),
                activeSide = side
            )
        }

        val cfg = runBlocking { config.configFlow.first() }

        listOf(offTopBar, offBottomBar, offLeftBar, offRightBar, imeLiftBar).forEach {
            it.clearOnChangeListeners()
            it.clearOnSliderTouchListeners()
        }

        setupOffset(offTopBar, offTopVal, bOf("offsetTop"), cfg.offsetTop,
            2, applyBoundGuide) { v -> runBlocking { config.update { it.copy(offsetTop = v) } } }
        setupOffset(offBottomBar, offBottomVal, bOf("offsetBottom"), cfg.offsetBottom,
            3, applyBoundGuide) { v -> runBlocking { config.update { it.copy(offsetBottom = v) } } }
        setupOffset(offLeftBar, offLeftVal, bOf("offsetLeft"), cfg.offsetLeft,
            0, applyBoundGuide) { v -> runBlocking { config.update { it.copy(offsetLeft = v) } } }
        setupOffset(offRightBar, offRightVal, bOf("offsetRight"), cfg.offsetRight,
            1, applyBoundGuide) { v -> runBlocking { config.update { it.copy(offsetRight = v) } } }

        swImeAdapt.isChecked = cfg.imeAdapt
        swImeAdapt.setOnCheckedChangeListener { _, b ->
            if (suppressRefresh) return@setOnCheckedChangeListener
            runBlocking { config.update { it.copy(imeAdapt = b) } }
        }

        swImeResetBottom.isChecked = cfg.imeResetBottomOffset
        swImeResetBottom.setOnCheckedChangeListener { _, b ->
            if (suppressRefresh) return@setOnCheckedChangeListener
            runBlocking { config.update { it.copy(imeResetBottomOffset = b) } }
        }

        swImeHide.isChecked = cfg.imeHide
        swImeHide.setOnCheckedChangeListener { _, b ->
            if (suppressRefresh) return@setOnCheckedChangeListener
            runBlocking { config.update { it.copy(imeHide = b) } }
        }

        // 抬高偏移：业务范围来自 PetBounds，拖动时显示键盘预览 + 抬高偏移线。
        // 与四边偏移滑块走完全相同的引用计数体系（showBoundGuide(imeOwner, imeLift=...) /
        // releaseGuide(imeOwner)），不再搞独立通道，彻底消除与边界滑块互相误杀导致的虚晃。
        val imeB = bOf("imeLiftOffset")
        val applyImeGuide: () -> Unit = {
            PetService.instance?.showBoundGuide(imeOwner, imeLift = imeLiftBar.bizValue(imeB))
        }
        imeLiftBar.apply {
            applyBlackStyle()
            valueFrom = 0f
            valueTo = 100f
            stepSize = 1f
            // 先设初始值（此时监听器尚未绑定，避免程序化赋值误触发回写/弹辅助线）
            value = bizToSliderValue(cfg.imeLiftOffset, imeB.from, imeB.to)
            // 浮窗显示真实抬高偏移量，而非 0..100 的滑块百分比
            setLabelText { raw -> (imeB.from + raw / 100f * (imeB.to - imeB.from)).toInt().toString() }
            onUserValueChange { v ->
                val biz = imeB.from + v / 100f * (imeB.to - imeB.from)
                // 与其它偏移滑块保持一致：拖动时实时刷新数值文本（之前漏掉，导致数值不跟手）
                EditSync.setText(imeLiftVal, biz.toInt().toString())
                runBlocking { config.update { it.copy(imeLiftOffset = biz) } }
                applyImeGuide()
            }
            onUserTouch(
                onStart = { applyImeGuide() },
                onStop = { PetService.instance?.releaseGuide(imeOwner) }
            )
        }
        EditSync.setText(imeLiftVal, cfg.imeLiftOffset.toInt().toString())
        EditSync.bind(
            imeLiftVal,
            parse = { it.toFloatOrNull() },
            format = { it.toInt().toString() },
            clamp = { it.coerceIn(minOf(imeB.from, imeB.to), maxOf(imeB.from, imeB.to)) },
            defaultValue = imeB.default
        ) { v ->
            imeLiftBar.value = bizToSliderValue(v, imeB.from, imeB.to)
            runBlocking { config.update { it.copy(imeLiftOffset = v) } }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUI()
    }
}
