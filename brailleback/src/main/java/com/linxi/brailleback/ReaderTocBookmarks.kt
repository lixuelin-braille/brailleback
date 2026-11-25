/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 阅读器目录 / 书签 / 跳页 / 跳行 / 字号弹窗，以及书签按文件 URI 的持久化。
 * 同包扩展函数，状态字段仍留在 BrailleReaderActivity；不引入 DI 框架。
 */

package com.linxi.brailleback

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.googlecode.eyesfree.utils.SettingsStore
import com.linxi.brailleback.compose.ReaderDialog
import com.linxi.brailleback.reader.ReaderChapters
import kotlin.math.min

    /**
     * 目录：优先按书自己的章节标题（「第X章」「序言」「尾声」…）列，识别不到才按页分组。
     *
     * 早先一律按 10 页一组：一本 300 万字的书页数上万，目录变成上万条「第 1-10 页」，
     * 听下来全是页码，没有任何能用来找内容的线索。章节标题是书自己给的结构，
     * 出发点是「第几章 什么题目」，比页码有用得多。
     */
internal fun BrailleReaderActivity.showToc() {
        if (mStream.isEmpty()) {
            toastNoContent()
            return
        }
        val table = mCellSpans as? CellSourceTable
        if (mSourceKind == ReaderSourceKind.TEXT && table != null) {
            val chapters = ReaderChapters.detect(mSourceText)
            if (chapters.size >= 2) {
                val targets = mapChaptersToStream(chapters, table)
                if (targets.size == chapters.size) {
                    readerDialog = ReaderDialog.Toc(
                        getString(R.string.braille_reader_toc_title),
                        chapters.map { it.title }, targets)
                    Log.d(BrailleReaderActivity.LOG_TAG, "toc: chapters=${chapters.size} entries")
                    return
                }
            }
        }
        showPageGroupedToc(table)
    }

    /**
     * 逐章起点映射到盲文流偏移：走一遍流，格序号对应的源字符下标一越过章节起点
     * 就记下当前流位置。整本只扫一次，不按章重复扫。
     */
internal fun BrailleReaderActivity.mapChaptersToStream(
        chapters: List<ReaderChapters.Chapter>,
        table: CellSourceTable,
    ): List<Int> {
        val targets = ArrayList<Int>(chapters.size)
        var chapter = 0
        var cells = 0
        for (i in mStream.indices) {
            val c = mStream[i]
            if (c.code !in 0x2800..0x28FF) continue
            val charPos = if (cells < table.size) table.charPosAt(cells) else Int.MAX_VALUE
            while (chapter < chapters.size && charPos >= chapters[chapter].startChar) {
                targets.add(i)
                chapter++
            }
            cells++
            if (chapter >= chapters.size) break
        }
        // 剩下没找到的（章节在流的末尾之外，例如还没翻到）落在文末。
        while (targets.size < chapters.size) targets.add(maxOffset())
        return targets
    }

    /** 兜底目录：按页分组，条目数控制在 [BrailleReaderActivity.TOC_MAX_ENTRIES] 以内，尽量带上首行预览。 */
internal fun BrailleReaderActivity.showPageGroupedToc(table: CellSourceTable?) {
        val total = totalUnits()
        val step = maxOf(BrailleReaderActivity.TOC_PAGE_STEP, (total + BrailleReaderActivity.TOC_MAX_ENTRIES - 1) / BrailleReaderActivity.TOC_MAX_ENTRIES)
        val labels = ArrayList<String>()
        val targets = ArrayList<Int>()
        var u = 0
        while (u < total) {
            val last = min(u + step, total) - 1
            val preview = pagePreview(u, table)
            labels.add(if (preview.isEmpty()) {
                getString(R.string.braille_reader_toc_item, u + 1, last + 1)
            } else {
                getString(R.string.braille_reader_toc_item_preview,
                    u + 1, last + 1, preview)
            })
            targets.add(u)
            u = last + 1
        }
        readerDialog = ReaderDialog.Toc(
            getString(R.string.braille_reader_toc_title), labels, targets)
    }

    /**
     * 某组页的首行预览（原文）。只有翻译流（有逐格源表）才给得出汉字；
     * 纯盲文文件没有原文，预览留空，让标签只显示页范围，免得念一串点位。
     */
internal fun BrailleReaderActivity.pagePreview(unit: Int, table: CellSourceTable?): String {
        if (table == null || mSourceKind != ReaderSourceKind.TEXT) return ""
        val startOffset = mPageOffsets.getOrElse(unit) { 0 }
        var cells = 0
        for (i in 0 until startOffset.coerceAtMost(mStream.length)) {
            if (mStream[i].code in 0x2800..0x28FF) cells++
        }
        if (cells >= table.size) return ""
        val charPos = table.charPosAt(cells)
        if (charPos !in mSourceText.indices) return ""
        val sb = StringBuilder()
        var i = charPos
        // 取到换行或够长为止，跳过缩进空格
        while (i < mSourceText.length && sb.length < BrailleReaderActivity.PREVIEW_CHARS) {
            val c = mSourceText[i]
            if (c == '\n' || c == '\r') break
            if (c != '\u3000' && c != ' ') sb.append(c)
            i++
        }
        return sb.toString()
    }

internal fun BrailleReaderActivity.onTocPicked(which: Int) {
        val dialog = readerDialog as? ReaderDialog.Toc ?: return
        readerDialog = null
        if (which in dialog.targets.indices) gotoUnit(dialog.targets[which])
    }

    /** 书签：尚无书签时直接把当前页加为书签，否则弹出列表供跳转/删除。 */
internal fun BrailleReaderActivity.showBookmarks() {
        if (mStream.isEmpty()) {
            toastNoContent()
            return
        }
        val offsets = loadBookmarkOffsets()
        if (offsets.isEmpty()) {
            addBookmark()
            return
        }
        val labels = offsets.map { offsetLabel(it) }
        readerDialog = ReaderDialog.Bookmarks(
            getString(R.string.braille_reader_bookmarks_title), labels, offsets)
    }

internal fun BrailleReaderActivity.onBookmarkPicked(which: Int) {
        val dialog = readerDialog as? ReaderDialog.Bookmarks ?: return
        readerDialog = null
        if (which in dialog.offsets.indices) jumpToOffset(dialog.offsets[which])
    }

internal fun BrailleReaderActivity.onBookmarkDeleteRequested() {
        val dialog = readerDialog as? ReaderDialog.Bookmarks ?: return
        showDeleteBookmark(dialog.offsets)
    }

internal fun BrailleReaderActivity.onBookmarkClearRequested() {
        clearBookmarks()
        readerDialog = null
    }

    /** 删除单条书签。 */
internal fun BrailleReaderActivity.showDeleteBookmark(offsets: List<Int>) {
        val labels = offsets.map { offsetLabel(it) }
        readerDialog = ReaderDialog.DeleteBookmark(
            getString(R.string.braille_reader_bm_delete), labels, offsets)
    }

internal fun BrailleReaderActivity.onDeletePicked(which: Int) {
        val dialog = readerDialog as? ReaderDialog.DeleteBookmark ?: return
        val remaining = dialog.offsets.toMutableList().also { it.removeAt(which) }
        saveBookmarkOffsets(remaining)
        readerDialog = null
        Toast.makeText(this, R.string.braille_reader_bm_deleted,
            Toast.LENGTH_SHORT).show()
    }

internal fun BrailleReaderActivity.addBookmark() {
        val offsets = loadBookmarkOffsets()
        if (offsets.contains(mOffset)) {
            Toast.makeText(this, getString(R.string.braille_reader_bm_exists,
                currentUnit() + 1), Toast.LENGTH_SHORT).show()
            return
        }
        offsets.add(mOffset)
        offsets.sort()
        saveBookmarkOffsets(offsets)
        Toast.makeText(this, getString(R.string.braille_reader_bm_added,
            currentUnit() + 1), Toast.LENGTH_SHORT).show()
    }

    /** 跳到书签位置：落到该位置所在那一串盲文的词首，不劈字。 */
internal fun BrailleReaderActivity.jumpToOffset(offset: Int) {
        if (mStream.isEmpty()) return
        mOffset = BrailleFileDecoder.wordStartAtOrBefore(
            mStream, offset.coerceIn(0, maxOffset()))
        showPage()
    }

internal fun BrailleReaderActivity.offsetLabel(offset: Int): String =
        getString(R.string.braille_reader_bm_item,
            unitForOffset(offset) + 1, previewAt(offset))

    /** 取某偏移处的一行预览（去掉首尾空白方，按词边界截断，不劈字）。 */
internal fun BrailleReaderActivity.previewAt(offset: Int): String {
        val line = BrailleFileDecoder.screenAt(mStream, offset, mCellsPerLine, 1)
            .trim('\u2800')
        return BrailleFileDecoder.cutAtBoundary(line, BrailleReaderActivity.PREVIEW_CELLS)
    }

internal fun BrailleReaderActivity.clearBookmarks() {
        getSharedPreferences(BrailleReaderActivity.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(bookmarkKey()).apply()
        Toast.makeText(this, R.string.braille_reader_bm_cleared, Toast.LENGTH_SHORT).show()
    }

    /** 跳到指定页（阅读单元序号，从 1 开始）。 */
internal fun BrailleReaderActivity.showGotoDialog() {
        if (mStream.isEmpty()) {
            toastNoContent()
            return
        }
        readerDialog = ReaderDialog.Goto(
            getString(R.string.braille_reader_goto_title),
            getString(R.string.braille_reader_goto_hint, totalUnits()))
    }

internal fun BrailleReaderActivity.onGotoConfirmed(n: Int) {
        readerDialog = null
        if (n in 1..totalUnits()) {
            gotoUnit(n - 1)
        }
    }

    /**
     * 跳到本页第 [index] 行（0 基）。只在本页范围内动，不跨页：
     * 行进度条说的是「这一页有几行」，拖到底就是本页最后一行。
     */
internal fun BrailleReaderActivity.moveToPageLine(index: Int) {
        if (mStream.isEmpty() || mPageLineStarts.isEmpty()) return
        val i = index.coerceIn(0, mPageLineStarts.size - 1)
        val target = mPageLineStarts[i]
        if (target == mOffset) return
        Log.i(BrailleReaderActivity.LOG_TAG, "page line: -> $i offset=${mOffset} -> $target")
        mOffset = target
        sendToDisplay()
        showPage(resetScroll = true)
        saveProgress()
    }

    /**
     * 行滑块走到头还继续往前（[delta] = +1 下一页 / -1 上一页）：直接翻页，
     * 新页仍从第 1 行起铺。滚屏时拖到本页最后一行再往前，就顺势滚进下一页，
     * 不会像以前那样卡在页末一动不动。
     */
internal fun BrailleReaderActivity.onLineOverflow(delta: Int) {
        if (mStream.isEmpty()) return
        val target = (currentUnit() + delta).coerceIn(0, (totalUnits() - 1).coerceAtLeast(0))
        if (target == currentUnit()) {
            val msg = if (delta > 0) R.string.braille_reader_last_page else R.string.braille_reader_first_page
            Log.i(BrailleReaderActivity.LOG_TAG, "page overflow: 到头了 delta=$delta unit=${currentUnit()}")
            announce(getString(msg))
            return
        }
        Log.i(BrailleReaderActivity.LOG_TAG, "page overflow: delta=$delta unit ${currentUnit()} -> $target")
        gotoUnit(target)
    }

internal fun BrailleReaderActivity.showPageLineDialog() {        if (mStream.isEmpty()) {
            toastNoContent()
            return
        }
        readerDialog = ReaderDialog.PageLine(
            getString(R.string.braille_reader_page_line_title),
            getString(
                R.string.braille_reader_page_line_hint,
                mPageLineStarts.size.coerceAtLeast(1),
            ),
        )
    }

    /** 数字输入确认：用户说的是「第 10 行」，从 1 开始。 */
internal fun BrailleReaderActivity.onPageLineConfirmed(n: Int) {
        readerDialog = null
        if (n in 1..mPageLineStarts.size.coerceAtLeast(1)) {
            moveToPageLine(n - 1)
        }
    }

internal fun BrailleReaderActivity.showFontSizeDialog() {
        val labels = BrailleReaderActivity.FONT_SIZES.map {
            getString(R.string.braille_reader_font_size_item, it)
        }
        readerDialog = ReaderDialog.FontSize(
            getString(R.string.braille_reader_font_size_title), labels)
    }

internal fun BrailleReaderActivity.onFontSizePicked(index: Int) {
        readerDialog = null
        val size = BrailleReaderActivity.FONT_SIZES.getOrElse(index) { BrailleReaderActivity.DEFAULT_READER_FONT_SP }
        SettingsStore.get(this).edit()
            .putInt(BrailleReaderActivity.KEY_READER_FONT_SP, size)
            .apply()
        updateUiState()
    }

internal fun BrailleReaderActivity.toastNoContent() {
        Toast.makeText(this, R.string.braille_reader_no_content, Toast.LENGTH_SHORT).show()
    }

    /**
     * 书签按文件 URI 隔离存储，值为**字符偏移**。
     * 存偏移是为了让屏幕尺寸或点显器方数变化引起重排后仍能定位到原处。
     */
internal fun BrailleReaderActivity.bookmarkKey(): String = BrailleReaderActivity.KEY_BM_PREFIX + (mFileUri?.toString() ?: "")

internal fun BrailleReaderActivity.loadBookmarkOffsets(): MutableList<Int> {
        val raw = getSharedPreferences(BrailleReaderActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .getStringSet(bookmarkKey(), null) ?: return ArrayList()
        return raw.mapNotNull { it.toIntOrNull() }.toMutableList().also { it.sort() }
    }

internal fun BrailleReaderActivity.saveBookmarkOffsets(offsets: List<Int>) {
        getSharedPreferences(BrailleReaderActivity.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putStringSet(bookmarkKey(), offsets.map { it.toString() }.toSet())
            .apply()
    }
