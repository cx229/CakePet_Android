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
import org.json.JSONArray
import org.json.JSONObject

/** 全屏共享显示画布实验：只绘制矩形边界，不逐帧扫描像素。 */
class AccessibilityHitTestView(context: Context) : View(context) {
    private data class Frame(val bitmap: Bitmap, val delayMs: Long, val bounds: RectF)
    private val frames = ArrayList<Frame>()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val imagePaint = Paint().apply { color = Color.YELLOW; style = Paint.Style.STROKE; strokeWidth = 2f }
    private val pixelPaint = Paint().apply { color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 2f }
    private val windowPaint = Paint().apply { color = Color.CYAN; style = Paint.Style.STROKE; strokeWidth = 3f }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.CYAN; textSize = 27f }
    private var index = 0
    private var nextAt = 0L
    var petX = 0f
        private set
    var petY = 0f
        private set
    val petSize = 256f
    private var initialized = false
    var onMove: ((Float, Float) -> Unit)? = null
    var onClose: (() -> Unit)? = null
    var fullScreenTouch = false
    private var dragging = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0f
    private var startY = 0f

    init {
        val root = JSONObject(context.assets.open("白酥/modes.json").bufferedReader().use { it.readText() })
        val arr = root.getJSONObject("modes").getJSONObject("sit_clam").getJSONArray("frames")
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            val bitmap = context.assets.open("白酥/" + item.getString("res")).use { BitmapFactory.decodeStream(it) }
            val delay = item.get("delay")
            val ms = if (delay is JSONArray) delay.getLong(0) else (delay as Number).toLong()
            // 每张素材仅计算一次非透明像素最小外接矩形。
            var minX = bitmap.width
            var minY = bitmap.height
            var maxX = -1
            var maxY = -1
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            for (y in 0 until bitmap.height) {
                for (x in 0 until bitmap.width) {
                    if (Color.alpha(pixels[y * bitmap.width + x]) != 0) {
                        if (x < minX) minX = x
                        if (y < minY) minY = y
                        if (x > maxX) maxX = x
                        if (y > maxY) maxY = y
                    }
                }
            }
            val bounds = if (maxX >= 0) RectF(
                minX.toFloat(), minY.toFloat(), (maxX + 1).toFloat(), (maxY + 1).toFloat()
            ) else RectF()
            frames.add(Frame(bitmap, ms.coerceAtLeast(30L), bounds))
        }
        require(frames.isNotEmpty())
    }

    fun initialize(w: Int, h: Int) {
        if (initialized) return
        petX = (w - petSize) / 2f
        petY = (h - petSize) / 2f
        initialized = true
    }

    fun moveTo(x: Float, y: Float) {
        petX = x.coerceIn(0f, (width - petSize).coerceAtLeast(0f))
        petY = y.coerceIn(0f, (height - petSize).coerceAtLeast(0f))
        invalidate()
    }

    fun pixelAlpha(localX: Float, localY: Float): Int {
        val bmp = frames[index].bitmap
        val x = (localX * bmp.width / petSize).toInt()
        val y = (localY * bmp.height / petSize).toInt()
        return if (x in 0 until bmp.width && y in 0 until bmp.height) Color.alpha(bmp.getPixel(x, y)) else -1
    }

    fun pixelHit(localX: Float, localY: Float): Boolean {
        val bmp = frames[index].bitmap
        val x = (localX * bmp.width / petSize).toInt()
        val y = (localY * bmp.height / petSize).toInt()
        return x in 0 until bmp.width && y in 0 until bmp.height &&
            Color.alpha(bmp.getPixel(x, y)) != 0
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        initialize(width, height)
        val now = SystemClock.uptimeMillis()
        if (nextAt == 0L) nextAt = now + frames[index].delayMs
        if (now >= nextAt) {
            index = (index + 1) % frames.size
            nextAt = now + frames[index].delayMs
        }
        val frame = frames[index]
        val rect = RectF(petX, petY, petX + petSize, petY + petSize)
        canvas.drawBitmap(frame.bitmap, null, rect, bitmapPaint)
        canvas.drawRect(rect, imagePaint)
        val scaleX = petSize / frame.bitmap.width
        val scaleY = petSize / frame.bitmap.height
        val b = frame.bounds
        canvas.drawRect(petX + b.left * scaleX, petY + b.top * scaleY,
            petX + b.right * scaleX, petY + b.bottom * scaleY, pixelPaint)
        canvas.drawRect(1.5f, 1.5f, width - 1.5f, height - 1.5f, windowPaint)
        canvas.drawText("全屏画布 | 黄:图片 红:像素外接框 青:画布", 12f, 42f, textPaint)
        if (fullScreenTouch) canvas.drawText("关闭 X", width - 130f, 85f, textPaint)
        // 按素材的实际延迟调度，而不是固定每 33ms 重绘全屏。
        postInvalidateDelayed((nextAt - now).coerceAtLeast(16L))
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!fullScreenTouch) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (event.x > width - 155f && event.y < 120f) {
                    onClose?.invoke()
                    return true
                }
                dragging = pixelHit(event.x - petX, event.y - petY)
                downRawX = event.rawX
                downRawY = event.rawY
                startX = petX
                startY = petY
                return dragging
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                moveTo(startX + event.rawX - downRawX, startY + event.rawY - downRawY)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val handled = dragging
                dragging = false
                return handled
            }
        }
        return false
    }

    fun release() {
        frames.forEach { it.bitmap.recycle() }
        frames.clear()
    }
}
