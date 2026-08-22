package com.example.cakepet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.IOException

/**
 * 图像模式定义（对应 PC 端 ImageConf）。
 * baseFrame: 静止基础帧；otherFrames: 播放序列（一次性或循环）。
 */
data class ImageMode(
    val name: String,
    val baseFrame: String,
    val otherFrames: List<String>,
    val loop: Boolean = false
)

/**
 * 帧序列：一次性播放后回到 base，或循环播放。
 * 对应 PC 端 ImagesMode（base + other frames 状态机）。
 */
class FrameSequence(
    val baseFrame: String,
    val otherFrames: List<String>,
    private val loop: Boolean
) {
    private var index = -1          // -1 表示停在 base
    private var rounds = 0
    val isBase: Boolean get() = index < 0

    fun current(): String {
        return if (index < 0) baseFrame else otherFrames[index]
    }

    fun feed(): String {
        if (otherFrames.isEmpty()) return baseFrame
        index++
        if (index >= otherFrames.size) {
            if (loop) {
                index = 0
                rounds++
            } else {
                index = -1   // 播放完毕，回到 base
            }
        }
        return current()
    }

    fun finishedOnce(): Boolean {
        return !loop && index < 0
    }
}

/**
 * 图像模式管理器（对应 PC 端 ModeManager）。
 * 负责从 assets/img 加载 Bitmap 并维护当前模式，提供每帧喂帧能力。
 */
class ImageModeManager(private val context: Context) {

    private val cache = mutableMapOf<String, Bitmap>()
    private val modes = mutableMapOf<String, FrameSequence>()

    // 当前模式
    private var currentName = DEFAULT_MODE
    private var currentSeq: FrameSequence

    // 强制播放一次性动画（如摸头、跳跃），播放完恢复 base
    private var forcedSeq: FrameSequence? = null

    companion object {
        const val DEFAULT_MODE = "default"
        const val PAT_HEAD = "pat_head"
        const val JUMP_DOWN = "jump_down"
        const val LIFT_UP = "lift_up"
        const val SHAKE_HEAD = "shake_head"
        const val ROLL = "roll"
        const val SIT_CLAM = "sit_clam"
        const val SIT_PUFFED = "sit_puffed"
        const val WALK = "walk"
        const val WRIGGLE = "wriggle"
    }

    init {
        registerMode(ImageMode(DEFAULT_MODE, "sit_clam-1.png",
            listOf("sit_clam-1.png", "sit_clam-2.png", "sit_clam-3.png"), loop = true))
        registerMode(ImageMode(PAT_HEAD, "sit_clam-1.png",
            listOf("pat_head-1.png", "pat_head-2.png", "pat_head-3.png", "pat_head-4.png", "pat_head-5.png")))
        registerMode(ImageMode(JUMP_DOWN, "sit_clam-1.png",
            listOf("jump_down-1.png", "jump_down-2.png", "jump_down-3.png")))
        registerMode(ImageMode(LIFT_UP, "sit_clam-1.png",
            listOf("lift_up-1.png", "lift_up-2.png", "lift_up-3.png", "lift_up-4.png", "lift_up-5.png", "lift_up-6.png")))
        registerMode(ImageMode(SHAKE_HEAD, "sit_clam-1.png",
            listOf("shake_head-1.png", "shake_head-2.png")))
        registerMode(ImageMode(ROLL, "roll-6.png",
            listOf("roll-1.png", "roll-2.png", "roll-3.png", "roll-4.png", "roll-5.png", "roll-6.png"), loop = true))
        registerMode(ImageMode(SIT_CLAM, "sit_clam-1.png",
            listOf("sit_clam-1.png", "sit_clam-2.png", "sit_clam-3.png"), loop = true))
        registerMode(ImageMode(SIT_PUFFED, "sit_puffed-1.png",
            listOf("sit_puffed-1.png", "sit_puffed-2.png", "sit_puffed-3.png", "sit_puffed-4.png"), loop = true))
        registerMode(ImageMode(WALK, "walk-1.png",
            listOf("walk-1.png", "walk-2.png", "walk-3.png"), loop = true))
        registerMode(ImageMode(WRIGGLE, "sit_clam-1.png",
            listOf("wriggle-1.png", "wriggle-2.png"), loop = true))
        currentSeq = modes[DEFAULT_MODE]!!
    }

    private fun registerMode(mode: ImageMode) {
        modes[mode.name] = FrameSequence(mode.baseFrame, mode.otherFrames, mode.loop)
    }

    fun getBitmap(frameName: String): Bitmap? {
        cache[frameName]?.let { return it }
        return try {
            context.assets.open("img/$frameName").use { stream ->
                val bmp = BitmapFactory.decodeStream(stream)
                if (bmp != null) cache[frameName] = bmp
                bmp
            }
        } catch (e: IOException) {
            null
        }
    }

    /** 切换常驻模式（如 walk / sit_clam 循环） */
    fun setMode(name: String) {
        if (forcedSeq != null) return  // 强制动画播放中，忽略
        modes[name]?.let {
            currentName = name
            currentSeq = it
        }
    }

    fun getMode(): String = currentName

    /** 播放一次性动画，结束后自动回到当前常驻模式的 base */
    fun playOnce(name: String) {
        modes[name]?.let { forcedSeq = FrameSequence(it.baseFrame, it.otherFrames, false) }
    }

    /** 每帧推进，返回当前应绘制的 Bitmap（可能为 null 表示无变化维持旧帧） */
    fun nextFrame(): Bitmap? {
        val seq = forcedSeq ?: currentSeq
        val frameName = seq.feed()
        if (forcedSeq != null && forcedSeq!!.finishedOnce()) {
            forcedSeq = null
        }
        return getBitmap(frameName)
    }

    fun currentBitmap(): Bitmap? = getBitmap((forcedSeq ?: currentSeq).current())

    /** 释放缓存（省内存，对应 PC 端无此需求，手机需控制） */
    fun clearCache() {
        cache.values.forEach { it.recycle() }
        cache.clear()
    }
}
