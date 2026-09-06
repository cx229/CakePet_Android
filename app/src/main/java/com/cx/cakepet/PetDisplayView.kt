package com.cx.cakepet

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * 每只宠物独立的显示窗（替代原共享全屏画布 PetStageView 的宠物绘制部分）。
 *
 * 窗口按宠物位图矩形显式摆放（小窗 + NOT_TOUCHABLE，纯展示、不接点击、事件穿透给下层 app），
 * 在安卓上正常把点击传给其它 app（与旧 9.27 几何一致；鸿蒙对小 NOT_TOUCHABLE 窗穿透属已知未知，尽力兼容）。
 * 仅设置期/调试用的大尺寸辅助线（边框/偏移/键盘示意）走独立 guide 窗（见 PetService.syncGuideWindow）。
 *
 * 绘制内容：
 *  1) 宠物位图：直接调用 PetView.render(canvas)，局部坐标即本窗内坐标；
 *  2) 吸附判定线：绝对坐标 → 减本窗左上角换算到窗内局部。
 *  （调试控制边框在控制窗自身绘制，见 PetView.onDraw，与其命中区几何严格同源。）
 */
class PetDisplayView(context: Context, private val petView: PetView) : View(context) {

    /** 本窗在屏幕物理坐标的左上角（绝对），用于把传入的绝对坐标辅助线换算到窗内局部坐标。 */
    var displayLeft = 0f
    var displayTop = 0f

    override fun onDraw(canvas: Canvas) {
        // 宠物位图（与 PetStageView 一致：直接调用 PetView.render，局部坐标即窗内坐标）。
        petView.render(canvas)
    }

    companion object {
    }
}
