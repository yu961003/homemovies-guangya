package com.example.localmovielibrarz.playback.lut

/**
 * 一次「自动分析调参」的完整结果。
 *
 * @property features 从这一帧里提出来的肤色统计；`null` 表示没找到足够肤色像素。
 * @property prediction 搜出来的参数与模板；`features` 为 null 时是「无肤色 → 标准模板」的兜底。
 * @property preview 用搜出来的参数把肤色像素预演一遍后的统计；没有肤色时为 null。
 * @property message 一句话结论（给用户看）。
 */
class LutAutoAnalysis(
    val features: LutSkinFeatures?,
    val prediction: LutPrediction,
    val preview: LutSkinFeatures?,
    val message: String
) {

    /** 是否真的从画面里拿到了可用的肤色数据。 */
    val hasSkin: Boolean get() = features != null

    override fun toString(): String = "LutAutoAnalysis(mode=${prediction.mode.name}, $message)"
}

/**
 * 「自动分析调参」：截一帧 → 提肤色 → 选模板 → 搜 7 个参数。
 *
 * 对应参考实现的 `analyze_and_apply()`（mpv_realtime_lut.py:1741-1830）。参考实现
 * 那条链路在桌面端是「IPC 截图 → cv2 读盘 → extract_skin（OpenCV 阈值 + MediaPipe
 * 人脸）→ predict_params → 下发 LUT → 同步滑杆」；本工程把它拆成三段，各段都能
 * 单测：
 *
 * | 参考实现 | 本工程 | 依赖 |
 * |---|---|---|
 * | `capture_mpv_frame_via_ipc` + `cv2.imread` | 调用方负责（mpv 截图 + [LutSkinAnalyzer.analyze]） | Android Bitmap |
 * | `extract_skin` | [LutSkinAnalyzer] | 纯 Kotlin |
 * | `predict_params` | [LutParamPredictor] | 纯 Kotlin |
 *
 * 本类只做「串起来」和「出结论文案」，不碰 Android API，因此可以脱离设备在
 * JVM 上用 `work/toolchain/krun.sh` 回归（见 `work/autolutspec/`）。
 *
 * ## 与参考实现的取舍
 *
 * 参考实现在 `analyze_and_apply` 里把 `used_deviated` / `deviated_ratio` /
 * `deviated_direction` 三个键写死成「不使用偏离帧」，说明多帧偏离检测在它的
 * 单次分析路径里本来就不生效。这里保持同样的单帧语义，不引入多帧采样——
 * 那样会让「按一下按钮」变成一个需要等待若干秒的采集过程。
 */
object LutAutoAnalyzer {

    /**
     * 分析一帧画面。
     *
     * @param frame 行优先的 ARGB 像素，长度必须是 width*height。
     * @param mode 强制指定模板；null 时按 [LutParamPredictor.selectMode] 自动选。
     * @param trace 非 null 时收集搜参过程的决策日志。
     */
    fun analyze(
        frame: IntArray,
        width: Int,
        height: Int,
        mode: LutAnalysisMode? = null,
        trace: MutableList<String>? = null
    ): LutAutoAnalysis {
        val features = LutSkinAnalyzer.analyze(frame, width, height)
        return analyzeFeatures(features, mode, trace)
    }

    /**
     * 分析已经提取好的肤色统计（把截帧与统计拆开，便于用构造数据测试）。
     */
    fun analyzeFeatures(
        features: LutSkinFeatures?,
        mode: LutAnalysisMode? = null,
        trace: MutableList<String>? = null
    ): LutAutoAnalysis {
        val prediction = LutParamPredictor.predict(features, mode, trace)
        val preview = previewOf(features, prediction.params)
        val message = buildMessage(features, prediction, preview)
        return LutAutoAnalysis(features, prediction, preview, message)
    }

    /**
     * 用搜出来的参数把肤色像素预演一遍，得到「预期生效后的统计」。
     *
     * 参考实现同样打印这一对指标（`[原片指标]` / `[调色预期]`），值不值得看取决于
     * 用户：它是这组参数在原片肤色上会得到什么，而不是最终画面的样子。
     */
    fun previewOf(features: LutSkinFeatures?, params: LutParams): LutSkinFeatures? {
        val skin = features?.skinRgb?.takeIf { it.isNotEmpty() } ?: return null
        val transformed = LutParamPredictor.simulateOnSkin(skin, params)
        return LutSkinStats.sample(transformed)
    }

    /**
     * 结论文案。
     *
     * 分成三类：没肤色（要告诉用户「换个有人的画面」）、有肤色（报模板与关键指标）、
     * 有肤色但搜出来的参数几乎不动（提示「这帧已经够好」）。
     */
    private fun buildMessage(
        features: LutSkinFeatures?,
        prediction: LutPrediction,
        preview: LutSkinFeatures?
    ): String {
        if (features == null) {
            return "这一帧没检测到足够肤色像素，换个能看清人脸的画面再试"
        }
        val modeLabel = prediction.mode.label
        val head = "已按「${modeLabel}」模板搜参"
        val p = prediction.params
        val changed = !p.isNeutral()
        val body = buildString {
            append(head)
            append("（R/B=").append(fmt(features.skinRb))
            append("，亮度=").append(fmt(features.skinLum))
            if (preview != null) {
                append(" → 预期 R/B=").append(fmt(preview.skinRb))
                append("，亮度=").append(fmt(preview.skinLum))
            }
            append("）")
            if (!changed) append("；这帧已经很接近目标区间，参数基本不用动")
        }
        return body
    }

    private fun fmt(v: Double): String = String.format(java.util.Locale.US, "%.3f", v)
}