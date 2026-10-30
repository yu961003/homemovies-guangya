package com.example.localmovielibrarz.mpv

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout

/**
 * mpv 渲染面。
 *
 * 用 SurfaceView 而不是 TextureView：mpv 走 mediacodec 直出时，需要一个独立的
 * Surface（`wid`），TextureView 的 Surface 会在重组时被重建，导致每次重组都黑屏一帧。
 *
 * 与 [MPVLib] 的契约：
 * - surface 挂载/解绑完全由 [SurfaceHolder.Callback] 驱动，不依赖外部时序；
 * - 未初始化（[MPVLib.create] 未调用）时收到的挂载请求会被记住，等 [attach] 时补挂。
 */
class MpvVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val surfaceView = SurfaceView(context).apply {
        // 这里**不能**固定 setFormat(RGBA_8888)：mpv 的 Android VO 自己会调
        // ANativeWindow_setBuffersGeometry（context_android.c），选的是能零拷贝的
        // 私有格式；上层一旦先把格式钉成 RGBA_8888，mediacodec 直出就要多走一次
        // 格式转换/回拷，表现为「hw+ 反而不如 sw 流畅」。
        // 保持 SurfaceHolder 的默认格式，让 mpv 自己协商。
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    }

    private var surfaceAttached = false
    private var engineReady = false
    private var pendingAttach = false
    private var lastWidth = 0
    private var lastHeight = 0

    init {
        addView(surfaceView)
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                if (engineReady) {
                    attachSurface(holder)
                } else {
                    pendingAttach = true
                }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                lastWidth = width
                lastHeight = height
                if (surfaceAttached) {
                    MPVLib.setSurfaceSizeSafe(width, height)
                }
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                if (surfaceAttached) {
                    MPVLib.detachSurfaceSafe()
                    surfaceAttached = false
                }
                pendingAttach = false
            }
        })
    }

    /**
     * 引擎（mpv_initialize）就绪后调用。必须在主线程调用。
     * 若 surface 已经创建好，这里会立即补挂。
     */
    fun attach() {
        engineReady = true
        val holder = surfaceView.holder
        // `!MPVLib.surfacePresent` 这一条是给「句柄被重建」准备的：本 View 的
        // surfaceAttached 是「我给过 surface」的记录，而 wid 是按 mpv 句柄生效的——
        // 换内核/重进播放页之后新句柄的 wid 是 0，只靠 surfaceAttached 判断会一直不补挂，
        // 于是每次重组都跳过、画面永远起不来。
        if (holder.surface?.isValid == true &&
            (pendingAttach || !surfaceAttached || !MPVLib.surfacePresent)
        ) {
            attachSurface(holder)
        }
    }

    /** 解绑 mpv 的渲染目标（引擎销毁前必须调用，否则 libmpv 会持有已释放的 Surface）。 */
    fun detach() {
        engineReady = false
        if (surfaceAttached) {
            MPVLib.detachSurfaceSafe()
            surfaceAttached = false
        }
    }

    /** 渲染尺寸由调用方决定（例如用户切换画面比例模式时做黑边裁剪）。 */
    fun setRenderSize(width: Int, height: Int) {
        lastWidth = width
        lastHeight = height
        if (surfaceAttached) {
            MPVLib.setSurfaceSizeSafe(width, height)
        }
    }

    private fun attachSurface(holder: SurfaceHolder) {
        val surface = holder.surface ?: return
        if (!surface.isValid) return
        val ret = MPVLib.attachSurfaceSafe(surface)
        if (ret == 0) {
            surfaceAttached = true
            pendingAttach = false
            if (lastWidth > 0 && lastHeight > 0) {
                MPVLib.setSurfaceSizeSafe(lastWidth, lastHeight)
            }
        } else {
            // 失败不能吞：把待挂状态留下，下一轮 attach()（每次重组都会调用）继续补挂。
            // 丢掉这个标志会让「Surface 比 mpv 先到」的场景永久黑屏。
            // 注意：mpv 实例还没创建**不**算失败（原生会缓存 surface 并返回 0），
            // 真正会走到这里的是 wid 写入被 mpv 拒绝、surface 已失效等情况。
            pendingAttach = true
            Log.w(TAG, "attachSurface 失败: $ret（保持待挂状态，下次 attach 重试）")
        }
    }

    override fun onDetachedFromWindow() {
        detach()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val TAG = "MpvVideoView"
    }
}
