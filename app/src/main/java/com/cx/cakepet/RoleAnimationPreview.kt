package com.cx.cakepet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.min

/** Full-width 2:1 editor view with a centered, unwarped 1:1 logical canvas. */
class RoleAnimationPreview(context: Context) : View(context) {
    var showGuides = true
    private var loops = false
    private var frameIndex = 0
    private var image: Bitmap? = null
    private var bounds = RectF()
    private var anchor = 0 to 0
    private var dx = 0
    private var dy = 0
    private var canvasRes = 128
    private var mode = ""
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2f }

    fun update(bitmap: Bitmap?, contentBounds: RectF, at: Pair<Int, Int>,
               offsetX: Int, offsetY: Int, resolution: Int, name: String,
               isLooping: Boolean, currentFrameIndex: Int) {
        image = bitmap
        bounds = RectF(contentBounds)
        anchor = at
        dx = offsetX
        dy = offsetY
        canvasRes = resolution.coerceAtLeast(1)
        mode = name
        loops = isLooping
        frameIndex = currentFrameIndex
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, (width / 2).coerceAtLeast(1))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(242, 244, 246))
        val margin = 20f * resources.displayMetrics.density
        val side = (min(width, height) - 2 * margin).coerceAtLeast(1f)
        val left = (width - side) / 2f
        val top = (height - side) / 2f
        val ratio = side / canvasRes
        val imageRect = RectF(left + dx * ratio, top + dy * ratio,
            left + (canvasRes + dx) * ratio, top + (canvasRes + dy) * ratio)
        image?.let { bitmap ->
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            canvas.drawBitmap(bitmap, null, imageRect, paint)
        }
        if (showGuides) {
            paint.color = Color.rgb(110, 123, 140)
            paint.style = Paint.Style.STROKE
            paint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(9f, 6f), 0f)
            canvas.drawRect(left, top, left + side, top + side, paint)
            paint.pathEffect = null
            paint.color = Color.rgb(39, 139, 203)
            val content = RectF(left + (bounds.left + dx) * ratio,
                top + (bounds.top + dy) * ratio,
                left + (bounds.right + dx) * ratio,
                top + (bounds.bottom + dy) * ratio)
            canvas.drawRect(content, paint)
            val ax = left + (anchor.first + dx) * ratio
            val ay = top + (anchor.second + dy) * ratio
            paint.color = Color.rgb(222, 66, 54)
            canvas.drawCircle(ax, ay, 5f, paint)
            canvas.drawLine(ax - 15f, ay, ax + 15f, ay, paint)
            canvas.drawLine(ax, ay - 15f, ax, ay + 15f, paint)
            paint.style = Paint.Style.FILL
        }
        // Non-layout HUD in the upper-left corner of the 2:1 preview.
        val scale = resources.displayMetrics.scaledDensity
        val hud = listOf(
            "画布：灰色虚线",
            "图片：蓝色矩形",
            "锚点：红十字 (${anchor.first}, ${anchor.second})",
            "位移：($dx, $dy)",
            "当前动作：$mode",
            "（帧$frameIndex，循环${if (loops) "开" else "关"}）"
        )
        paint.style = Paint.Style.FILL
        paint.textSize = 10f * scale
        val lineHeight = 14f * scale
        val hudWidth = hud.maxOf { paint.measureText(it) } + 14f * scale
        paint.color = Color.argb(213, 255, 255, 255)
        canvas.drawRoundRect(4f, 4f, hudWidth + 4f, lineHeight * hud.size + 9f, 6f, 6f, paint)
        paint.color = Color.rgb(44, 55, 67)
        hud.forEachIndexed { i, line ->
            canvas.drawText(line, 11f, lineHeight * (i + 1), paint)
        }

    }
}
