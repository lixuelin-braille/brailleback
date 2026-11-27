/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback

/**
 * 焦点稳定闸门：管「这次焦点变化要不要立刻上屏」和「要不要补一帧」。
 *
 * 为什么需要它：系统在自绘控件、列表滚动、输入法弹窗这些场景里会让无障碍焦点
 * 在两个节点之间反复切换，每个切换都是一次 `TYPE_VIEW_ACCESSIBILITY_FOCUSED`。
 * 内容指纹每帧都不同，去重拦不住，于是点显器按最小间隔一路跟着跳。
 *
 * 策略是「首帧不延迟、密集处合并」：
 * - 空闲后的第一个事件立刻放行，正常浏览的单步移动感受不变；
 * - 之后 [settleMillis] 窗口内的事件只记一次待补，窗口到期补刷最后一帧；
 * - 窗口起点不随新事件顺延，保证事件洪流下最多等一个窗口，不会永远轮不到。
 *
 * 纯逻辑、不碰时钟：时间由调用方传入，单测可以直接钉住行为。
 */
internal class FocusSettleGate(private val settleMillis: Long) {

    /** 窗口起点；[Long.MIN_VALUE] 表示当前没有进行中的窗口。 */
    private var windowStart = NO_WINDOW

    /**
     * 收到一次焦点事件。
     *
     * @param nowUptime 当前时刻（`SystemClock.uptimeMillis`）。
     * @return 0 表示调用方应当立刻上屏；大于 0 表示该帧已被合并，
     *         调用方无需排队，到期由 [expired] 判定后补刷。
     */
    fun onEvent(nowUptime: Long): Long {
        if (windowStart == NO_WINDOW) {
            windowStart = nowUptime
            return 0
        }
        val elapsed = nowUptime - windowStart
        if (elapsed < settleMillis) {
            return settleMillis - elapsed
        }
        // 窗口已过期：上一帧该补而未补（例如主线程被占住），这次直接放行并开新窗口。
        windowStart = nowUptime
        return 0
    }

    /** 待补帧是否到期。到期的调用方补刷一帧，然后调 [onFlushed]。 */
    fun expired(nowUptime: Long): Boolean =
        windowStart != NO_WINDOW && nowUptime - windowStart >= settleMillis

    /** 补帧已上屏，关掉窗口，下一个事件重新享受「立刻放行」。 */
    fun onFlushed() {
        windowStart = NO_WINDOW
    }

    /** 退出当前模式时清干净，避免下个模式继承半开窗口。 */
    fun reset() {
        windowStart = NO_WINDOW
    }

    private companion object {
        const val NO_WINDOW = Long.MIN_VALUE
    }
}
