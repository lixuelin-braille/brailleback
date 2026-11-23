/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback

/**
 * 一次刷新请求的裁决结果，纯数据，没有任何 Android 依赖。
 *
 * 从 `DisplayManager.refresh()` 里抽出来，是因为原来的三条 early return
 * （未连接 / 冻结 / 运动模式）只能在真机上验：既没法断言“什么时候该刷、
 * 什么时候该忍住”，也没法回归“冻结期间不能记录指纹”这类行为。
 */
internal enum class RefreshBlock {
    /** 点显器未连接：不刷，也不能记录指纹。 */
    NOT_CONNECTED,

    /** 输出被暂停：内容留在屏上，不刷。 */
    FROZEN,

    /** 运动模式：先压住，等滚动停下来再刷一次（调用方负责安排去抖）。 */
    MOTION,
}

internal sealed interface RefreshDecision {
    /** 允许下发。 */
    object Send : RefreshDecision

    /** 忍住不发，并说明原因（用于计数与日志）。 */
    data class Blocked(val reason: RefreshBlock) : RefreshDecision
}

/**
 * 刷新前置条件判定，对应 `DisplayManager.refresh()` 原来的三条 early return。
 *
 * `MOTION` 与其他两种拦截的区别：它是「延后」而不是「丢弃」，
 * 调用方收到 MOTION 后必须重新安排去抖刷新。
 */
internal fun decideRefresh(
    connected: Boolean,
    frozen: Boolean,
    motionMode: Boolean,
): RefreshDecision = when {
    !connected -> RefreshDecision.Blocked(RefreshBlock.NOT_CONNECTED)
    frozen -> RefreshDecision.Blocked(RefreshBlock.FROZEN)
    motionMode -> RefreshDecision.Blocked(RefreshBlock.MOTION)
    else -> RefreshDecision.Send
}
