package com.cx.cakepet

import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.cx.cakepet.databinding.ActivityTestHitBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 功能测试：像素级点击 + 简单拖拽
 * - 左侧：普通 View（Activity 内），验证 Activity 视图树的像素级命中
 * - 右侧：WindowManager 悬浮 View，验证 TYPE_APPLICATION_OVERLAY 的像素级命中（透明区穿透）
 * 两者都显示 probe_head，点击透明区不消费（穿透），点击小猫头可拖动。
 */
class TestHitActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTestHitBinding
    private lateinit var windowManager: WindowManager
    private var overlayView: PixelHitView? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTestHitBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // ===== 左侧：普通 View（Activity 内）=====
        val normal = PixelHitView(this).apply {
            // 普通 View 拖拽：直接用 translation 移动自身
            onPositionUpdate = { x, y ->
                translationX = x - viewX0
                translationY = y - viewY0
            }
        }
        binding.flNormal.addView(normal,
            android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
        // 记录初始屏幕位置作为 translation 基准（左侧放到 flNormal 容器内，以容器左上为 0）
        normal.post {
            val loc = IntArray(2)
            normal.getLocationOnScreen(loc)
            normal.viewX0 = loc[0].toFloat()
            normal.viewY0 = loc[1].toFloat()
            normal.viewX = normal.viewX0
            normal.viewY = normal.viewY0
        }

        // ===== 底部：左侧被测弹窗按钮（验证穿透是否落到下层）=====
        binding.btnProbeTarget.setOnClickListener {
            android.widget.Toast.makeText(this, "穿透成功：点击落到了下层按钮", android.widget.Toast.LENGTH_SHORT).show()
        }

        // ===== 右侧：WindowManager 悬浮 View =====
        binding.btnAddOverlay.setOnClickListener {
            if (Settings.canDrawOverlays(this)) {
                addOverlay()
            } else {
                startActivity(
                    android.content.Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName")
                    )
                )
            }
        }
        binding.btnRemoveOverlay.setOnClickListener { removeOverlay() }

        // ===== Region 方案切换（验证 setTouchableRegion 在 overlay 上的穿透效果）=====
        binding.btnRegionPixel.setOnClickListener {
            overlayView?.regionMode = PixelHitView.RegionMode.PIXEL
            applyRegion()
            updateRegionState("像素级 Region（真实轮廓）")
        }
        binding.btnRegionGrid.setOnClickListener {
            overlayView?.regionMode = PixelHitView.RegionMode.GRID
            applyRegion()
            updateRegionState("25矩形 Region（5x5网格）")
        }
        binding.btnRegionOff.setOnClickListener {
            overlayView?.regionMode = PixelHitView.RegionMode.OFF
            applyRegion()
            updateRegionState("整窗拦截(关闭，对照)")
        }

        // ===== 二分测试：控制【真实浮窗(宠物)】的命中行为 =====
        binding.btnForceHitTrue.setOnClickListener {
            PetView.FORCE_HIT_TEST = true
            updateForceState("强制全部命中(true)")
        }
        binding.btnForceHitFalse.setOnClickListener {
            PetView.FORCE_HIT_TEST = false
            updateForceState("强制全部穿透(false)")
        }
        binding.btnForceHitReset.setOnClickListener {
            PetView.FORCE_HIT_TEST = null
            updateForceState("恢复像素判定(null)")
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

    }

    private fun updateForceState(text: String) {
        binding.tvForceState.text = "当前：$text"
    }

    private fun updateRegionState(text: String) {
        binding.tvRegionState.text = "当前：$text"
    }

    /** 把当前 RegionMode 对应的可点击区域设给悬浮窗（null = 不设，整窗拦截） */
    private fun applyRegion() {
        val view = overlayView ?: return
        val lp = overlayParams ?: return
        val region = view.buildTouchableRegion()
        setTouchableRegion(lp, region)
        windowManager.updateViewLayout(view, lp)
    }

    /**
     * setTouchableRegion 是 @hide 方法，这里用反射调用（minSdk 29 但公开 SDK 未暴露）。
     * 若反射失败（ROM 不暴露该方法），则静默降级为整窗拦截。
     */
    private fun setTouchableRegion(lp: WindowManager.LayoutParams, region: android.graphics.Region?) {
        try {
            val m = lp.javaClass.getMethod("setTouchableRegion", android.graphics.Region::class.java)
            m.invoke(lp, region)
        } catch (e: Exception) {
            // 部分 ROM 可能隐藏该方法，降级：不设置 Region（整窗拦截）
            try {
                val f = lp.javaClass.getDeclaredField("touchableRegion")
                f.isAccessible = true
                f.set(lp, region)
            } catch (_: Exception) {
                // 彻底不可用，保持整窗拦截
            }
        }
    }

    private fun addOverlay() {
        if (overlayView != null) return
        val view = PixelHitView(this).apply {
            // WM 悬浮 View 拖拽：直接更新 layoutParams.x / y，并同步 Region（屏幕坐标）
            onPositionUpdate = { x, y ->
                overlayParams?.let { lp ->
                    lp.x = x.toInt()
                    lp.y = y.toInt()
                    if (regionMode != PixelHitView.RegionMode.OFF) {
                        val region = buildTouchableRegion()
                        setTouchableRegion(lp, region)
                    }
                    windowManager.updateViewLayout(this, lp)
                }
            }
        }
        val lp = WindowManager.LayoutParams().apply {
            width = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            // 关键：NOT_FOCUSABLE + NOT_TOUCH_MODAL 使窗口内未消费的触摸穿透到下层
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            format = android.graphics.PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.START
            x = (resources.displayMetrics.widthPixels * 0.6f).toInt()
            y = (resources.displayMetrics.heightPixels * 0.3f).toInt()
        }
        view.viewX = lp.x.toFloat()
        view.viewY = lp.y.toFloat()
        view.viewX0 = lp.x.toFloat()
        view.viewY0 = lp.y.toFloat()
        overlayView = view
        overlayParams = lp
        windowManager.addView(view, lp)
    }

    private fun removeOverlay() {
        overlayView?.let { windowManager.removeView(it) }
        overlayView = null
        overlayParams = null
    }

    override fun onDestroy() {
        super.onDestroy()
        removeOverlay()
    }
}
