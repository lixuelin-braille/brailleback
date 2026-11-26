/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 阅读器导航与翻页状态：阅读单元/页表、逐行整屏翻页、段落窗口、几何重算、
 * 出页装配与界面状态汇总。同包扩展函数，状态字段仍留在 BrailleReaderActivity。
 */

package com.linxi.brailleback

import android.util.Log
import com.googlecode.eyesfree.utils.SettingsStore
import com.linxi.brailleback.reader.ReaderDisplayAction
import com.linxi.brailleback.reader.ReaderDisplayKey

    /**
     * 阅读单元：一「页」是多少方 —— **只按手机屏幕自己一屏算**，
     * 与点显器一屏无关。
     *
     * 屏幕上的正文就该像普通电子书那样按版面排（实测出来的「一行几方 × 几行」），
     * 点显器那一路是后台翻译完的盲文流，按它自己的方数独立推进。两边共享的只有
     * 当前位置 [mOffset]，不必也不能凑成同一个单位：一页约有三十多个点显器屏，
     * 硬凑成同一格反而会出现「页码走了一页、手上还在读同一页」的怪账。
     */
internal fun BrailleReaderActivity.readingUnit(): Int =
        (mCellsPerLine * mLinesPerPage).coerceAtLeast(1)

internal fun BrailleReaderActivity.totalUnits(): Int = mPageOffsets.size.coerceAtLeast(1)

    /** 当前屏序号：页表中不超过 [mOffset] 的最后一个页起点。 */
internal fun BrailleReaderActivity.currentUnit(): Int = unitForOffset(mOffset)

    /** 页表中不超过 [offset] 的最后一个页起点序号（屏号）。 */
internal fun BrailleReaderActivity.unitForOffset(offset: Int): Int {
        val a = mPageOffsets
        if (a.isEmpty()) return 0
        var lo = 0
        var hi = a.size - 1
        var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (a[mid] <= offset) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

internal fun BrailleReaderActivity.maxOffset(): Int = (mStream.length - 1).coerceAtLeast(0)

    /**
     * 按当前阅读单元重建页表。
     *
     * 采用边界对齐步进后步进长度不再固定，页码无法再用除法换算，
     * 必须预先算出每一屏的起点；阅读单元变化（屏幕尺寸、点显器方数）
     * 时也要重建。
     *
     * @param keepPosition 为 true 时保留当前 [mOffset] 不吸附到新页起点，
     *   用于点显器连接/断开导致的页表重建——阅读位置不能跳。
     *   为 false（默认）时把偏移吸附到最近的页起点，用于打开文件。
     */
    /**
     * Compose 正文区实测结果：一行能排几个等宽字符、能排几行。
     *
     * 页单位从此跟随真实排版（屏幕宽度、字号、横竖屏、分屏都算），不再写死
     * 40 方 × 20 行；换字号或转屏时重测、重建页表，但**当前阅读位置不动**。
     * 明眼原文（TEXT 源）屏显是汉字，一个汉字平均占 [BrailleReaderActivity.CELLS_PER_SOURCE_CHAR] 方，
     * 所以一行可排的格数要按这个比例放大，屏幕才刚好铺满一页正文。
     */
internal fun BrailleReaderActivity.onScreenTextMetrics(charsPerLine: Int, linesPerPage: Int) {
        val cells = if (mSourceKind == ReaderSourceKind.TEXT) {
            (charsPerLine * BrailleReaderActivity.CELLS_PER_SOURCE_CHAR).toInt()
        } else {
            charsPerLine
        }.coerceIn(BrailleReaderActivity.MIN_CELLS_PER_LINE, BrailleReaderActivity.MAX_CELLS_PER_LINE)
        val lines = linesPerPage.coerceIn(BrailleReaderActivity.MIN_LINES_PER_PAGE, BrailleReaderActivity.MAX_LINES_PER_PAGE)
        if (cells == mCellsPerLine && lines == mLinesPerPage) return
        Log.d(BrailleReaderActivity.LOG_TAG, "screen metrics: chars=$charsPerLine lines=$linesPerPage " +
            "-> cells=$cells lines=$lines unit=${cells * lines}")
        mCellsPerLine = cells
        mLinesPerPage = lines
        if (mStream.isEmpty()) {
            updateUiState()
            return
        }
        // 页单位变了：重建页表，但阅读位置与点显器推送位置都不动。
        rebuildPageOffsets(keepPosition = true)
        showPage()
    }

internal fun BrailleReaderActivity.rebuildPageOffsets(keepPosition: Boolean = false) {
        val keep = keepPosition && mStream.isNotEmpty()
            && mOffset >= 0 && mOffset <= maxOffset()
        mPageOffsets = if (mStream.isEmpty()) {
            intArrayOf()
        } else {
            BrailleFileDecoder.pageOffsets(mStream, readingUnit())
        }
        Log.d(BrailleReaderActivity.LOG_TAG, "page table: unit=${readingUnit()} pages=${mPageOffsets.size} " +
            "connected=$mConnected displayCells=$mDisplayCells keep=$keep")
        if (mPageOffsets.isNotEmpty() && !keep) {
            mOffset = mPageOffsets[currentUnit()]
        }
    }

    /** 前进/后退 [delta] 屏。 */
internal fun BrailleReaderActivity.advance(delta: Int) {
        if (mPageOffsets.isEmpty()) return
        gotoUnit(currentUnit() + delta)
    }

    /** 跳到第 [unit] 屏（0 基）。起点取自页表，故不会把字切成两半。 */
internal fun BrailleReaderActivity.gotoUnit(unit: Int) {
        if (mPageOffsets.isEmpty()) return
        mOffset = mPageOffsets[unit.coerceIn(0, mPageOffsets.size - 1)]
        // 用户明确换页：正文列表回顶，从这一页第一段开始。
        showPage(resetScroll = true)
    }

    /**
     * 上一行/下一行（长按翻页键触发）。
     *
     * 翻页是一整屏（多行），逐行是微调：一次前进一行。行步进——
     * 连接点显器时按**点显器一屏**（单行点显器的「一行」就是它的整屏），
     * 未连接时按**屏幕一行**。两种操作经 [showPage] 都会自动同步点显器。
     */
internal fun BrailleReaderActivity.moveLine(delta: Int) {
        if (mStream.isEmpty()) return
        val beforeOffset = mOffset
        val step = if (mConnected && mDisplayCells > 0) mDisplayCells else mCellsPerLine
        // 步进必须落在词首：固定挪 [step] 方会把一个字（甚至一个词）劈在下一屏开头，
        // 上一屏只剩半个字。往前用 nextOffset（回退到本屏最后一个完整词），
        // 往后用 prevOffset（把目标位置对齐到词首）。
        mOffset = if (delta >= 0) {
            BrailleFileDecoder.nextOffset(mStream, mOffset, step)
        } else {
            BrailleFileDecoder.prevOffset(mStream, mOffset, step)
        }.coerceIn(0, maxOffset())
        Log.d(BrailleReaderActivity.LOG_TAG, "move: ${if (delta >= 0) "+" else "-"}$step " +
            "offset=$beforeOffset -> $mOffset")
        // 逐行是「整屏上移一行」：窗口从新的视口首行重铺，列表跟着回顶。
        showPage(resetScroll = true)
    }

    /**
     * 点显器按键的逐行移动：到文首 / 文末时 [moveLine] 会原地钳住，不播报的话用户会以为
     * 按键失灵（两键设备尤其明显，因为它只有这一个前进方向）。
     */
internal fun BrailleReaderActivity.moveLineFromKey(delta: Int) {
        if (mStream.isEmpty()) return
        val before = mOffset
        moveLine(delta)
        if (mOffset == before) {
            announce(getString(
                if (delta > 0) R.string.braille_reader_at_end
                else R.string.braille_reader_at_start))
        }
    }

    /**
     * 点显器按键落在正文页时的处理（映射见 [ReaderDisplayKey]）。
     *
     * 平移键逐行、上下行键整屏翻、顶/底键跳文首文末、回车播报当前页位置。
     */
internal fun BrailleReaderActivity.onDisplayKey(command: Int) {
        val action = ReaderDisplayKey.resolve(command)
        if (action == ReaderDisplayAction.NONE) return
        if (mStream.isEmpty()) return
        when (action) {
            ReaderDisplayAction.PREV_LINE -> moveLineFromKey(-1)
            ReaderDisplayAction.NEXT_LINE -> moveLineFromKey(1)
            // 上下行键在单行点显器上没有行可分，按「它自己一屏」步进：
            // 一次挪动 mDisplayCells 方，正好换掉点显器上的一屏。
            ReaderDisplayAction.PREV_PAGE -> moveLineFromKey(-1)
            ReaderDisplayAction.NEXT_PAGE -> moveLineFromKey(1)
            ReaderDisplayAction.FIRST_PAGE -> gotoUnit(0)
            // 越界由 gotoUnit 钳到末屏，不必自己算页数。
            ReaderDisplayAction.LAST_PAGE -> gotoUnit(Int.MAX_VALUE)
            ReaderDisplayAction.ANNOUNCE_PAGE -> {
                val firstLine = mLastView?.focusSegments?.firstOrNull().orEmpty()
                announce(listOf(scrollState.pageInfo, firstLine)
                    .filter { it.isNotEmpty() }.joinToString("，"))
            }
            ReaderDisplayAction.NONE -> return
        }
    }

    /**
     * 正文列表的段落窗口：从 [fromUnit] 起连续 [pages] 页的可聚焦段落，每段带页号。
     *
     * 只做纯文本重建，不动 mOffset 与页表；页号由「首可见段落在哪一页」反查，
     * 所以这里给的 pageIndex 就是分页的依据。
     */
internal fun BrailleReaderActivity.buildWindowSegment(fromUnit: Int, pages: Int): List<ReaderSegment> {
        if (mStream.isEmpty()) return emptyList()
        val unit = readingUnit()
        val out = ArrayList<ReaderSegment>()
        val endUnit = (fromUnit + pages - 1).coerceAtMost(totalUnits() - 1)
        for (u in fromUnit..endUnit) {
            out.addAll(buildUnitSegments(u, unit))
        }
        return out
    }

    /** 单独一页的段落（页首铺满一整页），供窗口续装用。 */
internal fun BrailleReaderActivity.buildUnitSegments(u: Int, unit: Int): List<ReaderSegment> {
        val start = mPageOffsets.getOrElse(u) { mOffset }
        val pageEnd = BrailleFileDecoder.nextOffset(mStream, start, unit)
        val brailleText = BrailleFileDecoder.screenAt(
            mStream, start, mCellsPerLine, mLinesPerPage)
        val count = (pageEnd - start).coerceIn(1, unit)
        val view = ReaderSourceModel.build(
            kind = mSourceKind,
            sourceText = mSourceText,
            brailleScreenText = brailleText,
            spans = mCellSpans,
            offset = start,
            count = count,
            currentUnit = u,
            totalUnits = totalUnits(),
            stream = mCellSpans?.let { mStream },
        )
        return view.focusSegments.map { ReaderSegment(it, u) }
    }

    /**
     * 正文滚到第 [index] 段成为首可见项：页号跟着它所属的页走。
     *
     * 只改 mOffset 与本页行表，**不重建段落**——重建会把滚动位置顶回去，
     * 跟手指抢。滚屏时「一页」只是分页刻度，行号从页首重新算。
     */
internal fun BrailleReaderActivity.onTopSegment(index: Int) {
        val seg = mWindowSegments.getOrNull(index) ?: return
        val unit = seg.pageIndex
        if (unit == currentUnit()) return
        // 只有往回退到倒数第二页之前才解除到头播报；往前滑贴在末尾的几页之间不复位。
        if (shouldReenableEndAnnounce(unit, currentUnit(), totalUnits())) mAnnouncedEnd = false
        mOffset = mPageOffsets.getOrElse(unit) { mOffset }
        Log.i(BrailleReaderActivity.LOG_TAG, "scroll page: unit=$unit offset=$mOffset")
        refreshPageGeometry()
        updateUiState()
        if (mConnected) sendToDisplay()
        saveProgress()
    }

/**
 * 是否允许播报「到文末」。
 *
 * 翻译仍在追加（[translating] = true）时，`totalUnits()` 只是当前已翻出的页数，
 * 后面还会陆续长出页面：此时贴到前沿不是书末，不报；同一文末也不重复刷。
 */
internal fun canAnnounceBookEnd(translating: Boolean, alreadyAnnounced: Boolean): Boolean =
    !translating && !alreadyAnnounced

/**
 * 是否复位「已报到文末」标记。只有**往回退**到倒数第二页之前才解除：
 * 往前滑贴着末尾的几页之间不复位，避免同一文末被反复播报。
 *
 * @param newUnit 新的首可见页；@param oldUnit 滑动前所在页；@param totalUnits 总页数
 */
internal fun shouldReenableEndAnnounce(newUnit: Int, oldUnit: Int, totalUnits: Int): Boolean =
    newUnit < oldUnit && newUnit < totalUnits - 2

internal fun BrailleReaderActivity.onNeedMoreSegments() {
        if (mStream.isEmpty()) return
        val next = mWindowEndUnit + 1
        if (next >= totalUnits()) {
            // 已到文末：贴着末尾再往前滑给一次到头播报；翻译未结束不报，同一文末不重复刷。
            if (canAnnounceBookEnd(mTranslating, mAnnouncedEnd)) {
                mAnnouncedEnd = true
                Log.i(BrailleReaderActivity.LOG_TAG, "page overflow: 到文末 unit=${currentUnit()} total=${totalUnits()}")
                announce(getString(R.string.braille_reader_at_end))
            }
            return
        }
        mWindowSegments = mWindowSegments + buildUnitSegments(next, readingUnit())
        mWindowEndUnit = next
        uiState = uiState.copy(focusSegments = mWindowSegments)
        Log.i(BrailleReaderActivity.LOG_TAG, "scroll window: +unit=$next size=${mWindowSegments.size}")
    }

    /** 只重算当前页的行表与行号（页号变了但不重铺正文时用）。 */
internal fun BrailleReaderActivity.refreshPageGeometry() {
        val unit = readingUnit()
        val pageStart = if (mStream.isEmpty()) mOffset
        else mPageOffsets.getOrElse(currentUnit()) { mOffset }
        val pageEnd = if (mStream.isEmpty()) pageStart
        else BrailleFileDecoder.nextOffset(mStream, pageStart, unit)
        mPageLineStarts = if (mStream.isEmpty()) intArrayOf(0)
        else BrailleFileDecoder.lineStarts(
            mStream, pageStart, mCellsPerLine, mLinesPerPage, pageEnd)
        Log.i(BrailleReaderActivity.LOG_TAG, "page geometry: unit=${currentUnit()} lines=${mPageLineStarts.size}")
        scrollState = scrollState.copy(
            lineIndex = 0,
            lineCount = mPageLineStarts.size.coerceAtLeast(1),
        )
    }

internal fun BrailleReaderActivity.showPage(resetScroll: Boolean = true) {
        val unit = readingUnit()
        // 屏幕正文从**视口首行**开始铺满一整屏。
        // 视口首行取光标所在的那一行：光标停在页首时视口就是整页（与翻页观感完全一致），
        // 光标落在页内第 i 行时，视口从该行起铺，屏底自然接上下一页开头的正文，
        // 于是「下移一行」看起来就是整屏向上滚一行——滚屏，而页号仍由 mOffset 反查，
        // 章节、进度、点显器推送口径都不受影响。
        val pageStart = if (mStream.isEmpty()) mOffset
        else mPageOffsets.getOrElse(currentUnit()) { mOffset }
        // 本页末（截到词边界），用来界定「本页有哪些行」。
        val pageEnd = if (mStream.isEmpty()) pageStart
        else BrailleFileDecoder.nextOffset(mStream, pageStart, unit)
        // 本页每一行的起点（与屏幕折行同规则），供行进度条与「跳到第 N 行」。
        mPageLineStarts = if (mStream.isEmpty()) intArrayOf(0)
        else BrailleFileDecoder.lineStarts(
            mStream, pageStart, mCellsPerLine, mLinesPerPage, pageEnd)
        val lineIndex = mPageLineStarts.indexOfLast { it <= mOffset }.coerceAtLeast(0)
        val viewStart = mPageLineStarts.getOrElse(lineIndex) { pageStart }
        val brailleText = if (mStream.isEmpty()) {
            getString(R.string.braille_reader_hint)
        } else {
            // 不截页末：铺满一屏，允许越过页边界，跨页连续才叫滚屏。
            BrailleFileDecoder.screenAt(
                mStream, viewStart, mCellsPerLine, mLinesPerPage)
        }
        // 视口可见格数截到词边界：末行不会露出半个字（屏显还原同口径）。
        val end = if (mStream.isEmpty()) viewStart
        else BrailleFileDecoder.nextOffset(mStream, viewStart, unit)
        val count = (end - viewStart).coerceIn(1, unit)
        val view = ReaderSourceModel.build(
            kind = mSourceKind,
            sourceText = mSourceText,
            brailleScreenText = brailleText,
            spans = mCellSpans,
            offset = viewStart,
            count = count,
            currentUnit = currentUnit(),
            totalUnits = totalUnits(),
            stream = mCellSpans?.let { mStream },
        )
        mLastView = view
        // 正文列表的窗口：**本屏（视口首行起）的段**，再接后面几页的段。
        // 读屏焦点滑到页末最后一段时，后面必须有下一段可去，它才肯继续往下；
        // 窗口里已有后续页的段，焦点于是能一路滑过去，滚过页边界时页号自己前进，
        // 不需要「下一页」节点、也不需要双击。
        //
        // 第一页从 viewStart（视口首行）起而不是页首：硬件键逐行 / 长按翻页仍然
        // 是「整屏上移一行」，窗口跟着 viewStart 走，观感不变。
        mWindowSegments = view.focusSegments.map { ReaderSegment(it, currentUnit()) } +
            buildWindowSegment(currentUnit() + 1, BrailleReaderActivity.WINDOW_PAGES - 1)
        mWindowEndUnit = (currentUnit() + BrailleReaderActivity.WINDOW_PAGES - 1).coerceAtMost(totalUnits() - 1)
        uiState = uiState.copy(
            readerText = view.displayText,
            focusSegments = mWindowSegments,
        )
        scrollState = scrollState.copy(
            lineIndex = lineIndex,
            lineCount = mPageLineStarts.size.coerceAtLeast(1),
            // 只有用户自己换页/跳行才回顶；翻译推进导致页表重建、滚屏引起页号变化都不回顶。
            scrollToken = if (resetScroll) scrollState.scrollToken + 1 else scrollState.scrollToken,
        )
        // 翻页后自动同步点显器，省去盲文用户每次手动点「发送点显」。
        // 显式换页/跳行直推；翻译推进、页表重建这类同页重铺按页节流，不重复推屏。
        if (mConnected) {
            if (resetScroll) sendToDisplay() else pushDisplayThrottled()
        }
        updateUiState()
        val unitIndex = currentUnit()
        if (unitIndex != mAnnouncedUnit) {
            mAnnouncedUnit = unitIndex
            val firstLine = mLastView?.focusSegments?.firstOrNull().orEmpty()
            announce(listOf(scrollState.pageInfo, firstLine)
                .filter { it.isNotEmpty() }.joinToString("，"))
        }
    }

internal fun BrailleReaderActivity.updateUiState() {
        val hasContent = mStream.isNotEmpty()
        val unit = readingUnit()
        val view = mLastView
        val pageInfo = if (!hasContent) {
            ""
        } else if (mConnected) {
            // 连上点显器时把驱动上报的方数一并显示，
            // 万一某设备上报不准，一眼就能看出是驱动问题而非阅读器问题。
            getString(R.string.braille_reader_page_cells,
                currentUnit() + 1, totalUnits(), mDisplayCells)
        } else {
            // 页码已交给滑块（照桌面读法「第 N 页 共 M 页」），这里只留段号，
            // 百分比随页码一起去掉——它是页内行的比例，滑块管页之后没有意义。
            val sourcePos = if (mSourceKind == ReaderSourceKind.TEXT &&
                view != null && view.paragraphCount > 0
            ) {
                getString(R.string.braille_reader_source_position,
                    view.paragraphIndex + 1, view.paragraphCount)
            } else ""
            sourcePos
        }
        val rawFont = SettingsStore.get(this)
            .getInt(BrailleReaderActivity.KEY_READER_FONT_SP, BrailleReaderActivity.DEFAULT_READER_FONT_SP)
        val fontSp = BrailleReaderActivity.FONT_SIZES.firstOrNull { it == rawFont } ?: BrailleReaderActivity.DEFAULT_READER_FONT_SP
        scrollState = scrollState.copy(
            pageInfo = pageInfo,
            pageIndex = currentUnit(),
            pageCount = totalUnits(),
        )
        uiState = uiState.copy(
            fontSizeSp = fontSp,
            hasContent = hasContent,
            prevEnabled = hasContent && mOffset > 0,
            nextEnabled = hasContent && mOffset + unit <= maxOffset(),
            tocEnabled = hasContent,
            bookmarkEnabled = hasContent,
            gotoEnabled = hasContent,
            exportEnabled = hasContent,
        )
    }
