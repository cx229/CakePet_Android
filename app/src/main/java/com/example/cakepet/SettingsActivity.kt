package com.example.cakepet

import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
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
        config = PetConfig(this)

        val scaleBar = findViewById<SeekBar>(R.id.scale_bar)
        val scaleVal = findViewById<android.widget.TextView>(R.id.scale_value)
        val gravityBar = findViewById<SeekBar>(R.id.gravity_bar)
        val gravityVal = findViewById<android.widget.TextView>(R.id.gravity_value)
        val reboundBar = findViewById<SeekBar>(R.id.rebound_bar)
        val reboundVal = findViewById<android.widget.TextView>(R.id.rebound_value)
        val walkBar = findViewById<SeekBar>(R.id.walk_bar)
        val walkVal = findViewById<android.widget.TextView>(R.id.walk_value)

        val swTop = findViewById<Switch>(R.id.sw_top)
        val swBottom = findViewById<Switch>(R.id.sw_bottom)
        val swLeft = findViewById<Switch>(R.id.sw_left)
        val swRight = findViewById<Switch>(R.id.sw_right)

        lifecycleScope.launch {
            val c = config.configFlow.first()
            scaleBar.progress = ((c.scale - 0.5f) / 1.5f * 100).toInt().coerceIn(0, 100)
            scaleVal.text = "%.2f×".format(c.scale)
            gravityBar.progress = (c.gravity / 3000f * 100).toInt().coerceIn(0, 100)
            gravityVal.text = c.gravity.toInt().toString()
            reboundBar.progress = (c.reboundRatio * 100).toInt().coerceIn(0, 100)
            reboundVal.text = "%.2f".format(c.reboundRatio)
            walkBar.progress = (c.walkSpeed / 600f * 100).toInt().coerceIn(0, 100)
            walkVal.text = c.walkSpeed.toInt().toString()
            swTop.isChecked = c.gravityTop
            swBottom.isChecked = c.gravityBottom
            swLeft.isChecked = c.gravityLeft
            swRight.isChecked = c.gravityRight
        }

        scaleBar.setOnSeekBarChangeListener(simple { p ->
            val v = 0.5f + p / 100f * 1.5f
            scaleVal.text = "%.2f×".format(v)
            lifecycleScope.launch { config.update { it.copy(scale = v) } }
        })
        gravityBar.setOnSeekBarChangeListener(simple { p ->
            val v = p / 100f * 3000f
            gravityVal.text = v.toInt().toString()
            lifecycleScope.launch { config.update { it.copy(gravity = v) } }
        })
        reboundBar.setOnSeekBarChangeListener(simple { p ->
            val v = p / 100f
            reboundVal.text = "%.2f".format(v)
            lifecycleScope.launch { config.update { it.copy(reboundRatio = v) } }
        })
        walkBar.setOnSeekBarChangeListener(simple { p ->
            val v = p / 100f * 600f
            walkVal.text = v.toInt().toString()
            lifecycleScope.launch { config.update { it.copy(walkSpeed = v) } }
        })

        swTop.setOnCheckedChangeListener { _, b -> lifecycleScope.launch { config.update { it.copy(gravityTop = b) } } }
        swBottom.setOnCheckedChangeListener { _, b -> lifecycleScope.launch { config.update { it.copy(gravityBottom = b) } } }
        swLeft.setOnCheckedChangeListener { _, b -> lifecycleScope.launch { config.update { it.copy(gravityLeft = b) } } }
        swRight.setOnCheckedChangeListener { _, b -> lifecycleScope.launch { config.update { it.copy(gravityRight = b) } } }

        findViewById<android.widget.Button>(R.id.btn_close).setOnClickListener { finish() }
    }

    private fun simple(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { onChange(p) }
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    }
}
