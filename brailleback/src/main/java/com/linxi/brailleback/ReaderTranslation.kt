/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 阅读器翻译辅助：增量拼接、挂起位置恢复、表语言不匹配提示与逐段翻译循环。
 * 同包扩展函数；TranslatedChunk 为本文件顶层 internal 数据类。
 */

package com.linxi.brailleback

import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import com.googlecode.eyesfree.braille.translate.BrailleTranslator

    /**
     * 只往末尾追加一块翻译流。
     *
     * 追加不改动原有偏移，故页表按 keepPosition 重建；屏与点显器按当前位置刷新。
     * 翻译推进属于后台追内容：列表位置保持不动，不再 resetScroll 拽回第 1 页。
     */
internal fun BrailleReaderActivity.appendBrailleStream(deltaText: String, deltaCells: IntArray) {
        val before = mStream.length
        val table = mCellSpans as? CellSourceTable
        if (table != null && table.sourceText == mSourceText) {
            // 表对象不换，只往尾部追加位置：屏显按格取字依赖的正是这一个表。
            table.appendAll(deltaCells)
        } else {
            mCellSpans = CellSourceTable(mSourceText, deltaCells)
        }
        mStream += deltaText
        rebuildPageOffsetsIncremental(keepPosition = true)
        showPage(resetScroll = false)
        Log.d(BrailleReaderActivity.LOG_TAG, "append: +${deltaText.length} stream=$before->${mStream.length} " +
            "cells=${mCellSpans?.size} units=${totalUnits()} pos=$mOffset")
    }

    /**
     * 上次读到的位置可能还没翻到：记住目标偏移，等流长够了再一次性回去，
     * 免得刚开书就停在「还没翻完的部分末尾」。
     */
internal fun BrailleReaderActivity.restorePendingOffsetIfReached() {
        val target = mPendingRestoreOffset ?: return
        if (mStream.length <= target) return
        Log.d(BrailleReaderActivity.LOG_TAG, "restore pending: target=$target stream=${mStream.length}")
        mPendingRestoreOffset = null
        mOffset = target.coerceIn(0, maxOffset())
        rebuildPageOffsets(keepPosition = true)
        mOffset = target.coerceIn(0, maxOffset())
        showPage()
        Log.d(BrailleReaderActivity.LOG_TAG, "restore applied: pos=$mOffset unit=${currentUnit()} units=${totalUnits()}")
        announce(getString(R.string.braille_reader_restored, currentUnit() + 1, totalUnits()))
    }

    /**
     * 内容语言与盲文表不匹配检测：中文/日文/韩文内容配非 CJK 表时，
     * liblouis 对表内无定义的字符会输出 \xNNNN/ 转义，屏幕成串乱码且
     * 无任何提示（与 S7 分词降级同款静默降级）。这里给出明确提示；
     * 是否切换盲文表属用户决策，不擅自改设置。
     */
internal fun BrailleReaderActivity.warnIfTableMismatch(text: String, translator: BrailleTranslator) {
        if (!BrailleReaderActivity.CJK_CONTENT.containsMatchIn(text)) return
        val tableLang = translator.tableInfo?.locale?.language
        if (tableLang in BrailleReaderActivity.CJK_TABLE_LANGUAGES) return
        Toast.makeText(this,
            getString(R.string.braille_reader_table_mismatch,
                translator.tableInfo?.locale?.displayLanguage ?: "?"),
            Toast.LENGTH_LONG).show()
        Log.w(BrailleReaderActivity.LOG_TAG, "表不匹配：CJK 内容配 $tableLang 表，翻译可能输出转义乱码")
    }

    /**
     * 逐段翻译：明眼文本按段（\n 分隔）翻译成盲文点位，段间保留换行
     * （阅读流里段落边界仍在，点显器上体现为段间空方）。逐段调用
     * 而非整本一次，避免一次跨进程 AIDL 传输/翻译超大文本。
     */
    /**
     * 逐段翻译，同时按翻译结果的 brailleToTextPositions 生成逐格源区间。
     * 返回盲文阅读流与格级跨度列表；任一整段翻译失败返回 null。
     */
    /**
     * 一块翻译成果（**增量**，只含上次之后新翻的部分）。[done] 为 true 表示整本翻完
     * （或因失败停下），[failedAtParagraph] 为 0 表示没失败。
     * [text] 为空表示「这一块没有新内容，只是状态」。
     */
internal data class TranslatedChunk(
        val text: String,
        val cellPositions: IntArray,
        val done: Boolean,
        val failedAtParagraph: Int,
        val paragraphs: Int,
    )

    /**
     * 逐段翻译，边翻边交块：每满 [BrailleReaderActivity.PUBLISH_INTERVAL_MS] 毫秒（或第一块攒够
     * [BrailleReaderActivity.FIRST_PUBLISH_CHARS] 字）就把当前成果交出去上屏，用户在后台翻译还在跑时
     * 就能开始读；收尾再交最后一块。
     *
     * 段落起点记在全书坐标系里，跨段不串位；逐格源下标进 int 缓冲，
     * 不用 List<CellSource>（378 万格逐格建对象会把 256MB 堆吃穿）。
     * 某段翻译失败时不再整本回退，而是把已经翻好的部分交出去并说明停在哪。
     */
internal fun BrailleReaderActivity.translateParagraphs(
        text: String,
        translator: BrailleTranslator,
        onChunk: (TranslatedChunk) -> Unit,
    ) {
        val startedAt = System.currentTimeMillis()
        val sb = StringBuilder(text.length)
        val positions = IntPositionBuffer(text.length + 16)
        var index = 0
        var paragraphStart = 0
        var invalidCells = 0
        var failedAt = 0
        var publishedChars = 0
        var publishedCells = 0
        var lastPublishAt = SystemClock.uptimeMillis()

        fun publish(done: Boolean) {
            if (sb.length == publishedChars && !done) return
            // 交出去的必须是**增量**：UI 侧只往流末尾追，整段重复交会翻倍铺流。
            val delta = sb.substring(publishedChars)
            val deltaCells = positions.copyFrom(publishedCells)
            publishedChars = sb.length
            publishedCells += deltaCells.size
            lastPublishAt = SystemClock.uptimeMillis()
            onChunk(TranslatedChunk(
                text = delta,
                cellPositions = deltaCells,
                done = done,
                failedAtParagraph = failedAt,
                paragraphs = index,
            ))
        }

        try {
            for (paragraph in text.split('\n')) {
                if (paragraph.isBlank()) {
                    sb.append('\n')
                    paragraphStart += paragraph.length + 1
                    continue
                }
                index++
                val paraStartedAt = System.currentTimeMillis()
                var res = translator.translate(paragraph, -1, false)
                var attempt = 1
                while (res == null && attempt < BrailleReaderActivity.TRANSLATE_ATTEMPTS) {
                    Log.w(BrailleReaderActivity.LOG_TAG, "translate retry #$attempt at paragraph #$index len=${paragraph.length}")
                    SystemClock.sleep(BrailleReaderActivity.TRANSLATE_RETRY_DELAY_MS)
                    res = translator.translate(paragraph, -1, false)
                    attempt++
                }
                if (res == null) {
                    Log.w(BrailleReaderActivity.LOG_TAG, "translate null at paragraph #$index len=${paragraph.length} " +
                        "elapsed=${System.currentTimeMillis() - paraStartedAt}ms")
                    failedAt = index
                    break
                }
                sb.append(BrailleFileDecoder.cellsToUnicodeText(res.cells)).append('\n')
                // 每格回挂到源字符：位置数组比格数短时缺的格按无映射补 -1，宁缺不串。
                val cellCount = res.cells.size
                val toText = res.brailleToTextPositions
                for (cell in 0 until cellCount) {
                    val pos = if (cell < toText.size) toText[cell] else -1
                    val mapped = if (pos in paragraph.indices) paragraphStart + pos else -1
                    if (mapped < 0) invalidCells++
                    positions.add(mapped)
                }
                paragraphStart += paragraph.length + 1
                val sincePublish = SystemClock.uptimeMillis() - lastPublishAt
                // 一次至少交够 BrailleReaderActivity.MIN_PUBLISH_CHARS，别把一段话当一页先推上去：
                // 冷启动等分词器的那几秒会把「2 秒交一块」的时钟提前打满。
                if (sb.length >= BrailleReaderActivity.MIN_PUBLISH_CHARS &&
                    ((publishedChars == 0 && sb.length >= BrailleReaderActivity.FIRST_PUBLISH_CHARS) ||
                        sincePublish >= BrailleReaderActivity.PUBLISH_INTERVAL_MS)) {
                    publish(false)
                }
            }
        } catch (t: Throwable) {
            // 包括 OutOfMemoryError：别让翻译线程把进程带走，已翻好的部分照样交出去。
            Log.w(BrailleReaderActivity.LOG_TAG, "翻译中断: ${t.message}")
            if (failedAt == 0) failedAt = index + 1
        }

        Log.d(BrailleReaderActivity.LOG_TAG, "translate end: paragraphs=$index chars=${sb.length} " +
            "invalidCells=$invalidCells failedAt=$failedAt " +
            "elapsed=${System.currentTimeMillis() - startedAt}ms")
        publish(true)
    }
