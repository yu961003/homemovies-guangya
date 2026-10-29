package com.example.localmovielibrarz.playback.lut

/**
 * 一次实时调色的完整状态：开关 + 强度 + 尺寸 + 7 个参数。
 *
 * 这是「设置里存的东西」与「引擎要的东西」之间的唯一契约。把它做成不可变值，
 * 是为了让设置变更能被 `StateFlow` 安全地广播给正在播放的页面：播放器拿到的是
 * 某一时刻的完整快照，而不是「开关已经是新的、参数还是旧的」这种中间态。
 *
 * ## 为什么强度单独拿出来
 *
 * mpv 的 `--target-lut` 只能加载一张 `.cube`，没有"不透明度"参数（见
 * [CubeLut.blendTowards]）。所以强度必须在**生成 LUT 时**就混进去。它不属于
 * [LutParams]（那 7 个是与参考实现一一对应的色彩参数），而属于"怎么用这些参数"。
 *
 * @property enabled 总开关。关闭时不写文件、不下发属性，mpv 保持片源原色。
 * @property lutSize LUT 边长。17 生成最快但暗部有可见台阶，65 最平滑但生成要几十毫秒。
 * @property strengthPercent 0..100 的混合强度，0 等同关闭，100 是参数原样。
 * @property params 7 个色彩参数。
 */
data class LutSettings(
    val enabled: Boolean = DEFAULT_ENABLED,
    val lutSize: Int = DEFAULT_LUT_SIZE,
    val strengthPercent: Int = DEFAULT_STRENGTH,
    val params: LutParams = LutParams.NEUTRAL
) {

    init {
        require(lutSize in CubeLut.MIN_SIZE..CubeLut.MAX_SIZE) {
            "LUT 尺寸超出范围：$lutSize"
        }
        require(strengthPercent in MIN_STRENGTH..MAX_STRENGTH) {
            "LUT 强度超出范围：$strengthPercent"
        }
    }

    /**
     * 实际要不要下发 LUT。
     *
     * 三个「不必下发」的条件：总开关关、强度 0、参数全 0。后两个都会生成一张
     * 恒等 LUT —— 下发了画面也一样，但 mpv 会为它重载一次着色，造成可见的一帧闪烁。
     * 参考实现没有这层判断（它是手动按钮触发的，闪一下无所谓），这里是滑杆实时触发。
     */
    val shouldApply: Boolean
        get() = enabled && strengthPercent > 0 && !params.isNeutral()

    /**
     * 当前设置对应的 LUT 身份串，用作缓存文件名。
     *
     * 把「影响生成结果」的全部输入都编进来：参数、尺寸、强度。任何一项变了名字就变，
     * 因此 mpv 不可能拿到「名字对、内容旧」的陈旧文件。不含 `enabled` —— 开关变化
     * 走"下发空路径"而不是"换文件"，见 [effectiveFileName]。
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
    }

    /**
     * 下发给 mpv 的文件名。
     *
     * 无效（不该生效）时返回 null，调用方据此把 `target-lut` 置空。
     * 用固定前缀 + 签名，便于清理时按前缀扫描旧文件。
     */
    fun effectiveFileName(): String? =
        if (shouldApply) "$FILE_PREFIX${signature()}.cube" else null

    /**
     * 生成这张 LUT 的数据。
     *
     * 只在 [shouldApply] 为真时有意义；为假时返回恒等 LUT（调用方应先判 [shouldApply]）。
     */
    fun buildLut(): CubeLut = LutColorMath.generateScaledLut(lutSize, params, strengthPercent)

    fun withParams(next: LutParams): LutSettings = copy(params = next)

    fun withStrength(next: Int): LutSettings =
        copy(strengthPercent = next.coerceIn(MIN_STRENGTH, MAX_STRENGTH))

    fun withLutSize(next: Int): LutSettings =
        copy(lutSize = next.coerceIn(CubeLut.MIN_SIZE, CubeLut.MAX_SIZE))

    companion object {

        const val MIN_STRENGTH = 0
        const val MAX_STRENGTH = 100

        /**
         * 默认强度 80。
         *
         * 不是 100：参考实现的预设本身是"分析后搜到的最优值"，直接用会偏重；
         * 80% 保留大部分意图又留出退路，用户觉得不够可以拉到 100。
         */
        const val DEFAULT_STRENGTH = 80

        /**
         * 默认 33。
         *
         * 这是 3D LUT 的行业惯例值，也是参考实现的默认值（`size=33`）。17 在暗部
         * 台阶可见、65 生成耗时约翻 8 倍（33³→65³ 是 7.6 倍格点），33 是折中。
         */
        const val DEFAULT_LUT_SIZE = 33

        /** 默认关闭：LUT 是审美取向，不该在用户没要求时改他的画面。 */
        const val DEFAULT_ENABLED = false

        /** 缓存文件名前缀，清理时按它扫。 */
        const val FILE_PREFIX = "lut_"

        /** 设置页可选的尺寸档，每档注明代价（3 档足够，不暴露 1..256 的自由值）。 */
        val SIZE_OPTIONS: List<LutSizeOption> = listOf(
            LutSizeOption(17, "17 档 · 最快", "生成约 5ms，暗部可能见台阶"),
            LutSizeOption(33, "33 档 · 推荐", "生成约 20ms，行业惯例值"),
            LutSizeOption(65, "65 档 · 最平滑", "生成约 150ms，老机型可能掉帧")
        )

        /** 只保留当前有效的那张，删掉同前缀的其它旧文件。 */
        fun staleFileNames(existing: Collection<String>, keep: String?): List<String> =
            existing.filter { name -> name.startsWith(FILE_PREFIX) && name != keep }
    }
}

/**
 * 设置页里的一个尺寸档。
 *
 * @property size 传给 [LutSettings.lutSize] 的值。
 * @property title 标题文案。
 * @property description 代价说明——选大档必须让用户知道要等。
 */
data class LutSizeOption(
    val size: Int,
    val title: String,
    val description: String
)
