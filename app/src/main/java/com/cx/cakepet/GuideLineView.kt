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
 * 用于在设置页调整“边界偏移”“碎碎念偏移”“吸附阈值”时绘制辅助虚线。
 *
 * 每条逻辑线由 **4 条虚线叠加** 绘制，形成沿线段「黑-白-黑-白」交替的效果：
 *  - 实线段长 [DASH]，间隔同样为 [DASH]（周期 2×DASH）
 *  - 黑段与白段相位相差半个周期，恰好互相错开、互不重叠
 *  - 每段都由「粗底 + 细芯」两层构成：黑段为黑芯白边，白段为白芯黑边
 * 这样无论壁纸是浅色还是深色，每一段都有对比色描边，全程清晰可见。
 *
 * 坐标为物理屏幕像素（与 PetService.getScreenBounds() 同一坐标系，含系统栏延伸），
 * 因浮层以 Gravity.TOP|START 且仅 FLAG_LAYOUT_NO_LIMITS，可见区原点已对齐物理屏幕，
 * 故传入的屏幕物理坐标可直接作为本 View 内部坐标使用。
 */
class GuideLineView(context: Context) : View(context) {

    /** 单条引导线 */
    data class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float)

    // 虚线单段长度（黑段与白段各占一个 DASH，故交替周期为 2×DASH）
    private val dash = 12f

    // 描边层宽度（比色芯宽，为每段提供对比色轮廓）
    private val strokeOuter = 6f
    // 色芯层宽度
    private val strokeInner = 3f

    /**
     * 四层绘制顺序（后画覆盖先画）：
     * 1) 白·粗·phase 0     → 黑段的白色描边底
     * 2) 黑·细·phase 0     → 黑段色芯
     * 3) 黑·粗·phase DASH  → 白段的黑色描边底
     * 4) 白·细·phase DASH  → 白段色芯
     */
    private val layers: List<Paint> = listOf(
        guidePaint(Color.WHITE, strokeOuter, 0f),
        guidePaint(Color.BLACK, strokeInner, 0f),
        guidePaint(Color.BLACK, strokeOuter, dash),
        guidePaint(Color.WHITE, strokeInner, dash)
    )

    private fun guidePaint(color: Int, width: Float, phase: Float) = Paint().apply {
        this.color = color
        this.strokeWidth = width
        this.style = Paint.Style.STROKE
        this.pathEffect = DashPathEffect(floatArrayOf(dash, dash), phase)
        this.isAntiAlias = true
    }

    private var lines: List<Line> = emptyList()

    /** 每条线对应的种类，决定绘制颜色与备注样式；与 lines 一一对应。 */
    private var lineKinds: List<LineKind> = emptyList()

    /** 当前正在被用户拖动的线索引（-1 表示无）；其备注绘制三层边框以突出。 */
    private var activeIndex: Int = -1

    /** 抬高偏移线正在被拖动：叠加「五道交叉」高亮。 */
    private var imeActive = false

    /** 吸附距离正在被拖动：对四条吸附内缩线叠加「五道交叉」高亮。 */
    private var snapActive = false

    /** 各方向吸附开关（顺序：左、右、上、下）；关闭的方向不绘制其吸附内缩线与环带。 */
    private var snapOnFlags = booleanArrayOf(true, true, true, true)

    /** 吸附距离调整红框三态文案（msg, sub），为 null 时不显示。 */
    private var snapWarn: Pair<String, String>? = null

    /** 宠物参数异常红框文案（大小/反弹/速度/重力等），为 null 时不显示。 */
    private var petWarn: Pair<String, String>? = null

    /** 待最后绘制的红框（高显示层级，覆盖在引导线之上）。 */
    private var pendingWarn: Pair<String, String>? = null

    /** 引导线种类：边界偏移线（黑白交替）、吸附内缩框线（绿色）、输入法抬高线（单独绘制）。 */
    enum class LineKind { BOUND, SNAP }

    // 虚拟键盘预览：拖动“抬高偏移”时假想键盘已弹出的辅助层。
    // 绘制一个斜线填充的“输入法键盘”矩形（屏幕底部，高度 IME_PREVIEW_HEIGHT），
    // 并在其上叠加四边偏移线（由 PetService 经 setLines 提供）与一条绿色“抬高偏移”线。
    private var keyboardPreview = false
    private var imeLiftOffsetPreview = 0f   // 抬高偏移即时值（px，正=更高）
    // 与 PetService.IME_PREVIEW_HEIGHT 保持一致：设置页假定的键盘高度。
    private val IME_PREVIEW_HEIGHT = 600f

    // 四边偏移线的小文字备注（与线一一对应，顺序：左、右、上、下）；为 null 时不绘制
    private var boundLabels: List<String>? = null

    // 标签文字大小（统一调大，便于在桌面看清）
    private val TAG_TEXT = 34f

    // 零区域红色警告文字（很大很大）
    private val WARN_TEXT = 72f

    // 标签背景底固定宽度：避免内容长度变化导致底框伸缩（需求 1）
    private val TAG_WIDTH = 260f
    private val TAG_PAD = 8f
    // 标签框高度（旋转后作为竖排时的水平厚度），用于旋转后紧贴偏移线定位。
    // 必须与 drawTag 内部 boxH 完全一致，故改用真实字体度量（descent-ascent）。
    private val tagMetrics: Pair<Float, Float> by lazy {
        val p = Paint().apply { textSize = TAG_TEXT; isAntiAlias = true }
        p.ascent() to p.descent()
    }
    private val tagAscent: Float get() = tagMetrics.first
    private val tagDescent: Float get() = tagMetrics.second

    // 吸附内缩框线 / 输入法抬高线：绿红双色交替四层（仿边界黑白双色方案）
    // 绘制顺序（后画覆盖先画）：
    // 1) 红·粗·phase 0   → 绿段的红色描边底
    // 2) 绿·细·phase 0   → 绿段色芯
    // 3) 绿·粗·phase D  → 红段的绿色描边底
    // 4) 红·细·phase D   → 红段色芯
    private val snapDash = 14f
    private val snapLayers: List<Paint> = listOf(
        guidePaint(0xFFFF0000.toInt(), strokeOuter, 0f),
        guidePaint(0xA000FF00.toInt(), strokeInner, 0f),
        guidePaint(0xA000FF00.toInt(), strokeOuter, snapDash),
        guidePaint(0xFFFF0000.toInt(), strokeInner, snapDash)
    )

    // 吸附区域环带（SNAP 模式专用）：outer=活动范围大矩形，inner=吸附合围小矩形；
    // 二者之间的环带即“吸附区域”（宠物进入内侧 snapDist 即吸附）。
    // empty=吸附合围为空（即 snapDist 过大、全吸附），此时不画环带而显示红底提示，但仍保留边界线。
    private var snapOuter: RectF? = null
    private var snapInner: RectF? = null
    private var snapEmpty = false

    /**
     * 绘制带固定宽度背景底的标签文字（先画矩形底，再在框内居中画前景字）。
     * 背景底宽度固定为 [TAG_WIDTH]，因此内容长度变化不会引起底框伸缩。
     * @param cx 标签框**水平中心**的 x（用于对称贴边时定位中心）
     * @param baselineY 文字基线 y（决定竖向位置）
     * @param rotation 文字与背景底整体旋转角度（度），用于竖线备注竖向显示
     */
    private fun drawTag(canvas: Canvas, text: String, cx: Float, baselineY: Float, bg: Int, fg: Int, rotation: Float = 0f) {
        val p = Paint().apply {
            color = fg
            textSize = TAG_TEXT
            isAntiAlias = true
        }
        val tw = p.measureText(text)
        val boxW = TAG_WIDTH
        val boxH = (p.descent() - p.ascent()) + TAG_PAD * 2
        val boxLeft = cx - boxW / 2f
        val boxTop = baselineY + p.ascent() - TAG_PAD
        val bgRect = RectF(boxLeft, boxTop, boxLeft + boxW, boxTop + boxH)
        if (rotation != 0f) canvas.save()
        if (rotation != 0f) canvas.rotate(rotation, cx, baselineY)
        canvas.drawRect(bgRect, Paint().apply { color = bg; isAntiAlias = true })
        // 文字在框内居中
        val tx = cx - tw / 2f
        canvas.drawText(text, tx, baselineY, p)
        if (rotation != 0f) canvas.restore()
    }

    /**
     * 沿法线方向平行偏移 5 条线，形成「五道交叉」高亮带，强烈突出当前正在调整的线。
     * [paints] 决定配色：layers（黑白）= 边界线；snapLayers（绿）= 吸附内缩线 / 抬高偏移线。
     * 竖线（x1==x2）法线为水平方向，横线法线为垂直方向。
     */
    private fun drawFiveCross(canvas: Canvas, l: Line, paints: List<Paint>) {
        val isVertical = l.x1 == l.x2
        for (d in floatArrayOf(-8f, -4f, 0f, 4f, 8f)) {
            val (dx1, dy1, dx2, dy2) = if (isVertical) {
                floatArrayOf(l.x1 + d, l.y1, l.x2 + d, l.y2)
            } else {
                floatArrayOf(l.x1, l.y1 + d, l.x2, l.y2 + d)
            }
            for (p in paints) canvas.drawLine(dx1, dy1, dx2, dy2, p)
        }
    }

    /**
     * 在 [clipArea] 内填充 45° 单向绿色/灰色斜线网格（实线，step=40，数量少、开销低）。
     * 斜线相位基于 [ref]（整个矩形）计算，确保多条相邻带（环带）斜线连续不割裂。
     */
    private fun fillHatch(canvas: Canvas, clipArea: RectF, ref: RectF, color: Int, step: Float) {
        canvas.save()
        canvas.clipRect(clipArea)
        val hatch = Paint().apply {
            this.color = color
            strokeWidth = 2f
            isAntiAlias = true
        }
        val rectW = ref.width()
        val rectH = ref.height()
        var x = ref.left - rectH
        while (x <= ref.left + rectW) {
            val sx = maxOf(x, ref.left)
            val ex = minOf(x + rectH, ref.right)
            val topCut = if (x < ref.left) ref.left - x else 0f
            val botCut = if (x + rectH > ref.right) (x + rectH) - ref.right else 0f
            canvas.drawLine(sx, ref.top + topCut, ex, ref.bottom - botCut, hatch)
            x += step
        }
        canvas.restore()
    }

    /** 在屏幕偏下区域绘制超大红底黑字提示（用于零区域 / 全吸附状态），支持 \n 多行，上下内容边距 50。
     *  [sub] 为副标题：在主文下方、红底之内，字号约为 WARN_TEXT 的三分之一。 */
    private fun drawWarnBox(canvas: Canvas, w: Float, h: Float, msg: String, sub: String? = null) {
        val lines = msg.split("\n")
        val subLines = sub?.split("\n").orEmpty()
        val mp = Paint().apply { textSize = WARN_TEXT; isAntiAlias = true; textAlign = Paint.Align.CENTER }
        val subSize = WARN_TEXT / 3f
        val padY = 50f                 // 上下内容边距
        val lineGap = WARN_TEXT * 0.3f
        val subGap = 16f
        val textH = lines.size * WARN_TEXT + (lines.size - 1) * lineGap +
            if (subLines.isNotEmpty()) subGap + subLines.size * subSize else 0f
        val subMp = Paint().apply { textSize = subSize; isAntiAlias = true; textAlign = Paint.Align.CENTER }
        val mainW = lines.maxOf { mp.measureText(it) }
        val subW = if (subLines.isNotEmpty()) subLines.maxOf { subMp.measureText(it) } else 0f
        val boxW = maxOf(mainW, subW) + 60f
        val boxH = textH + padY * 2f
        val bx = (w - boxW) / 2f
        val byY = h * 0.72f
        canvas.drawRoundRect(RectF(bx, byY, bx + boxW, byY + boxH), 24f, 24f,
            Paint().apply { color = 0xFFFF0000.toInt(); isAntiAlias = true })
        val textPaint = mp.apply { color = Color.BLACK; textAlign = Paint.Align.CENTER }
        // 首行基线：上边距 + 文字上升约 WARN_TEXT/3（视觉居中）
        var baseline = byY + padY + WARN_TEXT / 3f + WARN_TEXT / 2f
        for (line in lines) {
            canvas.drawText(line, w / 2f, baseline, textPaint)
            baseline += WARN_TEXT + lineGap
        }
        if (subLines.isNotEmpty()) {
            baseline += subGap - lineGap
            val subPaint = subMp.apply { color = Color.BLACK; textAlign = Paint.Align.CENTER }
            for (sl in subLines) {
                canvas.drawText(sl, w / 2f, baseline, subPaint)
                baseline += subSize
            }
        }
    }

    /** 设置要显示的引导线（屏幕物理像素坐标）；labels 不为空时给每条线绘制小文字备注 */
    fun setLines(lines: List<Line>, labels: List<String>? = null, kinds: List<LineKind>? = null, activeIndex: Int = -1, snapActive: Boolean = false) {
        this.lines = lines
        this.boundLabels = labels
        this.lineKinds = kinds ?: lines.map { LineKind.BOUND }
        this.activeIndex = activeIndex
        this.snapActive = snapActive
        // 非 SNAP 模式时清除吸附环带状态（避免上一次 SNAP 残留）
        if (lineKinds.none { it == LineKind.SNAP }) {
            snapOuter = null
            snapInner = null
            snapEmpty = false
        }
        postInvalidate()
    }

    /**
     * 开启/关闭虚拟键盘预览层。开启时下方会显示一个斜线填充的“输入法键盘”矩形，
     * 并在其顶边之上 [lift] 像素处绘制一条绿色“抬高偏移”线（正=更高、负=更低）。
     * 四边活动范围偏移线仍由 setLines 提供，复用同一坐标。
     */
    fun setKeyboardPreview(enabled: Boolean, lift: Float = 0f, active: Boolean = false) {
        keyboardPreview = enabled
        imeLiftOffsetPreview = lift
        imeActive = active
        postInvalidate()
    }

    /**
     * 设置吸附区域环带（SNAP 模式专用）。outer=活动范围大矩形，inner=吸附合围小矩形；
     * 二者之间的环带以绿色斜线填充、并在四处标注“吸附区域”。
     * 当 inner 为 null 或 [empty]=true（吸附合围为空，即全吸附）时，不画环带而显示红底提示，
     * 但边界线仍保留（不删除辅助线）。
     */
    fun setSnapRing(outer: RectF?, inner: RectF?, empty: Boolean, onFlags: BooleanArray? = null, warn: Pair<String, String>? = null) {
        snapOuter = outer
        snapInner = inner
        snapEmpty = empty
        if (onFlags != null && onFlags.size == 4) snapOnFlags = onFlags
        snapWarn = warn
        postInvalidate()
    }

    /**
     * 设置宠物参数异常红框文案（大小/反弹系数/最大速度/重力强度越界时的「酥」吐槽）。
     * 为 null 时清除；多个异常同时成立时由调用方挑一个展示。
     */
    fun setPetWarn(warn: Pair<String, String>? = null) {
        petWarn = warn
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        pendingWarn = null   // 每帧重算，避免上一帧红框残留（高显示层级，末尾统一绘制）
        val w = width.toFloat()
        val h = height.toFloat()
        if (keyboardPreview && w > 0 && h > 0) {
            val kbTop = h - IME_PREVIEW_HEIGHT
            // 斜线填充矩形（用细斜线网格模拟“输入法键盘”区域）
            val diag = Paint().apply {
                color = 0x400000FF.toInt()
                strokeWidth = 2f
                isAntiAlias = true
            }
            val step = 18f
            var x = -h
            while (x < w) {
                val x2 = x + h
                val sx = maxOf(x, 0f)
                val ex = minOf(x2, w)
                val topCut = if (x < 0) -x else 0f
                val botCut = if (x2 > w) x2 - w else 0f
                canvas.drawLine(sx, kbTop + topCut, ex, kbTop + h - botCut, diag)
                x += step
            }
            // 键盘外框
            val frame = Paint().apply {
                color = 0xFF0000FF.toInt()
                strokeWidth = 3f
                style = Paint.Style.STROKE
                isAntiAlias = true
            }
            canvas.drawRect(0f, kbTop, w, h, frame)
            // 居中文字“输入法键盘”：蓝底红字（需求：蓝底红字）
            val kbText = "输入法键盘"
            drawTag(canvas, kbText, w / 2f, (kbTop + h) / 2f + TAG_TEXT / 3f,
                0xFF0000FF.toInt(), Color.RED)
            // 抬高偏移线（绿色虚线，位于键盘顶边之上）
            val liftY = kbTop - imeLiftOffsetPreview
            val imeLine = Line(0f, liftY, w, liftY)
            for (p in snapLayers) {
                canvas.drawLine(imeLine.x1, imeLine.y1, imeLine.x2, imeLine.y2, p)
            }
            // 正在调整抬高偏移时，叠加「五道交叉」突出当前线
            if (imeActive) drawFiveCross(canvas, imeLine, snapLayers)
            val liftLabel = if (imeLiftOffsetPreview >= 0)
                "抬高偏移 +${imeLiftOffsetPreview.toInt()}" else "抬高偏移 ${imeLiftOffsetPreview.toInt()}"
            // 抬高偏移：绿地黑字；备注框中心对齐抬高线中心（横线，水平居中，中心 y = liftY - ad/2）
            val adLift = tagAscent + tagDescent
            drawTag(canvas, liftLabel, w / 2f, liftY - adLift / 2f, 0xFF00AA00.toInt(), Color.BLACK)
        }
        // 区域块绘制（非键盘预览且至少有 4 条线）
        if (!keyboardPreview && lines.size >= 4) {
            if (snapOuter != null) {
                // ===== SNAP 模式：绘制“吸附区域”环带（活动范围大矩形 − 吸附合围小矩形）=====
                // 吸附距离调整红框三态文案：延后到所有引导线之上绘制（高显示层级）
                pendingWarn = snapWarn
                if (snapInner == null) {
                    // 吸附合围为空（全吸附）：不画环带，红框由 pendingWarn 稍后绘制（高显示层级）
                } else {
                    val outer = snapOuter!!
                    val inner = snapInner!!
                    // 环带按方向开关逐侧绘制（snapOnFlags 顺序：左、右、上、下）；关闭的方向不画带、不留标注。
                    val green = 0xFF00FF00.toInt()
                    val bgPaint = Paint().apply { color = 0x1A00FF00.toInt(); isAntiAlias = true }
                    val drawBand: (RectF) -> Unit = { band ->
                        canvas.drawRect(band, bgPaint)
                        fillHatch(canvas, band, outer, green, 40f)
                    }
                    if (snapOnFlags[2]) drawBand(RectF(outer.left, outer.top, outer.right, inner.top))       // 上
                    if (snapOnFlags[3]) drawBand(RectF(outer.left, inner.bottom, outer.right, outer.bottom))  // 下
                    if (snapOnFlags[0]) drawBand(RectF(outer.left, inner.top, inner.left, inner.bottom))      // 左
                    if (snapOnFlags[1]) drawBand(RectF(inner.right, inner.top, outer.right, inner.bottom))    // 右
                    // 四处标注「吸附区域」：仅开启的方向显示（横线上/下、竖线左/右，均中心对齐带中心）
                    val ad = tagAscent + tagDescent
                    val cx = (outer.left + outer.right) / 2f
                    val my = (inner.top + inner.bottom) / 2f
                    val topMidY = (outer.top + inner.top) / 2f
                    val botMidY = (inner.bottom + outer.bottom) / 2f
                    val leftMidX = (outer.left + inner.left) / 2f
                    val rightMidX = (inner.right + outer.right) / 2f
                    if (snapOnFlags[2]) drawTag(canvas, "吸附区域", cx, topMidY - ad / 2f, 0x8800AA00.toInt(), Color.WHITE)
                    if (snapOnFlags[3]) drawTag(canvas, "吸附区域", cx, botMidY - ad / 2f, 0x8800AA00.toInt(), Color.WHITE)
                    if (snapOnFlags[0]) drawTag(canvas, "吸附区域", leftMidX + ad / 2f, my, 0x8800AA00.toInt(), Color.WHITE, rotation = 90f)
                    if (snapOnFlags[1]) drawTag(canvas, "吸附区域", rightMidX - ad / 2f, my, 0x8800AA00.toInt(), Color.WHITE, rotation = -90f)
                }
            } else if (lineKinds.firstOrNull() == LineKind.BOUND) {
                // ===== 纯 BOUND 模式：绘制「活动范围」矩形 =====
                val lx = lines[0].x1
                val rx = lines[1].x1
                val ty = lines[2].y1
                val by = lines[3].y1
                val rectW = rx - lx
                val rectH = by - ty
                if (rectW > 0f && rectH > 0f) {
                    // 浅色背景底：在斜线之下铺一层浅灰，提升斜线区域的可见性
                    canvas.drawRect(lx, ty, rx, by, Paint().apply {
                        color = 0x1A000000.toInt(); isAntiAlias = true
                    })
                    // 斜线填充（黑灰实线）：裁剪到合围矩形内，单向 45° 平行网格，避免溢出
                    fillHatch(canvas, RectF(lx, ty, rx, by), RectF(lx, ty, rx, by), 0x55000000.toInt(), 40f)
                    // 标注「活动范围」：灰底白字，置于合围矩形正中心
                    drawTag(canvas, "活动范围", (lx + rx) / 2f, (ty + by) / 2f + TAG_TEXT / 3f, 0x88000000.toInt(), Color.WHITE)
                } else {
                    // 零区域：屏幕偏下红色圆角矩形 + 超大黑字（高显示层级，稍后统一绘制）
                    pendingWarn = "活动范围不存在？\n「酥」要闹了！「酥」要闹了！！！" to
                        "上下左右合围范围不存在，得调整上下左右偏移量"
                }
            }
        }
        // 每条逻辑线：BOUND 走黑白交替四层，SNAP 走绿色虚线
        lines.forEachIndexed { i, l ->
            val isActive = i == activeIndex
            val isSnap = lineKinds.getOrNull(i) == LineKind.SNAP
            // 吸附内缩线：对应方向开关关闭（snapOnFlags[dir]）时不绘制（含其备注）
            if (isSnap) {
                val dir = i - 4
                if (dir in 0..3 && !snapOnFlags[dir]) return@forEachIndexed
                for (p in snapLayers) {
                    canvas.drawLine(l.x1, l.y1, l.x2, l.y2, p)
                }
            } else {
                for (p in layers) {
                    canvas.drawLine(l.x1, l.y1, l.x2, l.y2, p)
                }
            }
            // 正在调整的偏移线：叠加「五道交叉」——沿法线方向平行偏移 5 条。
            // 边界线（activeIndex）走黑白 layers；吸附内缩线（snapActive）走绿色 snapLayers。
            if (isActive) {
                drawFiveCross(canvas, l, layers)
            } else if (isSnap && snapActive) {
                drawFiveCross(canvas, l, snapLayers)
            }
            // 四边偏移线的小文字备注：黑底白字。按索引定位（0左/1右/2上/3下；4-7为吸附线，错开）
            // 基准点统一取「偏移线的中点」：横线中点=(x1+x2)/2，竖线中点=(y1+y2)/2；
            // 再朝指定方位错开一点点（left/right 偏移作用于横线水平，up/down 偏移作用于竖线垂直）。
            boundLabels?.getOrNull(i)?.let { text ->
                // 备注矩形（宽 TAG_WIDTH，高=descent-ascent+2*TAG_PAD，均为常量）中心对齐到偏移线中心：
                //  - 横线（不旋转）：drawTag 框中心 y = baselineY + ad/2（ad=ascent+descent），
                //    令框中心 = 线 y → baselineY = l.y1 - ad/2；水平居中 cx=midX。
                //  - 竖线 rot90：旋转后框中心 = (cx-ad/2, baselineY)，令=线中心(l.x1,midY) → cx=l.x1+ad/2。
                //  - 竖线 rot-90：旋转后框中心 = (cx+ad/2, baselineY)，令=线中心 → cx=l.x1-ad/2。
                val isSnap = lineKinds.getOrNull(i) == LineKind.SNAP
                val bg = if (isSnap) 0xFF006400.toInt() else Color.BLACK
                val midX = (l.x1 + l.x2) / 2f
                val midY = (l.y1 + l.y2) / 2f
                val ad = tagAscent + tagDescent
                when (i % 4) {
                    2, 3 -> { // 横线（上/下）：框中心对齐线 y，水平居中
                        drawTag(canvas, text, midX, l.y1 - ad / 2f, bg, Color.WHITE)
                    }
                    0 -> { // 左竖线（顺时针90°）：框中心对齐线 x
                        drawTag(canvas, text, l.x1 + ad / 2f, midY, bg, Color.WHITE, rotation = 90f)
                    }
                    1 -> { // 右竖线（逆时针90°）：框中心对齐线 x
                        drawTag(canvas, text, l.x1 - ad / 2f, midY, bg, Color.WHITE, rotation = -90f)
                    }
                    else -> {
                        drawTag(canvas, text, midX, midY - ad / 2f, bg, Color.WHITE)
                    }
                }
            }
        }
        // 红框（警告）以最高显示层级绘制，覆盖在所有引导线/备注之上
        // 优先吸附/活动范围类警告；若无，则展示宠物参数异常红框（大小/反弹/速度/重力）。
        if (pendingWarn == null) pendingWarn = petWarn
        pendingWarn?.let { (m, s) -> drawWarnBox(canvas, w, h, m, s) }
    }
}
