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
// 注意：所有字段默认值已集中到 AppDefaults.ConfigDefaults（唯一真相源），本处仅引用。
data class PetConfigData(
    val scale: Float = ConfigDefaults.SCALE,          // 大小系数（对应 PC 端 scale）
    val gravity: Float = ConfigDefaults.GRAVITY,       // 重力强度（像素/秒^2，对应 PC gravity）
    val reboundRatio: Float = ConfigDefaults.REBOUND_RATIO,   // 反弹系数（对应 PC rebound_ratio）
    val walkSpeed: Float = ConfigDefaults.WALK_SPEED,      // 游走速度（对应 PC walk）
    val followSpeed: Float = ConfigDefaults.FOLLOW_SPEED,    // 拖动尾随速度（安卓新增，替代鼠标跟随）
    // 四边重力方向开关（安卓新增需求）
    val gravityTop: Boolean = ConfigDefaults.GRAVITY_TOP,
    val gravityBottom: Boolean = ConfigDefaults.GRAVITY_BOTTOM,
    val gravityLeft: Boolean = ConfigDefaults.GRAVITY_LEFT,
    val gravityRight: Boolean = ConfigDefaults.GRAVITY_RIGHT,
    // 四边弹力开关（独立可选，默认全开）
    val reboundTop: Boolean = ConfigDefaults.REBOUND_TOP,
    val reboundBottom: Boolean = ConfigDefaults.REBOUND_BOTTOM,
    val reboundLeft: Boolean = ConfigDefaults.REBOUND_LEFT,
    val reboundRight: Boolean = ConfigDefaults.REBOUND_RIGHT,
    // 反弹总开关：关闭后所有碰撞反弹失效（撞墙直接停靠，不反弹、不振动），四边反弹开关与反弹系数均不生效。默认开。
    val reboundEnabled: Boolean = ConfigDefaults.REBOUND_ENABLED,
    // 吸附边缘总开关：关闭后整体禁用吸附（四边吸附开关与阈值均不生效）。默认开。
    val snapEnabled: Boolean = ConfigDefaults.SNAP_ENABLED,
    // 四边吸附开关（独立可选）：拖拽松手时若速度低且靠近该边，则吸附成探头常驻态。
    // 默认：上下左右全开。
    val snapTop: Boolean = ConfigDefaults.SNAP_TOP,
    val snapBottom: Boolean = ConfigDefaults.SNAP_BOTTOM,
    val snapLeft: Boolean = ConfigDefaults.SNAP_LEFT,
    val snapRight: Boolean = ConfigDefaults.SNAP_RIGHT,
    // 吸附判定阈值（像素）：拖拽松手时距某边 < snapThreshold 且速度低才吸附。默认 100。
    val snapThreshold: Float = ConfigDefaults.SNAP_THRESHOLD,
    // 显示吸附线：处于吸附态时，在图片“地面（锚点）”位置画一条灰色细线（宽度=宠物宽、随大小变化、高度固定）。
    // 仅在吸附态生效，默认开。
    val showSnapLine: Boolean = ConfigDefaults.SHOW_SNAP_LINE,
    // 重力/抛掷总开关：关闭后拖动即静止，不受拖动速度与重力影响（类似 PC）
    val gravityEnabled: Boolean = ConfigDefaults.GRAVITY_ENABLED,
    // 体感重力（倾斜重力）：开启后由手机重力传感器决定重力方向（关闭四边定向重力，但保留边界反弹）。
    // 重力强度仍复用 gravity 字段；倾斜越大，等效重力越大。
    val tiltGravity: Boolean = ConfigDefaults.TILT_GRAVITY,
    // 速度最大值（像素/秒），范围 0-20000
    val maxSpeed: Float = ConfigDefaults.MAX_SPEED,
    // 四边边界偏移（像素）：正=向屏内收缩（宠物离屏边有间距），负=允许超出屏边。
    // 上下范围 -300~1600、默认 140；左右范围 -300~500、默认 0。
    val offsetTop: Float = ConfigDefaults.OFFSET_TOP,
    val offsetBottom: Float = ConfigDefaults.OFFSET_BOTTOM,
    val offsetLeft: Float = ConfigDefaults.OFFSET_LEFT,
    val offsetRight: Float = ConfigDefaults.OFFSET_RIGHT,
    val alpha: Float = ConfigDefaults.ALPHA,          // 宠物整体透明度，范围 0.1 - 1
    val clickThrough: Boolean = ConfigDefaults.CLICK_THROUGH, // 点击穿透：开启后窗口不接收触摸事件，事件穿透到下层
    val bounceVibrate: Boolean = ConfigDefaults.BOUNCE_VIBRATE,  // 边界反弹时轻微振动（默认开）
    val enabled: Boolean = ConfigDefaults.ENABLED,      // 浮窗总开关
    val showDebug: Boolean = ConfigDefaults.SHOW_DEBUG,   // 屏幕左下调试信息
    // 调试边框拆成两个独立开关：
    // 显示边框 = 图片窗口黑细线 + 脚锚点十字；控制边框 = 命中/action 彩色调试框（贴实际控制窗）
    val showImageBorder: Boolean = ConfigDefaults.SHOW_IMAGE_BORDER,
    val showControlBorder: Boolean = ConfigDefaults.SHOW_CONTROL_BORDER,
    // 命中模式：像素/边界/核心(脚底盒)，见 ConfigDefaults.HIT_*
    val hitMode: Int = ConfigDefaults.HIT_MODE,
    // 核心(脚底盒)命中区尺寸：128 逻辑基数（1X=128），几何计算时 × scaleFactor（宠物大小）转屏幕像素
    val ctrlBoxWidth: Float = ConfigDefaults.CTRL_BOX_WIDTH,      // 宽：脚左右各半
    val ctrlBoxHeight: Float = ConfigDefaults.CTRL_BOX_HEIGHT,    // 高：脚以上
    val ctrlBoxVOffset: Float = ConfigDefaults.CTRL_BOX_VOFFSET,  // 垂直偏移：默认0（脚为基准，正=盒子上移）
    // 调试：在屏幕叠加「边界偏移线」（复用设置页边界辅助线，四边黑色虚线）
    val showBoundOffset: Boolean = ConfigDefaults.SHOW_BOUND_OFFSET,
    // 调试：在屏幕叠加「吸附边界」（复用设置页吸附辅助线，含边界线 + 吸附距离内缩框，完整）
    val showSnapOffset: Boolean = ConfigDefaults.SHOW_SNAP_OFFSET,
    val visible: Boolean = ConfigDefaults.VISIBLE,      // 宠物显示/隐藏（独立于浮窗总开关）
    val imeAdapt: Boolean = ConfigDefaults.IME_ADAPT,      // 输入法适应：键盘弹出时抬高宠物地面（避免遮挡输入框），默认开
    val imeLiftOffset: Float = ConfigDefaults.IME_LIFT_OFFSET,   // 抬高偏移（px）：对键盘抬高后的宠物地面再额外修正，范围 -800~800，默认 100
    val imeResetBottomOffset: Boolean = ConfigDefaults.IME_RESET_BOTTOM_OFFSET, // 键盘适应-重置边界：键盘弹出时无视上/下边界偏移，仅用键盘高度+抬高偏移决定活动下边界（默认开）
    val imeHide: Boolean = ConfigDefaults.IME_HIDE, // 键盘时隐藏：键盘弹出时暂时隐藏「酥」且不可点击（不修改 visible 设置），键盘收起恢复
    val thinkingEnabled: Boolean = ConfigDefaults.THINKING_ENABLED, // 碎碎念：屏幕底部居中随机轮播文字
    val thinkingOffset: Float = ConfigDefaults.THINKING_OFFSET,   // 碎碎念 Y 偏移（px）：不受底部偏移影响，正=向上（离屏幕底边更远）
    // ===== 碎碎念样式 =====
    val thinkingTextSize: Float = ConfigDefaults.THINKING_TEXT_SIZE, // 字号（dp），范围 6-24
    val thinkingAlpha: Float = ConfigDefaults.THINKING_ALPHA,     // 文字透明度，范围 0.1-1.0（与颜色 alpha 分离，避免叠加）
    // 文字颜色（ARGB，alpha 固定 0xFF；透明度由 thinkingAlpha 单独控制）
    val thinkingColor: Int = ConfigDefaults.THINKING_COLOR,
    val thinkingEmptyMin: Int = ConfigDefaults.THINKING_EMPTY_MIN,     // 空白（不显示文字）时长下限，单位秒，范围 1-60
    val thinkingEmptyMax: Int = ConfigDefaults.THINKING_EMPTY_MAX,    // 空白时长上限，单位秒，范围 1-60（不小于下限）
    // ===== 碎碎念过渡特效 =====
    // 文字闪现：空白结束后、文字显示「前」的显形特效开关（关闭则文字直接出现）
    val thinkingFlashIn: Boolean = ConfigDefaults.THINKING_FLASH_IN,
    // 文字闪回：文字消失「后」、进入空白前的退场特效开关（关闭则文字直接消失）
    val thinkingFlashOut: Boolean = ConfigDefaults.THINKING_FLASH_OUT,
    // ===== 碎碎念背景 =====
    // 气泡背景：显示开关（默认关）+ 背景色（ARGB，默认白）+ 背景透明度（0-100，默认 50）
    val thinkingBgEnabled: Boolean = ConfigDefaults.THINKING_BG_ENABLED,
    val thinkingBgColor: Int = ConfigDefaults.THINKING_BG_COLOR,
    val thinkingBgAlpha: Int = ConfigDefaults.THINKING_BG_ALPHA,
    // ===== 随机模式 =====
    val randomEnabled: Boolean = ConfigDefaults.RANDOM_ENABLED,        // 随机模式总开关
    // 参与随机的项（位掩码，见 RANDOM_ 常量），默认全开（除禁随机项外）
    val randomItems: Int = ConfigDefaults.RANDOM_ITEMS,
    val randomPeriodMin: Int = ConfigDefaults.RANDOM_PERIOD_MIN,             // 随机周期最小值（分钟），默认 30
    val randomPeriodMax: Int = ConfigDefaults.RANDOM_PERIOD_MAX,            // 随机周期最大值（分钟），默认 120
    val petCount: Int = ConfigDefaults.PET_COUNT,                     // 宠物显示数量（多实例），范围 1-10，默认 1
    // 夜间模式强制开关：二者默认均关。强制打开优先级 > 强制关闭 > 跟随系统。
    val forceNightOn: Boolean = ConfigDefaults.FORCE_NIGHT_ON,         // 强制打开夜间模式
    val forceNightOff: Boolean = ConfigDefaults.FORCE_NIGHT_OFF,        // 强制关闭夜间模式
    // 资源包：开启后使用 assets/芝麻酥/ 新图（关于页开关，默认关 = 沿用 assets/img/ 旧图）
    val useNewSprite: Boolean = ConfigDefaults.USE_NEW_SPRITE,           // 使用新资源包
    // 仅单个碎碎念：开启=仅主实例显示碎碎念；关闭=每个实例各自显示（关于页开关，默认开）
    val singleTrayMsg: Boolean = ConfigDefaults.SINGLE_TRAY_MSG
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
    private val KEY_SNAP_ENABLED = booleanPreferencesKey("snap_enabled")
    private val KEY_STOP = booleanPreferencesKey("snap_top")
    private val KEY_SBOTTOM = booleanPreferencesKey("snap_bottom")
    private val KEY_SLEFT = booleanPreferencesKey("snap_left")
    private val KEY_SRIGHT = booleanPreferencesKey("snap_right")
    private val KEY_SNAP_THRESHOLD = floatPreferencesKey("snap_threshold")
    private val KEY_SHOW_SNAP_LINE = booleanPreferencesKey("show_snap_line")
    private val KEY_GRAVITY_ENABLED = booleanPreferencesKey("gravity_enabled")
    private val KEY_TILT_GRAVITY = booleanPreferencesKey("tilt_gravity")
    private val KEY_MAXSPEED = floatPreferencesKey("max_speed")
    private val KEY_OFF_TOP = floatPreferencesKey("offset_top")
    private val KEY_OFF_BOTTOM = floatPreferencesKey("offset_bottom")
    private val KEY_OFF_LEFT = floatPreferencesKey("offset_left")
    private val KEY_OFF_RIGHT = floatPreferencesKey("offset_right")
    private val KEY_ENABLED = booleanPreferencesKey("enabled")
    private val KEY_DEBUG = booleanPreferencesKey("show_debug")
    private val KEY_RECT = booleanPreferencesKey("show_rect") // 旧键，仅用于迁移
    private val KEY_IMAGE_BORDER = booleanPreferencesKey("image_border_show")
    private val KEY_CONTROL_BORDER = booleanPreferencesKey("control_border_show")
    private val KEY_SHOW_BOUND_OFFSET = booleanPreferencesKey("show_bound_offset")
    private val KEY_SHOW_SNAP_OFFSET = booleanPreferencesKey("show_snap_offset")
    private val KEY_RECT_HIT_MODE = booleanPreferencesKey("rect_hit_mode") // 旧键，仅用于迁移
    private val KEY_HIT_MODE = androidx.datastore.preferences.core.intPreferencesKey("hit_mode")
    private val KEY_CTRL_BOX_W = androidx.datastore.preferences.core.floatPreferencesKey("ctrl_box_w")
    private val KEY_CTRL_BOX_H = androidx.datastore.preferences.core.floatPreferencesKey("ctrl_box_h")
    private val KEY_CTRL_BOX_VO = androidx.datastore.preferences.core.floatPreferencesKey("ctrl_box_vo")
    private val KEY_VISIBLE = booleanPreferencesKey("visible")
    private val KEY_IME_ADAPT = booleanPreferencesKey("ime_adapt")
    private val KEY_IME_LIFT_OFFSET = floatPreferencesKey("ime_lift_offset")
    private val KEY_IME_RESET_BOTTOM = booleanPreferencesKey("ime_reset_bottom_offset")
    private val KEY_IME_HIDE = booleanPreferencesKey("ime_hide")
    private val KEY_ALPHA = floatPreferencesKey("alpha")
    private val KEY_CLICK_THROUGH = booleanPreferencesKey("click_through")
    private val KEY_BOUNCE_VIBRATE = booleanPreferencesKey("bounce_vibrate")
    private val KEY_THINKING_ENABLED = booleanPreferencesKey("thinking_enabled")
    private val KEY_THINKING_OFFSET = floatPreferencesKey("thinking_offset")
    private val KEY_THINKING_TEXT_SIZE = floatPreferencesKey("thinking_text_size")
    private val KEY_THINKING_ALPHA = floatPreferencesKey("thinking_alpha")
    private val KEY_THINKING_COLOR = androidx.datastore.preferences.core.intPreferencesKey("thinking_color")
    private val KEY_THINKING_EMPTY_MIN = androidx.datastore.preferences.core.intPreferencesKey("thinking_empty_min")
    private val KEY_THINKING_EMPTY_MAX = androidx.datastore.preferences.core.intPreferencesKey("thinking_empty_max")
    private val KEY_THINKING_FLASH_IN = booleanPreferencesKey("thinking_flash_in")
    private val KEY_THINKING_FLASH_OUT = booleanPreferencesKey("thinking_flash_out")
    private val KEY_THINKING_BG_ENABLED = booleanPreferencesKey("thinking_bg_enabled")
    private val KEY_THINKING_BG_ALPHA = androidx.datastore.preferences.core.intPreferencesKey("thinking_bg_alpha")
    private val KEY_THINKING_BG_COLOR = androidx.datastore.preferences.core.intPreferencesKey("thinking_bg_color")
    private val KEY_RANDOM_ENABLED = booleanPreferencesKey("random_enabled")
    private val KEY_RANDOM_ITEMS = androidx.datastore.preferences.core.intPreferencesKey("random_items_v5")
    private val KEY_RANDOM_PERIOD_MIN = androidx.datastore.preferences.core.intPreferencesKey("random_period_min")
    private val KEY_RANDOM_PERIOD_MAX = androidx.datastore.preferences.core.intPreferencesKey("random_period_max")
    private val KEY_REBOUND_ENABLED = booleanPreferencesKey("rebound_enabled")
    private val KEY_PET_COUNT = androidx.datastore.preferences.core.intPreferencesKey("pet_count")
    private val KEY_FORCE_NIGHT_ON = booleanPreferencesKey("force_night_on")
    private val KEY_FORCE_NIGHT_OFF = booleanPreferencesKey("force_night_off")
    private val KEY_USE_NEW_SPRITE = booleanPreferencesKey("use_new_sprite")
    private val KEY_SINGLE_TRAY_MSG = booleanPreferencesKey("single_tray_msg")

    /** Preferences → PetConfigData 的映射。抽取为函数以便 configFlow 与 update 复用同一套默认值与钳制规则。 */
    private fun androidx.datastore.preferences.core.Preferences.toConfigData(): PetConfigData {
        val prefs = this
        return PetConfigData(
            scale = prefs[KEY_SCALE] ?: ConfigDefaults.SCALE,
            gravity = prefs[KEY_GRAVITY] ?: ConfigDefaults.GRAVITY,
            reboundRatio = prefs[KEY_REBOUND] ?: ConfigDefaults.REBOUND_RATIO,
            walkSpeed = prefs[KEY_WALK] ?: ConfigDefaults.WALK_SPEED,
            followSpeed = prefs[KEY_FOLLOW] ?: ConfigDefaults.FOLLOW_SPEED,
            gravityTop = prefs[KEY_TOP] ?: ConfigDefaults.GRAVITY_TOP,
            gravityBottom = prefs[KEY_BOTTOM] ?: ConfigDefaults.GRAVITY_BOTTOM,
            gravityLeft = prefs[KEY_LEFT] ?: ConfigDefaults.GRAVITY_LEFT,
            gravityRight = prefs[KEY_RIGHT] ?: ConfigDefaults.GRAVITY_RIGHT,
            reboundTop = prefs[KEY_RTOP] ?: ConfigDefaults.REBOUND_TOP,
            reboundBottom = prefs[KEY_RBOTTOM] ?: ConfigDefaults.REBOUND_BOTTOM,
            reboundLeft = prefs[KEY_RLEFT] ?: ConfigDefaults.REBOUND_LEFT,
            reboundRight = prefs[KEY_RRIGHT] ?: ConfigDefaults.REBOUND_RIGHT,
            snapEnabled = prefs[KEY_SNAP_ENABLED] ?: ConfigDefaults.SNAP_ENABLED,
            snapTop = prefs[KEY_STOP] ?: ConfigDefaults.SNAP_TOP,
            snapBottom = prefs[KEY_SBOTTOM] ?: ConfigDefaults.SNAP_BOTTOM,
            snapLeft = prefs[KEY_SLEFT] ?: ConfigDefaults.SNAP_LEFT,
            snapRight = prefs[KEY_SRIGHT] ?: ConfigDefaults.SNAP_RIGHT,
            snapThreshold = prefs[KEY_SNAP_THRESHOLD] ?: ConfigDefaults.SNAP_THRESHOLD,
            showSnapLine = prefs[KEY_SHOW_SNAP_LINE] ?: ConfigDefaults.SHOW_SNAP_LINE,
            gravityEnabled = prefs[KEY_GRAVITY_ENABLED] ?: ConfigDefaults.GRAVITY_ENABLED,
            // 体感重力默认关闭：开启后会自动关闭四边定向重力（见 PetService 处理）。
            tiltGravity = prefs[KEY_TILT_GRAVITY] ?: ConfigDefaults.TILT_GRAVITY,
            maxSpeed = prefs[KEY_MAXSPEED] ?: ConfigDefaults.MAX_SPEED,
            offsetTop = (prefs[KEY_OFF_TOP] ?: ConfigDefaults.OFFSET_TOP).coerceIn(-300f, 2100f),
            offsetBottom = (prefs[KEY_OFF_BOTTOM] ?: ConfigDefaults.OFFSET_BOTTOM).coerceIn(-300f, 2100f),
            offsetLeft = (prefs[KEY_OFF_LEFT] ?: ConfigDefaults.OFFSET_LEFT).coerceIn(-300f, 1000f),
            offsetRight = (prefs[KEY_OFF_RIGHT] ?: ConfigDefaults.OFFSET_RIGHT).coerceIn(-300f, 1000f),
            enabled = prefs[KEY_ENABLED] ?: ConfigDefaults.ENABLED,
            showDebug = prefs[KEY_DEBUG] ?: ConfigDefaults.SHOW_DEBUG,
            showImageBorder = prefs[KEY_IMAGE_BORDER] ?: run {
                // 迁移：旧 show_rect 布尔 -> 两个边框开关（开=两者都开）
                if (prefs.contains(KEY_RECT)) prefs[KEY_RECT] == true else ConfigDefaults.SHOW_IMAGE_BORDER
            },
            showControlBorder = prefs[KEY_CONTROL_BORDER] ?: run {
                if (prefs.contains(KEY_RECT)) prefs[KEY_RECT] == true else ConfigDefaults.SHOW_CONTROL_BORDER
            },
            showBoundOffset = prefs[KEY_SHOW_BOUND_OFFSET] ?: ConfigDefaults.SHOW_BOUND_OFFSET,
            showSnapOffset = prefs[KEY_SHOW_SNAP_OFFSET] ?: ConfigDefaults.SHOW_SNAP_OFFSET,
            hitMode = prefs[KEY_HIT_MODE] ?: run {
                // 迁移：旧 rect_hit_mode 布尔 -> 三态（true=边界, false=像素）
                if (prefs.contains(KEY_RECT_HIT_MODE)) {
                    if (prefs[KEY_RECT_HIT_MODE] == true) ConfigDefaults.HIT_BOUNDARY else ConfigDefaults.HIT_PIXEL
                } else ConfigDefaults.HIT_MODE
            },
            ctrlBoxWidth = prefs[KEY_CTRL_BOX_W] ?: ConfigDefaults.CTRL_BOX_WIDTH,
            ctrlBoxHeight = prefs[KEY_CTRL_BOX_H] ?: ConfigDefaults.CTRL_BOX_HEIGHT,
            ctrlBoxVOffset = prefs[KEY_CTRL_BOX_VO] ?: ConfigDefaults.CTRL_BOX_VOFFSET,
            visible = prefs[KEY_VISIBLE] ?: ConfigDefaults.VISIBLE,
            imeAdapt = prefs[KEY_IME_ADAPT] ?: ConfigDefaults.IME_ADAPT,
            imeLiftOffset = (prefs[KEY_IME_LIFT_OFFSET] ?: ConfigDefaults.IME_LIFT_OFFSET).coerceIn(-800f, 800f),
            imeResetBottomOffset = prefs[KEY_IME_RESET_BOTTOM] ?: ConfigDefaults.IME_RESET_BOTTOM_OFFSET,
            imeHide = prefs[KEY_IME_HIDE] ?: ConfigDefaults.IME_HIDE,
            alpha = (prefs[KEY_ALPHA] ?: ConfigDefaults.ALPHA).coerceIn(0.1f, 1f),
            clickThrough = prefs[KEY_CLICK_THROUGH] ?: ConfigDefaults.CLICK_THROUGH,
            bounceVibrate = prefs[KEY_BOUNCE_VIBRATE] ?: ConfigDefaults.BOUNCE_VIBRATE,
            thinkingEnabled = prefs[KEY_THINKING_ENABLED] ?: ConfigDefaults.THINKING_ENABLED,
            thinkingOffset = prefs[KEY_THINKING_OFFSET] ?: ConfigDefaults.THINKING_OFFSET,
            thinkingTextSize = (prefs[KEY_THINKING_TEXT_SIZE] ?: ConfigDefaults.THINKING_TEXT_SIZE).coerceIn(6f, 24f),
            thinkingAlpha = (prefs[KEY_THINKING_ALPHA] ?: ConfigDefaults.THINKING_ALPHA).coerceIn(0.1f, 1f),
            thinkingColor = prefs[KEY_THINKING_COLOR] ?: ConfigDefaults.THINKING_COLOR,
            thinkingEmptyMin = (prefs[KEY_THINKING_EMPTY_MIN] ?: ConfigDefaults.THINKING_EMPTY_MIN).coerceIn(1, 60),
            thinkingEmptyMax = (prefs[KEY_THINKING_EMPTY_MAX] ?: ConfigDefaults.THINKING_EMPTY_MAX).coerceIn(1, 60),
            thinkingFlashIn = prefs[KEY_THINKING_FLASH_IN] ?: ConfigDefaults.THINKING_FLASH_IN,
            thinkingFlashOut = prefs[KEY_THINKING_FLASH_OUT] ?: ConfigDefaults.THINKING_FLASH_OUT,
            thinkingBgEnabled = prefs[KEY_THINKING_BG_ENABLED] ?: ConfigDefaults.THINKING_BG_ENABLED,
            thinkingBgColor = prefs[KEY_THINKING_BG_COLOR] ?: ConfigDefaults.THINKING_BG_COLOR,
            thinkingBgAlpha = (prefs[KEY_THINKING_BG_ALPHA] ?: ConfigDefaults.THINKING_BG_ALPHA).coerceIn(0, 100),
            randomEnabled = prefs[KEY_RANDOM_ENABLED] ?: ConfigDefaults.RANDOM_ENABLED,
            randomItems = prefs[KEY_RANDOM_ITEMS] ?: ConfigDefaults.RANDOM_ITEMS,
            randomPeriodMin = prefs[KEY_RANDOM_PERIOD_MIN] ?: ConfigDefaults.RANDOM_PERIOD_MIN,
            randomPeriodMax = prefs[KEY_RANDOM_PERIOD_MAX] ?: ConfigDefaults.RANDOM_PERIOD_MAX,
            reboundEnabled = prefs[KEY_REBOUND_ENABLED] ?: ConfigDefaults.REBOUND_ENABLED,
            petCount = (prefs[KEY_PET_COUNT] ?: ConfigDefaults.PET_COUNT).coerceIn(ConfigDefaults.PET_COUNT_MIN, ConfigDefaults.PET_COUNT_MAX),
            forceNightOn = prefs[KEY_FORCE_NIGHT_ON] ?: ConfigDefaults.FORCE_NIGHT_ON,
            forceNightOff = prefs[KEY_FORCE_NIGHT_OFF] ?: ConfigDefaults.FORCE_NIGHT_OFF,
            useNewSprite = prefs[KEY_USE_NEW_SPRITE] ?: ConfigDefaults.USE_NEW_SPRITE,
            singleTrayMsg = prefs[KEY_SINGLE_TRAY_MSG] ?: ConfigDefaults.SINGLE_TRAY_MSG
        )
    }

    val configFlow: Flow<PetConfigData> = context.dataStore.data.map { it.toConfigData() }

    // 清洗已存在的非法旧数据：找出 DataStore 中可能残留的“左右同开/上下同开”组合并修正回写。
    // 仅当确实存在非法组合时才写盘（避免无谓 IO）。在配置加载入口调用一次即可。
    suspend fun normalize() {
        val cur = configFlow.first()
        // 每次启动：数量大于 3 时强制钳回 3（防启动批量生成过多实例崩溃；临时保护）。
        // 这是原先的启动写盘钳制，与大小/透明度等普通变量无差别接入阈值页后，仅此一处特别。
        val needPetCountFix = cur.petCount > 3
        // 修正“左右同开/上下同开”的非法重力组合
        val needGravityFix = (cur.gravityLeft && cur.gravityRight) || (cur.gravityTop && cur.gravityBottom)
        if (needPetCountFix || needGravityFix) {
            update {
                it.copy(
                    petCount = it.petCount.coerceAtMost(3),
                    gravityLeft = false,
                    gravityRight = false,
                    gravityTop = false,
                    gravityBottom = false
                )
            }
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
        context.dataStore.edit { prefs ->
            // 读改写必须整体放在 edit 事务内：DataStore 保证同一时刻只有一个写事务，
            // 且事务内能读到最新已提交数据。若把读取放到 edit 之外（configFlow.first()），
            // 高频拖动滑块时多个并发协程会读到同一个旧快照并乱序写回，
            // 最终配置回退到拖动途中的中间值，表现为松手后数值来回抖动。
            val current = prefs.toConfigData()
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
            prefs[KEY_SNAP_ENABLED] = updated.snapEnabled
            prefs[KEY_STOP] = updated.snapTop
            prefs[KEY_SBOTTOM] = updated.snapBottom
            prefs[KEY_SLEFT] = updated.snapLeft
            prefs[KEY_SRIGHT] = updated.snapRight
            prefs[KEY_SNAP_THRESHOLD] = updated.snapThreshold
            prefs[KEY_SHOW_SNAP_LINE] = updated.showSnapLine
            prefs[KEY_GRAVITY_ENABLED] = updated.gravityEnabled
            prefs[KEY_TILT_GRAVITY] = updated.tiltGravity
            prefs[KEY_MAXSPEED] = updated.maxSpeed
            prefs[KEY_OFF_TOP] = updated.offsetTop
            prefs[KEY_OFF_BOTTOM] = updated.offsetBottom
            prefs[KEY_OFF_LEFT] = updated.offsetLeft
            prefs[KEY_OFF_RIGHT] = updated.offsetRight
            prefs[KEY_ENABLED] = updated.enabled
            prefs[KEY_DEBUG] = updated.showDebug
            prefs[KEY_IMAGE_BORDER] = updated.showImageBorder
            prefs[KEY_CONTROL_BORDER] = updated.showControlBorder
            prefs[KEY_SHOW_BOUND_OFFSET] = updated.showBoundOffset
            prefs[KEY_SHOW_SNAP_OFFSET] = updated.showSnapOffset
            prefs[KEY_HIT_MODE] = updated.hitMode
            prefs[KEY_CTRL_BOX_W] = updated.ctrlBoxWidth
            prefs[KEY_CTRL_BOX_H] = updated.ctrlBoxHeight
            prefs[KEY_CTRL_BOX_VO] = updated.ctrlBoxVOffset
            prefs[KEY_VISIBLE] = updated.visible
            prefs[KEY_IME_ADAPT] = updated.imeAdapt
            prefs[KEY_IME_LIFT_OFFSET] = updated.imeLiftOffset
            prefs[KEY_IME_RESET_BOTTOM] = updated.imeResetBottomOffset
            prefs[KEY_IME_HIDE] = updated.imeHide
            prefs[KEY_ALPHA] = updated.alpha
            prefs[KEY_CLICK_THROUGH] = updated.clickThrough
            prefs[KEY_BOUNCE_VIBRATE] = updated.bounceVibrate
            prefs[KEY_THINKING_ENABLED] = updated.thinkingEnabled
            prefs[KEY_THINKING_OFFSET] = updated.thinkingOffset
            prefs[KEY_THINKING_TEXT_SIZE] = updated.thinkingTextSize
            prefs[KEY_THINKING_ALPHA] = updated.thinkingAlpha
            prefs[KEY_THINKING_COLOR] = updated.thinkingColor
            prefs[KEY_THINKING_EMPTY_MIN] = updated.thinkingEmptyMin
            prefs[KEY_THINKING_EMPTY_MAX] = updated.thinkingEmptyMax
            prefs[KEY_THINKING_FLASH_IN] = updated.thinkingFlashIn
            prefs[KEY_THINKING_FLASH_OUT] = updated.thinkingFlashOut
            prefs[KEY_THINKING_BG_ENABLED] = updated.thinkingBgEnabled
            prefs[KEY_THINKING_BG_ALPHA] = updated.thinkingBgAlpha
            prefs[KEY_THINKING_BG_COLOR] = updated.thinkingBgColor
            prefs[KEY_RANDOM_ENABLED] = updated.randomEnabled
            prefs[KEY_RANDOM_ITEMS] = updated.randomItems
            prefs[KEY_RANDOM_PERIOD_MIN] = updated.randomPeriodMin
            prefs[KEY_RANDOM_PERIOD_MAX] = updated.randomPeriodMax
            prefs[KEY_REBOUND_ENABLED] = updated.reboundEnabled
            prefs[KEY_PET_COUNT] = updated.petCount
            prefs[KEY_FORCE_NIGHT_ON] = updated.forceNightOn
            prefs[KEY_FORCE_NIGHT_OFF] = updated.forceNightOff
            prefs[KEY_USE_NEW_SPRITE] = updated.useNewSprite
            prefs[KEY_SINGLE_TRAY_MSG] = updated.singleTrayMsg
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

    // 按阈值页的「用户默认值」恢复：把指定页 bounds 中记录的 default 写回 PetConfig。
    // 这样用户在阈值页设定的默认值，会在各设置页「恢复默认」时生效。
    suspend fun resetFromBounds(petBounds: PetBounds, page: SettingsPage) {
        val b = petBounds.getBlocking(page)
        update { c ->
            var nc = c
            b.sliders.forEach { s ->
                nc = when (s.key) {
                    "scale" -> nc.copy(scale = s.default)
                    "alpha" -> nc.copy(alpha = s.default / 100f)
                    "gravity" -> nc.copy(gravity = s.default)
                    "maxSpeed" -> nc.copy(maxSpeed = s.default)
                    "rebound" -> nc.copy(reboundRatio = s.default)
                    "snapThreshold" -> nc.copy(snapThreshold = s.default)
                    "offsetTop" -> nc.copy(offsetTop = s.default)
                    "offsetBottom" -> nc.copy(offsetBottom = s.default)
                    "offsetLeft" -> nc.copy(offsetLeft = s.default)
                    "offsetRight" -> nc.copy(offsetRight = s.default)
                    "imeLiftOffset" -> nc.copy(imeLiftOffset = s.default)
                    "thinkingTextSize" -> nc.copy(thinkingTextSize = s.default)
                    "thinkingAlpha" -> nc.copy(thinkingAlpha = s.default / 100f)
                    "thinkingOffset" -> nc.copy(thinkingOffset = s.default)
                    "thinkingBgAlpha" -> nc.copy(thinkingBgAlpha = s.default.toInt())
                    else -> nc
                }
            }
            b.duals.forEach { d ->
                when (d.key) {
                    "thinkingEmpty" -> nc = nc.copy(thinkingEmptyMin = d.defaultA.toInt(), thinkingEmptyMax = d.defaultB.toInt())
                    "randomPeriod" -> nc = nc.copy(randomPeriodMin = d.defaultA.toInt(), randomPeriodMax = d.defaultB.toInt())
                }
            }
            b.switches.forEach { sw ->
                nc = when (sw.key) {
                    "visible" -> nc.copy(visible = sw.default)
                    "clickThrough" -> nc.copy(clickThrough = sw.default)
                    "gravityEnabled" -> nc.copy(gravityEnabled = sw.default)
                    "bounceVibrate" -> nc.copy(bounceVibrate = sw.default)
                    "tiltGravity" -> nc.copy(tiltGravity = sw.default)
                    "gravityTop" -> nc.copy(gravityTop = sw.default)
                    "gravityBottom" -> nc.copy(gravityBottom = sw.default)
                    "gravityLeft" -> nc.copy(gravityLeft = sw.default)
                    "gravityRight" -> nc.copy(gravityRight = sw.default)
                    "reboundEnabled" -> nc.copy(reboundEnabled = sw.default)
                    "reboundTop" -> nc.copy(reboundTop = sw.default)
                    "reboundBottom" -> nc.copy(reboundBottom = sw.default)
                    "reboundLeft" -> nc.copy(reboundLeft = sw.default)
                    "reboundRight" -> nc.copy(reboundRight = sw.default)
                    "thinkingEnabled" -> nc.copy(thinkingEnabled = sw.default)
                    "thinkingFlashIn" -> nc.copy(thinkingFlashIn = sw.default)
                    "thinkingFlashOut" -> nc.copy(thinkingFlashOut = sw.default)
                    "thinkingBgEnabled" -> nc.copy(thinkingBgEnabled = sw.default)
                    "randomEnabled" -> nc.copy(randomEnabled = sw.default)
                    "imeAdapt" -> nc.copy(imeAdapt = sw.default)
                    "imeResetBottomOffset" -> nc.copy(imeResetBottomOffset = sw.default)
                    "imeHide" -> nc.copy(imeHide = sw.default)
                    else -> nc
                }
            }
            b.colors.forEach { c ->
                nc = when (c.key) {
                    "thinkingBgColor" -> nc.copy(thinkingBgColor = c.default)
                    "thinkingColor" -> nc.copy(thinkingColor = c.default)
                    else -> nc
                }
            }
            // 随机项位掩码：严格以阈值开关默认重建，使「恢复默认」与系统出厂完全一致
            if (page == SettingsPage.RANDOM) {
                nc = nc.copy(randomItems = AppBounds.randomItemsFromBounds(b))
            }
            nc
        }
    }
}
