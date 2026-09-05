package com.cx.cakepet

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * HSV 色轮取色器（自绘 View）。
 *
 * 映射关系：
 *  - 角度 = 色相 Hue（0-360°，0° 位于 +x 轴即 3 点钟方向为红，顺时针递增）
 *  - 半径 = 饱和度 Saturation（圆心 0，圆周 1）
 *  - 明度 Value 由外部 setValue() 提供
 *
 * 色轮本身**不叠加明度**：若把明度压到很低，整个轮会变黑导致看不清色相，
 * 因此明度交由独立的滑条控制，色轮始终保持全亮，取色时一目了然。
 * 最终颜色由 [color] 读取（合成 HSV 三者）。
 */
class ColorWheelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** 取色变化回调（参数为合成后的 ARGB 颜色，alpha 固定 0xFF） */
    var onColorChanged: ((Int) -> Unit)? = null

    // HSV 三元组：0=Hue(0-360) 1=Saturation(0-1) 2=Value(0-1)
    private val hsv = floatArrayOf(0f, 0f, 1f)

    private val density = resources.displayMetrics.density

    private var cx = 0f
    private var cy = 0f
    private var radius = 0f
    private var sweep: SweepGradient? = null
    private var radial: RadialGradient? = null

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x33000000
        strokeWidth = 1f * density
    }

    // 指示器：黑圈打底 + 白圈覆盖，保证在任何底色上都清晰可见
    private val indicatorOuter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeWidth = 4.5f * density
    }
    private val indicatorInner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = 2.5f * density
    }

    /** 当前颜色（合成 HSV 后的 ARGB） */
    val color: Int get() = Color.HSVToColor(hsv)

    /** 当前明度（0-1） */
    fun getValue(): Float = hsv[2]

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val availableW = w - paddingLeft - paddingRight
        val availableH = h - paddingTop - paddingBottom
        radius = min(availableW, availableH) / 2f
        cx = paddingLeft + availableW / 2f
        cy = paddingTop + availableH / 2f
        buildShaders()
    }

    private fun buildShaders() {
        if (radius <= 0f) return
        // 色相环：13 个采样点（首尾同色），保证 360° 闭合处无接缝
        val hueColors = IntArray(13) { i ->
            Color.HSVToColor(floatArrayOf((i % 12) * 30f, 1f, 1f))
        }
        sweep = SweepGradient(cx, cy, hueColors, null)
        // 饱和度：中心纯白（饱和度 0）→ 圆周透明（饱和度 1，露出纯色相）
        radial = RadialGradient(cx, cy, radius, Color.WHITE, 0x00FFFFFF, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (radius <= 0f) return
        // 1) 色相环
        fillPaint.shader = sweep
        canvas.drawCircle(cx, cy, radius, fillPaint)
        // 2) 饱和度渐变叠加
        fillPaint.shader = radial
        canvas.drawCircle(cx, cy, radius, fillPaint)
        fillPaint.shader = null
        // 3) 外圈描边，界定可点击范围
        canvas.drawCircle(cx, cy, radius - ringPaint.strokeWidth, ringPaint)
        // 4) 指示器
        val p = indicatorPos()
        val r = 7f * density
        canvas.drawCircle(p.first, p.second, r, indicatorOuter)
        canvas.drawCircle(p.first, p.second, r, indicatorInner)
    }

    /** 指示器屏幕坐标：由 hue（角度）与 saturation（半径比例）还原 */
    private fun indicatorPos(): Pair<Float, Float> {
        val angle = Math.toRadians(hsv[0].toDouble())
        val r = hsv[1] * radius
        return Pair(cx + (cos(angle) * r).toFloat(), cy + (sin(angle) * r).toFloat())
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 阻止父容器（弹窗/滚动区）拦截，保证能连续拖动取色
                parent?.requestDisallowInterceptTouchEvent(true)
                updateFromTouch(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                updateFromTouch(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateFromTouch(x: Float, y: Float) {
        if (radius <= 0f) return
        val dx = x - cx
        val dy = y - cy
        // atan2 与 SweepGradient 角度体系一致：0° 在 +x 轴，顺时针为正
        var deg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        if (deg < 0f) deg += 360f
        val dist = sqrt(dx * dx + dy * dy)
        hsv[0] = deg.coerceIn(0f, 360f)
        hsv[1] = (dist / radius).coerceIn(0f, 1f)
        invalidate()
        onColorChanged?.invoke(color)
    }

    /** 由 ARGB 颜色反推 HSV（忽略 alpha），用于打开取色器时回填指示器位置 */
    fun setColor(color: Int) {
        Color.colorToHSV(color, hsv)
        invalidate()
    }

    /** 设置明度（0-1），不改变色相与饱和度 */
    fun setValue(v: Float) {
        hsv[2] = v.coerceIn(0f, 1f)
        invalidate()
    }
}
