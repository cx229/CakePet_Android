package com.cx.cakepet

import com.cx.cakepet.SettingsPage.MOVEMENT

/** 移动阈值设置页（独立 Activity，仅承载 MOVEMENT 页数据）。
 *  当前为框架占位（systemPage 返回空 PageBounds），具体阈值设置项后续追加，
 *  届时只需在 AppBounds 中补充 MOVEMENT 的滑块/开关清单并对接 PetConfig 映射。 */
class MovementThresholdActivity : BaseThresholdActivity() {
    override val page: SettingsPage = MOVEMENT
    override val pageTitleText: String = "移动"
}
