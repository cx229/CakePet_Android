package com.cx.cakepet

import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.ImageView
import android.widget.Switch
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.slider.Slider
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 移动设置页：动动动（移动总开关 / 最大速度）、重重重（重力抛掷 / 体感重力 / 重力强度 / 四边重力）、
 * 弹弹弹（边缘反弹 / 反弹振动 / 反弹系数 / 四边弹力）。与主设置页“动动动”入口行对应。
 * 阈值/默认值由 MovementThresholdActivity 维护，本页只负责实时读写 PetConfig。
 */
class MovementSettingsActivity : AppCompatActivity() {
    private lateinit var config: PetConfig
    private val roleId by lazy { intent?.getStringExtra("role_id") ?: RoleRegistry.DEFAULT.id }
    private lateinit var roleBounds: PetBounds
    // 屏蔽开关 listener 回调：applyConfig 程序化回填开关时置 true，避免回流 setChecked 触发 listener 又写盘
    private var suppressSwitch: Boolean = false
    // 阈值范围（来自当前角色 PetBounds，用于滑块业务上下界/默认值）
    private lateinit var bs: Map<String, SliderBound>
    // 各滑块「待确认值」：挡住滞后的旧发射回流
    private var pendingMaxSpeed: Int? = null
    private var pendingGravity: Int? = null
    private var pendingRebound: Int? = null
    private var pendingThrowCoeff: Int? = null
    private var pendingLeapProb: Int? = null
    private var pendingForageSpeed: Int? = null
    private lateinit var swWallClimb: Switch
    private lateinit var swLeap: Switch
    private lateinit var swAutoForage: Switch
    private lateinit var maxSpeedBar: Slider
    private lateinit var maxSpeedVal: EditText
    private lateinit var gravityBar: Slider
    private lateinit var gravityVal: EditText
    private lateinit var reboundBar: Slider
    private lateinit var reboundVal: EditText
    private lateinit var throwCoeffBar: Slider
    private lateinit var throwCoeffVal: EditText
    private lateinit var leapProbBar: Slider
    private lateinit var leapProbVal: EditText
    private lateinit var forageSpeedBar: Slider
    private lateinit var forageSpeedVal: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_movement_settings)
        // 标题下方小字灰色：当前页面所属角色名
        findViewById<android.widget.TextView>(R.id.tv_title_sub).text = RoleStore.resolveDisplayName(this, roleId)
        // 纯黑占位条填充状态栏高度，与标题栏黑底连成整体覆盖状态栏
        val spacer = findViewById<android.view.View>(R.id.status_bar_spacer)
        spacer.layoutParams.height = getStatusBarHeight()
        spacer.requestLayout()
        config = PetConfig.forRole(this, roleId)
        roleBounds = PetBounds.forRole(this, roleId)
        bs = roleBounds.getBlocking(SettingsPage.MOVEMENT).sliders.associateBy { it.key }

        findViewById<ImageView>(R.id.iv_back).setOnClickListener { finish() }
        // 右上角“更多”图标：弹出菜单（阈值等）
        val btnMore = findViewById<ImageView>(R.id.btn_more)
        val moveMoreMenu = PopupMenuHelper(
            anchor = btnMore,
            popupLayoutRes = R.layout.popup_settings_more_simple,
            items = mapOf(
                R.id.item_threshold to {
                    startActivity(android.content.Intent(this, MovementThresholdActivity::class.java).apply { putExtra("role_id", roleId) })
                }
            )
        )
        btnMore.setOnClickListener { moveMoreMenu.show() }

        // 滑块
        maxSpeedBar = findViewById(R.id.maxspeed_bar)
        maxSpeedVal = findViewById(R.id.maxspeed_value)
        gravityBar = findViewById(R.id.gravity_bar)
        gravityVal = findViewById(R.id.gravity_value)
        reboundBar = findViewById(R.id.rebound_bar)
        reboundVal = findViewById(R.id.rebound_value)
        throwCoeffBar = findViewById(R.id.throwcoeff_bar)
        throwCoeffVal = findViewById(R.id.throwcoeff_value)
        leapProbBar = findViewById(R.id.leap_prob_bar)
        leapProbVal = findViewById(R.id.leap_prob_value)

        // 开关
        val swMoveEnabled = findViewById<Switch>(R.id.sw_move_enabled)
        val swGravityEnabled = findViewById<Switch>(R.id.sw_gravity_enabled)
        val swTiltGravity = findViewById<Switch>(R.id.sw_tilt_gravity)
        val swTop = findViewById<Switch>(R.id.sw_top)
        val swBottom = findViewById<Switch>(R.id.sw_bottom)
        val swLeft = findViewById<Switch>(R.id.sw_left)
        val swRight = findViewById<Switch>(R.id.sw_right)
        val swReboundEnabled = findViewById<Switch>(R.id.sw_rebound_enabled)
        val swBounceVibrate = findViewById<Switch>(R.id.sw_bounce_vibrate)
        val swRtop = findViewById<Switch>(R.id.sw_rtop)
        val swRbottom = findViewById<Switch>(R.id.sw_rbottom)
        val swRleft = findViewById<Switch>(R.id.sw_rleft)
        val swRright = findViewById<Switch>(R.id.sw_rright)
        val swThrow = findViewById<Switch>(R.id.sw_throw)
        swWallClimb = findViewById(R.id.sw_wallclimb)
        swLeap = findViewById(R.id.sw_leap)
        swAutoForage = findViewById(R.id.sw_auto_forage)

        fun applyConfig(c: PetConfigData) {
            // 用 setValueFromConfig 而非直接赋值：换算可能是小数与 stepSize 冲突；用户拖动中跳过回写避免抖动
            if (maxSpeedBar.setValueFromConfig(bizToSliderValue(c.maxSpeed, bs["maxSpeed"]!!.from, bs["maxSpeed"]!!.to), pendingMaxSpeed)) {
                pendingMaxSpeed = null
                EditSync.setText(maxSpeedVal, c.maxSpeed.toInt().toString())
            }
            if (gravityBar.setValueFromConfig(bizToSliderValue(c.gravity, bs["gravity"]!!.from, bs["gravity"]!!.to), pendingGravity)) {
                pendingGravity = null
                EditSync.setText(gravityVal, c.gravity.toInt().toString())
            }
            if (reboundBar.setValueFromConfig(bizToSliderValue(c.reboundRatio, bs["rebound"]!!.from, bs["rebound"]!!.to), pendingRebound)) {
                pendingRebound = null
                EditSync.setText(reboundVal, "%.2f".format(c.reboundRatio))
            }
            if (throwCoeffBar.setValueFromConfig(bizToSliderValue(c.throwCoeff, bs["throwCoeff"]!!.from, bs["throwCoeff"]!!.to), pendingThrowCoeff)) {
                pendingThrowCoeff = null
                EditSync.setText(throwCoeffVal, "%.2f".format(c.throwCoeff))
            }
            if (leapProbBar.setValueFromConfig(bizToSliderValue(c.leapProbability, bs["leapProbability"]!!.from, bs["leapProbability"]!!.to), pendingLeapProb)) {
                pendingLeapProb = null
                EditSync.setText(leapProbVal, "%.2f".format(c.leapProbability))
            }
            if (forageSpeedBar.setValueFromConfig(bizToSliderValue(c.forageSpeed, bs["forageSpeed"]!!.from, bs["forageSpeed"]!!.to), pendingForageSpeed)) {
                pendingForageSpeed = null
                EditSync.setText(forageSpeedVal, c.forageSpeed.toInt().toString())
            }
            // 程序化回填所有开关用 suppressSwitch 包住：回流 setChecked 不再触发 listener 写盘
            suppressSwitch = true
            swMoveEnabled.isChecked = c.moveEnabled
            swTop.isChecked = c.gravityTop
            swBottom.isChecked = c.gravityBottom
            swLeft.isChecked = c.gravityLeft
            swRight.isChecked = c.gravityRight
            swRtop.isChecked = c.reboundTop
            swRbottom.isChecked = c.reboundBottom
            swRleft.isChecked = c.reboundLeft
            swRright.isChecked = c.reboundRight
            swGravityEnabled.isChecked = c.gravityEnabled
            swTiltGravity.isChecked = c.tiltGravity
            swReboundEnabled.isChecked = c.reboundEnabled
            swBounceVibrate.isChecked = c.bounceVibrate
            swThrow.isChecked = c.throwEnabled
            swWallClimb.isChecked = c.wallClimb
            swLeap.isChecked = c.leapEnabled
            swAutoForage.isChecked = c.autoForage
            suppressSwitch = false
        }
        lifecycleScope.launch {
            config.configFlow.collect { applyConfig(it) }
        }

        setupSliders()

        // 四边重力互斥联动：只有【开启】本侧时才关闭对侧；【关闭】本侧时绝不碰对侧
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
        // 移动总开关：关闭后「酥」静止（不运动/不掉落）
        swMoveEnabled.setOnCheckedChangeListener { _, b ->
            if (suppressSwitch) return@setOnCheckedChangeListener
            lifecycleScope.launch { config.update { it.copy(moveEnabled = b) } }
        }
        swGravityEnabled.setOnCheckedChangeListener { _, b ->
            if (suppressSwitch) return@setOnCheckedChangeListener
            lifecycleScope.launch { config.update { it.copy(gravityEnabled = b) } }
        }
        swBounceVibrate.setOnCheckedChangeListener { _, b ->
            if (suppressSwitch) return@setOnCheckedChangeListener
            lifecycleScope.launch { config.update { it.copy(bounceVibrate = b) } }
        }
        swReboundEnabled.setOnCheckedChangeListener { _, b ->
            if (suppressSwitch) return@setOnCheckedChangeListener
            lifecycleScope.launch { config.update { it.copy(reboundEnabled = b) } }
        }
        swThrow.setOnCheckedChangeListener { _, b ->
            if (suppressSwitch) return@setOnCheckedChangeListener
            lifecycleScope.launch { config.update { it.copy(throwEnabled = b) } }
        }
        swWallClimb.setOnCheckedChangeListener { _, b ->
            if (suppressSwitch) return@setOnCheckedChangeListener
            lifecycleScope.launch { config.update { it.copy(wallClimb = b) } }
        }
        swLeap.setOnCheckedChangeListener { _, b ->
            if (suppressSwitch) return@setOnCheckedChangeListener
            lifecycleScope.launch { config.update { it.copy(leapEnabled = b) } }
        }
        swAutoForage.setOnCheckedChangeListener { _, b ->
            if (suppressSwitch) return@setOnCheckedChangeListener
            lifecycleScope.launch { config.update { it.copy(autoForage = b) } }
        }
        // 体感重力：只切换 tiltGravity 标志，不改动四边定向重力的存储值与 UI 状态
        swTiltGravity.setOnCheckedChangeListener { _, on ->
            if (suppressSwitch) return@setOnCheckedChangeListener
            lifecycleScope.launch { config.update { c -> c.copy(tiltGravity = on) } }
        }
    }

    override fun onResume() {
        super.onResume()
        // 从阈值页返回后重新读取阈值，使滑块行程与回写范围同步为新阈值
        bs = roleBounds.getBlocking(SettingsPage.MOVEMENT).sliders.associateBy { it.key }
        setupSliders()
    }

    /** 绑定三个业务滑块：黑色样式 + 业务上下界行程 + 气泡/回写闭包。 */
    private fun setupSliders() {
        val c = kotlinx.coroutines.runBlocking { config.configFlow.first() }
        // 拖动“最大速度”滑块时显示越界红框彩蛋（与振动成对）；松手释放
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
        // 拖动“重力强度”滑块时显示越界红框彩蛋；松手释放
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
        // 拖动“反弹系数”滑块时显示越界红框彩蛋；松手释放
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
        // 拖动“抛掷系数”滑块时显示越界红框彩蛋；松手释放
        throwCoeffBar.applyBlackStyle()
        throwCoeffBar.applyBizRange(bs["throwCoeff"]!!.from, bs["throwCoeff"]!!.to, c.throwCoeff,
            formatter = { "%.2f".format(it) }) { r ->
            pendingThrowCoeff = bizToSliderValue(r, bs["throwCoeff"]!!.from, bs["throwCoeff"]!!.to).toInt()
            EditSync.setText(throwCoeffVal, "%.2f".format(r))
            lifecycleScope.launch { config.update { it.copy(throwCoeff = r) } }
        }
        EditSync.bind(
            throwCoeffVal,
            parse = { it.toFloatOrNull() },
            clamp = { it.coerceIn(bs["throwCoeff"]!!.from, bs["throwCoeff"]!!.to) },
            defaultValue = bs["throwCoeff"]!!.default
        ) { v ->
            throwCoeffBar.value = bizToSliderValue(v, bs["throwCoeff"]!!.from, bs["throwCoeff"]!!.to)
            lifecycleScope.launch { config.update { it.copy(throwCoeff = v) } }
        }
        // 飞跃概率（0-1 小数）
        leapProbBar.applyBlackStyle()
        leapProbBar.applyBizRange(bs["leapProbability"]!!.from, bs["leapProbability"]!!.to, c.leapProbability,
            formatter = { "%.2f".format(it) }) { r ->
            pendingLeapProb = bizToSliderValue(r, bs["leapProbability"]!!.from, bs["leapProbability"]!!.to).toInt()
            EditSync.setText(leapProbVal, "%.2f".format(r))
            lifecycleScope.launch { config.update { it.copy(leapProbability = r) } }
        }
        EditSync.bind(
            leapProbVal,
            parse = { it.toFloatOrNull() },
            format = { "%.2f".format(it) },
            clamp = { it.coerceIn(bs["leapProbability"]!!.from, bs["leapProbability"]!!.to) },
            defaultValue = bs["leapProbability"]!!.default
        ) { v ->
            leapProbBar.value = bizToSliderValue(v, bs["leapProbability"]!!.from, bs["leapProbability"]!!.to)
            lifecycleScope.launch { config.update { it.copy(leapProbability = v) } }
        }
        // 觅食速度：攀岩走壁自动觅食时沿墙奔向食物的速度（px/s）
        forageSpeedBar = findViewById(R.id.foragespeed_bar)!!
        forageSpeedVal = findViewById(R.id.foragespeed_value)!!
        forageSpeedBar.applyBlackStyle()
        forageSpeedBar.applyBizRange(bs["forageSpeed"]!!.from, bs["forageSpeed"]!!.to, c.forageSpeed,
            formatter = { it.toInt().toString() }) { s ->
            pendingForageSpeed = bizToSliderValue(s, bs["forageSpeed"]!!.from, bs["forageSpeed"]!!.to).toInt()
            EditSync.setText(forageSpeedVal, s.toInt().toString())
            lifecycleScope.launch { config.update { it.copy(forageSpeed = s) } }
        }
        EditSync.bind(forageSpeedVal,
            parse = { it.toFloatOrNull() },
            format = { "%.0f".format(it) },
            clamp = { it.coerceIn(bs["forageSpeed"]!!.from, bs["forageSpeed"]!!.to) },
            defaultValue = bs["forageSpeed"]!!.default
        ) { v ->
            forageSpeedBar.value = bizToSliderValue(v, bs["forageSpeed"]!!.from, bs["forageSpeed"]!!.to)
            lifecycleScope.launch { config.update { it.copy(forageSpeed = v) } }
        }
    }

    /** 获取系统真实状态栏高度（px），失败时回退 24dp */
    private fun getStatusBarHeight(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId)
        else (resources.displayMetrics.density * 24).toInt()
    }
}
