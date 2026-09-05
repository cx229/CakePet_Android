package com.cx.cakepet

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * 无障碍服务：用于感知当前前台应用（packageName），供桌宠互动 / 调试浮窗展示。
 * 不监听/拦截任何触摸，不弹请求弹窗，用户需在系统设置中手动开启。
 *
 * 前台包名通过 PetService 单例回传（PetService.instance.onForegroundAppChanged）。
 */
class CakePetAccessibilityService : AccessibilityService() {

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
