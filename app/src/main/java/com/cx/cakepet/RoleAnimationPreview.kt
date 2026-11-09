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
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        invalidate()
    }
    var showGuides = true
    private var loops = false
    private var frameIndex = 0
    private var image: Bitmap? = null
    private var bounds = RectF()
    private var anchor = 0 to 0
    private var dx = 0
    private var dy = 0
    private var frameFlipX = false
    private var canvasRes = 128
    private var mode = ""
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2f }

    fun update(bitmap: Bitmap?, contentBounds: RectF, at: Pair<Int, Int>,
               offsetX: Int, offsetY: Int, resolution: Int, name: String,
               isLooping: Boolean, currentFrameIndex: Int, flipX: Boolean = false) {
        image = bitmap
        bounds = RectF(contentBounds)
        anchor = at
        dx = offsetX
        dy = offsetY
        frameFlipX = flipX
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
        canvas.drawColor(resources.getColor(R.color.cp_preview_canvas, context.theme))
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
            paint.alpha = 255
            canvas.save()
            if (frameFlipX) canvas.scale(-1f, 1f, imageRect.centerX(), imageRect.centerY())
            canvas.drawBitmap(bitmap, null, imageRect, paint)
            canvas.restore()
        }
        if (showGuides) {
            paint.color = resources.getColor(R.color.cp_preview_guide, context.theme)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 6f
            paint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(9f, 6f), 0f)
            canvas.drawRect(left, top, left + side, top + side, paint)
            paint.pathEffect = null
            paint.strokeWidth = 2f
            paint.color = resources.getColor(R.color.cp_preview_bounds, context.theme)
            // Guide coordinates follow source flip while dx/dy stay unchanged.
            val bx0 = if (frameFlipX) canvasRes - bounds.right else bounds.left
            val bx1 = if (frameFlipX) canvasRes - bounds.left else bounds.right
            val content = RectF(left + (bx0 + dx) * ratio,
                top + (bounds.top + dy) * ratio,
                left + (bx1 + dx) * ratio,
                top + (bounds.bottom + dy) * ratio)
            canvas.drawRect(content, paint)
            val ax = left + ((if (frameFlipX) canvasRes - anchor.first else anchor.first) + dx) * ratio
            val ay = top + (anchor.second + dy) * ratio
            paint.color = resources.getColor(R.color.cp_preview_anchor, context.theme)
            paint.strokeWidth = 3.5f
            canvas.drawCircle(ax, ay, 7f, paint)
            canvas.drawLine(ax - 24f, ay, ax + 24f, ay, paint)
            canvas.drawLine(ax, ay - 24f, ax, ay + 24f, paint)
            paint.style = Paint.Style.FILL
        }
        // Non-layout HUD in the upper-left corner of the 2:1 preview.
        val scale = resources.displayMetrics.scaledDensity
        val hud = listOf(
            "画布：灰色虚线",
            "图片：蓝色矩形",
            "锚点：红十字 (${anchor.first}, ${anchor.second})",
            "位移：($dx, $dy)",
            "帧镜像：${if (frameFlipX) "开" else "关"}",
            "当前动作：$mode",
            "（帧$frameIndex，循环${if (loops) "开" else "关"}）"
        )
        paint.style = Paint.Style.FILL
        paint.textSize = 10f * scale
        val lineHeight = 14f * scale
        val hudWidth = hud.maxOf { paint.measureText(it) } + 14f * scale
        paint.color = resources.getColor(R.color.cp_preview_hud_bg, context.theme)
        canvas.drawRoundRect(4f, 4f, hudWidth + 4f, lineHeight * hud.size + 9f, 6f, 6f, paint)
        paint.color = resources.getColor(R.color.cp_preview_hud_text, context.theme)
        hud.forEachIndexed { i, line ->
            canvas.drawText(line, 11f, lineHeight * (i + 1), paint)
        }

    }
}
