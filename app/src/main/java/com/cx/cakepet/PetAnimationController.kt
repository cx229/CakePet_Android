package com.cx.cakepet

import android.graphics.Bitmap
import kotlin.random.Random

/** 宠物专用的状态选择及动作生命周期，通用 ImageModeManager 不参与任何角色业务判断。 */
class PetAnimationController(private val assets: ImageModeManager) {
    private var currentName = assets.modeOf("static", "sit") ?: ""
    private var clip: ImageModeManager.ModeClip? = assets.createClip(currentName)
    private var forced = false
    private var forcedReturn: String? = null
    private var modeChanged = false

    var useStaticPool = false
    var leapEnabled = false
    var forageActive = false
    var leapProbability = 0.10f
    var inClimbing = false
    var isFlying = false

    var walkDir = 1
    var walkSpeed = 0f
        private set
    var onWalkStateChanged: ((speed: Float, dir: Int) -> Unit)? = null

    val globalW: Int get() = assets.globalW
    val globalH: Int get() = assets.globalH
    fun globalMaxSize(): Pair<Int, Int> = assets.globalMaxSize()
    fun currentBitmapSize(): Pair<Int, Int> = assets.currentBitmapSize()
    fun currentBitmap(): Bitmap? = clip?.currentBitmap()
    fun currentAnchor(): Pair<Int, Int> = clip?.currentAnchor() ?: (80 to 160)
    fun previewFrame(res: String): Bitmap? = assets.previewFrame(res)
    fun currentResName(): String? = clip?.currentResName
    fun curDx(): Int = clip?.currentDx ?: 0
    fun curDy(): Int = clip?.currentDy ?: 0
    fun getMode(): String = clip?.currentModeId ?: ""
    fun isPlayingForced(): Boolean = forced
    fun isWalk(): Boolean = walkSpeed > 0f && !forced
    fun leavesOf(vararg path: String): List<String> = assets.leavesOf(*path)
    fun modeOf(vararg path: String): String? = assets.modeOf(*path)
    fun isFunctionMode(name: String, vararg path: String): Boolean = assets.isFunctionMode(name, *path)
    fun isDragMode(name: String = getMode()): Boolean =
        listOf("slow", "left_medium", "left_fast", "right_medium", "right_fast")
            .any { isFunctionMode(name, "touch", "lift_up", it) }

    private fun currentPool(): List<String> =
        if (useStaticPool) leavesOf("static", "pool") else leavesOf("standby", "pool")
    fun isStandbyMode(name: String = getMode()): Boolean = name in currentPool()
    fun pickStandbyMode(exclude: String): String {
        if (forageActive) modeOf("climb", "walk")?.let { return it }
        if (leapEnabled && inClimbing && Random.nextFloat() < leapProbability)
            modeOf("climb", "jump_up")?.let { return it }
        val pool = currentPool().filter { it != exclude }
        return if (pool.isNotEmpty()) pool.random() else getMode()
    }

    private fun updateWalkState(speed: Float) {
        if (speed > 0f) {
            walkDir = if (Random.nextBoolean()) 1 else -1
            walkSpeed = speed
        } else {
            walkDir = 1
            walkSpeed = 0f
        }
        onWalkStateChanged?.invoke(walkSpeed, walkDir)
    }

    fun setMode(id: String) {
        if (forced) return
        val next = assets.createClip(id) ?: return
        val different = getMode() != id
        clip = next
        currentName = id
        if (different) modeChanged = true
        updateWalkState(next.currentWalkSpeed)
    }

    fun playOnce(id: String, endMode: String? = null) {
        val next = assets.createClip(id) ?: return
        clip = next
        forced = true
        forcedReturn = endMode
        modeChanged = true
    }

    fun cancelForced() {
        if (!forced) return
        forced = false
        forcedReturn = null
        clip = assets.createClip(currentName)
        modeChanged = true
    }

    fun forceReset() {
        forced = false
        forcedReturn = null
        modeOf("static", "sit")?.let { setMode(it) }
    }

    fun setPack(pack: SpritePack) {
        if (assets.currentPack.id == pack.id) return
        assets.setPack(pack)
        forced = false
        forcedReturn = null
        currentName = ""
        modeOf("static", "sit")?.let { setMode(it) }
    }

    fun didModeChange(): Boolean = modeChanged.also { modeChanged = false }

    /** 每帧只调用通用动画游标，待机池、飞跃等行为仍由宠物控制器决定。 */
    fun nextFrame(dtMs: Long): ImageModeManager.FrameStep {
        val current = clip ?: return ImageModeManager.FrameStep(false)
        val step = current.advance(dtMs, allowModeSwitch = !isFlying)
        if (step.modeChanged) {
            if (forced) {
                forced = false
                forcedReturn = null
            }
            currentName = current.currentModeId
            modeChanged = true
            updateWalkState(current.currentWalkSpeed)
        }
        if (!isFlying && (step.ended || step.timerRequested)) {
            val endMode = if (forced) forcedReturn else null
            forced = false
            forcedReturn = null
            setMode(endMode ?: pickStandbyMode(getMode()))
            return ImageModeManager.FrameStep(true)
        }
        return ImageModeManager.FrameStep(step.changed)
    }

    fun clearCache() = assets.clearCache()
}
