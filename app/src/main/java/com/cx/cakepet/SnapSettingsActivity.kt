package com.cx.cakepet

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import android.widget.EditText
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.slider.Slider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// PetBounds 与 SliderHelper 同包，无需显式 import（com.cx.cakepet）

/**
 * 贴边边设置页：将设置页「贴边边」模块内容整段迁移而来。
 * 含：吸附边缘总开关（整行点击切换）/ 四边吸附 / 吸附距离（即 snapThreshold）。
 * 所有改动即时写入 DataStore，由 PetService 的 configFlow 监听同步到浮窗。
 */
class SnapSettingsActivity : AppCompatActivity() {

    // 点输入框以外区域即让当前输入框失焦，触发「失焦即提交」
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        commitInputOnTouchOutside(ev)
        return super.dispatchTouchEvent(ev)
    }

    private val config by lazy { PetConfig(this) }

    // 视图/开关提升为字段，供 refreshUI / onResume 复用
    private lateinit var swSnapEnabled: Switch
    private lateinit var swShowSnapLine: Switch
    private lateinit var swStop: Switch
    private lateinit var swSbottom: Switch
    private lateinit var swSleft: Switch
    private lateinit var swSright: Switch
    private lateinit var thresholdBar: Slider
    private lateinit var thresholdVal: EditText
    private var suppressEnabled = false
    // 记录总开关上一次状态，用于判断「用户手动从关到开」
    private var snapEnabledWasOn = false

    // 预览抑制标记：批量回填初始值时，避免触发四边开关的预览回调（一次性展示）
    private var suppressSnapPreview = false

    // 吸附距离滑块「待确认值」：记录用户最后拖到的滑块值（Int，0..100 滑块空间），用于挡住滞后的旧发射回流，
    // 避免松手后滑块/数字被在途旧值拉回（倒放 / 自动往返），并让「恢复默认/重置」不被旧发射覆盖。
    // 与通用设置页 SettingsActivity 同款机制。
    private var pendingThreshold: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_snap_settings)
        // 纯黑占位条填充状态栏高度，与标题栏黑底连成整体覆盖状态栏
        val spacer = findViewById<View>(R.id.status_bar_spacer)
        spacer.layoutParams.height = getStatusBarHeight()
        spacer.requestLayout()
        findViewById<ImageView>(R.id.iv_back).setOnClickListener { finish() }
        // 右上角“更多”图标：弹出菜单，含「默认值与阈值」「恢复用户默认」「重置系统默认值与阈值」
        val snapBtnMore = findViewById<ImageView>(R.id.btn_more)
        val snapMoreMenu = SettingsMoreMenu(
            anchor = snapBtnMore,
            onThreshold = { startActivity(Intent(this, SnapThresholdActivity::class.java)) },
            onResetUserDefault = { doResetDefault() },
            onResetSystem = { doResetSystem() }
        )
        snapBtnMore.setOnClickListener { snapMoreMenu.show() }

        swSnapEnabled = findViewById(R.id.sw_snap_enabled)
        swShowSnapLine = findViewById(R.id.sw_show_snap_line)
        swStop = findViewById(R.id.sw_stop)
        swSbottom = findViewById(R.id.sw_sbottom)
        swSleft = findViewById(R.id.sw_sleft)
        swSright = findViewById(R.id.sw_sright)
        thresholdBar = findViewById(R.id.snap_threshold_bar)
        thresholdVal = findViewById(R.id.snap_threshold_value)

        // 吸附边缘总开关：直接控制 snapEnabled，不影响子选项值
        swSnapEnabled.setOnCheckedChangeListener { _, b ->
            if (suppressEnabled) return@setOnCheckedChangeListener
            // 用户手动把总开关从「关」拨到「开」时，弹出吸附范围示意图
            if (b && !snapEnabledWasOn) showSnapGuideDialog()
            snapEnabledWasOn = b
            lifecycleScope.launch { config.update { it.copy(snapEnabled = b) } }
        }

        // 显示吸附线开关：控制吸附态时地面灰色细线是否显示，默认开
        swShowSnapLine.setOnCheckedChangeListener { _, b ->
            if (suppressSnapPreview) return@setOnCheckedChangeListener
            lifecycleScope.launch { config.update { it.copy(showSnapLine = b) } }
        }

        // 四边吸附开关：各自独立，点击时短暂预览对应边的吸附探头
        swStop.setOnCheckedChangeListener { _, b ->
            if (suppressSnapPreview) return@setOnCheckedChangeListener
            lifecycleScope.launch {
                config.update { it.copy(snapTop = b) }
                PetService.instance?.showSnapPreviewOnce(this@SnapSettingsActivity, PetService.SnapSide.TOP, b)
            }
        }
        swSbottom.setOnCheckedChangeListener { _, b ->
            if (suppressSnapPreview) return@setOnCheckedChangeListener
            lifecycleScope.launch {
                config.update { it.copy(snapBottom = b) }
                PetService.instance?.showSnapPreviewOnce(this@SnapSettingsActivity, PetService.SnapSide.BOTTOM, b)
            }
        }
        swSleft.setOnCheckedChangeListener { _, b ->
            if (suppressSnapPreview) return@setOnCheckedChangeListener
            lifecycleScope.launch {
                config.update { it.copy(snapLeft = b) }
                PetService.instance?.showSnapPreviewOnce(this@SnapSettingsActivity, PetService.SnapSide.LEFT, b)
            }
        }
        swSright.setOnCheckedChangeListener { _, b ->
            if (suppressSnapPreview) return@setOnCheckedChangeListener
            lifecycleScope.launch {
                config.update { it.copy(snapRight = b) }
                PetService.instance?.showSnapPreviewOnce(this@SnapSettingsActivity, PetService.SnapSide.RIGHT, b)
            }
        }

        // 吸附距离滑块：绑定 + 初始回填（抽成 refreshUI，onResume 复用实现热更新）
        refreshUI()

        // 常驻观察 configFlow：阈值页修改上下界/默认值、或本页复位/钳制后，数值·开关自动同步刷新，
        // 取代冷读 configFlow.first()（通用设置页同款机制）。
        lifecycleScope.launch {
            config.configFlow.collect { applyConfigToUi(it) }
        }

        // 「恢复默认设置」已移入右上角“更多”浮窗，逻辑封装为 doResetDefault()
    }

    /** 恢复本页默认设置（吸附边缘模块），默认值取阈值页设定，供右上角“更多”浮窗调用。先弹确认框。 */
    private fun doResetDefault() {
        showConfirmDialog(
            title = "恢复用户默认",
            bodyHtml = "将<b>本页</b>的所有设置项目恢复为当前默认值？",
            subText = "可在“默认值与阈值页”修改默认值",
            positiveText = "恢复"
        ) { resetDefaultNow() }
    }

    /** 把当前配置刷新到本页所有滑块/开关。供 configFlow.collect 持续调用，取代冷读 configFlow.first()。 */
    private suspend fun applyConfigToUi(c: PetConfigData) {
        val b = PetBounds(this@SnapSettingsActivity).getBlocking(SettingsPage.SNAP)
        val sb = b.sliders.first { it.key == "snapThreshold" }
        suppressSnapPreview = true
        suppressEnabled = true
        swSnapEnabled.isChecked = c.snapEnabled
        swShowSnapLine.isChecked = c.showSnapLine
        swStop.isChecked = c.snapTop
        swSbottom.isChecked = c.snapBottom
        swSleft.isChecked = c.snapLeft
        swSright.isChecked = c.snapRight
        suppressSnapPreview = false
        suppressEnabled = false
        if (thresholdBar.setValueFromConfig(bizToSliderValue(c.snapThreshold, sb.from, sb.to), pendingThreshold)) {
            pendingThreshold = null
            EditSync.setText(thresholdVal, c.snapThreshold.toInt().toString())
        }
    }

    /** 恢复默认 / 重置系统：把阈值默认写回 PetConfig 并重绑滑块行程；数值/开关由 configFlow.collect 自动刷新。 */
    private fun reloadUiAfterReset() {
        lifecycleScope.launch {
            config.resetFromBounds(PetBounds(this@SnapSettingsActivity), SettingsPage.SNAP)
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
            PetBounds(this@SnapSettingsActivity).resetSystem(SettingsPage.SNAP)
            reloadUiAfterReset()
        }
    }

    // 吸附距离滑块：绑定 + 初始回填。抽成方法，onCreate 与 onResume 复用，
    // 使从阈值页返回时滑块行程（来自 PetBounds 的最新上下界）即时热更新。
    private fun refreshUI() {
        // 吸附距离：业务范围来自 PetBounds（阈值页可改上下界），滑块内部 0..100 归一化
        val bs = PetBounds(this).getBlocking(SettingsPage.SNAP)
        val snapB = bs.sliders.first { it.key == "snapThreshold" }
        thresholdBar.boundGuideSlider(
            side = BoundSide.SNAP,
            bizFrom = snapB.from,
            bizTo = snapB.to,
            formatter = { it.toInt().toString() }
        ) { t: Float ->
            pendingThreshold = bizToSliderValue(t, snapB.from, snapB.to).toInt()
            EditSync.setText(thresholdVal, t.toInt().toString())
            lifecycleScope.launch { config.update { it.copy(snapThreshold = t) } }
        }
        // 关键：boundGuideSlider 重绑时不会定位滑块位置，必须在此按最新阈值范围把「当前配置值」同步回填滑块/数字。
        // 否则「恢复默认 / 重置系统」后滑块行程与位置不刷新；且一旦阈值范围被重置改变，
        // 旧范围算出的 pendingThreshold 永远追不平新范围算出的 v，会锁死 applyConfigToUi 的写回。
        // 清掉 pendingThreshold 也解除该死锁（重绑即放弃上一轮拖动在途值）。
        val cfg = config.getBlocking()
        pendingThreshold = null
        thresholdBar.value = bizToSliderValue(cfg.snapThreshold, snapB.from, snapB.to)
        EditSync.setText(thresholdVal, cfg.snapThreshold.toInt().toString())
        EditSync.bind(
            thresholdVal,
            parse = { it.toFloatOrNull() },
            format = { it.toInt().toString() },
            clamp = { it.coerceIn(minOf(snapB.from, snapB.to), maxOf(snapB.from, snapB.to)) },
            defaultValue = snapB.default
        ) { v ->
            thresholdBar.value = bizToSliderValue(v, snapB.from, snapB.to)
            lifecycleScope.launch { config.update { it.copy(snapThreshold = v) } }
        }
        // 数值/开关由 onCreate 的 configFlow.collect → applyConfigToUi 自动刷新，此处不再冷读回填。
    }

    override fun onResume() {
        super.onResume()
        refreshUI()
    }

    /**
     * 吸附范围示意图：用户手动开启「吸附边缘」总开关时弹出。
     * 用一个自定义 View 画出「屏幕外框 + 四边内侧 snapDist 虚线框」，
     * 直观表明宠物拖动到虚线框内会自动吸附到对应边缘。
     */
    private fun showSnapGuideDialog() {
        val view = SnapGuideView(this)
        AlertDialog.Builder(this)
            .setTitle("吸附边缘已开启")
            .setView(view)
            .setMessage("拖动宠物进入四边内侧的虚线框（吸附触发区）会自动吸附贴边。关闭总开关则完全不吸附。")
            .setPositiveButton("知道了", null)
            .show()
    }

    /** 吸附范围示意图 View：外框为屏幕，内缩虚线框为吸附触发范围 */
    private class SnapGuideView(context: android.content.Context) : android.view.View(context) {
        private val pad = (16 * resources.displayMetrics.density).toInt()
        private val inset = (28 * resources.displayMetrics.density).toInt()
        private val paintFrame = android.graphics.Paint().apply {
            color = 0xFF888888.toInt(); style = android.graphics.Paint.Style.STROKE
            strokeWidth = 2f * resources.displayMetrics.density; isAntiAlias = true
        }
        private val paintGuide = android.graphics.Paint().apply {
            color = 0xFF4A90E2.toInt(); style = android.graphics.Paint.Style.STROKE
            strokeWidth = 2f * resources.displayMetrics.density; isAntiAlias = true
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f, 6f), 0f)
        }
        override fun onMeasure(w: Int, h: Int) {
            val size = (200 * resources.displayMetrics.density).toInt()
            setMeasuredDimension(size, size)
        }
        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            val left = pad.toFloat(); val top = pad.toFloat()
            val right = (width - pad).toFloat(); val bottom = (height - pad).toFloat()
            // 屏幕外框
            canvas.drawRect(left, top, right, bottom, paintFrame)
            // 吸附触发区（内缩虚线框）
            canvas.drawRect(
                left + inset, top + inset, right - inset, bottom - inset, paintGuide
            )
        }
    }

    /** 获取系统真实状态栏高度（px），失败时回退 24dp */
    private fun getStatusBarHeight(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId)
        else (resources.displayMetrics.density * 24f).toInt()
    }
}
