package com.cx.cakepet

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager

/**
 * 无障碍服务：用于感知当前前台应用（packageName），供桌宠互动 / 调试浮窗展示。
 * 不监听/拦截任何触摸，不弹请求弹窗，用户需在系统设置中手动开启。
 *
 * 前台包名通过 PetService 单例回传（PetService.instance.onForegroundAppChanged）。
 */
class CakePetAccessibilityService : AccessibilityService() {
    companion object {
        var active: CakePetAccessibilityService? = null
            private set
    }
    private var testView: AccessibilityHitTestView? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        active = this
    }

    fun showHitTest(): Boolean {
        if (testView != null) return true
        return try {
            val view = AccessibilityHitTestView(this)
            view.onClose = { hideHitTest() }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START }
            (getSystemService(WINDOW_SERVICE) as WindowManager).addView(view, params)
            testView = view
            true
        } catch (_: Exception) { false }
    }

    fun hideHitTest() {
        val view = testView ?: return
        testView = null
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(view)
        } finally { view.release() }
    }

    override fun onDestroy() {
        hideHitTest()
        if (active === this) active = null
        super.onDestroy()
    }


    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        // 仅关注窗口状态变化事件，从中取包名。
        // 使用 TYPE_WINDOW_STATE_CHANGED：所有 Android 版本均稳定触发（切换 App 时必触发且能拿到 packageName），
        // 已足够覆盖前台 App 感知。
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            if (!pkg.isNullOrEmpty()) {
                PetService.instance?.onForegroundAppChanged(pkg)
            }
        }
    }

    override fun onInterrupt() {
        // 被系统中断时无需处理。
    }
}
