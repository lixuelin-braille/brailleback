package com.googlecode.eyesfree.braille.service.display

/**
 * 链路停滞判定（纯逻辑，无 android 依赖）。
 *
 * 为什么需要它：USB / 蓝牙写入一旦「回调不来」，上层看到的现象是刷新停住，而连接状态
 * 还是「已连接」，于是用户只能拔插或重启。判据必须统一，所以把「什么时候该认定这条链路
 * 已经死了、必须断链重连」抽成一个可单测的小类。
 *
 * 两条触发条件，命中任一即升级：
 * 1. 连续失败次数达到上限——写入明确报错，一次不说明问题，连着几次就说明通道废了；
 * 2. 还有待写内容，但距上一次成功写入已超过 [stallMillis]——回调丢了 / 写调用卡住。
 *
 * 注意：[hasPendingWork] 为 false 时不做超时判定，否则空闲的屏（没有新内容要发）会被误判成死链。
 */
class LinkStallPolicy(
    private val stallMillis: Long = DEFAULT_STALL_MILLIS,
    private val maxConsecutiveFailures: Int = DEFAULT_MAX_CONSECUTIVE_FAILURES,
) {

    fun shouldEscalate(
        nowUptimeMillis: Long,
        lastSuccessUptimeMillis: Long,
        hasPendingWork: Boolean,
        consecutiveFailures: Int,
    ): Boolean {
        if (consecutiveFailures >= maxConsecutiveFailures) return true
        if (!hasPendingWork) return false
        return nowUptimeMillis - lastSuccessUptimeMillis >= stallMillis
    }

    companion object {
        /** 10 秒没有一次成功写入，且还有内容等着发：够判定卡死，又不会误伤慢设备。 */
        const val DEFAULT_STALL_MILLIS: Long = 10_000L

        /** 连续 3 次写入失败即判定通道不可用。 */
        const val DEFAULT_MAX_CONSECUTIVE_FAILURES: Int = 3
    }
}
