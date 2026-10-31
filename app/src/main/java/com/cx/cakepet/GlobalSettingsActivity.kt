package com.cx.cakepet

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog

/**
 * 应用级页面：全局设置 -> 调试 -> 无障碍点击测试。
 * 暂不启动任何悬浮窗或触摸实验；所有页面不依赖角色 ID。
 */
class GlobalSettingsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PAGE = "page"
        const val PAGE_SETTINGS = "settings"
        const val PAGE_DEBUG = "debug"
        const val PAGE_ACCESSIBILITY_TEST = "accessibility_test"
    }

    private var page = PAGE_SETTINGS

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_global_settings)
        page = when (intent.getStringExtra(EXTRA_PAGE)) {
            PAGE_DEBUG -> PAGE_DEBUG
            PAGE_ACCESSIBILITY_TEST -> PAGE_ACCESSIBILITY_TEST
            else -> PAGE_SETTINGS
        }

        val spacer = findViewById<View>(R.id.status_bar_spacer)
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        spacer.layoutParams.height = if (resId > 0) resources.getDimensionPixelSize(resId)
            else (resources.displayMetrics.density * 24f).toInt()
        spacer.requestLayout()

        findViewById<View>(R.id.iv_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tv_title).text = when (page) {
            PAGE_DEBUG -> "调试"
            PAGE_ACCESSIBILITY_TEST -> "无障碍点击测试"
            else -> "全局设置"
        }

        val accessibilityRow = findViewById<View>(R.id.row_accessibility)
        val nextRow = findViewById<View>(R.id.row_next)
        when (page) {
            PAGE_SETTINGS -> {
                // 状态只读；不把系统权限伪装成可由应用切换的开关。
                findViewById<TextView>(R.id.tv_next).text = "调试"
                nextRow.setOnClickListener { openPage(PAGE_DEBUG) }
            }
            PAGE_DEBUG -> {
                accessibilityRow.visibility = View.GONE
                findViewById<TextView>(R.id.tv_next).text = "无障碍点击测试"
                nextRow.setOnClickListener { openPage(PAGE_ACCESSIBILITY_TEST) }
            }
            else -> {
                // 实验尚未实现，按需求保留空白内容区。
                accessibilityRow.visibility = View.GONE
                nextRow.visibility = View.GONE
                findViewById<View>(R.id.test_buttons).visibility = View.VISIBLE
                findViewById<View>(R.id.btn_create_test).setOnClickListener {
                    val service = CakePetAccessibilityService.active
                    if (service == null) {
                        AlertDialog.Builder(this)
                            .setMessage("你现在没有无障碍权限，或无障碍服务尚未连接。")
                            .setPositiveButton("确定", null)
                            .show()
                    } else if (!service.showHitTest()) {
                        AlertDialog.Builder(this)
                            .setMessage("无障碍测试窗口创建失败，请检查系统限制。")
                            .setPositiveButton("确定", null)
                            .show()
                    }
                }
                findViewById<View>(R.id.btn_close_test).setOnClickListener {
                    CakePetAccessibilityService.active?.hideHitTest()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (page == PAGE_SETTINGS) {
            val expected = ComponentName(this, CakePetAccessibilityService::class.java)
                .flattenToString()
            val enabled = Settings.Secure.getString(
                contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )?.split(":")?.any { it.equals(expected, ignoreCase = true) } == true
            findViewById<TextView>(R.id.tv_accessibility_status).text =
                if (enabled) "已开启" else "未开启"
        }
    }

    private fun openPage(destination: String) {
        startActivity(Intent(this, GlobalSettingsActivity::class.java).apply {
            putExtra(EXTRA_PAGE, destination)
        })
    }
}
