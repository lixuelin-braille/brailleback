/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback

import com.googlecode.eyesfree.braille.display.Display
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 点位回显：把「正在按的点位」立刻画到点显器第一格，供盲文输入时先摸到自己的手。
 *
 * 这是一次短暂的叠加显示：画完 [RESTORE_DELAY_MILLIS] 内没有新的按键就恢复原来的
 * 屏幕内容。不做常驻覆盖，是为了不和普通刷新抢屏——连按几格时每一格都会重新计时，
 * 松开手之后自动回到正文。
 *
 * 从 [DisplayManager] 里拆出来有两个原因：管理器已经顶到文件体积预算，而回显是
 * 「按键 → 画一格 → 定时还原」的独立小闭环，与内容模型、刷新决策无关。计时复用
 * [Debouncer]（同一条 display 线程调度器上的 `Job + delay`），不引入 `postDelayed`。
 *
 * 所有工作都在传入的 [scope] 上串行执行：生产环境是 display HandlerThread 的 looper，
 * 测试里是 `TestScope`，因此 `displayDots` 与 `restore` 都会落到同一条线程上。
 */
internal class DisplayPressEcho(
    private val scope: CoroutineScope,
    private val displayOf: () -> Display?,
    private val canEcho: () -> Boolean,
    private val restore: () -> Unit,
) {

    /** 恢复计时用去抖：每次按键重置，安静满 [RESTORE_DELAY_MILLIS] 才执行一次还原。 */
    private val restoreTimer = Debouncer(scope)

    fun show(dots: Int) {
        scope.launch {
            echo(dots)
        }
    }

    fun cancel() {
        restoreTimer.cancel()
    }

    private fun echo(dots: Int) {
        val currentDisplay = displayOf() ?: return
        if (!canEcho()) {
            return
        }
        val cells = currentDisplay.displayProperties?.numTextCells ?: 0
        if (cells <= 0) {
            return
        }
        val pattern = ByteArray(cells)
        if (dots != 0) {
            // brltty 的点位掩码与点阵字节位序一致：bit0 = 1 点 … bit5 = 6 点。
            pattern[0] = dots.toByte()
        }
        currentDisplay.displayDots(pattern, "", null)
        restoreTimer.schedule(RESTORE_DELAY_MILLIS) {
            restore()
        }
    }

    companion object {
        /** 点位回显停留时长：超过这段时间没有下一次按键就把正文画回来。 */
        const val RESTORE_DELAY_MILLIS: Long = 1500
    }
}
