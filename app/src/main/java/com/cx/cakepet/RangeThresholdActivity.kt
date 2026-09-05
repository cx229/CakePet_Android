package com.cx.cakepet

import com.cx.cakepet.SettingsPage.RANGE

/** 边缘偏移阈值设置页（独立 Activity，仅承载 RANGE 页数据）。 */
class RangeThresholdActivity : BaseThresholdActivity() {
    override val page: SettingsPage = RANGE
    override val pageTitleText: String = "边缘偏移"
}
