package com.cx.cakepet

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 全局配置（对应 PC 端 configs.py / config.yaml）。
 * 使用 DataStore 持久化，浮窗 Service 实时读取。
 */
data class PetConfigData(
    val scale: Float = 1.4f,          // 大小系数（对应 PC scale）
    val gravity: Float = 3000f,       // 重力强度（像素/秒^2，对应 PC gravity）
    val reboundRatio: Float = 0.7f,   // 反弹系数（对应 PC rebound_ratio）
    val walkSpeed: Float = 200f,      // 游走速度（对应 PC walk）
    val followSpeed: Float = 400f,    // 拖动尾随速度（安卓新增，替代鼠标跟随）
    // 四边重力方向开关（安卓新增需求）
    val gravityTop: Boolean = false,
    val gravityBottom: Boolean = true,
    val gravityLeft: Boolean = false,
    val gravityRight: Boolean = false,
    // 四边弹力开关（独立可选，默认全开）
    val reboundTop: Boolean = true,
    val reboundBottom: Boolean = true,
    val reboundLeft: Boolean = true,
    val reboundRight: Boolean = true,
    // 四边吸附开关（独立可选）：拖拽松手时若速度低且靠近该边，则吸附成探头常驻态。
    // 默认：上下左右全开。
    val snapTop: Boolean = true,
    val snapBottom: Boolean = true,
    val snapLeft: Boolean = true,
    val snapRight: Boolean = true,
    // 吸附判定阈值（像素）：拖拽松手时距某边 < snapThreshold 且速度低才吸附。默认 100。
    val snapThreshold: Float = 100f,
    // 重力/抛掷总开关：关闭后拖动即静止，不受拖动速度与重力影响（类似 PC）
    val gravityEnabled: Boolean = true,
    // 体感重力（倾斜重力）：开启后由手机重力传感器决定重力方向（关闭四边定向重力，但保留边界反弹）。
    // 重力强度仍复用 gravity 字段；倾斜越大，等效重力越大。
    val tiltGravity: Boolean = false,
    // 速度最大值（像素/秒），范围 0-20000
    val maxSpeed: Float = 7100f,
    // 四边边界偏移（像素）：正=向屏内收缩（宠物离屏边有间距），负=允许超出屏边，范围 -200~300
    val offsetTop: Float = 140f,
    val offsetBottom: Float = 140f,
    val offsetLeft: Float = 0f,
    val offsetRight: Float = 0f,
    val alpha: Float = 0.7f,          // 宠物整体透明度，范围 0.1 - 1
    val clickThrough: Boolean = false, // 点击穿透：开启后窗口不接收触摸事件，事件穿透到下层
    val bounceVibrate: Boolean = true,  // 边界反弹时轻微振动（默认开）
    val enabled: Boolean = true,      // 浮窗总开关
    val showDebug: Boolean = false,   // 屏幕左下调试信息
    val showRect: Boolean = false,    // pet 叠加红色矩形调试层
    val visible: Boolean = true,      // 宠物显示/隐藏（独立于浮窗总开关）
    val thinkingEnabled: Boolean = true, // 碎碎念：屏幕底部居中随机轮播文字
    val thinkingOffset: Float = 0f,   // 碎碎念 Y 偏移（px）：不受底部偏移影响，正=向下
    // ===== 随机模式 =====
    val randomEnabled: Boolean = false,        // 随机模式总开关
    // 参与随机的项（位掩码，见 RANDOM_ 常量），默认全开（除禁随机项外）
    val randomItems: Int = RandomItemFlags.ALL,
    val randomPeriodMin: Int = 30,             // 随机周期最小值（分钟），默认 30
    val randomPeriodMax: Int = 120             // 随机周期最大值（分钟），默认 120
)

/**
 * 随机模式可随机项的位掩码集合。
 * 禁随机项（点击穿透 / 显示隐藏 / 四边吸附 / 反弹振动）不在此列。
 *
 * 四边重力为四个独立开关（GDIR_*），生成时遵循上下互斥、左右互斥。
 * 四边反弹为四个独立开关（RBOUND_*），默认不开启随机。
 */
object RandomItemFlags {
    const val SCALE = 1 shl 0            // 大小
    const val ALPHA = 1 shl 1            // 透明度
    const val GRAVITY_ENABLED = 1 shl 2  // 重力·抛掷总开关
    const val TILT_GRAVITY = 1 shl 3     // 体感重力
    const val MAX_SPEED = 1 shl 4        // 最大速度
    const val GRAVITY = 1 shl 5          // 重力强度
    const val REBOUND = 1 shl 6          // 反弹系数
    const val GDIR_V = 1 shl 7           // 四边重力·上下（每次随机出单一纵向方向）
    const val GDIR_H = 1 shl 8           // 四边重力·左右（每次随机出单一横向方向）
    const val RBOUND_TOP = 1 shl 11      // 四边反弹·上
    const val RBOUND_BOTTOM = 1 shl 12   // 四边反弹·下
    const val RBOUND_LEFT = 1 shl 13     // 四边反弹·左
    const val RBOUND_RIGHT = 1 shl 14     // 四边反弹·右

    // 默认开启除四边反弹外的所有随机项
    const val ALL = SCALE or ALPHA or GRAVITY_ENABLED or TILT_GRAVITY or MAX_SPEED or
            GRAVITY or REBOUND or GDIR_V or GDIR_H
    // 四边反弹默认关闭随机
    const val RBOUND_ALL = RBOUND_TOP or RBOUND_BOTTOM or RBOUND_LEFT or RBOUND_RIGHT
}

private val Context.dataStore by preferencesDataStore(name = "pet_config")

class PetConfig(private val context: Context) {

    private val KEY_SCALE = floatPreferencesKey("scale")
    private val KEY_GRAVITY = floatPreferencesKey("gravity")
    private val KEY_REBOUND = floatPreferencesKey("rebound_ratio")
    private val KEY_WALK = floatPreferencesKey("walk_speed")
    private val KEY_FOLLOW = floatPreferencesKey("follow_speed")
    private val KEY_TOP = booleanPreferencesKey("gravity_top")
    private val KEY_BOTTOM = booleanPreferencesKey("gravity_bottom")
    private val KEY_LEFT = booleanPreferencesKey("gravity_left")
    private val KEY_RIGHT = booleanPreferencesKey("gravity_right")
    private val KEY_RTOP = booleanPreferencesKey("rebound_top")
    private val KEY_RBOTTOM = booleanPreferencesKey("rebound_bottom")
    private val KEY_RLEFT = booleanPreferencesKey("rebound_left")
    private val KEY_RRIGHT = booleanPreferencesKey("rebound_right")
    private val KEY_STOP = booleanPreferencesKey("snap_top")
    private val KEY_SBOTTOM = booleanPreferencesKey("snap_bottom")
    private val KEY_SLEFT = booleanPreferencesKey("snap_left")
    private val KEY_SRIGHT = booleanPreferencesKey("snap_right")
    private val KEY_SNAP_THRESHOLD = floatPreferencesKey("snap_threshold")
    private val KEY_GRAVITY_ENABLED = booleanPreferencesKey("gravity_enabled")
    private val KEY_TILT_GRAVITY = booleanPreferencesKey("tilt_gravity")
    private val KEY_MAXSPEED = floatPreferencesKey("max_speed")
    private val KEY_OFF_TOP = floatPreferencesKey("offset_top")
    private val KEY_OFF_BOTTOM = floatPreferencesKey("offset_bottom")
    private val KEY_OFF_LEFT = floatPreferencesKey("offset_left")
    private val KEY_OFF_RIGHT = floatPreferencesKey("offset_right")
    private val KEY_ENABLED = booleanPreferencesKey("enabled")
    private val KEY_DEBUG = booleanPreferencesKey("show_debug")
    private val KEY_RECT = booleanPreferencesKey("show_rect")
    private val KEY_VISIBLE = booleanPreferencesKey("visible")
    private val KEY_ALPHA = floatPreferencesKey("alpha")
    private val KEY_CLICK_THROUGH = booleanPreferencesKey("click_through")
    private val KEY_BOUNCE_VIBRATE = booleanPreferencesKey("bounce_vibrate")
    private val KEY_THINKING_ENABLED = booleanPreferencesKey("thinking_enabled")
    private val KEY_THINKING_OFFSET = floatPreferencesKey("thinking_offset")
    private val KEY_RANDOM_ENABLED = booleanPreferencesKey("random_enabled")
    private val KEY_RANDOM_ITEMS = androidx.datastore.preferences.core.intPreferencesKey("random_items_v5")
    private val KEY_RANDOM_PERIOD_MIN = androidx.datastore.preferences.core.intPreferencesKey("random_period_min")
    private val KEY_RANDOM_PERIOD_MAX = androidx.datastore.preferences.core.intPreferencesKey("random_period_max")

    val configFlow: Flow<PetConfigData> = context.dataStore.data.map { prefs ->
        PetConfigData(
            scale = prefs[KEY_SCALE] ?: 1.4f,
            gravity = prefs[KEY_GRAVITY] ?: 3000f,
            reboundRatio = prefs[KEY_REBOUND] ?: 0.7f,
            walkSpeed = prefs[KEY_WALK] ?: 200f,
            followSpeed = prefs[KEY_FOLLOW] ?: 400f,
            gravityTop = prefs[KEY_TOP] ?: false,
            gravityBottom = prefs[KEY_BOTTOM] ?: true,
            gravityLeft = prefs[KEY_LEFT] ?: false,
            gravityRight = prefs[KEY_RIGHT] ?: false,
            reboundTop = prefs[KEY_RTOP] ?: true,
            reboundBottom = prefs[KEY_RBOTTOM] ?: true,
            reboundLeft = prefs[KEY_RLEFT] ?: true,
            reboundRight = prefs[KEY_RRIGHT] ?: true,
            snapTop = prefs[KEY_STOP] ?: true,
            snapBottom = prefs[KEY_SBOTTOM] ?: true,
            snapLeft = prefs[KEY_SLEFT] ?: true,
            snapRight = prefs[KEY_SRIGHT] ?: true,
            snapThreshold = prefs[KEY_SNAP_THRESHOLD] ?: 100f,
            gravityEnabled = prefs[KEY_GRAVITY_ENABLED] ?: true,
            // 体感重力默认关闭：开启后会自动关闭四边定向重力（见 PetService 处理）。
            tiltGravity = prefs[KEY_TILT_GRAVITY] ?: false,
            maxSpeed = prefs[KEY_MAXSPEED] ?: 7100f,
            offsetTop = prefs[KEY_OFF_TOP] ?: 140f,
            offsetBottom = prefs[KEY_OFF_BOTTOM] ?: 140f,
            offsetLeft = prefs[KEY_OFF_LEFT] ?: 0f,
            offsetRight = prefs[KEY_OFF_RIGHT] ?: 0f,
            enabled = prefs[KEY_ENABLED] ?: true,
            showDebug = prefs[KEY_DEBUG] ?: false,
            showRect = prefs[KEY_RECT] ?: false,
            visible = prefs[KEY_VISIBLE] ?: true,
            alpha = (prefs[KEY_ALPHA] ?: 0.7f).coerceIn(0.1f, 1f),
            clickThrough = prefs[KEY_CLICK_THROUGH] ?: false,
            bounceVibrate = prefs[KEY_BOUNCE_VIBRATE] ?: true,
            thinkingEnabled = prefs[KEY_THINKING_ENABLED] ?: true,
            thinkingOffset = prefs[KEY_THINKING_OFFSET] ?: 0f,
            randomEnabled = prefs[KEY_RANDOM_ENABLED] ?: false,
            randomItems = prefs[KEY_RANDOM_ITEMS] ?: RandomItemFlags.ALL,
            randomPeriodMin = prefs[KEY_RANDOM_PERIOD_MIN] ?: 30,
            randomPeriodMax = prefs[KEY_RANDOM_PERIOD_MAX] ?: 120
        )
    }

    // 清洗已存在的非法旧数据：找出 DataStore 中可能残留的“左右同开/上下同开”组合并修正回写。
    // 仅当确实存在非法组合时才写盘（避免无谓 IO）。在配置加载入口调用一次即可。
    suspend fun normalize() {
        val cur = configFlow.first()
        if ((cur.gravityLeft && cur.gravityRight) || (cur.gravityTop && cur.gravityBottom)) {
            update { it } // 复用 update 内的规范化逻辑完成回写
        }
    }

    // 阻塞版清洗，供非协程上下文（如 Service.onCreate 中的 loadConfig）调用。
    fun normalizeBlocking() {
        try {
            kotlinx.coroutines.runBlocking { normalize() }
        } catch (e: Exception) {
            // 清洗失败不影响启动
        }
    }

    suspend fun update(block: (PetConfigData) -> PetConfigData) {
        val current = configFlow.first()
        // 规范化四边定向重力：左右不能同时开、上下不能同时开（避免两套重力叠加成非法组合）。
        // 规则：若左右同时为 true，则保留右侧(true)、关闭左侧；上下同时为 true，则保留下侧、关闭上侧。
        // 这样无论调用方如何写值，最终落入 DataStore 的永远是唯一合法组合，杜绝“莫名多重力源”。
        val raw = block(current)
        val normalized = raw.copy(
            gravityLeft = raw.gravityLeft && !raw.gravityRight,
            gravityRight = raw.gravityRight,
            gravityTop = raw.gravityTop && !raw.gravityBottom,
            gravityBottom = raw.gravityBottom
        )
        val updated = normalized
        context.dataStore.edit { prefs ->
            prefs[KEY_SCALE] = updated.scale
            prefs[KEY_GRAVITY] = updated.gravity
            prefs[KEY_REBOUND] = updated.reboundRatio
            prefs[KEY_WALK] = updated.walkSpeed
            prefs[KEY_FOLLOW] = updated.followSpeed
            prefs[KEY_TOP] = updated.gravityTop
            prefs[KEY_BOTTOM] = updated.gravityBottom
            prefs[KEY_LEFT] = updated.gravityLeft
            prefs[KEY_RIGHT] = updated.gravityRight
            prefs[KEY_RTOP] = updated.reboundTop
            prefs[KEY_RBOTTOM] = updated.reboundBottom
            prefs[KEY_RLEFT] = updated.reboundLeft
            prefs[KEY_RRIGHT] = updated.reboundRight
            prefs[KEY_STOP] = updated.snapTop
            prefs[KEY_SBOTTOM] = updated.snapBottom
            prefs[KEY_SLEFT] = updated.snapLeft
            prefs[KEY_SRIGHT] = updated.snapRight
            prefs[KEY_SNAP_THRESHOLD] = updated.snapThreshold
            prefs[KEY_GRAVITY_ENABLED] = updated.gravityEnabled
            prefs[KEY_TILT_GRAVITY] = updated.tiltGravity
            prefs[KEY_MAXSPEED] = updated.maxSpeed
            prefs[KEY_OFF_TOP] = updated.offsetTop
            prefs[KEY_OFF_BOTTOM] = updated.offsetBottom
            prefs[KEY_OFF_LEFT] = updated.offsetLeft
            prefs[KEY_OFF_RIGHT] = updated.offsetRight
            prefs[KEY_ENABLED] = updated.enabled
            prefs[KEY_DEBUG] = updated.showDebug
            prefs[KEY_RECT] = updated.showRect
            prefs[KEY_VISIBLE] = updated.visible
            prefs[KEY_ALPHA] = updated.alpha
            prefs[KEY_CLICK_THROUGH] = updated.clickThrough
            prefs[KEY_BOUNCE_VIBRATE] = updated.bounceVibrate
            prefs[KEY_THINKING_ENABLED] = updated.thinkingEnabled
            prefs[KEY_THINKING_OFFSET] = updated.thinkingOffset
            prefs[KEY_RANDOM_ENABLED] = updated.randomEnabled
            prefs[KEY_RANDOM_ITEMS] = updated.randomItems
            prefs[KEY_RANDOM_PERIOD_MIN] = updated.randomPeriodMin
            prefs[KEY_RANDOM_PERIOD_MAX] = updated.randomPeriodMax
        }
    }

    // 阻塞式读取初始值（Service 启动时用）
    fun getBlocking(): PetConfigData {
        return try {
            kotlinx.coroutines.runBlocking { configFlow.first() }
        } catch (e: Exception) {
            PetConfigData()
        }
    }

    // 恢复默认设置：直接把 data class 的真实默认值写回 DataStore，
    // 不依赖 configFlow 的「?: 回退值」，彻底避免两处默认值不同步导致恢复出错
    // （例如体感重力、底部偏移等曾被回退值覆盖）。写盘即生效，下次启动也一致。
    suspend fun reset() {
        update { PetConfigData() }
    }
}
