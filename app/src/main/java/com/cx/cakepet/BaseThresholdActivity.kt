package com.cx.cakepet

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.view.MotionEvent
import android.graphics.drawable.GradientDrawable
import androidx.appcompat.app.AppCompatActivity
import com.cx.cakepet.databinding.ActivityThresholdSettingsBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * 阈值设置页基类：仅承载「通用 UI 框架 + 渲染引擎 + 保存工具」，
 * 不含任何 page 业务数据/分支。子类只需 override [page] 与 [pageTitleText]，
 * 各自是独立 Activity（在 Manifest 注册），互不继承、互不共用业务。
 */
abstract class BaseThresholdActivity : AppCompatActivity() {

    // 点输入框以外区域即让当前输入框失焦，触发「失焦即提交」
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        commitInputOnTouchOutside(ev)
        return super.dispatchTouchEvent(ev)
    }

    /** 子类指定自己的设置页标识。 */
    protected abstract val page: SettingsPage

    /** 子类指定标题前缀（如「通用」「吸附」）。 */
    protected abstract val pageTitleText: String

    protected lateinit var binding: ActivityThresholdSettingsBinding
    protected lateinit var petBounds: PetBounds
    protected lateinit var petConfig: PetConfig
    protected var current: PageBounds = PageBounds()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityThresholdSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        petBounds = PetBounds(this)
        petConfig = PetConfig(this)

        // 与设置页一致：用占位条填满状态栏高度，标题栏黑底与状态栏连成整体覆盖状态栏
        binding.statusBarSpacer.layoutParams.height = getStatusBarHeight()
        binding.statusBarSpacer.requestLayout()

        binding.tvTitle.text = "$pageTitleText · 阈值设置"
        binding.ivBack.setOnClickListener { finish() }
        binding.btnResetSystem.setOnClickListener { resetSystem() }

        current = petBounds.getBlocking(page)
        render()
    }

    /** 获取系统真实状态栏高度（px），失败时回退 24dp（与设置页一致）。 */
    private fun getStatusBarHeight(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId)
        else (resources.displayMetrics.density * 24f).toInt()
    }

    // ===== 渲染（纯 UI 引擎，不区分 page） =====
    // 按「所属设置页的模块(group)」分组渲染，与设置页的视觉结构完全一致：
    // 每个模块 = 分隔线 + 标题(include_module_header) + 卡片(card_bg) 包裹的内容。
    // 模块顺序、组内顺序严格对齐设置页，由 PetBounds 的 order 字段决定。
    // 样式全部复用设置页已有 style / drawable，代码里不再手抄任何样式数值。
    protected fun render() {
        binding.container.removeAllViews()
        // 开关阈值项为 live 配置，直接读实时 PetConfig 显示（不依赖 pet_bounds 的 default）
        val liveCfg = runBlocking { petConfig.configFlow.first() }
        val order = current.order.ifEmpty {
            // 兜底：无 order 时按 group 首次出现顺序
            current.sliders.map { it.key } + current.duals.map { it.key } + current.switches.map { it.key }
        }
        // 建立 key -> 项 的索引
        val sliderMap = current.sliders.withIndex().associate { it.value.key to it }
        val dualMap = current.duals.withIndex().associate { it.value.key to it }
        val switchMap = current.switches.withIndex().associate { it.value.key to it }
        val colorMap = current.colors.withIndex().associate { it.value.key to it }

        // 模块顺序 = order 中首次出现的 group 顺序
        val groupOrder = mutableListOf<String>()
        order.forEach { key ->
            val g = (sliderMap[key]?.value?.group
                ?: dualMap[key]?.value?.group
                ?: switchMap[key]?.value?.group
                ?: colorMap[key]?.value?.group) ?: return@forEach
            if (g !in groupOrder) groupOrder.add(g)
        }

        // 卡片上下 padding：与对应设置页保持一致
        // 通用页(MAIN)卡片 padding=16dp；其余子页(范围/吸附/碎碎念/随机)卡片 padding=20dp
        val cardPadV = 16

        var first = true
        groupOrder.forEach { g ->
            // 非首模块前：inflate 复用 include_module_header（分隔线 + 标题），与设置页结构完全一致
            val header = LayoutInflater.from(this)
                .inflate(R.layout.include_module_header, binding.container, false)
            // 首模块不显示分隔线（设置页首模块也无前置分隔线）
            val sep = header.findViewById<View>(R.id.module_sep)
            sep.visibility = if (first) View.GONE else View.VISIBLE
            header.findViewById<TextView>(R.id.tv_module_title).text = g
            binding.container.addView(header)
            first = false

            // 卡片容器：包裹该模块的全部内容，直接复用设置页的 card_bg + padding
            // item 内部用 layout_margin 提供左右内边距（与设置页 item 写法一致），
            // 因此卡片只需设置上下 padding，左右由 margin=16 承担。
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = getDrawable(R.drawable.card_bg)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(16, 0, 16, 0) }
                setPadding(0, cardPadV, 0, cardPadV)
            }
            order.forEach { key ->
                when {
                    sliderMap[key]?.value?.group == g -> {
                        val (idx, s) = sliderMap.getValue(key)
                        card.addView(sliderItem(s, idx))
                    }
                    dualMap[key]?.value?.group == g -> {
                        val (idx, d) = dualMap.getValue(key)
                        card.addView(dualItem(d, idx))
                    }
                    switchMap[key]?.value?.group == g -> {
                        val (idx, sw) = switchMap.getValue(key)
                        card.addView(switchItem(sw, idx, liveCfg))
                    }
                    colorMap[key]?.value?.group == g -> {
                        val (idx, c) = colorMap.getValue(key)
                        card.addView(colorItem(c, idx))
                    }
                }
            }
            // 模块内：首项清上边距、末项清下边距，避免与卡片 padding 叠加（与设置页一致）
            if (card.childCount > 0) {
                (card.getChildAt(0).layoutParams as ViewGroup.MarginLayoutParams).topMargin = 0
                (card.getChildAt(card.childCount - 1).layoutParams as ViewGroup.MarginLayoutParams).bottomMargin = 0
            }
            binding.container.addView(card)
        }
    }

    private fun sliderItem(s: SliderBound, index: Int): View {
        val v = layoutInflater.inflate(R.layout.item_slider_bound, binding.container, false)
        v.findViewById<TextView>(R.id.tv_label).text = s.label
        val etMin = v.findViewById<EditText>(R.id.et_min)
        val etDef = v.findViewById<EditText>(R.id.et_default)
        val etMax = v.findViewById<EditText>(R.id.et_max)

        /** 把当前数据写回三个输入框（联动后统一刷新显示）。 */
        fun refresh() {
            val cur = current.sliders[index]
            etMin.setText(fmt(cur.from)); etMax.setText(fmt(cur.to)); etDef.setText(fmt(cur.default))
        }
        refresh()

        etMin.onLostFocus { txt ->
            val (ok, vv) = parseInRange(txt, s.hardMin, s.hardMax) ?: return@onLostFocus
            if (!ok) { Toast.makeText(this, "${s.label}最小值需在 ${fmt(s.hardMin)}~${fmt(s.hardMax)}", Toast.LENGTH_SHORT).show(); refresh(); return@onLostFocus }
            val cur = current.sliders[index]
            val newFrom = vv
            // 最小 > 最大：把最大拉到与最小一致（联动修正）
            val newTo = if (cur.to < newFrom) newFrom else cur.to
            current = current.copy(sliders = current.sliders.toMutableList().also { it[index] = cur.copy(from = newFrom, to = newTo) })
            commitOne()
            refresh()
        }
        etMax.onLostFocus { txt ->
            val (ok, vv) = parseInRange(txt, s.hardMin, s.hardMax) ?: return@onLostFocus
            if (!ok) { Toast.makeText(this, "${s.label}最大值需在 ${fmt(s.hardMin)}~${fmt(s.hardMax)}", Toast.LENGTH_SHORT).show(); refresh(); return@onLostFocus }
            val cur = current.sliders[index]
            val newTo = vv
            // 最大 < 最小：把最小拉到与最大一致（联动修正）
            val newFrom = if (cur.from > newTo) newTo else cur.from
            current = current.copy(sliders = current.sliders.toMutableList().also { it[index] = cur.copy(from = newFrom, to = newTo) })
            commitOne()
            refresh()
        }
        etDef.onLostFocus { txt ->
            val (ok, vv) = parseInRange(txt, s.hardMin, s.hardMax) ?: return@onLostFocus
            if (!ok) { Toast.makeText(this, "${s.label}默认值需在 ${fmt(s.hardMin)}~${fmt(s.hardMax)}", Toast.LENGTH_SHORT).show(); refresh(); return@onLostFocus }
            val cur = current.sliders[index]
            current = current.copy(sliders = current.sliders.toMutableList().also { it[index] = cur.copy(default = vv) })
            commitOne()
            refresh()
        }
        return v
    }

    private fun dualItem(d: DualSliderBound, index: Int): View {
        val v = layoutInflater.inflate(R.layout.item_dual_bound, binding.container, false)
        v.findViewById<TextView>(R.id.tv_label).text = d.label
        val etMin = v.findViewById<EditText>(R.id.et_min)
        val etDefA = v.findViewById<EditText>(R.id.et_def_a)
        val etDefB = v.findViewById<EditText>(R.id.et_def_b)
        val etMax = v.findViewById<EditText>(R.id.et_max)

        /** 把当前数据写回四个输入框（下界=min(A,B下限)，上界=max(A,B上限)）。 */
        fun refresh() {
            val cur = current.duals[index]
            etMin.setText(fmt(minOf(cur.fromA, cur.fromB)))
            etMax.setText(fmt(maxOf(cur.toA, cur.toB)))
            etDefA.setText(fmt(cur.defaultA))
            etDefB.setText(fmt(cur.defaultB))
        }
        refresh()

        // 下界：统一约束 A/B 两组下限（fromA=fromB），并钳制上限/默认值不小于它
        etMin.onLostFocus { t ->
            val r = parseInRange(t, d.hardMin, d.hardMax) ?: return@onLostFocus
            if (!r.first) { Toast.makeText(this, "下界越界", Toast.LENGTH_SHORT).show(); refresh(); return@onLostFocus }
            val c = current.duals[index]
            // 保证 下界 < 上界（最小跨度 1）：写入值≥上界时钳到「上界-1」，避免范围塌缩与死循环
            val hi = maxOf(c.toA, c.toB)
            val newLo = if (r.second >= hi) hi - 1f else r.second
            current = current.copy(duals = current.duals.toMutableList().also {
                it[index] = c.copy(
                    fromA = newLo, fromB = newLo,
                    toA = maxOf(c.toA, newLo), toB = maxOf(c.toB, newLo),
                    defaultA = c.defaultA.coerceAtLeast(newLo),
                    defaultB = c.defaultB.coerceAtLeast(newLo)
                )
            })
            commitOne(); refresh()
        }
        // 左值默认（A 组默认）：钳制在 [下界, 上界]
        etDefA.onLostFocus { t ->
            val r = parseInRange(t, d.hardMin, d.hardMax) ?: return@onLostFocus
            if (!r.first) { Toast.makeText(this, "左值默认越界", Toast.LENGTH_SHORT).show(); refresh(); return@onLostFocus }
            val c = current.duals[index]
            val lo = minOf(c.fromA, c.fromB); val hi = maxOf(c.toA, c.toB)
            current = current.copy(duals = current.duals.toMutableList().also {
                it[index] = c.copy(defaultA = r.second.coerceIn(lo, hi))
            })
            commitOne(); refresh()
        }
        // 右值默认（B 组默认）：钳制在 [下界, 上界]
        etDefB.onLostFocus { t ->
            val r = parseInRange(t, d.hardMin, d.hardMax) ?: return@onLostFocus
            if (!r.first) { Toast.makeText(this, "右值默认越界", Toast.LENGTH_SHORT).show(); refresh(); return@onLostFocus }
            val c = current.duals[index]
            val lo = minOf(c.fromA, c.fromB); val hi = maxOf(c.toA, c.toB)
            current = current.copy(duals = current.duals.toMutableList().also {
                it[index] = c.copy(defaultB = r.second.coerceIn(lo, hi))
            })
            commitOne(); refresh()
        }
        // 上界：统一约束 A/B 两组上限（toA=toB），并钳制下限/默认值不大于它
        etMax.onLostFocus { t ->
            val r = parseInRange(t, d.hardMin, d.hardMax) ?: return@onLostFocus
            if (!r.first) { Toast.makeText(this, "上界越界", Toast.LENGTH_SHORT).show(); refresh(); return@onLostFocus }
            val c = current.duals[index]
            // 保证 上界 > 下界（最小跨度 1）：写入值≤下界时钳到「下界+1」，避免范围塌缩与死循环
            val lo = minOf(c.fromA, c.fromB)
            val newHi = if (r.second <= lo) lo + 1f else r.second
            current = current.copy(duals = current.duals.toMutableList().also {
                it[index] = c.copy(
                    toA = newHi, toB = newHi,
                    fromA = minOf(c.fromA, newHi), fromB = minOf(c.fromB, newHi),
                    defaultA = c.defaultA.coerceAtMost(newHi),
                    defaultB = c.defaultB.coerceAtMost(newHi)
                )
            })
            commitOne(); refresh()
        }
        return v
    }

    // 开关阈值项为 live 配置：显示直接读 PetConfig，切换直接写 PetConfig，
    // 不再经由 pet_bounds 的 default↔live 回灌（那是「键盘适应」等模块默认/重置失效的根因）。
    private fun switchItem(sw: SwitchBound, index: Int, liveCfg: PetConfigData): View {
        val v = layoutInflater.inflate(R.layout.item_switch_bound, binding.container, false)
        v.findViewById<TextView>(R.id.tv_label).text = sw.label
        val swView = v.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.sw_value)
        swView.isChecked = liveSwitchFromConfig(sw.key, liveCfg) ?: sw.default
        swView.setOnCheckedChangeListener { _, isChecked ->
            runBlocking { petConfig.update { setLiveSwitch(it, sw.key, isChecked) } }
        }
        return v
    }

    private fun colorItem(c: ColorBound, index: Int): View {
        val v = layoutInflater.inflate(R.layout.item_color_bound, binding.container, false)
        v.findViewById<TextView>(R.id.tv_label).text = c.label
        val swatch = v.findViewById<View>(R.id.view_color)
        fun paint() {
            val cur = current.colors[index]
            swatch.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 6f * resources.displayMetrics.density
                setColor(cur.default)
                setStroke((1.5f * resources.displayMetrics.density).toInt(), 0xFF000000.toInt())
            }
        }
        paint()
        v.setOnClickListener {
            openColorPresetDialog(c.presets, c.default) { picked ->
                val cur = current.colors[index]
                current = current.copy(colors = current.colors.toMutableList().also { it[index] = cur.copy(default = picked) })
                paint()
                commitOne()
            }
        }
        return v
    }

    private var colorPresetDialog: android.app.AlertDialog? = null
    private fun openColorPresetDialog(presets: List<Int>, current: Int, onPick: (Int) -> Unit) {
        if (colorPresetDialog?.isShowing == true) return
        val dm = resources.displayMetrics.density
        val size = (44f * dm).toInt()
        val gap = (12f * dm).toInt()
        val grid = GridLayout(this).apply {
            columnCount = 3
            val pad = (16f * dm).toInt()
            setPadding(pad, pad, pad, pad)
        }
        presets.forEach { col ->
            val cell = View(this)
            val lp = GridLayout.LayoutParams()
            lp.width = size
            lp.height = size
            lp.setMargins(gap / 2, gap / 2, gap / 2, gap / 2)
            cell.layoutParams = lp
            cell.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8f * dm
                setColor(col)
                setStroke((1.5f * dm).toInt(), 0xFF000000.toInt())
            }
            cell.setOnClickListener {
                onPick(col)
                colorPresetDialog?.dismiss()
            }
            grid.addView(cell)
        }
        colorPresetDialog = android.app.AlertDialog.Builder(this)
            .setTitle("选择背景颜色")
            .setView(grid)
            .setNegativeButton("取消", null)
            .create()
        colorPresetDialog?.show()
    }

    // ===== 保存：每次改动即时落盘 =====
    protected fun commitOne() {
        current = normalizeBounds(current)
        runBlocking { petBounds.updateBounds(page, current) }
        runBlocking { coerceConfigToBounds(page) }
    }

    /**
     * 规范化：保证每个 default 落在各自的 [from,to] 内，双滑块的 defaultA/defaultB
     * 分别落在 [fromA,toA] / [fromB,toB] 内。避免用户输入使默认值小于/大于上下界。
     */
    private fun normalizeBounds(b: PageBounds): PageBounds {
        val sliders = b.sliders.map { s ->
            val from = s.from.coerceAtMost(s.to)
            val to = s.to.coerceAtLeast(s.from)
            val def = s.default.coerceIn(from, to)
            s.copy(from = from, to = to, default = def)
        }
        val duals = b.duals.map { d ->
            // 单组内部规整
            val fromA = d.fromA.coerceAtMost(d.toA)
            val toA = d.toA.coerceAtLeast(d.fromA)
            val fromB = d.fromB.coerceAtMost(d.toB)
            val toB = d.toB.coerceAtLeast(d.fromB)
            // 四输入框模型下：下界 = min(A,B 下限)，上界 = max(A,B 上限)，并强制对称与最小跨度 1，
            // 保证 refreshUI 取的 [fromA, toB] 严格递增，杜绝双滑块范围塌缩/死循环。
            val lo = minOf(fromA, fromB)
            val hi = maxOf(toA, toB, lo + 1f)
            d.copy(
                fromA = lo, toA = hi, defaultA = d.defaultA.coerceIn(lo, hi),
                fromB = lo, toB = hi, defaultB = d.defaultB.coerceIn(lo, hi)
            )
        }
        return b.copy(sliders = sliders, duals = duals)
    }

    private fun resetSystem() {
        showConfirmDialog(
            title = "重置系统阈值",
            bodyHtml = "将<b>本页</b>的默认值/阈值均重置为系统预设？",
            subText = "仅修改默认值与阈值，当前值视情况变化",
            positiveText = "重置"
        ) { resetSystemNow() }
    }

    /** 实际执行「重置系统阈值」：bounds 回落系统出厂；数值阈值夹紧；开关/颜色统一经 resetFromBounds 恢复出厂。
     * 与设置页「恢复默认/重置系统」走完全相同的通用逻辑，IME 等开关不再特殊。 */
    private fun resetSystemNow() {
        runBlocking {
            petBounds.resetSystem(page)
            coerceConfigToBounds(page)
            petConfig.resetFromBounds(petBounds, page)
        }
        current = petBounds.getBlocking(page)
        render()
        Toast.makeText(this, "已恢复系统默认阈值", Toast.LENGTH_SHORT).show()
    }

    protected suspend fun coerceConfigToBounds(p: SettingsPage) {
        val b = runBlocking { petBounds.boundsFlow(p).first() }
        petConfig.update { c ->
            var nc = c
            b.sliders.forEach { s ->
                nc = when (s.key) {
                    "scale" -> nc.copy(scale = nc.scale.coerceIn(s.from, s.to))
                    "alpha" -> nc.copy(alpha = (nc.alpha * 100f).coerceIn(s.from, s.to) / 100f)
                    "gravity" -> nc.copy(gravity = nc.gravity.coerceIn(s.from, s.to))
                    "maxSpeed" -> nc.copy(maxSpeed = nc.maxSpeed.coerceIn(s.from, s.to))
                    "rebound" -> nc.copy(reboundRatio = nc.reboundRatio.coerceIn(s.from, s.to))
                    "petCount" -> nc.copy(petCount = nc.petCount.coerceIn(s.from.toInt(), s.to.toInt()))
                    "snapThreshold" -> nc.copy(snapThreshold = nc.snapThreshold.coerceIn(s.from, s.to))
                    "offsetTop" -> nc.copy(offsetTop = nc.offsetTop.coerceIn(s.from, s.to))
                    "offsetBottom" -> nc.copy(offsetBottom = nc.offsetBottom.coerceIn(s.from, s.to))
                    "offsetLeft" -> nc.copy(offsetLeft = nc.offsetLeft.coerceIn(s.from, s.to))
                    "offsetRight" -> nc.copy(offsetRight = nc.offsetRight.coerceIn(s.from, s.to))
                    "imeLiftOffset" -> nc.copy(imeLiftOffset = nc.imeLiftOffset.coerceIn(s.from, s.to))
                    "thinkingTextSize" -> nc.copy(thinkingTextSize = nc.thinkingTextSize.coerceIn(s.from, s.to))
                    "thinkingAlpha" -> nc.copy(thinkingAlpha = (nc.thinkingAlpha * 100f).coerceIn(s.from, s.to) / 100f)
                    "thinkingOffset" -> nc.copy(thinkingOffset = nc.thinkingOffset.coerceIn(s.from, s.to))
                    "thinkingBgAlpha" -> nc.copy(thinkingBgAlpha = nc.thinkingBgAlpha.coerceIn(s.from.toInt(), s.to.toInt()))
                    else -> nc
                }
            }
            b.duals.forEach { d ->
                when (d.key) {
                    "thinkingEmpty" -> {
                        nc = nc.copy(
                            thinkingEmptyMin = nc.thinkingEmptyMin.coerceIn(d.fromA.toInt(), d.toA.toInt()),
                            thinkingEmptyMax = nc.thinkingEmptyMax.coerceIn(d.fromB.toInt(), d.toB.toInt())
                        )
                    }
                    "randomPeriod" -> {
                        nc = nc.copy(
                            randomPeriodMin = nc.randomPeriodMin.coerceIn(d.fromA.toInt(), d.toA.toInt()),
                            randomPeriodMax = nc.randomPeriodMax.coerceIn(d.fromB.toInt(), d.toB.toInt())
                        )
                    }
                }
            }
            // 开关阈值项不再经由 pet_bounds 回灌（已改为阈值页直接读写 live），此处仅夹紧数值类阈值。
            // 颜色阈值项无需"夹紧"（无上下界），其默认→当前的回灌仅发生在「重置」流程，
            // 由 resetSystemNow 针对性处理，避免实时修改默认色误改当前显示色。
            nc
        }
    }

    /**
     * 阈值页开关阈值项对应的实时 PetConfig 布尔值。
     * 用于打开阈值页时把开关初始态对齐 live 配置，避免与设置页双存储(pet_bounds / pet_config)脱节：
     * 否则 pet_bounds 的「默认」会在提交阈值时把用户在设置页的选择覆盖掉（即「默认值不好使」的复发根因）。
     * 非简单布尔开关（如随机项位标志）回退到 null，交由调用方保留 pet_bounds 默认。
     */
    private fun liveSwitchFromConfig(key: String, cfg: PetConfigData): Boolean? = when (key) {
        "snapEnabled" -> cfg.snapEnabled
        "snapTop" -> cfg.snapTop
        "snapBottom" -> cfg.snapBottom
        "snapLeft" -> cfg.snapLeft
        "snapRight" -> cfg.snapRight
        "showSnapLine" -> cfg.showSnapLine
        "visible" -> cfg.visible
        "clickThrough" -> cfg.clickThrough
        "gravityEnabled" -> cfg.gravityEnabled
        "bounceVibrate" -> cfg.bounceVibrate
        "tiltGravity" -> cfg.tiltGravity
        "gravityTop" -> cfg.gravityTop
        "gravityBottom" -> cfg.gravityBottom
        "gravityLeft" -> cfg.gravityLeft
        "gravityRight" -> cfg.gravityRight
        "reboundEnabled" -> cfg.reboundEnabled
        "reboundTop" -> cfg.reboundTop
        "reboundBottom" -> cfg.reboundBottom
        "reboundLeft" -> cfg.reboundLeft
        "reboundRight" -> cfg.reboundRight
        "imeAdapt" -> cfg.imeAdapt
        "imeResetBottomOffset" -> cfg.imeResetBottomOffset
        "imeHide" -> cfg.imeHide
        "thinkingEnabled" -> cfg.thinkingEnabled
        "thinkingFlashIn" -> cfg.thinkingFlashIn
        "thinkingFlashOut" -> cfg.thinkingFlashOut
        "thinkingBgEnabled" -> cfg.thinkingBgEnabled
        "randomEnabled" -> cfg.randomEnabled
        else -> {
            val flag = AppBounds.RANDOM_ITEM_FLAG_MAP[key]
            flag?.let { cfg.randomItems and it != 0 }
        }
    }

    /**
     * 阈值页开关阈值项对应的「写入 PetConfig」反向映射，与 [liveSwitchFromConfig] 严格对称。
     * 开关切换/恢复出厂时直接写 live（pet_config），不再经由 pet_bounds 的 default 回灌。
     */
    private fun setLiveSwitch(c: PetConfigData, key: String, value: Boolean): PetConfigData = when (key) {
        "snapEnabled" -> c.copy(snapEnabled = value)
        "snapTop" -> c.copy(snapTop = value)
        "snapBottom" -> c.copy(snapBottom = value)
        "snapLeft" -> c.copy(snapLeft = value)
        "snapRight" -> c.copy(snapRight = value)
        "showSnapLine" -> c.copy(showSnapLine = value)
        "visible" -> c.copy(visible = value)
        "clickThrough" -> c.copy(clickThrough = value)
        "gravityEnabled" -> c.copy(gravityEnabled = value)
        "bounceVibrate" -> c.copy(bounceVibrate = value)
        "tiltGravity" -> c.copy(tiltGravity = value)
        "gravityTop" -> c.copy(gravityTop = value)
        "gravityBottom" -> c.copy(gravityBottom = value)
        "gravityLeft" -> c.copy(gravityLeft = value)
        "gravityRight" -> c.copy(gravityRight = value)
        "reboundEnabled" -> c.copy(reboundEnabled = value)
        "reboundTop" -> c.copy(reboundTop = value)
        "reboundBottom" -> c.copy(reboundBottom = value)
        "reboundLeft" -> c.copy(reboundLeft = value)
        "reboundRight" -> c.copy(reboundRight = value)
        "imeAdapt" -> c.copy(imeAdapt = value)
        "imeResetBottomOffset" -> c.copy(imeResetBottomOffset = value)
        "imeHide" -> c.copy(imeHide = value)
        "thinkingEnabled" -> c.copy(thinkingEnabled = value)
        "thinkingFlashIn" -> c.copy(thinkingFlashIn = value)
        "thinkingFlashOut" -> c.copy(thinkingFlashOut = value)
        "thinkingBgEnabled" -> c.copy(thinkingBgEnabled = value)
        "randomEnabled" -> c.copy(randomEnabled = value)
        else -> {
            val flag = AppBounds.RANDOM_ITEM_FLAG_MAP[key]
            if (flag != null) c.copy(randomItems = if (value) c.randomItems or flag else c.randomItems and flag.inv()) else c
        }
    }

    // ===== 工具 =====
    protected fun EditText.onLostFocus(block: (String) -> Unit) {
        setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val t = text?.toString()?.trim() ?: ""
                block(t)
            }
        }
    }

    /** 解析并校验 hard 范围。返回 null 表示空字符串（忽略，不保存）。 */
    protected fun parseInRange(txt: String, min: Float, max: Float): Pair<Boolean, Float>? {
        if (txt.isEmpty()) return null
        val v = txt.toFloatOrNull() ?: return Pair(false, min)
        if (v < min || v > max) return Pair(false, v)
        return Pair(true, v)
    }

    protected fun fmt(v: Float): String =
        if (v == v.toLong().toFloat()) v.toLong().toString() else v.toString()
}
