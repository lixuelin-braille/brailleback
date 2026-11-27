/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 盲文阅读器：用系统文件选择器打开 BRF / BDO / 文本文件，
 * 自动识别编码（BOM / UTF-8 / GBK）与格式（Bword 二进制 / ASCII 盲文），
 * 按点显器一屏推进阅读，屏幕同步显示上下文，并记忆阅读进度。
 *
 * 阅读位置以**字符偏移**表示，而非「屏幕页」：点显器是单行的，
 * 常见 20/30/40 方不等，若按屏幕页（可达数百方）步进，
 * 20 方点显器每页只能读到开头一小段，其余内容永远读不到。
 *
 * 2026-11-23：界面从 XML 搬到 Compose（BrailleReaderScreen）；解码/翻译/分页/
 * 书签/进度/点显同步等逻辑一行未动。另加「导出为盲文」：编码在纯 JVM 的
 * BrailleExporter，本类只留 SAF 接线、后台写盘与播报。
 */

package com.linxi.brailleback

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.googlecode.eyesfree.braille.display.BrailleInputEvent
import com.googlecode.eyesfree.braille.display.Display
import com.googlecode.eyesfree.braille.display.DisplayClient
import com.googlecode.eyesfree.braille.translate.BrailleTranslator
import com.googlecode.eyesfree.utils.SettingsStore
import com.linxi.brailleback.compose.BrailleReaderScreen
import com.linxi.brailleback.compose.ExportFormatCatalog
import com.linxi.brailleback.compose.ExportGeometryCatalog
import com.linxi.brailleback.compose.GeometryChoice
import com.linxi.brailleback.compose.ReaderDialog
import com.linxi.brailleback.compose.ReaderLabels
import com.linxi.brailleback.compose.ReaderScrollState
import com.linxi.brailleback.compose.ReaderUiState
import com.linxi.brailleback.compose.MoreAction
import com.linxi.brailleback.compose.TranslateAction
import com.linxi.brailleback.reader.ReaderChapters
import com.linxi.brailleback.reader.ReaderDisplayAction
import com.linxi.brailleback.reader.ReaderDisplayKey
import com.linxi.brailleback.utils.LogFileWriter
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.nio.charset.StandardCharsets
import kotlin.math.min
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class BrailleReaderActivity : ComponentActivity(), Display.OnConnectionStateChangeListener {

    internal var mDisplay: DisplayClient? = null

    /**
     * 是否已向无障碍服务申请独占点显器。
     *
     * 正文页有两个写入方：本页 [sendToDisplay] 直接下发点位，无障碍服务又把本页的无障碍
     * 文本（页码行、首行摘要、播报）翻成盲文下发。两个都写同一块屏，用户摸到的就是
     * 「一会儿点位、一会儿文字」。正文在屏期间冻结服务输出，只留点位；
     * 没有正文（提示语 / 刚进页）时交还，让服务的常规文字照常显示。
     */
    internal var holdingDisplay = false

    /**
     * 正文页的点显器按键入口。
     *
     * 输入事件由服务向所有客户端广播，所以本页自带监听即可（同 81 号视口的做法）。
     * 回调在 DisplayClient 的 handler（主线程）上到，仍统一 post 一次再改状态，避免
     * 以后换成别的线程时静默出错。
     */
    internal val displayKeyListener = object : Display.OnInputEventListener {
        override fun onInputEvent(inputEvent: BrailleInputEvent) {
            runOnUiThread { onDisplayKey(inputEvent.command) }
        }
    }

    /** 阅读流：段落以 '\n' 分隔，空行已折叠。 */
    internal var mStream = ""
    /** 当前阅读位置（字符偏移）。 */
    internal var mOffset = 0
    internal var mConnected = false
    internal var mFileUri: Uri? = null

    /** 上一次已推送到点显器的页号（unit）；同页重复重铺不重复推屏。 */
    internal var lastPushedUnit = -1

    /** Compose 界面状态与弹窗。 */
    internal var uiState by mutableStateOf(ReaderUiState())
    internal var scrollState by mutableStateOf(ReaderScrollState())
    internal var readerDialog by mutableStateOf<ReaderDialog?>(null)

    /** 进度写盘去抖任务；离开页面时由 flushProgress() 立即落盘。 */
    internal var progressSaveJob: Job? = null

    /** 翻译服务（明眼 txt 电子书 → 盲文）。懒初始化：绑定 service 模块的翻译服务。 */
    internal val mTranslatorManager by lazy { TranslatorManager(this) }

    /** 正在翻译标志，防止重复触发。 */
    internal var mTranslating = false

    /** 用户在翻译未完成时点了导出：翻完自动把导出弹窗接着打开。 */
    internal var mPendingExport = false

    /** 翻译服务超时未就绪只播报一次，避免每本书都重复吵。 */
    internal var mWarnedTranslatorUnavailable = false

    /** 当前流是否来自明眼 txt 翻译。翻译流与屏幕尺寸无关，重排时无需重翻。 */
    internal var mIsTranslated = false

    /** 逐格源区间（仅翻译流有）；点位 JSON 导出与屏幕取字共用。 */
    internal var mCellSpans: List<CellSource>? = null

    /** TEXT 源原始明文；盲文源为空。屏幕回显原文用，不参与点显/导出。 */
    internal var mSourceText = ""
    /** 当前屏显来源类型：TEXT 回显原文，其余回显点字。 */
    internal var mSourceKind = ReaderSourceKind.BRAILLE

    /** 最近一屏视图模型结果，供页码行与无障碍播报取用。 */
    internal var mLastView: ReaderPageView? = null

    /**
     * 正文列表当前加载的段落窗口（本页 + 后面几页）。
     *
     * 读屏焦点滑到页末最后一段时必须有「下一段」可去，否则焦点就停在页末不动。
     * 窗口里接着后面页的段，焦点于是能一路滑下去，滚过页边界时页号跟着前进。
     */
    internal var mWindowSegments: List<ReaderSegment> = emptyList()

    /** 窗口里已经装到第几页（含），滚到窗口尾部时按页续装。 */
    internal var mWindowEndUnit = 0

    /** 已播报过的屏序号，避免逐行移动重复播报。 */
    internal var mAnnouncedUnit = -1

    /** 文末到头播报是否已给过；离开末页后复位，避免贴着末尾反复刷同一句。 */
    internal var mAnnouncedEnd = false

    /** 翻译服务未就绪时挂起的翻译请求。 */
    internal var mPendingTranslation: PendingTranslation? = null

    /**
     * 用户点了「停止翻译」。
     *
     * 翻译跑在独立线程里，只能靠这个标志在段边界收尾；已经翻好的部分
     * 照常上屏、照常留在落盘文件里，只是不再写完成标记。
     */
    @Volatile
    internal var mCancelTranslate = false

    /** 当前书的文件名，重新翻译时重建阅读流要用（mFileUri 可能是 content:// 的编码串）。 */
    internal var mFileName: String? = null

    internal val mTranslationReadyListener: TranslatorManager.OnTablesChangedListener =
        object : TranslatorManager.OnTablesChangedListener {
            override fun onTablesChanged() {
                val pending = mPendingTranslation
                // 排查「进书后像翻译了好几次」：清单/表变化会重复通知，这里记一条，
                // 有没有挂起的翻译请求一眼就能看出来。
                Log.d(LOG_TAG, "tables changed: pending=${pending != null}, " +
                    "translator=${mTranslatorManager.getTranslator() != null}")
                if (pending == null) return
                // 表清单就绪 ≠ 翻译器造好：这一步可能先到。此时若照原逻辑重跑
                // startTranslation，会再铺一遍原文流（用户听到两次「先按原文读」、
                // 点显器也被重推一次），看起来就像「翻译了好几次」。继续挂起等下一次通知。
                if (mTranslatorManager.getTranslator() == null) return
                mPendingTranslation = null
                mTranslatorManager.removeOnTablesChangedListener(mTranslationReadyListener)
                startTranslation(pending.text, pending.name, pending.uri,
                    pending.resetPosition, pending.targetOffset)
            }
        }

    /**
     * 屏幕每行显示的方数，由屏幕宽度实测得出。
     * 必须按屏幕算：若按固定值折行，超出屏宽时文本会自动换行，
     * 一页 20 行会被撑成 40 行，得滚动才能看完。
     */
    internal var mCellsPerLine = DEFAULT_CELLS
    /** 屏幕每页显示的行数，由屏幕高度实测得出。 */
    internal var mLinesPerPage = LINES_PER_PAGE
    /** 点显器每行方数，连接后由 getDisplayProperties() 更新；未连接用默认值。 */
    internal var mDisplayCells = DEFAULT_CELLS
    /**
     * 回到本页（onResume / 重新连上点显器）时，先把屏清成空方再铺本页。
     *
     * 从别的界面切回来时，点显器上常常还留着那边的字（服务/输入法的文字输出），
     * 不先清一次就会和这一页的点位混在一起，摸到的既不像书也不像刚离开的界面。
     */
    internal var mClearDisplayOnEnter = true
    /** 当前页内每一行的起点偏移，行进度条与「跳到第 N 行」都按它走。 */
    internal var mPageLineStarts: IntArray = intArrayOf(0)
    /** 上次读到、但因后台翻译还没翻到而暂时落不了地的位置。 */
    internal var mPendingRestoreOffset: Int? = null

    /**
     * 每一屏的起始偏移。
     *
     * 采用边界对齐步进（[BrailleFileDecoder.nextOffset]）后步进长度不再固定，
     * 页码无法再用「偏移 / 单元」换算，故预先算好页表（见 [rebuildPageOffsets]）。
     */
    internal var mPageOffsets: IntArray = intArrayOf()

    /**
     * 建表时的阅读单元（[readingUnit]）。增量续算只在 unit 没变时进行；
     * 转屏、换字号、点显器方数变化导致 unit 变化后，回落整表重建并刷新这个值。
     */
    internal var mPageUnit: Int = -1


    // ------------------------------------------------------------------
    // 导出（SAF + 后台写盘）
    // ------------------------------------------------------------------

    /** 导出防重入；编码与写盘都在后台线程，主线程只做弹窗/按钮态/播报。 */
    internal var mExporting = false
    internal var pendingFormat: BrailleExportFormat? = null

    /**
     * 点显器上报的原始方数（未连接或上报异常时为 null）。
     * 和 [mDisplayCells] 的区别：mDisplayCells 会在异常时兜底成默认值，
     * 导出排版不能把「兜底的 40」当成设备真方数，所以这里单独留一份可空的原值。
     */
    internal var mReportedCells: Int? = null

    /** 版面预设：4 档方数 × 3 档行数，顺序与版面菜单项一致。 */
    internal val geometryPresets: List<PageGeometry> =
        PageGeometry.PRESET_COLUMNS.flatMap { columns ->
            PageGeometry.PRESET_LINES.map { lines -> PageGeometry(columns, lines) }
        }

    /** BRF 按 octet-stream 落盘；BRL / 文本按 text/plain。 */
    internal val brfExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        onExportUri(uri)
    }
    internal val textExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        onExportUri(uri)
    }
    internal val jsonExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")) { uri ->
        onExportUri(uri)
    }

    private fun showExportMenu() {
        if (mStream.isEmpty()) {
            announce(getString(R.string.braille_reader_export_empty))
            Toast.makeText(this, R.string.braille_reader_export_empty,
                Toast.LENGTH_SHORT).show()
            return
        }
        // 翻译没完成时导出的会把明眼汉字当盲文丢光，全是问号。
        // 要么等这一刻的翻译，要么明确告诉用户为什么现在不能导。
        if (mSourceKind == ReaderSourceKind.TEXT && !mIsTranslated) {
            if (mTranslating) {
                mPendingExport = true
                announce(getString(R.string.braille_reader_export_wait_translate))
                Toast.makeText(this, R.string.braille_reader_export_wait_translate,
                    Toast.LENGTH_SHORT).show()
            } else {
                announce(getString(R.string.braille_reader_export_need_translate))
                Toast.makeText(this, R.string.braille_reader_export_need_translate,
                    Toast.LENGTH_LONG).show()
            }
            return
        }
        // 标题与说明都按 ExportFormatCatalog.formats 的顺序生成，序号永远与格式对齐。
        val formats = ExportFormatCatalog.formats
        val labels = formats.map { getString(exportFormatLabelRes(it)) }
        val descriptions = formats.map { getString(exportFormatDescRes(it)) }
        readerDialog = ReaderDialog.ExportFormat(
            getString(R.string.braille_reader_export_title), labels, descriptions,
            getString(R.string.braille_reader_export_hint))
    }

    internal fun announce(msg: CharSequence) {
        window.decorView.announceForAccessibility(msg)
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 阅读器可独立于无障碍服务使用，单独初始化文件日志
        LogFileWriter.init(this)

        setContent {
            BrailleReaderScreen(
                uiState = uiState,
                scrollState = scrollState,
                labels = ReaderLabels(
                    prev = getString(R.string.braille_reader_prev),
                    next = getString(R.string.braille_reader_next),
                    toc = getString(R.string.braille_reader_toc),
                    bookmark = getString(R.string.braille_reader_bookmark),
                    goto = getString(R.string.braille_reader_goto),
                    export = getString(R.string.braille_reader_export),
                    translate = getString(R.string.braille_reader_translate_menu),
                    more = getString(R.string.braille_reader_more),
                    fontSize = getString(R.string.braille_reader_font_size),
                    navHint = getString(R.string.braille_reader_nav_hint),
                    ok = getString(android.R.string.ok),
                    cancel = getString(android.R.string.cancel),
                    delete = getString(R.string.braille_reader_bm_delete),
                    clearAll = getString(R.string.braille_reader_bm_clear_all),
                    pageLine = { line, total ->
                        getString(R.string.braille_reader_page_line, line, total)
                    },
                    pageScrubber = { page, total ->
                        getString(R.string.braille_reader_page_scrubber, page, total)
                    },
                    pageScrubberAction = getString(R.string.braille_reader_page_scrubber_action),
                    pageLineAction = getString(R.string.braille_reader_page_line_action),
                    nextPage = getString(R.string.braille_reader_next_page),
                ),
                dialog = readerDialog,
                onPrev = { advance(-1) },
                onPrevLine = { moveLine(-1) },
                onNext = { advance(1) },
                onNextLine = { moveLine(1) },
                onToc = ::showToc,
                onBookmark = ::showBookmarks,
                onGoto = ::showGotoDialog,
                onExport = ::showExportMenu,
                onTranslate = ::showTranslateMenu,
                onTranslatePick = ::onTranslatePick,
                onMore = ::showMoreMenu,
                onMorePick = ::onMorePick,
                onFont = ::showFontSizeDialog,
                onFontSizePick = ::onFontSizePicked,
                onTocPick = ::onTocPicked,
                onBookmarkPick = ::onBookmarkPicked,
                onBookmarkDelete = ::onBookmarkDeleteRequested,
                onBookmarkClear = ::onBookmarkClearRequested,
                onDeletePick = ::onDeletePicked,
                onGotoConfirm = ::onGotoConfirmed,
                onExportPick = ::onExportPicked,
                onGeometryPick = ::onGeometryPicked,
                onCustomGeometryConfirm = ::onCustomGeometryConfirmed,
                onDismissDialog = { readerDialog = null },
                onPageSeek = ::gotoUnit,
                onPageEdit = ::showGotoDialog,
                onLineSeek = ::moveToPageLine,
                onLineEdit = ::showPageLineDialog,
                onLineOverflow = ::onLineOverflow,
                // 滚屏即分页：首可见段落在哪一页，页号就跟到哪一页。
                onTopSegment = ::onTopSegment,
                onNeedMoreSegments = ::onNeedMoreSegments,
                onLineConfirm = ::onPageLineConfirmed,
                onTextMetrics = ::onScreenTextMetrics,
            )
        }

        // 书架/最近打开跳转而来：直接加载指定文件
        intent.getStringExtra(EXTRA_URI)?.let { uriStr ->
            try {
                val uri = Uri.parse(uriStr)
                mFileUri = uri
                // 同一本书续读上次进度；换了一本书从第 1 页开始。
                // 否则盲人用户从书架重开书永远回到第 1 页，长书重新定位成本高。
                // 按书各存一份位置：以前只存「最近读的那一本」一个槽，
                // 读过 B 书再回 A 书，A 的进度就被覆盖没了。
                val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                val savedOffset = prefs.getInt(perBookOffsetKey(uriStr), 0)
                loadFile(uri, savedOffset)
            } catch (e: Exception) {
                Toast.makeText(this,
                    getString(R.string.braille_reader_load_fail, e.message),
                    Toast.LENGTH_SHORT).show()
            }
        }
        updateUiState()
    }

    override fun onResume() {
        super.onResume()
        val display = DisplayClient(this)
        mDisplay = display
        display.setOnConnectionStateChangeListener(this)
        display.setOnInputEventListener(displayKeyListener)
        // 每次回到本页都先清屏：屏上可能还是刚才那个界面的字。
        mClearDisplayOnEnter = true
        onConnectionStateChanged(Display.STATE_NOT_CONNECTED)
        // 首次进入且尚无内容时，尝试恢复上次阅读进度。
        // mFileUri != null 说明本次是书架/最近打开明确指定的书（onCreate 已同步
        // 设置并触发加载），绝不能再用 last_uri 恢复上一本——否则挂起的
        // 加载请求会被旧书覆盖，用户要开 B 书实际打开的却是 A 书。
        if (mStream.isEmpty() && mFileUri == null) {
            restoreProgress()
        }
    }

    override fun onPause() {
        super.onPause()
        flushProgress()
        // 离开正文页就交还点显器，否则服务一直冻着，别的界面摸不到字。
        releaseDisplay()
        mDisplay?.shutdown()
        mDisplay = null
        mConnected = false
    }

    override fun onDestroy() {
        super.onDestroy()
        progressSaveJob?.cancel()
        progressSaveJob = null
        mTranslatorManager.removeOnTablesChangedListener(mTranslationReadyListener)
        mTranslatorManager.shutdown()
    }

    override fun onConnectionStateChanged(state: Int) {
        val wasConnected = mConnected
        val oldDisplayCells = mDisplayCells
        mConnected = state == Display.STATE_CONNECTED
        Log.d(LOG_TAG, "display state=$state connected=$mConnected " +
            "cells=${mDisplay?.displayProperties?.numTextCells ?: -1}")
        if (mConnected) {
            lastPushedUnit = -1
            // 完全采用系统/驱动上报的方数（numTextCells），不做推测。
            // 仅对明显异常的值兜底：0 表示尚未就绪，过大则是驱动误报，
            // 若照单全收会让「一屏」变成整本书、步进失控。
            val cells = mDisplay?.displayProperties?.numTextCells ?: 0
            mDisplayCells = when {
                cells <= 0 -> {
                    Log.w(LOG_TAG, "点显器方数未上报($cells)，暂用默认 $DEFAULT_CELLS")
                    DEFAULT_CELLS
                }
                cells > MAX_DISPLAY_CELLS -> {
                    Log.w(LOG_TAG, "点显器方数上报异常($cells)，回退默认 $DEFAULT_CELLS")
                    DEFAULT_CELLS
                }
                else -> cells
            }
            // 只有驱动真正上报的有效方数才留给导出排版（兜底出来的默认值不算设备方数）。
            mReportedCells = cells.takeIf { it in 1..MAX_DISPLAY_CELLS }
        } else {
            mReportedCells = null
            // 独占标记不在这里清。onResume 会先灌一次 NOT_CONNECTED 做初始化，
            // 若在此清标志，等服务侧那份 hold 已经发出去了，两边就失去配对：
            // 退出正文时 release 被 `if (!holdingDisplay) return` 拦掉，
            // 服务侧 hostOwned 一直留着，压输出又吞按键，表现为阅读器一退
            // 无障碍就彻底不动。真正的交还点只有 onPause 的 releaseDisplay()。
        }
        // 阅读单元随连接状态/方数变化：连接后按点显器一屏步进、断开后回屏幕一屏。
        // 页表步进单位变了必须重建，但只换「翻页的格子」，不移动当前阅读位置。
        if ((mConnected != wasConnected || mDisplayCells != oldDisplayCells)
                && mStream.isNotEmpty()) {
            rebuildPageOffsets(keepPosition = true)
        }
        runOnUiThread {
            updateUiState()
            // 刚回到本页：先把屏清空，再把本页点位铺上去，
            // 免得上一处界面留下的字和这一页混在一起。
            if (mConnected && mClearDisplayOnEnter) {
                mClearDisplayOnEnter = false
                clearDisplay()
            }
            // 连上点显器/回到本页立即推送当前屏
            if (mConnected && mStream.isNotEmpty()) {
                sendToDisplay()
            }
        }
    }

    // ------------------------------------------------------------------
    // 翻译模式：明眼 txt 电子书 -> 盲文
    // ------------------------------------------------------------------

    /**
     * 缓存键里的盲文表标识。
     *
     * 换表之后点位完全不同，缓存必须跟着表走；翻译服务还没起来时给个
     * 稳定占位，等服务就绪再查同一份缓存也不冲突（那时表名已确定，
     * 命中与否自然分开）。
     */
    internal fun translationTableName(): String {
        val info = mTranslatorManager.getTranslator()?.tableInfo ?: return "unknown"
        return "${info.id ?: ""}|${info.locale}|${info.grade}|${info.isEightDot}"
    }

    /**
     * 「更多」菜单：翻译、字号、导出。
     *
     * 正文上方只留 目录 / 书签 / 更多 三个入口。按钮一多，读屏滑过按钮区
     * 要多走好几步，正文区也被挤短；字号、导出这些低频操作收进来更划算。
     */
    internal fun showMoreMenu() {
        val labels = ArrayList<String>(3)
        val descriptions = ArrayList<String>(3)
        val actions = ArrayList<MoreAction>(3)
        // 只有明眼文字书才有翻译这回事；盲文文件连这一项都不列，省得白点。
        if (mSourceKind == ReaderSourceKind.TEXT) {
            labels.add(getString(R.string.braille_reader_translate_menu))
            descriptions.add(getString(R.string.braille_reader_more_translate_desc))
            actions.add(MoreAction.TRANSLATE)
        }
        labels.add(getString(R.string.braille_reader_font_size))
        descriptions.add(getString(R.string.braille_reader_more_font_desc, uiState.fontSizeSp))
        actions.add(MoreAction.FONT)
        if (mStream.isNotEmpty()) {
            labels.add(getString(R.string.braille_reader_export))
            descriptions.add(getString(R.string.braille_reader_more_export_desc))
            actions.add(MoreAction.EXPORT)
        }
        readerDialog = ReaderDialog.More(
            getString(R.string.braille_reader_more_title), labels, descriptions, actions)
    }

    internal fun onMorePick(which: Int) {
        val dialog = readerDialog as? ReaderDialog.More ?: return
        val action = dialog.actions.getOrNull(which) ?: return
        readerDialog = null
        when (action) {
            MoreAction.TRANSLATE -> showTranslateMenu()
            MoreAction.FONT -> showFontSizeDialog()
            MoreAction.EXPORT -> showExportMenu()
        }
    }

    /**
     * 「翻译」菜单：重新翻译 / 停止翻译 / 清除本书缓存。
     *
     * 盲文文件本来不翻译，入口置灰并且点了一句说明，免得用户以为坏了。
     */
    internal fun showTranslateMenu() {
        if (mSourceKind != ReaderSourceKind.TEXT) {
            announce(getString(R.string.braille_reader_translate_no_text))
            return
        }
        val uri = mFileUri ?: return
        val labels = ArrayList<String>(3)
        val descriptions = ArrayList<String>(3)
        val actions = ArrayList<TranslateAction>(3)
        if (mTranslating) {
            labels.add(getString(R.string.braille_reader_translate_stop))
            descriptions.add(getString(R.string.braille_reader_translate_stop_desc))
            actions.add(TranslateAction.STOP)
        } else {
            labels.add(getString(R.string.braille_reader_translate_retry))
            descriptions.add(getString(R.string.braille_reader_translate_retry_desc))
            actions.add(TranslateAction.RETRY)
        }
        if (ReaderTranslationCache.isComplete(this, uri, translationTableName())) {
            labels.add(getString(R.string.braille_reader_translate_clear))
            descriptions.add(getString(R.string.braille_reader_translate_clear_desc))
            actions.add(TranslateAction.CLEAR_CACHE)
        }
        readerDialog = ReaderDialog.Translate(
            getString(R.string.braille_reader_translate_menu_title),
            labels, descriptions, actions)
    }

    internal fun onTranslatePick(which: Int) {
        val dialog = readerDialog as? ReaderDialog.Translate ?: return
        val action = dialog.actions.getOrNull(which) ?: return
        readerDialog = null
        val uri = mFileUri ?: return
        when (action) {
            TranslateAction.STOP -> {
                // 不收流、不清屏：已经翻好的部分正在读，停下来就该原地不动。
                mCancelTranslate = true
                Log.d(LOG_TAG, "translate stop requested")
            }
            TranslateAction.RETRY -> {
                // 重翻：丢掉缓存与当前译流，位置记住，靠 mPendingRestoreOffset
                // 等新流翻到那个位置再自动落回去（与首次打开同一套机制）。
                ReaderTranslationCache.clear(this, uri, translationTableName())
                val keep = mOffset
                mStream = ""
                mCellSpans = null
                mIsTranslated = false
                mPageOffsets = intArrayOf()
                mOffset = 0
                rebuildPageOffsets()
                showPage()
                startTranslation(mSourceText, mFileName ?: mFileUri?.lastPathSegment, uri,
                    resetPosition = true, targetOffset = keep, force = true)
            }
            TranslateAction.CLEAR_CACHE -> {
                ReaderTranslationCache.clear(this, uri, translationTableName())
                val msg = getString(R.string.braille_reader_translate_cache_cleared)
                announce(msg)
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 启动翻译。翻译服务（TranslatorService）是异步绑定的，首次打开 txt
     * 时可能尚未就绪；未就绪则挂起请求，服务就绪后由 [mTranslationReadyListener]
     * 自动继续。
     */
    internal fun startTranslation(
        text: String,
        name: String?,
        uri: Uri,
        resetPosition: Boolean,
        targetOffset: Int? = null,
        force: Boolean = false,
    ) {
        if (mTranslating) return
        // 这本书翻过：直接用上次落盘的点位，不重翻、不播报、不碰翻译服务。
        // 翻译服务没起来也能靠这份缓存读，所以这一步放在取 translator 之前。
        if (!force) {
            val stored = ReaderTranslationCache.read(this, uri, translationTableName())
            if (stored != null) {
                mSourceText = text
                mSourceKind = ReaderSourceKind.TEXT
                setBrailleStream(stored.text, name, uri, resetPosition, targetOffset,
                    isTranslated = true,
                    cellSpans = CellSourceTable(text, stored.cells),
                    notifyLoaded = false)
                Log.d(LOG_TAG, "translate cache hit: chars=${stored.text.length}")
                return
            }
        }
        val translator = mTranslatorManager.getTranslator()
        if (translator == null) {
            mPendingTranslation = PendingTranslation(text, name, uri, resetPosition, targetOffset)
            mTranslatorManager.addOnTablesChangedListener(mTranslationReadyListener)
            // 翻译服务没就绪也不能让用户对着空屏干等：先把原文铺进阅读流，
            // 至少能听到正文、能翻页、能进目录；服务就绪后自动切成盲文流。
            // 翻译没起来时也别报「已加载共 N 页」：这一屏只是临时铺的原文，
            // 读屏一进书就连着报两句，正文还没开始就先吵了一轮。
            setBrailleStream(text, name, uri, resetPosition, targetOffset,
                isTranslated = false, notifyLoaded = false)
            Toast.makeText(this,
                getString(R.string.braille_reader_translator_pending),
                Toast.LENGTH_LONG).show()
            // 8 秒还拿不到翻译器就说明这条链路真的起不来（多包冲突、服务崩溃等），
            // 明确说一句“先按原文读”，别让用户以为翻译成了只是没声。
            // 延时不走裸 Handler：Activity 销毁时 lifecycleScope 自动取消。
            lifecycleScope.launch {
                delay(TRANSLATOR_WARN_DELAY_MS)
                if (!mIsTranslated && mSourceKind == ReaderSourceKind.TEXT
                        && !mWarnedTranslatorUnavailable) {
                    mWarnedTranslatorUnavailable = true
                    val msg = getString(R.string.braille_reader_translator_unavailable)
                    announce(msg)
                    Toast.makeText(this@BrailleReaderActivity, msg, Toast.LENGTH_LONG).show()
                }
            }
            return
        }
        mTranslating = true
        mCancelTranslate = false
        Log.d(LOG_TAG, "translate start: chars=${text.length}")
        uiState = uiState.copy(
            readerText = getString(R.string.braille_reader_translating))
        warnIfTableMismatch(text, translator)
        // 后台翻译要「边翻边能读」：进度挂在字段里，翻出第一块就先上屏，
        // 之后按间隔续块，用户不用干等整本（3MB 书整本约一分钟）。
        mPendingRestoreOffset = targetOffset
        // 边翻边落盘：下次打开这本书直接读点位，不用再翻一遍。
        val cacheTable = translationTableName()
        ReaderTranslationCache.begin(this, uri, cacheTable)
        Thread {
            translateParagraphs(text, translator,
                onPersist = { deltaText, deltaCells ->
                    ReaderTranslationCache.append(this, uri, cacheTable, deltaText, deltaCells)
                },
                onChunk = { chunk ->
                    runOnUiThread {
                        applyTranslatedChunk(chunk, name, uri, resetPosition, targetOffset)
                    }
                })
        }.start()
    }

    /**
     * 应用一块翻译结果。第一块整块铺流（分配位置、记最近打开），之后只往末尾追：
     * 追加不改变已有偏移，所以用户正读的那一屏不会被拽走。
     */
    internal fun applyTranslatedChunk(
        chunk: TranslatedChunk,
        name: String?,
        uri: Uri,
        resetPosition: Boolean,
        targetOffset: Int?
    ) {
        if (isFinishing || isDestroyed) {
            mTranslating = false
            return
        }
        val keepPlace = mSourceKind == ReaderSourceKind.TEXT &&
            !mIsTranslated && mStream.isNotEmpty()
        if (!mIsTranslated) {
            if (chunk.text.isEmpty()) {
                // 第一段就失败：没有可读内容，退回原文流并明说翻译失败。
                mTranslating = false
                setBrailleStream(mSourceText, name, uri,
                    if (keepPlace) false else resetPosition,
                    if (keepPlace) null else targetOffset)
                Toast.makeText(this,
                    getString(R.string.braille_reader_translate_fail),
                    Toast.LENGTH_LONG).show()
                return
            }
            setBrailleStream(chunk.text, name, uri,
                if (keepPlace) false else resetPosition,
                if (keepPlace) null else targetOffset,
                isTranslated = true,
                cellSpans = CellSourceTable(mSourceText, chunk.cellPositions),
                // 翻译只播「开始翻译」和「翻译结束」两句：中间每次切块都报
                // 「已翻出前 N 页」「已加载共 N 页」，读屏会一直打断正文。
                notifyLoaded = false)
        } else if (chunk.text.isNotEmpty()) {
            appendBrailleStream(chunk.text, chunk.cellPositions)
        }
        restorePendingOffsetIfReached()
        if (chunk.done) {
            mTranslating = false
            val stopped = mCancelTranslate
            mCancelTranslate = false
            // 完整翻完才写完成标记：停止或失败留下的半本不能当缓存复用，
            // 否则下次打开会以为整本都在，读到后面直接断掉。
            if (!stopped && chunk.failedAtParagraph == 0) {
                ReaderTranslationCache.complete(this, uri, translationTableName())
            }
            val msg = when {
                stopped -> getString(R.string.braille_reader_translate_stopped)
                chunk.failedAtParagraph > 0 -> getString(
                    R.string.braille_reader_translate_partial_stop,
                    chunk.failedAtParagraph, totalUnits())
                else -> getString(R.string.braille_reader_translate_done)
            }
            announce(msg)
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            Log.d(LOG_TAG, "translate done: paragraphs=${chunk.paragraphs} " +
                "failedAt=${chunk.failedAtParagraph} units=${totalUnits()}")
            // 用户是在“翻译没完”的时候点的导出，这里接着把导出弹窗打开。
            if (mPendingExport) {
                mPendingExport = false
                showExportMenu()
            }
        }
    }

    companion object {
        internal const val LOG_TAG = "BrailleReader"

        /** CJK 字符（汉字扩展 A 区+基本区/假名/谚文），用于内容语言检测。 */
        internal val CJK_CONTENT = Regex("[\\u3400-\\u4DBF\\u4E00-\\u9FFF\\u3040-\\u30FF\\uAC00-\\uD7AF]")

        /** 以 CJK 为主要书写语言的表 locale.language 集合。 */
        internal val CJK_TABLE_LANGUAGES = setOf("zh", "ja", "ko")

        /** 书架/最近打开跳转时携带待打开文件 uri（见 BookshelfActivity）。 */
        const val EXTRA_URI = "com.linxi.brailleback.extra.OPEN_URI"

        internal const val DEFAULT_CELLS = 40
        internal const val LINES_PER_PAGE = 20

        /** 翻译服务超过这个时长仍未就绪，就播报一次“先按原文读”。 */
        internal const val TRANSLATOR_WARN_DELAY_MS = 8000L

        /** 单段翻译失败后的原地重试次数与间隔（桥抖动多为瞬时）。 */
        internal const val TRANSLATE_ATTEMPTS = 3
        internal const val TRANSLATE_RETRY_DELAY_MS = 300L

        /**
         * 边翻边读：第一块攒够这么多字就先上屏（约 1–2 秒），之后每
         * [PUBLISH_INTERVAL_MS] 毫秒续一块，免得用户对着「正在翻译」干等一分钟。
         */
        internal const val FIRST_PUBLISH_CHARS = 12_000
        internal const val PUBLISH_INTERVAL_MS = 2_000L
        /** 单块最少这么多字；不到就先攒着，免得先推上去半页空屏。约 1.5 页正文。 */
        internal const val MIN_PUBLISH_CHARS = 1_200
        /**
         * 点显器方数上限，仅用于兜住驱动误报。
         * 常见单行点显器为 12–80 方，多行显示器最多数百方；
         * 超过此值基本可判定为上报异常（照单全收会让「一屏」变成整本书）。
         */
        internal const val MAX_DISPLAY_CELLS = 4096
        /**
         * 低于此值视为布局未完成或极端小屏，不据此重排。
         * 横屏时分屏只剩几行可显示，一页 3 行会把页码撑到一两万页，
         * 这里兜到 5 行：屏幕可能少显示几行，但盲文流是齐的、页码也还说得通。
         */
        internal const val MIN_LINES_PER_PAGE = 5
        internal const val MIN_CELLS_PER_LINE = 8
        /** 屏幕一行最多按这么多方算（400 字 × 2.5 方），兜住横屏/分屏误测。 */
        internal const val MAX_CELLS_PER_LINE = 1000
        internal const val MAX_LINES_PER_PAGE = 80
        /** 明眼原文屏显是汉字：通用盲文一个汉字平均占约 2.5 方，用来把「一行几个字」换成「一行几方」。 */
        internal const val CELLS_PER_SOURCE_CHAR = 2.5
        /**
         * 正文列表一次装几页的段落。
         *
         * 读屏焦点滑到页末最后一段时，后面必须有段可去；装 3 页够滑一阵，
         * 又不会一次重建太多文本（每次换页都要按页重建一遍）。
         */
        internal const val WINDOW_PAGES = 3
        /** 满方盲文，用于测量单个盲文字符的实际宽度。 */
        internal const val CELL_SAMPLE = "\u283F"
        internal const val PREFS_NAME = "braille_reader"
        internal const val KEY_URI = "last_uri"
        internal const val KEY_OFFSET = "last_offset"
        internal const val KEY_BM_PREFIX = "bm_"
        internal const val KEY_EXPORT_COLUMNS = "export_columns"
        internal const val KEY_EXPORT_LINES = "export_lines_per_page"
        /** 导出排版是否跟随点显器上报的方数；默认开启。 */
        internal const val KEY_EXPORT_FOLLOW_DISPLAY = "export_follow_display"
        /** 导出折行是否按词断（空方处断行）；默认开启。 */
        internal const val KEY_EXPORT_WORD_WRAP = "export_word_wrap"
        internal const val EXPORT_MIN_COLUMNS = 20
        internal const val EXPORT_MAX_COLUMNS = 40
        internal const val EXPORT_MIN_LINES = 20
        internal const val EXPORT_MAX_LINES = 28
        internal const val KEY_READER_FONT_SP = "reader_font_sp"
        internal const val DEFAULT_READER_FONT_SP = 18
        internal val FONT_SIZES = listOf(18, 22, 26)
        /** 目录每 N 页一条。 */
        internal const val TOC_PAGE_STEP = 10
        /** 兜底（按页分组）目录最多这么多条，超了就把每组页数加大。 */
        internal const val TOC_MAX_ENTRIES = 150
        /** 目录/书签列表里预览用到的方数。 */
        internal const val PREVIEW_CELLS = 40
        /** 目录预览取多少个原文字。 */
        internal const val PREVIEW_CHARS = 14
        /** 翻译服务未就绪时挂起的翻译请求。 */
        internal data class PendingTranslation(
            val text: String,
            val name: String?,
            val uri: Uri,
            val resetPosition: Boolean,
            val targetOffset: Int?
        )

        /**
         * 明确不支持的二进制/排版格式。盲文阅读器本身只处理盲文与文字：
         * .bdo/.bdoc（Bword 二进制盲文）、.brf/.brl/.txt（文本类）、
         * .epub/.html/.md/.docx/.docm（由 [EbookTextExtractor] 取出文字后翻译）。
         * PDF/MOBI 等需先转成盲文；旧版 .doc 在 checkSupportedFormat 里单独提示。
         */
        internal val UNSUPPORTED_EXT = setOf(
            "doc", "wps", "rtf", "odt",
            "pdf", "mobi", "azw3",
            "rar", "zip", "7z",
            "jpg", "jpeg", "png", "gif", "bmp", "webp"
        )
    }
}
