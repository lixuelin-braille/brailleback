/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback

/**
 * 刷新闸门：管「这一帧要不要发」和「上一帧什么时候发的」。
 *
 * 三件事本来就是一组——上次送达的指纹、当前待发的指纹、上次送达的时刻。
 * 分开成三个 `@Volatile` 时，`setContent` 读到的送达时刻和去重判定用的指纹
 * 可能来自两个不同时刻；这里用一把锁把它们绑成一次操作。
 *
 * 关键不变量（改实现时别破坏）：
 * - 指纹只在**真的送达点显器之后**才转正（[onDelivered]），连接未建立或冻结期间
 *   不记录，否则恢复后第一帧会被自己当作重复帧丢掉，屏上停在旧内容。
 * - 待发指纹可以在送达前被覆盖，覆盖方负责 recycle 被替换的那帧。
 */
internal class RefreshGate {

    private val lock = Any()
    private var lastDeliveredFingerprint: String? = null
    private var pendingFingerprint: String? = null
    private var lastDeliveredUptime = 0L

    /** 记下这一帧的指纹，等 [onDelivered] 时转正。 */
    fun markPending(fingerprint: String) {
        synchronized(lock) {
            pendingFingerprint = fingerprint
        }
    }

    /** 与上一次真正送达的内容是否完全一致（文本/选区/焦点/平移策略都没变）。 */
    fun isDuplicateOfLast(fingerprint: String): Boolean = synchronized(lock) {
        fingerprint == lastDeliveredFingerprint
    }

    /** 上次真正送达点显器的时刻（[android.os.SystemClock.uptimeMillis]），未送达过则为 0。 */
    fun lastDeliveredAt(): Long = synchronized(lock) {
        lastDeliveredUptime
    }

    /**
     * 一帧真的送达了：记录时刻，并把待发指纹转为「已送达」。
     *
     * 时刻由调用方传入而不是在这里读时钟——测试要能钉住限速行为。
     */
    fun onDelivered(nowUptime: Long) {
        synchronized(lock) {
            lastDeliveredUptime = nowUptime
            lastDeliveredFingerprint = pendingFingerprint
        }
    }
}
