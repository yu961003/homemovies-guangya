package com.example.localmovielibrarz.mpv

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Build
import android.util.Log
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.localmovielibrarz.playback.engine.MpvOptions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipFile

/**
 * mpv 原生播放链路真机/模拟器验证。
 *
 * 为什么需要这一组测试：`MPVLib.isAvailable()` 只反映**编译期**的 MPV_AVAILABLE 宏，
 * 即使 APK 里一个 .so 都没有、CMake 编出的是 stub.cpp 版本，它也可能返回 true/false
 * 而编译期早已知晓。真正能证明「libmpv.so 确实进了 APK、确实被加载、确实能解码」
 * 的只有运行期行为，所以这里逐层验证：
 *
 *  1. [packagedNativeLibsArePerAbi] —— 安装后的 APK 里是否真有 libmpv.so（打包层）
 *  2. [engineCreatesAndInitializes]  —— System.loadLibrary("player") 能否连同
 *     libmpv.so 一起加载成功，mpv_create/mpv_initialize 能否返回成功（加载层）
 *  3. [decodesVideoFramesHeadless]   —— vo=null 下解封装 + 解码能否推进时间轴（解码层）
 *  4. [rendersToSurface]             —— vo=gpu + gpu-context=android 挂 Surface 后
 *     vo-configured 是否变 true（渲染层）
 *
 * 失败信息里带上 lastError / loadErrorMessage，避免只看到 "expected true" 而无从下手。
 */
@RunWith(AndroidJUnit4::class)
class MpvPlaybackSmokeTest {

    private companion object {
        const val TAG = "MpvSmokeTest"

        /** androidTest/assets 里带一个 10 秒 360p H.264 样本，测试不依赖外部文件。 */
        const val ASSET_NAME = "test_360p_10s.mp4"

        /** 引擎创建/初始化在模拟器上偏慢（首次 JIT + mediacodec 探测）。 */
        const val INIT_TIMEOUT_MS = 10_000L
        const val EVENT_TIMEOUT_MS = 20_000L
    }

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    @After
    fun tearDown() {
        onMain { MPVLib.destroyEngine() }
        MPVLib.listeners.clear()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 1. 打包层：APK 里到底有没有 libmpv.so
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun packagedNativeLibsArePerAbi() {
        val apk = File(context.applicationInfo.sourceDir)
        assertTrue("APK 不存在：${apk.absolutePath}", apk.isFile)

        val entries = ZipFile(apk).use { zip ->
            zip.entries().asSequence()
                .map { it.name }
                .filter { it.startsWith("lib/") && it.endsWith(".so") }
                .toList()
        }
        val libMpv = entries.filter { it.endsWith("/libmpv.so") }

        // 一个都不能少：libmpv.so 缺失正是用户看到的「原生库缺失」。
        assertTrue(
            "APK 内没有任何 libmpv.so；实际打包的原生库=${entries.sorted()}",
            libMpv.isNotEmpty()
        )

        // 每个进包的 ABI 都必须成组出现：libmpv.so 在，libplayer.so 也在。
        val abis = entries.map { it.split('/')[1] }.distinct().sorted()
        abis.forEach { abi ->
            assertTrue("$abi 缺少 libmpv.so", "lib/$abi/libmpv.so" in entries)
            assertTrue("$abi 缺少 libplayer.so", "lib/$abi/libplayer.so" in entries)
        }
        Log.i(TAG, "APK 原生库 ABI=$abis；libmpv.so=${libMpv.sorted()}")

        // 当前设备的 ABI 必须在包内，否则安装后运行期必然加载失败。
        val deviceAbi = Build.SUPPORTED_ABIS.firstOrNull { it in abis }
        assertTrue(
            "设备 ABI=${Build.SUPPORTED_ABIS.toList()} 与包内 ABI=$abis 无交集",
            deviceAbi != null
        )
        // 运行期真正被 dlopen 的就是这个目录，目录里必须真有文件。
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        assertTrue(
            "$nativeDir 下没有 libmpv.so：${nativeDir.list()?.toList()}",
            File(nativeDir, "libmpv.so").isFile
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2. 加载层：libplayer.so 能否带着 libmpv.so 一起加载，引擎能否初始化
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun engineCreatesAndInitializes() {
        assertTrue(
            "libplayer.so 未加载：${MPVLib.loadErrorMessage()}",
            MPVLib.probe()
        )

        val created = onMain { MPVLib.createEngine(context) }
        assertEquals(
            "create() 失败（-2 表示 mpv_create 返回 NULL）：${MPVLib.loadErrorMessage()}",
            0,
            created
        )

        // 用产品真实参数集，但不依赖画面：vo=null / ao=null 只验证引擎选项能落地。
        val cacheDir = File(context.cacheDir, "mpv-test").apply { mkdirs() }
        val caFile = com.example.localmovielibrarz.playback.engine.MpvCaBundle.ensure(context)
        assertTrue(
            "CA 包没解出来（assets/mpv/cacert.pem 缺失或损坏）——有锚才能开 tls-verify=yes",
            !caFile.isNullOrBlank() && File(caFile).length() > 65536
        )
        onMain {
            MpvOptions.build(context, "no", cacheDir, caFile).forEach { (k, v) ->
                val ret = MPVLib.setOption(k, v)
                if (ret < 0) Log.w(TAG, "setOption $k=$v 返回 $ret")
            }
            MPVLib.setOption("vo", "null")
            MPVLib.setOption("ao", "null")
        }

        val initialized = onMain { MPVLib.initialize() }
        assertEquals(
            "init() 失败（-3 表示 mpv_initialize 失败）：${MPVLib.loadErrorMessage()}",
            0,
            initialized
        )

        // 引擎跑起来后，这些核心属性必须可读——mpv 的 option/property 系统已工作。
        val mpvVersion = MPVLib.getString("mpv-version")
        assertTrue("mpv-version 为空，mpv 实例状态异常", !mpvVersion.isNullOrBlank())
        val ffmpeg = MPVLib.getString("ffmpeg-version")
        assertTrue("ffmpeg-version 为空，libav* 未正确链接", !ffmpeg.isNullOrBlank())
        Log.i(TAG, "mpv-version=$mpvVersion; ffmpeg=${ffmpeg?.take(60)}")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3. 解码层：解封装 + 解码能否推进时间轴
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun decodesVideoFramesHeadless() {
        val video = copyAssetToCache(ASSET_NAME)
        assertTrue("测试视频不存在：${video.absolutePath}", video.length() > 0)

        val fileLoaded = java.util.concurrent.atomic.AtomicBoolean(false)
        val endFileReason = java.util.concurrent.atomic.AtomicInteger(-1)
        val listener = object : MPVLib.Listener {
            override fun onEvent(eventId: Int) {
                if (eventId == MPVLib.MPV_EVENT_FILE_LOADED) fileLoaded.set(true)
            }

            override fun onEndFile(reason: Int, error: Int) {
                endFileReason.set(reason)
            }

            override fun onProperty(name: String, value: Any?) {
                // 本用例直接轮询属性，不依赖回调值；实现空体只是为了满足接口。
            }
        }

        assertEquals(0, onMain { MPVLib.createEngine(context) })
        onMain {
            MPVLib.setOption("vo", "null")
            MPVLib.setOption("ao", "null")
            MPVLib.setOption("hwdec", "no")
            MPVLib.setOption("idle", "yes")
            MPVLib.setOption("msg-level", "all=info")
        }
        assertEquals("mpv 初始化失败：${MPVLib.loadErrorMessage()}", 0, onMain { MPVLib.initialize() })
        MPVLib.listeners.add(listener)

        MPVLib.observe("time-pos", MPVLib.MPV_FORMAT_DOUBLE)
        MPVLib.observe("duration", MPVLib.MPV_FORMAT_DOUBLE)

        onMain { MPVLib.cmd("loadfile", video.absolutePath, "replace") }

        assertTrue(
            "等待 FILE_LOADED 超时：${MPVLib.loadErrorMessage()}",
            await(EVENT_TIMEOUT_MS) { fileLoaded.get() }
        )

        val duration = awaitValue(EVENT_TIMEOUT_MS) {
            MPVLib.getDouble("duration")?.takeIf { it > 0.0 }
        }
        assertTrue("duration 读取失败：$duration", duration != null && duration!! > 5.0)

        // 时间轴推进 = 至少一帧被解出来了。无音频输出时 mpv 会全速跑完，
        // 所以「时间前进」或「END_FILE 正常 EOF」二者之一出现即证明解码链路通。
        val advanced = await(EVENT_TIMEOUT_MS) {
            val pos = MPVLib.getDouble("time-pos") ?: 0.0
            pos > 0.5 || endFileReason.get() == MPVLib.MPV_END_FILE_REASON_EOF
        }
        assertTrue(
            "播放 20 秒仍无时间推进（time-pos=${MPVLib.getDouble("time-pos")}，" +
                "endFile=${endFileReason.get()}）：${MPVLib.loadErrorMessage()}",
            advanced
        )
        Log.i(
            TAG,
            "解码验证通过：duration=$duration time-pos=${MPVLib.getDouble("time-pos")} " +
                "endFileReason=${endFileReason.get()}"
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4. 渲染层：vo=gpu + gpu-context=android 挂上 Surface
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun rendersToSurface() {
        val video = copyAssetToCache(ASSET_NAME)
        val surfaceTexture = SurfaceTexture(1).apply { setDefaultBufferSize(640, 360) }
        val surface = Surface(surfaceTexture)

        try {
            assertEquals(0, onMain { MPVLib.createEngine(context) })
            onMain {
                MpvOptions.build(
                    context,
                    "no",
                    File(context.cacheDir, "mpv-test"),
                    com.example.localmovielibrarz.playback.engine.MpvCaBundle.ensure(context),
                ).forEach { (k, v) ->
                    val ret = MPVLib.setOption(k, v)
                    if (ret < 0) Log.w(TAG, "setOption $k=$v 返回 $ret")
                }
                // 模拟器上没有 mediacodec 时退回软解，避免把「无硬解」误判成「无渲染」。
                MPVLib.setOption("hwdec", "no")
            }
            assertEquals("mpv 初始化失败：${MPVLib.loadErrorMessage()}", 0, onMain { MPVLib.initialize() })

            val attachRet = MPVLib.attachSurfaceSafe(surface)
            assertEquals("attachSurface 失败：${MPVLib.loadErrorMessage()}", 0, attachRet)
            MPVLib.setSurfaceSizeSafe(640, 360)

            MPVLib.observe("vo-configured", MPVLib.MPV_FORMAT_FLAG)
            MPVLib.observe("width", MPVLib.MPV_FORMAT_INT64)
            MPVLib.observe("height", MPVLib.MPV_FORMAT_INT64)

            onMain { MPVLib.cmd("loadfile", video.absolutePath, "replace") }

            val voReady = await(EVENT_TIMEOUT_MS) {
                MPVLib.getBoolean("vo-configured") == true
            }
            val width = MPVLib.getInt("width") ?: 0
            val height = MPVLib.getInt("height") ?: 0
            Log.i(TAG, "vo-configured=$voReady width=$width height=$height")

            assertTrue(
                "20 秒内 vo-configured 未变 true（width=$width height=$height）：" +
                    "${MPVLib.loadErrorMessage()}",
                voReady
            )
            assertTrue("videoparams 宽高异常：${width}x$height", width > 0 && height > 0)
        } finally {
            MPVLib.detachSurfaceSafe()
            runCatching { surface.release() }
            runCatching { surfaceTexture.release() }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 工具
    // ─────────────────────────────────────────────────────────────────────────

    private fun copyAssetToCache(name: String): File {
        val out = File(context.cacheDir, name)
        if (out.length() > 0) return out
        context.assets.open(name).use { input ->
            out.outputStream().use { input.copyTo(it) }
        }
        return out
    }

    /** mpv 的 Java 侧入口要求主线程，测试统一从这里下到主线程执行。 */
    private fun <T> onMain(block: () -> T): T {
        var result: Any? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun await(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }

    private fun <T : Any> awaitValue(timeoutMs: Long, supplier: () -> T?): T? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            supplier()?.let { return it }
            Thread.sleep(100)
        }
        return supplier()
    }
}