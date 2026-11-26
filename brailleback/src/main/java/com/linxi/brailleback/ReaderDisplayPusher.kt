/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 阅读器点显推送：取词边界内点位铺屏、清屏、独占/交还服务侧文字输出。
 * 同包扩展函数，状态字段仍留在 BrailleReaderActivity；不引入 DI 框架。
 */

package com.linxi.brailleback

import android.util.Log

    /**
     * 发送当前屏到点显器：取从 [mOffset] 开始的 [mDisplayCells] 个方。
     * 旧实现发送「当前屏幕页的前 N 方」，一页可达数百方，
     * 20 方点显器只能读到开头一小段，其余内容永远读不到。
     */
internal fun BrailleReaderActivity.sendToDisplay() {
        val display = mDisplay ?: return
        if (mStream.isEmpty()) {
            releaseDisplay()
            return
        }
        // 明眼原文（翻译尚未就绪时的兜底流）不是点位，发过去只会是乱码。
        if (mSourceKind == ReaderSourceKind.TEXT && !mIsTranslated) {
            releaseDisplay()
            return
        }
        // 只发到词边界为止：多出来的那半方会把字劈在屏尾，摸到的就是半个字。
        val end = BrailleFileDecoder.nextOffset(mStream, mOffset, mDisplayCells)
        val cells = BrailleFileDecoder.cellsAt(mStream, mOffset, end - mOffset)
        if (cells.isEmpty()) {
            releaseDisplay()
            return
        }
        // 先冻服务再画点位：反过来的话，服务那次文字刷新有可能落在点位之后把它盖掉。
        holdDisplay()
        val len = cells.length
        Log.d(BrailleReaderActivity.LOG_TAG, "send: from=$mOffset to=$end len=$len display=$mDisplayCells")
        val patterns = ByteArray(len)
        val map = IntArray(len)
        for (i in 0 until len) {
            patterns[i] = (BrailleFileDecoder.toDots(cells[i]) and 0xFF).toByte()
            map[i] = i
        }
        display.displayDots(patterns, cells, map)
    }

    /**
     * 按页节流推送：页号（unit）没变就不重复推屏。
     *
     * 用于翻译推进/页表重建这类同页内的重复重铺；显式翻页、跳行、连点显器仍走
     * [sendToDisplay] 直推，保证手上摸到的位置即时跟上操作。
     */
internal fun BrailleReaderActivity.pushDisplayThrottled() {
        if (!mConnected) return
        val unit = currentUnit()
        if (unit == lastPushedUnit) return
        lastPushedUnit = unit
        sendToDisplay()
    }

    /**
     * 把点显器整屏清成空方（不释放独占，随后照常由 [sendToDisplay] 铺本页）。
     *
     * 与 `DisplayManager` 清屏同一做法：全 0 点位 + 空文本。
     * 先冻服务再清，避免服务那边的文字输出紧接着又盖回来。
     */
internal fun BrailleReaderActivity.clearDisplay() {
        val display = mDisplay ?: return
        val cells = mDisplayCells.coerceIn(1, BrailleReaderActivity.MAX_DISPLAY_CELLS)
        holdDisplay()
        display.displayDots(ByteArray(cells), "", null)
        Log.d(BrailleReaderActivity.LOG_TAG, "display cleared: cells=$cells")
    }

    /**
     * 正文在屏期间独占点显器：冻结服务侧文字输出，屏上只剩这一页点位。
     * 服务没起来时返回 false，行为回落到普通模式，不报错。
     */
internal fun BrailleReaderActivity.holdDisplay() {
        if (holdingDisplay) return
        holdingDisplay = BrailleBackService.holdDisplayForApp()
    }

    /** 交还点显器；重复调用无副作用。 */
internal fun BrailleReaderActivity.releaseDisplay() {
        if (!holdingDisplay) return
        holdingDisplay = false
        BrailleBackService.releaseDisplayForApp()
    }
