package com.cx.cakepet

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/** 与正式桌宠独立的无障碍全屏透传对照实验。 */
class CakePetAccessibilityService : AccessibilityService() {
    companion object {
        const val MODE_PASS_THROUGH = 0
        const val MODE_FULL_TOUCH = 1
        const val MODE_SPLIT = 2
        var active: CakePetAccessibilityService? = null
            private set
    }

    private var stage: AccessibilityHitTestView? = null
    private val debugTag = "CakePetHitTest"
    private var control: View? = null
    private var controlParams: WindowManager.LayoutParams? = null
    private val wm: WindowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }

    override fun onServiceConnected() {
        super.onServiceConnected()
        active = this
    }

    /** Production display windows are not part of the A/B/C experimental test overlay. */
    fun attachPetDisplay(view: View, params: WindowManager.LayoutParams): Boolean = try {
        wm.addView(view, params)
        true
    } catch (e: Exception) {
        Log.w(debugTag, "Cannot add accessibility pet display", e)
        false
    }

    fun updatePetDisplay(view: View, params: WindowManager.LayoutParams) {
        try { wm.updateViewLayout(view, params) } catch (_: Exception) {}
    }

    fun detachPetDisplay(view: View) {
        try { wm.removeView(view) } catch (_: Exception) {}
    }

    fun showHitTest(mode: Int = MODE_SPLIT): Boolean {
        hideHitTest()
        return try {
            val view = AccessibilityHitTestView(this)
            view.fullScreenTouch = mode == MODE_FULL_TOUCH
            view.onClose = { hideHitTest() }
            val stageParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    (if (mode == MODE_FULL_TOUCH) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START }
            wm.addView(view, stageParams)
            stage = view
            if (mode == MODE_SPLIT) {
                // 复用历史方案：全屏显示层完全透传，单个角色矩形负责触摸。
                // 控制窗自身透明像素仍会拦截，这是本次需要观察的边界。
                val size = view.petSize.toInt()
                val dm = resources.displayMetrics
                view.initialize(dm.widthPixels, dm.heightPixels)
                val lp = WindowManager.LayoutParams(
                    size, size, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = view.petX.toInt()
                    y = view.petY.toInt()
                }
                val touch = object : View(this) {
                    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.MAGENTA
                        style = Paint.Style.STROKE
                        strokeWidth = 4f
                    }
                    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.MAGENTA
                        textSize = 20f
                    }
                    override fun onDraw(canvas: Canvas) {
                        super.onDraw(canvas)
                        canvas.drawRect(2f, 2f, width - 2f, height - 2f, borderPaint)
                        canvas.drawText("CONTROL WINDOW", 8f, height - 12f, labelPaint)
                    }
                    private var held = false
                    private var downX = 0f
                    private var downY = 0f
                    private var originX = 0f
                    private var originY = 0f
                    override fun onTouchEvent(event: MotionEvent): Boolean {
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                held = view.pixelHit(event.x, event.y)
                                val alpha = view.pixelAlpha(event.x, event.y)
                                Log.i(debugTag, "C DOWN local=(${event.x},${event.y}) raw=(${event.rawX},${event.rawY}) alpha=$alpha hit=$held window=(${lp.x},${lp.y}) pet=(${view.petX},${view.petY})")
                                downX = event.rawX
                                downY = event.rawY
                                originX = view.petX
                                originY = view.petY
                                return held
                            }
                            MotionEvent.ACTION_MOVE -> {
                                if (!held) return false
                                Log.d(debugTag, "C MOVE raw=(${event.rawX},${event.rawY})")
                                view.moveTo(originX + event.rawX - downX, originY + event.rawY - downY)
                                lp.x = view.petX.toInt()
                                lp.y = view.petY.toInt()
                                wm.updateViewLayout(this, lp)
                                return true
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                val handled = held
                                Log.i(debugTag, "C END action=${event.actionMasked} handled=$handled")
                                held = false
                                return handled
                            }
                        }
                        return false
                    }
                }
                wm.addView(touch, lp)
                Log.i(debugTag, "C control created at (${lp.x},${lp.y}) size=$size")
                control = touch
                controlParams = lp
            }
            true
        } catch (_: Exception) {
            hideHitTest()
            false
        }
    }

    fun hideHitTest() {
        control?.let {
            try { wm.removeView(it) } catch (_: Exception) {}
        }
        control = null
        controlParams = null
        stage?.let {
            try { wm.removeView(it) } catch (_: Exception) {}
            it.release()
        }
        stage = null
    }

    override fun onDestroy() {
        hideHitTest()
        if (active === this) {
            active = null
            PetService.instance?.onAccessibilityUnavailable()
        }
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            if (!pkg.isNullOrEmpty()) PetService.instance?.onForegroundAppChanged(pkg)
        }
    }

    override fun onInterrupt() {}
}
