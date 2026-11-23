/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback

/**
 * 为什么需要它：
 *
 * 旧的默认表选择只按目录排序挑出一张候选表就交给服务加载；这张表在本机
 * liblouis 上可能本身就加载失败（缺文件、表内语法错误等），一旦失败整个
 * 翻译器就造不出来，其它同语言候选和英文兜底表都不再尝试，界面于是永远
 * 停在「自动（无）」/空清单。
 *
 * 这里把「按优先级逐个探测、取第一张能加载的」抽成不依赖 Android 的纯逻辑：
 * 输入有序候选表 + 一个加载探测函数，输出胜出候选、失败候选名单与实际探测
 * 次数。探测顺序、探测上界与异常兜底都能在纯 JVM 单测里钉住，不碰真机、
 * 服务进程与翻译输出本身。
 */
internal object TableCandidatePicker {

    /**
     * 单次选择最多探测多少张候选表。
     *
     * 探测是跨进程（Binder）调用，每张表都要让服务侧走一遍 liblouis 加载，
     * 不能把全量目录（上百张）挨个试一遍拖住界面。候选顺序已经把「用户显式
     * 选择 → 当前语言按匹配度/grade 排序 → 同语言其余表 → 英文兜底」排在最前，
     * 一个语言的可用候选通常只有少数几张；取 5 足以覆盖同语言候选加兜底，
     * 又不至于遍历整个目录。
     */
    const val MAX_PROBES = 5

    /**
     * 选择结果。
     *
     * @property winner 第一张探测通过的候选；全部失败时为 null
     * @property failed 按探测顺序记录的失败候选（不含胜出项）
     * @property probedCount 实际调用探测函数的次数
     */
    data class Picked<T>(
        val winner: T?,
        val failed: List<T>,
        val probedCount: Int
    )

    /**
     * 按 [candidates] 的顺序逐个探测：
     * - 第一个探测通过（[probe] 返回 true）的候选即胜出，后面的不再探测；
     * - [probe] 抛异常按该候选失败处理，继续下一张；
     * - 最多探测 [maxProbes] 张，超出部分不再调用 [probe]。
     */
    fun <T> pickFirstLoadable(
        candidates: List<T>,
        maxProbes: Int = MAX_PROBES,
        probe: (T) -> Boolean
    ): Picked<T> {
        val failed = ArrayList<T>()
        val limit = if (maxProbes >= 0) maxProbes else 0
        var probed = 0
        for (candidate in candidates) {
            if (probed >= limit) {
                break
            }
            probed++
            val ok = try {
                probe(candidate)
            } catch (e: Throwable) {
                false
            }
            if (ok) {
                return Picked(candidate, failed, probed)
            }
            failed.add(candidate)
        }
        return Picked(null, failed, probed)
    }
}
