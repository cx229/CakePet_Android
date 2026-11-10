package com.cx.cakepet

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
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
    private val assets: ImageModeManager,
    private val imageView: ImageView,
    density: Float
) {
    private val mgr = PetAnimationController(assets)
    private val boxPx = (64f * density).toInt().coerceAtLeast(1)
    // Same decoded frame + its declared anchor always produce the same preview.
    // Never key by resName alone: one resource may use different alignment/transform.
    private val composedCache = mutableMapOf<Pair<Bitmap, Pair<Int, Int>>, Bitmap?>()
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var previewScene = ""
    private var lastTick = 0L
    private val FRAME_MS = 33L   // 约 30fps 推进状态机，足够平滑且省电

    fun start() {
        if (running && previewScene == "normal") return
        stop()
        previewScene = "normal"
        running = true
        // Ordinary home previews retain their existing independent static-pool behavior.
        mgr.setActiveStateFunction(null)
        mgr.cancelForced()
        mgr.useStaticPool = true
        (mgr.modeOf("standby", "static_pool") ?: mgr.modeOf("standby", "pool"))
            ?.let { mgr.setMode(it) }
        lastTick = System.currentTimeMillis()
        renderCurrent()
        postTick()
    }

    /** Summoned cards use their own optional function pool, not a frozen placeholder.
     * Normal Mode frames, continuation links and pool reselection are unchanged.
     */
    fun startSummonedPreview() {
        if (running && previewScene == "summoned") return
        val path = MaterialFunctionSupport.SUMMONED
        val startMode = mgr.modeOf(*path.toTypedArray())
        if (startMode == null) {
            showSummonedPlaceholder()
            return
        }
        stop()
        previewScene = "summoned"
        mgr.useStaticPool = true
        mgr.setActiveStateFunction(path)
        mgr.cancelForced()
        mgr.setMode(startMode)
        running = true
        lastTick = System.currentTimeMillis()
        renderCurrent()
        imageView.contentDescription = "已召唤 · 展示动画"
        postTick()
    }

    fun stop() {
        running = false
        previewScene = ""
        handler.removeCallbacksAndMessages(null)
    }

    /** Keep a stable frame without scheduling periodic animation ticks. */
    fun showStatic() {
        stop()
        mgr.setActiveStateFunction(null)
        mgr.cancelForced()
        mgr.useStaticPool = true
        (mgr.modeOf("standby", "static_pool") ?: mgr.modeOf("standby", "pool"))
            ?.let { mgr.setMode(it) }
        renderCurrent()
    }

    /** Missing optional summoned preview: do not display a fake sprite. */
    fun showSummonedPlaceholder() {
        stop()
        mgr.setActiveStateFunction(null)
        previewScene = "placeholder"
        imageView.setImageDrawable(null)
        imageView.contentDescription = "已召唤 · 未配置展示动画"
    }

    /**
     * 触发“摸摸头”一次性动作（PAT_HEAD → SHAKE_HEAD → SIT_PUFFED → 回待机池），
     * 与悬浮宠物 playOnce(PAT_HEAD) 同源同链。playOnce 无 forcedSeq 守卫会覆盖重播；
     * 置 modeChangedFlag=true，现有 tick() 下一帧即接管渲染，pat_head-* 帧由 composedCache 复用。
     */
    fun triggerPat() {
        mgr.modeOf("pat_head", "pat_head")?.let { mgr.playOnce(it) }
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
        // Use the current clip's actual padded bitmap and frame anchor, including
        // its verticalAlign/transform. Never crop each frame to alpha bounds:
        // doing so re-centers changing silhouettes and makes patting jitter.
        // Full-frame preview intentionally never consumes animation displacement.
        val frame = mgr.currentBitmap() ?: return
        val anchor = mgr.currentAnchor()
        val key = frame to anchor
        val bmp = composedCache.getOrPut(key) {
            composePreviewBitmap(frame, boxPx, anchor)
        }
        if (bmp != null) imageView.setImageBitmap(bmp)
    }
}

/**
 * Draw the *entire* already padded frame, rather than cropping its alpha bounds.
 * The actual frame anchor (not a per-frame silhouette center) stays at the fixed
 * preview position. Frame dx/dy are intentionally ignored: previews only change
 * the image, never the preview pet's physical position.
 */
private fun composePreviewBitmap(frame: Bitmap, boxPx: Int, anchor: Pair<Int, Int>): Bitmap? {
    return try {
        // Engine anchor coordinates are logical 128-space; frame.width can be
        // a higher-resolution decoded canvas (e.g. 256) without changing scale.
        val scale = 1.4f
        val logicalToPreview = 128f / frame.width.coerceAtLeast(1) * scale
        val width = frame.width * logicalToPreview
        val height = frame.height * logicalToPreview
        val left = boxPx / 2f - anchor.first * scale
        val top = boxPx.toFloat() - anchor.second * scale
        val out = Bitmap.createBitmap(boxPx, boxPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(frame, null, RectF(left, top, left + width, top + height), paint)
        out
    } catch (_: Exception) { null }
}
