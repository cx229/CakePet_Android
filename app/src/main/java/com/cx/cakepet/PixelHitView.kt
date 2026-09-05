package com.cx.cakepet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.view.MotionEvent
import android.view.View
import kotlin.math.round

/**
 * 最小化像素级命中测试 View（同时用于验证 setTouchableRegion 的两种方案）：
 * - 绘制 assets/img/probe_head-1.png（3x 缩放居中，红色边框标记 View 边界）
 * - DOWN 仅在“可点击区域”内才消费；区域外 return false（配合 setTouchableRegion 真穿透）
 * - regionMode 决定窗口系统层面的可点击区域：
 *     PIXEL = 像素级真实轮廓（逐非透明像素并入 Region）
 *     GRID  = 25 矩形（5x5 网格，占比阈值生成 25 个小矩形 union）
 *     OFF   = 不设 Region（整窗拦截，作为对照）
 * - 命中/消费时在图片外围画绿色外框，方便肉眼确认“被消费”
 */
class PixelHitView(context: Context) : View(context) {

    enum class RegionMode { PIXEL, GRID, OFF }

    private var bitmap: Bitmap? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint().apply {
        color = 0xFFFF0000.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    // 消费外框（绿）：命中可点击区域并消费事件时绘制
    private val consumePaint = Paint().apply {
        color = 0xFF00FF00.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private val scale = 3f

    // 当前 View 左上角在屏幕上的坐标（WM 时由外部维护 layoutParams）
    var viewX = 0f
    var viewY = 0f
    var viewX0 = 0f
    var viewY0 = 0f

    var regionMode: RegionMode = RegionMode.OFF

    // 命中/消费状态：消费时画绿色外框
    private var consumed = false

    var onPositionUpdate: ((x: Float, y: Float) -> Unit)? = null

    init {
        bitmap = try {
            context.assets.open("img/probe_head-1.png").use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            null
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val bmp = bitmap
        if (bmp == null) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val w = (bmp.width * scale).toInt().coerceAtLeast(1)
        val h = (bmp.height * scale).toInt().coerceAtLeast(1)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = bitmap ?: return
        val dw = bmp.width * scale
        val dh = bmp.height * scale
        val left = (width - dw) / 2f
        val top = (height - dh) / 2f

        canvas.drawBitmap(bmp, null, android.graphics.RectF(left, top, left + dw, top + dh), paint)
        canvas.drawRect(2f, 2f, width - 2f, height - 2f, borderPaint)

        if (consumed) {
            // 图片外围绿色可点击外框
            canvas.drawRect(left - 4f, top - 4f, left + dw + 4f, top + dh + 4f, consumePaint)
        }
    }

    /** 像素坐标 -> View 坐标 的正向矩阵（bitmap -> 居中 3x） */
    private fun buildMatrix(): Matrix {
        val bmp = bitmap ?: return Matrix()
        val dw = bmp.width * scale
        val dh = bmp.height * scale
        val left = (width - dw) / 2f
        val top = (height - dh) / 2f
        val m = Matrix()
        m.postTranslate(left, top)
        m.postScale(scale, scale)
        return m
    }

    /** 像素级命中判定：只认非透明像素 */
    fun isHitOnPet(x: Float, y: Float): Boolean {
        val bmp = bitmap ?: return false
        val w = bmp.width
        val h = bmp.height
        if (w <= 0 || h <= 0) return false

        val m = buildMatrix()
        val inv = Matrix()
        if (!m.invert(inv)) return false
        val pts = floatArrayOf(x, y)
        inv.mapPoints(pts)
        val px = round(pts[0]).toInt()
        val py = round(pts[1]).toInt()
        if (px < 0 || py < 0 || px >= w || py >= h) return false
        return (bmp.getPixel(px, py).ushr(24)) != 0
    }

    // ============ Region 构造（窗口/屏幕坐标，含 viewX/viewY 偏移）============

    /** 当前图片在窗口坐标系中的绘制矩形（居中 3x 的区域） */
    private fun drawRectInView(): RectF {
        val bmp = bitmap ?: return RectF(0f, 0f, width.toFloat(), height.toFloat())
        val dw = bmp.width * scale
        val dh = bmp.height * scale
        val left = (width - dw) / 2f
        val top = (height - dh) / 2f
        return RectF(left, top, left + dw, top + dh)
    }

    /**
     * 构建 Region（屏幕坐标）。调用前需已设置 viewX/viewY。
     * 返回 null 表示不设置（OFF）。
     */
    fun buildTouchableRegion(): Region? {
        val bmp = bitmap ?: return null
        when (regionMode) {
            RegionMode.OFF -> return null
            RegionMode.PIXEL -> return buildPixelRegion(bmp)
            RegionMode.GRID -> return buildGridRegion(bmp, 5)
        }
    }

    /** 像素级：逐非透明像素并入 Region（demo 图小，成本可接受） */
    private fun buildPixelRegion(bmp: Bitmap): Region {
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val base = drawRectInView()
        val sx = base.width() / w
        val sy = base.height() / h
        val ox = base.left + viewX
        val oy = base.top + viewY
        val region = Region()
        val r = Rect()
        for (py in 0 until h) {
            for (px in 0 until w) {
                if ((pixels[py * w + px].ushr(24)) != 0) {
                    r.set(
                        (ox + px * sx).toInt(),
                        (oy + py * sy).toInt(),
                        (ox + (px + 1) * sx).toInt(),
                        (oy + (py + 1) * sy).toInt()
                    )
                    region.op(r, Region.Op.UNION)
                }
            }
        }
        return region
    }

    /** 25 矩形：5x5 网格，非透明占比 > 阈值 的格子 union 成 Region */
    private fun buildGridRegion(bmp: Bitmap, n: Int): Region {
        val w = bmp.width
        val h = bmp.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        val base = drawRectInView()
        val cellW = base.width() / n
        val cellH = base.height() / n
        val ox = base.left + viewX
        val oy = base.top + viewY
        val region = Region()
        val r = Rect()
        val threshold = 0.5f
        for (gr in 0 until n) {
            for (gc in 0 until n) {
                // 统计该格非透明占比
                val x0 = (gc * w / n)
                val x1 = ((gc + 1) * w / n).coerceAtMost(w)
                val y0 = (gr * h / n)
                val y1 = ((gr + 1) * h / n).coerceAtMost(h)
                var total = 0
                var solid = 0
                for (yy in y0 until y1) {
                    for (xx in x0 until x1) {
                        total++
                        if ((pixels[yy * w + xx].ushr(24)) != 0) solid++
                    }
                }
                if (total > 0 && solid.toFloat() / total >= threshold) {
                    r.set(
                        (ox + gc * cellW).toInt(),
                        (oy + gr * cellH).toInt(),
                        (ox + (gc + 1) * cellW).toInt(),
                        (oy + (gr + 1) * cellH).toInt()
                    )
                    region.op(r, Region.Op.UNION)
                }
            }
        }
        return region
    }

    // ============ 触摸处理 ============

    private var dragging = false
    private var grabX = 0f
    private var grabY = 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 区域外恒定不消费，直接放行（配合 setTouchableRegion 穿透到下层）
        if (event.action == MotionEvent.ACTION_DOWN && !isHitOnPet(event.x, event.y)) {
            consumed = false
            invalidate()
            return false
        }

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                consumed = true
                invalidate()
                grabX = event.rawX - viewX
                grabY = event.rawY - viewY
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging) {
                    val nx = event.rawX - grabX
                    val ny = event.rawY - grabY
                    viewX = nx
                    viewY = ny
                    onPositionUpdate?.invoke(nx, ny)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                consumed = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
