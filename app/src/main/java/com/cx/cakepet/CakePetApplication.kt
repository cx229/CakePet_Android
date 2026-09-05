package com.cx.cakepet

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

/**
 * 全局 Application：在进程最早的点（任何 Activity / Service / 浮窗创建之前）就根据
 * “系统当前是否夜间 + 用户强制开关”确定夜间模式，使 [AppCompatDelegate.setDefaultNightMode]
 * 的全局设置尽早生效，避免“点击启动之后”才切到夜间的观感问题，也避免夜间资源在 Service 内
 * 提前 inflate 时取错配置。
 */
class CakePetApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val cfg = PetConfig(this).getBlocking()
        val mode = when {
            cfg.forceNightOn -> AppCompatDelegate.MODE_NIGHT_YES
            cfg.forceNightOff -> AppCompatDelegate.MODE_NIGHT_NO
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }
}
