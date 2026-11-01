package com.example.localmovielibrarz.playback.face

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.example.localmovielibrarz.playback.lut.LutFaceGeometry
import com.example.localmovielibrarz.playback.lut.LutFaceRegion
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.Connection
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import java.io.Closeable
import kotlin.math.max
import kotlin.math.min

/**
 * 一次人脸检测的结果。
 *
 * @property regions 已翻译成 [LutFaceRegion] 的人脸（坐标相对**传入的整帧**归一化）。
 * @property backend 实际用了哪条路径：`face_landmarker` / `face_detector` / `none`。
 * @property elapsedMs 检测耗时（含多尺度重试），写进分析日志便于真机评估。
 * @property error 两条路径都不可用时的原因（模型缺失 / 原生库加载失败等）；正常为 null。
 */
data class FaceDetectionResult(
    val regions: List<LutFaceRegion>,
    val backend: String,
    val elapsedMs: Long,
    val error: String? = null
)

/**
 * MediaPipe Tasks 人脸检测 → 肤色采样区域。
 *
 * ## 定位到的 v1 不足（本类要补的）
 *
 * 1. **完全没有人脸检测**：`LutSkinAnalyzer` 注释里写明「人脸框路径未移植」，只能整帧找肤色，
 *    背景里的木地板/墙面/暖光床单会把 R/B 和亮度统计拉偏；
 * 2. **自动分析只支持 mpv**：exo 内核下直接返回「仅 mpv 可用」（截帧另由 `ExoFrameGrabber` 补齐）；
 * 3. **无法区分「没有人」与「有人但没采到」**：结论文案只有一句「没检测到足够肤色像素」。
 *
 * ## 两级模型
 *
 * | 级别 | 模型（随包 assets/mediapipe/） | 产出 | 用途 |
 * |---|---|---|---|
 * | 1 | `face_landmarker.task`（478 点网格，约 3.6 MB） | 轮廓凸包 − 眼/眉/唇 | 首选：最干净的皮肤区域 |
 * | 2 | `blaze_face_short_range.tflite`（约 230 KB） | 两颊 + 鼻梁 | 网格模型不可用时兜底 |
 *
 * 任一级初始化失败（模型缺失、原生库不支持当前 ABI）都只降级不抛异常；两级都不可用时
 * 返回空结果，分析器自动回到整帧路径——**人脸检测永远不会让自动分析失败**。
 *
 * ## 多尺度
 *
 * 两个模型内部都用 short-range 检测器，对「远景小脸」（占画面 < 5%）召回率偏低，
 * 而影片里中远景很常见。整帧没检出时，再在画面中央 2/3 区域放大重试一次，坐标映射回整帧。
 *
 * ## 线程
 *
 * MediaPipe 的 task 实例不是线程安全的：[detect] 整体加锁；只应在后台线程调用（单次 30~80 ms）。
 */
class MediaPipeFaceRegionDetector(context: Context) : Closeable {

    private val appContext = context.applicationContext
    private val lock = Any()

    private var landmarker: FaceLandmarker? = null
    private var detector: FaceDetector? = null
    private var landmarkerFailed = false
    private var detectorFailed = false
    private var initError: String? = null
    private var closed = false

    /** 网格模型各部位的点索引（从 MediaPipe 的连线常量里取，不手抄索引表）。 */
    private val ovalIdx: IntArray by lazy { indices(FaceLandmarker.FACE_LANDMARKS_FACE_OVAL) }
    private val eyeIdx: List<IntArray> by lazy {
        listOf(
            indices(FaceLandmarker.FACE_LANDMARKS_LEFT_EYE + FaceLandmarker.FACE_LANDMARKS_LEFT_IRIS),
            indices(FaceLandmarker.FACE_LANDMARKS_RIGHT_EYE + FaceLandmarker.FACE_LANDMARKS_RIGHT_IRIS)
        )
    }
    private val browIdx: List<IntArray> by lazy {
        listOf(
            indices(FaceLandmarker.FACE_LANDMARKS_LEFT_EYE_BROW),
            indices(FaceLandmarker.FACE_LANDMARKS_RIGHT_EYE_BROW)
        )
    }
    private val lipIdx: IntArray by lazy { indices(FaceLandmarker.FACE_LANDMARKS_LIPS) }

    /**
     * 检测 [bitmap] 里的人脸。[bitmap] 必须是 ARGB_8888（mpv 截图解码出来的就是）。
     */
    fun detect(bitmap: Bitmap): FaceDetectionResult {
        val start = SystemClock.elapsedRealtime()
        synchronized(lock) {
            if (closed) return FaceDetectionResult(emptyList(), BACKEND_NONE, 0L, "检测器已关闭")
            val input = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
            try {
                ensureLandmarker()?.let { lm ->
                    var regions = runLandmarker(lm, input, 0f, 0f, 1f, 1f)
                    if (regions.isEmpty()) regions = retryCentered(input) { crop, ox, oy, sx, sy -> runLandmarker(lm, crop, ox, oy, sx, sy) }
                    return FaceDetectionResult(regions, BACKEND_LANDMARKER, SystemClock.elapsedRealtime() - start)
                }
                ensureDetector()?.let { fd ->
                    var regions = runDetector(fd, input, 0f, 0f, 1f, 1f)
                    if (regions.isEmpty()) regions = retryCentered(input) { crop, ox, oy, sx, sy -> runDetector(fd, crop, ox, oy, sx, sy) }
                    return FaceDetectionResult(regions, BACKEND_DETECTOR, SystemClock.elapsedRealtime() - start)
                }
                return FaceDetectionResult(emptyList(), BACKEND_NONE, SystemClock.elapsedRealtime() - start, initError)
            } catch (t: Throwable) {
                // 推理期异常（极少见，例如原生层 OOM）：本次按「无人脸」处理，不影响分析主流程。
                Log.w(TAG, "人脸检测失败，本次按无人脸处理", t)
                return FaceDetectionResult(emptyList(), BACKEND_NONE, SystemClock.elapsedRealtime() - start, t.message)
            } finally {
                if (input !== bitmap) input.recycle()
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            runCatching { landmarker?.close() }
            runCatching { detector?.close() }
            landmarker = null
            detector = null
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 初始化（惰性、失败只记一次）
    // ─────────────────────────────────────────────────────────────────────────

    private fun ensureLandmarker(): FaceLandmarker? {
        landmarker?.let { return it }
        if (landmarkerFailed) return null
        if (!assetExists(MODEL_LANDMARKER)) {
            landmarkerFailed = true
            initError = "缺少模型 assets/$MODEL_LANDMARKER"
            return null
        }
        return runCatching {
            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        .setModelAssetPath(MODEL_LANDMARKER)
                        .setDelegate(Delegate.CPU)
                        .build()
                )
                .setRunningMode(RunningMode.IMAGE)
                .setNumFaces(MAX_FACES)
                .setMinFaceDetectionConfidence(MIN_CONFIDENCE)
                .setMinFacePresenceConfidence(MIN_CONFIDENCE)
                .setOutputFaceBlendshapes(false)
                .setOutputFacialTransformationMatrixes(false)
                .build()
            FaceLandmarker.createFromOptions(appContext, options)
        }.onFailure {
            // UnsatisfiedLinkError（ABI 不支持）也在这里被接住：Throwable 级别。
            landmarkerFailed = true
            initError = "人脸网格模型初始化失败：${it.javaClass.simpleName} ${it.message.orEmpty()}"
            Log.w(TAG, initError!!, it)
        }.getOrNull()?.also { landmarker = it }
    }

    private fun ensureDetector(): FaceDetector? {
        detector?.let { return it }
        if (detectorFailed) return null
        if (!assetExists(MODEL_DETECTOR)) {
            detectorFailed = true
            initError = (initError?.let { "$it；" } ?: "") + "缺少模型 assets/$MODEL_DETECTOR"
            return null
        }
        return runCatching {
            val options = FaceDetector.FaceDetectorOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        .setModelAssetPath(MODEL_DETECTOR)
                        .setDelegate(Delegate.CPU)
                        .build()
                )
                .setRunningMode(RunningMode.IMAGE)
                .setMinDetectionConfidence(MIN_CONFIDENCE)
                .build()
            FaceDetector.createFromOptions(appContext, options)
        }.onFailure {
            detectorFailed = true
            initError = (initError?.let { "$it；" } ?: "") +
                "人脸检测模型初始化失败：${it.javaClass.simpleName} ${it.message.orEmpty()}"
            Log.w(TAG, "人脸检测模型初始化失败", it)
        }.getOrNull()?.also { detector = it }
    }

    private fun assetExists(path: String): Boolean =
        runCatching { appContext.assets.open(path).use { true } }.getOrDefault(false)

    // ─────────────────────────────────────────────────────────────────────────
    // 推理 + 翻译成 LutFaceRegion
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * 网格路径。[ox]/[oy]/[sx]/[sy] 把裁剪图里的归一化坐标映射回整帧：`full = o + crop * s`。
     */
    private fun runLandmarker(
        lm: FaceLandmarker, image: Bitmap, ox: Float, oy: Float, sx: Float, sy: Float
    ): List<LutFaceRegion> {
        val result = lm.detect(BitmapImageBuilder(image).build())
        val faces = result.faceLandmarks()
        if (faces.isEmpty()) return emptyList()
        val out = ArrayList<LutFaceRegion>(faces.size)
        for (landmarks in faces) {
            val n = landmarks.size
            if (n < 468) continue
            val xs = FloatArray(n) { ox + landmarks[it].x() * sx }
            val ys = FloatArray(n) { oy + landmarks[it].y() * sy }
            var l = 1f
            var t = 1f
            var r = 0f
            var b = 0f
            for (i in 0 until n) {
                l = min(l, xs[i]); r = max(r, xs[i])
                t = min(t, ys[i]); b = max(b, ys[i])
            }
            val oval = LutFaceGeometry.convexHull(pick(xs, ys, ovalIdx))
            val exclude = ArrayList<FloatArray>(5)
            eyeIdx.forEach { exclude += LutFaceGeometry.scale(LutFaceGeometry.convexHull(pick(xs, ys, it)), EYE_DILATE) }
            browIdx.forEach { exclude += LutFaceGeometry.scale(LutFaceGeometry.convexHull(pick(xs, ys, it)), BROW_DILATE) }
            exclude += LutFaceGeometry.scale(LutFaceGeometry.convexHull(pick(xs, ys, lipIdx)), LIP_DILATE)
            out += LutFaceRegion(
                left = l.coerceIn(0f, 1f), top = t.coerceIn(0f, 1f),
                right = r.coerceIn(0f, 1f), bottom = b.coerceIn(0f, 1f),
                include = listOf(oval),
                exclude = exclude,
                // FaceLandmarker 不直接给每张脸的置信度；能出网格即已过 presence 阈值。
                confidence = MIN_CONFIDENCE,
                source = LutFaceRegion.Source.MESH
            )
        }
        return out
    }

    /** 检测框路径：BlazeFace 6 个关键点 → 两颊 + 鼻梁（见 [LutFaceGeometry.regionsFromBlazeKeypoints]）。 */
    private fun runDetector(
        fd: FaceDetector, image: Bitmap, ox: Float, oy: Float, sx: Float, sy: Float
    ): List<LutFaceRegion> {
        val result = fd.detect(BitmapImageBuilder(image).build())
        val w = image.width.toFloat()
        val h = image.height.toFloat()
        return result.detections().mapNotNull { det ->
            val box = det.boundingBox() // 像素坐标
            val score = det.categories().firstOrNull()?.score() ?: 0f
            if (score < MIN_CONFIDENCE) return@mapNotNull null
            val kp = det.keypoints().orElse(null)?.let { list ->
                FloatArray(list.size * 2) { i ->
                    val p = list[i / 2]
                    if (i % 2 == 0) ox + p.x() * sx else oy + p.y() * sy
                }
            }
            LutFaceGeometry.regionsFromBlazeKeypoints(
                left = (ox + box.left / w * sx).coerceIn(0f, 1f),
                top = (oy + box.top / h * sy).coerceIn(0f, 1f),
                right = (ox + box.right / w * sx).coerceIn(0f, 1f),
                bottom = (oy + box.bottom / h * sy).coerceIn(0f, 1f),
                kp = kp,
                confidence = score
            )
        }
    }

    /** 中央 2/3 区域放大重试（远景小脸）。 */
    private inline fun retryCentered(
        input: Bitmap,
        run: (crop: Bitmap, ox: Float, oy: Float, sx: Float, sy: Float) -> List<LutFaceRegion>
    ): List<LutFaceRegion> {
        val cw = (input.width * CENTER_CROP).toInt()
        val ch = (input.height * CENTER_CROP).toInt()
        if (cw < MIN_CROP_SIDE || ch < MIN_CROP_SIDE) return emptyList()
        val x = (input.width - cw) / 2
        val y = (input.height - ch) / 2
        val crop = Bitmap.createBitmap(input, x, y, cw, ch)
        return try {
            run(
                crop,
                x / input.width.toFloat(), y / input.height.toFloat(),
                cw / input.width.toFloat(), ch / input.height.toFloat()
            )
        } finally {
            if (crop !== input) crop.recycle()
        }
    }

    private fun pick(xs: FloatArray, ys: FloatArray, idx: IntArray): FloatArray {
        val out = FloatArray(idx.size * 2)
        var k = 0
        for (i in idx) {
            if (i !in xs.indices) continue
            out[k++] = xs[i]
            out[k++] = ys[i]
        }
        return if (k == out.size) out else out.copyOf(k)
    }

    private fun indices(connections: Set<Connection>): IntArray {
        val set = java.util.TreeSet<Int>()
        for (c in connections) {
            set += c.start()
            set += c.end()
        }
        return set.toIntArray()
    }

    companion object {
        private const val TAG = "MpFaceRegion"

        const val MODEL_LANDMARKER = "mediapipe/face_landmarker.task"
        const val MODEL_DETECTOR = "mediapipe/blaze_face_short_range.tflite"

        const val BACKEND_LANDMARKER = "face_landmarker"
        const val BACKEND_DETECTOR = "face_detector"
        const val BACKEND_NONE = "none"

        /** 一帧最多分析几张脸：再多统计意义不大，且推理耗时线性增长。 */
        private const val MAX_FACES = 4
        private const val MIN_CONFIDENCE = 0.5f

        private const val EYE_DILATE = 1.45f
        private const val BROW_DILATE = 1.25f
        private const val LIP_DILATE = 1.15f

        private const val CENTER_CROP = 0.66f
        private const val MIN_CROP_SIDE = 96
    }
}
