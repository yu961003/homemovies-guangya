package com.example.localmovielibrarz.subtitle

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * 本地字幕库周期重扫的调度器。
 *
 * 为什么需要它：字幕库目录是用户提前选好的 SAF 目录，索引只在「设置页手动点重扫」时更新，
 * 于是用户在 App 之外往目录里拷了新字幕，App 里按番号搜不到（缺陷 B-2）。
 * 这里登记一个 WorkManager 周期任务，让索引自己跟上目录变化，不再依赖用户记得去点按钮。
 *
 * 为什么周期是 12 小时而不是更短：字幕库是低频变化的资料库（用户不会每分钟往里面拷电影），
 * 而一次扫描要遍历整个 SAF 目录树，几千个文件是几秒到几十秒的 binder + 磁盘 IO。
 * 12 小时足以覆盖「晚上下载完、第二天看」的典型节奏，又把对电量与 IO 的影响压到可忽略。
 * 首跑额外延迟 10 分钟：避开用户刚点完手动重扫、或刚冷启动还在做首屏的时段。
 *
 * 约束只加 [Constraints.Builder.setRequiresBatteryNotLow]：字幕库在 SD 卡 / 网盘挂载目录上时，
 * 低电量下遍历目录会明显拖慢设备；任务本身不联网、不需要充电，所以不设 requiresCharging。
 */
object SubtitleLibraryRescanScheduler {
    private const val TAG = "SubtitleRescanScheduler"

    /** 唯一任务名：全局只允许一个周期重扫，重复登记时按 [ExistingPeriodicWorkPolicy.KEEP] 忽略。 */
    const val UNIQUE_WORK_NAME = "local_subtitle_library_periodic_rescan"

    private const val REPEAT_INTERVAL_HOURS = 12L
    private const val INITIAL_DELAY_MINUTES = 10L

    /**
     * 登记周期重扫。可以重复调用（[LocalMovieLibraryApp] 每次进程启动都会调一次）。
     *
     * 为什么用 [ExistingPeriodicWorkPolicy.KEEP] 而不是 UPDATE / REPLACE：
     * 1. `schedule()` 挂在 Application.onCreate 上，每次冷启动都会执行一次；UPDATE 会替换
     *    已登记的 WorkSpec，虽然 2.9.1 会保留原始 enqueue 时间，但「每个进程都重新写一次调度」
     *    没有任何收益，反而把「一个周期任务只登记一次」的简单语义复杂化。
     * 2. REPLACE 在 2.9.1 已标记 deprecated（官方建议改用 UPDATE），且它会取消正在运行的
     *    同名任务——如果某个进程恰好在重扫中途被拉起，REPLACE 会把正在跑的扫描硬砍掉。
     * 3. 周期参数（12 小时 / 约束）写在代码里，升级 App 也不需要改已有登记；
     *    真要改参数时，下一次冷启动前手动 cancel 一次即可，或把此处换成 UPDATE。
     */
    fun schedule(context: Context) {
        val appContext = context.applicationContext
        val request = PeriodicWorkRequestBuilder<SubtitleLibraryRescanWorker>(
            REPEAT_INTERVAL_HOURS,
            TimeUnit.HOURS
        )
            // 首跑推迟 10 分钟：见类注释，避开冷启动首屏与刚完成的手动重扫。
            .setInitialDelay(INITIAL_DELAY_MINUTES, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .addTag(TAG)
            .build()

        WorkManager.getInstance(appContext)
            .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        Log.i(
            TAG,
            "已登记本地字幕库周期重扫：每 $REPEAT_INTERVAL_HOURS 小时一次" +
                "（首次约 $INITIAL_DELAY_MINUTES 分钟后）"
        )
    }

    /** 取消周期重扫；目前没有 UI 入口调用它，留给「关闭自动重扫」这类后续开关。 */
    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_WORK_NAME)
    }
}
