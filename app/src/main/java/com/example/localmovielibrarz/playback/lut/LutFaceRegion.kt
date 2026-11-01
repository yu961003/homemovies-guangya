package com.example.localmovielibrarz.playback.lut

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 一张脸上「可以采肤色」的区域，坐标全部归一化到 0..1（相对整帧宽高）。
 *
 * ## 为什么单独建模（而不是直接把 MediaPipe 结果传进分析器）
 *
 * [LutSkinAnalyzer] 是纯 Kotlin、可以在 JVM 单测里跑的；MediaPipe 是 Android + 原生库。
 * 把「人脸检测」与「在人脸区域里统计肤色」之间的契约收敛成这个值类型，
 * 两边就能独立演进、独立测试：
 * - Android 侧（`playback/face/MediaPipeFaceRegionDetector`）只负责把关键点翻译成多边形；
 * - 分析器只负责「多边形内哪些像素是皮肤」。
 *
 * @property include 采样多边形（`[x0,y0,x1,y1,...]`）。网格模型给的是脸部轮廓的凸包；
 *   检测框模型给的是由关键点推出的两颊 + 鼻梁三个小区域。
 * @property exclude 要挖掉的多边形：眼睛、眉毛、嘴唇（它们的颜色会把 Cr 分位区间拉偏）。
 * @property confidence 检测置信度 0..1。
 * @property source 区域来自哪种模型，决定 [LutSkinFeatures.regionName]。
 */
class LutFaceRegion(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val include: List<FloatArray>,
    val exclude: List<FloatArray> = emptyList(),
    val confidence: Float = 1f,
    val source: Source = Source.MESH
) {
    enum class Source(val regionName: String) {
        /** FaceLandmarker 478 点网格：最精确，皮肤区域 = 轮廓 − 五官。 */
        MESH("face_mesh"),

        /** FaceDetector（BlazeFace）检测框 + 6 个关键点：两颊与鼻梁。 */
        BOX("face_box")
    }

    /** 人脸面积占整帧的比例。 */
    val areaFraction: Float get() = max(0f, right - left) * max(0f, bottom - top)

    override fun toString(): String =
        "LutFaceRegion(${source.name}, conf=%.2f, box=[%.2f,%.2f,%.2f,%.2f], inc=${include.size}, exc=${exclude.size})"
            .format(java.util.Locale.US, confidence, left, top, right, bottom)
}

/**
 * 人脸区域的几何工具（纯函数，可单测）。
 */
object LutFaceGeometry {

    /** 人脸面积低于整帧这个比例就丢弃：太小的脸采不到 150 个有效像素，反而会引入噪声。 */
    const val MIN_FACE_AREA_FRACTION = 0.004f

    /**
     * 求点集的凸包（Andrew 单调链），输入 `[x0,y0,x1,y1,...]`，输出逆时针多边形。
     *
     * 用凸包而不是按连接关系拼环：MediaPipe 的眼睛/嘴唇连线是「上沿 + 下沿」两条链，
     * 拼环需要依赖索引顺序；凸包只依赖点集合，对模型版本变化更稳。
     */
    fun convexHull(points: FloatArray): FloatArray {
        val n = points.size / 2
        if (n < 3) return points.copyOf()
        val idx = (0 until n).sortedWith(compareBy<Int>({ points[it * 2] }, { points[it * 2 + 1] }))
        val hull = IntArray(2 * n)
        var k = 0
        fun cross(o: Int, a: Int, b: Int): Float =
            (points[a * 2] - points[o * 2]) * (points[b * 2 + 1] - points[o * 2 + 1]) -
                (points[a * 2 + 1] - points[o * 2 + 1]) * (points[b * 2] - points[o * 2])
        for (i in idx) {
            while (k >= 2 && cross(hull[k - 2], hull[k - 1], i) <= 0f) k--
            hull[k++] = i
        }
        val lower = k + 1
        for (j in idx.indices.reversed()) {
            val i = idx[j]
            while (k >= lower && cross(hull[k - 2], hull[k - 1], i) <= 0f) k--
            hull[k++] = i
        }
        val count = (k - 1).coerceAtLeast(0)
        val out = FloatArray(count * 2)
        for (i in 0 until count) {
            out[i * 2] = points[hull[i] * 2]
            out[i * 2 + 1] = points[hull[i] * 2 + 1]
        }
        return out
    }

    /** 以质心为中心缩放多边形（眼睛区域外扩一点，把睫毛与眼影一起挖掉）。 */
    fun scale(polygon: FloatArray, factor: Float): FloatArray {
        val n = polygon.size / 2
        if (n == 0) return polygon
        var cx = 0f
        var cy = 0f
        for (i in 0 until n) {
            cx += polygon[i * 2]
            cy += polygon[i * 2 + 1]
        }
        cx /= n
        cy /= n
        return FloatArray(polygon.size) { i ->
            val c = if (i % 2 == 0) cx else cy
            c + (polygon[i] - c) * factor
        }
    }

    /**
     * BlazeFace 的 6 个关键点 → 两颊与鼻梁三个采样区。
     *
     * 关键点顺序（MediaPipe FaceDetector 输出约定）：0 右眼、1 左眼、2 鼻尖、3 嘴、4 右耳屏、5 左耳屏
     * （"右/左"指被拍摄者自身）。区域尺寸按两眼间距归一，因此对远近景都成立：
     * - 颊区中心 = 眼睛正下方、眼与嘴的中线高度，半径 0.32 倍眼距；
     * - 鼻梁区 = 两眼中点到鼻尖之间的竖条。
     *
     * @param kp 归一化关键点 `[x0,y0,...,x5,y5]`；少于 4 个点时退化为检测框中下部。
     */
    fun regionsFromBlazeKeypoints(
        left: Float, top: Float, right: Float, bottom: Float,
        kp: FloatArray?,
        confidence: Float
    ): LutFaceRegion {
        val include = ArrayList<FloatArray>(3)
        if (kp != null && kp.size >= 8) {
            val rex = kp[0]; val rey = kp[1]
            val lex = kp[2]; val ley = kp[3]
            val nx = kp[4]; val ny = kp[5]
            val my = kp[7]
            val eyeDist = hypot(lex - rex, ley - rey).coerceAtLeast(1e-4f)
            val cheekY = (min(rey, ley) + my) / 2f + eyeDist * 0.05f
            val r = eyeDist * 0.32f
            include += circle(rex, cheekY, r)
            include += circle(lex, cheekY, r)
            val bridgeTop = (rey + ley) / 2f + eyeDist * 0.12f
            val bridgeW = eyeDist * 0.16f
            include += floatArrayOf(
                nx - bridgeW, bridgeTop, nx + bridgeW, bridgeTop,
                nx + bridgeW, ny - eyeDist * 0.08f, nx - bridgeW, ny - eyeDist * 0.08f
            )
        } else {
            val w = right - left
            val h = bottom - top
            include += floatArrayOf(
                left + w * 0.18f, top + h * 0.45f, right - w * 0.18f, top + h * 0.45f,
                right - w * 0.18f, top + h * 0.72f, left + w * 0.18f, top + h * 0.72f
            )
        }
        return LutFaceRegion(left, top, right, bottom, include, emptyList(), confidence, LutFaceRegion.Source.BOX)
    }

    private fun circle(cx: Float, cy: Float, r: Float, segments: Int = 14): FloatArray =
        FloatArray(segments * 2) { i ->
            val a = (i / 2) * (2.0 * Math.PI / segments)
            if (i % 2 == 0) cx + r * kotlin.math.cos(a).toFloat() else cy + r * kotlin.math.sin(a).toFloat()
        }

    /**
     * 把若干人脸区域光栅化成像素掩码（1 = 采样）。
     *
     * 先画所有 [LutFaceRegion.include]，再挖掉 [LutFaceRegion.exclude]。
     * 用扫描线 + 偶奇规则填充，复杂度 O(像素 + 边数×行数)，640 宽的帧里一张脸约 0.2 ms。
     *
     * @return 掩码与被置 1 的像素数；没有任何可用区域时返回 null。
     */
    fun rasterize(faces: List<LutFaceRegion>, width: Int, height: Int): Pair<BooleanArray, Int>? {
        val usable = faces.filter { it.areaFraction >= MIN_FACE_AREA_FRACTION && it.include.isNotEmpty() }
        if (usable.isEmpty()) return null
        val mask = BooleanArray(width * height)
        for (face in usable) {
            for (poly in face.include) fill(mask, width, height, poly, true)
            for (poly in face.exclude) fill(mask, width, height, poly, false)
        }
        var count = 0
        for (v in mask) if (v) count++
        return if (count == 0) null else mask to count
    }

    private fun fill(mask: BooleanArray, width: Int, height: Int, poly: FloatArray, value: Boolean) {
        val n = poly.size / 2
        if (n < 3) return
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in 0 until n) {
            val y = poly[i * 2 + 1] * height
            minY = min(minY, y)
            maxY = max(maxY, y)
        }
        val y0 = max(0, floor(minY).toInt())
        val y1 = min(height - 1, ceil(maxY).toInt())
        val xs = FloatArray(n)
        for (py in y0..y1) {
            val sy = py + 0.5f
            var cnt = 0
            for (i in 0 until n) {
                val j = (i + 1) % n
                val ax = poly[i * 2] * width
                val ay = poly[i * 2 + 1] * height
                val bx = poly[j * 2] * width
                val by = poly[j * 2 + 1] * height
                if ((ay <= sy && by > sy) || (by <= sy && ay > sy)) {
                    val t = (sy - ay) / (by - ay)
                    xs[cnt++] = ax + t * (bx - ax)
                }
            }
            if (cnt < 2) continue
            java.util.Arrays.sort(xs, 0, cnt)
            var k = 0
            while (k + 1 < cnt) {
                val xa = max(0, ceil(xs[k] - 0.5f).toInt())
                val xb = min(width - 1, floor(xs[k + 1] - 0.5f).toInt())
                val row = py * width
                for (px in xa..xb) mask[row + px] = value
                k += 2
            }
        }
    }

    /** 两个归一化框的交并比，用于网格与检测框两路结果去重。 */
    fun iou(a: LutFaceRegion, b: LutFaceRegion): Float {
        val ix = max(0f, min(a.right, b.right) - max(a.left, b.left))
        val iy = max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
        val inter = ix * iy
        val union = a.areaFraction + b.areaFraction - inter
        return if (union <= 0f) 0f else inter / union
    }

    @Suppress("unused")
    private fun nearlyZero(v: Float) = abs(v) < 1e-6f
}
