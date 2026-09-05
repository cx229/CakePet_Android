package com.cx.cakepet

/**
 * 全局系统基线常量（唯一真相源，单文件集中）。
 *
 * 本文件收敛两类分散在 PetBounds / PetConfig 里的字面量：
 *
 * 一、阈值基线（设置页「可阈值化项」的 5 层语义，见 PetBounds 的三层语义注释）：
 *   - 默认值        (default)        : 代码内置默认
 *   - 默认上下界    (baseMin/baseMax): 未启用阈值时滑块的初始行程范围，也是「恢复系统默认」回落值
 *   - 系统极限上下界 (hardMin/hardMax): 用户允许输入到的上限（阈值页输入框仅校验此范围）
 *   - 用户当前默认值/上下界 (from/to/userDefault): 运行时持久值（pet_bounds DataStore），
 *        由 PetBounds.withUser() 计算，不落源码（见下方 AppBounds 仅收系统基线）
 *
 * SliderBound 构造参数布局（具名对照，便于核对）：
 *   (key, label, group, hardMin, hardMax, baseMin, baseMax, from, to, step, default)
 * DualSliderBound 构造参数布局：
 *   (key, label, group, hardMin, hardMax,
 *    baseMinA, baseMaxA, baseMinB, baseMaxB,
 *    fromA, toA, stepA, defaultA, fromB, toB, stepB, defaultB)
 * SwitchBound 构造参数布局：
 *   (key, label, group, default)
 *
 * 二、PetConfig 字段默认值（含关于页开关默认值）：见 ConfigDefaults。
 *   「当前值」为运行时持久值（pet_config DataStore），由 PetConfig.toConfigData() 读取，不落源码。
 *
 * ── AppBounds 与 ConfigDefaults 的关系（重要）──
 *   - AppBounds：纯 UI 层。定义设置页「滑块行程 / 输入框上下限 / 初次进入时预填值」，
 *     决定的是滑块能拉到哪、输入框能填到哪、页面首次打开显示什么。它不直接参与物理运算。
 *   - ConfigDefaults：运行时真相源。PetConfig 初始化 / 数据缺失回退时用的「实际生效默认值」，
 *     单位是物理层单位（如 gravity=3000 是物理引擎数值，不是 UI 的 0-10000 行程）。
 *   - 两者用同名 key 对齐（如 AppBounds 的 "gravity" 与 ConfigDefaults.GRAVITY），数值通常一致，
 *     但语义不同：AppBounds 的 default 是「UI 预填」，ConfigDefaults 的常量才是「物理默认值」。
 *     一致性由开发者手动维护——改一处记得核对另一处，这是本文件最大的踩坑点。
 */

// =====================================================================
// 一、阈值基线（按设置页分组）
// =====================================================================
/**
 * 设置页 UI 阈值基线（仅 UI 行程/预填，见文件头「AppBounds 与 ConfigDefaults 的关系」）。
 *
 * 每个分组页提供三种列表：
 *   *_SLIDERS  : 单滑块（SliderBound），如「大小 / 透明度 / 重力强度」
 *   *_DUALS    : 双滑块（DualSliderBound），表示「区间/频率」这类需要两个端点的项
 *   *_SWITCHES : 开关（SwitchBound）
 *   *_ORDER    : 该页控件在 UI 上的竖向排列顺序（key 列表）
 * 改 UI 布局只需动这里，物理逻辑不依赖本对象的字段顺序。
 */
object AppBounds {

    // ---------------- MAIN 页 ----------------
    // 主设置页：宠物整体「显示/移动/重力/反弹」四大基础项。
    val MAIN_SLIDERS = listOf(
        SliderBound(
            key = "scale", label = "大小", group = "显示",
            hardMin = 0.01f, hardMax = 30f,        // 倍率：1.4 = 原始 140%
            baseMin = 0.5f, baseMax = 3f,
            from = 0.5f, to = 3f,
            step = 0.01f, default = 1.4f
        ),
        SliderBound(
            key = "alpha", label = "透明度", group = "显示",
            hardMin = 0f, hardMax = 100f,           // 百分比：0=全透明，100=不透明（UI 层；物理层 ALPHA 用 0-1 小数）
            baseMin = 10f, baseMax = 100f,
            from = 10f, to = 100f,
            step = 1f, default = 70f
        ),
        SliderBound(
            key = "maxSpeed", label = "最大速度", group = "移动",
            hardMin = 0f, hardMax = 80000f,
            baseMin = 0f, baseMax = 20000f,
            from = 0f, to = 20000f,
            step = 100f, default = 7100f
        ),
        SliderBound(
            key = "gravity", label = "重力强度", group = "重力",
            hardMin = 0f, hardMax = 80000f,
            baseMin = 0f, baseMax = 10000f,
            from = 0f, to = 10000f,
            step = 100f, default = 3000f
        ),
        SliderBound(
            key = "rebound", label = "反弹系数", group = "反弹",
            hardMin = 0f, hardMax = 30f,
            baseMin = 0f, baseMax = 2f,
            from = 0f, to = 2f,
            step = 0.1f, default = 0.7f
        ),
        SliderBound(
            key = "petCount", label = "数量", group = "显示",
            hardMin = 1f, hardMax = 30f,            // 阈值极限 1-30（关于页入口仍为 1-10）
            baseMin = 1f, baseMax = 10f,
            from = 1f, to = 10f,
            step = 1f, default = 1f
        )
    )
    val MAIN_SWITCHES = listOf(
        SwitchBound("visible", "显示 / 隐藏", "显示", true),
        SwitchBound("clickThrough", "点击穿透", "显示", false),
        SwitchBound("gravityEnabled", "重力抛掷", "重力", true),
        SwitchBound("tiltGravity", "体感重力", "重力", false),
        SwitchBound("gravityTop", "上边重力", "重力", false),
        SwitchBound("gravityBottom", "下边重力", "重力", true),
        SwitchBound("gravityLeft", "左边重力", "重力", false),
        SwitchBound("gravityRight", "右边重力", "重力", false),
        SwitchBound("reboundEnabled", "边缘反弹", "反弹", true),
        SwitchBound("bounceVibrate", "反弹振动", "反弹", true),
        SwitchBound("reboundTop", "上边反弹", "反弹", true),
        SwitchBound("reboundBottom", "下边反弹", "反弹", true),
        SwitchBound("reboundLeft", "左边反弹", "反弹", true),
        SwitchBound("reboundRight", "右边反弹", "反弹", true)
    )
    val MAIN_ORDER = listOf(
        "scale", "alpha", "petCount", "visible", "clickThrough",
        "maxSpeed",
        "gravityEnabled", "tiltGravity", "gravity", "gravityTop", "gravityBottom", "gravityLeft", "gravityRight",
        "reboundEnabled", "bounceVibrate", "rebound", "reboundTop", "reboundBottom", "reboundLeft", "reboundRight"
    )

    // ---------------- SNAP 页 ----------------
    // 吸附边缘：宠物靠近屏幕四边/四角时停住并切换「吸附探头」状态；showSnapLine 控制是否显示吸附辅助线。
    val SNAP_SLIDERS = listOf(
        SliderBound(
            key = "snapThreshold", label = "吸附距离", group = "吸附边缘",
            hardMin = -4000f, hardMax = 4000f,     // 可为负：负值表示「离开边缘该距离内也吸附」（外扩吸附区）
            baseMin = 0f, baseMax = 600f,
            from = 0f, to = 600f,
            step = 1f, default = 100f
        )
    )
    val SNAP_SWITCHES = listOf(
        SwitchBound("snapEnabled", "吸附边缘", "吸附边缘", true),
        SwitchBound("snapTop", "上边吸附", "吸附边缘", true),
        SwitchBound("snapBottom", "下边吸附", "吸附边缘", true),
        SwitchBound("snapLeft", "左边吸附", "吸附边缘", true),
        SwitchBound("snapRight", "右边吸附", "吸附边缘", true),
        SwitchBound("showSnapLine", "显示吸附线", "显示吸附线", true)
    )
    val SNAP_ORDER = listOf(
        "snapEnabled", "snapTop", "snapBottom", "snapLeft", "snapRight", "snapThreshold", "showSnapLine"
    )

    // ---------------- RANGE 页 ----------------
    // 活动范围：上下左右偏移（向内/向外收紧可活动区域）+ 输入法弹起时自动上抬避免被键盘遮挡。
    val RANGE_SLIDERS = listOf(
        SliderBound(
            key = "offsetTop", label = "顶部偏移", group = "活动范围",
            hardMin = -4000f, hardMax = 4000f,     // 正=向内收缩活动区（顶部留白更多），负=向外扩（宠物可更靠边/出血）
            baseMin = -300f, baseMax = 1200f,
            from = -300f, to = 1200f,
            step = 1f, default = 140f
        ),
        SliderBound(
            key = "offsetBottom", label = "底部偏移", group = "活动范围",
            hardMin = -4000f, hardMax = 4000f,
            baseMin = -300f, baseMax = 1200f,
            from = -300f, to = 1200f,
            step = 1f, default = 140f
        ),
        SliderBound(
            key = "offsetLeft", label = "左部偏移", group = "活动范围",
            hardMin = -4000f, hardMax = 4000f,
            baseMin = -300f, baseMax = 600f,
            from = -300f, to = 600f,
            step = 1f, default = 0f
        ),
        SliderBound(
            key = "offsetRight", label = "右部偏移", group = "活动范围",
            hardMin = -4000f, hardMax = 4000f,
            baseMin = -300f, baseMax = 600f,
            from = -300f, to = 600f,
            step = 1f, default = 0f
        ),
        SliderBound(
            key = "imeLiftOffset", label = "抬高偏移", group = "键盘适应",
            hardMin = -4000f, hardMax = 4000f,
            baseMin = -800f, baseMax = 800f,
            from = -800f, to = 800f,
            step = 1f, default = 100f
        )
    )
    val RANGE_SWITCHES = listOf(
        SwitchBound("imeAdapt", "键盘时抬高", "键盘适应", true),
        SwitchBound("imeResetBottomOffset", "重置边界", "键盘适应", true),
        SwitchBound("imeHide", "键盘时隐藏", "键盘适应", false)
    )
    val RANGE_ORDER = listOf(
        "offsetTop", "offsetBottom", "offsetLeft", "offsetRight",
        "imeAdapt", "imeResetBottomOffset", "imeLiftOffset", "imeHide"
    )

    // ---------------- THINKING 页 ----------------
    // 碎碎念：宠物底部气泡文字的「字号/透明度/偏移/出现频率/闪现闪回」等显示项。
    val THINKING_SLIDERS = listOf(
        SliderBound(
            key = "thinkingTextSize", label = "字号", group = "显示",
            hardMin = 0f, hardMax = 80f,
            baseMin = 0f, baseMax = 24f,
            from = 0f, to = 24f,
            step = 1f, default = 10f
        ),
        SliderBound(
            key = "thinkingAlpha", label = "透明度", group = "显示",
            hardMin = 0f, hardMax = 100f,
            baseMin = 0f, baseMax = 100f,
            from = 0f, to = 100f,
            step = 1f, default = 100f
        ),
        SliderBound(
            key = "thinkingOffset", label = "位置偏移", group = "显示",
            hardMin = -4000f, hardMax = 4000f,
            baseMin = -200f, baseMax = 200f,
            from = -200f, to = 200f,
            step = 1f, default = 0f
        ),
        SliderBound(
            key = "thinkingBgAlpha", label = "背景透明度", group = "背景",
            hardMin = 0f, hardMax = 100f,
            baseMin = 0f, baseMax = 100f,
            from = 0f, to = 100f,
            step = 1f, default = 50f
        )
    )
    val THINKING_DUALS = listOf(
        DualSliderBound(
            key = "thinkingEmpty", label = "频率", group = "显示",
            hardMin = 0f, hardMax = 3600f,         // 单位：秒（空窗时长区间 [min,max]，每次随机取一个）
            baseMinA = 1f, baseMaxA = 60f,
            baseMinB = 1f, baseMaxB = 60f,
            fromA = 1f, toA = 60f, stepA = 1f, defaultA = 2f,
            fromB = 1f, toB = 60f, stepB = 1f, defaultB = 15f
        )
    )
    val THINKING_SWITCHES = listOf(
        SwitchBound("thinkingEnabled", "底部消息", "碎碎念", true),
        SwitchBound("thinkingFlashIn", "文字闪现", "闪回", true),
        SwitchBound("thinkingFlashOut", "文字闪回", "闪回", true),
        SwitchBound("thinkingBgEnabled", "气泡背景", "背景", false)
    )
    val THINKING_COLORS = listOf(
        ColorBound("thinkingBgColor", "背景颜色", "背景", THINKING_PRESET_COLORS, 0xFFFFFFFF.toInt()),
        ColorBound("thinkingColor", "文字颜色", "显示", THINKING_PRESET_COLORS, ConfigDefaults.THINKING_COLOR)
    )
    val THINKING_ORDER = listOf(
        "thinkingEnabled",
        "thinkingTextSize", "thinkingAlpha", "thinkingOffset", "thinkingEmpty",
        "thinkingFlashIn", "thinkingFlashOut",
        "thinkingBgEnabled", "thinkingBgAlpha", "thinkingBgColor",
        "thinkingColor"
    )

    // ---------------- RANDOM 页 ----------------
    // 随机模式：按周期在若干预设动作/状态间随机切换（RandomItemFlags 控制可含哪些项）。
    //
    // 随机项规格（阈值机制唯一真相源）：总开关 randomEnabled 与 13 个随机项「相互独立」。
    // 每个随机项 = 一个独立的「默认开关」，key 与 RandomItemFlags 位掩码一一对应；
    // 阈值页「恢复默认 / 重置系统」据此重建 randomItems，使三条重置路径结果完全一致：
    //   - 恢复默认(本页)        ：取用户在阈值页设的默认开关
    //   - 重置系统(本页/阈值页) ：取下方系统出厂默认 defaultOn
    //   - 重置软件所有默认/首次安装：取 ConfigDefaults.RANDOM_ITEMS（由本表 defaultOn 推算）
    data class RandomItemSpec(val key: String, val label: String, val flag: Int, val defaultOn: Boolean)
    val RANDOM_ITEM_SPECS = listOf(
        RandomItemSpec("r_scale", "大小", RandomItemFlags.SCALE, true),
        RandomItemSpec("r_alpha", "透明度", RandomItemFlags.ALPHA, true),
        RandomItemSpec("r_gravity_enabled", "重力抛掷", RandomItemFlags.GRAVITY_ENABLED, true),
        RandomItemSpec("r_tilt_gravity", "体感重力", RandomItemFlags.TILT_GRAVITY, true),
        RandomItemSpec("r_max_speed", "最大速度", RandomItemFlags.MAX_SPEED, true),
        RandomItemSpec("r_gravity", "重力强度", RandomItemFlags.GRAVITY, true),
        RandomItemSpec("r_rebound", "反弹系数", RandomItemFlags.REBOUND, true),
        RandomItemSpec("r_gdir_v", "四边重力·上下", RandomItemFlags.GDIR_V, true),
        RandomItemSpec("r_gdir_h", "四边重力·左右", RandomItemFlags.GDIR_H, true),
        RandomItemSpec("r_rbound_top", "四边反弹·上", RandomItemFlags.RBOUND_TOP, false),
        RandomItemSpec("r_rbound_bottom", "四边反弹·下", RandomItemFlags.RBOUND_BOTTOM, false),
        RandomItemSpec("r_rbound_left", "四边反弹·左", RandomItemFlags.RBOUND_LEFT, false),
        RandomItemSpec("r_rbound_right", "四边反弹·右", RandomItemFlags.RBOUND_RIGHT, false)
    )
    /** 随机项 key -> 位掩码，供阈值页切换默认开关时即时同步当前位掩码。 */
    val RANDOM_ITEM_FLAG_MAP: Map<String, Int> = RANDOM_ITEM_SPECS.associate { it.key to it.flag }
    val RANDOM_DUALS = listOf(
        DualSliderBound(
            key = "randomPeriod", label = "周期", group = "周期",
            hardMin = 1f, hardMax = 144f,
            baseMinA = 1f, baseMaxA = 120f,
            baseMinB = 1f, baseMaxB = 120f,
            fromA = 1f, toA = 120f, stepA = 1f, defaultA = 30f,
            fromB = 1f, toB = 120f, stepB = 1f, defaultB = 120f
        )
    )
    // 总开关与随机项独立：randomEnabled 默认关；13 个随机项各有「默认开关」。
    val RANDOM_SWITCHES = listOf(
        SwitchBound("randomEnabled", "随机模式", "随机模式", false)
    ) + RANDOM_ITEM_SPECS.map { SwitchBound(it.key, it.label, "随机项", it.defaultOn) }
    val RANDOM_ORDER = listOf("randomEnabled", "randomPeriod") + RANDOM_ITEM_SPECS.map { it.key }
    /** 由阈值开关默认值重建 randomItems 位掩码（系统出厂 / 用户默认均走此函数，保证唯一真相源）。 */
    fun randomItemsFromBounds(b: PageBounds): Int {
        var mask = 0
        for (spec in RANDOM_ITEM_SPECS) {
            val sw = b.switches.firstOrNull { it.key == spec.key }
            if (sw?.default == true) mask = mask or spec.flag
        }
        return mask
    }
}

// =====================================================================
// 二、PetConfig 字段默认值（含关于页开关默认值）
// =====================================================================
/**
 * PetConfig 运行时真相源默认值（物理层单位，见文件头「AppBounds 与 ConfigDefaults 的关系」）。
 *
 * 注意单位差异：
 *   - 「透明度」本处 ALPHA 用 0-1 小数（0.7），而 AppBounds 的 alpha 滑块用 0-100 百分比——两者在 PetConfig 内换算。
 *   - gravity / maxSpeed / rebound 等物理量与 AppBounds 同名滑块行程（如 gravity 0-10000）量纲一致，仅行程更宽。
 *   - 布尔项（四边重力/反弹/吸附…）的默认开关，既决定 UI 初次勾选态，也决定数据缺失时宠物实际行为。
 * 改物理默认值改这里；改 UI 可拉范围改 AppBounds。两处 key 对齐、改动需同步核对。
 */
object ConfigDefaults {
    // 宠物运动/显示
    val SCALE = 1.4f                       // 绘制缩放倍率：1.4 = 原始 140%（与 AppBounds scale 滑块 0.5-3 行程一致）
    val GRAVITY = 3000f                    // 重力加速度，物理引擎数值（越大下落越快）；AppBounds gravity 滑块更宽行程
    val REBOUND_RATIO = 0.7f              // 反弹能量保留比：0=不弹，1=完全弹性碰撞
    val WALK_SPEED = 200f                 // 走路动画时物理驱动的横向速度（px/s）
    val FOLLOW_SPEED = 400f              // 拖动释放后宠物“追手/回归”的跟随速度（px/s）
    val MAX_SPEED = 7100f                // 速度上限，防止数值爆炸（px/s）
    val ALPHA = 0.7f                      // 整体透明度：0-1 小数（UI 滑块是 0-100 百分比，PetConfig 内换算）
    val CLICK_THROUGH = false             // 点击穿透：true 时宠物不拦截触摸（点击会落到底层窗口）
    val BOUNCE_VIBRATE = true             // 落地/反弹时是否触发一次振动反馈
    val ENABLED = true                    // 总开关：false 时宠物整体停用（不渲染、不运动）
    val VISIBLE = true                    // 可见性：false 时隐藏宠物（仍常驻、不卸载）
    // 四边重力
    val GRAVITY_TOP = false               // 是否启用“向屏幕顶边”的重力分量
    val GRAVITY_BOTTOM = true             // 是否启用“向屏幕底边”的重力分量（默认开，宠物落地）
    val GRAVITY_LEFT = false              // 是否启用“向屏幕左边”的重力分量
    val GRAVITY_RIGHT = false             // 是否启用“向屏幕右边”的重力分量
    val GRAVITY_ENABLED = true            // 四边重力总开关（关闭后无方向性重力，靠惯性/反弹运动）
    val TILT_GRAVITY = false             // 重力是否随设备方向传感器倾斜（陀螺仪/重力感应）
    // 四边反弹
    val REBOUND_TOP = true                // 撞到屏幕顶边是否反弹
    val REBOUND_BOTTOM = true             // 撞到屏幕底边是否反弹
    val REBOUND_LEFT = true               // 撞到屏幕左边是否反弹
    val REBOUND_RIGHT = true              // 撞到屏幕右边是否反弹
    val REBOUND_ENABLED = true            // 四边反弹总开关
    // 吸附
    val SNAP_ENABLED = true               // 吸附边缘总开关：靠近边/角时停住并切“吸附探头”状态
    val SNAP_TOP = true                   // 是否吸附屏幕顶边
    val SNAP_BOTTOM = true                // 是否吸附屏幕底边
    val SNAP_LEFT = true                  // 是否吸附屏幕左边
    val SNAP_RIGHT = true                 // 是否吸附屏幕右边
    val SNAP_THRESHOLD = 100f             // 吸附触发距离（px，可为负=外扩吸附区）；AppBounds 同名校验更宽
    val SHOW_SNAP_LINE = true             // 是否绘制吸附辅助线（调试/对齐用）
    // 边界偏移
    val OFFSET_TOP = 140f                 // 活动区顶部内缩（px，正=向内留白，负=向外出血）
    val OFFSET_BOTTOM = 140f              // 活动区底部内缩（px）
    val OFFSET_LEFT = 0f                  // 活动区左侧内缩（px）
    val OFFSET_RIGHT = 0f                 // 活动区右侧内缩（px）
    // 输入法适应
    val IME_ADAPT = true                  // 键盘弹起时是否自动上抬宠物，避免被输入法遮挡
    val IME_LIFT_OFFSET = 100f           // 键盘弹起时上抬的额外偏移（px）
    val IME_RESET_BOTTOM_OFFSET = true   // 键盘收起后是否把之前底部偏移复位
    val IME_HIDE = false                 // 键盘时隐藏：键盘弹出时暂时隐藏「酥」且不可点击（不修改 visible 设置），键盘收起恢复
    // 显示/调试开关（含关于页不可改阈值的开关默认值）
    val SHOW_DEBUG = false                // 调试模式总开关（叠加调试信息）
    val SHOW_IMAGE_BORDER = false         // 显示边框：图片窗口黑线 + 脚锚点十字（判断图片显示位置）
    val SHOW_CONTROL_BORDER = false       // 控制边框：命中/action 彩色调试框（贴实际控制窗）
    // 命中模式三态：像素(全框+alpha) / 边界(全框整窗) / 核心(脚底盒)
    const val HIT_PIXEL = 0
    const val HIT_BOUNDARY = 1
    const val HIT_CORE = 2
    const val HIT_MODE = HIT_CORE          // 默认核心(脚底盒)命中
    // 核心(脚底盒)命中区尺寸：128 逻辑基数（1X=128），几何计算时 × scaleFactor（宠物大小）转屏幕像素
    const val CTRL_BOX_WIDTH = 80f         // 宽：脚左右各 40
    const val CTRL_BOX_HEIGHT = 80f        // 高：脚以上 80
    const val CTRL_BOX_VOFFSET = 0f        // 垂直偏移：默认 0（脚为基准，正=盒子上移）
    val SHOW_BOUND_OFFSET = false         // 是否可视化活动范围边界偏移
    val SHOW_SNAP_OFFSET = false          // 是否可视化吸附距离阈值区域
    // 碎碎念
    val THINKING_ENABLED = true           // 底部碎碎念气泡总开关
    val THINKING_OFFSET = 0f              // 气泡相对宠物底部的纵向偏移（px，正=更靠上）
    val THINKING_TEXT_SIZE = 10f          // 气泡文字字号（sp）
    val THINKING_ALPHA = 1f               // 气泡整体透明度 0-1
    val THINKING_COLOR = 0xFF333333.toInt() // 气泡文字颜色（ARGB，此处深灰）
    val THINKING_EMPTY_MIN = 2            // 两次气泡间的空窗最短时长（秒）
    val THINKING_EMPTY_MAX = 15           // 两次气泡间的空窗最长时长（秒），实际每次随机取 [min,max]
    val THINKING_FLASH_IN = true          // 气泡淡入（闪现）动画
    val THINKING_FLASH_OUT = true         // 气泡淡出（闪回）动画
    // 碎碎念背景
    val THINKING_BG_ENABLED = false        // 气泡背景是否显示（默认关）
    val THINKING_BG_COLOR = 0xFFFFFFFF.toInt() // 气泡背景颜色（ARGB，默认白）
    val THINKING_BG_ALPHA = 50             // 气泡背景透明度 0-100（默认 50）
    // 随机模式
    val RANDOM_ENABLED = false            // 随机模式总开关：按周期在预设动作/状态间随机切换
    // 随机时可包含的预设动作/状态集合（位掩码）。
    // 唯一真相源改为阈值机制：与 AppBounds.RANDOM_ITEM_SPECS 的 defaultOn 完全一致，
    // 使「重置软件所有默认(=首次安装)」与阈值页「恢复默认 / 重置系统」结果统一。
    val RANDOM_ITEMS = run {
        var mask = 0
        AppBounds.RANDOM_ITEM_SPECS.forEach { if (it.defaultOn) mask = mask or it.flag }
        mask
    }
    val RANDOM_PERIOD_MIN = 30            // 随机切换周期下限（分钟，与 AppBounds randomPeriod 滑块一致）
    val RANDOM_PERIOD_MAX = 120           // 随机切换周期上限（分钟），实际每次随机取 [min,max]
    // 数量与夜间
    val PET_COUNT = 1                     // 同时存在宠物数量
    val PET_COUNT_MIN = 1                 // 数量下限（UI 约束）
    val PET_COUNT_MAX = 30                // 数量上限（UI 约束；阈值页极限范围可到 30，启动时硬限 3 防崩溃）
    val FORCE_NIGHT_ON = false            // 强制夜间模式（始终暗色调试用）
    val FORCE_NIGHT_OFF = false           // 强制关闭夜间模式
    // 资源包：是否使用“芝麻酥”新图（关于页开关，默认关 = 沿用 assets/img/ 旧图）。
    val USE_NEW_SPRITE = true
    // 仅单个碎碎念：开启=仅主实例显示碎碎念；关闭=每个实例各自显示（关于页开关，默认开）。
    val SINGLE_TRAY_MSG = true
}

/** 颜色阈值项（预设色板 + 默认色）。字体色与背景色共用同一套预设。 */
data class ColorBound(
    val key: String,
    val label: String,
    val group: String,
    val presets: List<Int>,
    val default: Int
)

/** 碎碎念颜色预设板（字体色与背景色一致）。 */
val THINKING_PRESET_COLORS = listOf(
    0xFF000000.toInt(),  // 黑
    0xFFFFFFFF.toInt(),  // 白
    0xFFFFFF00.toInt(),  // 黄
    0xFF00FF00.toInt(),  // 绿
    0xFF0000FF.toInt(),  // 蓝
    0xFFFF0000.toInt()   // 红
)
