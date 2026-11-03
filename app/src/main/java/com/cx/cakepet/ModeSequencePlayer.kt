package com.cx.cakepet

import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * 通用 Mode 序列的播放数据。Mode 不区分角色、食物或其它对象；
 * 播放者只推进帧/内部动画链，物理位移是否执行由持有播放者的引擎对象决定。
 */
data class SequenceFrame(
    val res: String,
    val next: Int?,
    val minDelay: Long,
    val maxDelay: Long,
    val anchorX: Int,
    val anchorY: Int?,
    val dx: Int,
    val dy: Int
)

data class SequenceTimeNext(val min: Long, val max: Long, val probability: Float)

data class ModeSequence(
    val name: String,
    val frames: List<SequenceFrame>,
    val loop: Boolean,
    val nextMode: String?,
    val timeNext: SequenceTimeNext?,
    val walkSpeed: Float,
    val fallback: String,
    val verticalAlign: String
)

class ModeSequenceLibrary private constructor(val modes: Map<String, ModeSequence>) {
    companion object {
        fun parse(root: JSONObject): ModeSequenceLibrary {
            val out = LinkedHashMap<String, ModeSequence>()
            val definitions = root.getJSONObject("modes")
            val keys = definitions.keys()
            while (keys.hasNext()) {
                val name = keys.next()
                val obj = definitions.getJSONObject(name)
                val array = obj.getJSONArray("frames")
                val frames = (0 until array.length()).map { index ->
                    val frame = array.getJSONObject(index)
                    val delay = frame.get("delay")
                    val minimum: Long
                    val maximum: Long
                    if (delay is JSONArray) {
                        minimum = delay.getLong(0)
                        maximum = delay.getLong(1)
                    } else {
                        minimum = (delay as Number).toLong()
                        maximum = minimum
                    }
                    SequenceFrame(
                        frame.getString("res"),
                        if (frame.has("next") && !frame.isNull("next")) frame.getInt("next") else null,
                        minimum.coerceAtLeast(1),
                        maximum.coerceAtLeast(minimum.coerceAtLeast(1)),
                        frame.optInt("anchorX", -1),
                        if (frame.has("anchorY")) frame.getInt("anchorY") else null,
                        frame.optInt("dx", 0),
                        frame.optInt("dy", 0)
                    )
                }
                val tn = obj.optJSONObject("timeNext")
                val timeNext = if (tn?.optBoolean("enabled", false) == true) {
                    val low = tn.optLong("min", 3000L).coerceAtLeast(1L)
                    val high = tn.optLong("max", 4000L).coerceAtLeast(low)
                    SequenceTimeNext(low, high, tn.optDouble("prob", 0.5).toFloat().coerceIn(0f, 1f))
                } else null
                out[name] = ModeSequence(
                    name, frames, obj.optBoolean("loop", false),
                    obj.optString("nextMode", "").takeIf { it.isNotEmpty() && it != "null" },
                    timeNext, obj.optDouble("walkSpeed", 0.0).toFloat(),
                    obj.optString("fallback", ""), obj.optString("verticalAlign", "top")
                )
            }
            return ModeSequenceLibrary(out)
        }
    }

    /** 只追踪动画自身的 nextMode；绝不跳入角色待机池。 */
    fun reachableFrom(start: String): List<ModeSequence> {
        val seen = LinkedHashMap<String, ModeSequence>()
        var next: String? = start
        while (next != null && next !in seen) {
            val mode = modes[next] ?: break
            seen[next] = mode
            next = mode.nextMode
        }
        return seen.values.toList()
    }
}

data class SequenceAdvance(val changed: Boolean, val dx: Int = 0, val dy: Int = 0)

/** 每个显示对象独立持有，不同食物/角色不会共享播放位置和时钟。 */
class ModeSequencePlayer(
    private val library: ModeSequenceLibrary,
    startMode: String,
    private val random: Random = Random.Default
) {
    var mode: ModeSequence = requireNotNull(library.modes[startMode]) { "Unknown Mode: $startMode" }
        private set
    var frameIndex: Int = 0
        private set
    private var frameElapsed = 0L
    private var frameDelay = sampleFrameDelay()
    private var timeNextElapsed = 0L
    private var timeNextDelay = sampleTimeNextDelay()
    private var completed = false

    val currentFrame: SequenceFrame get() = mode.frames[frameIndex]
    val walkSpeed: Float get() = mode.walkSpeed

    private fun sampleDelay(min: Long, max: Long): Long =
        if (min >= max) min else random.nextLong(min, max + 1)

    private fun sampleFrameDelay(): Long = mode.frames.getOrNull(frameIndex)?.let {
        sampleDelay(it.minDelay, it.maxDelay)
    } ?: 100L

    private fun sampleTimeNextDelay(): Long = mode.timeNext?.let {
        sampleDelay(it.min, it.max)
    } ?: Long.MAX_VALUE

    private fun switchMode(name: String): Boolean {
        val next = library.modes[name] ?: return false
        mode = next
        frameIndex = 0
        frameElapsed = 0L
        frameDelay = sampleFrameDelay()
        timeNextElapsed = 0L
        timeNextDelay = sampleTimeNextDelay()
        completed = false
        return true
    }

    fun advance(dtMs: Long): SequenceAdvance {
        if (completed || dtMs <= 0) return SequenceAdvance(false)
        var changed = false
        var dx = 0
        var dy = 0
        var remaining = dtMs
        var guard = 0
        while (remaining > 0 && guard++ < 256 && !completed) {
            val need = (frameDelay - frameElapsed).coerceAtLeast(1L)
            val elapsed = minOf(need, remaining)
            frameElapsed += elapsed
            timeNextElapsed += elapsed
            remaining -= elapsed

            if (frameElapsed >= frameDelay) {
                val frame = currentFrame
                val next = frame.next
                if (next != null && next in mode.frames.indices) {
                    frameIndex = next
                    changed = true
                } else if (mode.loop) {
                    frameIndex = 0
                    changed = true
                } else if (mode.nextMode != null && switchMode(mode.nextMode!!)) {
                    changed = true
                } else {
                    completed = true // 停留在最后一帧；对象生命期归引擎管理
                }
                if (!completed) {
                    if (next != null || mode.loop) frameElapsed = 0L
                    frameDelay = sampleFrameDelay()
                    if (changed) {
                        dx += currentFrame.dx
                        dy += currentFrame.dy
                    }
                }
            }
            val timed = mode.timeNext
            if (!completed && timed != null && timeNextElapsed >= timeNextDelay) {
                timeNextElapsed = 0L
                timeNextDelay = sampleTimeNextDelay()
                if (random.nextFloat() < timed.probability && mode.nextMode != null) {
                    changed = switchMode(mode.nextMode!!) || changed
                }
            }
        }
        return SequenceAdvance(changed, dx, dy)
    }
}
