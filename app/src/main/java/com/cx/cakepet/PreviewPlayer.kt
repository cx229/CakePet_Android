package com.cx.cakepet

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.widget.ImageView

/**
 * 通用动态预览播放器：直接驱动 ImageModeManager 自带的“无重力静止池”状态机，
 * 完全复用正式配置——动作池（staticPool）、timeNext 的 min/max/prob、loop、
 * nextModeName 链式跳转、以及每一帧的图片顺序与时延，均由引擎按真实 dt 推进 nextFrame 决定。
 * 相当于预览里的宠物以“无重力”方式自治播放：静坐若干秒后按配置概率切入随机静止动作，
 * 摇头→炸毛、美白/趴一次性播完回池等切换顺序与正式一致。
 * 锚点（脚）固定：composePreviewBitmap 把所有帧统一画到预览框底部居中，动作切换不漂移。
 * 同样 resName 的帧只合成一次（composedCache）。
 * 首页与素材管理页共用同一实现（不再依赖 MainActivity 内部状态）。
 */
class PreviewPlayer(
    private val mgr: ImageModeManager,
    private val imageView: ImageView,
    density: Float
) {
    private val boxPx = (64f * density).toInt().coerceAtLeast(1)
    private val composedCache = mutableMapOf<String, Bitmap?>()
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var lastTick = 0L
    private val FRAME_MS = 33L   // 约 30fps 推进状态机，足够平滑且省电

    fun start() {
        if (running) return
        running = true
        // 进入“无重力静止池”，从静坐开始（正式配置的自然起点，等同“静坐一遍”的起点）。
        mgr.useStaticPool = true
        mgr.setMode(ImageModeManager.SIT_CLAM)
        lastTick = System.currentTimeMillis()
        renderCurrent()     // 立即渲染起始帧
        postTick()
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
    }

    /**
     * 触发“摸摸头”一次性动作（PAT_HEAD → SHAKE_HEAD → SIT_PUFFED → 回待机池），
     * 与悬浮宠物 playOnce(PAT_HEAD) 同源同链。playOnce 无 forcedSeq 守卫会覆盖重播；
     * 置 modeChangedFlag=true，现有 tick() 下一帧即接管渲染，pat_head-* 帧由 composedCache 复用。
     */
    fun triggerPat() {
        mgr.playOnce(ImageModeManager.PAT_HEAD)
    }

    private fun postTick() {
        handler.postDelayed({ tick() }, FRAME_MS)
    }

    private fun tick() {
        if (!running) return
        val now = System.currentTimeMillis()
        // 真实 dt 推进正式状态机：动作内帧循环、timeNext 定时与随机切换、一次性动作回池
        // 全部按 modes.json/内建配置执行；changed 为 true 表示帧或动作发生切换，需要重绘。
        val dt = (now - lastTick).coerceAtLeast(0L)
        lastTick = now
        val changed = mgr.nextFrame(dt).changed
        if (changed) renderCurrent()
        postTick()
    }

    private fun renderCurrent() {
        val res = mgr.currentResName() ?: return
        val bmp = compose(res)
        if (bmp != null) imageView.setImageBitmap(bmp)
    }

    private fun compose(resName: String): Bitmap? {
        composedCache[resName]?.let { return it }
        val frame = mgr.previewFrame(resName) ?: return null
        val out = composePreviewBitmap(frame, boxPx)
        composedCache[resName] = out
        return out
    }
}

/**
 * 合成单帧预览位图：复用引擎“复杂变换”（由调用方先经 [ImageModeManager.previewFrame] 取得统一画布位图），
 * 使不同资源图（芝麻酥 1000px 高清 / 芝麻球促销 128px 原生）在预览框内尺寸一致、锚点正确。
 *
 * 关键：与实时浮动宠物统一到【128 逻辑坐标系】——实时宠物 1.0× 窗口即 128 屏幕像素（见 PetView
 * getBaseBitmapSize = baseFrameW*scaleFactor，baseFrameW=统一画布逻辑尺寸 128），内容在 128 逻辑下
 * 的占比即实时屏幕占比。预览直接把内容按【1.4×】绘制到【预览框像素尺寸 boxPx=64dp@density】的位图里、
 * 锚点（内容底部中心）对齐底部居中；因位图尺寸=框尺寸，ImageView(fitCenter) 不再二次缩放，
 * 故倍率恰为【浮动宠物的 1.4×】、密度无关（同时消除之前输出 128px 再经 fitCenter 放大导致的倍率飘移）。
 */
private fun composePreviewBitmap(frame: Bitmap, boxPx: Int): Bitmap? {
    return try {
        // 统一回 128 逻辑（256 位图→factor 0.5、128 位图→1.0）；基准 = 实时 1.0× 窗口(128px) 的内容占比。
        val factor = 128f / frame.width.coerceAtLeast(1)
        val cb = petContentBounds(frame)
        val lw = cb.width() * factor
        val lh = cb.height() * factor
        val scale = 1.4f                              // 用户期望：预览为浮动宠物的 1.4 倍（只施加一次）
        val dw = lw * scale
        val dh = lh * scale
        val dx = (boxPx - dw) / 2f                    // 水平居中
        val dy = boxPx - dh                           // 内容底部（脚）贴框底部中心
        val out = Bitmap.createBitmap(boxPx, boxPx, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        c.drawBitmap(
            frame,
            Rect(cb.left.toInt(), cb.top.toInt(), cb.right.toInt(), cb.bottom.toInt()),
            RectF(dx, dy, dx + dw, dy + dh),
            p
        )
        out
    } catch (_: Exception) { null }
}

/** 求位图非透明内容包围盒（宠物实际像素范围），用于把“宠物锚点”而非整张画布对齐到预览窗口。 */
private fun petContentBounds(bmp: Bitmap): RectF {
    val w = bmp.width
    val h = bmp.height
    if (w == 0 || h == 0) return RectF(0f, 0f, w.toFloat(), h.toFloat())
    var minX = w; var minY = h; var maxX = -1; var maxY = -1
    val row = IntArray(w)
    for (y in 0 until h) {
        bmp.getPixels(row, 0, w, 0, y, w, 1)
        for (x in 0 until w) {
            if ((row[x] ushr 24) > 12) {   // alpha > ~5%，忽略半透明抗锯齿边
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
    }
    if (maxX < 0) return RectF(0f, 0f, w.toFloat(), h.toFloat())
    return RectF(minX.toFloat(), minY.toFloat(), (maxX + 1).toFloat(), (maxY + 1).toFloat())
}
