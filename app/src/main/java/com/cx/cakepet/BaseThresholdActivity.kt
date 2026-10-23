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
    protected val roleId by lazy { intent?.getStringExtra("role_id") ?: RoleRegistry.DEFAULT.id }
    protected lateinit var petBounds: PetBounds
    protected lateinit var petConfig: PetConfig
    protected var current: PageBounds = PageBounds()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityThresholdSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        petBounds = PetBounds.forRole(this, roleId)
        petConfig = PetConfig.forRole(this, roleId)

        // 与设置页一致：用占位条填满状态栏高度，标题栏黑底与状态栏连成整体覆盖状态栏
        binding.statusBarSpacer.layoutParams.height = getStatusBarHeight()
        binding.statusBarSpacer.requestLayout()

        binding.tvTitle.text = "$pageTitleText · 阈值/默认"
        // 标题下方小字灰色：当前页面所属角色名（与设置页一致）
        binding.tvTitleSub.text = RoleStore.resolveDisplayName(this, roleId)
        // 离开前先让持有焦点的输入框失焦 → 触发 onLostFocus 把挂起的默认值/边界编辑落盘，
        // 否则直接返回时编辑值未提交，子页读到的仍是旧值（"改了不生效/需重载"）。
        binding.ivBack.setOnClickListener { leave() }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                leave()
            }
        })

        // 右上角“更多”：恢复用户默认 / 重置系统默认值与阈值。
        // 每个阈值页是独立 Activity、自带 page，重置天然只作用于本页，无需 tab 分派。
        val btnMore = binding.btnMore
        val moreMenu = PopupMenuHelper(
            anchor = btnMore,
            popupLayoutRes = R.layout.popup_threshold_more,
            items = mapOf(
                R.id.item_reset_user to { resetUserDefault() },
                R.id.item_reset_system to { resetSystem() }
            )
        )
        btnMore.setOnClickListener { moreMenu.show() }

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
            // 非首模块前：inflate 复用 include_module_header（仅模块标题，无模块间分隔线），与设置页一致
            val header = LayoutInflater.from(this)
                .inflate(R.layout.include_module_header, binding.container, false)
            header.findViewById<TextView>(R.id.tv_module_title).text = g
            binding.container.addView(header)
            first = false

            // 卡片容器：包裹该模块的全部内容，直接复用设置页的 card_bg + padding
            // item 内部用 layout_margin 提供左右内边距（与设置页 item 写法一致），
            // 因此卡片只需设置上下 padding，左右由 margin=16 承担。
            // 选项间分隔线：showDividers=middle 在 item 之间自动绘制 item_divider（与设置页一致）。
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = getDrawable(R.drawable.card_bg)
                showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
                dividerDrawable = getDrawable(R.drawable.item_divider)
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
                        card.addView(switchItem(sw, idx))
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

        // 派生本配置项的合理输入格式：有负号当且仅当 hardMin<0；小数位由 step 推出
        val fmt = FieldFmt(s.hardMin < 0, decimalsOf(s.step), s.step, s.hardMin, s.hardMax)
        val it = inputTypeFor(fmt.signed, fmt.decimals)
        etMin.inputType = it; etDef.inputType = it; etMax.inputType = it

        /** 把当前数据写回三个输入框（联动后统一刷新显示）。 */
        fun refresh() {
            val cur = current.sliders[index]
            etMin.setText(fmt(cur.from)); etMax.setText(fmt(cur.to)); etDef.setText(fmt(cur.default))
        }
        refresh()

        /** 统一处理：0=下界 1=默认值 2=上界。空→静默；非法→回退修改前值(缺则兜底)并提示；合法→钳制+链序联动+提示。 */
        fun handle(which: Int, txt: String) {
            val fieldName = when (which) { 0 -> "下界"; 1 -> "默认值"; 2 -> "上限"; else -> "" }
            when (val r = parseInput(txt, fmt)) {
                // 真·无输入：静默回退，不提示、不动 current
                is ParseResult.Empty -> { refresh() }
                // 无效输入：使用修改前值；若修改前值缺失则兜底并额外提示「需在/已修改至有效范围」
                is ParseResult.Invalid -> {
                    val cur = current.sliders.getOrNull(index)
                    if (cur != null) {
                        refresh()
                        showThresholdToast("当前输入无效")
                    } else {
                        // 兜底：边界→系统自带边界默认；默认值→系统默认钳进当前上下界且不联动其它字段
                        val fb = when (which) {
                            0 -> s.baseMin
                            2 -> s.baseMax
                            else -> s.default.coerceIn(s.baseMin, s.baseMax)
                        }
                        current = current.copy(sliders = current.sliders.toMutableList().also {
                            it[index] = when (which) {
                                0 -> s.copy(from = fb)
                                2 -> s.copy(to = fb)
                                else -> s.copy(default = fb)
                            }
                        })
                        commitOne(); refresh()
                        showThresholdToast("当前输入无效\n" + buildMsg(s.label, fieldName, fmt, true, emptyList()))
                    }
                }
                is ParseResult.Valid -> {
                    val cur = current.sliders[index]
                    val c = snapAndFormat(r.raw, fmt)
                    val (nf, nd, nt, changed) = syncSingle(which, c, cur)
                    // 无实际变化且未越硬界（如原值 1.5 又输 1.5）→ 静默；越硬界即使钳回原值也要提示
                    if (nf == cur.from && nd == cur.default && nt == cur.to && !r.outOfHard) { refresh(); return }
                    current = current.copy(sliders = current.sliders.toMutableList().also {
                        it[index] = cur.copy(from = nf, default = nd, to = nt)
                    })
                    commitOne()
                    refresh()
                    showThresholdToast(buildMsg(s.label, fieldName, fmt, r.outOfHard, changed))
                }
            }
        }
        etMin.onLostFocus { handle(0, it) }
        etDef.onLostFocus { handle(1, it) }
        etMax.onLostFocus { handle(2, it) }
        // 回车仅确认当前框，不跳到下一个输入框（handle 已定义，放此处避免前向引用）
        etMin.onEnterDone { handle(0, etMin.text.toString().trim()) }
        etDef.onEnterDone { handle(1, etDef.text.toString().trim()) }
        etMax.onEnterDone { handle(2, etMax.text.toString().trim()) }
        return v
    }

    private fun dualItem(d: DualSliderBound, index: Int): View {
        val v = layoutInflater.inflate(R.layout.item_dual_bound, binding.container, false)
        v.findViewById<TextView>(R.id.tv_label).text = d.label
        val etMin = v.findViewById<EditText>(R.id.et_min)
        val etDefA = v.findViewById<EditText>(R.id.et_def_a)
        val etDefB = v.findViewById<EditText>(R.id.et_def_b)
        val etMax = v.findViewById<EditText>(R.id.et_max)

        // 派生各字段格式：下界/上界用 min(stepA,stepB) 作代表步进；默认值各自 step
        val signed = d.hardMin < 0
        val decLo = minOf(decimalsOf(d.stepA), decimalsOf(d.stepB))
        val decA = decimalsOf(d.stepA); val decB = decimalsOf(d.stepB)
        etMin.inputType = inputTypeFor(signed, decLo)
        etMax.inputType = inputTypeFor(signed, decLo)
        etDefA.inputType = inputTypeFor(signed, decA)
        etDefB.inputType = inputTypeFor(signed, decB)

        fun refresh() {
            val cur = current.duals[index]
            etMin.setText(fmt(minOf(cur.fromA, cur.fromB)))
            etMax.setText(fmt(maxOf(cur.toA, cur.toB)))
            etDefA.setText(fmt(cur.defaultA))
            etDefB.setText(fmt(cur.defaultB))
        }
        refresh()

        val fmtLo = FieldFmt(signed, decLo, minOf(d.stepA, d.stepB), d.hardMin, d.hardMax)
        val fmtA = FieldFmt(signed, decA, d.stepA, d.hardMin, d.hardMax)
        val fmtB = FieldFmt(signed, decB, d.stepB, d.hardMin, d.hardMax)

        /** 统一处理：0=下界 1=左值默认 2=右值默认 3=上界。空→静默；非法→回退(缺则兜底)；合法→钳制+联动+提示。 */
        fun handle(which: Int, txt: String, fmt: FieldFmt) {
            val fieldName = when (which) { 0 -> "下界"; 1 -> "左值默认"; 2 -> "右值默认"; 3 -> "上限"; else -> "" }
            when (val r = parseInput(txt, fmt)) {
                is ParseResult.Empty -> { refresh() }
                is ParseResult.Invalid -> {
                    val cur = current.duals.getOrNull(index)
                    if (cur != null) {
                        refresh()
                        showThresholdToast("当前输入无效")
                    } else {
                        // 兜底：边界→系统自带边界默认(minA,B / maxA,B)；默认值→系统默认钳进当前上下界且不联动
                        val fb = when (which) {
                            0 -> minOf(d.baseMinA, d.baseMinB)
                            3 -> maxOf(d.baseMaxA, d.baseMaxB)
                            1 -> d.defaultA.coerceIn(d.baseMinA, d.baseMaxA)
                            else -> d.defaultB.coerceIn(d.baseMinB, d.baseMaxB)
                        }
                        current = current.copy(duals = current.duals.toMutableList().also {
                            it[index] = when (which) {
                                0 -> d.copy(fromA = fb, fromB = fb)
                                3 -> d.copy(toA = fb, toB = fb)
                                1 -> d.copy(defaultA = fb)
                                else -> d.copy(defaultB = fb)
                            }
                        })
                        commitOne(); refresh()
                        showThresholdToast("当前输入无效\n" + buildMsg(d.label, fieldName, fmt, true, emptyList()))
                    }
                }
                is ParseResult.Valid -> {
                    val cur = current.duals[index]
                    val c = snapAndFormat(r.raw, fmt)
                    val (nlo, ndA, ndB, nhi, changed) = syncDual(which, c, cur)
                    val curLo = minOf(cur.fromA, cur.fromB); val curHi = maxOf(cur.toA, cur.toB)
                    // 无实际变化且未越硬界→静默；越硬界即使钳回原值也要提示
                    if (nlo == curLo && ndA == cur.defaultA && ndB == cur.defaultB && nhi == curHi && !r.outOfHard) { refresh(); return }
                    current = current.copy(duals = current.duals.toMutableList().also {
                        it[index] = cur.copy(
                            fromA = nlo, toA = nhi, defaultA = ndA,
                            fromB = nlo, toB = nhi, defaultB = ndB
                        )
                    })
                    commitOne(); refresh()
                    showThresholdToast(buildMsg(d.label, fieldName, fmt, r.outOfHard, changed))
                }
            }
        }
        etMin.onLostFocus { handle(0, it, fmtLo) }
        etDefA.onLostFocus { handle(1, it, fmtA) }
        etDefB.onLostFocus { handle(2, it, fmtB) }
        etMax.onLostFocus { handle(3, it, fmtLo) }
        // 回车仅确认当前框，不跳到下一个输入框（handle 与 fmt* 已定义，放此处避免前向引用）
        etMin.onEnterDone { handle(0, etMin.text.toString().trim(), fmtLo) }
        etDefA.onEnterDone { handle(1, etDefA.text.toString().trim(), fmtA) }
        etDefB.onEnterDone { handle(2, etDefB.text.toString().trim(), fmtB) }
        etMax.onEnterDone { handle(3, etMax.text.toString().trim(), fmtLo) }
        return v
    }

    // 开关阈值项 = 用户在阈值页设置的【默认值】(存 pet_bounds.default)：
    // 显示读 default；切换时仅写 default 并经 commitOne 持久化，不写当前值(live)。
    // 默认值只在用户手动点「恢复用户默认」时经 resetFromBounds 写回当前值。
    private fun switchItem(sw: SwitchBound, index: Int): View {
        val v = layoutInflater.inflate(R.layout.item_switch_bound, binding.container, false)
        v.findViewById<TextView>(R.id.tv_label).text = sw.label
        val swView = v.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.sw_value)
        swView.isChecked = sw.default
        swView.setOnCheckedChangeListener { _, isChecked ->
            val cur = current.switches[index]
            current = current.copy(switches = current.switches.toMutableList().also { it[index] = cur.copy(default = isChecked) })
            commitOne() // 仅存阈值默认(pet_bounds)，不改当前值(live)；只在「恢复用户默认」时才写回
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
        // 先让仍持有焦点的输入框失焦，触发挂起提交（随后 resetSystem 清空用户值，挂起编辑被一起丢弃，符合预期）。
        currentFocus?.clearFocus()
        runBlocking {
            petBounds.resetSystem(page)
            coerceConfigToBounds(page)
            petConfig.resetFromBounds(petBounds, page)
        }
        current = petBounds.getBlocking(page)
        render()
        Toast.makeText(this, "已恢复系统默认阈值", Toast.LENGTH_SHORT).show()
    }

    /** 离开阈值页：先让输入框失焦以提交挂起的默认值/边界编辑，再关闭页面（覆盖 ivBack 与系统返回键）。 */
    private fun leave() {
        currentFocus?.clearFocus()
        finish()
    }

    /**
     * 恢复用户默认（把阈值页设定的默认值应用到当前运行配置），供右上角“更多”浮窗调用。先弹确认框。
     * 与设置页「恢复用户默认」走完全相同的逻辑（config.resetFromBounds），但作用域限定在本阈值页 page。
     */
    private fun resetUserDefault() {
        showConfirmDialog(
            title = "恢复用户默认",
            bodyHtml = "将<b>本页</b>的当前值恢复为当前默认值？",
            subText = "默认值由「默认值与阈值」页设定",
            positiveText = "恢复"
        ) { resetUserDefaultNow() }
    }

    private fun resetUserDefaultNow() {
        // 关键：先让当前仍持有焦点的输入框失焦，触发 onLostFocus → commitOne 把挂起的新默认值落盘，
        // 否则直接点「更多→恢复用户默认」时 EditText 未失焦、编辑值从未提交，菜单读到的仍是旧默认值。
        currentFocus?.clearFocus()
        runBlocking {
            petConfig.resetFromBounds(petBounds, page)
            coerceConfigToBounds(page)
        }
        current = petBounds.getBlocking(page)
        render()
        Toast.makeText(this, "已恢复用户默认", Toast.LENGTH_SHORT).show()
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
                    // 抛掷系数/飞跃概率：新增字段，必须纳入钳制，否则改阈值后当前值不回写、页面不刷新
                    "throwCoeff" -> nc.copy(throwCoeff = nc.throwCoeff.coerceIn(s.from, s.to))
                    "leapProbability" -> nc.copy(leapProbability = nc.leapProbability.coerceIn(s.from, s.to))
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
                    "feedScale" -> nc.copy(feedingFishScale = nc.feedingFishScale.coerceIn(s.from, s.to))
                    "feedAlpha" -> nc.copy(feedingFishAlpha = nc.feedingFishAlpha.coerceIn(s.from, s.to))
                    "feedMaxCount" -> nc.copy(feedingMaxCount = nc.feedingMaxCount.coerceIn(s.from.toInt(), s.to.toInt()))
                    "feedExpire" -> nc.copy(feedingExpireTime = nc.feedingExpireTime.coerceIn(s.from.toInt(), s.to.toInt()))
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
                    "feedPeriod" -> {
                        nc = nc.copy(
                            feedingPeriodMin = nc.feedingPeriodMin.coerceIn(d.fromA.toInt(), d.toA.toInt()),
                            feedingPeriodMax = nc.feedingPeriodMax.coerceIn(d.fromB.toInt(), d.toB.toInt())
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
     * 阈值页开关阈值项对应的「写入 PetConfig」反向映射，与 [liveSwitchFromConfig] 严格对称。
     * 开关切换/恢复出厂时直接写 live（pet_config），不再经由 pet_bounds 的 default 回灌。
     */
    private fun setLiveSwitch(c: PetConfigData, key: String, value: Boolean): PetConfigData = when (key) {
        "snapEnabled" -> c.copy(snapEnabled = value)
        "snapTop" -> c.copy(snapTop = value)
        "snapBottom" -> c.copy(snapBottom = value)
        "snapLeft" -> c.copy(snapLeft = value)
        "snapRight" -> c.copy(snapRight = value)
        "visible" -> c.copy(visible = value)
        "clickThrough" -> c.copy(clickThrough = value)
        "moveEnabled" -> c.copy(moveEnabled = value)
        "throwEnabled" -> c.copy(throwEnabled = value)
        "wallClimb" -> c.copy(wallClimb = value)
        "leapEnabled" -> c.copy(leapEnabled = value)
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
        "feedingEnabled" -> c.copy(feedingEnabled = value)
        "feedingAutoExpire" -> c.copy(feedingAutoExpire = value)
        "feedingFullScreen" -> c.copy(feedingFullScreen = value)
        "feedingBorderShow" -> c.copy(feedingBorderShow = value)
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

    /** 回车/「完成」仅确认当前框：提交并收起键盘，不跳到下一个输入框。
     *  阈值页默认 imeOptions=UNSPECIFIED 时，IME 会把回车当成 NEXT，导致“确认+Tab”跳到下一框，不合理；
     *  这里显式设为 IME_ACTION_DONE，软键盘「完成」与硬键盘回车都只提交当前框（与设置页输入框行为一致）。 */
    protected fun EditText.onEnterDone(block: () -> Unit) {
        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                block(); clearFocus(); true
            } else false
        }
        setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_ENTER && event.action == android.view.KeyEvent.ACTION_DOWN) {
                block(); clearFocus(); true
            } else false
        }
    }

    protected fun fmt(v: Float): String =
        if (v == v.toLong().toFloat()) v.toLong().toString() else v.toString()

    /** 显示用格式化：按本配置项的小数位渲染（消除浮点尾噪）。 */
    private fun fmt(v: Float, decimals: Int): String {
        if (decimals <= 0) return v.toLong().toString()
        val s = String.format(java.util.Locale.US, "%.${decimals}f", v)
        return s.trimEnd('0').trimEnd('.')
    }

    /** 一个配置项的输入格式元数据（由模型派生的合理格式）。 */
    private data class FieldFmt(
        val signed: Boolean, val decimals: Int, val step: Float,
        val hardMin: Float, val hardMax: Float
    )

    /** 由 step 推出小数位：step>=1→0 位；0.1→1 位；0.01→2 位。step<=0 保护为 0 位。
     *  注意：step 是 Float，0.01f 实际存为 0.009999999776482582，直接 log10 得 -2.0000000093，
     *  ceil 会得到 3（多出一位），导致 大小/飞跃概率 等 0.01 步长字段小数位算错、显示尾噪。
     *  故先按 1e6 就近取整吃掉 float 尾噪，再算位数。 */
    private fun decimalsOf(step: Float): Int {
        if (step <= 0f) return 0
        val sd = Math.round(step * 1_000_000f) / 1_000_000f   // 收口 float 尾噪
        return kotlin.math.ceil(-kotlin.math.log10(sd.toDouble())).toInt().coerceAtLeast(0)
    }

    /** 由正负/小数位生成软键盘类型，从源头限制非法字符。 */
    private fun inputTypeFor(signed: Boolean, decimals: Int): Int =
        android.text.InputType.TYPE_CLASS_NUMBER or
            (if (signed) android.text.InputType.TYPE_NUMBER_FLAG_SIGNED else 0) or
            (if (decimals > 0) android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL else 0)

    /** 三层钳制：硬范围 → 对齐 step 整数倍 → 按显示精度收口（杜绝 0.099994 类浮点尾噪）。 */
    private fun snapAndFormat(raw: Float, fmt: FieldFmt): Float {
        val c = raw.coerceIn(fmt.hardMin, fmt.hardMax)
        if (fmt.step <= 0f) return c
        val steps = Math.round((c - fmt.hardMin) / fmt.step)
        val snapped = fmt.hardMin + steps * fmt.step
        // 对齐后仍可能是 0.0999998 之类，按显示小数位收口为可精确表示的值，
        // 使“存储值”与“显示值(fmt 去尾零)”严格相等，避免输入框出现 0.099994。
        val f = Math.pow(10.0, fmt.decimals.toDouble()).toFloat()
        return Math.round(snapped * f) / f    // Long / Float → Float，收口到显示精度
    }

    /** 四态解析：空串/非法字符 → 回退输入前；合法数字 → 携带是否越硬界标记。 */
    private sealed interface ParseResult {
        object Empty : ParseResult
        object Invalid : ParseResult
        data class Valid(val raw: Float, val outOfHard: Boolean) : ParseResult
    }

    private fun parseInput(txt: String, fmt: FieldFmt): ParseResult {
        val t = txt.trim()
        if (t.isEmpty()) return ParseResult.Empty
        val v = t.toFloatOrNull() ?: return ParseResult.Invalid
        return ParseResult.Valid(v, v < fmt.hardMin || v > fmt.hardMax)
    }

    /** 提示合成（每行以「。」结尾，多行以 \n 连接）：
     *  - 越硬界：首行「配置项 需在 a~b。」，次行「字段名已修改至有效范围。」
     *  - 联动：每条改动「同步修改当前默认值、上限。」
     *  - 普通正常修改（无越界、无联动）：「已修改{字段名}」
     */
    private fun buildMsg(label: String, fieldName: String, fmt: FieldFmt, outOfHard: Boolean, changed: List<String>): String {
        val parts = mutableListOf<String>()
        if (outOfHard) {
            parts += "${label} 需在 ${fmt(fmt.hardMin, fmt.decimals)}~${fmt(fmt.hardMax, fmt.decimals)}。"
            parts += "${fieldName}已修改至有效范围。"
        }
        if (changed.isNotEmpty()) parts += "同步修改当前" + changed.joinToString("、") + "。"
        if (!outOfHard && changed.isEmpty()) parts += "已修改${fieldName}"
        return parts.joinToString("\n")
    }

    /** 多行 Toast：用显式 TextView 保证「\n」换行渲染。
     *  规避部分 ROM 把系统 Toast 设为 singleLine 导致换行失效（如「同步修改当前xxx」被接在前一行）。 */
    private fun showThresholdToast(msg: String) {
        if (msg.isBlank()) return
        val dm = resources.displayMetrics.density
        val dp = { v: Int -> (v * dm).toInt() }
        val tv = android.widget.TextView(this).apply {
            text = msg
            gravity = android.view.Gravity.CENTER
            textSize = 14f
            setTextColor(0xFFEDEDED.toInt())
            setLineSpacing(0f, 1.35f)
            setPadding(dp(20), dp(14), dp(20), dp(14))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(0xCC000000.toInt())
            }
        }
        Toast(this).apply {
            setGravity(android.view.Gravity.CENTER_HORIZONTAL or android.view.Gravity.BOTTOM, 0, dp(90))
            duration = Toast.LENGTH_SHORT
            view = tv
            show()
        }
    }

    /** 单滑块链：hardMin ≤ 下界(from) ≤ 默认值(default) ≤ 上界(to) ≤ hardMax。返回新值与被联动的字段名。 */
    private data class SingleSync(val from: Float, val default: Float, val to: Float, val changed: List<String>)

    private fun syncSingle(which: Int, c: Float, cur: SliderBound): SingleSync {
        var from = cur.from; var def = cur.default; var to = cur.to
        val changed = mutableListOf<String>()
        when (which) {
            0 -> { // 下界：右侧默认值/上界不得 < 它
                if (def < c) { def = c; changed += "默认值" }
                if (to < c) { to = c; changed += "上限" }
                from = c
            }
            1 -> { // 默认值：左侧下界不得 > 它，右侧上界不得 < 它
                if (from > c) { from = c; changed += "下限" }
                if (to < c) { to = c; changed += "上限" }
                def = c
            }
            2 -> { // 上界：左侧下界/默认值不得 > 它
                if (from > c) { from = c; changed += "下限" }
                if (def > c) { def = c; changed += "默认值" }
                to = c
            }
        }
        return SingleSync(from, def, to, changed)
    }

    /** 双滑块链：hardMin ≤ 下界(lo) ≤ 左值默认(defA) ≤ 右值默认(defB) ≤ 上界(hi) ≤ hardMax。 */
    private data class DualSync(val lo: Float, val defA: Float, val defB: Float, val hi: Float, val changed: List<String>)

    private fun syncDual(which: Int, c: Float, cur: DualSliderBound): DualSync {
        var lo = minOf(cur.fromA, cur.fromB); var dA = cur.defaultA; var dB = cur.defaultB
        var hi = maxOf(cur.toA, cur.toB)
        val changed = mutableListOf<String>()
        when (which) {
            0 -> { // 下界
                if (dA < c) { dA = c; changed += "左值默认" }
                if (dB < c) { dB = c; changed += "右值默认" }
                if (hi < c) { hi = c; changed += "上限" }
                lo = c
            }
            1 -> { // 左值默认
                if (lo > c) { lo = c; changed += "下界" }
                if (dB < c) { dB = c; changed += "右值默认" }
                if (hi < c) { hi = c; changed += "上限" }
                dA = c
            }
            2 -> { // 右值默认
                if (lo > c) { lo = c; changed += "下界" }
                if (dA > c) { dA = c; changed += "左值默认" }
                if (hi < c) { hi = c; changed += "上限" }
                dB = c
            }
            3 -> { // 上界
                if (lo > c) { lo = c; changed += "下界" }
                if (dA > c) { dA = c; changed += "左值默认" }
                if (dB > c) { dB = c; changed += "右值默认" }
                hi = c
            }
        }
        return DualSync(lo, dA, dB, hi, changed)
    }
}
