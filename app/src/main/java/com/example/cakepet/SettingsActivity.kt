package com.example.cakepet

import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 设置页（对应 PC 端设置 + 安卓新增的四边重力）。
 * 实时写入 DataStore，浮窗 Service 自动响应。
 */
class SettingsActivity : ComponentActivity() {

    private lateinit var config: PetConfig

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        // 用纯黑占位条填充状态栏高度，与标题栏黑底连成整体覆盖状态栏；标题文字在标题栏内完整显示。
        val spacer = findViewById<android.view.View>(R.id.status_bar_spacer)
        spacer.layoutParams.height = getStatusBarHeight()
        spacer.requestLayout()
        config = PetConfig(this)

        val scaleBar = findViewById<SeekBar>(R.id.scale_bar)
        val scaleVal = findViewById<android.widget.TextView>(R.id.scale_value)
        val gravityBar = findViewById<SeekBar>(R.id.gravity_bar)
        val gravityVal = findViewById<android.widget.TextView>(R.id.gravity_value)
        val reboundBar = findViewById<SeekBar>(R.id.rebound_bar)
        val reboundVal = findViewById<android.widget.TextView>(R.id.rebound_value)

        val swTop = findViewById<Switch>(R.id.sw_top)
        val swBottom = findViewById<Switch>(R.id.sw_bottom)
        val swLeft = findViewById<Switch>(R.id.sw_left)
        val swRight = findViewById<Switch>(R.id.sw_right)
        val swRtop = findViewById<Switch>(R.id.sw_rtop)
        val swRbottom = findViewById<Switch>(R.id.sw_rbottom)
        val swRleft = findViewById<Switch>(R.id.sw_rleft)
        val swRright = findViewById<Switch>(R.id.sw_rright)
        val swStop = findViewById<Switch>(R.id.sw_stop)
        val swSbottom = findViewById<Switch>(R.id.sw_sbottom)
        val swSleft = findViewById<Switch>(R.id.sw_sleft)
        val swSright = findViewById<Switch>(R.id.sw_sright)
        val swRandom = findViewById<Switch>(R.id.sw_random)
        var suppressRandomSwitch = false
        // 具名 listener：避免 applyConfig 程序化 setChecked 时回写触发随机副作用
        val randomListener = android.widget.CompoundButton.OnCheckedChangeListener { _, b ->
            if (suppressRandomSwitch) return@OnCheckedChangeListener
            lifecycleScope.launch { config.update { it.copy(randomEnabled = b) } }
            if (b) {
                // 打开随机模式：立即随机一次
                PetService.instance?.triggerRandomNow()
            }
            // 关闭时不主动唤醒循环：循环自身在下一轮读 configFlow 发现 randomEnabled=false 会自然阻塞停止
        }
        val swVisible = findViewById<Switch>(R.id.sw_visible)
        val swClickThrough = findViewById<Switch>(R.id.sw_click_through)
        val swBounceVibrate = findViewById<Switch>(R.id.sw_bounce_vibrate)
        val swThinking = findViewById<Switch>(R.id.sw_thinking)
        val thinkingOffsetBar = findViewById<SeekBar>(R.id.thinking_offset_bar)
        val thinkingOffsetVal = findViewById<TextView>(R.id.thinking_offset_value)
        val swGravityEnabled = findViewById<Switch>(R.id.sw_gravity_enabled)
        val swTiltGravity = findViewById<Switch>(R.id.sw_tilt_gravity)
        val alphaBar = findViewById<SeekBar>(R.id.alpha_bar)
        val alphaVal = findViewById<TextView>(R.id.alpha_value)
        val maxSpeedBar = findViewById<SeekBar>(R.id.maxspeed_bar)
        val maxSpeedVal = findViewById<TextView>(R.id.maxspeed_value)
        val offTopBar = findViewById<SeekBar>(R.id.off_top_bar)
        val offTopVal = findViewById<TextView>(R.id.off_top_value)
        val offBottomBar = findViewById<SeekBar>(R.id.off_bottom_bar)
        val offBottomVal = findViewById<TextView>(R.id.off_bottom_value)
        val offLeftBar = findViewById<SeekBar>(R.id.off_left_bar)
        val offLeftVal = findViewById<TextView>(R.id.off_left_value)
        val offRightBar = findViewById<SeekBar>(R.id.off_right_bar)
        val offRightVal = findViewById<TextView>(R.id.off_right_value)
        val snapThresholdBar = findViewById<SeekBar>(R.id.snap_threshold_bar)
        val snapThresholdVal = findViewById<TextView>(R.id.snap_threshold_value)

        // 常驻观察 configFlow：菜单等外部改动任意字段后，设置页自动同步，避免残留旧状态。
        // applyConfig 直接 setChecked/setProgress 为程序化赋值，不触发写盘 listener，无回写死循环风险。
        fun applyConfig(c: PetConfigData) {
            scaleBar.progress = ((c.scale - 0.5f) / 0.05f).toInt().coerceIn(0, 50)
            scaleVal.text = "%.2f×".format(c.scale)
            gravityBar.progress = (c.gravity / 10000f * 100).toInt().coerceIn(0, 100)
            gravityVal.text = c.gravity.toInt().toString()
            reboundBar.progress = (c.reboundRatio / 2.0f * 100).toInt().coerceIn(0, 100)
            reboundVal.text = "%.2f".format(c.reboundRatio)
            swTop.isChecked = c.gravityTop
            swBottom.isChecked = c.gravityBottom
            swLeft.isChecked = c.gravityLeft
            swRight.isChecked = c.gravityRight
            swRtop.isChecked = c.reboundTop
            swRbottom.isChecked = c.reboundBottom
            swRleft.isChecked = c.reboundLeft
            swRright.isChecked = c.reboundRight
            swStop.isChecked = c.snapTop
            swSbottom.isChecked = c.snapBottom
            swSleft.isChecked = c.snapLeft
            swSright.isChecked = c.snapRight
            // 程序化回写随机总开关：临时移除 listener，避免触发随机副作用（否则关闭后又被刷新打开）
            suppressRandomSwitch = true
            swRandom.isChecked = c.randomEnabled
            suppressRandomSwitch = false
            swVisible.isChecked = c.visible
            swClickThrough.isChecked = c.clickThrough
            swBounceVibrate.isChecked = c.bounceVibrate
            swThinking.isChecked = c.thinkingEnabled
            thinkingOffsetBar.progress = ((c.thinkingOffset + 200f) / 500f * 100).toInt().coerceIn(0, 100)
            thinkingOffsetVal.text = c.thinkingOffset.toInt().toString()
            swGravityEnabled.isChecked = c.gravityEnabled
            swTiltGravity.isChecked = c.tiltGravity
            // 体感重力与四边定向重力互不干扰：四边开关始终可交互并显示原值，
            // 开启体感时由 PetPhysics.step() 的 tiltGravity 分支优先接管，四边仅被忽略；
            // 关闭体感后四边原值自动恢复生效，无需用户重新逐个开启。
            alphaBar.progress = (((c.alpha - 0.1f) / 0.9f) * 100).toInt().coerceIn(0, 100)
            alphaVal.text = "%d%%".format((c.alpha * 100).toInt())
            maxSpeedBar.progress = (c.maxSpeed / 20000f * 100).toInt().coerceIn(0, 100)
            maxSpeedVal.text = c.maxSpeed.toInt().toString()
            offTopBar.progress = ((c.offsetTop + 200f) / 500f * 100).toInt().coerceIn(0, 100)
            offTopVal.text = c.offsetTop.toInt().toString()
            offBottomBar.progress = ((c.offsetBottom + 200f) / 500f * 100).toInt().coerceIn(0, 100)
            offBottomVal.text = c.offsetBottom.toInt().toString()
            offLeftBar.progress = ((c.offsetLeft + 200f) / 500f * 100).toInt().coerceIn(0, 100)
            offLeftVal.text = c.offsetLeft.toInt().toString()
            offRightBar.progress = ((c.offsetRight + 200f) / 500f * 100).toInt().coerceIn(0, 100)
            offRightVal.text = c.offsetRight.toInt().toString()
            // 吸附判定迁移：滑块 0..300 直接对应阈值 0..300 px（默认 100）。
            snapThresholdBar.progress = c.snapThreshold.toInt().coerceIn(0, 300)
            snapThresholdVal.text = c.snapThreshold.toInt().toString()
        }
        lifecycleScope.launch {
            config.configFlow.collect { applyConfig(it) }
        }

        scaleBar.setOnSeekBarChangeListener(simple { p ->
            val v = 0.5f + p * 0.05f
            scaleVal.text = "%.2f×".format(v)
            lifecycleScope.launch { config.update { it.copy(scale = v) } }
        })
        gravityBar.setOnSeekBarChangeListener(simple { p ->
            val v = p / 100f * 10000f
            gravityVal.text = v.toInt().toString()
            lifecycleScope.launch { config.update { it.copy(gravity = v) } }
        })
        reboundBar.setOnSeekBarChangeListener(simple { p ->
            val v = p / 100f * 2.0f
            reboundVal.text = "%.2f".format(v)
            lifecycleScope.launch { config.update { it.copy(reboundRatio = v) } }
        })
        maxSpeedBar.setOnSeekBarChangeListener(simple { p ->
            val v = p / 100f * 20000f
            maxSpeedVal.text = v.toInt().toString()
            lifecycleScope.launch { config.update { it.copy(maxSpeed = v) } }
        })
        offTopBar.setOnSeekBarChangeListener(boundGuideSeekBar { p ->
            val v = -200f + p / 100f * 500f
            offTopVal.text = v.toInt().toString()
            lifecycleScope.launch { config.update { it.copy(offsetTop = v) } }
        })
        offBottomBar.setOnSeekBarChangeListener(boundGuideSeekBar { p ->
            val v = -200f + p / 100f * 500f
            offBottomVal.text = v.toInt().toString()
            lifecycleScope.launch { config.update { it.copy(offsetBottom = v) } }
        })
        offLeftBar.setOnSeekBarChangeListener(boundGuideSeekBar { p ->
            val v = -200f + p / 100f * 500f
            offLeftVal.text = v.toInt().toString()
            lifecycleScope.launch { config.update { it.copy(offsetLeft = v) } }
        })
        offRightBar.setOnSeekBarChangeListener(boundGuideSeekBar { p ->
            val v = -200f + p / 100f * 500f
            offRightVal.text = v.toInt().toString()
            lifecycleScope.launch { config.update { it.copy(offsetRight = v) } }
        })
        // 吸附判定迁移：滑块 0..300 直接对应阈值 0..300 px。
        snapThresholdBar.setOnSeekBarChangeListener(boundGuideSeekBar { p ->
            val v = p.toFloat()
            snapThresholdVal.text = v.toInt().toString()
            lifecycleScope.launch { config.update { it.copy(snapThreshold = v) } }
        })

        // 四边重力互斥联动：左右不能同时开、上下不能同时开（避免两套重力叠加成非法组合）。
        // 关键：只有【开启】本侧时才关闭对侧；【关闭】本侧时绝不碰对侧（保留原值）。
        // 旧逻辑写成 gravityOpposite=false（无论 on 与否都关对侧），会在 configFlow 刷新时
        // 程序化 setChecked 触发对侧 listener，把刚刚开启的对侧又误关掉（即“开下后下又被刷新关掉”）。
        // 不在此手动 setChecked 对侧，只写盘，由下方 configFlow.collect → applyConfig 统一同步 UI。
        swTop.setOnCheckedChangeListener { _, on ->
            lifecycleScope.launch { config.update { it.copy(gravityTop = on, gravityBottom = if (on) false else it.gravityBottom) } }
        }
        swBottom.setOnCheckedChangeListener { _, on ->
            lifecycleScope.launch { config.update { it.copy(gravityBottom = on, gravityTop = if (on) false else it.gravityTop) } }
        }
        swLeft.setOnCheckedChangeListener { _, on ->
            lifecycleScope.launch { config.update { it.copy(gravityLeft = on, gravityRight = if (on) false else it.gravityRight) } }
        }
        swRight.setOnCheckedChangeListener { _, on ->
            lifecycleScope.launch { config.update { it.copy(gravityRight = on, gravityLeft = if (on) false else it.gravityLeft) } }
        }
        swRtop.setOnCheckedChangeListener { _, b -> lifecycleScope.launch { config.update { it.copy(reboundTop = b) } } }
        swRbottom.setOnCheckedChangeListener { _, b -> lifecycleScope.launch { config.update { it.copy(reboundBottom = b) } } }
        swRleft.setOnCheckedChangeListener { _, b -> lifecycleScope.launch { config.update { it.copy(reboundLeft = b) } } }
        swRright.setOnCheckedChangeListener { _, b -> lifecycleScope.launch { config.update { it.copy(reboundRight = b) } } }
        swStop.setOnCheckedChangeListener { _, b -> lifecycleScope.launch { config.update { it.copy(snapTop = b) } } }
        swSbottom.setOnCheckedChangeListener { _, b -> lifecycleScope.launch { config.update { it.copy(snapBottom = b) } } }
        swSleft.setOnCheckedChangeListener { _, b -> lifecycleScope.launch { config.update { it.copy(snapLeft = b) } } }
        swSright.setOnCheckedChangeListener { _, b -> lifecycleScope.launch { config.update { it.copy(snapRight = b) } } }
        swRandom.setOnCheckedChangeListener(randomListener)
        // 进入随机模式设置页
        findViewById<android.view.View>(R.id.row_random_settings).setOnClickListener {
            startActivity(android.content.Intent(this, RandomSettingsActivity::class.java))
        }
        swVisible.setOnCheckedChangeListener { _, b -> lifecycleScope.launch { config.update { it.copy(visible = b) } } }
        swGravityEnabled.setOnCheckedChangeListener { _, b -> lifecycleScope.launch { config.update { it.copy(gravityEnabled = b) } } }
        swClickThrough.setOnCheckedChangeListener { _, b -> lifecycleScope.launch { config.update { it.copy(clickThrough = b) } } }
        swBounceVibrate.setOnCheckedChangeListener { _, b -> lifecycleScope.launch { config.update { it.copy(bounceVibrate = b) } } }
        swThinking.setOnCheckedChangeListener { _, b -> lifecycleScope.launch { config.update { it.copy(thinkingEnabled = b) } } }
        thinkingOffsetBar.setOnSeekBarChangeListener(trayGuideSeekBar { p ->
            val v = -200f + p / 100f * 500f
            thinkingOffsetVal.text = v.toInt().toString()
            lifecycleScope.launch { config.update { it.copy(thinkingOffset = v) } }
        })
        // 体感重力：只切换 tiltGravity 标志，不改动四边定向重力的存储值与 UI 状态。
        // 物理层 PetPhysics.step() 在 tiltGravity=true 时优先走体感分支（忽略四边），
        // 关闭后四边原配置自动恢复生效，用户无需重新逐个开启。
        swTiltGravity.setOnCheckedChangeListener { _, on ->
            lifecycleScope.launch {
                config.update { c -> c.copy(tiltGravity = on) }
            }
        }
        alphaBar.setOnSeekBarChangeListener(simple { p ->
            val v = (0.1f + p / 100f * 0.9f).coerceIn(0.1f, 1f)
            alphaVal.text = "%d%%".format((v * 100).toInt())
            lifecycleScope.launch { config.update { it.copy(alpha = v) } }
        })

        findViewById<android.widget.TextView>(R.id.btn_reset).setOnClickListener {
            lifecycleScope.launch { config.reset() }
        }
        findViewById<android.widget.TextView>(R.id.btn_about).setOnClickListener {
            startActivity(android.content.Intent(this, AboutActivity::class.java))
        }
        findViewById<android.widget.ImageView>(R.id.iv_back).setOnClickListener { finish() }
    }

    private fun simple(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        // 仅用户拖动时写盘；程序化 setProgress（applyConfig 同步）不触发写盘，避免回写死循环
        override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
            if (fromUser) onChange(p)
        }
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    }

    // 边界偏移滑块：拖动时显示四边边界黑色虚线辅助线，松手隐藏
    private fun boundGuideSeekBar(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
            if (fromUser) { onChange(p); PetService.instance?.showBoundGuide() }
        }
        override fun onStartTrackingTouch(sb: SeekBar?) { PetService.instance?.showBoundGuide() }
        override fun onStopTrackingTouch(sb: SeekBar?) { PetService.instance?.hideGuide() }
    }

    // 碎碎念偏移滑块：拖动时显示碎碎念水平黑色虚线辅助线，松手隐藏
    private fun trayGuideSeekBar(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
            if (fromUser) { onChange(p); PetService.instance?.showTrayGuide() }
        }
        override fun onStartTrackingTouch(sb: SeekBar?) { PetService.instance?.showTrayGuide() }
        override fun onStopTrackingTouch(sb: SeekBar?) { PetService.instance?.hideGuide() }
    }

    // 离开设置页（含系统返回键、跳转关于页等）时兜底隐藏辅助线，避免残留
    override fun onPause() {
        super.onPause()
        PetService.instance?.hideGuide()
    }

    /** 获取系统真实状态栏高度（px），失败时回退 24dp */
    private fun getStatusBarHeight(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId)
        else (resources.displayMetrics.density * 24f).toInt()
    }
}
