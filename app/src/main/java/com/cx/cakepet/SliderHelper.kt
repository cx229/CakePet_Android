package com.cx.cakepet

import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.slider.Slider

/**
 * Material Slider 的统一封装。
 *
 * 背景：项目原先使用原生 SeekBar，改用 Material Slider 后：
 *  1. 需要显式指定配色——SeekBar 靠主题 colorControlActivated 变黑，
 *     而 Material Slider 不吃该属性（默认走 colorPrimary 紫色），必须逐个设置。
 *  2. 需要在程序中 setValue 时不触发业务回调——Slider 的 addOnChangeListener
 *     对程序化 setValue 同样回调（fromUser=false），沿用 SeekBar 时代的守卫习惯。
 *  3. 浮动数值气泡默认显示原始 value，而多数滑块的 value 与真实业务值是换算关系，
 *     必须配合 setLabelFormatter，否则气泡数字与设置项实际值对不上。
 */

/** 统一黑色配色（与页面其它控件一致）；轨道未激活段用浅灰区分。 */
fun Slider.applyBlackStyle() {
    setThumbTintList(android.content.res.ColorStateList.valueOf(0xFF000000.toInt()))
    trackActiveTintList = android.content.res.ColorStateList.valueOf(0xFF000000.toInt())
    trackInactiveTintList = android.content.res.ColorStateList.valueOf(0xFFBDBDBD.toInt())
    // 按压光晕保留透明度，纯黑会遮挡轨道
    haloTintList = android.content.res.ColorStateList.valueOf(0x33000000)
    tickActiveTintList = android.content.res.ColorStateList.valueOf(0xFF000000.toInt())
    tickInactiveTintList = android.content.res.ColorStateList.valueOf(0xFFBDBDBD.toInt())
}

/**
 * 触摸落在 EditText 之外时，强制让当前焦点 EditText 失去焦点并收起软键盘。
 * 配合 EditSync.bind / 阈值页 onLostFocus 的「失焦即提交」逻辑，确保：
 *   - 点页面空白处、软键盘收起后焦点仍停留在输入框时，也能触发提交；
 *   - 覆盖「按返回键/点空白」这类 onFocusChange 未必触发的场景。
 * 在 Activity 的 dispatchTouchEvent 中首行调用即可。
 */
fun AppCompatActivity.commitInputOnTouchOutside(ev: MotionEvent) {
    if (ev.action != MotionEvent.ACTION_DOWN) return
    val f = currentFocus
    if (f is EditText) {
        val r = android.graphics.Rect()
        f.getGlobalVisibleRect(r)
        if (!r.contains(ev.rawX.toInt(), ev.rawY.toInt())) {
            f.clearFocus()
            (getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(f.windowToken, 0)
        }
    }
}

/**
 * 仅当用户拖动时回调（程序化 setValue 不回调），避免配置回写死循环。
 * 与 SeekBar 时代 `if (fromUser)` 的行为保持一致。
 */
fun Slider.onUserValueChange(block: (Float) -> Unit) {
    addOnChangeListener { _, value, fromUser ->
        if (fromUser) block(value)
    }
}

/**
 * 正在被用户拖动的滑块集合（弱引用，避免持有 Activity/View 造成泄漏）。
 * 用于区分「用户主动拖动」与「配置回写导致的程序化赋值」。
 */
private val trackingSliders =
    java.util.Collections.newSetFromMap(java.util.WeakHashMap<Slider, Boolean>())

/** 该滑块当前是否正被用户拖动（按下未抬起）。 */
val Slider.isUserTracking: Boolean
    get() = trackingSliders.contains(this)

/** 触摸起止回调：用于拖动开始时显示辅助线/预览、松手时隐藏，同时维护拖动状态。 */
fun Slider.onUserTouch(onStart: () -> Unit = {}, onStop: () -> Unit = {}) {
    addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
        override fun onStartTrackingTouch(slider: Slider) {
            trackingSliders.add(slider)
            onStart()
        }
        override fun onStopTrackingTouch(slider: Slider) {
            trackingSliders.remove(slider)
            onStop()
        }
    })
}

/** 仅为滑块挂上拖动状态跟踪（无额外业务回调）。用于不需要辅助线/预览的普通滑块。 */
fun Slider.trackTouchState() = onUserTouch()

/**
 * 边界偏移滑块：拖动时显示四边边界虚线辅助线，松手释放。
 *
 * 两个关键点：
 * 1. 每次值变化都要刷新辅助线——辅助线位置取自传入的即时值，
 *    只在按下时画一次的话线会停在旧位置不跟随手指。
 * 2. 通过 owner 引用计数释放——多指同时拖动多个滑块时，
 *    先松手的那个不能把别人还在用的辅助线一起关掉。
 *
 * @param owner 请求来源（通常为滑块自身），用于引用计数
 * @param side 该滑块对应哪条边，用于只更新对应边的即时值
 */
fun Slider.boundGuideSlider(
    owner: Any = this,
    side: BoundSide,
    bizFrom: Float = -200f,
    bizTo: Float = 300f,
    formatter: (Float) -> String = { it.toInt().toString() },
    onChange: (Float) -> Unit
) {
    // 重绑前清空旧监听器，避免 onResume 重复调用导致 addOnChangeListener 叠加
    clearOnChangeListeners()
    // 统一黑色配色（默认 Material Slider 是紫色，与页面其它黑色滑块不一致）
    applyBlackStyle()
    // 滑块内部固定 0..100 归一化，业务范围由 bizFrom/bizTo 决定（来自 PetBounds，阈值页可改）。
    valueFrom = 0f
    valueTo = 100f
    stepSize = 1f
    // 浮窗显示真实业务值，而非 0..100 的滑块百分比
    setLabelText { raw -> formatter(bizFrom + raw / 100f * (bizTo - bizFrom)) }
    onUserValueChange { v: Float ->
        // 把 0..100 滑块值换算回业务值
        val biz = bizFrom + v / 100f * (bizTo - bizFrom)
        onChange(biz)
        // 把手指当前值直接传给服务，绕开 DataStore 回流的 IO 延迟，保证辅助线跟手
        val svc = PetService.instance ?: return@onUserValueChange
        if (side == BoundSide.SNAP) {
            // 吸附阈值：业务值即阈值，绘制吸附范围框
            svc.showSnapGuide(owner, threshold = biz)
        } else {
            val o = biz
            // 边 -> 线索引（与 boundLines 顺序一致：0左/1右/2上/3下），用于高亮正在调整的备注
            val idx = when (side) {
                BoundSide.TOP -> 2
                BoundSide.BOTTOM -> 3
                BoundSide.LEFT -> 0
                BoundSide.RIGHT -> 1
                BoundSide.SNAP -> -1
            }
            when (side) {
                BoundSide.TOP -> svc.showBoundGuide(owner, top = o, activeSide = idx)
                BoundSide.BOTTOM -> svc.showBoundGuide(owner, bottom = o, activeSide = idx)
                BoundSide.LEFT -> svc.showBoundGuide(owner, left = o, activeSide = idx)
                BoundSide.RIGHT -> svc.showBoundGuide(owner, right = o, activeSide = idx)
                BoundSide.SNAP -> svc.showSnapGuide(owner, threshold = biz)
            }
        }
    }
    onUserTouch(
        onStart = {
            if (side == BoundSide.SNAP) PetService.instance?.showSnapGuide(owner)
            else {
                val idx = when (side) {
                    BoundSide.TOP -> 2
                    BoundSide.BOTTOM -> 3
                    BoundSide.LEFT -> 0
                    BoundSide.RIGHT -> 1
                    BoundSide.SNAP -> -1
                }
                PetService.instance?.showBoundGuide(owner, activeSide = idx)
            }
        },
        onStop = { PetService.instance?.releaseGuide(owner) }
    )
}

/** 边界偏移滑块对应的边 */
enum class BoundSide { TOP, BOTTOM, LEFT, RIGHT, SNAP }

/**
 * 碎碎念偏移滑块：拖动时显示碎碎念水平辅助线 + 预览文字，松手释放。
 * 同 boundGuideSlider，辅助线走即时值并采用引用计数。
 */
fun Slider.trayGuideSlider(
    owner: Any = this,
    onShowPreview: () -> Unit,
    onHidePreview: () -> Unit,
    bizFrom: Float = -200f,
    bizTo: Float = 300f,
    formatter: (Float) -> String = { it.toInt().toString() },
    onChange: (Float) -> Unit
) {
    // 统一黑色配色（默认 Material Slider 是紫色，与页面其它黑色滑块不一致）
    applyBlackStyle()
    // 滑块内部固定 0..100 归一化，业务范围由 bizFrom/bizTo 决定（来自 PetBounds，阈值页可改）。
    valueFrom = 0f
    valueTo = 100f
    stepSize = 1f
    // 浮窗显示真实业务值（碎碎念偏移量），而非 0..100 的滑块百分比
    setLabelText { raw -> formatter(bizFrom + raw / 100f * (bizTo - bizFrom)) }
    onUserValueChange { v: Float ->
        val biz = bizFrom + v / 100f * (bizTo - bizFrom)
        onChange(biz)
        PetService.instance?.showTrayGuide(owner, offset = biz)
    }
    onUserTouch(
        onStart = {
            PetService.instance?.showTrayGuide(owner)
            onShowPreview()
        },
        onStop = {
            PetService.instance?.releaseGuide(owner)
            onHidePreview()
        }
    )
}

/**
 * 由配置回写滑块位置，带「待确认值」保护。
 *
 * 背景：滑块 → 写盘 → DataStore → configFlow → applyConfig → 回写滑块，这条回路有延迟。
 * 为了让宠物在拖动时实时响应，必须每次回调都写盘；但写盘产生的发射会滞后于手指，
 * 松手后旧值仍会陆续回流，把滑块拉回拖动途中的位置（表现为松手后数值倒放几帧）。
 *
 * 解决办法：在接收端区分新旧——只有追上用户最后写入值的那一次才允许回写。
 * 这样既保留了「宠物实时更新」，又彻底消除滑块倒放。
 *
 * 注：仅「持续 collect configFlow」的页面需要 pending；只在 onCreate 读一次的页面
 * （如随机模式页、碎碎念页）不存在回流问题，用默认 null 即可。
 *
 * @param v 由配置值换算出的滑块目标值
 * @param pending 用户最后写入的滑块值（Int，未拖动/已追平为 null）
 * @return true 表示已追平（调用方可据此清空 pending）
 */
fun Slider.setValueFromConfig(v: Float, pending: Int? = null): Boolean {
    // 自愈：若记录为「拖动中」但视图实际已非按下态，说明 onStopTrackingTouch 漏触发
    // （多指场景下 ACTION_POINTER_UP 未正常派发等边界情况），此时清除残留状态，
    // 否则该滑块会被永久判定为拖动中、从此不再回写（死锁）。
    if (trackingSliders.contains(this) && !isPressed) trackingSliders.remove(this)
    // 手指仍按在该滑块上：任何回写都会让 thumb 脱离手指，直接跳过
    if (isUserTracking) return false
    // 尚未追平用户最后写入的值：本次发射是旧值，跳过回写（但宠物仍会用它更新，正是期望行为）。
    // 注意：比对必须复用 alignToStep（与赋值同一套舍入），不能用 toInt() 截断——
    // Float 换算常得出 29.999999 这类值，截断成 29 会与 pending(30) 永不相等，
    // 导致 pending 永远清不掉、该滑块从此不再回写（死锁）。
    if (pending != null && alignToStep(v).toInt() != pending) return false
    setValueSafe(v)
    return true
}

/** 设置气泡显示文本：把原始 value 换算成真实业务值的展示字符串。 */
fun Slider.setLabelText(formatter: (Float) -> String) {
    setLabelFormatter { value -> formatter(value) }
}

/**
 * 安全赋值：先钳制到 [valueFrom, valueTo]，再对齐到 stepSize 的整数倍。
 *
 * 必须这样处理的原因：Material Slider 在绘制时会执行 validateValues()，要求
 *  1) value 落在 [valueFrom, valueTo] 内；
 *  2) 当 stepSize > 0 时，(value - valueFrom) 必须是 stepSize 的整数倍。
 * 任一不满足即抛 IllegalStateException，表现为打开页面直接闪退。
 * 而业务值换算结果常常是小数（例如透明度 66.67、最大速度 35.5），
 * 与 stepSize=1.0 冲突，因此回填空配置前必须先对齐步长。
 */
fun Slider.setValueSafe(v: Float) {
    value = alignToStep(v)
}

/**
 * 把目标值钳制到 [valueFrom, valueTo] 并对齐到 stepSize 的整数倍。
 *
 * pending 比对必须复用本函数的结果：若比对用另一种舍入方式（例如 toInt() 截断），
 * 而赋值用 Math.round，两者会在浮点误差下得出不同结果，
 * 导致 pending 永远追不平、滑块从此不再回写（死锁）。
 */
fun Slider.alignToStep(v: Float): Float {
    if (v.isNaN() || v.isInfinite()) return valueFrom
    val from = valueFrom
    val to = valueTo
    val step = stepSize
    var out = v.coerceIn(from, to)
    if (step > 0f) {
        val steps = Math.round((out - from) / step)
        out = (from + steps * step).coerceIn(from, to)
    }
    return out
}

/**
 * 以「业务范围」驱动滑块：滑块内部仍用 0..100 归一化，
 * 但显示文本与回写值都按 [bizFrom, bizTo] 换算。
 * 这样阈值页修改上下界后，滑块行程不变、只改变它代表的业务值范围，
 * 从而让阈值调整真正生效，且不影响既有 0..100 的辅助线/预览映射。
 *
 * @param bizFrom   业务下界（来自 PetBounds）
 * @param bizTo     业务上界
 * @param currentBiz 当前业务值（用于初始化滑块位置）
 * @param onChange  回写：把换算后的业务值交给调用方
 */
fun Slider.applyBizRange(
    bizFrom: Float,
    bizTo: Float,
    currentBiz: Float,
    formatter: (Float) -> String,
    onChange: (Float) -> Unit
) {
    // 重绑前清空旧监听器，避免 onResume 重复调用导致 addOnChangeListener 叠加
    clearOnChangeListeners()
    // 统一黑色配色（默认 Material Slider 是紫色，与页面其它黑色滑块不一致）
    applyBlackStyle()
    valueFrom = 0f
    valueTo = 100f
    stepSize = 1f
    val span = (bizTo - bizFrom).let { if (it == 0f) 1f else it }
    val init = ((currentBiz - bizFrom) / span * 100f).coerceIn(0f, 100f)
    setValueSafe(init)
    setLabelText { raw -> formatter(bizFrom + raw / 100f * (bizTo - bizFrom)) }
    onUserValueChange { v ->
        onChange(bizFrom + v / 100f * (bizTo - bizFrom))
    }
}

/** 把业务值换算成 0..100 的滑块值（供 applyConfig 回写使用）。
 *  子设置页的滑块统一 [valueFrom,valueTo]=[0,100]、stepSize=1，
 *  Material Slider 在设置 value 时要求 (value-valueFrom) 是 stepSize 整数倍，
 *  否则 validateValues() 抛 IllegalStateException 导致打开页面即闪退。
 *  故这里直接 round 成整数返回，等价于 setValueSafe 的对齐效果。 */
fun bizToSliderValue(biz: Float, bizFrom: Float, bizTo: Float): Float {
    val span = (bizTo - bizFrom).let { if (it == 0f) 1f else it }
    return kotlin.math.round(((biz - bizFrom) / span * 100f).coerceIn(0f, 100f))
}

/**
 * 滑块数值标签 ↔ 输入框的双向同步工具。
 *
 * 需求：原本滑块旁的数值是只读 TextView（滑块拖动时同步更新文本）；
 * 现改为可编辑 EditText，要求「滑块拖动 → 输入框文本同步」「输入框输入 → 滑块位置与配置同步」。
 *
 * 关键难点是死循环：输入框文本被滑块/配置回填时，会触发 TextWatcher.afterTextChanged，
 * 若里面再去回写配置，又会引起滑块/配置变化，再回填文本……无限循环。
 *
 * 解决办法：
 *  1) [setText] 在程序回填前给 EditText 打 tag=SUPPRESS，watcher 检测到则直接 return，不回写；
 *  2) [bind] 的 afterTextChanged 仅在「用户真实输入（tag 非 SUPPRESS）」时解析并回调。
 * 回调里用 slider.value=...（fromUser=false，不触发用户 onChange）更新滑块，再写配置。
 */
object EditSync {
    const val SUPPRESS = "suppress"

    /** 程序回填文本（滑块拖动 / config 回流时调用），标记 SUPPRESS 跳过 watcher 回写。 */
    fun setText(et: EditText, text: String) {
        et.tag = SUPPRESS
        et.setText(text)
        // 光标移到末尾，体验更自然；空文本时 setSelection(0) 安全
        try { et.setSelection(text.length) } catch (_: Exception) {}
        et.tag = null
    }

    /**
     * 绑定输入框：用户真实输入（非 SUPPRESS）时仅暂存文本，
     * 在「焦点离开」或「点完成键(actionDone)」时才解析并提交，避免连续输入被打断。
     * 提交时会先 [clamp] 校验到合法范围，并把输入框校正为 [format] 后的合法值，
     * 避免输入超界值（如 10000000000）残留。
     * @param parse 文本 → 解析结果（泛型 T，可空）；解析失败（空/非法）返回 null。
     * @param format 合法结果 → 显示文本（用于回填输入框）。默认 toString。
     * @param clamp 合法结果 → 钳制后的合法结果。默认恒等。
     * @param defaultValue 当解析结果为 null（如输入为空/非法）时使用的默认值；
     *                     提供后，空输入会被回填为该默认值并提交，而非保留空。
     * @param onValue 合法（已钳制）结果回调（调用方负责更新滑块 + 写配置）。
     */
    fun <T> bind(
        et: EditText,
        parse: (String) -> T?,
        format: (T) -> String = { it.toString() },
        clamp: (T) -> T = { it },
        defaultValue: T? = null,
        onValue: (T) -> Unit
    ) {
        val commit: () -> Unit = {
            if (et.tag != SUPPRESS) {
                val raw = parse(et.text?.toString() ?: "")
                val resolved = raw ?: defaultValue
                if (resolved != null) {
                    val vc = clamp(resolved)
                    EditSync.setText(et, format(vc)) // 立即校正输入框显示
                    onValue(vc)
                }
            }
        }
        // 失去焦点时提交（点击别处 / 输入法收起）
        et.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) commit()
        }
        // 软键盘「完成」键提交
        et.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                et.clearFocus()
                commit()
                true
            } else false
        }
    }
}
