package com.cx.cakepet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/** Unified live debugger: editor page and detail dialog share exactly this view. */
class RoleAnimationPreview(context: Context) : View(context) {
    data class State(
        val chain: List<String> = emptyList(),
        val current: String = "",
        val frame: Int = 0,
        val frameCount: Int = 1,
        val frameFraction: Float = 0f,
        /** -1: active without a predictable end; 0..1: determinate/complete. */
        val primaryProgress: Float = -1f,
        val loopKind: String = "off",
        val duration: String = "",
        val status: String = "播放中"
    )
    var showGuides = true
    var onReplay: (() -> Unit)? = null
    private var state = State()
    private var image: Bitmap? = null
    private var bounds = RectF()
    private var anchor = 0 to 0
    private var referenceAnchor = 0 to 0
    private var anchorReferenceSet = false
    private var dx = 0
    private var dy = 0
    private var resolution = 128
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density get() = resources.displayMetrics.density
    private val scaled get() = resources.displayMetrics.scaledDensity
    private fun dp(n: Float) = n * density
    private fun chainLines(widthPx: Int): Int {
        paint.textSize = 10f * scaled
        var lines = 1
        var x = dp(12f)
        val maxX = (widthPx - dp(110f)).coerceAtLeast(dp(110f))
        state.chain.forEachIndexed { index, name ->
            val label = if (index == 0) name else " → $name"
            val w = paint.measureText(label) + dp(6f)
            if (x > dp(12f) && x + w > maxX) { ++lines; x = dp(12f) }
            x += w
        }
        return lines
    }
    fun resetReference() { anchorReferenceSet = false }

    fun update(bitmap: Bitmap?, contentBounds: RectF, at: Pair<Int, Int>,
               offsetX: Int, offsetY: Int, canvasResolution: Int, playback: State) {
        image = bitmap
        bounds = RectF(contentBounds)
        anchor = at
        if (!anchorReferenceSet) {
            referenceAnchor = at
            anchorReferenceSet = true
        }
        dx = offsetX
        dy = offsetY
        resolution = canvasResolution.coerceAtLeast(1)
        state = playback
        requestLayout()
        invalidate()
    }
    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec).coerceAtLeast(1)
        setMeasuredDimension(w, w / 2 + dp(40f + chainLines(w) * 18f).toInt())
    }
    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(resources.getColor(R.color.cp_preview_canvas, context.theme))
        val artHeight = width / 2f
        val side = (min(width.toFloat(), artHeight) - dp(50f)).coerceAtLeast(1f)
        val left = (width - side) / 2f
        val top = (artHeight - side) / 2f
        val ratio = side / resolution
        // dx/dy move the physical canvas; an anchor adjustment moves only the
        // bitmap relative to a fixed anchor position in that canvas.
        val canvasLeft = left + dx * ratio
        val canvasTop = top + dy * ratio
        val pictureX = (referenceAnchor.first - anchor.first) * ratio
        val pictureY = (referenceAnchor.second - anchor.second) * ratio
        val bitmapRect = RectF(canvasLeft + pictureX, canvasTop + pictureY,
            canvasLeft + resolution * ratio + pictureX,
            canvasTop + resolution * ratio + pictureY)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        image?.let { canvas.drawBitmap(it, null, bitmapRect, paint) }
        if (showGuides) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2f)
            paint.color = resources.getColor(R.color.cp_preview_guide, context.theme)
            paint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(5f), dp(3f)), 0f)
            canvas.drawRect(canvasLeft, canvasTop, canvasLeft + side, canvasTop + side, paint)
            paint.pathEffect = null
            paint.color = resources.getColor(R.color.cp_preview_bounds, context.theme)
            canvas.drawRect(canvasLeft + bounds.left * ratio + pictureX,
                canvasTop + bounds.top * ratio + pictureY,
                canvasLeft + bounds.right * ratio + pictureX,
                canvasTop + bounds.bottom * ratio + pictureY, paint)
            val ax = canvasLeft + referenceAnchor.first * ratio
            val ay = canvasTop + referenceAnchor.second * ratio
            paint.color = resources.getColor(R.color.cp_preview_anchor, context.theme)
            canvas.drawCircle(ax, ay, dp(3f), paint)
            canvas.drawLine(ax - dp(9f), ay, ax + dp(9f), ay, paint)
            canvas.drawLine(ax, ay - dp(9f), ax, ay + dp(9f), paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = resources.getColor(R.color.cp_preview_hud_text, context.theme)
        paint.textSize = 9f * scaled
        listOf("画布：灰色虚线", "内容：蓝色边框", "锚点：红色十字",
            "锚点：(${anchor.first}, ${anchor.second})", "位移：($dx, $dy)")
            .forEachIndexed { i, label -> canvas.drawText(label, dp(8f), dp(13f + i * 13f), paint) }

        paint.color = resources.getColor(R.color.cp_preview_hud_bg, context.theme)
        canvas.drawRect(0f, artHeight, width.toFloat(), height.toFloat(), paint)
        var x = dp(12f)
        var line = 0
        paint.textSize = 10f * scaled
        val maxX = (width - dp(110f)).coerceAtLeast(dp(110f))
        state.chain.forEachIndexed { index, name ->
            val label = if (index == 0) name else " → $name"
            val w = paint.measureText(label)
            if (x > dp(12f) && x + w + dp(6f) > maxX) { ++line; x = dp(12f) }
            val terminal = name == "◎" || name == "∞" ||
                (state.chain.lastOrNull() == "◎" && index == state.chain.lastIndex - 1)
            val baseline = artHeight + dp(16f + line * 18f)
            if (!terminal && name == state.current) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(1.5f)
                paint.color = resources.getColor(R.color.cp_preview_bounds, context.theme)
                canvas.drawRoundRect(x - dp(2f), baseline - dp(11f), x + w + dp(2f),
                    baseline + dp(3f), dp(3f), dp(3f), paint)
                paint.style = Paint.Style.FILL
            }
            paint.color = resources.getColor(R.color.cp_preview_hud_text, context.theme)
            paint.textSize = 10f * scaled
            canvas.drawText(label, x, baseline, paint)
            x += w + dp(6f)
        }
        paint.textSize = 8f * scaled
        paint.color = when (state.status) {
            "播放中" -> Color.rgb(38, 150, 89)
            "已结束" -> Color.rgb(208, 62, 62)
            else -> resources.getColor(R.color.cp_preview_hud_text, context.theme)
        }
        canvas.drawText("● ${state.status}", width - dp(107f), artHeight + dp(16f), paint)
        paint.color = resources.getColor(R.color.cp_preview_hud_text, context.theme)
        paint.textSize = 21f * scaled
        canvas.drawText("↺", width - dp(34f), artHeight + dp(21f), paint)
        paint.textSize = 8f * scaled
        val infoY = artHeight + dp(16f + (chainLines(width) - 1) * 18f + 16f)
        val loopText = when (state.loopKind) {
            "timed" -> "定时循环" + state.duration
            "infinite" -> "无限循环"
            else -> "播放一次"
        }
        val rightWidth = paint.measureText(loopText)
        val leftWidth = (width - rightWidth - dp(34f)).coerceAtLeast(dp(20f))
        val leftText = "${state.current} · 第${state.frame + 1}/${state.frameCount}帧"
        val clippedLeft = android.text.TextUtils.ellipsize(leftText,
            android.text.TextPaint(paint), leftWidth,
            android.text.TextUtils.TruncateAt.END).toString()
        canvas.drawText(clippedLeft, dp(12f), infoY, paint)
        canvas.drawText(loopText, width - dp(12f) - rightWidth, infoY, paint)

        val x0 = dp(12f)
        val x1 = width - dp(12f)
        val guideColor = resources.getColor(R.color.cp_preview_guide, context.theme)
        val fillColor = resources.getColor(R.color.cp_preview_bounds, context.theme)
        // Main bar: timed progress, or an indeterminate stage until the first cycle ends.
        val mainY = infoY + dp(7f)
        paint.color = guideColor
        canvas.drawRoundRect(x0, mainY, x1, mainY + dp(5f), dp(2f), dp(2f), paint)
        paint.color = fillColor
        if (state.primaryProgress < 0f) {
            val bandLeft = x0 + (x1 - x0) * 0.34f
            canvas.drawRoundRect(bandLeft, mainY, bandLeft + (x1 - x0) * 0.24f,
                mainY + dp(5f), dp(2f), dp(2f), paint)
        } else {
            canvas.drawRoundRect(x0, mainY,
                x0 + (x1 - x0) * state.primaryProgress.coerceIn(0f, 1f),
                mainY + dp(5f), dp(2f), dp(2f), paint)
        }
        // Secondary bar always represents the currently playing frame sequence.
        val frameY = mainY + dp(8f)
        paint.color = guideColor
        canvas.drawRoundRect(x0, frameY, x1, frameY + dp(3f), dp(2f), dp(2f), paint)
        val fraction = ((state.frame + state.frameFraction.coerceIn(0f, 1f)) /
            state.frameCount.coerceAtLeast(1)).coerceIn(0f, 1f)
        paint.color = Color.rgb(195, 155, 83)
        canvas.drawRoundRect(x0, frameY, x0 + (x1 - x0) * fraction,
            frameY + dp(3f), dp(2f), dp(2f), paint)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val cy = width / 2f + dp(14f)
        val hit = event.x >= width - dp(48f) &&
            event.y >= cy - dp(24f) && event.y <= cy + dp(24f)
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> hit
            MotionEvent.ACTION_UP -> if (hit) { performClick(); true } else false
            else -> hit
        }
    }
    override fun performClick(): Boolean {
        super.performClick()
        performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        onReplay?.invoke()
        return true
    }
}
