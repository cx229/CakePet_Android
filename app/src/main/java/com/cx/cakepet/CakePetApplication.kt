package com.cx.cakepet

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/**
 * 全局 Application：在进程最早的点（任何 Activity / Service / 浮窗创建之前）就根据
 * “系统当前是否夜间 + 用户强制开关”确定夜间模式，使 [AppCompatDelegate.setDefaultNightMode]
 * 的全局设置尽早生效，避免“点击启动之后”才切到夜间的观感问题，也避免夜间资源在 Service 内
 * 提前 inflate 时取错配置。
 */
class CakePetApplication : Application() {

    companion object {
        /** 全局 Application Context（供无 Context 处读取角色清单等）。 */
        lateinit var appCtx: Context
            private set
    }

    override fun onCreate() {
        super.onCreate()
        appCtx = this
        // Keep the APK template available for creation, but register only player-owned packs.
        RoleRegistry.init(this)
        val cfg = try {
            val firstRoleId = RoleStore.getHomeRoles(this).firstOrNull()?.instanceId
                ?: RoleRegistry.PRESETS.firstOrNull()?.id
            firstRoleId?.let { PetConfig.forRole(this, it).getBlocking() }
        } catch (error: Exception) {
            android.util.Log.e("CakePetApplication", "Unable to read startup night mode", error)
            null
        }
        val mode = when {
            cfg?.forceNightOn == true -> AppCompatDelegate.MODE_NIGHT_YES
            cfg?.forceNightOff == true -> AppCompatDelegate.MODE_NIGHT_NO
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }
}
