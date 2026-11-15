/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback

import java.util.Locale
import java.util.concurrent.atomic.AtomicLongArray

/**
 * 刷新链路的计数与心跳行格式。
 *
 * 从 `DisplayManager` 里抽出 11 个 `@Volatile` 字段：这些计数由 handler 线程自增、
 * 由独立的上报线程读取，原来的 `statX += 1` 是“单写多读 + volatile 可见性”的混合体，
 * 换成 [AtomicLongArray] 后语义明确，也不再和 1500 行的管理器绑在一起。
 *
 * 上报线程本身**仍留在 `DisplayManager`**：它的存在意义是「handler 线程卡死时仍然
 * 能出日志」，所以不能和刷新链路共用一个调度器。这里只负责计数与格式化。
 */
internal class DisplayRefreshStats {

    private val counters = AtomicLongArray(COUNTER_COUNT)

    /** 收到一帧内容（进入合并窗口就算）。 */
    fun onContent() = add(IDX_CONTENT)

    /** 收到一次重译请求。 */
    fun onRetranslate() = add(IDX_RETRANSLATE)

    /** 合并窗口里被新帧替换掉（旧帧已归还）。 */
    fun onCoalesced() = add(IDX_COALESCED)

    /** 指纹与上次下发相同，整帧丢弃。 */
    fun onIdentical() = add(IDX_BLOCKED_IDENTICAL)

    /** 计算出的点阵为空且屏上已有内容，保持原屏。 */
    fun onEmpty() = add(IDX_BLOCKED_EMPTY)

    /** 被前置条件拦下。 */
    fun onBlocked(reason: RefreshBlock) {
        when (reason) {
            RefreshBlock.NOT_CONNECTED -> add(IDX_BLOCKED_NOT_CONNECTED)
            RefreshBlock.FROZEN -> add(IDX_BLOCKED_FROZEN)
            RefreshBlock.MOTION -> add(IDX_BLOCKED_MOTION)
        }
    }

    /** 真正下发到点显器。 */
    fun onRefreshOk(cells: Int, digest: Int) {
        add(IDX_REFRESH_OK)
        counters.set(IDX_LAST_CELLS, cells.toLong())
        counters.set(IDX_LAST_DIGEST, digest.toLong())
    }

    /** 屏上已显示的格数；空帧抑制要靠它区分「首次无内容」和「切节点时的短暂空帧」。 */
    fun lastCells(): Int = counters.get(IDX_LAST_CELLS).toInt()

    fun snapshot(): Snapshot = Snapshot(
        refreshOk = counters.get(IDX_REFRESH_OK),
        content = counters.get(IDX_CONTENT),
        retranslate = counters.get(IDX_RETRANSLATE),
        blockedNotConnected = counters.get(IDX_BLOCKED_NOT_CONNECTED),
        blockedFrozen = counters.get(IDX_BLOCKED_FROZEN),
        blockedMotion = counters.get(IDX_BLOCKED_MOTION),
        blockedIdentical = counters.get(IDX_BLOCKED_IDENTICAL),
        coalesced = counters.get(IDX_COALESCED),
        blockedEmpty = counters.get(IDX_BLOCKED_EMPTY),
        lastCells = counters.get(IDX_LAST_CELLS),
        lastDigest = counters.get(IDX_LAST_DIGEST),
    )

    /**
     * 心跳行。字段名保持不变（`conn=/content=/retranslate=/ok=/cells=/digest=` 与
     * `blocked(nc=/frozen=/motion=/identical=/empty=)`、`coalesced=`）——
     * 现场排障靠的就是这几项在日志里逐次比对。
     */
    fun describe(
        connected: Boolean,
        frozen: Boolean,
        motionMode: Boolean,
    ): String {
        val s = snapshot()
        return String.format(
            Locale.US,
            "refresh stats: conn=%s content=%d retranslate=%d ok=%d " +
                "cells=%d digest=%08X " +
                "blocked(nc=%d frozen=%d motion=%d identical=%d empty=%d) " +
                "coalesced=%d frozen=%s motion=%s",
            connected,
            s.content,
            s.retranslate,
            s.refreshOk,
            s.lastCells,
            s.lastDigest,
            s.blockedNotConnected,
            s.blockedFrozen,
            s.blockedMotion,
            s.blockedIdentical,
            s.blockedEmpty,
            s.coalesced,
            frozen,
            motionMode,
        )
    }

    data class Snapshot(
        val refreshOk: Long,
        val content: Long,
        val retranslate: Long,
        val blockedNotConnected: Long,
        val blockedFrozen: Long,
        val blockedMotion: Long,
        val blockedIdentical: Long,
        val coalesced: Long,
        val blockedEmpty: Long,
        val lastCells: Long,
        val lastDigest: Long,
    )

    private fun add(index: Int) {
        counters.incrementAndGet(index)
    }

    private companion object {
        const val IDX_REFRESH_OK = 0
        const val IDX_CONTENT = 1
        const val IDX_RETRANSLATE = 2
        const val IDX_BLOCKED_NOT_CONNECTED = 3
        const val IDX_BLOCKED_FROZEN = 4
        const val IDX_BLOCKED_MOTION = 5
        const val IDX_BLOCKED_IDENTICAL = 6
        const val IDX_COALESCED = 7
        const val IDX_BLOCKED_EMPTY = 8
        const val IDX_LAST_CELLS = 9
        const val IDX_LAST_DIGEST = 10
        const val COUNTER_COUNT = 11
    }
}
