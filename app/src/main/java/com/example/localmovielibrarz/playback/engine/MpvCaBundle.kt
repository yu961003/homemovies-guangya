package com.example.localmovielibrarz.playback.engine

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException

/**
 * 把随 APK 分发的 CA 根证书包解出来，给 libmpv 的 `--tls-ca-file` 用。
 *
 * ## 定位（已于第二轮更正）
 *
 * 这个类**不是** `loading_failed(-13)` 的主因修复，它只服务「退回 mpv 直连」这条兜底路径。
 * 主路径已改为回环代理（见 `MpvHttpProxy`）：那条路径上 TLS 由 OkHttp/系统 Conscrypt 完成，
 * 与本类无关。主因分析见 `docs/MPV-NETWORK-PROXY-20261029.md`。
 *
 * ## 仍然成立的事实（这些是二进制层面观测到的，不是推断）
 *
 * - 本工程打包的 `libavformat.so` 是**静态链接 mbedtls** 的构建（不依赖 `libssl.so`），
 *   且 mbedtls 后端**没有**「读 Android 系统 CA 库」的能力：lavf 的 `tls_*` 选项里
 *   只有 `ca_file`，没有 `ca_path`，也不会去扫 `/system/etc/security/cacerts`
 *   （对 `libavformat.so` 逐字检索：`/system/etc/security`、`cacerts` 均 0 命中，
 *   而 `ssl_tls.c`、`No CA chain is set` 命中）。
 * - 一旦把 `tls_verify` 置为真（yes/1）而没有 `ca_file`，mbedtls 就会返回
 *   `No CA chain is set, but required to operate. Was the CA correctly set?`，
 *   lavf 打不开 URL → mpv 折叠成 `MPV_ERROR_LOADING_FAILED(-13)` →
 *   `end_file(reason=MPV_END_FILE_REASON_ERROR=4)`。
 *   **注意**：mpv 0.41 的 `stream_lavf.c` 里 `tls-verify` 默认是 **false**
 *   （`OPT_FLAG("tls-verify", NULL, 0, false)`），所以「默认配置下必因无 CA 而失败」
 *   **不成立**——是本工程 Round-1 把 `tls-verify` 显式设成了 `yes` 才造出这个前提。
 *   这也是 Round-1 误判根因的来龙去脉，保留记录以免再次踩坑。
 * - exo 侧不踩这个坑：它走 `DefaultHttpDataSource` → 平台 TLS，系统 CA + 用户 CA 都自动生效。
 *
 * ## 为什么不直接用系统 CA 目录
 *
 * 1) `/system/etc/security/cacerts` 里是**每个证书一个文件**的哈希命名格式，而 lavf 的
 *    `ca_file` 只接受「单个 PEM 里若干个 `BEGIN CERTIFICATE` 块」的拼装包；
 * 2) Android 14+ 起一部分 CA 迁到 `/apex/com.android.conscrypt/cacerts`，路径不再稳定；
 * 3) 用户自行安装的 CA 在 app 侧读不到。
 * 所以随包带一份 Mozilla 根证书包（certifi 生成，与 Python/requests 同源），
 * 首次启动解到 `filesDir` 后反复复用。
 *
 * 为什么放 `filesDir` 而不是 `cacheDir`：系统可以在任意时刻清空 cacheDir，
 * 而 mpv 的 `tls-ca-file` 指向的路径一旦消失，下一次 https 装载会立刻回到 -13。
 * 这份文件只有 ~290 KB，放 filesDir 不心疼。
 *
 * 线程约定：可在任意线程调用；内部加锁，只有「需要解压」那一次会做 IO。
 */
object MpvCaBundle {

    private const val TAG = "MpvCaBundle"

    /** APK 内的 asset 路径（`app/src/main/assets/mpv/cacert.pem`）。 */
    private const val ASSET_PATH = "mpv/cacert.pem"

    /** filesDir 下的落地目录。与缓存、字幕、LUT 目录都分开。 */
    private const val DIR_NAME = "mpv"

    private const val FILE_NAME = "cacert.pem"

    /** 小于这个体积的一律认为解压失败（真实包 ~290 KB，Mozilla 根证书只会更多）。 */
    private const val MIN_VALID_BYTES = 64L * 1024L

    private val lock = Any()

    /** 缓存解好的路径，避免每次装载都 stat 一遍磁盘。 */
    @Volatile
    private var cachedPath: String? = null

    /**
     * 确保 CA 包已就绪，返回可交给 `--tls-ca-file` 的绝对路径。
     *
     * @return 绝对路径；asset 缺失或解压失败时返回 null（调用方据此**不**下发
     *         `tls-ca-file`，并让 [MpvOptions] 保持 mpv 的默认校验行为）。
     */
    fun ensure(context: Context): String? {
        cachedPath?.let { return it }
        synchronized(lock) {
            cachedPath?.let { return it }

            val dir = File(context.filesDir, DIR_NAME)
            val target = File(dir, FILE_NAME)

            // 幂等：已经解过一次就直接用。体积校验能挡住「写了一半被杀进程」的残文件。
            if (target.isFile && target.length() > MIN_VALID_BYTES) {
                cachedPath = target.absolutePath
                return cachedPath
            }

            return try {
                if (!dir.isDirectory && !dir.mkdirs()) {
                    Log.e(TAG, "创建 $dir 失败，https 将无法校验证书")
                    return null
                }
                // 先写 .tmp 再改名：解压中途被杀进程不会留下一个「看着存在但是截断」的
                // cacert.pem，否则下一次启动会跳过解压并让所有握手失败。
                val tmp = File(dir, "$FILE_NAME.tmp")
                context.assets.open(ASSET_PATH).use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                }
                if (tmp.length() <= MIN_VALID_BYTES) {
                    Log.e(TAG, "asset/$ASSET_PATH 解出 ${tmp.length()} B，体积异常")
                    tmp.delete()
                    return null
                }
                if (target.exists() && !target.delete()) {
                    Log.w(TAG, "旧 $FILE_NAME 删除失败，尝试覆盖")
                }
                if (!tmp.renameTo(target)) {
                    // 少数文件系统上 rename 会失败，退化成拷贝后删源。
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
                Log.i(TAG, "CA 包就绪：${target.absolutePath}（${target.length()} B）")
                cachedPath = target.absolutePath
                target.absolutePath
            } catch (e: IOException) {
                Log.e(TAG, "解压 CA 包失败，https 直链会以 -13 失败", e)
                null
            }
        }
    }

    /** 仅测试用：清掉进程内缓存，强制下次重新校验磁盘。 */
    internal fun resetCacheForTest() {
        cachedPath = null
    }
}
