package com.example.localmovielibrarz.playback.lut

/**
 * 创意调色参数（v2 新增）。
 *
 * ## 为什么不塞进 [LutParams]
 *
 * [LutParams] 的 7 个参数与参考实现 `mpv_realtime_lut.py` 一一对应，并且被
 * [LutParamPredictor] 当作搜索维度——往里加字段会让自动分析的搜索空间、模板、
 * 单测期望值全部跟着变。创意参数是「在参考调色链之后」追加的审美层，
 * 自动分析不碰它们，所以独立成一个值类型：
 *
 * - 全部为 0 时 [isNeutral] 为真，[LutGradeKernel] 会整段跳过，**输出与旧版逐位一致**
 *   （包括最后那次硬裁剪），升级用户的画面不会有任何变化；
 * - 非 0 时才进入 Oklab 色域里的色相/自然饱和度/分离色调/褪色运算，并改用
 *   保色相的软裁剪代替逐通道硬裁剪（硬裁剪在高饱和区域会把橙色裁成黄色）。
 *
 * 取值区间：
 * - [vibrance] / [hue] / [fade] / [splitBalance]：-100..100（[fade] 只取 0..100）
 * - [shadowTintHue] / [highlightTintHue]：0..359（色轮角度，0=红、120=绿、240=蓝）
 * - [shadowTintAmount] / [highlightTintAmount] / [skinProtect]：0..100
 */
data class LutCreativeParams(
    val vibrance: Int = 0,
    val hue: Int = 0,
    val fade: Int = 0,
    val shadowTintHue: Int = DEFAULT_SHADOW_HUE,
    val shadowTintAmount: Int = 0,
    val highlightTintHue: Int = DEFAULT_HIGHLIGHT_HUE,
    val highlightTintAmount: Int = 0,
    val splitBalance: Int = 0,
    val skinProtect: Int = 0
) {
    init {
        require(vibrance in -100..100) { "自然饱和度超出范围：$vibrance" }
        require(hue in -100..100) { "色相超出范围：$hue" }
        require(fade in 0..100) { "褪色超出范围：$fade" }
        require(shadowTintHue in 0..359) { "阴影色相超出范围：$shadowTintHue" }
        require(highlightTintHue in 0..359) { "高光色相超出范围：$highlightTintHue" }
        require(shadowTintAmount in 0..100) { "阴影染色强度超出范围：$shadowTintAmount" }
        require(highlightTintAmount in 0..100) { "高光染色强度超出范围：$highlightTintAmount" }
        require(splitBalance in -100..100) { "分离色调平衡超出范围：$splitBalance" }
        require(skinProtect in 0..100) { "肤色保护超出范围：$skinProtect" }
    }

    /**
     * 是否不改变画面。
     *
     * 注意 [skinProtect] 单独不构成「改变」：它只是削弱其它算子在肤色上的力度，
     * 其它算子都为 0 时它没有作用对象。色相角本身也不构成改变（强度为 0 时无效）。
     */
    fun isNeutral(): Boolean =
        vibrance == 0 && hue == 0 && fade == 0 &&
            shadowTintAmount == 0 && highlightTintAmount == 0

    /** 是否包含会改变色度（而非仅亮度）的算子；肤色保护只对这类算子生效。 */
    fun hasChromaticOps(): Boolean =
        vibrance != 0 || hue != 0 || shadowTintAmount != 0 || highlightTintAmount != 0

    fun get(key: LutCreativeKey): Int = when (key) {
        LutCreativeKey.Vibrance -> vibrance
        LutCreativeKey.Hue -> hue
        LutCreativeKey.Fade -> fade
        LutCreativeKey.ShadowTintHue -> shadowTintHue
        LutCreativeKey.ShadowTintAmount -> shadowTintAmount
        LutCreativeKey.HighlightTintHue -> highlightTintHue
        LutCreativeKey.HighlightTintAmount -> highlightTintAmount
        LutCreativeKey.SplitBalance -> splitBalance
        LutCreativeKey.SkinProtect -> skinProtect
    }

    /** 按键写入；越界值夹到合法区间（滑杆回调不该把 App 打崩）。 */
    fun set(key: LutCreativeKey, value: Int): LutCreativeParams {
        val v = value.coerceIn(key.min, key.max)
        return when (key) {
            LutCreativeKey.Vibrance -> copy(vibrance = v)
            LutCreativeKey.Hue -> copy(hue = v)
            LutCreativeKey.Fade -> copy(fade = v)
            LutCreativeKey.ShadowTintHue -> copy(shadowTintHue = v)
            LutCreativeKey.ShadowTintAmount -> copy(shadowTintAmount = v)
            LutCreativeKey.HighlightTintHue -> copy(highlightTintHue = v)
            LutCreativeKey.HighlightTintAmount -> copy(highlightTintAmount = v)
            LutCreativeKey.SplitBalance -> copy(splitBalance = v)
            LutCreativeKey.SkinProtect -> copy(skinProtect = v)
        }
    }

    /** 参与 LUT 文件名的签名片段；中性时为空串，保证旧版签名（以及缓存文件名）不变。 */
    fun signaturePart(): String = if (isNeutral()) "" else buildString {
        append("_cv").append(vibrance)
        append("_ch").append(hue)
        append("_cf").append(fade)
        append("_cs").append(shadowTintHue).append('x').append(shadowTintAmount)
        append("_cl").append(highlightTintHue).append('x').append(highlightTintAmount)
        append("_cb").append(splitBalance)
        append("_cp").append(skinProtect)
    }

    fun toPrefMap(): Map<String, Int> = LutCreativeKey.entries.associate { it.prefKey to get(it) }

    companion object {
        const val DEFAULT_SHADOW_HUE = 200
        const val DEFAULT_HIGHLIGHT_HUE = 35

        val NEUTRAL = LutCreativeParams()

        /** 从偏好还原；缺键回落默认值，越界夹取。 */
        fun fromPrefMap(read: (String) -> Int?): LutCreativeParams {
            var out = NEUTRAL
            for (key in LutCreativeKey.entries) {
                val stored = read(key.prefKey) ?: continue
                out = out.set(key, stored)
            }
            return out
        }
    }
}

/**
 * 创意参数的身份。偏好键统一以 `lut_` 开头：
 * `AppSettingsRepository.observeLutSettings()` 按这个前缀监听变化，
 * 换前缀会导致「滑杆拖了画面不动」。
 */
enum class LutCreativeKey(
    val prefKey: String,
    val label: String,
    val hint: String,
    val min: Int,
    val max: Int,
    val isAngle: Boolean = false
) {
    Vibrance("lut_cr_vibrance", "自然饱和度", "只提升低饱和区域，已经很浓的颜色与肤色几乎不动", -100, 100),
    Hue("lut_cr_hue", "色相", "整体旋转色相（±30°），在 Oklab 色域内计算、不改亮度", -100, 100),
    Fade("lut_cr_fade", "褪色", "抬起黑位、压低白位，做出胶片的哑光质感", 0, 100),
    ShadowTintHue("lut_cr_sh_hue", "阴影色相", "分离色调：暗部染上的颜色", 0, 359, isAngle = true),
    ShadowTintAmount("lut_cr_sh_amt", "阴影染色", "分离色调：暗部染色强度", 0, 100),
    HighlightTintHue("lut_cr_hl_hue", "高光色相", "分离色调：亮部染上的颜色", 0, 359, isAngle = true),
    HighlightTintAmount("lut_cr_hl_amt", "高光染色", "分离色调：亮部染色强度", 0, 100),
    SplitBalance("lut_cr_balance", "色调平衡", "负数让阴影染色覆盖更多中间调，正数让高光覆盖更多", -100, 100),
    SkinProtect("lut_cr_skin", "肤色保护", "削弱饱和度/色温/色相/染色对肤色区域的影响", 0, 100);
}

/**
 * 风格预设（Look）。
 *
 * 对应 mpvRx 的「视频滤镜预设」能力，但**落在 LUT 上**而不是 mpv 的
 * brightness/contrast/saturation/gamma/hue 属性上，原因有二：
 * 1. 两个内核都能用：mpv 走 `target-lut`，exo 走 media3 `SingleColorLut`；
 *    mpv 的 eq 属性在 exo 侧没有对应物，会造成「换内核风格就没了」。
 * 2. 与参考调色链同一管线：风格与手动微调、自动分析叠加时语义一致，
 *    不会出现两套调色系统互相打架。
 *
 * 数值是本项目自行调校的，不复制任何上游实现。
 */
enum class LutLook(
    val label: String,
    val description: String,
    val params: LutParams,
    val creative: LutCreativeParams
) {
    Natural(
        "自然", "轻微提通透度，肤色不动",
        LutParams(contrast = 4, saturation = 0),
        LutCreativeParams(vibrance = 18, skinProtect = 70)
    ),
    Vivid(
        "鲜艳", "饱和与对比同时提升，适合风景与动画",
        LutParams(contrast = 12, saturation = 10, brightness = 3),
        LutCreativeParams(vibrance = 30, skinProtect = 55)
    ),
    Cinematic(
        "电影感", "青橙分离 + 轻褪色，压高光",
        LutParams(contrast = 10, highlight = -8, shadow = 4, saturation = -4),
        LutCreativeParams(
            fade = 12, shadowTintHue = 195, shadowTintAmount = 28,
            highlightTintHue = 32, highlightTintAmount = 22, skinProtect = 60
        )
    ),
    WarmFilm(
        "暖调胶片", "偏暖、哑光黑位，复古柔和",
        LutParams(temperature = 14, contrast = -4, highlight = -6),
        LutCreativeParams(fade = 22, highlightTintHue = 38, highlightTintAmount = 18, vibrance = -8)
    ),
    CoolNight(
        "冷调夜景", "暗部偏蓝、高光保持中性",
        LutParams(temperature = -14, contrast = 6, shadow = 6),
        LutCreativeParams(shadowTintHue = 220, shadowTintAmount = 30, splitBalance = -20, skinProtect = 50)
    ),
    SoftPastel(
        "柔和粉彩", "低对比、低饱和、略提亮",
        LutParams(brightness = 6, contrast = -10, saturation = -12),
        LutCreativeParams(fade = 16, vibrance = 8, hue = 4)
    ),
    Mono(
        "黑白", "去色后略提对比",
        // 饱和度算子的缩放是 1 + sat/50*0.82；-61 时缩放≈0（完全去色），-100 会反相成补色。
        LutParams(saturation = -61, contrast = 10),
        LutCreativeParams()
    ),
    DeepBlack(
        "深邃黑", "压暗部、提对比，适合 OLED",
        LutParams(contrast = 14, shadow = -10, highlight = 2),
        LutCreativeParams(vibrance = 6)
    );

    companion object {
        fun fromName(name: String?): LutLook? = entries.firstOrNull { it.name == name }
    }
}
