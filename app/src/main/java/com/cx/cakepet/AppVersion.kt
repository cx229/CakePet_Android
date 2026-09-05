package com.cx.cakepet

/**
 * 全局共享的版本号，统一从 BuildConfig（由 build.gradle.kts 的 versionName 生成）读取，
 * 首页与关于页共用，避免多处重复读取 / 取值不一致。
 */
object AppVersion {
    /** 当前版本名，如 "1.26.8.29" */
    val name: String get() = BuildConfig.VERSION_NAME
}
