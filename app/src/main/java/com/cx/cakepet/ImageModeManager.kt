package com.cx.cakepet

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlin.math.roundToInt
import kotlin.random.Random
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 图片资源包：抽象“不同套美术资源”的加载策略。
 * - [resolve]：帧图片相对路径（如 "安静/sit_clam-1.png"）解析为本素材包中的实际路径；
 *   所有帧图片在素材包导入校验时必须齐全，播放不使用替代图片。
 * - [decodeTarget]：物理解码目标边长(px)；0 = 保持原始尺寸（默认包，原生 128）。
 *   新包原图 1000×1000，设为 256 ⇒ 解码期超采样到 256（最长边），作 2x 抗锯齿源；
 *   但【逻辑/锚点/画布】空间仍由 [anchorRes]=128 决定，渲染时把 256 位图按 ratio 缩进 128 逻辑矩形，
 *   既复用现有 128 逻辑（JSON 锚点/边界/scaleFactor 零改动），又比硬压 128 清晰 2x。
 * - [anchorRes]：逻辑分辨率（锚点/画布/UI 坐标系的“1”单位，恒为 128）。
 *   与 decodeTarget 解耦：decodeTarget=256、anchorRes=128 ⇒ 解码超采样 2x、逻辑仍 128。
 *   默认包 decodeTarget=0（原生=128=逻辑），此字段不生效。
 * - 锚点完全由逐帧 anchorX/anchorY 决定（JSON 未给 anchorY 时回退贴底 0），不假设“图底中心”。
 */
data class SpritePack(
    val id: String,
    val base: String = "",                           // 资源根目录（assets/<base> 或 filesDir/roles/<base>）
    val source: RoleSource = RoleSource.ASSETS,      // 存储类型（预装=ASSETS / 用户上传=FILES）
    val resolve: (String) -> String?,
    val configPath: String? = null,   // 本包 modes.jsonc 路径（assets 或私有角色包目录）
    val decodeTarget: Int = 0,         // 物理超采样解码边长（0=原生）；新包 256（由 modes.jsonc 覆盖）
    val anchorRes: Int = 128,          // 逻辑分辨率（锚点/画布/UI 坐标系单位），恒 128（由 modes.jsonc 覆盖）
    val name: String = "",             // 资源包中文名（modes.jsonc 指定，v1.0 必填），仅展示用
    val shortName: String = "",         // 资源包默认简称（modes.jsonc 指定，v1.0 必填）：每个角色各自定义，不全局写死
    // v1.02：所有功能路径均映射到通用 Mode ID；Mode 可用于角色、食物及其他动画。
    // 键为【功能路径】（如 [climb, move]、[snap, pat_head, pat_head]），层数不限；
    // 值为候选列表（当前多为单元素，为将来"同一功能多个动作"预留）。
    val functions: Map<List<String>, List<String>> = emptyMap(),
    // 模式名 → 是否循环（modes 里每项的 loop）。循环类（走/蠕动等）须用 setMode 常驻，
    // 一次性类用 playOnce；由素材包声明，代码不写死哪些动作该常驻。
    val modeLoops: Map<String, Boolean> = emptyMap(),
    val thinking: PackThinking = PackThinking()
)


/**
 * 动画模式管理：每帧时延严格对齐 PC（image_modes.py）。
 * - 每个模式有帧序列 + 与帧一一对应的时延列表（可为固定值或随机区间）。
 * - 帧序列按 next 链循环（loop=true）或在最后一帧结束后切到指定下一模式（一次性动画）。
 * - timeNext 到期且未指定 nextMode 时，只向调用方报告事件，不自行选择其他动作。
 */
class ImageModeManager(
    private val ctx: android.content.Context,
    initialPack: SpritePack
) {

    /**
     * 每个素材包实例持有自己的随机源，不依赖外部对象类型。
     */
    private val rng = Random(initialPack.id.hashCode())

    /** 单帧定义：资源名、下一帧索引（null=序列结束）、时延(ms)、锚点(图片像素坐标，对齐 PC image_meta) */
    private data class Frame(
        val resName: String,
        val delay: () -> Long,          // 该帧停留时长(ms)
        val anchorX: Int = -1,          // 图片像素锚点 X（<0 表示用图宽/2 居中）
        // 图片像素锚点 Y（距【底】边，左下角原点，Y 向上）。
        // 合法范围 [-128, 128]：0=贴底，128=贴顶，负数=脚在图底下方（图外，如 不同姿态动画 的脚实际在地面以下）。
        // null 表示未设置：回退贴底 0（脚贴地）。锚点完全由逐帧 anchorX/anchorY 决定，不假设“图底中心”。
        val anchorY: Int? = null,
        // 帧位移偏移(px)，对齐 PC 的 QPoint(x,0) offset：每帧把该偏移累加到锚点坐标，
        // 实现“拉鱼/扒鱼”这类【帧驱动】位移（区别于引擎主动导航或抛掷的连续速度）。
        // 单循环：一次性动画播完即停，不反弹（对齐 PC PullFishMode 第6~9帧的 offset）。
        val dx: Int = 0,
        val dy: Int = 0,
        val verticalAlign: String = "bottom",
        val transform: String = "none"
    )

    /** 帧推进结果：changed=是否换帧。动画只管切图，绝不控制位置（位移由物理循环负责）。 */
    data class FrameStep(val changed: Boolean)
    data class ClipStep(val changed: Boolean, val ended: Boolean = false,
                        val timerRequested: Boolean = false, val modeChanged: Boolean = false)

    /** 一个动画模式 */
    private data class ImageModeDef(
        val name: String,
        val frames: List<Frame>,
        val loop: Boolean,              // kept for callers consuming the existing modeLoops metadata
        val loopMode: String = "off",    // off / timed / infinite: independent of stored duration
        val durationMin: Long? = null,
        val durationMax: Long? = null,
        val finishCycle: Boolean = true,
        val nextModeName: String? = null
    )

    private fun rnd(min: Long, max: Long): () -> Long = { Random.nextLong(min, max + 1) }
    private fun fixed(v: Long): () -> Long = { v }

    private val modes = mutableMapOf<String, ImageModeDef>()
    /** 资源文件的垂直填充方式，取自其所属 Mode，而非文件命名。 */
    /** 素材包元数据，无对象行为状态。 */
    var currentPack: SpritePack = initialPack

    /**
     * 按【功能路径】取该功能的候选叶子（v1.01 契约）。
     * 返回空表表示素材包未提供该功能——调用方应据此【跳过】，不要发空 setMode。
     */
    // Only static standby is package-wide mandatory. Check its candidates lazily
    // and cache image-bound results so one broken alternative cannot be selected.
    private val playableModeCache = mutableMapOf<String, Boolean>()
    private val imageReadyCache = mutableMapOf<String, Boolean>()
    private fun isPlayableCandidate(start: String): Boolean {
        return playableModeCache.getOrPut(start) {
            val visited = mutableSetOf<String>()
            var next: String? = start
            while (!next.isNullOrBlank()) {
                if (!visited.add(next)) return@getOrPut true
                val mode = modes[next] ?: return@getOrPut false
                if (mode.frames.isEmpty()) return@getOrPut false
                for (frame in mode.frames) {
                    if (frame.resName.isBlank()) return@getOrPut false
                    val valid = imageReadyCache.getOrPut(frame.resName) {
                        val resource = currentPack.resolve(frame.resName)
                        if (resource == null) false else {
                            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            runCatching {
                                openPackStream(resource)?.use { BitmapFactory.decodeStream(it, null, opts) }
                                opts.outWidth > 0 && opts.outHeight > 0
                            }.getOrDefault(false)
                        }
                    }
                    if (!valid) return@getOrPut false
                }
                if (mode.loopMode == "infinite") return@getOrPut true
                next = mode.nextModeName
            }
            true
        }
    }
    fun leavesOf(vararg path: String): List<String> =
        currentPack.functions[path.toList()].orEmpty().filter { name ->
            modes[name]?.frames?.isNotEmpty() == true &&
                isPlayableCandidate(name)
        }

    /**
     * 按功能路径取【模式名】（从候选里挑一个）。
     * 候选当前多为单元素（行为不变）；将来同一功能有多个动作时，由本角色的独立随机源挑选，
     * 各角色序列互不干扰。素材包未提供该功能时返回 null。
     */
    fun modeOf(vararg path: String): String? = leavesOf(*path).let { if (it.isEmpty()) null else it.random(rng) }

    /**
     * A one-shot entrance may lead into a looping visual action.
     * The continuation is shared across functional scenes (including menu and snap),
     * and its identity must not depend on the list of Functions referencing it.
     */
    fun isContinuationOf(name: String, start: String): Boolean {
        if (name == start) return true
        val visited = mutableSetOf<String>()
        var current = start
        while (visited.add(current)) {
            val def = modes[current] ?: return false
            // In an infinite loop the saved nextMode is intentionally inactive.
            if (def.loopMode == "infinite") return false
            val next = def.nextModeName ?: return false
            if (next == name) return true
            current = next
        }
        return false
    }

    /** Consider an internal continuation part of the same user-facing function. */
    fun isFunctionMode(name: String, vararg path: String): Boolean =
        leavesOf(*path).any { isContinuationOf(name, it) }

    init {
        currentPack.configPath?.let { path ->
            try { loadModesFromJson(path) }
            catch (e: Exception) { Log.e("ImageModeManager", "无效素材包: $path", e) }
        }
    }

    /**
     * 从 assets 下的 JSON 配置加载动画模式（帧序列/延迟/锚点/待机池等）。
     * 解析失败由调用方捕获并回退到内建模式，本函数不抛未处理异常。
     */
    private fun loadModesFromJson(path: String) {
        val text = openPackStream(path)?.bufferedReader()?.use { it.readText() } ?: return
        val root = parseJsonc(text)
        // 包级元数据：全部来自 modes.jsonc 根节点（覆盖 currentPack/Skeleton 默认值，实现资产与程序解耦）。
        val decodeTarget = if (root.has("decodeTarget")) root.optInt("decodeTarget", currentPack.decodeTarget) else currentPack.decodeTarget
        val anchorRes = if (root.has("anchorRes")) root.optInt("anchorRes", currentPack.anchorRes) else currentPack.anchorRes
        val id = if (root.has("id")) root.optString("id", currentPack.id) else currentPack.id
        val name = if (root.has("name")) root.optString("name", currentPack.name) else currentPack.name
        currentPack = currentPack.copy(
            id = id, decodeTarget = decodeTarget, anchorRes = anchorRes, name = name
        )
        // 清空旧模式，按 JSON 重建
        modes.clear()
        playableModeCache.clear()
        imageReadyCache.clear()
        val modesObj = root.getJSONObject("modes")
        val kit = modesObj.keys()
        while (kit.hasNext()) {
            val name = kit.next()
            // A damaged optional animation must not prevent other functions
            // (especially the standby pool) from being loaded.
            try {
                val m = modesObj.getJSONObject(name)
                val duration = m.optJSONObject("duration")
                val configuredKind = m.optString("loopMode")
                val loopMode = if (configuredKind in listOf("off", "timed", "infinite")) configuredKind else when {
                    !m.optBoolean("loop", false) -> "off"
                    duration != null -> "timed"
                    else -> "infinite"
                }
                val loop = loopMode != "off"
                // Inactive settings remain on disk and must not control runtime playback.
                val durationMin = if (loopMode == "timed") duration?.optLong("min") else null
                val durationMax = if (loopMode == "timed") duration?.optLong("max") else null
                val finishCycle = m.optBoolean("finishCycle", true)
                val nextModeName = if (m.has("nextMode") && !m.isNull("nextMode")) m.getString("nextMode") else null
                val framesArr = m.getJSONArray("frames")
                val frames = mutableListOf<Frame>()
                for (i in 0 until framesArr.length()) {
                    val f = framesArr.getJSONObject(i)
                    val res = f.getString("res")
                    val delay = parseDelay(f.get("delay"))
                    val anchorX = if (f.has("anchorX")) f.getInt("anchorX") else -1
                    val anchorY = if (f.has("anchorY")) f.getInt("anchorY") else null
                    val dx = if (f.has("dx")) f.getInt("dx") else 0
                    val dy = if (f.has("dy")) f.getInt("dy") else 0
                    val align = f.optString("verticalAlign", "bottom")
                    frames.add(Frame(res, delay, anchorX, anchorY, dx, dy, align,
                        f.optString("transform", "none")))
                }
                if (frames.isNotEmpty()) registerMode(ImageModeDef(name, frames, loop, loopMode, durationMin,
                    durationMax, finishCycle, nextModeName))
            } catch (e: Exception) {
                Log.w("ImageModeManager", "跳过不可播放的可选动作: $name", e)
            }
        }
    }

    /** delay 字段：[min,max]=随机区间；数字=固定值(ms)。 */
    private fun parseDelay(d: Any?): () -> Long = when (d) {
        is JSONArray -> {
            require(d.length() == 2 && d.opt(0) is Number && d.opt(1) is Number)
            val min = d.getLong(0)
            val max = d.getLong(1)
            require(min > 0 && max >= min)
            rnd(min, max)
        }
        is Number -> {
            require(d.toLong() > 0)
            fixed(d.toLong())
        }
        else -> error("帧缺少有效 delay")
    }

    private fun registerMode(mode: ImageModeDef) {
        modes[mode.name] = mode
    }

    /**
     * 创建完整 Mode 的独立播放游标，供非角色对象复用已解析的动画定义与位图缓存。
     * 与角色共享 Mode 解析/图片加载，但绝不调用角色的物理回调或待机池。
     * 每个显示实例创建自己的游标；其帧序、延时与 nextMode 都来自素材包。
     */
    fun createClip(modeId: String): ModeClip? =
        if (modes.containsKey(modeId)) ModeClip(modeId) else null

    fun modeFrameBitmaps(modeId: String): Map<String, Bitmap> {
        val frames = linkedMapOf<String, Bitmap>()
        val visited = mutableSetOf<String>()
        var next: String? = modeId
        while (next != null && visited.add(next)) {
            val def = modes[next] ?: break
            for (frame in def.frames) {
                val visualKey = frame.resName + "|" + frame.verticalAlign + "|" + frame.transform
                if (visualKey !in frames) {
                    loadBitmap(frame.resName, frame.verticalAlign, frame.transform)?.let {
                        frames[visualKey] = it
                    }
                }
            }
            next = def.nextModeName
        }
        return frames
    }

    /** 模式图像显示结果。透明边缘裁剪属于可选的渲染变换，与角色/道具类型无关。 */
    data class ModeVisual(val frames: Map<String, Bitmap>, val first: Bitmap)

    // 同一个素材包实例共享解码位图、各类渲染变换和透明边界；缓存键不含具体对象类型。
    private val modeVisualCache = mutableMapOf<Pair<String, Int?>, ModeVisual>()
    fun modeVisual(modeId: String, alphaTrimThreshold: Int? = null): ModeVisual? {
        val key = modeId to alphaTrimThreshold
        modeVisualCache[key]?.let { return it }
        val source = modeFrameBitmaps(modeId)
        if (source.isEmpty()) return null
        val frames = if (alphaTrimThreshold == null) {
            source
        } else {
            var union: android.graphics.Rect? = null
            for (bitmap in source.values) {
                val tight = ModeImageProcessing.alphaBounds(bitmap, alphaTrimThreshold) ?: continue
                if (union == null) union = android.graphics.Rect(tight) else union.union(tight)
            }
            val firstBitmap = source.values.first()
            val bounds = union ?: android.graphics.Rect(0, 0, firstBitmap.width, firstBitmap.height)
            source.mapValues { (_, bitmap) ->
                Bitmap.createBitmap(bitmap, bounds.left, bounds.top, bounds.width(), bounds.height())
            }
        }
        return ModeVisual(frames, frames.values.first()).also { modeVisualCache[key] = it }
    }

    inner class ModeClip internal constructor(startMode: String) {
        private var def = requireNotNull(modes[startMode]) { "Undefined Mode: $startMode" }
        private var frameIndex = 0
        private var elapsed = 0L
        private var frameDelay = def.frames[0].delay().coerceAtLeast(1L)
        private var timeElapsed = 0L
        private var timeTarget = sampleTimeTarget()
        private var pendingFinish = false
        var isEnded: Boolean = false
            private set

        val currentResName: String get() = def.frames[frameIndex].resName
        val currentModeId: String get() = def.name
        val currentFrameIndex: Int get() = frameIndex
        val debugFrameCount: Int get() = def.frames.size
        val debugFrameElapsedMs: Long get() = elapsed
        val debugFrameDurationMs: Long get() = frameDelay
        val debugLoopKind: String get() = def.loopMode
        val debugDurationMinMs: Long? get() = def.durationMin
        val debugDurationMaxMs: Long? get() = def.durationMax
        val debugWaitingForCycle: Boolean get() = pendingFinish
        val isInfiniteLoop: Boolean get() = def.loopMode == "infinite"
        val currentDx: Int get() = def.frames[frameIndex].dx
        val currentDy: Int get() = def.frames[frameIndex].dy
        // Movement capability must not depend on the animation's loop policy.
        val hasFrameMotion: Boolean get() = def.frames.any { it.dx != 0 || it.dy != 0 }
        val currentVerticalAlign: String get() = def.frames[frameIndex].verticalAlign
        val currentTransform: String get() = def.frames[frameIndex].transform
        /** Unique visual reference: one PNG may be used with multiple frame transformations. */
        val currentVisualKey: String get() =
            currentResName + "|" + currentVerticalAlign + "|" + currentTransform
        val currentAnchorX: Int get() = def.frames[frameIndex].anchorX
        val currentAnchorY: Int? get() = def.frames[frameIndex].anchorY
        fun currentBitmap(): Bitmap? = loadBitmap(currentResName, currentVerticalAlign, currentTransform)
        fun currentAnchor(): Pair<Int, Int> = frameAnchor(currentResName,
            currentAnchorX, currentAnchorY, currentVerticalAlign, currentTransform)

        private fun sampleTimeTarget(): Long {
            val min = def.durationMin ?: return Long.MAX_VALUE
            val max = (def.durationMax ?: min).coerceAtLeast(min)
            return if (max == Long.MAX_VALUE) max else Random.nextLong(min.coerceAtLeast(1), max + 1)
        }

        private fun resetPlayback(restartTimer: Boolean) {
            frameIndex = 0
            elapsed = 0L
            frameDelay = def.frames[0].delay().coerceAtLeast(1L)
            if (restartTimer) {
                timeElapsed = 0L
                timeTarget = sampleTimeTarget()
                pendingFinish = false
            }
            isEnded = false
        }

        /** Reuse the cursor when a function pool selects its current mode again. */
        fun restart() = resetPlayback(restartTimer = true)

        private fun switchTo(id: String): Boolean {
            if (id == def.name) {
                // A self nextMode is a new cycle, not a real mode transition.
                resetPlayback(restartTimer = true)
                return true
            }
            val next = modes[id] ?: return false
            def = next
            resetPlayback(restartTimer = true)
            return true
        }

        /** Frames always play sequentially; only the Mode controls looping and transitions. */
        fun advance(dtMs: Long, allowModeSwitch: Boolean = true): ClipStep {
            if (isEnded || dtMs <= 0L) return ClipStep(false, isEnded)
            var remaining = dtMs
            var changed = false
            var modeChanged = false
            var timerRequested = false
            var guard = 0
            while (remaining > 0 && !isEnded && guard++ < 256) {
                val span = minOf(remaining, (frameDelay - elapsed).coerceAtLeast(1L))
                remaining -= span
                elapsed += span
                timeElapsed += span
                if (def.loop && def.durationMin != null && allowModeSwitch &&
                    timeElapsed >= timeTarget) {
                    if (def.finishCycle) pendingFinish = true
                    else {
                        val next = def.nextModeName
                        val sameMode = next == def.name
                        if (next != null && switchTo(next)) {
                            changed = true
                            modeChanged = true
                        } else {
                            timeTarget = Long.MAX_VALUE
                            timerRequested = true
                        }
                        break
                    }
                }
                if (elapsed >= frameDelay) {
                    if (frameIndex < def.frames.lastIndex) {
                        frameIndex++
                        elapsed = 0L
                        frameDelay = def.frames[frameIndex].delay().coerceAtLeast(1L)
                        changed = true
                    } else if (def.loop && !pendingFinish) {
                        // Looping frames retain their sampled timer; no mode switch.
                        resetPlayback(restartTimer = false)
                        changed = true
                    } else {
                        val next = def.nextModeName
                        val sameMode = next == def.name
                        if (next != null && allowModeSwitch && switchTo(next)) {
                            changed = true
                            modeChanged = !sameMode
                        } else if (def.loop && def.durationMin != null && allowModeSwitch) {
                            timeTarget = Long.MAX_VALUE
                            timerRequested = true
                        } else isEnded = true
                        break
                    }
                }
            }
            return ClipStep(changed, isEnded, timerRequested, modeChanged)
        }
    }

    /** 素材切换只更新定义和图片缓存，不决定对象播放什么动画。 */
    fun setPack(pack: SpritePack) {
        if (currentPack.id == pack.id) return
        currentPack = pack
        bitmapCache.clear()
        rawSizeCache.clear()
        modeVisualCache.clear()
        modes.clear()
        playableModeCache.clear()
        imageReadyCache.clear()
        pack.configPath?.let { loadModesFromJson(it) }
        val (w, h) = globalMaxSize()
        globalW = w
        globalH = h
    }

    fun frameBitmap(resName: String): Bitmap? = loadBitmap(resName)

    /**
     * 预览用：返回某帧经本包“复杂变换”后的位图——即 [loadBitmap] 产物：
     * 按包 decodeTarget 物理超采样 + 统一画布 padding（roll 居中、其余顶部对齐底部补透明）。
     * 首页预览借此让不同资源图（1000px 高清 / 128px 原生）在预览框内尺寸一致、锚点正确。
     */
    fun previewFrame(resName: String): Bitmap? = loadBitmap(resName)

    /** 当前帧【逻辑画布】尺寸（恒 128 基准；新包解码 256 超采样但画布逻辑仍为 128）。
     *  窗口/锚点 X 居中/渲染矩形均以画布计，而非逐帧 bitmap 像素（256 超采样源）。 */
    fun currentBitmapSize(): Pair<Int, Int> {
        return if (globalW > 0 && globalH > 0) globalW to globalH else 160 to 160
    }

    /** 原始位图尺寸记忆化：同一 resName 只解一次，消除 currentAnchor() 每次调用都 open+decode 的 I/O 抖动。 */
    private val rawSizeCache = mutableMapOf<String, Pair<Int, Int>>()

    /**
     * 仅解码取原始位图尺寸（不进入 bitmapCache、不引用 globalW/globalH），
     * 专供 init 阶段计算统一画布尺寸，避免与 globalW/globalH 的 init 形成初始化死循环。
     * 结果按 resName 记忆化：同一帧尺寸只解一次，既消除重复 I/O，也保证“位置求锚点”与“显示求锚点”
     * 取到的是同一份已缓存尺寸、不会因重读原始文件而错位。
     */
    // 取原图尺寸（用于锚点/ padding 计算）。缓存避免重复解码头。
    // 新包：返回「保比例下采样」后的真实内容尺寸（最长边 = decodeTarget，如 1000×500 → 128×64），
    // 而非原始的 1000×1000 也非方块 128×128（否则非正方形图锚点会算到错误处/脚落到透明区导致错位）。
    private fun rawBitmapSize(resName: String, transform: String = "none"): Pair<Int, Int> {
        val size = sourceBitmapSize(resName)
        return if (transform == "rotate90" || transform == "rotate270") size.second to size.first else size
    }

    private fun sourceBitmapSize(resName: String): Pair<Int, Int> {
        rawSizeCache[resName]?.let { return it }
        val path = currentPack.resolve(resName)
        if (path == null) {
            rawSizeCache[resName] = 0 to 0
            return 0 to 0
        }
        val r = if (currentPack.decodeTarget > 0) {
            try {
                val istr = openPackStream(path)
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(istr, null, opts)
                istr?.close()
                // 逻辑尺寸：按【anchorRes=128】保比例算，而非物理 decodeTarget(256)。
                // 锚点/画布/边界全部活在 128 逻辑空间，decodeTarget 只影响解码清晰度、不影响坐标。
                scaledContentSize(opts.outWidth, opts.outHeight, currentPack.anchorRes)
            } catch (_: Exception) {
                0 to 0 // 素材包必须完整；无法读取的帧不伪装为有效图片尺寸
            }
        } else {
            try {
                val istr = openPackStream(path)
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(istr, null, opts)
                opts.outWidth to opts.outHeight
            } catch (_: Exception) {
                0 to 0
            }
        }
        rawSizeCache[resName] = r
        return r
    }

    /**
     * 所有模式所有帧的全局最大【原始】尺寸（真实像素，未 padding）。用于固定统一画布尺寸，
     * 保证任何动画帧（滚动、探头、落地和其他动画等）都能完整显示且窗口尺寸恒定 → 不重排。
     * 用 rawBitmapSize（仅取边界）而非 loadBitmap，避免初始化期引用尚未赋值的 globalW/globalH。
     */
    fun globalMaxSize(): Pair<Int, Int> {
        var mw = 0
        var mh = 0
        for (def in modes.values) {
            for (f in def.frames) {
                val (bw, bh) = rawBitmapSize(f.resName, f.transform)
                if (bw > mw) mw = bw
                if (bh > mh) mh = bh
            }
        }
        return if (mw > 0 && mh > 0) mw to mh else currentBitmapSize()
    }

    /**
     * 当前帧锚点（【转换器】内部使用，对外输入见各 frame.anchorX/Y 注释）。
     *
     * 对外输入约定（方便美术/新增动作直接按图确认地面点）：
     *   - anchorX：距图片【左】边的像素（<0 表示水平居中 = 图宽/2）
     *   - anchorY：距图片【底】边的像素（左下角原点，Y 向上）。null = 未设置（贴底 = 0）；
     *     可为负值（脚在图底下方，如 不同姿态动画），转换器照常计算 rawH - anchorY，不截断。
     * 背景：原 PC 锚点是“距顶 + 贴底 padding 偏移”的复合值，难直观。现改为按 PNG 左下角
     * 直接填地面点，转换器在内部还原为旧版等价坐标，保证渲染/边界效果【与改动前完全一致】。
     *
     * 还原推导（padY 由 fillOffset 给出：非 roll = 0 顶部填充、roll = (globalH-H_orig)/2 居中）：
     *   新版输出 = (anchorX距左 + padX) , (anchorY距顶 + padY)
     *   新版输入 = 距底 = H_orig - 距顶  ⇒  距顶 = H_orig - anchorY
     *   ⇒ 新版输出 = (anchorX + padX) , ((H_orig - anchorY) + padY)
     *             = (anchorX + padX) , (anchorY + (H_orig + padY - anchorY))  // 与旧距顶版等价
     *   即：ay = (H_orig - anchorY) + padY，X 不变（距左语义一致）；
     *   非 roll 时 padY=0 ⇒ ay = H_orig - anchorY（脚距画布顶 = 图内脚距顶），画布底到脚为透明。
     *   ROLL 不再特判，使用与所有动作相同的真实 anchorY；其旋转一致性由 PetView 在
     *   rotateOffsetWith / onDraw 中对 ROLL 改绕锚点自身旋转来保证（球状对称，外观不变）。
     */
    /** 根据当前帧的图片和声明锚点计算画布锚点，不涉及任何对象物理状态。 */

    /** Unpadded picture rectangle in the same logical canvas coordinates as frameAnchor(). */
    fun previewContentBounds(resName: String, alignment: String = "bottom",
                             transform: String = "none"): android.graphics.RectF {
        val (w, h) = rawBitmapSize(resName, transform)
        val (left, top) = fillOffset(w, h,
            currentPack.anchorRes, currentPack.anchorRes, alignment)
        return android.graphics.RectF(left.toFloat(), top.toFloat(),
            (left + w).toFloat(), (top + h).toFloat())
    }

    fun frameAnchor(resName: String, anchorX: Int, anchorY: Int?,
                    alignment: String = "bottom", transform: String = "none"): Pair<Int, Int> {
        val (w, _) = currentBitmapSize()
        val (_, rawH) = rawBitmapSize(resName, transform)
        val axRaw = if (anchorX >= 0) anchorX else w / 2
        val ayRaw = rawH - (anchorY ?: 0)
        val (padX, padY) = padOf(resName, alignment, transform)
        return (axRaw + padX) to (ayRaw + padY)
    }

    private val bitmapCache = mutableMapOf<Triple<String, String, String>, Bitmap?>()

    /**
     * 统一画布尺寸（全局最大帧）。所有帧加载后都被居中 padding 到此尺寸，
     * 使每张帧的位图宽高恒等于 [globalW,globalH]：
     *  - getAnchorScaled 的居中偏移 (w-dw)/2 恒为 0，窗口尺寸恒定 → 切模式不 resize、不卡顿；
     *  - 锚点 (ax,ay) 经 pad 偏移后仍指向「宠物脚在原图内的相对位置」，跨模式连续 → 不错位。
     * 这解决了“各 mode 画布被裁切到不同尺寸、锚点像素值未同步”导致的全链路错位。
     */
    var globalW: Int = 0
    var globalH: Int = 0
    init {
        val (w, h) = globalMaxSize()
        globalW = w
        globalH = h
    }

    /** Vertical alignment runs after orientation correction, before animation. */
    private fun fillOffset(bw: Int, bh: Int, canvasW: Int = globalW,
                           canvasH: Int = globalH, alignment: String = "bottom"): Pair<Int, Int> {
        if (bw <= 0 || bh <= 0) return 0 to 0
        val x = (canvasW - bw).coerceAtLeast(0) / 2
        val freeY = (canvasH - bh).coerceAtLeast(0)
        val y = when (alignment) {
            "top" -> 0
            "center" -> freeY / 2
            else -> freeY
        }
        return x to y
    }

    private fun padOf(resName: String, alignment: String, transform: String): Pair<Int, Int> {
        val (w, h) = rawBitmapSize(resName, transform)
        return fillOffset(w, h, alignment = alignment)
    }

    private fun transformBitmap(bitmap: Bitmap, transform: String): Bitmap {
        if (transform == "none") return bitmap
        val matrix = android.graphics.Matrix()
        when (transform) {
            "flipX" -> matrix.setScale(-1f, 1f)
            "flipY" -> matrix.setScale(1f, -1f)
            "rotate90" -> matrix.setRotate(90f)
            "rotate180" -> matrix.setRotate(180f)
            "rotate270" -> matrix.setRotate(270f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    /** 按当前资源包打开任意资源流；ASSETS 走 assets.open，FILES 走 filesDir/roles/<base>/...。 */
    private fun openPackStream(resolvedPath: String): java.io.InputStream? = try {
        when (currentPack.source) {
            RoleSource.ASSETS -> ctx.assets.open(resolvedPath)
            RoleSource.FILES -> File(ctx.filesDir, "roles/$resolvedPath").inputStream()
        }
    } catch (_: Exception) { null }

    /** Decode source PNG, transform it, then align inside a transparent canvas. */
    private fun loadBitmap(resName: String, alignment: String = "bottom",
                           transform: String = "none"): Bitmap? {
        val key = Triple(resName, alignment, transform)
        bitmapCache[key]?.let { return it }
        val path = currentPack.resolve(resName) ?: return null
        val decoded = try {
            if (currentPack.decodeTarget > 0) decodeAssetScaled(path, currentPack.decodeTarget)
            else openPackStream(path)?.use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) { null } ?: return null
        val transformed = transformBitmap(decoded, transform)
        if (transformed !== decoded) decoded.recycle()
        val canvasRes = if (currentPack.decodeTarget > 0) currentPack.decodeTarget else globalW
        val bitmap = if (transformed.width == canvasRes && transformed.height == canvasRes) {
            transformed
        } else {
            val (x, y) = fillOffset(transformed.width, transformed.height, canvasRes, canvasRes, alignment)
            val out = Bitmap.createBitmap(canvasRes, canvasRes, Bitmap.Config.ARGB_8888)
            android.graphics.Canvas(out).drawBitmap(transformed, x.toFloat(), y.toFloat(), null)
            transformed.recycle()
            out
        }
        bitmapCache[key] = bitmap
        return bitmap
    }

    /**
     * 下采样解码（仅新包 decodeTarget>0 用）：先以 inJustDecodeBounds 取原始尺寸算 inSampleSize
     * （按最长边向上取整到目标尺寸，避免欠采样发虚），粗降后【保比例】精确缩放，使最长边 = target，
     * 而非压成 target×target 方块（否则非正方形图会被拉伸变形）。
     * 真实内容尺寸由 scaledContentSize 计算，与 rawBitmapSize 共用，保证锚点/画布一致。
     */
    private fun decodeAssetScaled(path: String, target: Int): Bitmap? {
        val istr1 = try { openPackStream(path) } catch (_: Exception) { return null }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(istr1, null, bounds)
        istr1?.close()
        val realW = bounds.outWidth.takeIf { it > 0 } ?: return null
        val realH = bounds.outHeight.takeIf { it > 0 } ?: return null
        val (outW, outH) = scaledContentSize(realW, realH, target)   // 保比例：最长边 = target
        val longSide = if (realW > realH) realW else realH
        val inSample = ((longSide + target - 1) / target).coerceAtLeast(1)
        val istr2 = try { openPackStream(path) } catch (_: Exception) { return null }
        val opts = BitmapFactory.Options().apply { inSampleSize = inSample }
        val base = BitmapFactory.decodeStream(istr2, null, opts) ?: return null
        return if (base.width == outW && base.height == outH) base
        else Bitmap.createScaledBitmap(base, outW, outH, true).also { if (it != base) base.recycle() }
    }

    /**
     * 计算「最长边缩到 target、保比例」后的内容尺寸（与 decodeAssetScaled 产物严格一致）。
     * 例：1000×500, target=128 → 128×64；128×64 再经 fillOffset 顶部对齐、底部补透明到统一画布。
     */
    private fun scaledContentSize(realW: Int, realH: Int, target: Int): Pair<Int, Int> {
        if (realW <= 0 || realH <= 0) return 0 to 0
        val longSide = if (realW > realH) realW else realH
        val scale = target.toFloat() / longSide
        val outW = (realW * scale).roundToInt().coerceAtLeast(1)
        val outH = (realH * scale).roundToInt().coerceAtLeast(1)
        return outW to outH
    }

    /** 释放位图缓存（View 销毁时调用） */
    fun clearCache() {
        modeVisualCache.clear()
        bitmapCache.clear()
        rawSizeCache.clear()
    }
}

