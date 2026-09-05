package com.cx.cakepet

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 阈值配置层：独立于 pet_config，保存每个设置页里「可阈值化项」的
 * 上下界(from/to)、步进(step)与默认值(default)。
 *
 * 三层语义：
 *  - 基础款(Base)：代码内置、未启用阈值时各设置页滑块逻辑里硬编码的范围（如吸附 0~600）。
 *    从 bounds 读取时即为 from/to 的默认值，也是设置页滑块的初始行程范围。
 *  - 极限款(Hard)：用户允许输入到的上限（见需求极值列表，如 0.1~30）。
 *    阈值页输入框只校验 hard 范围，基础款之外的极值仅作输入边界、不作为默认行程。
 *  - 用户默认(User)：用户在阈值页改的 from/to/step/default，存本 DataStore。
 *    无用户值时回落基础款；设置页「恢复默认设置」使用之。
 *    阈值页「恢复系统默认设置」按钮清空用户值，回到基础款（并恢复阈值调整本身）。
 *
 * UI 映射约定：滑块内部用 0..100 归一化，业务范围由 bounds 决定（见 SliderHelper.applyBizRange）。
 */

/** 设置页标识，用于区分各自的阈值清单与入口。 */
enum class SettingsPage(val tag: String) {
    MAIN("main"),
    SNAP("snap"),
    RANGE("range"),
    THINKING("thinking"),
    RANDOM("random");

    companion object {
        fun fromTag(tag: String): SettingsPage =
            entries.firstOrNull { it.tag == tag } ?: MAIN
    }
}

/** 单滑块阈值项（如大小、透明度、偏移、吸附距离等）。 */
data class SliderBound(
    val key: String,
    val label: String,
    val group: String,
    val hardMin: Float,
    val hardMax: Float,
    val baseMin: Float,
    val baseMax: Float,
    val from: Float,
    val to: Float,
    val step: Float,
    val default: Float
) {
    /** 未设置用户值时，from/to 回落基础款。 */
    fun withUser(uFrom: Float?, uTo: Float?, uStep: Float?, uDefault: Float?): SliderBound =
        copy(
            from = uFrom ?: baseMin,
            to = uTo ?: baseMax,
            step = uStep ?: step,
            default = uDefault ?: default
        )
}

/** 双滑块阈值项（一个值含两个独立子值，如随机周期 min/max、碎碎念空白 min/max）。 */
data class DualSliderBound(
    val key: String,
    val label: String,
    val group: String,
    val hardMin: Float,
    val hardMax: Float,
    val baseMinA: Float, val baseMaxA: Float,
    val baseMinB: Float, val baseMaxB: Float,
    val fromA: Float, val toA: Float, val stepA: Float, val defaultA: Float,
    val fromB: Float, val toB: Float, val stepB: Float, val defaultB: Float
) {
    fun withUser(
        uFromA: Float?, uToA: Float?, uStepA: Float?, uDefA: Float?,
        uFromB: Float?, uToB: Float?, uStepB: Float?, uDefB: Float?
    ): DualSliderBound = copy(
        fromA = uFromA ?: baseMinA, toA = uToA ?: baseMaxA,
        stepA = uStepA ?: stepA, defaultA = uDefA ?: defaultA,
        fromB = uFromB ?: baseMinB, toB = uToB ?: baseMaxB,
        stepB = uStepB ?: stepB, defaultB = uDefB ?: defaultB
    )
}

/** 开关阈值项（仅默认 bool）。 */
data class SwitchBound(
    val key: String,
    val label: String,
    val group: String,
    val default: Boolean
)

/** 一个设置页的完整阈值清单。
 *  [order] 为该页所有项（slider/dual/switch）的显示顺序（按 key），
 *  严格对齐其设置页的视觉顺序；render() 据此决定模块顺序与组内顺序。 */
data class PageBounds(
    val sliders: List<SliderBound> = emptyList(),
    val duals: List<DualSliderBound> = emptyList(),
    val switches: List<SwitchBound> = emptyList(),
    val colors: List<ColorBound> = emptyList(),
    val order: List<String> = emptyList()
) {
    /**
     * 规范化：保证 from<=to（旧版本可能把「最小>最大」写进了 DataStore，
     * 导致子设置页用其设置 Slider 的 valueFrom/valueTo 时 valueFrom>valueTo，
     * Material Slider 会直接抛 IllegalArgumentException 而闪退）。
     * 若发现 from>to 则交换，并把默认 clamp 进 [from,to]。
     */
    fun normalize(): PageBounds {
        val sliders = sliders.map { s ->
            val (f, t) = if (s.from <= s.to) s.from to s.to else s.to to s.from
            s.copy(from = f, to = t, default = s.default.coerceIn(f, t))
        }
        val duals = duals.map { d ->
            // 组内先各自保证 from<=to
            val aLo = minOf(d.fromA, d.toA); val aHi = maxOf(d.fromA, d.toA)
            val bLo = minOf(d.fromB, d.toB); val bHi = maxOf(d.fromB, d.toB)
            // 跨组：仅保证 A 组下限不超过 B 组上限（fromA <= toB），
            // 子设置页用 valueFrom=fromA / valueTo=toB 设置 RangeSlider，绝不能 valueFrom>valueTo。
            // 注意：不交换 A/B 两组身份（A 始终代表 min 端语义），只在边界上做轻量钳制，
            // 避免破坏用户对「A 组 = 最小值区间」的语义预期。
            val fa = aLo
            val ta = aHi.coerceAtLeast(aLo)
            val fb = bLo.coerceAtLeast(fa)   // 防止 fromA > fromB 导致整段跨界
            val tb = bHi.coerceAtLeast(fb)
            d.copy(
                fromA = fa, toA = ta, defaultA = d.defaultA.coerceIn(fa, ta),
                fromB = fb, toB = tb, defaultB = d.defaultB.coerceIn(fb, tb)
            )
        }
        return copy(sliders = sliders, duals = duals)
    }
}

private val Context.boundsStore by preferencesDataStore(name = "pet_bounds")

class PetBounds(private val context: Context) {

    // ===== 系统默认（基础款 + 极限款） =====
    // 所有字面量已集中到 AppDefaults.AppBounds，本处仅装配引用。
    private fun systemPage(page: SettingsPage): PageBounds = when (page) {
        SettingsPage.MAIN -> PageBounds(
            sliders = AppBounds.MAIN_SLIDERS,
            switches = AppBounds.MAIN_SWITCHES,
            order = AppBounds.MAIN_ORDER
        )
        SettingsPage.SNAP -> PageBounds(
            sliders = AppBounds.SNAP_SLIDERS,
            switches = AppBounds.SNAP_SWITCHES,
            order = AppBounds.SNAP_ORDER
        )
        SettingsPage.RANGE -> PageBounds(
            sliders = AppBounds.RANGE_SLIDERS,
            switches = AppBounds.RANGE_SWITCHES,
            order = AppBounds.RANGE_ORDER
        )
        SettingsPage.THINKING -> PageBounds(
            sliders = AppBounds.THINKING_SLIDERS,
            duals = AppBounds.THINKING_DUALS,
            switches = AppBounds.THINKING_SWITCHES,
            colors = AppBounds.THINKING_COLORS,
            order = AppBounds.THINKING_ORDER
        )
        SettingsPage.RANDOM -> PageBounds(
            duals = AppBounds.RANDOM_DUALS,
            switches = AppBounds.RANDOM_SWITCHES,
            order = AppBounds.RANDOM_ORDER
        )
    }

    /** 系统默认清单（不可变，用于「恢复系统默认设置」）。 */
    fun systemItems(page: SettingsPage): PageBounds = systemPage(page)

    // ===== 用户默认值（持久化） =====
    private fun fk(page: SettingsPage, key: String, field: String) =
        floatPreferencesKey("${page.tag}_${key}_$field")
    private fun bk(page: SettingsPage, key: String, field: String) =
        booleanPreferencesKey("${page.tag}_${key}_$field")
    private fun ck(page: SettingsPage, key: String) =
        intPreferencesKey("${page.tag}_${key}_default")

    /** 读取某页的当前（用户）阈值清单；未设置的字段回落基础款。 */
    fun boundsFlow(page: SettingsPage): Flow<PageBounds> = context.boundsStore.data.map { prefs ->
        val sys = systemPage(page)
        PageBounds(
            sliders = sys.sliders.map { s ->
                s.withUser(
                    prefs[fk(page, s.key, "from")],
                    prefs[fk(page, s.key, "to")],
                    prefs[fk(page, s.key, "step")],
                    prefs[fk(page, s.key, "default")]
                )
            },
            duals = sys.duals.map { d ->
                d.withUser(
                    prefs[fk(page, d.key, "fromA")], prefs[fk(page, d.key, "toA")],
                    prefs[fk(page, d.key, "stepA")], prefs[fk(page, d.key, "defaultA")],
                    prefs[fk(page, d.key, "fromB")], prefs[fk(page, d.key, "toB")],
                    prefs[fk(page, d.key, "stepB")], prefs[fk(page, d.key, "defaultB")]
                )
            },
            switches = sys.switches.map { sw ->
                // 开关阈值项为 live 配置（存 pet_config），不持久化进 pet_bounds：
                // 始终用 AppDefaults 定义的 default 作为渲染/重置的工厂值，避免与 live 双存储互相覆盖。
                sw
            },
            colors = sys.colors.map { c ->
                c.copy(default = prefs[ck(page, c.key)] ?: c.default)
            },
            order = sys.order
        ).normalize()
    }

    /** 写回某页的用户阈值（全量覆盖传入的清单）。 */
    suspend fun updateBounds(page: SettingsPage, bounds: PageBounds) {
        context.boundsStore.edit { prefs ->
            bounds.sliders.forEach { s ->
                prefs[fk(page, s.key, "from")] = s.from
                prefs[fk(page, s.key, "to")] = s.to
                prefs[fk(page, s.key, "step")] = s.step
                prefs[fk(page, s.key, "default")] = s.default
            }
            bounds.duals.forEach { d ->
                prefs[fk(page, d.key, "fromA")] = d.fromA
                prefs[fk(page, d.key, "toA")] = d.toA
                prefs[fk(page, d.key, "stepA")] = d.stepA
                prefs[fk(page, d.key, "defaultA")] = d.defaultA
                prefs[fk(page, d.key, "fromB")] = d.fromB
                prefs[fk(page, d.key, "toB")] = d.toB
                prefs[fk(page, d.key, "stepB")] = d.stepB
                prefs[fk(page, d.key, "defaultB")] = d.defaultB
            }
            // 开关阈值项不持久化进 pet_bounds（只存 live，见 boundsFlow 注释），此处跳过。
            bounds.colors.forEach { c ->
                prefs[ck(page, c.key)] = c.default
            }
        }
    }

    /** 恢复系统默认：清空某页所有用户阈值键（next 读取即回落基础款），
     *  并恢复阈值调整本身（即 bounds 回到系统出厂）。 */
    suspend fun resetSystem(page: SettingsPage) {
        val sys = systemPage(page)
        val keys = mutableListOf<String>()
        sys.sliders.forEach { s ->
            keys += listOf("${page.tag}_${s.key}_from", "${page.tag}_${s.key}_to",
                "${page.tag}_${s.key}_step", "${page.tag}_${s.key}_default")
        }
        sys.duals.forEach { d ->
            listOf("fromA","toA","stepA","defaultA","fromB","toB","stepB","defaultB").forEach {
                keys += "${page.tag}_${d.key}_$it"
            }
        }
        sys.switches.forEach { sw ->
            keys += "${page.tag}_${sw.key}_default"
        }
        sys.colors.forEach { c ->
            keys += "${page.tag}_${c.key}_default"
        }
        context.boundsStore.edit { prefs ->
            keys.forEach { k ->
                prefs.remove(floatPreferencesKey(k))
                prefs.remove(booleanPreferencesKey(k))
                prefs.remove(intPreferencesKey(k))
            }
        }
    }

    /** 阻塞读取（供非协程初始化滑块范围用）。 */
    fun getBlocking(page: SettingsPage): PageBounds {
        return try {
            kotlinx.coroutines.runBlocking { boundsFlow(page).first() }
        } catch (e: Exception) {
            systemPage(page).normalize()
        }
    }
}
