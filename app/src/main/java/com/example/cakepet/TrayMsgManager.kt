package com.example.cakepet

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import kotlin.random.Random

/**
 * 碎碎念：屏幕底部居中的独立浮窗 TextView，水平居中、6dp 字体、不受宠物旋转/缩放/底部偏移影响。
 * 轮播状态机：
 *   - 每条文字显示后，有 70% 概率下一个进入“空状态”（持续随机 2-10s 不显示任何文字），
 *     然后再指定一条有文字的；30% 概率直接显示下一条有文字的。反复随机。
 *   - 空状态期间不显示文本，但仍占用该浮窗（位置不变）。
 * 文本池给定 11 条（PHILOSOPHY1-6 / MIAO1-2 / OHU1-2 / PURR），每条带显示时长参数
 * （base_ms + 随机 random_ms）；随机抽（含重复可能，避免用完即停）。
 */
class TrayMsgManager(private val context: Context) {

    companion object {
        // 文本池（碎碎念），含每条显示时长参数
        private data class TrayMsgMeta(val text: String, val base_ms: Long = 0, val random_ms: Long)

        private val MSG_POOL = listOf(
            TrayMsgMeta(text = "你盯着屏幕，而我盯着你，谁又在盯着我们呢？", base_ms = 500, random_ms = 3500),
            TrayMsgMeta(text = "存在大于本质，但小鱼干先大于一切", base_ms = 500, random_ms = 3500),
            TrayMsgMeta(text = "我是谁？我从哪里来？要到哪里去？", base_ms = 500, random_ms = 3500),
            TrayMsgMeta(text = "喵生三大事：吃饭，睡觉", base_ms = 500, random_ms = 3500),
            TrayMsgMeta(text = "如果桌面是无限的，它的尽头在哪里？", base_ms = 500, random_ms = 3500),
            TrayMsgMeta(text = "今天想通了一个道理，有些道理是想不通的", base_ms = 500, random_ms = 3500),
            TrayMsgMeta(text = "（此处应有哲学）", base_ms = 500, random_ms = 3500),
            TrayMsgMeta(text = "我刚刚想到了一个绝妙的计划，但现在已经忘了", base_ms = 500, random_ms = 3500),
            TrayMsgMeta(text = "我决定把今天剩下的时间都用来发呆，这是经过深思熟虑的", base_ms = 500, random_ms = 3500),
            TrayMsgMeta(text = "你忙你的，我……我也忙我的", base_ms = 500, random_ms = 3500),
            TrayMsgMeta(text = "为什么圆的总是比方的更值得拍？", base_ms = 500, random_ms = 3500),
            TrayMsgMeta(text = "闭上眼睛的时候，耳朵就变得特别忙", base_ms = 500, random_ms = 3500),
            TrayMsgMeta(text = "我决定什么都不决定", base_ms = 500, random_ms = 3500),
            TrayMsgMeta(text = "等。等。等。等到了。什么？", base_ms = 500, random_ms = 3500),
            TrayMsgMeta(text = "我刚才觉得自己是一个球，滚来滚去的那种", base_ms = 500, random_ms = 3500),
            TrayMsgMeta(text = "zzzZZZ...", base_ms = 500, random_ms = 5500),
            TrayMsgMeta(text = "喵？！？", base_ms = 1000, random_ms = 3500),
            TrayMsgMeta(text = "喵！", base_ms = 1000, random_ms = 3500),
            TrayMsgMeta(text = "哦呜~ 哦呜~", base_ms = 1000, random_ms = 3500),
            TrayMsgMeta(text = "哦呜~", base_ms = 1000, random_ms = 3500),
            TrayMsgMeta(text = "咕噜咕咕噜...", base_ms = 1000, random_ms = 3500),
            TrayMsgMeta(text = "呼噜……呼噜……", base_ms = 1000, random_ms = 3500),
            TrayMsgMeta(text = "嗯？", base_ms = 1000, random_ms = 3500),
            TrayMsgMeta(text = "……哦。", base_ms = 1000, random_ms = 3500),
            TrayMsgMeta(text = "咕。", base_ms = 1000, random_ms = 3500),
            TrayMsgMeta(text = "喵呜。", base_ms = 1000, random_ms = 3500),
            TrayMsgMeta(text = "哈——", base_ms = 1000, random_ms = 3500),
            TrayMsgMeta(text = "咔。", base_ms = 1000, random_ms = 3500),
            TrayMsgMeta(text = "......", base_ms = 1000, random_ms = 3500),
            TrayMsgMeta(text = "……", base_ms = 1000, random_ms = 3500),
            TrayMsgMeta(text = "嗯。", base_ms = 1000, random_ms = 3500),
            TrayMsgMeta(text = "哦。", base_ms = 1000, random_ms = 3500),
            TrayMsgMeta(text = "……？", base_ms = 1000, random_ms = 3500),
            TrayMsgMeta(text = "呼。", base_ms = 1000, random_ms = 3500),
            TrayMsgMeta(text = "唔。", base_ms = 1000, random_ms = 3500),
            TrayMsgMeta(text = "……啊。", base_ms = 1000, random_ms = 3500),
            TrayMsgMeta(text = "(✘_✘)", base_ms = 1000, random_ms = 3500),
            TrayMsgMeta(text = "(=°Д°=)", base_ms = 1000, random_ms = 3500),
            TrayMsgMeta(text = "(◦`~´◦)", base_ms = 1000, random_ms = 3500),
            TrayMsgMeta(text = "(=^‥^=)", base_ms = 1000, random_ms = 3500),
            TrayMsgMeta(text = "(⋟﹏⋞)", base_ms = 1000, random_ms = 3500)

        )

        // 空状态持续时长范围（ms）：每条有文字之后必定进入空状态，2-15s 随机
        private const val EMPTY_MIN_MS = 2000L
        private const val EMPTY_MAX_MS = 15000L

        // 启动后第一条固定文案（程序打开/碎碎念打开时）
        private const val FIRST_TEXT = "喵？！？"
        private const val FIRST_SHOW_MS = 2000L

        // 摸头（双击）文案：随机显示一条，1-3s 后继续轮播池
        private val PAT_MSGS = listOf("喵喵喵", "喵" ,"喵！","喵？！？",
            "~o( =∩ω∩= )m","(=^‥^=)",
            "（｀へ´）","（▼へ▼メ）","(╬◣д◢)")
        private const val PAT_MIN_MS = 1000L
        private const val PAT_MAX_MS = 3000L

        // 提起（拖拽中）文案：持续显示同一条，直到 clearLift()
        private val LIFT_MSGS = listOf("喵？...", "喵？喵？喵？", "喵？！？")

        // 落地（拖拽结束/被放下）文案：随机一条，1-3s 后进入空白再继续轮播池
        private val LAND_MSGS = listOf("喵？.....", "咕噜咕噜....", "(⋟﹏⋞)", "(✘_✘)")
        private const val LAND_MIN_MS = 1000L
        private const val LAND_MAX_MS = 2000L
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val textView: android.widget.TextView
    private val params: WindowManager.LayoutParams

    @Volatile
    private var enabled = false

    // 轮播状态机：true=当前在显示有文字，false=当前为空状态
    @Volatile
    private var showingText = false

    // 覆盖态：true 时碎碎念由外部事件（摸头/提起）指定，轮播循环暂停。
    @Volatile
    private var overrideActive = false

    // 退场溶解特效进行中：true 时碎碎念正播放“文字消失”过渡，期间屏蔽轮播新文案写入。
    @Volatile
    private var dissolving = false

    // 退场特效：随机字符池（ASCII + 符号 + 空格，空格加权，营造科技噪点感）
    private val NOISE_POOL = buildString {
        append(" ".repeat(4)) // 空格加权，溶解时更“虚”
        append("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789")
        append("!@#\$%^&*<>/?\\|=+~-_.")
    }
    private fun noiseChar(): Char = NOISE_POOL[Random.nextInt(NOISE_POOL.length)]

    // 风格 D 空格溶解：汉字占两个空格、半角/数字/字母/符号占一个空格
    private fun spaceNoiseChar(ch: Char): String = if (ch.isHighSurrogate() || ch.code > 0x2E7F) "  " else " "

    // 风格 F 黑框溶解：全黑方块
    private val BLOCK_CHAR = '█'

    // Y 偏移（碎碎念偏移），正=向下，负=向上
    private var offsetY = 0f

    private val handler = Handler(Looper.getMainLooper())
    private var pendingRunnable: Runnable? = null
    // 退场特效当前排程的 Runnable（用于 cancelDissolve 精确取消，避免 stopLoop 移除后 dissolving 卡死）
    private var dissolveRunnable: Runnable? = null

    // 显形特效进行中：true 时碎碎念正播放“空白→有文字”过渡，与 dissolving 互斥。
    @Volatile
    private var materializing = false

    // 显形特效当前排程的 Runnable
    private var materializeRunnable: Runnable? = null

    // 取消正在进行的显形/退场特效并复位标志（两者互斥，统一清除避免卡死轮播）。
    private fun cancelDissolve() {
        dissolveRunnable?.let { handler.removeCallbacks(it) }
        dissolveRunnable = null
        dissolving = false
    }

    private fun cancelMaterialize() {
        materializeRunnable?.let { handler.removeCallbacks(it) }
        materializeRunnable = null
        materializing = false
    }

    private val rotateRunnable = object : Runnable {
        override fun run() {
            if (!enabled || overrideActive) return
            if (dissolving) {
                // 退场特效进行中：不再自我排程，等 dissolve 末帧自行结束（会复位 dissolving 并继续轮播）
                return
            }
            if (materializing) {
                // 显形特效进行中：不再自我排程，等 materialize 末帧自行结束（会复位 materializing 并继续）
                return
            }
            if (showingText) {
                // 当前这一条已显示足够久 -> 播放退场特效后进入空状态（持续随机 2-60s），再指定下一条有文字
                showingText = false
                dissolveThenClear {
                    textView.visibility = android.view.View.INVISIBLE
                    val emptyMs = EMPTY_MIN_MS + Random.nextLong(0, EMPTY_MAX_MS - EMPTY_MIN_MS + 1)
                    scheduleNext(emptyMs)
                }
            } else {
                // 空状态结束 -> 指定一条有文字的（一定非空）
                showRandomText()
            }
        }
    }

    init {
        textView = android.widget.TextView(context).apply {
            setTextColor(0xFF333333.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, 10f) // 6dp 字体
            setShadowLayer(2f, 1f, 1f, 0xCCFFFFFF.toInt())
            gravity = android.view.Gravity.CENTER
            visibility = android.view.View.INVISIBLE
        }
        params = WindowManager.LayoutParams().apply {
            type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            this.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
        }
        windowManager.addView(textView, params)
        // 初始放到屏幕底部中心，稍高于底边（不受底部偏移影响，仅受碎碎念偏移影响）
        reposition()
    }

    private fun showRandomText() {
        val meta = MSG_POOL[Random.nextInt(MSG_POOL.size)]
        showingText = true
        // 空白→有文字：先播显形特效（噪点凝聚/黑框退散等），定格后进入显示计时
        materializeThenShow(meta.text) {
            val showMs = meta.base_ms + Random.nextLong(0, meta.random_ms + 1)
            scheduleNext(showMs)
        }
    }

    // 首次启动：显示固定的第一条（喵？！？），直接出现（避免每次开设置页重放显形）
    private fun startFirst() {
        showingText = true
        textView.text = FIRST_TEXT
        textView.visibility = android.view.View.VISIBLE
        scheduleNext(FIRST_SHOW_MS)
    }

    // 显示一条外部指定的文本（覆盖态使用），先播显形特效，不进入轮播计时。
    private fun showOverrideText(text: String) {
        showingText = true
        materializeThenShow(text) { }
    }

    private fun reposition() {
        // 紧贴屏幕底部，水平居中；仅受 offsetY（碎碎念偏移）影响 Y。
        // Gravity.BOTTOM 下 y 为负表示离底边的距离；正 offsetY 想下移 -> y 取负。
        params.x = 0
        params.y = (-offsetY).toInt()
        try {
            windowManager.updateViewLayout(textView, params)
        } catch (_: Exception) {
        }
    }

    private fun scheduleNext(delayMs: Long) {
        pendingRunnable?.let { handler.removeCallbacks(it) }
        pendingRunnable = rotateRunnable
        handler.postDelayed(rotateRunnable, delayMs)
    }

    /**
     * 文字退场特效：在当前文字消失前，随机选用一种过渡，全部结束后回调 onCleared。
     * 比例：A 渐进噪点溶解 20% / B 闪烁噪点 30% / C 直接消失 10% /
     *      D 空格溶解 20% / F 黑框溶解 20%。
     * 特效期间 dissolving=true，屏蔽轮播/覆盖写入，避免被打断。
     */
    private fun dissolveThenClear(onCleared: () -> Unit) {
        val original = textView.text?.toString() ?: ""
        if (original.isEmpty()) {
            onCleared()
            return
        }
        dissolving = true
        textView.visibility = android.view.View.VISIBLE
        val style = Random.nextInt(100)
        when {
            style < 20 -> dissolveStyleA(original, onCleared)   // A 渐进噪点溶解 20%
            style < 50 -> dissolveStyleB(original, onCleared)   // B 闪烁噪点 30%
            style < 60 -> {                                     // C 直接消失 10%
                dissolving = false
                onCleared()
            }
            style < 80 -> dissolveStyleD(original, onCleared)   // D 空格溶解 20%
            else -> dissolveStyleF(original, onCleared)         // F 黑框溶解 20%
        }
    }

    // 风格 A：逐帧递增替换比例，字符被噪点“吞没”
    private fun dissolveStyleA(original: String, onCleared: () -> Unit) {
        val len = original.length
        val frames = 5
        val stepMs = 55L
        var frame = 0
        val runnable = object : Runnable {
            override fun run() {
                frame++
                val ratio = frame.toFloat() / (frames + 1)  // 0..<1，逐帧递增
                val sb = StringBuilder(len)
                for (i in 0 until len) {
                    // 越靠后帧，越靠前位置的字符也越可能被替换
                    sb.append(if (Random.nextFloat() < ratio) noiseChar() else original[i])
                }
                textView.text = sb.toString()
                if (frame < frames) {
                    handler.postDelayed(this, stepMs)
                } else {
                    // 末帧：整串纯随机噪点，短暂停留后清空
                    textView.text = buildString(len) { repeat(len) { append(noiseChar()) } }
                    handler.postDelayed({
                        cancelDissolve()
                        onCleared()
                    }, stepMs)
                }
            }
        }
        dissolveRunnable = runnable
        handler.postDelayed(runnable, stepMs)
    }

    // 风格 B：保留骨架，部分字符随机闪烁跳动
    private fun dissolveStyleB(original: String, onCleared: () -> Unit) {
        val len = original.length
        val frames = 5
        val stepMs = 55L
        var frame = 0
        val runnable = object : Runnable {
            override fun run() {
                frame++
                val sb = StringBuilder(len)
                for (i in 0 until len) {
                    // 约 40% 位置跳成噪点，其余保留原字符（骨架可见）
                    sb.append(if (Random.nextFloat() < 0.4f) noiseChar() else original[i])
                }
                textView.text = sb.toString()
                if (frame < frames) {
                    handler.postDelayed(this, stepMs)
                } else {
                    // 末帧原样停留极短后清空
                    textView.text = original
                    handler.postDelayed({
                        cancelDissolve()
                        onCleared()
                    }, stepMs)
                }
            }
        }
        dissolveRunnable = runnable
        handler.postDelayed(runnable, stepMs)
    }

    // 风格 D：空格溶解——逐帧递增把字符替换为空格（汉字占两空格，半角占一空格），末帧整串空格后清空
    private fun dissolveStyleD(original: String, onCleared: () -> Unit) {
        val frames = 5
        val stepMs = 55L
        var frame = 0
        val runnable = object : Runnable {
            override fun run() {
                frame++
                val ratio = frame.toFloat() / (frames + 1)  // 0..<1，逐帧递增
                val sb = StringBuilder()
                for (i in original.indices) {
                    // 越靠后帧，越多字符被“消”成空格（保持视觉长度：汉字两空格、半角一空格）
                    if (Random.nextFloat() < ratio) sb.append(spaceNoiseChar(original[i]))
                    else sb.append(original[i])
                }
                textView.text = sb.toString()
                if (frame < frames) {
                    handler.postDelayed(this, stepMs)
                } else {
                    // 末帧：整串纯空格（汉字位置两空格），短暂停留后清空
                    val blank = buildString { for (ch in original) append(spaceNoiseChar(ch)) }
                    textView.text = blank
                    handler.postDelayed({
                        cancelDissolve()
                        onCleared()
                    }, stepMs)
                }
            }
        }
        dissolveRunnable = runnable
        handler.postDelayed(runnable, stepMs)
    }

    // 风格 F：黑框溶解——约 40% 位置逐帧替换为全黑方块█，其余保留原字符，末帧整串黑框后清空
    private fun dissolveStyleF(original: String, onCleared: () -> Unit) {
        val len = original.length
        val frames = 5
        val stepMs = 55L
        var frame = 0
        val runnable = object : Runnable {
            override fun run() {
                frame++
                val sb = StringBuilder(len)
                for (i in 0 until len) {
                    // 约 40% 位置跳成黑框，其余保留原字符（骨架可见）
                    sb.append(if (Random.nextFloat() < 0.4f) BLOCK_CHAR else original[i])
                }
                textView.text = sb.toString()
                if (frame < frames) {
                    handler.postDelayed(this, stepMs)
                } else {
                    // 末帧整串黑框，短暂停留后清空
                    textView.text = BLOCK_CHAR.toString().repeat(len)
                    handler.postDelayed({
                        cancelDissolve()
                        onCleared()
                    }, stepMs)
                }
            }
        }
        dissolveRunnable = runnable
        handler.postDelayed(runnable, stepMs)
    }

    /**
     * 文字显形特效（空白→有文字）：与退场 dissolve 对称。按五风格随机过渡后定格 target 文字。
     * 比例：A' 噪点凝聚 20% / B' 闪烁显形 30% / C' 直接出现 10% / D' 空格凝聚 20% / F' 黑框退散 20%。
     * 特效期间 materializing=true（与 dissolving 互斥），屏蔽轮播/覆盖写入。
     */
    private fun materializeThenShow(target: String, onShown: () -> Unit) {
        if (target.isEmpty()) {
            onShown()
            return
        }
        materializing = true
        dissolving = false        // 互斥：显形开始时若有退场在跑则中止
        cancelDissolve()
        textView.visibility = android.view.View.VISIBLE
        val style = Random.nextInt(100)
        when {
            style < 20 -> materializeStyleA(target, onShown)  // A' 噪点凝聚 20%
            style < 50 -> materializeStyleB(target, onShown)  // B' 闪烁显形 30%
            style < 60 -> {                                    // C' 直接出现 10%
                materializing = false
                textView.text = target
                onShown()
            }
            style < 80 -> materializeStyleD(target, onShown)  // D' 空格凝聚 20%
            else -> materializeStyleF(target, onShown)        // F' 黑框退散 20%
        }
    }

    // A' 噪点凝聚：起始整串噪点，逐帧把越来越少的字符替换为真实目标字符，最终定格原文字
    private fun materializeStyleA(target: String, onShown: () -> Unit) {
        val len = target.length
        val frames = 5
        val stepMs = 55L
        var frame = 0
        val runnable = object : Runnable {
            override fun run() {
                frame++
                // 真实字符比例随帧递增（与退场反向）
                val keep = frame.toFloat() / (frames + 1)
                val sb = StringBuilder(len)
                for (i in 0 until len) {
                    sb.append(if (Random.nextFloat() < keep) target[i] else noiseChar())
                }
                textView.text = sb.toString()
                if (frame < frames) {
                    handler.postDelayed(this, stepMs)
                } else {
                    textView.text = target
                    handler.postDelayed({
                        cancelMaterialize()
                        onShown()
                    }, stepMs)
                }
            }
        }
        materializeRunnable = runnable
        handler.postDelayed(runnable, stepMs)
    }

    // B' 闪烁显形：起始原文字但部分位置是噪点，逐帧减少噪点比例，最终纯原文字
    private fun materializeStyleB(target: String, onShown: () -> Unit) {
        val len = target.length
        val frames = 5
        val stepMs = 55L
        var frame = 0
        val runnable = object : Runnable {
            override fun run() {
                frame++
                val noiseRatio = 0.4f * (1f - frame.toFloat() / frames)  // 40% -> 0
                val sb = StringBuilder(len)
                for (i in 0 until len) {
                    sb.append(if (Random.nextFloat() < noiseRatio) noiseChar() else target[i])
                }
                textView.text = sb.toString()
                if (frame < frames) {
                    handler.postDelayed(this, stepMs)
                } else {
                    textView.text = target
                    handler.postDelayed({
                        cancelMaterialize()
                        onShown()
                    }, stepMs)
                }
            }
        }
        materializeRunnable = runnable
        handler.postDelayed(runnable, stepMs)
    }

    // D' 空格凝聚：起始整串空格（汉字两空格），逐帧把空格填回真实字符，最终定格原文字
    private fun materializeStyleD(target: String, onShown: () -> Unit) {
        val frames = 5
        val stepMs = 55L
        var frame = 0
        val runnable = object : Runnable {
            override fun run() {
                frame++
                val keep = frame.toFloat() / (frames + 1)
                val sb = StringBuilder()
                for (i in target.indices) {
                    sb.append(if (Random.nextFloat() < keep) target[i] else spaceNoiseChar(target[i]))
                }
                textView.text = sb.toString()
                if (frame < frames) {
                    handler.postDelayed(this, stepMs)
                } else {
                    textView.text = target
                    handler.postDelayed({
                        cancelMaterialize()
                        onShown()
                    }, stepMs)
                }
            }
        }
        materializeRunnable = runnable
        handler.postDelayed(runnable, stepMs)
    }

    // F' 黑框退散：起始整串█，逐帧把黑框替换为真实字符，最终定格原文字
    private fun materializeStyleF(target: String, onShown: () -> Unit) {
        val len = target.length
        val frames = 5
        val stepMs = 55L
        var frame = 0
        val runnable = object : Runnable {
            override fun run() {
                frame++
                val keep = frame.toFloat() / (frames + 1)
                val sb = StringBuilder(len)
                for (i in 0 until len) {
                    sb.append(if (Random.nextFloat() < keep) target[i] else BLOCK_CHAR)
                }
                textView.text = sb.toString()
                if (frame < frames) {
                    handler.postDelayed(this, stepMs)
                } else {
                    textView.text = target
                    handler.postDelayed({
                        cancelMaterialize()
                        onShown()
                    }, stepMs)
                }
            }
        }
        materializeRunnable = runnable
        handler.postDelayed(runnable, stepMs)
    }

    private fun stopLoop() {
        pendingRunnable?.let { handler.removeCallbacks(it) }
        pendingRunnable = null
        cancelDissolve()
        cancelMaterialize()
    }

    fun setEnabled(on: Boolean) {
        // 同值不重启：已启用又来一次 true（如其他配置项变更触发的 observer），
        // 仅刷新 enabled 标志，不重置轮播，避免“一条条显示”被重新触发。
        if (on == enabled) {
            enabled = on
            return
        }
        enabled = on
        overrideActive = false
        if (on) {
            // 启动轮播：首条固定“喵？！？”，之后进入空白再继续轮播池
            showingText = false
            textView.visibility = android.view.View.INVISIBLE
            textView.text = ""
            startFirst()
        } else {
            stopLoop()
            if (showingText || overrideActive) {
                showingText = false
                overrideActive = false
                dissolveThenClear {
                    textView.visibility = android.view.View.INVISIBLE
                }
            } else {
                textView.text = ""
                textView.visibility = android.view.View.INVISIBLE
            }
        }
    }

    /**
     * 覆盖结束后：进入一轮空状态（2-15s 随机），再继续轮播池的有文字。
     * 即“摸头/提起”文案之后也必定是空白，再出现下一条。
     */
    private fun resumeAfterEmpty() {
        showingText = false
        textView.text = ""
        textView.visibility = android.view.View.INVISIBLE
        val emptyMs = EMPTY_MIN_MS + Random.nextLong(0, EMPTY_MAX_MS - EMPTY_MIN_MS + 1)
        scheduleNext(emptyMs) // scheduleNext 内部注册 rotateRunnable（空状态结束 -> 有文字）
    }

    /**
     * 一次性覆盖：显示 texts 中随机一条，持续随机 [minMs, maxMs]，之后直接继续轮播池的有文字。
     * 用于“摸头”等短暂事件——摸头结束后下一个一定是文字（不先空白）。
     */
    fun showMomentary(texts: List<String>, minMs: Long, maxMs: Long) {
        if (!enabled) return
        stopLoop()
        overrideActive = true
        showOverrideText(texts[Random.nextInt(texts.size)])
        val dur = minMs + Random.nextLong(0, maxMs - minMs + 1)
        handler.postDelayed({
            overrideActive = false
            if (!enabled) return@postDelayed
            dissolveThenClear {
                if (enabled) showRandomText()
            }
        }, dur)
    }

    /**
     * 持续覆盖：显示 texts 中随机一条并保持，直到 clearOverride() 被调用才继续轮播池。
     * 用于“提起”（拖拽中）等持续事件——整个提起期间都显示同一类文案。
     */
    fun showHeld(texts: List<String>) {
        if (!enabled) return
        stopLoop()
        overrideActive = true
        showOverrideText(texts[Random.nextInt(texts.size)])
    }

    /** 结束持续覆盖：先播放退场特效，再进入空白继续轮播池 */
    fun clearOverride() {
        if (!overrideActive) return
        overrideActive = false
        if (enabled) {
            if (showingText || (textView.text?.isNotEmpty() == true)) {
                showingText = false
                dissolveThenClear { if (enabled) resumeAfterEmpty() }
            } else {
                resumeAfterEmpty()
            }
        }
    }

    /** 摸头（双击）：随机显示一条摸头文案，持续随机 1-3s 后继续轮播池 */
    fun showPat() {
        showMomentary(PAT_MSGS, PAT_MIN_MS, PAT_MAX_MS)
    }

    /** 提起（拖拽中）：持续显示一条提起文案，直到 clearLift() */
    fun showLift() {
        showHeld(LIFT_MSGS)
    }

    /** 结束提起：进入空白再继续轮播池 */
    fun clearLift() {
        clearOverride()
    }

    /** 落地（拖拽结束/被放下）：先结束提起态，随机显示一条落地文案 1-3s，之后进入空白再继续轮播池 */
    fun showLand() {
        if (!enabled) return
        stopLoop()
        overrideActive = true
        showOverrideText(LAND_MSGS[Random.nextInt(LAND_MSGS.size)])
        val dur = LAND_MIN_MS + Random.nextLong(0, LAND_MAX_MS - LAND_MIN_MS + 1)
        handler.postDelayed({
            overrideActive = false
            if (!enabled) return@postDelayed
            dissolveThenClear {
                if (enabled) resumeAfterEmpty()   // 落地文案后先空白，再继续轮播池
            }
        }, dur)
    }

    /** 随机模式触发时：显示“变变变”，持续 2s+(0~3s) 后播放退场特效并继续轮播池。 */
    fun showRandomTrigger() {
        if (!enabled) return
        stopLoop()
        overrideActive = true
        showOverrideText("变变变")
        val dur = 2000L + Random.nextLong(0, 3000L)
        handler.postDelayed({
            overrideActive = false
            if (!enabled) return@postDelayed
            dissolveThenClear {
                if (enabled) showRandomText()   // 变变变之后直接接下一条轮播文案
            }
        }, dur)
    }

    fun setOffsetY(offset: Float) {
        offsetY = offset
        reposition()
    }

    /** 跟随宠物整体可见性：隐藏时碎碎念也消失，显示时恢复（若启用） */
    fun setVisible(visible: Boolean) {
        if (!enabled) {
            textView.visibility = android.view.View.INVISIBLE
            return
        }
        textView.visibility = if (visible) {
            if (showingText || overrideActive) android.view.View.VISIBLE else android.view.View.INVISIBLE
        } else {
            android.view.View.INVISIBLE
        }
    }

    fun destroy() {
        stopLoop()
        try {
            windowManager.removeView(textView)
        } catch (_: Exception) {
        }
    }
}
