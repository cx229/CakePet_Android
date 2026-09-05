package com.cx.cakepet

import com.cx.cakepet.SettingsPage.SNAP

/** 吸附阈值设置页（独立 Activity，仅承载 SNAP 页数据）。 */
class SnapThresholdActivity : BaseThresholdActivity() {
    override val page: SettingsPage = SNAP
    override val pageTitleText: String = "吸附"
}
