package com.example.localmovielibrarz.playback.lut

/**
 * 一次实时调色的完整状态：开关 + 强度 + 尺寸 + 7 个参考参数 + 创意层 + 可选风格 LUT。
 *
 * 这是「设置里存的东西」与「引擎要的东西」之间的唯一契约。把它做成不可变值，
 * 是为了让设置变更能被 `StateFlow` 安全地广播给正在播放的页面。
 *
 * ## 为什么强度单独拿出来
 *
 * mpv 的 `--target-lut` 只能加载一张 `.cube`，没有"不透明度"参数（见
 * [CubeLut.blendTowards]）。所以强度必须在**生成 LUT 时**就混进去。
 *
 * ## v2 兼容性
 *
 * 新增的 [creative] / [overlayLutPath] / [overlayMixPercent] 全部有「不生效」的默认值；
 * 在默认值下 [signature] 与 v1 **逐字相同**，因此：
 * - 已经落盘的 LUT 缓存文件继续命中；
 * - 单测 `settingsSignatureChangesWithEveryInputThatAffectsTheImage` 的期望不变。
 *
 * @property enabled 总开关。关闭时不写文件、不下发属性，保持片源原色。
 * @property lutSize LUT 边长。
 * @property strengthPercent 0..100 的混合强度，0 等同关闭，100 是参数原样。
 * @property params 7 个参考色彩参数（自动分析的搜索维度）。
 * @property creative 创意层（自然饱和度 / 色相 / 褪色 / 分离色调 / 肤色保护）。
 * @property overlayLutPath 用户导入的风格 LUT 的**应用私有目录**绝对路径；文件名是内容哈希，
 *   因此路径本身就能唯一标识内容（签名不需要再 stat 文件）。
 * @property overlayMixPercent 风格 LUT 的混合比例 0..100。
 */
data class LutSettings(
    val enabled: Boolean = DEFAULT_ENABLED,
    val lutSize: Int = DEFAULT_LUT_SIZE,
    val strengthPercent: Int = DEFAULT_STRENGTH,
    val params: LutParams = LutParams.NEUTRAL,
    val creative: LutCreativeParams = LutCreativeParams.NEUTRAL,
    val overlayLutPath: String? = null,
    val overlayMixPercent: Int = DEFAULT_OVERLAY_MIX
) {

    init {
        require(lutSize in CubeLut.MIN_SIZE..CubeLut.MAX_SIZE) {
            "LUT 尺寸超出范围：$lutSize"
        }
        require(strengthPercent in MIN_STRENGTH..MAX_STRENGTH) {
            "LUT 强度超出范围：$strengthPercent"
        }
        require(overlayMixPercent in 0..100) { "风格 LUT 混合比例超出范围：$overlayMixPercent" }
    }

    /** 是否带有有效的风格 LUT 叠加。 */
    val hasOverlay: Boolean
        get() = !overlayLutPath.isNullOrBlank() && overlayMixPercent > 0

    /**
     * 实际要不要下发 LUT。
     *
     * 「不必下发」的条件：总开关关、强度 0、或者三层（参考参数 / 创意层 / 风格 LUT）全都不改变画面。
     * 下发恒等 LUT 画面也一样，但 mpv 会为它重载一次着色，造成可见的一帧闪烁。
     */
    val shouldApply: Boolean
        get() = enabled && strengthPercent > 0 &&
            (!params.isNeutral() || !creative.isNeutral() || hasOverlay)

    /**
     * 当前设置对应的 LUT 身份串，用作缓存文件名与去重键。
     *
     * 不含 `enabled`——开关变化走"下发空路径"而不是"换文件"，见 [effectiveFileName]。
     */
    fun signature(): String = buildString {
        append("s").append(lutSize)
        append("_k").append(strengthPercent)
        append("_t").append(params.temperature)
        append("_n").append(params.tint)
        append("_sa").append(params.saturation)
        append("_b").append(params.brightness)
        append("_c").append(params.contrast)
        append("_h").append(params.highlight)
        append("_sh").append(params.shadow)
        append(creative.signaturePart())
        if (hasOverlay) {
            append("_x").append(overlayId(overlayLutPath!!))
            append("m").append(overlayMixPercent)
        }
    }

    /** 下发给 mpv 的文件名；不该生效时返回 null（调用方据此把 `target-lut` 置空）。 */
    fun effectiveFileName(): String? =
        if (shouldApply) "$FILE_PREFIX${signature()}.cube" else null

    /**
     * 生成这张 LUT 的数据（经 [LutEngine] 的内存缓存）。
     *
     * 只在 [shouldApply] 为真时有意义。
     */
    fun buildLut(): CubeLut = LutEngine.lutFor(this)

    fun withParams(next: LutParams): LutSettings = copy(params = next)

    fun withCreative(next: LutCreativeParams): LutSettings = copy(creative = next)

    fun withStrength(next: Int): LutSettings =
        copy(strengthPercent = next.coerceIn(MIN_STRENGTH, MAX_STRENGTH))

    fun withLutSize(next: Int): LutSettings =
        copy(lutSize = next.coerceIn(CubeLut.MIN_SIZE, CubeLut.MAX_SIZE))

    /** 套用风格预设：参考参数与创意层一起替换，并打开总开关。 */
    fun withLook(look: LutLook): LutSettings =
        copy(enabled = true, params = look.params, creative = look.creative)

    companion object {

        const val MIN_STRENGTH = 0
        const val MAX_STRENGTH = 100

        /** 默认强度 80：参考实现的预设直接用会偏重，80% 保留大部分意图又留出退路。 */
        const val DEFAULT_STRENGTH = 80

        /** 默认 33：3D LUT 的行业惯例值，也是参考实现的默认值。 */
        const val DEFAULT_LUT_SIZE = 33

        /** 默认关闭：LUT 是审美取向，不该在用户没要求时改他的画面。 */
        const val DEFAULT_ENABLED = false

        const val DEFAULT_OVERLAY_MIX = 100

        /** 缓存文件名前缀，清理时按它扫。 */
        const val FILE_PREFIX = "lut_"

        /**
         * 设置页可选的尺寸档。v2 生成耗时已下降（见 README 的 LUT 性能表），
         * 文案按「相对代价」描述，不再写死毫秒数。
         */
        val SIZE_OPTIONS: List<LutSizeOption> = listOf(
            LutSizeOption(17, "17 档 · 最快", "暗部可能见台阶"),
            LutSizeOption(33, "33 档 · 推荐", "行业惯例值"),
            LutSizeOption(65, "65 档 · 最平滑", "生成量约为 33 档的 7.6 倍")
        )

        /** 只保留当前有效的那张，删掉同前缀的其它旧文件。 */
        fun staleFileNames(existing: Collection<String>, keep: String?): List<String> =
            existing.filter { name -> name.startsWith(FILE_PREFIX) && name != keep }

        /** 风格 LUT 的短标识：文件名（内容哈希）去扩展名后取前 12 位。 */
        internal fun overlayId(path: String): String =
            path.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.').take(12)
    }
}

/**
 * 设置页里的一个尺寸档。
 */
data class LutSizeOption(
    val size: Int,
    val title: String,
    val description: String
)
