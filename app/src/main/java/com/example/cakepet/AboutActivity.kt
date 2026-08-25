package com.example.cakepet

import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Switch
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AboutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)
        // 用纯黑占位条填充状态栏高度，与标题栏黑底连成整体覆盖状态栏；标题文字在标题栏内完整显示。
        val spacer = findViewById<android.view.View>(R.id.status_bar_spacer)
        spacer.layoutParams.height = getStatusBarHeight()
        spacer.requestLayout()
        findViewById<android.widget.ImageView>(R.id.iv_back).setOnClickListener { finish() }

        val config = PetConfig(this)
        val swDebug = findViewById<Switch>(R.id.sw_debug)
        val swRect = findViewById<Switch>(R.id.sw_rect)

        // 版本号：读取真实版本（与 build.gradle.kts 的 versionName 同步）
        val versionName = try {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
        } catch (_: PackageManager.NameNotFoundException) {
            "unknown"
        }
        findViewById<android.widget.TextView>(R.id.tv_version).text = "版本：$versionName"

        lifecycleScope.launch {
            val c = config.configFlow.first()
            swDebug.isChecked = c.showDebug
            swRect.isChecked = c.showRect
        }
        swDebug.setOnCheckedChangeListener { _, b ->
            lifecycleScope.launch { config.update { it.copy(showDebug = b) } }
        }
        swRect.setOnCheckedChangeListener { _, b ->
            lifecycleScope.launch { config.update { it.copy(showRect = b) } }
        }
    }

    /** 获取系统真实状态栏高度（px），失败时回退 24dp */
    private fun getStatusBarHeight(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId)
        else (resources.displayMetrics.density * 24f).toInt()
    }
}
