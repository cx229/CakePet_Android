package com.cx.cakepet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import org.json.JSONObject

/**
 * 独立的无障碍全屏画布实验。青色框：窗口边界；黄色框：角色图像边界；
 * 红色轮廓：当前帧非透明像素的外沿。透明像素不会启动角色拖动。
 * 注意：Android 的窗口级触摸分发不保证透明像素能透传给下层应用。
 */
class AccessibilityHitTestView(context: Context) : View(context) {
    private data class Frame(val bitmap: Bitmap, val delayMs: Long)
    private val frames = ArrayList<Frame>()
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 1.5f
    }
    private val imageBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW; style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val windowBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.CYAN; style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.CYAN; textSize = 28f
    }
    var onClose: (() -> Unit)? = null
    private var index = 0
    private var nextFrameAt = 0L
    private var left = 0f
    private var top = 0f
    private var initialized = false
    private var dragging = false
    private var lastX = 0f
    private var lastY = 0f
    private val drawSize = 256f

    init {
        // 直接读取已预装的白酥素材与 modes.json 中静坐帧顺序，绝不复制角色图片。
        val root = JSONObject(context.assets.open("白酥/modes.json").bufferedReader().use { it.readText() })
        val arr = root.getJSONObject("modes").getJSONObject("sit_clam").getJSONArray("frames")
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            val path = "白酥/" + item.getString("res")
            val bmp = context.assets.open(path).use { BitmapFactory.decodeStream(it) }
            val delay = item.get("delay")
            val ms = if (delay is org.json.JSONArray) delay.getLong(0) else (delay as Number).toLong()
            frames.add(Frame(bmp, ms.coerceAtLeast(30L)))
        }
        require(frames.isNotEmpty()) { "白酥静坐素材不存在" }
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    private fun frame(): Bitmap = frames[index].bitmap

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!initialized && width > 0 && height > 0) {
            left = (width - drawSize) / 2f
            top = (height - drawSize) / 2f
            initialized = true
        }
        val now = SystemClock.uptimeMillis()
        if (nextFrameAt == 0L) nextFrameAt = now + frames[index].delayMs
        if (now >= nextFrameAt) {
            index = (index + 1) % frames.size
            nextFrameAt = now + frames[index].delayMs
        }
        val bmp = frame()
        val dest = RectF(left, top, left + drawSize, top + drawSize)
        canvas.drawBitmap(bmp, null, dest, bitmapPaint)
        canvas.drawRect(dest, imageBorder)
        canvas.drawRect(1.5f, 1.5f, width - 1.5f, height - 1.5f, windowBorder)
        canvas.drawText("Accessibility overlay / window boundary", 12f, 40f, labelPaint)
        canvas.drawText("关闭 X", width - 125f, 80f, labelPaint)
        // 在位图坐标中扫描邻接透明像素的边缘，标出实际 alpha 命中轮廓。
        val sx = drawSize / bmp.width
        val sy = drawSize / bmp.height
        for (y in 0 until bmp.height) {
            for (x in 0 until bmp.width) {
                if (Color.alpha(bmp.getPixel(x, y)) == 0) continue
                val edge = x == 0 || y == 0 || x == bmp.width - 1 || y == bmp.height - 1 ||
                    Color.alpha(bmp.getPixel((x - 1).coerceAtLeast(0), y)) == 0 ||
                    Color.alpha(bmp.getPixel((x + 1).coerceAtMost(bmp.width - 1), y)) == 0 ||
                    Color.alpha(bmp.getPixel(x, (y - 1).coerceAtLeast(0))) == 0 ||
                    Color.alpha(bmp.getPixel(x, (y + 1).coerceAtMost(bmp.height - 1))) == 0
                if (edge) canvas.drawRect(left + x * sx, top + y * sy,
                    left + (x + 1) * sx, top + (y + 1) * sy, outline)
            }
        }
        postInvalidateDelayed(33L)
    }

    private fun pixelHit(x: Float, y: Float): Boolean {
        val bmp = frame()
        val px = ((x - left) * bmp.width / drawSize).toInt()
        val py = ((y - top) * bmp.height / drawSize).toInt()
        return px in 0 until bmp.width && py in 0 until bmp.height &&
            Color.alpha(bmp.getPixel(px, py)) != 0
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (event.x > width - 155f && event.y < 120f) {
                    onClose?.invoke()
                    return true
                }
                dragging = pixelHit(event.x, event.y)
                lastX = event.x; lastY = event.y
                return dragging // 返回 false 不等于系统把事件转发给其他 App。
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                left = (left + event.x - lastX).coerceIn(0f, (width - drawSize).coerceAtLeast(0f))
                top = (top + event.y - lastY).coerceIn(0f, (height - drawSize).coerceAtLeast(0f))
                lastX = event.x; lastY = event.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasDragging = dragging
                dragging = false
                return wasDragging
            }
        }
        return false
    }

    fun release() {
        frames.forEach { it.bitmap.recycle() }
        frames.clear()
    }
}
