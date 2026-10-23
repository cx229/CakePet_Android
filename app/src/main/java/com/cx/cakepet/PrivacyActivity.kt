package com.cx.cakepet

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

/**
 * 隐私协议页。
 *
 * 【内容真源】app/src/main/assets/privacy.html —— 与 Gitee Pages 上的隐私协议页是【同一份文件】。
 * 协议内容后续仍需修订，只改这一处即可让 App 与网页端同步生效，彻底避免两份内容漂移。
 *
 * 采用 WebView 直接渲染 HTML（而非硬编码字符串或自行解析文本），理由：
 * 1) 排版两端完全一致：标题层级、表格、行距、手机适配、夜间配色均由 HTML/CSS 定义；
 * 2) 内容修订无需改动任何 Kotlin 代码，也不受 Android 资源编译的空白裁剪影响。
 *
 * 安全约束：
 * 1) 仅加载本地 file:///android_asset/privacy.html，页面自身不含任何外链资源；
 * 2) 关闭 JavaScript —— 隐私页不应执行任何脚本；
 * 3) 本应用未声明 INTERNET 权限，WebView 天然无法联网。正因如此【不可】调用
 *    setBlockNetworkLoads(true)：该设置需要 INTERNET 权限，缺失时调用会抛 SecurityException；
 * 4) 文中少量外部参考链接（如 aboutcookies.org）拦截后交给系统浏览器，由用户显式决定是否外跳。
 */
class PrivacyActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_privacy)

        // 用占位条填充状态栏高度，与标题栏连成整体覆盖状态栏
        val spacer = findViewById<android.view.View>(R.id.status_bar_spacer)
        spacer.layoutParams.height = getStatusBarHeight()
        spacer.requestLayout()
        findViewById<android.widget.ImageView>(R.id.iv_back).setOnClickListener { finish() }

        webView = findViewById(R.id.web_privacy)
        webView.settings.apply {
            javaScriptEnabled = false       // 纯展示，关闭脚本
            useWideViewPort = true          // 配合 HTML 的 viewport meta 做手机适配
            loadWithOverviewMode = true
            setSupportZoom(true)            // 长文允许双指缩放阅读
            builtInZoomControls = true
            displayZoomControls = false     // 隐藏缩放按钮，保持界面干净
        }
        // 背景交给 HTML 自身的 CSS（含夜间配色），此处透明避免加载瞬间闪白
        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                handleUrl(request.url.toString())

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                handleUrl(url)
        }
        webView.loadUrl("file:///android_asset/privacy.html")
    }

    /** 本地协议正文交给 WebView 渲染；外部链接转交系统浏览器（本应用无 INTERNET 权限）。 */
    private fun handleUrl(url: String): Boolean {
        if (url.startsWith("file://")) return false
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        return true
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }

    /** 获取系统真实状态栏高度（px），失败时回退 24dp */
    private fun getStatusBarHeight(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId)
        else (resources.displayMetrics.density * 24f).toInt()
    }
}
