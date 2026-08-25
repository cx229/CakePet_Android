package com.example.cakepet

import android.os.Bundle
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class RandomSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_random_settings)
        // 纯黑占位条填充状态栏高度，与标题栏黑底连成整体覆盖状态栏
        val spacer = findViewById<android.view.View>(R.id.status_bar_spacer)
        spacer.layoutParams.height = getStatusBarHeight()
        spacer.requestLayout()
        findViewById<ImageView>(R.id.iv_back).setOnClickListener { finish() }

        val config = PetConfig(this)

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
        val switchViews = itemSwitches.map { (id, flag) ->
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

        val periodMinBar = findViewById<SeekBar>(R.id.period_min_bar)
        val periodMaxBar = findViewById<SeekBar>(R.id.period_max_bar)
        val periodMinVal = findViewById<TextView>(R.id.period_min_value)
        val periodMaxVal = findViewById<TextView>(R.id.period_max_value)

        // 修改周期后立即重置随机计时（重新开始等待），并触发服务重置
        fun onPeriodChanged() {
            PetService.instance?.requestRandomReset()
        }
        periodMinBar.setOnSeekBarChangeListener(simpleSeekBar { p ->
            val min = maxOf(1, minOf(p, periodMaxBar.progress))
            periodMinBar.progress = min
            periodMinVal.text = min.toString()
            lifecycleScope.launch { config.update { it.copy(randomPeriodMin = min) } }
            onPeriodChanged()
        })
        periodMaxBar.setOnSeekBarChangeListener(simpleSeekBar { p ->
            val max = maxOf(periodMinBar.progress, p)
            periodMaxBar.progress = max
            periodMaxVal.text = max.toString()
            lifecycleScope.launch { config.update { it.copy(randomPeriodMax = max) } }
            onPeriodChanged()
        })

        lifecycleScope.launch {
            val c = config.configFlow.first()
            switchViews.forEach { (flag, sw) -> sw.isChecked = (c.randomItems and flag) != 0 }
            periodMinBar.progress = c.randomPeriodMin
            periodMaxBar.progress = c.randomPeriodMax
            periodMinVal.text = c.randomPeriodMin.toString()
            periodMaxVal.text = c.randomPeriodMax.toString()
        }
    }

    /** 获取系统真实状态栏高度（px），失败时回退 24dp */
    private fun getStatusBarHeight(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId)
        else (resources.displayMetrics.density * 24f).toInt()
    }

    /** 简洁 SeekBar 监听：仅当用户拖动时回调 onChange(进度) */
    private fun simpleSeekBar(onChange: (Int) -> Unit) = object : android.widget.SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
            if (fromUser) onChange(p)
        }
        override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
        override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
    }
}
