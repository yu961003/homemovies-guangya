package com.example.localmovielibrarz.playback.lut

/**
 * 实时调色的 7 个参数与强度。
 *
 * 取值区间与参考实现 `PARAM_LIMITS`（mpv_realtime_lut.py:219-227）一致：-100..100。
 * 单位是「参考实现的参数单位」，不是百分比——参考实现内部用 `v/50` 做归一
 * （见 `LutColorMath.applyTempTint` 的 `temp/50`），所以 ±50 才是「一个单位」的力度。
 * 界面上显示成 -100..100 的滑杆，与参考实现的 GUI 完全一致。
 *
 * ## 为什么是不可变 data class
 *
 * 参数会被 UI（StateFlow）、后台生成（协程）、磁盘（SharedPreferences）三方同时读。
 * 不可变值 + `copy` 让「滑杆每一帧都在改」这件事不需要锁：改出来的新值整体替换旧值，
 * 生成任务拿到的永远是某一时刻的完整快照，不会读到「色温是新的、饱和度是旧的」这种撕裂态。
 */
data class LutParams(
    val temperature: Int = 0,
    val tint: Int = 0,
    val saturation: Int = 0,
    val brightness: Int = 0,
    val contrast: Int = 0,
    val highlight: Int = 0,
    val shadow: Int = 0
) {

    init {
        require(temperature in MIN..MAX) { "色温超出范围：$temperature" }
        require(tint in MIN..MAX) { "色调超出范围：$tint" }
        require(saturation in MIN..MAX) { "饱和度超出范围：$saturation" }
        require(brightness in MIN..MAX) { "亮度超出范围：$brightness" }
        require(contrast in MIN..MAX) { "对比度超出范围：$contrast" }
        require(highlight in MIN..MAX) { "高光超出范围：$highlight" }
        require(shadow in MIN..MAX) { "阴影超出范围：$shadow" }
    }

    /**
     * 是否「等于不动」。
     *
     * 全 0 是恒等变换（每个 `apply*` 都在参数为 0 时短路返回原值），所以可以据此
     * 跳过整个 LUT 生成与下发流程。参考实现没有这个判断——它是手动按钮触发的，
     * 而这里是滑杆实时触发，必须能判断「这次改动有没有实际意义」。
     */
    fun isNeutral(): Boolean =
        temperature == 0 && tint == 0 && saturation == 0 &&
            brightness == 0 && contrast == 0 && highlight == 0 && shadow == 0

    /** 与 [preset] 的差是否超过容差，用于 UI 上判断「是否已偏离预设」。 */
    fun differsFrom(other: LutParams): Boolean = this != other

    /** 按 [key] 读一个参数，供通用滑杆回调使用。 */
    fun get(key: LutParamKey): Int = when (key) {
        LutParamKey.Temperature -> temperature
        LutParamKey.Tint -> tint
        LutParamKey.Saturation -> saturation
        LutParamKey.Brightness -> brightness
        LutParamKey.Contrast -> contrast
        LutParamKey.Highlight -> highlight
        LutParamKey.Shadow -> shadow
    }

    /** 按 [key] 写一个参数。超出范围时夹取而不是抛异常——滑杆回调不该把 App 打崩。 */
    fun set(key: LutParamKey, value: Int): LutParams {
        val v = value.coerceIn(MIN, MAX)
        return when (key) {
            LutParamKey.Temperature -> copy(temperature = v)
            LutParamKey.Tint -> copy(tint = v)
            LutParamKey.Saturation -> copy(saturation = v)
            LutParamKey.Brightness -> copy(brightness = v)
            LutParamKey.Contrast -> copy(contrast = v)
            LutParamKey.Highlight -> copy(highlight = v)
            LutParamKey.Shadow -> copy(shadow = v)
        }
    }

    /**
     * 序列化成 SharedPreferences 的 7 个整数键。
     *
     * 用 `key.prefKey` 而不是下标或名字拼接：名字拼错是静默失败（读到默认值 0），
     * 那种 bug 在"参数保存后重开变回默认"的现象里极难定位。
     */
    fun toPrefMap(): Map<String, Int> = LutParamKey.entries.associate { it.prefKey to get(it) }

    companion object {

        const val MIN = -100
        const val MAX = 100

        /** 全 0：恒等变换。 */
        val NEUTRAL = LutParams()

        /**
         * 从偏好设置还原。缺键或越界一律回落 0，不抛异常：
         * 用户升级 / 降级 App 时残留的旧偏好不应该让播放器起不来。
         */
        fun fromPrefMap(read: (String) -> Int?): LutParams {
            fun pick(key: LutParamKey): Int =
                (read(key.prefKey) ?: 0).coerceIn(MIN, MAX)
            return LutParams(
                temperature = pick(LutParamKey.Temperature),
                tint = pick(LutParamKey.Tint),
                saturation = pick(LutParamKey.Saturation),
                brightness = pick(LutParamKey.Brightness),
                contrast = pick(LutParamKey.Contrast),
                highlight = pick(LutParamKey.Highlight),
                shadow = pick(LutParamKey.Shadow)
            )
        }
    }
}

/**
 * 7 个参数的身份。
 *
 * 把「键名」和「中文标签」放在枚举里，而不是散在三处（UI 文案 / 偏好键 / 日志），
 * 是为了让加参数变成改一处。参考实现用 `PARAM_KEYS` + `PARAM_LABELS_CN` 两个字典
 * （mpv_realtime_lut.py:217-232），这里合并成一个枚举。
 */
enum class LutParamKey(
    /** SharedPreferences 里的键名。带 `lut_` 前缀，避免与其它设置项撞名。 */
    val prefKey: String,
    /** 界面上显示的名字，与参考实现 `PARAM_LABELS_CN` 一致。 */
    val label: String,
    /** 一句话说明这个参数「往哪边调会怎样」，直接显示在滑杆下方。 */
    val hint: String
) {
    Temperature("lut_temp", "色温", "负数偏冷（蓝），正数偏暖（黄）"),
    Tint("lut_tint", "色调", "负数偏绿，正数偏品红"),
    Saturation("lut_sat", "饱和度", "负数变淡，正数变浓"),
    Brightness("lut_bright", "亮度", "提亮时自动压高光、护暗部"),
    Contrast("lut_contrast", "对比度", "绕中灰扩张，高光处自动收敛"),
    Highlight("lut_highlight", "高光", "只影响亮度 0.5 以上的区域"),
    Shadow("lut_shadow", "阴影", "只影响亮度 0.5 以下的区域")
}

/**
 * 预设。
 *
 * 三个模板全部来自参考实现（mpv_realtime_lut.py:137-215），**数值一字未改**：
 * - [Standard] = `JY_TEMPLATE`：日常片源的标准调法，轻微偏冷 + 压制高光。
 * - [HighMatch] = `JY_TEMPLATE_HIGH_MATCH`：番号/发行年份与目标高度匹配的片源，色温几乎不动。
 * - [ExtremeDark] = `JY_TEMPLATE_EXTREME_DARK`：极暗片源，大幅提亮 + 大幅提阴影。
 *
 * 参考实现是靠自动分析在三个模板里**选一个**再搜参数；这里把它们做成可点的预设，
 * 让用户能在不跑自动分析的情况下直接拿到同样的起点。差异已在交付文档中写明。
 */
enum class LutPreset(
    val label: String,
    val description: String,
    val params: LutParams
) {
    Standard(
        label = "标准",
        description = "轻微偏冷、压高光、抬阴影 · 通用",
        params = LutParams(
            temperature = -20,
            tint = 6,
            saturation = 0,
            brightness = 0,
            contrast = 0,
            highlight = -2,
            shadow = 2
        )
    ),
    HighMatch(
        label = "高匹配",
        description = "色温几乎不动、略提亮 · 片源与目标高度一致时",
        params = LutParams(
            temperature = -3,
            tint = 6,
            saturation = 0,
            brightness = 5,
            contrast = 0,
            highlight = -2,
            shadow = 2
        )
    ),
    ExtremeDark(
        label = "极暗",
        description = "大幅提亮与提阴影 · 暗部细节丢失严重的片源",
        params = LutParams(
            temperature = -18,
            tint = 3,
            saturation = -1,
            brightness = 25,
            contrast = -2,
            highlight = -1,
            shadow = 20
        )
    ),
    Neutral(
        label = "关闭调色",
        description = "全部归零 · 画面回到片源原样",
        params = LutParams.NEUTRAL
    );

    companion object {
        /** 供 UI 展示的顺序：先三个参考模板，最后是"归零"。 */
        val orderedForUi: List<LutPreset> = listOf(Standard, HighMatch, ExtremeDark, Neutral)

        fun fromName(name: String?): LutPreset? = entries.firstOrNull { it.name == name }
    }
}
