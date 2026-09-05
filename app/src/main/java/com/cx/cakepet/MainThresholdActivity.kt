package com.cx.cakepet

import com.cx.cakepet.SettingsPage.MAIN

/** 通用阈值设置页（独立 Activity，仅承载 MAIN 页数据）。 */
class MainThresholdActivity : BaseThresholdActivity() {
    override val page: SettingsPage = MAIN
    override val pageTitleText: String = "通用"
}
