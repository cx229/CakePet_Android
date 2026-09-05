package com.cx.cakepet

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.view.View

/**
 * 共享显示画布：单个全屏、固定、NOT_TOUCHABLE 窗口，所有宠物在同一 onDraw 内按各自锚点绘制。
 *
 * 关键点：
 * - 窗口几何固定不动，仅通过 canvas.translate 把每只宠物画到其屏幕位置，
 *   因此“位图 + 位置”在【同一次 onDraw 提交】中完成，消除浮窗几何(WMS) 与
 *   View 绘制(RenderThread) 之间的 vsync 竞态（原每帧 updateViewLayout 移动窗口导致的可见错位）。
 * - 窗口 NOT_TOUCHABLE，触摸完全穿透到下层 App；宠物的拖拽/长按/双击由各自的
 *   “控制窗”(PetView 实例，透明、NOT_TOUCH_MODAL) 承接，互不干扰。
 * - 多宠物：所有宠物共用同一画布（位置和动画一起绘制），控制各自独立。
 * - 【唯一显示画布原则】本舞台是 App 唯一的全屏悬浮显示窗口；偏移线、吸附判定线等
 *   「纯绘制」内容一律在此绘制，不再各自建独立全屏窗口。
 *   原因：Android 16 上同时存在 ≥2 个全屏 TYPE_APPLICATION_OVERLAY 窗口时，
 *   即便都带 FLAG_NOT_TOUCHABLE，触摸穿透也会失效并吞掉整屏点击（A12 无此问题）。
 * - 【层级约定】宠物显示在【最顶层】。onDraw 顺序：边界偏移线 → 吸附判定线 → 宠物。
 */
class PetStageView(context: Context) : View(context) {

    /** 吸附判定线段（屏幕绝对坐标）：每个处于吸附态的宠物脚下一段「虚拟地面」线。 */
    data class SnapSeg(val x1: Float, val y1: Float, val x2: Float, val y2: Float)

    // 所有宠物的“控制视图”(PetView)。舞台仅负责绘制，不持有任何宠物状态。
    var pets: List<PetView> = emptyList()

    // 并入本舞台绘制的纯绘制 View（不再挂载到 WindowManager，无独立窗口/输入通道）。
    // 由 onDraw 在【画宠物之前】委派绘制 —— 宠物显示在最顶层，辅助层在其下。
    var guideView: GuideLineView? = null

    // 吸附判定线段：由 PetService 每帧按「处于吸附态 + 吸附判定线开关已开启」计算后写入。
    // 空列表 = 不绘制（未吸附 / 开关关闭 / 无宠物），无需任何可见性/窗口状态同步。
    private var snapSegs: List<SnapSeg> = emptyList()

    /** 设置本帧要绘制的吸附判定线段（传空列表即不绘制）。 */
    fun setSnapSegments(list: List<SnapSeg>) {
        snapSegs = list
    }

    // 吸附判定线样式：与边界偏移线一致的黑白交替四层虚线（黑芯白边 / 白芯黑边），
    // 保证任何壁纸底色下都清晰可见。
    private val snapDash = 12f
    private val snapStrokeOuter = 6f
    private val snapStrokeInner = 3f
    private fun snapPaint(color: Int, width: Float, phase: Float) = Paint().apply {
        this.color = color
        this.strokeWidth = width
        this.style = Paint.Style.STROKE
        this.pathEffect = DashPathEffect(floatArrayOf(snapDash, snapDash), phase)
        this.isAntiAlias = true
    }
    private val snapPaints: List<Paint> = listOf(
        snapPaint(Color.WHITE, snapStrokeOuter, 0f),
        snapPaint(Color.BLACK, snapStrokeInner, 0f),
        snapPaint(Color.BLACK, snapStrokeOuter, snapDash),
        snapPaint(Color.WHITE, snapStrokeInner, snapDash)
    )

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // ===== 层级约定：宠物显示在【最顶层】，所有辅助层一律画在宠物之下 =====
        // 自下而上：边界偏移线（含吸附距离环带）→ 吸附判定线 → 宠物。
        // （碎碎念为独立小窗，其窗口在 stageView 之前添加，天然位于宠物之下。）
        guideView?.let { g ->
            if (g.visibility == View.VISIBLE) {
                fitToStage(g)
                g.draw(canvas)
            }
        }
        // 吸附判定线（脚下地面线）：画在宠物之下，中段被宠物身体自然遮挡。
        for (s in snapSegs) {
            for (p in snapPaints) {
                canvas.drawLine(s.x1, s.y1, s.x2, s.y2, p)
            }
        }
        for (v in pets) {
            if (v.visibility != View.VISIBLE) continue
            // 与 PetService 主循环 / onPositionChanged 完全同源：window 左上 = 锚点 - 锚点偏移。
            val (ax, ay) = v.getBaseAnchorScaled()
            val lx = v.physics.x - ax
            val ly = v.physics.y - ay
            canvas.save()
            canvas.translate(lx, ly)
            // 透明度（设置页可调）：Canvas 无 alpha 属性，改用 saveLayerAlpha 把整体透明度
            // 施加到本宠物绘制（原 View.alpha 由框架在自绘时自动处理，现移到舞台需手动复刻）。
            val alphaInt = (v.alpha * 255).toInt()
            if (alphaInt < 255) {
                // alpha 图层边界用固定整帧尺寸（非控制窗实时尺寸）：CORE 模式控制窗缩成脚底盒，
                // 否则图层被裁到小块、宠物被裁切。
                val (afw, afh) = v.getBaseBitmapSize()
                canvas.saveLayerAlpha(0f, 0f, afw.toFloat(), afh.toFloat(), alphaInt, Canvas.ALL_SAVE_FLAG)
            }
            v.render(canvas)
            if (alphaInt < 255) {
                canvas.restore()
            }
            canvas.restore()
        }
        // ===== 控制边框调试层：仅在 showControlBorder 开启时绘制（按需），屏幕绝对坐标，独立于显示窗 =====
        // 几何取 PetView.getControlWindowOBB()（控制层同源：CORE=旋转脚盒，其余=整帧 AABB），
        // 直接画在现有全屏画布，不新增窗口/View；实时预览由每帧 invalidate 免费提供。
        for (v in pets) {
            if (!v.showControlBorder) continue
            val o = v.getControlWindowOBB() ?: continue
            canvas.save()
            canvas.rotate(o.rot, o.cx, o.cy) // 仅 CORE 旋转；其余 rot=0
            // 主轮廓（控制窗边界）：品红，醒目区分于图片边框。
            val bp = Paint().apply {
                color = 0xFFFF00FF.toInt()
                style = Paint.Style.STROKE
                strokeWidth = 4f
                isAntiAlias = true
            }
            canvas.drawRect(o.cx - o.hw, o.cy - o.hh, o.cx + o.hw, o.cy + o.hh, bp)
            // 最近一次 DOWN 命中判定可视化（绿=命中可拖，红=透明穿透），缩进 4px。
            v.debugLastHitTest?.let { hit ->
                val c = if (hit) 0xFF00FF00.toInt() else 0xFFFF0000.toInt()
                val hp = Paint().apply {
                    color = c
                    style = Paint.Style.STROKE
                    strokeWidth = 8f
                    isAntiAlias = true
                }
                canvas.drawRect(o.cx - o.hw + 4f, o.cy - o.hh + 4f, o.cx + o.hw - 4f, o.cy + o.hh - 4f, hp)
            }
            // 实际收到的最近一次 action（蓝=DOWN 绿=MOVE 黄=UP），缩进 12px。
            val ac = when (v.debugLastAction) {
                0 -> 0xFF2196F3.toInt()
                1 -> 0xFF00FF00.toInt()
                2 -> 0xFFFFEB3B.toInt()
                else -> 0xFF9E9E9E.toInt()
            }
            val ap = Paint().apply {
                color = ac
                style = Paint.Style.STROKE
                strokeWidth = 2f
                isAntiAlias = true
            }
            canvas.drawRect(o.cx - o.hw + 12f, o.cy - o.hh + 12f, o.cx + o.hw - 12f, o.cy + o.hh - 12f, ap)
            canvas.restore()
        }
    }

    /**
     * 并入绘制的 View 未参与窗口 layout，需手动赋尺寸：其 onDraw 依赖 width/height
     * （drawCurrentGuide 也用 guideView.height 判断），故铺满舞台尺寸。
     */
    private fun fitToStage(v: View) {
        if (v.width != width || v.height != height) {
            v.layout(0, 0, width, height)
        }
    }
}
