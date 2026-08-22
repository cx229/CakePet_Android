package com.example.cakepet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * 宠物浮窗视图：绘制当前帧、处理触摸拖动、长按弹出菜单。
 * 对应 PC 端 FollowAndDragWidget（拖拽 + 触摸交互）。
 */
class PetView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    val physics = PetPhysics()
    private val imageManager = ImageModeManager(context)
    private var currentBitmap: Bitmap? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // 触摸拖动状态
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var dragging = false
    private var movedDuringPress = false

    // 抛掷速度计算
    private var lastMoveX = 0f
    private var lastMoveY = 0f
    private var lastMoveTime = 0L

    // 回调
    var onLongPress: (() -> Unit)? = null
    var onDoubleTap: (() -> Unit)? = null
    var onPositionChanged: ((x: Float, y: Float) -> Unit)? = null
    var onDragStateChanged: ((dragging: Boolean) -> Unit)? = null

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
            if (!dragging) onLongPress?.invoke()
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            onDoubleTap?.invoke()
            return true
        }
    })

    init {
        currentBitmap = imageManager.currentBitmap()
        isClickable = true
        isFocusable = true
    }

    fun setConfig(scale: Float, gravity: Float, rebound: Float,
                  top: Boolean, bottom: Boolean, left: Boolean, right: Boolean) {
        physics.gravity = gravity
        physics.reboundRatio = rebound
        physics.gravityTop = top
        physics.gravityBottom = bottom
        physics.gravityLeft = left
        physics.gravityRight = right
    }

    fun setPetScale(scale: Float) {
        scaleFactor = scale
        requestLayout()
    }

    fun getScale(): Float = scaleFactor

    private var scaleFactor = 1.0f
    private val baseSize = 160
    private var frameAccumulator = 0f
    private val FRAME_INTERVAL = 0.06f   // 动画帧间隔（对应 PC frame_interval=0.06）

    /** 由物理循环每帧调用：推进物理 + 取下一帧 */
    fun tick(dt: Float) {
        physics.step(dt)
        // 图像帧按固定间隔推进（与物理解耦，对应 PC 的 frame_interval）
        frameAccumulator += dt
        if (frameAccumulator >= FRAME_INTERVAL) {
            frameAccumulator = 0f
            val bmp = imageManager.nextFrame()
            if (bmp != null) currentBitmap = bmp
        }
        // 物理位置 -> 视图位置由 Service 同步
    }

    fun getBitmapSize(): Pair<Int, Int> {
        return (baseSize * scaleFactor).toInt() to (baseSize * scaleFactor).toInt()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val (w, h) = getBitmapSize()
        setMeasuredDimension(
            resolveSize(w.coerceAtLeast(1), widthMeasureSpec),
            resolveSize(h.coerceAtLeast(1), heightMeasureSpec)
        )
    }

    fun playOnce(name: String) {
        imageManager.playOnce(name)
    }

    fun setMode(name: String) {
        imageManager.setMode(name)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = currentBitmap ?: return
        val dw = (bmp.width * scaleFactor).toInt()
        val dh = (bmp.height * scaleFactor).toInt()
        if (dw <= 0 || dh <= 0) return
        // 居中绘制
        val left = (width - dw) / 2f
        val top = (height - dh) / 2f
        canvas.drawBitmap(bmp, null,
            android.graphics.RectF(left, top, left + dw, top + dh), paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                dragging = false
                movedDuringPress = false
                lastTouchX = event.rawX
                lastTouchY = event.rawY
                lastMoveX = event.rawX
                lastMoveY = event.rawY
                lastMoveTime = SystemClock.uptimeMillis()
                physics.isDragging = true
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - lastTouchX
                val dy = event.rawY - lastTouchY
                if (abs(dx) > 2 || abs(dy) > 2) movedDuringPress = true
                // 速度采样（用于抛掷），只要移动就更新
                val now = SystemClock.uptimeMillis()
                val dt = (now - lastMoveTime) / 1000f
                if (dt > 0 && movedDuringPress) {
                    physics.vx = (event.rawX - lastMoveX) / dt
                    physics.vy = (event.rawY - lastMoveY) / dt
                }
                lastMoveX = event.rawX
                lastMoveY = event.rawY
                lastMoveTime = now
                if (movedDuringPress) {
                    dragging = true
                    physics.isDragging = true
                    onPositionChanged?.invoke(event.rawX - width / 2f, event.rawY - height / 2f)
                }
                lastTouchX = event.rawX
                lastTouchY = event.rawY
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                physics.isDragging = false
                if (dragging) {
                    onDragStateChanged?.invoke(false)
                    // 抛掷：保持当前速度（已被 onPositionChanged 采样更新）
                    dragging = false
                } else {
                    // 点击未拖动，忽略（单击不做特殊事）
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        imageManager.clearCache()
    }
}
