package com.cx.cakepet

import com.cx.cakepet.SettingsPage.FEEDING

/** 投喂阈值设置页（独立 Activity，仅承载 FEEDING 页数据）。 */
class FeedingThresholdActivity : BaseThresholdActivity() {
    override val page: SettingsPage = FEEDING
    override val pageTitleText: String = "投食"
}
