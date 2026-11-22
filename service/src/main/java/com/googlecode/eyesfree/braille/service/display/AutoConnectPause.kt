/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.braille.service.display

import timber.log.Timber

/**
 * 「用户主动断开 → 暂停自动重连」的判定。
 *
 * <p>用户按下「断开连接」后，[DisplayService.mAutoReconnectSuppressed] 为 true。
 * 此后一切隐式触发——亮屏、客户端重新绑定、蓝牙重新打开、后台重试——都必须
 * 被挡住，否则点显器会在两秒后自己连回来，而读屏用户完全不知道发生了什么。
 *
 * <p>能突破暂停的只有明确的用户动作：在设备页点某一行（已配对或新配对）、
 * 插上 USB 点显器、以及客户端 poll()（用户点了状态行刷新）。这些调用点都传
 * `userInitiated = true`。
 *
 * <p>拆成独立函数是因为 [DisplayServiceConnectionFlow.kt] 有 600 行非空行的
 * 契约预算，判定本身带注释与日志，塞回调用处会顶破预算。
 */
internal fun clearPauseForUserAction(
    service: DisplayService,
    userInitiated: Boolean,
): Boolean {
    if (userInitiated) {
        service.mAutoReconnectSuppressed = false
    }
    if (service.mAutoReconnectSuppressed) {
        Timber.i("Auto-connect paused by user disconnect; waiting for explicit connect")
        return false
    }
    return true
}
