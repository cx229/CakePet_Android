package com.cx.cakepet

import android.graphics.Bitmap

/** 宠物专用的状态选择及动作生命周期，通用 ImageModeManager 不参与任何角色业务判断。 */
class PetAnimationController(private val assets: ImageModeManager) {
    private var currentName = assets.modeOf("static", "sit") ?: ""
    private var clip: ImageModeManager.ModeClip? = assets.createClip(currentName)
    private var forced = false
    private var forcedReturn: String? = null
    private var forcedStart: String? = null
    private var modeChanged = false
    private var thinkingFunctionPath: List<String> = listOf("static", "sit")
    private val pendingFunctionForMode = mutableMapOf<String, List<String>>()
    private var frameMotionPending = true
    /** Active state-owned Functions path. A finished action chain resumes from this pool. */
    private var activeStateFunction: List<String>? = null
    /** Engine may choose an automatic function at a natural action boundary. */
    var onAutomaticModeChoice: (() -> String?)? = null

    fun setActiveStateFunction(path: List<String>?) {
        activeStateFunction = path
    }

    private fun activeStateMode(): String? =
        activeStateFunction?.let { path -> assets.leavesOf(*path.toTypedArray()).takeIf { it.isNotEmpty() }?.random() }

    var useStaticPool = false
    var forageActive = false
    /** Set only when the normal action chooser starts the forage behavior. */
    var forageSelected = false
    var inSnapped = false
    var isFlying = false

    /** Engine may choose an initial facing when the normal pool selects a moving clip. */
    var onStandbyMotionChosen: (() -> Unit)? = null

    val globalW: Int get() = assets.globalW
    val globalH: Int get() = assets.globalH
    fun globalMaxSize(): Pair<Int, Int> = assets.globalMaxSize()
    fun currentBitmapSize(): Pair<Int, Int> = assets.currentBitmapSize()
    fun currentBitmap(): Bitmap? = clip?.currentBitmap()
    fun currentAnchor(): Pair<Int, Int> = clip?.currentAnchor() ?: (80 to 160)
    fun currentFrameFlipX(): Boolean = clip?.currentFlipX ?: false
    fun previewFrame(res: String): Bitmap? = assets.previewFrame(res)
    fun currentResName(): String? = clip?.currentResName
    fun curDx(): Int = clip?.currentDx ?: 0
    fun curDy(): Int = clip?.currentDy ?: 0
    fun getMode(): String = clip?.currentModeId ?: ""
    fun isPlayingForced(): Boolean = forced
    /** Frame impulse is consumed once for initial frame, every advance and loop wrap. */
    fun consumeFrameDisplacement(): Pair<Int, Int>? {
        if (!frameMotionPending) return null
        frameMotionPending = false
        return curDx() to curDy()
    }
    fun leavesOf(vararg path: String): List<String> = assets.leavesOf(*path)
    fun modeOf(vararg path: String): String? = assets.modeOf(*path)?.also {
        pendingFunctionForMode[it] = path.toList()
    }
    fun currentThinkingConfig(): Pair<ThinkingConfig, Map<String, String>> {
        val catalog = assets.currentPack.thinking
        return catalog.resolve(getMode(), thinkingFunctionPath) to catalog.texts
    }
    fun isFunctionMode(name: String, vararg path: String): Boolean = assets.isFunctionMode(name, *path)
    fun isDragMode(name: String = getMode()): Boolean =
        listOf("slow", "left_medium", "left_fast", "right_medium", "right_fast")
            .any { isFunctionMode(name, "touch", "lift_up", it) }

    private fun currentPool(): List<String> =
        if (useStaticPool) leavesOf("static", "pool") else leavesOf("standby", "pool")
    fun isStandbyMode(name: String = getMode()): Boolean = currentPool().any { assets.isContinuationOf(name, it) }
    fun pickStandbyMode(exclude: String): String {
        // Only this natural completion boundary may initiate automatic behaviors.
        forageSelected = false
        if (forageActive) modeOf("climb", "forage", "move")?.let {
            forageSelected = true
            return it
        }
        // Behavior decisions (e.g. whether to leap) belong to PetView, not Modes.
        onAutomaticModeChoice?.invoke()?.let { return it }
        val pool = currentPool().filter { it != exclude }
        val selected = if (pool.isNotEmpty()) pool.random() else getMode()
        // Random facing is a standby behavior, not a privilege of any Mode/Functions name.
        pendingFunctionForMode[selected] = if (useStaticPool) listOf("static", "pool") else listOf("standby", "pool")
        if (assets.createClip(selected)?.hasFrameMotion == true) onStandbyMotionChosen?.invoke()
        return selected
    }

    /** A lure is a fresh normal function choice, not a forced-mode override.
     * Ignore current Mode, nextMode and forcedReturn: the whole old chain is
     * considered finished. State-owned functions still take priority.
     */
    fun tryRestartForageFromNormalChoice(): Boolean {
        val foragePath = listOf("climb", "forage", "move")
        if (!forageActive || useStaticPool) return false
        if (activeStateFunction != null && activeStateFunction != foragePath) return false
        // A missing optional function cannot be chosen by normal selection.
        if (assets.leavesOf(*foragePath.toTypedArray()).isEmpty()) return false

        val next = if (activeStateFunction == foragePath) {
            activeStateMode()
        } else {
            pickStandbyMode(getMode())
        } ?: return false

        // Commit the choice only after the normal selector confirms forage.
        forced = false
        forcedReturn = null
        forcedStart = null
        forageSelected = true
        setMode(next)  // Recreates the clip even when already foraging.
        return true
    }

    fun setMode(id: String) {
        if (forced) return
        val next = assets.createClip(id) ?: return
        clip = next
        thinkingFunctionPath = pendingFunctionForMode.remove(id) ?: activeStateFunction ?: thinkingFunctionPath
        currentName = id
        modeChanged = true
        frameMotionPending = true
    }

    fun playOnce(id: String, endMode: String? = null) {
        val next = assets.createClip(id) ?: return
        clip = next
        thinkingFunctionPath = pendingFunctionForMode.remove(id) ?: thinkingFunctionPath
        forced = true
        forcedReturn = endMode
        forcedStart = id
        modeChanged = true
        frameMotionPending = true
    }

    fun cancelForced() {
        if (!forced) return
        forced = false
        forcedReturn = null
        forcedStart = null
        clip = assets.createClip(currentName)
        modeChanged = true
        frameMotionPending = true
    }

    fun forceReset() {
        forced = false
        forcedReturn = null
        forcedStart = null
        modeOf("static", "sit")?.let { setMode(it) }
    }

    fun setPack(pack: SpritePack) {
        if (assets.currentPack.id == pack.id) return
        assets.setPack(pack)
        forced = false
        forcedReturn = null
        forcedStart = null
        currentName = ""
        activeStateFunction = null
        pendingFunctionForMode.clear()
        thinkingFunctionPath = listOf("static", "sit")
        modeOf("static", "sit")?.let { setMode(it) }
    }

    /** Physical landing wins over every unfinished flight animation or nextMode chain.
     * The landing function is an independent one-shot; its own Mode chain then plays
     * normally. Missing optional mappings fall back to ordinary automatic selection.
     */
    fun onLeapLanded() {
        val landing = modeOf("climb", "leap", "landing")
        if (landing != null) {
            playOnce(landing)
        } else {
            if (forced) cancelForced()
            setMode(pickStandbyMode(getMode()))
        }
    }

    fun didModeChange(): Boolean = modeChanged.also { modeChanged = false }

    /** 每帧只调用通用动画游标，待机池、飞跃等行为仍由宠物控制器决定。 */
    fun nextFrame(dtMs: Long): ImageModeManager.FrameStep {
        val current = clip ?: return ImageModeManager.FrameStep(false)
        // Physical flight never pauses a Mode's own frame timer or nextMode chain.
        val step = current.advance(dtMs)
        if (step.modeChanged) {
            val continuation = forced && forcedStart?.let {
                assets.isContinuationOf(current.currentModeId, it)
            } == true
            // A forced interaction can naturally return to the state-owned
            // infinite idle animation (e.g. snapped pat -> snapped idle).
            // At that point the state, not the old interaction, owns playback.
            val stateOwnsInfinite = current.isInfiniteLoop && activeStateFunction?.let { path ->
                assets.leavesOf(*path.toTypedArray()).any {
                    assets.isContinuationOf(current.currentModeId, it)
                }
            } == true
            if (forced && (!continuation || stateOwnsInfinite)) {
                forced = false
                forcedReturn = null
                forcedStart = null
            }
            if (!continuation) currentName = current.currentModeId
            modeChanged = true
            frameMotionPending = true
        }
        if (step.changed) {
            frameMotionPending = true
        }
        if (step.ended || step.timerRequested) {
            val endMode = if (forced) forcedReturn else null
            forced = false
            forcedReturn = null
            forcedStart = null
            val desired = endMode ?: activeStateMode() ?: pickStandbyMode(getMode())
            if (desired == current.currentModeId) {
                // Re-enter the same mode without replacing the cursor or resetting heading.
                current.restart()
                modeChanged = true
                frameMotionPending = true
            } else {
                setMode(desired)
            }
            return ImageModeManager.FrameStep(true)
        }
        return ImageModeManager.FrameStep(step.changed)
    }

    fun clearCache() = assets.clearCache()
}
