package com.cx.cakepet

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * 关于页：版本 / 作者 / 角色 / 感谢，以及隐私协议入口。
 *
 * 本页是【全局页】，不绑定任何角色，故没有 tv_title_sub 角色名副标题（这点与调试页不同，
 * 调试页内联取 role_id 显示角色名）。
 *
 * 版本号取自 AppVersion.name（BuildConfig.VERSION_NAME），动态拼接而非写死，
 * 随构建自动更新，避免忘记同步。
 */
class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        // 用占位条填充状态栏高度，与标题栏连成整体覆盖状态栏
        val spacer = findViewById<android.view.View>(R.id.status_bar_spacer)
        spacer.layoutParams.height = getStatusBarHeight()
        spacer.requestLayout()
        findViewById<android.widget.ImageView>(R.id.iv_back).setOnClickListener { finish() }

        // 版本号由构建注入（不在此写死版本串）
        findViewById<TextView>(R.id.tv_version).text = "版本：${AppVersion.name}"

        findViewById<android.view.View>(R.id.btn_privacy).setOnClickListener {
            startActivity(Intent(this, PrivacyActivity::class.java))
        }
    }

    /** 获取系统真实状态栏高度（px），失败时回退 24dp */
    private fun getStatusBarHeight(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId)
        else (resources.displayMetrics.density * 24f).toInt()
    }
}
