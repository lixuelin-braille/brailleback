/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 参数档位变更：方数 / 行数 / 点数 / 线宽归一 / G1 封面分层，DEBUG 注入档位；参数变更
 * 后在已选图前提下触发重转。纯重构拆分自 TactileStripActivity.kt，方法体逐字未改；
 * 以同包 internal 扩展函数承载，状态仍由 Activity 持有。
 */

package com.linxi.brailleback.tactile

import android.util.Log
import com.linxi.brailleback.BuildConfig

/** DEBUG 专用：兼容 `--es` 字符串与 `--ei` 整型两种注入方式，归一为文本交解析器。 */
internal fun TactileStripActivity.extraText(key: String): String? =
    intent.getStringExtra(key)
        ?: intent.getIntExtra(key, -1).takeIf { it >= 0 }?.toString()

/** DEBUG 专用：把解析出的合法档位写进既有参数状态模型；未选图时不触发 rebuild。 */
internal fun TactileStripActivity.applyDebugParams(params: TactileDebugParams) {
    params.cellsPerLine?.let {
        // debug 注入的档位等同于手动指定，关掉跟随，避免被设备值覆盖。
        cellsFollowDisplay = false
        cellsPerLine = it
    }
    params.linesPerScreen?.let { linesPerScreen = it }
    params.dots?.let { dotsPerCell = it }
    params.normalizer?.let { normalizerMode = it }
    params.g1SplitCover?.let { g1SplitCover = it }
    if (BuildConfig.DEBUG) {
        Log.d(
            DEBUG_TAG,
            "debug params applied: cells=$cellsPerLine lines=$linesPerScreen " +
                "dots=$dotsPerCell normalizer=$normalizerMode g1=$g1SplitCover",
        )
    }
}

internal fun TactileStripActivity.onCellsChange(value: Int) {
    // 手动点档位即退出「跟随点显器」，否则下次连接又被设备值覆盖掉。
    cellsFollowDisplay = false
    if (cellsPerLine == value) return
    cellsPerLine = value
    // 图片已选的前提下重转并回到封面；未选图只记参数，等选图后生效。
    if (sourceUri != null) rebuild()
}

/** 高级里的「跟随点显器」：切回自动，并按当前设备值立即重算一次。 */
internal fun TactileStripActivity.onCellsFollow() {
    if (cellsFollowDisplay) return
    cellsFollowDisplay = true
    applyDisplayCells()?.let { if (sourceUri != null) rebuild() }
}

/**
 * 按点显器上报的文本格数更新方数；返回是否真的改了值（未连接 / 值相同返回 false）。
 * 行数不在这里跟随：客户端 API（[com.googlecode.eyesfree.braille.display.BrailleDisplayProperties]）
 * 只暴露文本格数，行数没有任何设备上报到这一层，所以行数仍由「减 1 / 加 1」决定。
 */
internal fun TactileStripActivity.applyDisplayCells(): Boolean? {
    if (!cellsFollowDisplay) return null
    val cells = observeClient?.displayProperties?.numTextCells ?: 0
    if (cells <= 0 || cells == cellsPerLine) return null
    cellsPerLine = cells
    return true
}

internal fun TactileStripActivity.onLinesChange(delta: Int) {
    val next = (linesPerScreen + delta).coerceIn(MIN_LINES, MAX_LINES)
    if (next == linesPerScreen) return
    linesPerScreen = next
    if (sourceUri != null) rebuild()
}

internal fun TactileStripActivity.onDotsChange(value: Int) {
    if (dotsPerCell == value) return
    dotsPerCell = value
    if (sourceUri != null) rebuild()
}

internal fun TactileStripActivity.onNormalizerChange(mode: StockNormalizerMode) {
    if (normalizerMode == mode) return
    normalizerMode = mode
    if (sourceUri != null) rebuild()
}

internal fun TactileStripActivity.onG1Change(enabled: Boolean) {
    if (g1SplitCover == enabled) return
    g1SplitCover = enabled
    if (sourceUri != null) rebuild()
}
