package com.example.localmovielibrarz.mpv

import android.content.Context
import android.graphics.PixelFormat
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
        holder.setFormat(PixelFormat.RGBA_8888)
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
        if (holder.surface?.isValid == true && (pendingAttach || !surfaceAttached)) {
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
            // 失败不能吞：mpv 实例还没创建时原生侧会返回 -900（g_mpv 为空），
            // 这时把待挂状态留下，下一轮 attach()（每次重组都会调用）继续补挂。
            // 丢掉这个标志会让「Surface 比 mpv 先到」的场景永久黑屏。
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
