package com.cx.cakepet

import com.cx.cakepet.SettingsPage.RANDOM

/** 随机阈值设置页（独立 Activity，仅承载 RANDOM 页数据）。 */
class RandomThresholdActivity : BaseThresholdActivity() {
    override val page: SettingsPage = RANDOM
    override val pageTitleText: String = "随机"
}
