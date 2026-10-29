package com.example.localmovielibrarz.playback.lut

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 自动分析选中的模板。
 *
 * 参考实现用 `use_extreme` / `use_high_match` 两个布尔量表达同一件事
 * （mpv_realtime_lut.py:612-632），三个分支只可能命中一个。这里收成枚举，
 * 让「选哪个模板」成为一个可见、可测试、可展示给用户的结果。
 */
enum class LutAnalysisMode {
    Standard,
    HighMatch,
    ExtremeDark;

    val label: String
        get() = when (this) {
            Standard -> "标准"
            HighMatch -> "高匹配"
            ExtremeDark -> "极暗"
        }
}

/**
 * 自动分析的结果。
 *
 * @property params 搜出来的 7 个参数，可直接写进 [LutSettings]。
 * @property mode 命中的模板。
 * @property note 一句话解释（给用户看），例如「高匹配 · 肤色 R/B=1.13，色温几乎不动」。
 * @property trace 逐步决策日志；只在需要排查时收集，正常路径为空。
 */
class LutPrediction(
    val params: LutParams,
    val mode: LutAnalysisMode,
    val note: String,
    val trace: List<String> = emptyList()
) {
    override fun toString(): String = "LutPrediction(${mode.name}, $note, $params)"
}

/**
 * 肤色统计 → 7 个调色参数。
 *
 * 逐项移植自参考实现 `predict_params`（mpv_realtime_lut.py:593-1446）。这是整个
 * 自动分析的核心：**给定一帧画面的肤色统计，搜出一组让肤色落进目标区间的参数**。
 *
 * ## 为什么是「搜索」而不是「公式」
 *
 * 调色算子是逐像素非线性的（高光衰减、阴影衰减都带 if），从目标统计量反解参数的
 * 闭式解不存在。参考实现的做法是：把 7 个参数看成 7 个整数维度，**按顺序逐个搜**
 * （色温 → 亮度 → 色调 → 饱和度 → 对比度 → 高光 → 阴影），每一步在当前最优解上
 * 只搜自己这一维，用「目标区间误差² + 轻微偏离惩罚」打分。这个顺序本身是设计：
 * 先把色温定死（色温会改变亮度），再调亮度，最后才动高光/阴影这些精修项。
 * 每一步的搜索区间都很窄（±3..±20），所以总评估次数在 150 次量级。
 *
 * ## 三个模板的取舍
 *
 * 标准模板大幅偏冷（temp -20），因为它假设片源偏黄；高匹配模板几乎不动色温（-3），
 * 用于本来就接近目标的片源；极暗模板提亮提阴影（bright +25 / shadow +20）。
 * 选错模板比参数不准更糟——所以 [selectMode] 的判据（[LutSkinStats.isExtremeDark] /
 * [LutSkinStats.isHighMatch]）是这道流程里最保守的代码。
 *
 * ## 与参考实现的数值差异
 *
 * 参考实现在 numpy 的 float32 数组上算子，标量系数是 Python float（float64）。
 * 这里全程 Float（与 [LutColorMath] 一致，保证「搜参用的算子」与「最终渲染用的算子」
 * 完全相同）。两者差异在 1e-7 量级；实测把像素抖动 1e-6 后 40 组随机样本的参数
 * 全部不变（`work/autolutspec/sens.py`），因此搜索不会因为这点误差跳到相邻档位。
 */
object LutParamPredictor {

    /** 参考实现 `NO_HL_EXEMPT` 的阈值（mpv_realtime_lut.py:637-640）。 */
    private const val NO_HL_EXEMPT_HL = 0.01
    private const val NO_HL_EXEMPT_LUM = 0.35

    /** 原片已经够亮时，亮度搜索的起点（mpv_realtime_lut.py:659）。 */
    private const val BRIGHT_BRIGHT_SEARCH_LO = 3
    private const val BRIGHT_BRIGHT_LUM = 0.60

    /** 保守场景判据（mpv_realtime_lut.py:667-672）。 */
    private const val CONS_RB = 2.0
    private const val CONS_SAT = 0.5
    private const val CONS_LUM = 0.35

    /** 标准模板的最后一道亮度下限（mpv_realtime_lut.py:1148-1165 / 573-585）。 */
    private const val TEMP_FLOOR_THRESHOLD = -18
    private const val BRIGHT_FLOOR_STD = 7

    /** 阴影搜索的暗部目标（mpv_realtime_lut.py:983-984）。 */
    private const val DARK_LUM_THRESHOLD = 0.20
    private const val DARK_LUM_TARGET = 0.30

    /**
     * 选模板。顺序不能换：极暗优先于高匹配。
     *
     * 参考实现 `is_high_match` 自己也不会把极暗片源判成高匹配（内部先判 extreme），
     * 这里只是把优先级写明显。
     */
    fun selectMode(features: LutSkinFeatures?): LutAnalysisMode = when {
        LutSkinStats.isExtremeDark(features) -> LutAnalysisMode.ExtremeDark
        LutSkinStats.isHighMatch(features) -> LutAnalysisMode.HighMatch
        else -> LutAnalysisMode.Standard
    }

    /**
     * 搜参数。
     *
     * @param features 肤色统计；为 null 或没有肤色像素时直接返回标准模板（参考实现
     *   的兜底：无肤色数据就用 `JY_TEMPLATE`，不猜）。
     * @param mode 强制指定模板；null 时按 [selectMode] 自动选。
     * @param trace 非 null 时把决策过程写进去，便于排查「为什么这个参数是这样」。
     */
    fun predict(
        features: LutSkinFeatures?,
        mode: LutAnalysisMode? = null,
        trace: MutableList<String>? = null
    ): LutPrediction {
        if (features == null || !features.hasSkin) {
            trace?.add("[模板] 无肤色数据，直接使用标准模板")
            return LutPrediction(
                params = LutPreset.Standard.params,
                mode = LutAnalysisMode.Standard,
                note = "无肤色数据，使用标准模板",
                trace = trace?.toList() ?: emptyList()
            )
        }

        val selected = mode ?: selectMode(features)
        val useExtreme = selected == LutAnalysisMode.ExtremeDark
        val useHighMatch = selected == LutAnalysisMode.HighMatch
        val template = when (selected) {
            LutAnalysisMode.Standard -> LutTemplate.STANDARD
            LutAnalysisMode.HighMatch -> LutTemplate.HIGH_MATCH
            LutAnalysisMode.ExtremeDark -> LutTemplate.EXTREME_DARK
        }
        val targets = when (selected) {
            LutAnalysisMode.Standard -> LutTargets.STANDARD
            LutAnalysisMode.HighMatch -> LutTargets.HIGH_MATCH
            LutAnalysisMode.ExtremeDark -> LutTargets.EXTREME_DARK
        }
        trace?.add("[模板] ${selected.label}（temp=${template.temp}, bright=${template.bright}, shadow=${template.shadow}）")

        val skin = features.skinRgb
        val n = skin.size / 3
        val out = FloatArray(skin.size)

        val origHl = features.fullHlRatio
        val origLum = features.skinLum
        val origRb = features.skinRb
        val origSat = features.skinSat

        // 「无高光豁免」：画面本来就没有高光、肤色也不暗，说明不需要压高光/压亮度。
        // 此时高光搜索结果不再是「落进区间」而是「别动」（参考实现 637-650 / 969-972）。
        val noHlExempt = origHl < NO_HL_EXEMPT_HL && origLum >= NO_HL_EXEMPT_LUM
        trace?.add(if (noHlExempt) "[无高光豁免] 生效：高光=${fmt4(origHl)}、亮度=${fmt3(origLum)}" else "[无高光豁免] 未生效")

        // 原片本来就亮：亮度搜索不许从 0 起（会把已经够亮的画面再推高）。
        val origLumIsBright = origLum > BRIGHT_BRIGHT_LUM

        // 保守场景：肤色极红 + 极高饱和 + 很暗，多半是暖光/烛光，任何「压暗补偿」
        // 都会让画面更脏，所以后面多处跳过 bright 补偿。
        val conservativeScene = origRb > CONS_RB && origSat > CONS_SAT &&
            origLum < CONS_LUM && !useExtreme
        if (conservativeScene) {
            trace?.add("[保守场景] R/B=${fmt3(origRb)} > 2.0、饱和度=${fmt3(origSat)} > 0.5、亮度=${fmt3(origLum)} < 0.35，跳过 bright 补偿")
        }

        var p = P.of(template)

        // ── ① 色温 ─────────────────────────────────────────────────────────
        val lumLo = targets.skinLumRange.start
        val lumHi = targets.skinLumRange.endInclusive

        if (useHighMatch) {
            // 高匹配模板的色温由 R/B 直接反推一个基准（rb 越接近上限越冷），
            // 再在基准 ±2 内微调。参考实现 677-701。
            val rbLo = targets.skinRbRange.start
            val rbHi = targets.skinRbRange.endInclusive
            val tempBase = when {
                origRb <= rbLo -> -3
                origRb >= rbHi -> -8
                else -> {
                    val ratio = (origRb - rbLo) / max(rbHi - rbLo, 1e-6)
                    LutSkinStats.pyRound(-3 + ratio * (-8 - (-3)))
                }
            }
            var bestTemp = tempBase
            var bestErr = Double.MAX_VALUE
            for (delta in -2..2) {
                val temp = tempBase + delta
                p.temp = temp
                applyToSkin(skin, p, out)
                val d = LutSkinStats.sample(out)
                var err = sq(LutSkinStats.rangeError(d.skinRb, rbLo, rbHi))
                err += 0.001 * (delta * delta)
                if (err < bestErr) {
                    bestErr = err
                    bestTemp = temp
                }
            }
            p.temp = bestTemp
            trace?.add("[色温-高匹配] R/B=${fmt3(origRb)} → 基准 $tempBase，搜得 $bestTemp")
        } else {
            // 标准/极暗模板：先在模板色温上算一次基准 R/B，用「实测 R/B - 基准 R/B」
            // 修正目标。修正项夹在 ±0.08 —— 这是「肤色提取可能偏了」的容差，
            // 不是让搜索去追一个不可达的目标（参考实现 703-740）。
            applyToSkin(skin, p, out)
            val baseRb = LutSkinStats.sample(out).skinRb
            var rbOffset = origRb - baseRb
            rbOffset = max(-0.08, min(0.08, rbOffset))
            trace?.add("[色温] 基准修正 info_rb=${fmt3(origRb)}, base_rb=${fmt3(baseRb)}, offset=${fmt3(rbOffset)}")

            val rbLo = targets.skinRbRange.start
            val rbHi = targets.skinRbRange.endInclusive
            // 极红的片源（R/B > 2）实际上不可达目标区间，硬追会搜出一个极端色温，
            // 所以目标放宽到区间上限；其余情况取区间 60% 位置。
            val rbTarget = if (origRb > CONS_RB) rbHi else rbLo + (rbHi - rbLo) * 0.6
            trace?.add("[色温] R/B 目标 = ${fmt3(rbTarget)}")

            val range = template.rangeOf(LutParamKey.Temperature)
            var bestTemp = p.temp
            var bestErr = Double.MAX_VALUE
            for (delta in range.start..range.endInclusive) {
                val temp = template.temp + delta
                p.temp = temp
                applyToSkin(skin, p, out)
                val d = LutSkinStats.sample(out)
                val correctedRb = d.skinRb + rbOffset
                // 目标带 ±0.03 容差：R/B 是像素比例，1% 的提取噪声就值 0.015，
                // 不带容差会让搜索在两个相邻档位之间反复跳。
                val rbErr = max(0.0, abs(correctedRb - rbTarget) - 0.03)
                var err = 15 * sq(rbErr)
                err += 5 * sq(LutSkinStats.rangeError(d.skinRg, targets.skinRgRange.start, targets.skinRgRange.endInclusive))
                err += 10 * sq(LutSkinStats.rangeError(d.skinLum, lumLo, lumHi))
                err += 0.0005 * (delta * delta)
                if (err < bestErr) {
                    bestErr = err
                    bestTemp = temp
                }
            }
            p.temp = bestTemp
            trace?.add("[色温] 搜得 temp=$bestTemp")
        }

        // ── ② 亮度目标 ──────────────────────────────────────────────────────
        val rgTarget = (targets.skinRgRange.start + targets.skinRgRange.endInclusive) / 2
        val satTarget = (targets.skinSatRange.start + targets.skinSatRange.endInclusive) / 2
        // 默认目标取区间 80% 位置（偏亮一侧：肤色偏亮比偏暗好看）。
        var lumTarget = lumLo + (lumHi - lumLo) * 0.8
        // 注意：亮度目标这一支的判据是 `R/B >= 2.0`，比上面的「保守场景」(`> 2.0`)
        // 宽一个点。参考实现两处就是不一致的（766 行 vs 668 行），照抄。
        val lumConservative = origRb >= CONS_RB && origSat > CONS_SAT && origLum < CONS_LUM && !useExtreme

        when {
            lumConservative -> {
                lumTarget = max(origLum + 0.04, 0.39)
                trace?.add("[亮度目标-保守场景] = ${fmt3(lumTarget)}")
            }
            origLum > 0.54 && origLum <= 0.60 -> {
                lumTarget = min(origLum + 0.02, 0.58)
                trace?.add("[亮度目标-超亮动态] = ${fmt3(lumTarget)}")
            }
            origLum > 0.60 -> {
                lumTarget = 0.58
                trace?.add("[亮度目标-极亮压回] = 0.580")
            }
            origRb < CONS_RB && origLum >= 0.50 && origLum <= 0.54 -> {
                lumTarget = origLum + 0.05
                trace?.add("[亮度目标-高亮动态] = ${fmt3(lumTarget)}")
            }
            origRb < CONS_RB && origLum > 0.35 && origLum < 0.50 && origHl > 0.005 -> {
                lumTarget = min(origLum + 0.09, 0.54)
                trace?.add("[亮度目标-动态] = ${fmt3(lumTarget)}")
            }
            else -> trace?.add("[亮度目标] 默认 80% 位置 = ${fmt3(lumTarget)}")
        }
        val targetLum = lumTarget

        // ── ③ 亮度搜索 ──────────────────────────────────────────────────────
        p = searchBright(skin, out, p, template, targets, targetLum, lumHi, origLumIsBright, trace)

        // 复检：在 ±8 档内再搜一次（色温定下来后实际亮度可能已经偏了 10%）。
        var postLum = evaluateLum(skin, out, p)
        trace?.add("[亮度复检] 当前亮度=${fmt3(postLum)}，目标=${fmt3(targetLum)}")
        if (postLum < targetLum * 0.9 || postLum > targetLum * 1.1) {
            // 起搜点必须单独存：循环里 p.bright 会被逐档改写，拿它做「有没有变化」
            // 的判断会永远相等。
            val baseBright = p.bright
            var bestBright2 = baseBright
            var bestErr2 = Double.MAX_VALUE
            var lo2 = max(template.rangeOf(LutParamKey.Brightness).start, baseBright - 8 - template.bright)
            val hi2 = min(template.rangeOf(LutParamKey.Brightness).endInclusive, baseBright + 8 - template.bright)
            if (origLumIsBright) {
                lo2 = max(lo2, BRIGHT_BRIGHT_SEARCH_LO)
                p.bright = lo2
                trace?.add("[亮度复检-极亮锁定] → $lo2")
            } else {
                for (delta in lo2..hi2) {
                    val bright = template.bright + delta
                    p.bright = bright
                    val d = sampleAfter(skin, p, out)
                    val curLum = d.skinLum
                    var err = when {
                        curLum < targetLum -> sq(targetLum - curLum)
                        curLum > lumHi -> sq(curLum - targetLum)
                        else -> 0.0
                    }
                    if (p.temp > -40) {
                        err += 2 * sq(LutSkinStats.rangeError(d.skinRb, targets.skinRbRange.start, targets.skinRbRange.endInclusive))
                    }
                    if (err < bestErr2) {
                        bestErr2 = err
                        bestBright2 = bright
                    }
                }
                if (bestBright2 != baseBright) trace?.add("[亮度复检] $baseBright → $bestBright2")
                p.bright = bestBright2
            }
            postLum = evaluateLum(skin, out, p)
        }

        // ── ④ 色调（R/G 对准区间中心 ±5%） ─────────────────────────────────
        run {
            val range = template.rangeOf(LutParamKey.Tint)
            var bestTint = p.tint
            var bestErr = Double.MAX_VALUE
            val lo = rgTarget * 0.95
            val hi = rgTarget * 1.05
            for (delta in range.start..range.endInclusive) {
                val tint = template.tint + delta
                p.tint = tint
                val d = sampleAfter(skin, p, out)
                var err = sq(LutSkinStats.rangeError(d.skinRg, lo, hi))
                err += 0.001 * (delta * delta)
                if (err < bestErr) {
                    bestErr = err
                    bestTint = tint
                }
            }
            p.tint = bestTint
            trace?.add("[色调] R/G 目标=${fmt3(rgTarget)} → tint=$bestTint")
        }

        // ── ⑤ 饱和度（对准区间中心 ±10%） ──────────────────────────────────
        run {
            val range = template.rangeOf(LutParamKey.Saturation)
            var bestSat = p.sat
            var bestErr = Double.MAX_VALUE
            val lo = satTarget * 0.9
            val hi = satTarget * 1.1
            for (delta in range.start..range.endInclusive) {
                val sat = template.sat + delta
                p.sat = sat
                val d = sampleAfter(skin, p, out)
                var err = sq(LutSkinStats.rangeError(d.skinSat, lo, hi))
                err += 0.001 * (delta * delta)
                if (err < bestErr) {
                    bestErr = err
                    bestSat = sat
                }
            }
            p.sat = bestSat
            trace?.add("[饱和度] 目标=${fmt3(satTarget)} → sat=$bestSat")
        }

        // ── ⑥ 对比度（同时兼顾对比度区间与 R/B） ────────────────────────────
        run {
            val range = template.rangeOf(LutParamKey.Contrast)
            var bestContrast = p.contrast
            var bestErr = Double.MAX_VALUE
            for (delta in range.start..range.endInclusive) {
                val contrast = template.contrast + delta
                p.contrast = contrast
                val d = sampleAfter(skin, p, out)
                var err = sq(LutSkinStats.rangeError(d.fullContrast, targets.fullContrastRange.start, targets.fullContrastRange.endInclusive))
                err += 100 * sq(LutSkinStats.rangeError(d.skinRb, targets.skinRbRange.start, targets.skinRbRange.endInclusive))
                err += 0.001 * (delta * delta)
                if (err < bestErr) {
                    bestErr = err
                    bestContrast = contrast
                }
            }
            p.contrast = bestContrast
            trace?.add("[对比度] → contrast=$bestContrast")
        }

        // ── ⑦ 高光补偿基数 ─────────────────────────────────────────────────
        // 前面把亮度/阴影都提了，高光区会被一起抬高；按提升量抵扣高光参数，
        // 免得「提亮」把天花板的细节吃掉。参考实现 931-948。
        val hlCompensation: Int
        if (useExtreme) {
            hlCompensation = 0
        } else {
            val brightBoost = max(0, p.bright - 5)
            val shadowBoost = max(0, p.shadow - 2)
            val raw = LutSkinStats.pyRound(-(brightBoost * 1.0 + shadowBoost * 0.5))
            hlCompensation = max(-5, min(0, raw))
            if (hlCompensation != 0) {
                trace?.add("[高光补偿] bright=${p.bright}, shadow=${p.shadow} → $hlCompensation")
            }
        }

        // ── ⑧ 高光（目标区间由当前肤色亮度动态决定） ────────────────────────
        run {
            val range = template.rangeOf(LutParamKey.Highlight)
            val hlBase = template.highlight + hlCompensation
            val skinLumNow = evaluateLum(skin, out, p)
            val (hlLo, hlHi) = LutSkinStats.dynamicHlTarget(skinLumNow)
            trace?.add("[高光] 当前亮度=${fmt3(skinLumNow)}，动态目标=[${fmt3(hlLo)}, ${fmt3(hlHi)}]")

            var bestHl = p.highlight
            var bestErr = Double.MAX_VALUE
            val baseHl = p.highlight
            for (delta in range.start..range.endInclusive) {
                val hl = hlBase + delta
                p.highlight = hl
                val d = sampleAfter(skin, p, out)
                // 豁免情形下不追求「高光占比落进区间」，只保 R/B 不跑偏。
                var err = if (noHlExempt) 0.0 else sq(LutSkinStats.rangeError(d.fullHlRatio, hlLo, hlHi))
                err += 10 * sq(LutSkinStats.rangeError(d.skinRb, targets.skinRbRange.start, targets.skinRbRange.endInclusive))
                if (err < bestErr) {
                    bestErr = err
                    bestHl = hl
                }
            }
            p.highlight = bestHl
            if (bestHl != baseHl) trace?.add("[高光] $baseHl → $bestHl") else trace?.add("[高光] 保持 $bestHl")
        }

        // ── ⑨ 阴影（把暗部中位亮度推到 0.30） ───────────────────────────────
        run {
            val range = template.rangeOf(LutParamKey.Shadow)
            var bestSh = p.shadow
            var bestErr = Double.MAX_VALUE

            // 暗部掩码在「阴影=0」时算一次就固定：判谁算暗部，不该跟着阴影参数变，
            // 否则每换一档阴影都换一批像素，中位数失去可比性（参考实现 986-1002）。
            val pBase = p.copy()
            pBase.shadow = 0
            applyToSkin(skin, pBase, out)
            var darkCount = 0
            val darkIdx = IntArray(n)
            for (i in 0 until n) {
                val b0 = i * 3
                if (LutColorMath.luma(out[b0], out[b0 + 1], out[b0 + 2]) < DARK_LUM_THRESHOLD) {
                    darkIdx[darkCount++] = i
                }
            }

            if (darkCount > 0) {
                val darkVals = DoubleArray(darkCount)
                for (delta in range.start..range.endInclusive) {
                    val sh = template.shadow + delta
                    p.shadow = sh
                    applyToSkin(skin, p, out)
                    for (k in 0 until darkCount) {
                        val b0 = darkIdx[k] * 3
                        darkVals[k] = LutColorMath.luma(out[b0], out[b0 + 1], out[b0 + 2]).toDouble()
                    }
                    darkVals.sort()
                    val median = medianOfSorted(darkVals)
                    var err = sq(median - DARK_LUM_TARGET)
                    val d = LutSkinStats.sample(out)
                    err += sq(LutSkinStats.rangeError(d.skinRb, targets.skinRbRange.start, targets.skinRbRange.endInclusive))
                    if (err < bestErr) {
                        bestErr = err
                        bestSh = sh
                    }
                }
            } else {
                trace?.add("[阴影] 无暗部像素（亮度 < 0.20），保持模板值")
            }
            p.shadow = bestSh
            trace?.add("[阴影] 暗部像素=$darkCount → shadow=$bestSh")
        }

        // ── ⑩ 最终亮度复检 ──────────────────────────────────────────────────
        // 前面几步（尤其阴影）会再次改变亮度。这里把「调整后不该比原片更暗」
        // 这条硬规则兜一次：区间内的原片被压暗、低于硬下限、超出上限，都重搜亮度。
        val finalLum = evaluateLum(skin, out, p)
        val hardFloor = if (useExtreme) 0.0 else lumLo
        trace?.add("[最终复检] 原片=${fmt3(origLum)}, 调整后=${fmt3(finalLum)}, 区间=[${fmt3(lumLo)}, ${fmt3(lumHi)}], 硬下限=${fmt3(hardFloor)}")

        val origInRange = origLum >= lumLo && origLum <= lumHi
        val origBelowFloor = origLum < hardFloor

        var needRecheck = false
        var recheckTarget = finalLum
        when {
            origLum > lumHi && origLum <= 0.60 && finalLum < origLum + 0.02 - 0.005 -> {
                needRecheck = true
                recheckTarget = origLum + 0.02
            }
            origLum > 0.60 && finalLum < 0.60 - 0.005 -> {
                needRecheck = true
                recheckTarget = 0.60
            }
            origInRange && finalLum < origLum - 0.005 -> {
                needRecheck = true
                recheckTarget = if (origBelowFloor) hardFloor else origLum
            }
            origBelowFloor && finalLum < hardFloor -> {
                needRecheck = true
                recheckTarget = hardFloor
            }
            finalLum > lumHi * 1.05 && !(origRb < CONS_RB && origLum >= 0.50 && origLum <= 0.60) -> {
                needRecheck = true
                recheckTarget = finalLum
            }
        }

        if (needRecheck) {
            trace?.add("[最终复检] 触发，目标=${fmt3(recheckTarget)}")
            // 只有「原片本来就在区间下方」时才允许把目标抬到当前亮度；
            // 原片超上限时要把它压回来，不能顺手抬高。
            if (!(origLum > lumHi) && recheckTarget < finalLum) {
                recheckTarget = finalLum
            }

            var bestBright3 = p.bright
            var bestErr3 = Double.MAX_VALUE
            val baseBright3 = p.bright
            var lo3 = max(template.rangeOf(LutParamKey.Brightness).start, baseBright3 - 6 - template.bright)
            val hi3 = min(template.rangeOf(LutParamKey.Brightness).endInclusive, baseBright3 + 15 - template.bright)
            if (origLumIsBright) {
                lo3 = max(lo3, BRIGHT_BRIGHT_SEARCH_LO)
                p.bright = lo3
            } else {
                for (delta in lo3..hi3) {
                    val bright = template.bright + delta
                    p.bright = bright
                    val d = sampleAfter(skin, p, out)
                    val curLum = d.skinLum
                    var err = when {
                        curLum < recheckTarget -> sq(recheckTarget - curLum)
                        recheckTarget <= lumHi && curLum > lumHi -> sq(curLum - lumHi)
                        else -> 0.0
                    }
                    if (p.temp > -40) {
                        err += 2 * sq(LutSkinStats.rangeError(d.skinRb, targets.skinRbRange.start, targets.skinRbRange.endInclusive))
                    }
                    err += 0.0001 * abs(curLum - recheckTarget)
                    if (err < bestErr3) {
                        bestErr3 = err
                        bestBright3 = bright
                    }
                }
                if (bestBright3 != baseBright3) trace?.add("[最终复检] bright $baseBright3 → $bestBright3")
                p.bright = bestBright3
            }
        }

        // ── ⑪ 标准模板的亮度下限 ────────────────────────────────────────────
        // 标准模板的色温是 -20，本身就会压暗；如果原片亮度刚好在区间内、高光也少，
        // 再压下去就发灰，所以给 bright 一个 +7 的地板（参考实现 1148-1165）。
        if (!useExtreme && !useHighMatch) {
            val tempOk = p.temp > TEMP_FLOOR_THRESHOLD
            val lumOk = origLum >= lumLo && origLum <= lumHi
            val hlOk = origHl < 0.10
            if (tempOk && lumOk && hlOk && p.bright < BRIGHT_FLOOR_STD) {
                trace?.add("[标准-亮度下限] bright ${p.bright} → $BRIGHT_FLOOR_STD")
                p.bright = BRIGHT_FLOOR_STD
            }
        }

        // ── ⑫ 力度协调 ──────────────────────────────────────────────────────
        p = balanceStrength(p, template, useExtreme, useHighMatch, conservativeScene, trace)

        // ── ⑬ 参数空间收尾 ──────────────────────────────────────────────────
        // 这些都来自参考实现 1178-1360，属于「参数之间会互相抵消」的经验法则。
        if (p.bright > 35 && p.tint > 0) {
            trace?.add("[收尾] bright=${p.bright} > 35，撤销补血色 tint=${p.tint} → 0")
            p.tint = 0
        }
        if (p.bright >= 20) {
            if (p.contrast < 0) p.contrast = 0
            if (p.sat < 0) p.sat = 0
        }
        if (origHl > 0.18 && origRb > 1.8) {
            val raise = p.bright - template.bright
            if (raise > 0) {
                p.bright = template.bright + LutSkinStats.pyRound(raise * 0.7)
            }
            if (p.highlight > 0) p.highlight = -1
        }

        // 「联动」：bright 抬得太狠时必须补一点冷色与负对比度，否则画面会发白发雾。
        var contrastBoosted = false
        if (!useExtreme) {
            val origInRangeLink = origLum >= lumLo && origLum <= lumHi
            var brightTrigger = false
            var hlTrigger = false
            if (!origInRangeLink) {
                brightTrigger = p.bright >= 15
                hlTrigger = p.bright < 15 && p.highlight < -10
            }
            if (brightTrigger || hlTrigger) {
                if (brightTrigger) {
                    if (!contrastBoosted && p.temp > -15) p.temp = -8
                } else {
                    if (!conservativeScene && !contrastBoosted && p.temp < -20) p.temp = -20
                    if (!conservativeScene && p.highlight < -5) p.highlight = -5
                }
                if (p.bright < 20) {
                    if (p.contrast > -1) p.contrast = -1
                    if (brightTrigger) {
                        if (p.sat > -1) p.sat = -1
                    } else if (p.sat < 1) {
                        p.sat = 1
                    }
                }
            }
        }

        // 白雾补偿：bright 每 +2 就补 1 档对比度，同时给饱和度一个负地板。
        if (!useExtreme && p.bright > 0) {
            contrastBoosted = false
            val neededContrast = min(LutSkinStats.pyRound(p.bright * 0.5), 6)
            if (p.contrast < neededContrast) {
                p.contrast = neededContrast
                contrastBoosted = true
            }
            var satFloor = -LutSkinStats.pyRound(p.contrast / 5.0)
            satFloor = max(satFloor, -2)
            if (satFloor < 0 && p.sat > satFloor) p.sat = satFloor
        } else {
            contrastBoosted = false
        }

        // 对比度拉高会放大色偏，把色温往回收一点（每 3 档对比度 ≈ 3 档色温）。
        if (!useExtreme && !useHighMatch && p.contrast > 0) {
            val c = p.contrast
            val tempDrop = when {
                c in 1..3 -> 3
                c in 4..6 -> 6
                else -> 0
            }
            if (tempDrop > 0) {
                val floor = template.temp + template.rangeOf(LutParamKey.Temperature).start
                p.temp = max(p.temp - tempDrop, floor)
            }
            // 同理，对比度也会放大「补血色」的色调，tint 跟着回压一半。
            if (p.tint > 0) {
                var tintDrop = LutSkinStats.pyRound(c / 2.0)
                if (tintDrop > p.tint) tintDrop = p.tint
                if (tintDrop > 0) p.tint -= tintDrop
            }
        }

        // 缺亮补亮：高光占比极低的暗肤画面，肤色亮度可能停在 0.3 出头，
        // 此时强行把 bright 推到 20（参考实现 1323-1354）。
        run {
            val lowHlRatio = origHl < 0.02
            val darkSkin = origLum < 0.52
            val tempAtFloor = p.temp <= -40
            val tempInRange = p.temp > -40 && p.temp <= -30
            val origLumBright = origLum > 0.35
            val weakenBoost = tempInRange && origLumBright
            if (lowHlRatio && darkSkin && !useExtreme && !tempAtFloor && !noHlExempt && !conservativeScene) {
                val bMax = template.bright + template.rangeOf(LutParamKey.Brightness).endInclusive
                val ratio = max(0.0, min(1.0, origHl / 0.02))
                var dynamicTarget = LutSkinStats.pyRound(20 * (1 - ratio) + p.bright * ratio)
                if (weakenBoost) {
                    val originalGain = dynamicTarget - p.bright
                    val weakenedGain = LutSkinStats.pyRound(originalGain * 0.4)
                    dynamicTarget = p.bright + weakenedGain
                }
                val targetBright = min(dynamicTarget, bMax)
                if (p.bright < targetBright) {
                    trace?.add("[缺亮补亮] bright ${p.bright} → $targetBright")
                    p.bright = targetBright
                }
            }
        }

        // 缺亮补亮之后再看一眼高光：亮度被推高后高光占比会上升，需要重搜高光。
        if (!useExtreme && p.bright > template.bright && !noHlExempt) {
            val postLum2 = evaluateLum(skin, out, p)
            val (hlLo, hlHi) = LutSkinStats.dynamicHlTarget(postLum2)
            val range = template.rangeOf(LutParamKey.Highlight)
            val hlBase2 = template.highlight + hlCompensation
            var bestHl2 = p.highlight
            var bestErr2 = Double.MAX_VALUE
            for (delta in range.start..range.endInclusive) {
                val hl = hlBase2 + delta
                p.highlight = hl
                val d = sampleAfter(skin, p, out)
                val err = 100 * sq(LutSkinStats.rangeError(d.fullHlRatio, hlLo, hlHi))
                if (err < bestErr2) {
                    bestErr2 = err
                    bestHl2 = hl
                }
            }
            if (bestHl2 != p.highlight) trace?.add("[缺亮后-高光复检] ${p.highlight} → $bestHl2")
            p.highlight = bestHl2
        }

        // 高光下限：highlight < -7 会把高光压成灰块，得不偿失。
        if (!useExtreme && p.highlight < -7) p.highlight = -7

        // 标准模板专用：高光占比还很低时逐档加亮，直到高光刚要出现（0.22）为止。
        if (!useExtreme && !useHighMatch) {
            applyToSkin(skin, p, out)
            val currentHl = LutSkinStats.sample(out).fullHlRatio
            if (p.bright < 18 && currentHl < 0.20) {
                val brightLo = p.bright
                val brightHi = min(18, template.bright + template.rangeOf(LutParamKey.Brightness).endInclusive)
                var chosen = brightLo
                var bright = brightLo
                while (bright <= brightHi) {
                    p.bright = bright
                    val det = sampleAfter(skin, p, out)
                    if (det.fullHlRatio >= 0.22) {
                        // 越过阈值的那一档太亮，回退一格。
                        chosen = max(brightLo, bright - 1)
                        break
                    }
                    chosen = bright
                    bright++
                }
                if (chosen != brightLo) trace?.add("[标准-高光放行] bright $brightLo → $chosen")
                p.bright = chosen
            }
        }

        val finalParams = p.toLutParams()
        return LutPrediction(
            params = finalParams,
            mode = selected,
            note = buildNote(selected, features, finalParams),
            trace = trace?.toList() ?: emptyList()
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 子步骤
    // ─────────────────────────────────────────────────────────────────────────

    /** 亮度搜索。参考实现 801-832。 */
    private fun searchBright(
        skin: FloatArray,
        out: FloatArray,
        p: P,
        template: LutTemplate,
        targets: LutTargets,
        targetLum: Double,
        lumHi: Double,
        origLumIsBright: Boolean,
        trace: MutableList<String>?
    ): P {
        val range = template.rangeOf(LutParamKey.Brightness)
        if (origLumIsBright) {
            val lo = max(range.start, BRIGHT_BRIGHT_SEARCH_LO)
            p.bright = lo
            trace?.add("[亮度搜索-极亮锁定] → $lo")
            return p
        }
        val rbLo = targets.skinRbRange.start
        val rbHi = targets.skinRbRange.endInclusive
        var bestBright = p.bright
        var bestErr = Double.MAX_VALUE
        for (delta in range.start..range.endInclusive) {
            val bright = template.bright + delta
            p.bright = bright
            val d = sampleAfter(skin, p, out)
            val curLum = d.skinLum
            var err = when {
                curLum < targetLum -> sq(targetLum - curLum)
                curLum > lumHi -> sq(curLum - targetLum)
                else -> 0.0
            }
            if (p.temp > -40) {
                err += 2 * sq(LutSkinStats.rangeError(d.skinRb, rbLo, rbHi))
            }
            if (err < bestErr) {
                bestErr = err
                bestBright = bright
            }
        }
        p.bright = bestBright
        trace?.add("[亮度搜索] → bright=$bestBright")
        return p
    }

    /**
     * 力度协调：把「压暗项」的等效亮度与「bright 提升量」对账。
     *
     * 色温、高光、阴影、对比度都会不同程度压暗画面；如果压暗总量大于 bright 的
     * 提升量，净效果就是发灰。这里按经验权重（temp 0.6 / highlight 0.3 /
     * shadow 0.6 / contrast 0.4 档）折算成「等效 bright」再补足（参考实现 513-587）。
     */
    private fun balanceStrength(
        input: P,
        template: LutTemplate,
        useExtreme: Boolean,
        useHighMatch: Boolean,
        skipBrightComp: Boolean,
        trace: MutableList<String>?
    ): P {
        val out = input.copy()

        val tempDelta = out.temp - template.temp
        val tempEquiv = (if (tempDelta < 0) -tempDelta else 0) * 0.6

        val hlDelta = out.highlight - template.highlight
        val hlEquiv = if (useExtreme) 0.0 else (if (hlDelta < 0) -hlDelta else 0) * 0.3

        val shDelta = out.shadow - template.shadow
        val shEquiv = (if (shDelta < 0) -shDelta else 0) * 0.6

        val cDelta = out.contrast - template.contrast
        val cEquiv = (if (cDelta < 0) -cDelta else 0) * 0.4

        val totalDarken = tempEquiv + hlEquiv + shEquiv + cEquiv
        val brightCompensation = out.bright - template.bright
        val deficit = totalDarken - brightCompensation
        val tempAtFloor = out.temp <= -40

        if (skipBrightComp) {
            trace?.add("[力度协调] 保守场景，跳过 bright 补偿")
        } else if (deficit > 0.5 && !tempAtFloor) {
            val addBright = LutSkinStats.pyRound(deficit)
            val bMax = template.bright + template.rangeOf(LutParamKey.Brightness).endInclusive
            val newBright = min(out.bright + addBright, bMax)
            val actualAdded = newBright - out.bright
            out.bright = newBright
            if (actualAdded != 0) {
                trace?.add("[力度协调] 压暗=${fmt2(totalDarken)}档，已补=${fmt2(brightCompensation.toDouble())}，缺口=${fmt2(deficit)}，补 +$actualAdded")
            }
            // bright 到顶还有缺口，就抬高光（提亮高光不影响暗部观感）。
            val stillDeficit = totalDarken - (out.bright - template.bright)
            if (stillDeficit > 0.5) {
                val hlMax = template.highlight + template.rangeOf(LutParamKey.Highlight).endInclusive
                val hlDeficit = LutSkinStats.pyRound(stillDeficit)
                val newHl = min(out.highlight + hlDeficit, hlMax)
                if (newHl - out.highlight > 0) out.highlight = newHl
            }
        } else if (deficit > 0.5 && tempAtFloor) {
            trace?.add("[力度协调] 色温 ${out.temp} <= -40，跳过补偿（避免过曝）")
        }

        if (!useExtreme && !useHighMatch) {
            if (out.temp > TEMP_FLOOR_THRESHOLD && out.bright < BRIGHT_FLOOR_STD) {
                out.bright = BRIGHT_FLOOR_STD
            }
        }
        return out
    }

    /**
     * 拿一组参数把肤色像素预演一遍，返回调色后的像素。
     *
     * 给「自动分析调参」做效果预览用：搜完参数后拿原片肤色再跑一遍，就知道这组参数
     * 会把肤色推到哪个 R/B 和亮度。与 [LutColorMath.applyAll] 的差异见 [applyToSkin]。
     *
     * @param skinRgb 行优先的 RGB（0..1），长度必须是 3 的倍数。
     * @return 同长度的新数组，入参不被修改。
     */
    fun simulateOnSkin(skinRgb: FloatArray, params: LutParams): FloatArray {
        require(skinRgb.size % 3 == 0) { "像素数组长度必须是 3 的倍数：${skinRgb.size}" }
        val out = FloatArray(skinRgb.size)
        applyToSkin(skinRgb, P.of(params), out)
        return out
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 像素运算
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 对肤色像素应用一遍完整调色链，结果写进 [out]。
     *
     * 与 [LutColorMath.applyAll] 的算子与系数完全相同，但有两处刻意的不同：
     * 1. **过曝保护是无条件的**。`applyAll` 只压「原本没过曝、调完过曝」的格点
     *    （LUT 生成必须这样，否则会压平片源本身的高光）；而参考实现分析肤色时用的
     *    `_apply_params_to_skin`（mpv_realtime_lut.py:434-440）对所有超过 0.92 的
     *    像素都压。分析的是「放进 LUT 之后的肤色」，必须跟分析函数一致。
     * 2. **原地写缓冲**。搜索阶段要反复评估同一批像素上百次，每次分配 Triple 会让
     *    ART 的 GC 在热循环里抖动。
     */
    private fun applyToSkin(skin: FloatArray, p: P, out: FloatArray) {
        System.arraycopy(skin, 0, out, 0, skin.size)
        val n = skin.size / 3

        if (p.bright != 0) {
            for (i in 0 until n) applyBrightnessInPlace(out, i * 3, p.bright)
        }
        if (p.highlight != 0 || p.shadow != 0) {
            for (i in 0 until n) applyHighlightShadowInPlace(out, i * 3, p.highlight, p.shadow)
        }
        if (p.contrast != 0) {
            for (i in 0 until n) applyContrastInPlace(out, i * 3, p.contrast)
        }
        if (p.sat != 0) {
            for (i in 0 until n) applySaturationInPlace(out, i * 3, p.sat)
        }
        if (p.temp != 0 || p.tint != 0) {
            for (i in 0 until n) applyTempTintInPlace(out, i * 3, p.temp, p.tint)
        }

        // 无条件过曝保护 + clamp（与 _apply_params_to_skin 一致）。
        for (i in 0 until n) {
            val b0 = i * 3
            val lum = LutColorMath.luma(out[b0], out[b0 + 1], out[b0 + 2])
            if (lum > OVEREXPOSE_LIMIT) {
                val scale = OVEREXPOSE_LIMIT / max(lum, 1e-6f)
                out[b0] *= scale
                out[b0 + 1] *= scale
                out[b0 + 2] *= scale
            }
            out[b0] = out[b0].coerceIn(0f, 1f)
            out[b0 + 1] = out[b0 + 1].coerceIn(0f, 1f)
            out[b0 + 2] = out[b0 + 2].coerceIn(0f, 1f)
        }
    }

    private fun applyBrightnessInPlace(s: FloatArray, b0: Int, bright: Int) {
        val r = s[b0]
        val g = s[b0 + 1]
        val b = s[b0 + 2]
        val lum = LutColorMath.luma(r, g, b)
        val hlT = if (lum > HL_BRIGHT_DAMP_START) (lum - HL_BRIGHT_DAMP_START) / (1f - HL_BRIGHT_DAMP_START) else 0f
        val hlDamp = max(HL_BRIGHT_DAMP_MIN, 1f - hlT * HL_BRIGHT_DAMP_STRENGTH)
        val shDamp = if (lum < 0.18f) 0.35f + (1f - 0.35f) * (lum / 0.18f) else 1f
        var scale = 1f + bright / 50f * 0.67f * hlDamp * shDamp
        if (bright > 0) {
            val protectW = ((lum - 0.85f) / (1f - 0.85f)).coerceIn(0f, 1f)
            scale = scale * (1f - protectW) + protectW
        }
        s[b0] = r * scale
        s[b0 + 1] = g * scale
        s[b0 + 2] = b * scale
    }

    private fun applyHighlightShadowInPlace(s: FloatArray, b0: Int, highlight: Int, shadow: Int) {
        val r = s[b0]
        val g = s[b0 + 1]
        val b = s[b0 + 2]
        val lum = LutColorMath.luma(r, g, b)
        var outR = r
        var outG = g
        var outB = b
        if (highlight != 0) {
            val w = if (lum > 0.5f) (lum - 0.5f) / 0.5f else 0f
            val sc = 1f + highlight / 50f * 0.7f * w
            outR *= sc; outG *= sc; outB *= sc
        }
        if (shadow != 0) {
            val w = if (lum < 0.5f) (0.5f - lum) / 0.5f else 0f
            val sc = 1f + shadow / 50f * 0.7f * w
            outR *= sc; outG *= sc; outB *= sc
        }
        s[b0] = outR
        s[b0 + 1] = outG
        s[b0 + 2] = outB
    }

    private fun applyContrastInPlace(s: FloatArray, b0: Int, contrast: Int) {
        val r = s[b0]
        val g = s[b0 + 1]
        val b = s[b0 + 2]
        val lum = LutColorMath.luma(r, g, b)
        val hlT = if (lum > HL_BRIGHT_DAMP_START) (lum - HL_BRIGHT_DAMP_START) / (1f - HL_BRIGHT_DAMP_START) else 0f
        val hlDamp = max(HL_CONTRAST_DAMP_MIN, 1f - hlT * HL_CONTRAST_DAMP_STRENGTH)
        val scale = 1f + contrast / 50f * 0.57f * hlDamp
        s[b0] = 0.5f + scale * (r - 0.5f)
        s[b0 + 1] = 0.5f + scale * (g - 0.5f)
        s[b0 + 2] = 0.5f + scale * (b - 0.5f)
    }

    private fun applySaturationInPlace(s: FloatArray, b0: Int, sat: Int) {
        val r = s[b0]
        val g = s[b0 + 1]
        val b = s[b0 + 2]
        val gray = LutColorMath.luma(r, g, b)
        val scale = 1f + sat / 50f * 0.82f
        s[b0] = gray + scale * (r - gray)
        s[b0 + 1] = gray + scale * (g - gray)
        s[b0 + 2] = gray + scale * (b - gray)
    }

    private fun applyTempTintInPlace(s: FloatArray, b0: Int, temp: Int, tint: Int) {
        val t = temp / 50f
        val ti = tint / 50f
        var rGain = 1f + t * 0.23f
        var gGain = 1f + t * 0.058f
        var bGain = 1f - t * 0.23f
        rGain *= 1f + ti * 0.045f
        gGain *= 1f - ti * 0.09f
        bGain *= 1f + ti * 0.045f
        val lumBefore = KR + KG + KB
        val lumAfter = KR * rGain + KG * gGain + KB * bGain
        if (lumAfter > 0.001f) {
            val scale = lumBefore / lumAfter
            rGain *= scale
            gGain *= scale
            bGain *= scale
        }
        val r = s[b0]
        val g = s[b0 + 1]
        val b = s[b0 + 2]
        val lum = LutColorMath.luma(r, g, b)
        val hlWeight = if (lum > TEMP_HL_START) ((lum - TEMP_HL_START) / (1f - TEMP_HL_START)).coerceIn(0f, 1f) else 0f
        val hlDamp = 1f - hlWeight * TEMP_HL_PROTECT
        s[b0] = r * (1f + (rGain - 1f) * hlDamp)
        s[b0 + 1] = g * (1f + (gGain - 1f) * hlDamp)
        s[b0 + 2] = b * (1f + (bGain - 1f) * hlDamp)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 小工具
    // ─────────────────────────────────────────────────────────────────────────

    private fun sampleAfter(skin: FloatArray, p: P, out: FloatArray): LutSkinFeatures {
        applyToSkin(skin, p, out)
        return LutSkinStats.sample(out)
    }

    private fun evaluateLum(skin: FloatArray, out: FloatArray, p: P): Double =
        sampleAfter(skin, p, out).skinLum

    private fun medianOfSorted(sorted: DoubleArray): Double {
        val n = sorted.size
        if (n == 0) return 0.0
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }

    private fun sq(v: Double): Double = v * v

    private fun fmt2(v: Double): String = String.format(java.util.Locale.US, "%.2f", v)
    private fun fmt3(v: Double): String = String.format(java.util.Locale.US, "%.3f", v)
    private fun fmt4(v: Double): String = String.format(java.util.Locale.US, "%.4f", v)

    /** 给用户看的一句话总结：命中哪个模板、依据的两个数、搜出来的三个主参数。 */
    private fun buildNote(mode: LutAnalysisMode, f: LutSkinFeatures, params: LutParams): String {
        val rb = fmt2(f.skinRb)
        val lum = fmt2(f.skinLum)
        val hl = String.format(java.util.Locale.US, "%.0f%%", f.fullHlRatio * 100)
        val head = when (mode) {
            LutAnalysisMode.ExtremeDark -> "极暗模板：肤色亮度 $lum、高光 $hl"
            LutAnalysisMode.HighMatch -> "高匹配模板：肤色 R/B $rb、亮度 $lum"
            LutAnalysisMode.Standard -> "标准模板：肤色 R/B $rb、亮度 $lum"
        }
        val bright = if (params.brightness >= 0) "+${params.brightness}" else params.brightness.toString()
        return "$head，色温 ${params.temperature}、亮度 $bright、对比度 ${params.contrast}"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 内部状态
    // ─────────────────────────────────────────────────────────────────────────

    /** 7 个参数的可变副本；搜索过程中反复改它。 */
    private class P(
        var temp: Int,
        var tint: Int,
        var sat: Int,
        var bright: Int,
        var contrast: Int,
        var highlight: Int,
        var shadow: Int
    ) {
        fun copy(): P = P(temp, tint, sat, bright, contrast, highlight, shadow)

        fun toLutParams(): LutParams = LutParams(
            temperature = temp,
            tint = tint,
            saturation = sat,
            brightness = bright,
            contrast = contrast,
            highlight = highlight,
            shadow = shadow
        )

        companion object {
            fun of(t: LutTemplate): P = P(t.temp, t.tint, t.sat, t.bright, t.contrast, t.highlight, t.shadow)

            fun of(params: LutParams): P = P(
                params.temperature, params.tint, params.saturation, params.brightness,
                params.contrast, params.highlight, params.shadow
            )
        }
    }

    // 与 LutColorMath 同一组常量（参考实现 mpv_realtime_lut.py:118-120 / 234-246）。
    private const val KR = 0.2126f
    private const val KG = 0.7152f
    private const val KB = 0.0722f
    private const val OVEREXPOSE_LIMIT = 0.92f
    private const val HL_BRIGHT_DAMP_START = 0.7f
    private const val HL_BRIGHT_DAMP_STRENGTH = 0.7f
    private const val HL_BRIGHT_DAMP_MIN = 0.3f
    private const val HL_CONTRAST_DAMP_STRENGTH = 0.5f
    private const val HL_CONTRAST_DAMP_MIN = 0.5f
    private const val TEMP_HL_START = 0.70f
    private const val TEMP_HL_PROTECT = 0.95f
}
