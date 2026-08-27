package com.cx.cakepet

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.WindowManager

/**
 * 全屏透明辅助线浮层（TYPE_APPLICATION_OVERLAY，NOT_TOUCHABLE / NOT_FOCUSABLE），
 * 用于在设置页调整“边界偏移”“碎碎念偏移”时，在目标边缘位置绘制辅助虚线，
 * 每条线由“白色虚线 + 黑色虚线”叠加而成：白线略粗打底、黑线略细覆盖，
 * 无论在浅色还是深色壁纸上都清晰可见。
 *
 * 坐标为物理屏幕像素（与 PetService.getScreenBounds() 同一坐标系，含系统栏延伸），
 * 因浮层以 Gravity.TOP|START 且仅 FLAG_LAYOUT_NO_LIMITS，可见区原点已对齐物理屏幕，
 * 故传入的屏幕物理坐标可直接作为本 View 内部坐标使用。
 */
class GuideLineView(context: Context) : View(context) {

    /** 单条引导线 */
    data class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float)

    // 白色虚线（略粗，作底色描边，保证暗背景可见）
    private val paintWhite = Paint().apply {
        color = Color.WHITE
        strokeWidth = 5f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
        isAntiAlias = true
    }

    // 黑色虚线（略细，覆盖在白线之上，保证亮背景可见）
    private val paintBlack = Paint().apply {
        color = Color.BLACK
        strokeWidth = 3f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
        isAntiAlias = true
    }

    private var lines: List<Line> = emptyList()

    /** 设置要显示的引导线（屏幕物理像素坐标）；传空列表即清除 */
    fun setLines(lines: List<Line>) {
        this.lines = lines
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 每条逻辑线先画白（粗）再画黑（细），叠成“黑白双虚线”
        for (l in lines) {
            canvas.drawLine(l.x1, l.y1, l.x2, l.y2, paintWhite)
            canvas.drawLine(l.x1, l.y1, l.x2, l.y2, paintBlack)
        }
    }
}
