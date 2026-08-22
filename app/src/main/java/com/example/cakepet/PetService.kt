package com.example.cakepet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * 宠物浮窗服务：ForegroundService + WindowManager + Choreographer 物理循环。
 * 对应 PC 端 pet_sesame_cake.py 的主循环，但安卓用 Choreographer 每帧回调替代 Qt 事件循环。
 */
class PetService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var petView: PetView
    private lateinit var layoutParams: WindowManager.LayoutParams
    private lateinit var config: PetConfig

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var running = false
    private var lastFrameTime = 0L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val FRAME_DELAY = 33L   // ~30fps，省电（对应 PC 30fps 上限）

    // 屏幕尺寸
    private var screenW = 0
    private var screenH = 0

    private val frameRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.uptimeMillis()
            var dt = (now - lastFrameTime) / 1000f
            if (dt <= 0) dt = 1f / 30f
            if (dt > 0.1f) dt = 0.1f   // 限制大跳变（如息屏恢复）
            lastFrameTime = now

            // 物理推进
            petView.tick(dt)
            // 同步位置到 WindowManager
            val (w, h) = petView.getBitmapSize()
            val nx = petView.physics.x.coerceIn(0f, (screenW - w).coerceAtLeast(0).toFloat())
            val ny = petView.physics.y.coerceIn(0f, (screenH - h).coerceAtLeast(0).toFloat())
            petView.physics.x = nx
            petView.physics.y = ny
            layoutParams.x = nx.toInt()
            layoutParams.y = ny.toInt()
            try {
                windowManager.updateViewLayout(petView, layoutParams)
            } catch (_: Exception) {
            }
            petView.invalidate()

            mainHandler.postDelayed(this, FRAME_DELAY)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> pause()
                Intent.ACTION_SCREEN_ON -> resume()
                Intent.ACTION_CONFIGURATION_CHANGED -> recalcBounds()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        config = PetConfig(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        initPetView()
        loadConfig()
        registerConfigObserver()
        registerScreenReceiver()

        // 初始位置：底部中间
        val dm = resources.displayMetrics
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        petView.physics.x = (screenW / 2f)
        petView.physics.y = (screenH - 300f)
        recalcBounds()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_CONFIGURATION_CHANGED)
        }
        registerReceiver(screenReceiver, filter)

        startLoop()
    }

    private fun initPetView() {
        petView = PetView(this)
        layoutParams = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            gravity = Gravity.TOP or Gravity.START
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
        }
        petView.onLongPress = { showMenu() }
        petView.onDoubleTap = { petView.playOnce(ImageModeManager.PAT_HEAD) }
        petView.onPositionChanged = { x, y ->
            layoutParams.x = x.toInt()
            layoutParams.y = y.toInt()
            petView.physics.x = x
            petView.physics.y = y
            try { windowManager.updateViewLayout(petView, layoutParams) } catch (_: Exception) {}
        }
        petView.onDragStateChanged = { dragging ->
            if (!dragging) {
                // 释放后根据当前边重力决定模式
                petView.setMode(ImageModeManager.SIT_CLAM)
            }
        }
        windowManager.addView(petView, layoutParams)
    }

    private fun loadConfig() {
        val c = config.getBlocking()
        petView.setConfig(c.scale, c.gravity, c.reboundRatio,
            c.gravityTop, c.gravityBottom, c.gravityLeft, c.gravityRight)
        petView.setPetScale(c.scale)
        recalcBounds()
    }

    private fun registerConfigObserver() {
        config.configFlow.onEach { c ->
            petView.physics.gravity = c.gravity
            petView.physics.reboundRatio = c.reboundRatio
            petView.physics.gravityTop = c.gravityTop
            petView.physics.gravityBottom = c.gravityBottom
            petView.physics.gravityLeft = c.gravityLeft
            petView.physics.gravityRight = c.gravityRight
            petView.setPetScale(c.scale)
            if (!c.enabled) {
                pause()
            } else {
                resume()
            }
        }.launchIn(scope)
    }

    private fun registerScreenReceiver() { /* 已在 onCreate 注册 */ }

    private fun recalcBounds() {
        val dm = resources.displayMetrics
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        val (w, h) = petView.getBitmapSize()
        petView.physics.setBounds(0f, 0f,
            (screenW - w).coerceAtLeast(0).toFloat(),
            (screenH - h).coerceAtLeast(0).toFloat())
    }

    private fun startLoop() {
        if (running) return
        running = true
        lastFrameTime = SystemClock.uptimeMillis()
        mainHandler.postDelayed(frameRunnable, FRAME_DELAY)
    }

    private fun pause() {
        if (!running) return
        running = false
        mainHandler.removeCallbacks(frameRunnable)
    }

    private fun resume() {
        if (running) return
        running = true
        lastFrameTime = SystemClock.uptimeMillis()
        mainHandler.postDelayed(frameRunnable, FRAME_DELAY)
    }

    private fun showMenu() {
        // 长按菜单通过广播/Activity 弹出 BottomSheet，避免浮窗内无法获取 Window Token
        val intent = Intent(this, MenuActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                CHANNEL_ID, "CakePet 浮窗",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CakePet 运行中")
            .setContentText("点击打开设置")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            MenuActivity.ACTION_PAT -> petView.playOnce(ImageModeManager.PAT_HEAD)
            MenuActivity.ACTION_JUMP -> petView.playOnce(ImageModeManager.JUMP_DOWN)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
        mainHandler.removeCallbacks(frameRunnable)
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        try { windowManager.removeView(petView) } catch (_: Exception) {}
        scope.cancel()
    }

    companion object {
        const val CHANNEL_ID = "cakepet_channel"
        const val NOTIFICATION_ID = 1001
    }
}
