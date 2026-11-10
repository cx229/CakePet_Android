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
    /** Page/dialog decides which existing detail popup to open. */
    var onModeClick: ((String) -> Unit)? = null
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
    private data class ChainNode(
        val id: String, val text: String, val prefix: String,
        val prefixX: Float, val nameX: Float, val baseline: Float,
        val nameWidth: Float, val interactive: Boolean
    )

    /** One layout for measurement, drawing and hit testing; separators never enter the node border. */
    private fun chainNodes(widthPx: Int): List<ChainNode> {
        paint.textSize = 10f * scaled
        val left = dp(12f)
        val limit = (widthPx - dp(54f)).coerceAtLeast(left + dp(40f))
        val space = dp(6f)
        val result = mutableListOf<ChainNode>()
        var x = left
        var row = 0
        state.chain.forEachIndexed { index, name ->
            val terminal = name == "◎" || name == "∞" ||
                (state.chain.lastOrNull() == "◎" && index == state.chain.lastIndex - 1)
            val displayed = android.text.TextUtils.ellipsize(name,
                android.text.TextPaint(paint), (limit - left - dp(18f)).coerceAtLeast(dp(12f)),
                android.text.TextUtils.TruncateAt.END).toString()
            val prefix = if (index == 0) "" else " > "
            val prefixWidth = paint.measureText(prefix)
            val nameWidth = paint.measureText(displayed)
            if (x > left && x + prefixWidth + nameWidth > limit) {
                x = left
                row++
            }
            val nameX = x + prefixWidth
            val baseline = widthPx / 2f + dp(16f + 18f * row)
            result += ChainNode(name, displayed, prefix, x, nameX,
                baseline, nameWidth, !terminal)
            x = nameX + nameWidth + space
        }
        return result
    }

    private fun chainLines(widthPx: Int): Int {
        val nodes = chainNodes(widthPx)
        return if (nodes.isEmpty()) 1 else
            ((nodes.last().baseline - widthPx / 2f - dp(16f)) / dp(18f)).toInt() + 1
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
        // Canvas outline belongs behind the art, with a heavier dashed stroke
        // than the fine content boundary. Both still follow their own motion.
        if (showGuides) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(3f)
            paint.color = resources.getColor(R.color.cp_preview_guide, context.theme)
            paint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(dp(6f), dp(4f)), 0f)
            canvas.drawRect(canvasLeft, canvasTop, canvasLeft + side, canvasTop + side, paint)
            paint.pathEffect = null
        }
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        image?.let { canvas.drawBitmap(it, null, bitmapRect, paint) }
        if (showGuides) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1.5f)
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
        paint.textSize = 10f * scaled
        chainNodes(width).forEach { node ->
            if (node.interactive && node.id == state.current) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = dp(1.5f)
                paint.color = resources.getColor(R.color.cp_preview_bounds, context.theme)
                canvas.drawRoundRect(node.nameX - dp(2f), node.baseline - dp(11f),
                    node.nameX + node.nameWidth + dp(2f), node.baseline + dp(3f),
                    dp(3f), dp(3f), paint)
                paint.style = Paint.Style.FILL
            }
            paint.textSize = 10f * scaled
            paint.color = resources.getColor(R.color.cp_preview_hud_text, context.theme)
            if (node.prefix.isNotEmpty())
                canvas.drawText(node.prefix, node.prefixX, node.baseline, paint)
            canvas.drawText(node.text, node.nameX, node.baseline, paint)
        }
        paint.color = resources.getColor(R.color.cp_preview_hud_text, context.theme)
        paint.textSize = 21f * scaled
        canvas.drawText("↺", width - dp(34f), artHeight + dp(21f), paint)

        // One compact information line above the two progress strips.
        paint.textSize = 8f * scaled
        val infoY = artHeight + dp(16f + (chainLines(width) - 1) * 18f + 16f)
        val loopText = when (state.loopKind) {
            "timed" -> "定时循环" + state.duration
            "infinite" -> "无限循环"
            else -> "播放一次"
        }
        val leftText = "${state.current} · 第${state.frame + 1}/${state.frameCount}帧 · $loopText"
        val statusText = "● ${state.status}"
        val statusWidth = paint.measureText(statusText)
        val leftWidth = (width - statusWidth - dp(34f)).coerceAtLeast(dp(20f))
        val clippedLeft = android.text.TextUtils.ellipsize(leftText,
            android.text.TextPaint(paint), leftWidth,
            android.text.TextUtils.TruncateAt.END).toString()
        paint.color = resources.getColor(R.color.cp_preview_hud_text, context.theme)
        canvas.drawText(clippedLeft, dp(12f), infoY, paint)
        paint.color = when (state.status) {
            "播放中" -> Color.rgb(38, 150, 89)
            "已结束" -> Color.rgb(208, 62, 62)
            else -> resources.getColor(R.color.cp_preview_hud_text, context.theme)
        }
        canvas.drawText(statusText, width - dp(12f) - statusWidth, infoY, paint)

        val x0 = dp(12f)
        val x1 = width - dp(12f)
        val guideColor = resources.getColor(R.color.cp_preview_guide, context.theme)
        val fillColor = resources.getColor(R.color.cp_preview_bounds, context.theme)
        // Main bar: timed progress, or an indeterminate stage until the first cycle ends.
        val mainY = infoY + dp(7f)
        paint.color = guideColor
        canvas.drawRoundRect(x0, mainY, x1, mainY + dp(5f), dp(2f), dp(2f), paint)
        paint.color = fillColor
        // Single/infinite first cycle: a full-width bar filled halfway (stage, not time).
        val progress = if (state.primaryProgress < 0f) 0.5f
            else state.primaryProgress.coerceIn(0f, 1f)
        canvas.drawRoundRect(x0, mainY, x0 + (x1 - x0) * progress,
            mainY + dp(5f), dp(2f), dp(2f), paint)
        // Secondary bar always represents the currently playing frame sequence.
        val frameY = mainY + dp(8f)
        paint.color = guideColor
        canvas.drawRoundRect(x0, frameY, x1, frameY + dp(3f), dp(2f), dp(2f), paint)
        val fraction = ((state.frame + 1).toFloat() /
            state.frameCount.coerceAtLeast(1)).coerceIn(0f, 1f)
        paint.color = Color.rgb(195, 155, 83)
        canvas.drawRoundRect(x0, frameY, x0 + (x1 - x0) * fraction,
            frameY + dp(3f), dp(2f), dp(2f), paint)
    }
    private var pressedMode: String? = null
    private var pressedReplay = false

    private fun replayHit(x: Float, y: Float): Boolean {
        val cy = width / 2f + dp(14f)
        return x >= width - dp(48f) && y >= cy - dp(24f) && y <= cy + dp(24f)
    }

    private fun modeAt(x: Float, y: Float): String? {
        return chainNodes(width).firstOrNull { node ->
            node.interactive && x >= node.nameX - dp(3f) &&
                x <= node.nameX + node.nameWidth + dp(3f) &&
                y >= node.baseline - dp(13f) && y <= node.baseline + dp(6f)
        }?.id
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedReplay = replayHit(event.x, event.y)
                pressedMode = if (pressedReplay) null else modeAt(event.x, event.y)
                return pressedReplay || pressedMode != null
            }
            MotionEvent.ACTION_UP -> {
                val replay = pressedReplay && replayHit(event.x, event.y)
                val mode = pressedMode?.takeIf { it == modeAt(event.x, event.y) }
                pressedReplay = false
                pressedMode = null
                if (replay) { performClick(); return true }
                if (mode != null) {
                    performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    onModeClick?.invoke(mode)
                    return true
                }
                return false
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedReplay = false
                pressedMode = null
                return true
            }
            MotionEvent.ACTION_MOVE -> return pressedReplay || pressedMode != null
        }
        return false
    }
    override fun performClick(): Boolean {
        super.performClick()
        performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        onReplay?.invoke()
        return true
    }
}
