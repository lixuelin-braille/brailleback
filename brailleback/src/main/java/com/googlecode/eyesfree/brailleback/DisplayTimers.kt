/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 三个刷新链路定时器的可测实现。
 *
 * 原来它们是 `Handler.postDelayed` + 自续 `Runnable`：空闲清屏每 3 分钟重排一次、
 * 运动模式 350 ms 去抖、光标闪烁 600/800 ms 交替。这类“等一下再执行、并且可能
 * 自己再排下一次”的行为，用协程写出来是 `delay` + `Job`，取消即 `cancel()`，
 * 而且可以在 `runTest` 的虚拟时间里断言发生了多少次，不用真等三分钟。
 *
 * 三者都不自己持有线程：调度器由调用方传入（生产环境是 display HandlerThread 的
 * looper，测试里是 `TestScope`）。
 */

/** 去抖：每次 [schedule] 都重置计时，只有安静满 [delayMs] 才执行一次。 */
internal class Debouncer(private val scope: CoroutineScope) {
    private var job: Job? = null

    fun schedule(delayMs: Long, action: () -> Unit) {
        job?.cancel()
        job = scope.launch {
            delay(delayMs)
            action()
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    val isPending: Boolean get() = job?.isActive == true
}

/** 周期：安静期结束后执行，然后按同一间隔一直执行；[restart] 会丢掉已计的时间。 */
internal class RepeatingTimer(private val scope: CoroutineScope) {
    private var job: Job? = null

    fun restart(intervalMs: Long, action: () -> Unit) {
        cancel()
        if (intervalMs <= 0) {
            return
        }
        job = scope.launch {
            while (isActive) {
                delay(intervalMs)
                action()
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }
}

/**
 * 单次：到点执行一次；如果动作内部又调用了 [schedule]，就再排下一轮。
 *
 * 光标闪烁靠的就是这条链：闪一次 → 重画 → 重画时的 `doRefresh` 再决定要不要排下一次。
 * 所以执行前必须先把 [job] 置空，否则动作里的 [schedule] 会以为自己还在排队而跳过。
 */
internal class OneShotTimer(private val scope: CoroutineScope) {
    private var job: Job? = null

    fun schedule(delayMs: Long, action: () -> Unit) {
        if (isScheduled) {
            return
        }
        job = scope.launch {
            delay(delayMs)
            job = null
            action()
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    val isScheduled: Boolean get() = job?.isActive == true
}
