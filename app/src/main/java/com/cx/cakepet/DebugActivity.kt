package com.cx.cakepet

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
import android.view.View
import android.widget.TextView
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class DebugActivity : AppCompatActivity() {
    // 点输入框以外区域即让当前输入框失焦，触发「失焦即提交」
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        commitInputOnTouchOutside(ev)
        return super.dispatchTouchEvent(ev)
    }

    private lateinit var config: PetConfig
    private lateinit var rowCtrlBox: View
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug)
        // 标题下方小字灰色：当前页面所属角色名（调试页内联取 roleId，无统一字段）
        val debugRoleId = intent?.getStringExtra("role_id") ?: RoleRegistry.DEFAULT.id
        findViewById<android.widget.TextView>(R.id.tv_title_sub).text = RoleStore.resolveDisplayName(this, debugRoleId)
        // 用纯黑占位条填充状态栏高度，与标题栏黑底连成整体覆盖状态栏；标题文字在标题栏内完整显示。
        val spacer = findViewById<android.view.View>(R.id.status_bar_spacer)
        spacer.layoutParams.height = getStatusBarHeight()
        spacer.requestLayout()
        findViewById<android.widget.ImageView>(R.id.iv_back).setOnClickListener { finish() }

        config = PetConfig.forRole(this, intent?.getStringExtra("role_id") ?: RoleRegistry.DEFAULT.id)
        val swDebug = findViewById<Switch>(R.id.sw_debug)
        val swBoundOffset = findViewById<Switch>(R.id.sw_bound_offset)
        val swSnapOffset = findViewById<Switch>(R.id.sw_snap_offset)
        val rgHitMode = findViewById<RadioGroup>(R.id.rg_hit_mode)
        rowCtrlBox = findViewById(R.id.row_ctrl_box)
        val sbCtrlBoxW = findViewById<SeekBar>(R.id.sb_ctrl_box_w)
        val sbCtrlBoxH = findViewById<SeekBar>(R.id.sb_ctrl_box_h)
        val sbCtrlBoxVo = findViewById<SeekBar>(R.id.sb_ctrl_box_vo)
        val tvCtrlBoxW = findViewById<android.widget.EditText>(R.id.tv_ctrl_box_w)
        val tvCtrlBoxH = findViewById<android.widget.EditText>(R.id.tv_ctrl_box_h)
        val tvCtrlBoxVo = findViewById<android.widget.EditText>(R.id.tv_ctrl_box_vo)
        val swForceNightOn = findViewById<Switch>(R.id.sw_force_night_on)
        val swForceNightOff = findViewById<Switch>(R.id.sw_force_night_off)
        val sbStaticTop = findViewById<SeekBar>(R.id.sb_static_top)
        val sbStaticBottom = findViewById<SeekBar>(R.id.sb_static_bottom)
        val tvStaticTop = findViewById<android.widget.EditText>(R.id.tv_static_top)
        val tvStaticBottom = findViewById<android.widget.EditText>(R.id.tv_static_bottom)
        val swImageBorder = findViewById<Switch>(R.id.sw_image_border)
        val swControlBorder = findViewById<Switch>(R.id.sw_control_border)
        val swFreezeBorder = findViewById<Switch>(R.id.sw_freeze_border)
        val swForceOpaque = findViewById<Switch>(R.id.sw_force_opaque)
        val swFeedBorder = findViewById<Switch>(R.id.sw_feed_border)
        val swHideControlLayer = findViewById<Switch>(R.id.sw_hide_control_layer)
        val swHideStage = findViewById<Switch>(R.id.sw_hide_stage)
        val swSingleTrayMsg = findViewById<Switch>(R.id.sw_single_tray_msg)
        val sbPetCount = findViewById<SeekBar>(R.id.sb_pet_count)
        val tvPetCount = findViewById<TextView>(R.id.tv_pet_count)

        // 像素级点击测试入口（右侧 >）：进入独立测试页，验证普通 View 与 WindowManager 悬浮窗的透明区穿透
        findViewById<android.view.View>(R.id.row_pixel_hit_test)
            .setOnClickListener { startActivity(Intent(this, TestHitActivity::class.java)) }

        // 版本号：全局共享（与 build.gradle.kts 的 versionName 同步）
        findViewById<android.widget.TextView>(R.id.tv_version).text = "版本：v${AppVersion.name}"

        // 无障碍权限状态：仅展示是否已开启（用户需在系统设置中手动开启，本应用不弹请求弹窗）。
        val tvAccessibility = findViewById<TextView>(R.id.tv_accessibility)
        tvAccessibility.text = "无障碍权限：${if (isAccessibilityServiceEnabled()) "已开启" else "未开启"}"
        // 点击跳转到系统无障碍设置页，方便用户手动开启。
        tvAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        lifecycleScope.launch {
            val c = config.configFlow.first()
            swDebug.isChecked = c.showDebug
            swBoundOffset.isChecked = c.showBoundOffset
            swSnapOffset.isChecked = c.showSnapOffset
            rgHitMode.check(
                when (c.hitMode) {
                    ConfigDefaults.HIT_PIXEL -> R.id.rb_hit_pixel
                    ConfigDefaults.HIT_CORE -> R.id.rb_hit_core
                    else -> R.id.rb_hit_boundary
                }
            )
            updateCtrlBoxEnabled(c.hitMode == ConfigDefaults.HIT_CORE)
            sbCtrlBoxW.progress = c.ctrlBoxWidth.toInt().coerceIn(0, 256)
            sbCtrlBoxH.progress = c.ctrlBoxHeight.toInt().coerceIn(0, 256)
            sbCtrlBoxVo.progress = (c.ctrlBoxVOffset + 128f).toInt().coerceIn(0, 256)
            EditSync.setText(tvCtrlBoxW, sbCtrlBoxW.progress.toString())
            EditSync.setText(tvCtrlBoxH, sbCtrlBoxH.progress.toString())
            EditSync.setText(tvCtrlBoxVo, (sbCtrlBoxVo.progress - 128).toString())
            swForceNightOn.isChecked = c.forceNightOn
            swForceNightOff.isChecked = c.forceNightOff
            sbStaticTop.progress = (c.staticTopBound + 200).coerceIn(0, 600)
            sbStaticBottom.progress = (c.staticBottomBound + 200).coerceIn(0, 600)
            EditSync.setText(tvStaticTop, c.staticTopBound.toString())
            EditSync.setText(tvStaticBottom, c.staticBottomBound.toString())
            swImageBorder.isChecked = c.showImageBorder
            swControlBorder.isChecked = c.showControlBorder
            swFreezeBorder.isChecked = c.freezeBorderShow
            swFeedBorder.isChecked = c.feedingBorderShow
            swForceOpaque.isChecked = c.forceOpaque
            swHideControlLayer.isChecked = c.hideControlLayer
            swHideStage.isChecked = c.hideStage
            swSingleTrayMsg.isChecked = c.singleTrayMsg
            sbPetCount.progress = c.petCount.coerceIn(1, 20)
            tvPetCount.text = c.petCount.toString()
        }
        swDebug.setOnCheckedChangeListener { _, b ->
            lifecycleScope.launch { config.update { it.copy(showDebug = b) } }
        }
        swBoundOffset.setOnCheckedChangeListener { _, b ->
            lifecycleScope.launch { config.update { it.copy(showBoundOffset = b) } }
        }
        swSnapOffset.setOnCheckedChangeListener { _, b ->
            lifecycleScope.launch { config.update { it.copy(showSnapOffset = b) } }
        }
        rgHitMode.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.rb_hit_pixel -> ConfigDefaults.HIT_PIXEL
                R.id.rb_hit_core -> ConfigDefaults.HIT_CORE
                else -> ConfigDefaults.HIT_BOUNDARY
            }
            updateCtrlBoxEnabled(mode == ConfigDefaults.HIT_CORE)
            lifecycleScope.launch { config.update { it.copy(hitMode = mode) } }
        }
        bindCtrlBox(sbCtrlBoxW, tvCtrlBoxW, 0) { cfg, real -> cfg.copy(ctrlBoxWidth = real.toFloat()) }
        bindCtrlBox(sbCtrlBoxH, tvCtrlBoxH, 0) { cfg, real -> cfg.copy(ctrlBoxHeight = real.toFloat()) }
        bindCtrlBox(sbCtrlBoxVo, tvCtrlBoxVo, 128) { cfg, real -> cfg.copy(ctrlBoxVOffset = real.toFloat()) }
        // 静态边界（定格/食物绝对显示边界）：范围 -200..400，SeekBar 偏移 200（progress = 真实值 + 200）。
        bindStaticBound(sbStaticTop, tvStaticTop) { cfg, real -> cfg.copy(staticTopBound = real) }
        bindStaticBound(sbStaticBottom, tvStaticBottom) { cfg, real -> cfg.copy(staticBottomBound = real) }
        // 夜间模式强制开关：默认均关；强制打开优先级 > 强制关闭。
        // 二者互斥——打开其一即关闭另一个，保证语义唯一。
        swForceNightOn.setOnCheckedChangeListener { _, b ->
            lifecycleScope.launch {
                config.update {
                    it.copy(forceNightOn = b, forceNightOff = if (b) false else it.forceNightOff)
                }
            }
            if (b) swForceNightOff.isChecked = false
        }
        swForceNightOff.setOnCheckedChangeListener { _, b ->
            lifecycleScope.launch {
                config.update {
                    it.copy(forceNightOff = b, forceNightOn = if (b) false else it.forceNightOn)
                }
            }
            if (b) swForceNightOn.isChecked = false
        }
        // 调试边框：显示边框（图片窗口+锚点十字）/ 控制边框（命中可视化），分别控制 PetView 的两个调试层。
        swImageBorder.setOnCheckedChangeListener { _, b ->
            lifecycleScope.launch { config.update { it.copy(showImageBorder = b) } }
        }
        swControlBorder.setOnCheckedChangeListener { _, b ->
            lifecycleScope.launch { config.update { it.copy(showControlBorder = b) } }
        }
        // 显示定格边框：开启后在每块定格显示层叠加红色真实窗口边框（调试用）
        swFreezeBorder.setOnCheckedChangeListener { _, b ->
            lifecycleScope.launch { config.update { it.copy(freezeBorderShow = b) } }
        }
        // 显示投喂边框：开启后每条支鱼浮窗叠加红色真实窗口边框（调试用）
        swFeedBorder.setOnCheckedChangeListener { _, b ->
            lifecycleScope.launch { config.update { it.copy(feedingBorderShow = b) } }
        }
        // 强制显示无透明（默认关）：开启让显示窗去掉 FLAG_NOT_TOUCHABLE，避开系统对透明悬浮窗的半透明钳制，宠物 100% 不透明；
        // 与点击穿透/隐藏控制层互斥（二者开启时显示窗恢复 NOT_TOUCHABLE，见 PetService.applyDisplayTouchFlags）。
        swForceOpaque.setOnCheckedChangeListener { _, b ->
            lifecycleScope.launch { config.update { it.copy(forceOpaque = b) } }
        }
        swHideControlLayer.setOnCheckedChangeListener { _, b ->
            lifecycleScope.launch { config.update { it.copy(hideControlLayer = b) } }
        }
        swHideStage.setOnCheckedChangeListener { _, b ->
            lifecycleScope.launch { config.update { it.copy(hideStage = b) } }
        }
        // 仅单个碎碎念：开启=仅主实例显示；关闭=每个实例各自显示（默认开）。
        swSingleTrayMsg.setOnCheckedChangeListener { _, b ->
            lifecycleScope.launch { config.update { it.copy(singleTrayMsg = b) } }
        }
        // 酥的数量：整数 1-10，默认 1。改变后写入配置，PetService 监听 petCount 变化自动重建宠物实例。
        // 注意：原生 SeekBar 默认 min=0、max=10 共 0-10 档；拖动可能暂显 0，松手 coerceIn(1,10) 保证落在 1-10。
        sbPetCount.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvPetCount.text = progress.toString()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val n = seekBar?.progress?.coerceIn(1, 20) ?: 1
                tvPetCount.text = n.toString()
                lifecycleScope.launch { config.update { it.copy(petCount = n) } }
            }
        })
    }

    /** 获取系统真实状态栏高度（px），失败时回退 24dp */
    private fun getStatusBarHeight(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId)
        else (resources.displayMetrics.density * 24f).toInt()
    }

    /**
     * 判定本应用的 AccessibilityService 是否已在系统设置中开启。
     * 安卓的无障碍服务不是普通运行时权限，无法用 checkSelfPermission 判定，
     * 只能通过 Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES 读取已启用服务列表，
     * 其中每项形如 "包名/完整服务类名"，匹配本应用包名 + CakePetAccessibilityService 即可。
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = ComponentName(this, CakePetAccessibilityService::class.java)
            .flattenToString()  // "com.cx.cakepet/com.cx.cakepet.CakePetAccessibilityService"
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(":").any { it.equals(expected, ignoreCase = true) }
    }

    /** 核心(脚底盒)命中区尺寸仅在 HIT_CORE 模式下有意义，非该模式时灰显禁用。 */
    private fun updateCtrlBoxEnabled(enabled: Boolean) {
        rowCtrlBox.isEnabled = enabled
    }

    /**
     * 绑定脚底盒尺寸：SeekBar ↔ EditText 双向联动（EditSync）。
     * - 宽度/高度范围 0..256（128 基数），偏移范围 -128..128（SeekBar progress 加 128 偏置）。
     * - 滑块拖动：实时回填输入框文本并写配置；输入框输入：经 EditSync 解析 + clamp 校正后写配置并回设滑块。
     * - writeReal 接收「真实值」（已扣除 offset），调用方直接写入对应字段，offset 仅在 UI 层处理。
     */
    private fun bindCtrlBox(
        sb: SeekBar, et: android.widget.EditText, offset: Int,
        writeReal: (PetConfigData, Int) -> PetConfigData
    ) {
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val real = progress - offset
                    EditSync.setText(et, real.toString())
                    lifecycleScope.launch { config.update { writeReal(it, real) } }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        EditSync.bind(
            et,
            parse = { it.toIntOrNull() },
            format = { it.toString() },
            clamp = { it.coerceIn(-offset, 256 - offset) },
            defaultValue = 0
        ) { v ->
            sb.progress = (v + offset).coerceIn(0, 256)
            lifecycleScope.launch { config.update { writeReal(it, v) } }
        }
    }

    /**
     * 绑定静态边界（定格/食物绝对显示边界）：SeekBar ↔ EditText 双向联动（EditSync）。
     * - 取值范围固定 -200..400；SeekBar max=600，UI 层加 200 偏置（progress = 真实值 + 200）。
     * - 滑块拖动：实时回填输入框并写配置；输入框输入：EditSync 解析 + clamp(-200,400) 校正后写配置并回设滑块。
     */
    private fun bindStaticBound(
        sb: SeekBar, et: android.widget.EditText,
        writeReal: (PetConfigData, Int) -> PetConfigData
    ) {
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val real = (progress - 200).coerceIn(-200, 400)
                    EditSync.setText(et, real.toString())
                    lifecycleScope.launch { config.update { writeReal(it, real) } }
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        EditSync.bind(
            et,
            parse = { it.toIntOrNull() },
            format = { it.toString() },
            clamp = { it.coerceIn(-200, 400) },
            defaultValue = 0
        ) { v ->
            sb.progress = (v + 200).coerceIn(0, 600)
            lifecycleScope.launch { config.update { writeReal(it, v) } }
        }
    }
}
