/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback

/**
 * 跨线程共享的运行时状态：连接、模拟显示器、暂停、运动模式、光标叠加。
 *
 * 原来是五个独立的 `@Volatile` 布尔值。`refresh()` 要连着读三个
 * （`connected` / `frozen` / `motionMode`）才能决定这一帧该不该发，三个字段
 * 各自 volatile 只保证单个读写的可见性，不保证组合一致：回调线程刚把
 * `connected` 置 true、display 线程读到 `frozen` 还是旧值时，就会做出
 * 「已连接但暂停」这种当次不成立的判断。
 *
 * 收成一个不可变快照后，`refreshDecision()` 从**同一份**状态里取值，
 * 且写入用 `MutableStateFlow.update`（CAS），并发写不会互相覆盖。
 */
internal data class DisplayRuntimeState(
    val connected: Boolean = false,
    val simulated: Boolean = false,
    val frozen: Boolean = false,
    val motionMode: Boolean = false,
    val cursorDots: Boolean = false,
) {
    /** 刷新裁决：三个条件必然取自同一次读取。 */
    fun refreshDecision(): RefreshDecision = decideRefresh(connected, frozen, motionMode)
}
