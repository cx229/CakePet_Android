package com.cx.cakepet

import com.cx.cakepet.SettingsPage.THINKING

/** 碎碎念阈值设置页（独立 Activity，仅承载 THINKING 页数据）。 */
class ThinkingThresholdActivity : BaseThresholdActivity() {
    override val page: SettingsPage = THINKING
    override val pageTitleText: String = "碎碎念"
}
