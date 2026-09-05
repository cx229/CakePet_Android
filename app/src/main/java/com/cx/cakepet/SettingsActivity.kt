package com.cx.cakepet

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import android.view.MotionEvent
import com.google.android.material.slider.Slider
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 设置页（对应 PC 端设置 + 安卓新增的四边重力）。
 * 实时写入 DataStore，浮窗 Service 自动响应。
 */
class SettingsActivity : AppCompatActivity() {

    // 点输入框以外区域（空白/其它控件）即让当前输入框失焦，触发「失焦即提交」
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        commitInputOnTouchOutside(ev)
        return super.dispatchTouchEvent(ev)
    }

    private lateinit var config: PetConfig
    // 屏蔽吸附预览回调（提升为字段，供 doResetDefault 复用）
    private var suppressSnapPreview: Boolean = false
    // 屏蔽开关 listener 回调：applyConfig 程序化回填所有开关时置 true，避免回流 setChecked 触发 listener 又写盘，
    // 造成「同时改多个开关」时自激闪动（与随机页 suppressRandomSwitch 同思路）。
    private var suppressSwitch: Boolean = false

    // 阈值范围（来自 PetBounds，用于滑块业务上下界/默认值）。提升为字段，onResume 重新读后滑块监听闭包读到新值。
    private lateinit var bs: Map<String, SliderBound>

    // 各滑块「待确认值」：记录用户最后拖到的滑块值（Int），用于挡住滞后的旧发射回流。
    // 提升为字段，供 onResume 刷新时读取，避免退回本页后滑块被旧值拉回。
    private var pendingScale: Int? = null
    private var pendingGravity: Int? = null
    private var pendingRebound: Int? = null
    private var pendingAlpha: Int? = null
    private var pendingMaxSpeed: Int? = null
    private var pendingSnap: Int? = null

    // 滑块视图提升为字段，供 onResume 刷新位置
    private lateinit var scaleBar: Slider
    private lateinit var scaleVal: EditText
    private lateinit var gravityBar: Slider
    private lateinit var gravityVal: EditText
    private lateinit var reboundBar: Slider
    private lateinit var reboundVal: EditText
    private lateinit var alphaBar: Slider
    private lateinit var alphaVal: EditText
    private lateinit var maxSpeedBar: Slider
    private lateinit var maxSpeedVal: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        // 用纯黑占位条填充状态栏高度，与标题栏黑底连成整体覆盖状态栏；标题文字在标题栏内完整显示。
        val spacer = findViewById<android.view.View>(R.id.status_bar_spacer)
        spacer.layoutParams.height = getStatusBarHeight()
        spacer.requestLayout()
        config = PetConfig(this)

        // 阈值范围（来自 PetBounds，用于滑块业务上下界/默认值）
        bs = PetBounds(this).getBlocking(SettingsPage.MAIN).sliders.associateBy { it.key }

        // 右上角“更多”图标：弹出菜单，含「默认值与阈值」「恢复用户默认」
        // 「重置系统默认值与阈值」及主设置页专属「重置软件所有设置」（需确认）
        val btnMore = findViewById<ImageView>(R.id.btn_more)
        val moreMenu = SettingsMoreMenu(
            anchor = btnMore,
            onThreshold = { startActivity(Intent(this, MainThresholdActivity::class.java)) },
            onResetUserDefault = { doResetDefault() },
            onResetSystem = { doResetSystem() },
            onResetAll = { confirmResetAll() }
        )
        btnMore.setOnClickListener { moreMenu.show() }

        // 吸附开关预览屏蔽标志：程序赋值（重置/默认值）期间为 true，避免误触发 1s 示意图与振动
        suppressSnapPreview = false

        scaleBar = findViewById(R.id.scale_bar)
        scaleVal = findViewById(R.id.scale_value)
        gravityBar = findViewById(R.id.gravity_bar)
        gravityVal = findViewById(R.id.gravity_value)
        reboundBar = findViewById(R.id.rebound_bar)
        reboundVal = findViewById(R.id.rebound_value)

        val swTop = findViewById<Switch>(R.id.sw_top)
        val swBottom = findViewById<Switch>(R.id.sw_bottom)
        val swLeft = findViewById<Switch>(R.id.sw_left)
        val swRight = findViewById<Switch>(R.id.sw_right)
        val swRtop = findViewById<Switch>(R.id.sw_rtop)
        val swRbottom = findViewById<Switch>(R.id.sw_rbottom)
        val swRleft = findViewById<Switch>(R.id.sw_rleft)
        val swRright = findViewById<Switch>(R.id.sw_rright)
        // 随机模式入口行：显示开关状态（已开启/已关闭），点击进入二级页
        val tvRandomState = findViewById<TextView>(R.id.tv_random_state)
        val rowRandomMode = findViewById<android.view.View>(R.id.row_random_mode)
        // 吸附边缘总开关入口：整行点击切换 snapEnabled，右侧显示已开启/已关闭
        val tvSnapState = findViewById<TextView>(R.id.tv_snap_state)
        val rowSnapMode = findViewById<android.view.View>(R.id.row_snap_mode)
        // 碎碎念开关入口：整行点击进入二级设置页，右侧显示已开启/已关闭
        val tvThinkingState = findViewById<TextView>(R.id.tv_thinking_state)
        val rowThinkingMode = findViewById<android.view.View>(R.id.row_thinking_mode)
        val swVisible = findViewById<Switch>(R.id.sw_visible)
        val swClickThrough = findViewById<Switch>(R.id.sw_click_through)
        val swBounceVibrate = findViewById<Switch>(R.id.sw_bounce_vibrate)
        val swGravityEnabled = findViewById<Switch>(R.id.sw_gravity_enabled)
        val swTiltGravity = findViewById<Switch>(R.id.sw_tilt_gravity)
        val swReboundEnabled = findViewById<Switch>(R.id.sw_rebound_enabled)
        alphaBar = findViewById(R.id.alpha_bar)
        alphaVal = findViewById(R.id.alpha_value)
        maxSpeedBar = findViewById(R.id.maxspeed_bar)
        maxSpeedVal = findViewById(R.id.maxspeed_value)

        // 常驻观察 configFlow：菜单等外部改动任意字段后，设置页自动同步，避免残留旧状态。
        // applyConfig 直接 setChecked/setProgress 为程序化赋值，不触发写盘 listener，无回写死循环风险。
        fun applyConfig(c: PetConfigData) {
            // 用 setValueFromConfig 而非直接赋值：
            // 1) 换算结果可能是小数（如 66.67），与 stepSize=1.0 冲突会触发 Material 校验异常导致闪退；
            // 2) 用户正在拖动的滑块跳过回写，避免 thumb 被在途配置拉回造成抖动。
            // 回写时传入 pending：滞后的旧发射会被跳过，追平后清空 pending 恢复自由回写。
            // 宠物侧仍照常使用同一份配置实时更新，不受影响。
            // 数值文本必须与滑块回写同步保护：否则在途旧值到达时 thumb 被保护不动，
            // 文本却被刷成旧值，用户会看到数字跳回（滑块不动而数字闪回）。
            if (scaleBar.setValueFromConfig(bizToSliderValue(c.scale, bs["scale"]!!.from, bs["scale"]!!.to), pendingScale)) {
                pendingScale = null
                EditSync.setText(scaleVal, "%.2f×".format(c.scale))
            }
            if (gravityBar.setValueFromConfig(bizToSliderValue(c.gravity, bs["gravity"]!!.from, bs["gravity"]!!.to), pendingGravity)) {
                pendingGravity = null
                EditSync.setText(gravityVal, c.gravity.toInt().toString())
            }
            if (reboundBar.setValueFromConfig(bizToSliderValue(c.reboundRatio, bs["rebound"]!!.from, bs["rebound"]!!.to), pendingRebound)) {
                pendingRebound = null
                EditSync.setText(reboundVal, "%.2f".format(c.reboundRatio))
            }
            // 程序化回填所有开关用 suppressSwitch 包住：回流 setChecked 不再触发 listener 写盘，避免并发改多个开关自激闪动
            suppressSwitch = true
            swTop.isChecked = c.gravityTop
            swBottom.isChecked = c.gravityBottom
            swLeft.isChecked = c.gravityLeft
            swRight.isChecked = c.gravityRight
            swRtop.isChecked = c.reboundTop
            swRbottom.isChecked = c.reboundBottom
            swRleft.isChecked = c.reboundLeft
            swRright.isChecked = c.reboundRight
            swVisible.isChecked = c.visible
            swClickThrough.isChecked = c.clickThrough
            swBounceVibrate.isChecked = c.bounceVibrate
            swGravityEnabled.isChecked = c.gravityEnabled
            swTiltGravity.isChecked = c.tiltGravity
            swReboundEnabled.isChecked = c.reboundEnabled
            suppressSwitch = false
            // 回写入口状态文本
            tvRandomState.text = if (c.randomEnabled) "已开启" else "已关闭"
            tvSnapState.text = if (c.snapEnabled) "已开启" else "已关闭"
            tvThinkingState.text = if (c.thinkingEnabled) "已开启" else "已关闭"
            // 体感重力与四边定向重力互不干扰：四边开关始终可交互并显示原值，
            // 开启体感时由 PetPhysics.step() 的 tiltGravity 分支优先接管，四边仅被忽略；
            // 关闭体感后四边原值自动恢复生效，无需用户重新逐个开启。
            if (alphaBar.setValueFromConfig(bizToSliderValue(c.alpha * 100f, bs["alpha"]!!.from, bs["alpha"]!!.to), pendingAlpha)) {
                pendingAlpha = null
                EditSync.setText(alphaVal, "%d%%".format((c.alpha * 100).toInt()))
            }
            if (maxSpeedBar.setValueFromConfig(bizToSliderValue(c.maxSpeed, bs["maxSpeed"]!!.from, bs["maxSpeed"]!!.to), pendingMaxSpeed)) {
                pendingMaxSpeed = null
                EditSync.setText(maxSpeedVal, c.maxSpeed.toInt().toString())
            }
        }
        lifecycleScope.launch {
            config.configFlow.collect { applyConfig(it) }
        }

        // ===== 滑块：统一黑色样式 + 气泡显示真实业务值 =====
        // 气泡显示真实业务值；滑块内部固定 0..100 归一化，业务范围(上下界)来自 PetBounds，
        // 从而「阈值设置」页修改上下界/默认值后，滑块代表的业务值范围随之变化。
        // 绑定逻辑抽成 setupSliders()，onResume 重新读取阈值后也会重绑，使修改立即生效。
        setupSliders()

        // 四边偏移已移至「显示范围」二级页（RangeSettingsActivity）。

        // 四边重力互斥联动：左右不能同时开、上下不能同时开（避免两套重力叠加成非法组合）。
        // 关键：只有【开启】本侧时才关闭对侧；【关闭】本侧时绝不碰对侧（保留原值）。
        // 旧逻辑写成 gravityOpposite=false（无论 on 与否都关对侧），会在 configFlow 刷新时
        // 程序化 setChecked 触发对侧 listener，把刚刚开启的对侧又误关掉（即“开下后下又被刷新关掉”）。
        // 不在此手动 setChecked 对侧，只写盘，由下方 configFlow.collect → applyConfig 统一同步 UI。
        swTop.setOnCheckedChangeListener { _, on ->
            if (suppressSwitch) return@setOnCheckedChangeListener
            lifecycleScope.launch { config.update { it.copy(gravityTop = on, gravityBottom = if (on) false else it.gravityBottom) } }
        }
        swBottom.setOnCheckedChangeListener { _, on ->
            if (suppressSwitch) return@setOnCheckedChangeListener
            lifecycleScope.launch { config.update { it.copy(gravityBottom = on, gravityTop = if (on) false else it.gravityTop) } }
        }
        swLeft.setOnCheckedChangeListener { _, on ->
            if (suppressSwitch) return@setOnCheckedChangeListener
            lifecycleScope.launch { config.update { it.copy(gravityLeft = on, gravityRight = if (on) false else it.gravityRight) } }
        }
        swRight.setOnCheckedChangeListener { _, on ->
            if (suppressSwitch) return@setOnCheckedChangeListener
            lifecycleScope.launch { config.update { it.copy(gravityRight = on, gravityLeft = if (on) false else it.gravityLeft) } }
        }
        swRtop.setOnCheckedChangeListener { _, b ->
            if (suppressSwitch) return@setOnCheckedChangeListener
            lifecycleScope.launch { config.update { it.copy(reboundTop = b) } }
        }
        swRbottom.setOnCheckedChangeListener { _, b ->
            if (suppressSwitch) return@setOnCheckedChangeListener
            lifecycleScope.launch { config.update { it.copy(reboundBottom = b) } }
        }
        swRleft.setOnCheckedChangeListener { _, b ->
            if (suppressSwitch) return@setOnCheckedChangeListener
            lifecycleScope.launch { config.update { it.copy(reboundLeft = b) } }
        }
        swRright.setOnCheckedChangeListener { _, b ->
            if (suppressSwitch) return@setOnCheckedChangeListener
            lifecycleScope.launch { config.update { it.copy(reboundRight = b) } }
        }
        // 随机模式入口：点击进入二级页
        rowRandomMode.setOnClickListener {
            startActivity(android.content.Intent(this, RandomSettingsActivity::class.java))
        }
        swVisible.setOnCheckedChangeListener { _, b ->
            if (suppressSwitch) return@setOnCheckedChangeListener
            lifecycleScope.launch { config.update { it.copy(visible = b) } }
        }
        swGravityEnabled.setOnCheckedChangeListener { _, b ->
            if (suppressSwitch) return@setOnCheckedChangeListener
            lifecycleScope.launch { config.update { it.copy(gravityEnabled = b) } }
        }
        swClickThrough.setOnCheckedChangeListener { _, b ->
            if (suppressSwitch) return@setOnCheckedChangeListener
            lifecycleScope.launch { config.update { it.copy(clickThrough = b) } }
        }
        swBounceVibrate.setOnCheckedChangeListener { _, b ->
            if (suppressSwitch) return@setOnCheckedChangeListener
            lifecycleScope.launch { config.update { it.copy(bounceVibrate = b) } }
        }
        swReboundEnabled.setOnCheckedChangeListener { _, b ->
            if (suppressSwitch) return@setOnCheckedChangeListener
            lifecycleScope.launch { config.update { it.copy(reboundEnabled = b) } }
        }
        // 碎碎念开关入口：整行点击进入碎碎念设置页
        rowThinkingMode.setOnClickListener {
            startActivity(android.content.Intent(this, ThinkingSettingsActivity::class.java))
        }
        // 吸附边缘总开关入口：整行点击切换 snapEnabled
        // 吸附边缘入口：仅显示状态（已开启/已关闭）+ 跳转贴边边设置页
        rowSnapMode.setOnClickListener {
            startActivity(android.content.Intent(this, SnapSettingsActivity::class.java))
        }
        // 进入显示范围二级页（边界偏移：顶/底/左/右）
        findViewById<android.view.View>(R.id.row_range_settings).setOnClickListener {
            startActivity(android.content.Intent(this, RangeSettingsActivity::class.java))
        }
        // 体感重力：只切换 tiltGravity 标志，不改动四边定向重力的存储值与 UI 状态。
        // 物理层 PetPhysics.step() 在 tiltGravity=true 时优先走体感分支（忽略四边），
        // 关闭后四边原配置自动恢复生效，用户无需重新逐个开启。
        swTiltGravity.setOnCheckedChangeListener { _, on ->
            if (suppressSwitch) return@setOnCheckedChangeListener
            lifecycleScope.launch {
                config.update { c -> c.copy(tiltGravity = on) }
            }
        }
        // 「恢复默认设置」已移入右上角“更多”浮窗，逻辑封装为 doResetDefault()
        findViewById<android.widget.TextView>(R.id.btn_about).setOnClickListener {
            startActivity(android.content.Intent(this, AboutActivity::class.java))
        }
        findViewById<android.widget.ImageView>(R.id.iv_back).setOnClickListener { finish() }
    }

    /** 恢复本页默认设置（阈值页设定的默认值会同步生效），供右上角“更多”浮窗调用。先弹确认框。 */
    private fun doResetDefault() {
        showConfirmDialog(
            title = "恢复用户默认",
            bodyHtml = "将<b>本页</b>的所有设置项目恢复为当前默认值？",
            subText = "可在“默认值与阈值页”修改默认值",
            positiveText = "恢复"
        ) { resetDefaultNow() }
    }

    /** 实际执行「恢复本页默认」：把阈值页记录的默认值写回 PetConfig。 */
    private fun resetDefaultNow() {
        suppressSnapPreview = true
        lifecycleScope.launch {
            config.resetFromBounds(PetBounds(this@SettingsActivity), SettingsPage.MAIN)
            // 重置后数据绑定会回填开关并触发 listener，延迟解除屏蔽避免误显示示意图
            kotlinx.coroutines.delay(300)
            suppressSnapPreview = false
        }
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

    /** 实际执行「重置系统默认值与阈值」：bounds 回落系统出厂，并把系统默认值写回 PetConfig（同页联动）。 */
    private fun resetSystemNow() {
        suppressSnapPreview = true
        lifecycleScope.launch {
            PetBounds(this@SettingsActivity).resetSystem(SettingsPage.MAIN)
            config.resetFromBounds(PetBounds(this@SettingsActivity), SettingsPage.MAIN)
            // 关键：阈值已回落，必须按最新 bounds 重绑滑块行程（setupSliders 重绑 from/to 闭包），
            // 否则滑块仍按「先在阈值页改过的旧上下界」映射，导致重置后范围不更新、显示异常。
            bs = PetBounds(this@SettingsActivity).getBlocking(SettingsPage.MAIN).sliders.associateBy { it.key }
            setupSliders()
            kotlinx.coroutines.delay(300)
            suppressSnapPreview = false
        }
    }

    /** 重置软件所有设置：先弹确认框，确认后恢复全系统所有页（含阈值默认值/当前值）。 */
    private fun confirmResetAll() {
        showConfirmDialog(
            title = "重置软件所有设置",
            bodyHtml = "将<b>软件全部</b>的所有设置项目的当前值/默认值/阈值均重置为系统预设",
            subText = "软件所有、所有、所有",
            positiveText = "重置"
        ) { doResetAll() }
    }

    /** 执行全系统重置：遍历所有页重置阈值系统默认值，并恢复 PetConfig 出厂默认值。 */
    private fun doResetAll() {
        lifecycleScope.launch {
            val petBounds = PetBounds(this@SettingsActivity)
            SettingsPage.entries.forEach { petBounds.resetSystem(it) }
            config.reset()
            // 关键：本页阈值已回落，按最新 bounds 重绑滑块行程（其余页会在各自 onResume 重绑）。
            bs = petBounds.getBlocking(SettingsPage.MAIN).sliders.associateBy { it.key }
            setupSliders()
            suppressSnapPreview = true
            kotlinx.coroutines.delay(300)
            suppressSnapPreview = false
        }
    }

    // 离开设置页（含系统返回键、跳转关于页等）时兜底隐藏辅助线，避免残留
    override fun onPause() {
        super.onPause()
        PetService.instance?.hideGuide()
    }

    // 从阈值页等返回时热更新：重新读取最新阈值范围，重绑滑块行程与回写闭包，
    // 使「阈值设置」里修改的上下界/默认值立即生效（无需重新进入本页）。
    override fun onResume() {
        super.onResume()
        bs = PetBounds(this).getBlocking(SettingsPage.MAIN).sliders.associateBy { it.key }
        setupSliders()
    }

    /** 绑定（或重绑）五个业务滑块：黑色样式 + 业务上下界行程 + 气泡/回写闭包。
     *  每次调用都基于当前字段 [bs] 与最新 config，故 onCreate 与 onResume 可复用，
     *  使从阈值页返回后滑块行程与回写范围同步为新阈值。 */
    private fun setupSliders() {
        val c = kotlinx.coroutines.runBlocking { config.configFlow.first() }
        // 拖动“大小”滑块时显示越界红框彩蛋（与振动成对）；松手释放。
        scaleBar.apply { applyBlackStyle(); onUserTouch(
            onStart = { PetService.instance?.showPetParamGuide(this, "size") },
            onStop = { PetService.instance?.releaseGuide(this) }) }
        scaleBar.applyBizRange(bs["scale"]!!.from, bs["scale"]!!.to, c.scale,
            formatter = { "%.2f×".format(it) }) { s ->
            pendingScale = bizToSliderValue(s, bs["scale"]!!.from, bs["scale"]!!.to).toInt()
            EditSync.setText(scaleVal, "%.2f×".format(s))
            lifecycleScope.launch { config.update { it.copy(scale = s) } }
        }
        EditSync.bind(
            scaleVal,
            parse = { it.replace("×", "").toFloatOrNull() },
            format = { "%.2f×".format(it) },
            clamp = { it.coerceIn(bs["scale"]!!.from, bs["scale"]!!.to) },
            defaultValue = bs["scale"]!!.default
        ) { v ->
            scaleBar.value = bizToSliderValue(v, bs["scale"]!!.from, bs["scale"]!!.to)
            lifecycleScope.launch { config.update { it.copy(scale = v) } }
        }
        // 拖动“重力强度”滑块时显示越界红框彩蛋（与振动成对）；松手释放。
        gravityBar.apply { applyBlackStyle(); onUserTouch(
            onStart = { PetService.instance?.showPetParamGuide(this, "gravity") },
            onStop = { PetService.instance?.releaseGuide(this) }) }
        gravityBar.applyBizRange(bs["gravity"]!!.from, bs["gravity"]!!.to, c.gravity,
            formatter = { it.toInt().toString() }) { g ->
            pendingGravity = bizToSliderValue(g, bs["gravity"]!!.from, bs["gravity"]!!.to).toInt()
            EditSync.setText(gravityVal, g.toInt().toString())
            lifecycleScope.launch { config.update { it.copy(gravity = g) } }
        }
        EditSync.bind(
            gravityVal,
            parse = { it.toFloatOrNull() },
            clamp = { it.coerceIn(bs["gravity"]!!.from, bs["gravity"]!!.to) },
            defaultValue = bs["gravity"]!!.default
        ) { v ->
            gravityBar.value = bizToSliderValue(v, bs["gravity"]!!.from, bs["gravity"]!!.to)
            lifecycleScope.launch { config.update { it.copy(gravity = v) } }
        }
        // 拖动“反弹系数”滑块时显示越界红框彩蛋（与振动成对）；松手释放。
        reboundBar.apply { applyBlackStyle(); onUserTouch(
            onStart = { PetService.instance?.showPetParamGuide(this, "rebound") },
            onStop = { PetService.instance?.releaseGuide(this) }) }
        reboundBar.applyBizRange(bs["rebound"]!!.from, bs["rebound"]!!.to, c.reboundRatio,
            formatter = { "%.2f".format(it) }) { r ->
            pendingRebound = bizToSliderValue(r, bs["rebound"]!!.from, bs["rebound"]!!.to).toInt()
            EditSync.setText(reboundVal, "%.2f".format(r))
            lifecycleScope.launch { config.update { it.copy(reboundRatio = r) } }
        }
        EditSync.bind(
            reboundVal,
            parse = { it.toFloatOrNull() },
            clamp = { it.coerceIn(bs["rebound"]!!.from, bs["rebound"]!!.to) },
            defaultValue = bs["rebound"]!!.default
        ) { v ->
            reboundBar.value = bizToSliderValue(v, bs["rebound"]!!.from, bs["rebound"]!!.to)
            lifecycleScope.launch { config.update { it.copy(reboundRatio = v) } }
        }
        alphaBar.apply { applyBlackStyle(); trackTouchState() }
        alphaBar.applyBizRange(bs["alpha"]!!.from, bs["alpha"]!!.to, c.alpha * 100f,
            formatter = { "%d%%".format(it.toInt()) }) { a ->
            pendingAlpha = bizToSliderValue(a, bs["alpha"]!!.from, bs["alpha"]!!.to).toInt()
            EditSync.setText(alphaVal, "%d%%".format(a.toInt()))
            lifecycleScope.launch { config.update { it.copy(alpha = a / 100f) } }
        }
        EditSync.bind(
            alphaVal,
            parse = { it.replace("%", "").toFloatOrNull() },
            format = { "%d%%".format(it.toInt()) },
            clamp = { it.coerceIn(bs["alpha"]!!.from, bs["alpha"]!!.to) },
            defaultValue = bs["alpha"]!!.default
        ) { v ->
            alphaBar.value = bizToSliderValue(v, bs["alpha"]!!.from, bs["alpha"]!!.to)
            lifecycleScope.launch { config.update { it.copy(alpha = v / 100f) } }
        }
        // 拖动“最大速度”滑块时显示越界红框彩蛋（与振动成对）；松手释放。
        maxSpeedBar.apply { applyBlackStyle(); onUserTouch(
            onStart = { PetService.instance?.showPetParamGuide(this, "speed") },
            onStop = { PetService.instance?.releaseGuide(this) }) }
        maxSpeedBar.applyBizRange(bs["maxSpeed"]!!.from, bs["maxSpeed"]!!.to, c.maxSpeed,
            formatter = { it.toInt().toString() }) { s ->
            pendingMaxSpeed = bizToSliderValue(s, bs["maxSpeed"]!!.from, bs["maxSpeed"]!!.to).toInt()
            EditSync.setText(maxSpeedVal, s.toInt().toString())
            lifecycleScope.launch { config.update { it.copy(maxSpeed = s) } }
        }
        EditSync.bind(
            maxSpeedVal,
            parse = { it.toFloatOrNull() },
            format = { it.toInt().toString() },
            clamp = { it.coerceIn(bs["maxSpeed"]!!.from, bs["maxSpeed"]!!.to) },
            defaultValue = bs["maxSpeed"]!!.default
        ) { v ->
            maxSpeedBar.value = bizToSliderValue(v, bs["maxSpeed"]!!.from, bs["maxSpeed"]!!.to)
            lifecycleScope.launch { config.update { it.copy(maxSpeed = v) } }
        }
    }

    /** 获取系统真实状态栏高度（px），失败时回退 24dp */
    private fun getStatusBarHeight(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId)
        else (resources.displayMetrics.density * 24f).toInt()
    }
}
