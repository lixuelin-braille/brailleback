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
import com.linxi.brailleback.compose.ReaderUiState
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class BrailleReaderActivity : ComponentActivity(), Display.OnConnectionStateChangeListener {

    private var mDisplay: DisplayClient? = null

    /**
     * 是否已向无障碍服务申请独占点显器。
     *
     * 正文页有两个写入方：本页 [sendToDisplay] 直接下发点位，无障碍服务又把本页的无障碍
     * 文本（页码行、首行摘要、播报）翻成盲文下发。两个都写同一块屏，用户摸到的就是
     * 「一会儿点位、一会儿文字」。正文在屏期间冻结服务输出，只留点位；
     * 没有正文（提示语 / 刚进页）时交还，让服务的常规文字照常显示。
     */
    private var holdingDisplay = false

    /**
     * 正文页的点显器按键入口。
     *
     * 输入事件由服务向所有客户端广播，所以本页自带监听即可（同 81 号视口的做法）。
     * 回调在 DisplayClient 的 handler（主线程）上到，仍统一 post 一次再改状态，避免
     * 以后换成别的线程时静默出错。
     */
    private val displayKeyListener = object : Display.OnInputEventListener {
        override fun onInputEvent(inputEvent: BrailleInputEvent) {
            runOnUiThread { onDisplayKey(inputEvent.command) }
        }
    }

    /** 阅读流：段落以 '\n' 分隔，空行已折叠。 */
    private var mStream = ""
    /** 当前阅读位置（字符偏移）。 */
    private var mOffset = 0
    private var mConnected = false
    private var mFileUri: Uri? = null

    /** Compose 界面状态与弹窗。 */
    private var uiState by mutableStateOf(ReaderUiState())
    private var readerDialog by mutableStateOf<ReaderDialog?>(null)

    /** 翻译服务（明眼 txt 电子书 → 盲文）。懒初始化：绑定 service 模块的翻译服务。 */
    private val mTranslatorManager by lazy { TranslatorManager(this) }

    /** 正在翻译标志，防止重复触发。 */
    private var mTranslating = false

    /** 用户在翻译未完成时点了导出：翻完自动把导出弹窗接着打开。 */
    private var mPendingExport = false

    /** 翻译服务超时未就绪只播报一次，避免每本书都重复吵。 */
    private var mWarnedTranslatorUnavailable = false

    /** 当前流是否来自明眼 txt 翻译。翻译流与屏幕尺寸无关，重排时无需重翻。 */
    private var mIsTranslated = false

    /** 逐格源区间（仅翻译流有）；点位 JSON 导出与屏幕取字共用。 */
    private var mCellSpans: List<CellSource>? = null

    /** TEXT 源原始明文；盲文源为空。屏幕回显原文用，不参与点显/导出。 */
    private var mSourceText = ""
    /** 当前屏显来源类型：TEXT 回显原文，其余回显点字。 */
    private var mSourceKind = ReaderSourceKind.BRAILLE

    /** 最近一屏视图模型结果，供页码行与无障碍播报取用。 */
    private var mLastView: ReaderPageView? = null

    /** 已播报过的屏序号，避免逐行移动重复播报。 */
    private var mAnnouncedUnit = -1

    /** 翻译服务未就绪时挂起的翻译请求。 */
    private var mPendingTranslation: PendingTranslation? = null

    private val mTranslationReadyListener: TranslatorManager.OnTablesChangedListener =
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
    private var mCellsPerLine = DEFAULT_CELLS
    /** 屏幕每页显示的行数，由屏幕高度实测得出。 */
    private var mLinesPerPage = LINES_PER_PAGE
    /** 点显器每行方数，连接后由 getDisplayProperties() 更新；未连接用默认值。 */
    private var mDisplayCells = DEFAULT_CELLS
    /** 上次读到、但因后台翻译还没翻到而暂时落不了地的位置。 */
    private var mPendingRestoreOffset: Int? = null

    /**
     * 每一屏的起始偏移。
     *
     * 采用边界对齐步进（[BrailleFileDecoder.nextOffset]）后步进长度不再固定，
     * 页码无法再用「偏移 / 单元」换算，故预先算好页表（见 [rebuildPageOffsets]）。
     */
    private var mPageOffsets: IntArray = intArrayOf()

    /**
     * 阅读单元：一「页」是多少方。
     *
     * 取**手机屏幕一屏**（每行方数 × 行数），不取点显器一屏：点显器常见 20/30/40 方，
     * 若按点显器一屏当一页，一本 300 万字的书会拆成十几万页——屏幕上的
     * 「上一页/下一页」每按一次只挪二十几个字，看着就像没反应；目录也会变成上万条
     * 「第 1-10 页」。点显器自己那一屏由按键按方数步进（见 [moveLine]），
     * 两者各管一层，互不干扰。
     */
    private fun readingUnit(): Int =
        (mCellsPerLine * mLinesPerPage).coerceAtLeast(1)

    private fun totalUnits(): Int = mPageOffsets.size.coerceAtLeast(1)

    /** 当前屏序号：页表中不超过 [mOffset] 的最后一个页起点。 */
    private fun currentUnit(): Int = unitForOffset(mOffset)

    /** 页表中不超过 [offset] 的最后一个页起点序号（屏号）。 */
    private fun unitForOffset(offset: Int): Int {
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

    private fun maxOffset(): Int = (mStream.length - 1).coerceAtLeast(0)

    // ------------------------------------------------------------------
    // 导出（SAF + 后台写盘）
    // ------------------------------------------------------------------

    /** 导出防重入；编码与写盘都在后台线程，主线程只做弹窗/按钮态/播报。 */
    private var mExporting = false
    private var pendingFormat: BrailleExportFormat? = null

    /**
     * 点显器上报的原始方数（未连接或上报异常时为 null）。
     * 和 [mDisplayCells] 的区别：mDisplayCells 会在异常时兜底成默认值，
     * 导出排版不能把「兜底的 40」当成设备真方数，所以这里单独留一份可空的原值。
     */
    private var mReportedCells: Int? = null

    /** 版面预设：4 档方数 × 3 档行数，顺序与版面菜单项一致。 */
    private val geometryPresets: List<PageGeometry> =
        PageGeometry.PRESET_COLUMNS.flatMap { columns ->
            PageGeometry.PRESET_LINES.map { lines -> PageGeometry(columns, lines) }
        }

    /** BRF 按 octet-stream 落盘；BRL / 文本按 text/plain。 */
    private val brfExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        onExportUri(uri)
    }
    private val textExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        onExportUri(uri)
    }
    private val jsonExportLauncher = registerForActivityResult(
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

    private fun exportFormatLabelRes(format: BrailleExportFormat): Int = when (format) {
        BrailleExportFormat.BRF -> R.string.braille_reader_export_fmt_brf
        BrailleExportFormat.BRL -> R.string.braille_reader_export_fmt_brl
        BrailleExportFormat.UNICODE_TXT -> R.string.braille_reader_export_fmt_utf
        BrailleExportFormat.JSON -> R.string.braille_reader_export_fmt_json
    }

    private fun exportFormatDescRes(format: BrailleExportFormat): Int = when (format) {
        BrailleExportFormat.BRF -> R.string.braille_reader_export_fmt_brf_desc
        BrailleExportFormat.BRL -> R.string.braille_reader_export_fmt_brl_desc
        BrailleExportFormat.UNICODE_TXT -> R.string.braille_reader_export_fmt_utf_desc
        BrailleExportFormat.JSON -> R.string.braille_reader_export_fmt_json_desc
    }

    private fun onExportPicked(index: Int) {
        val format = ExportFormatCatalog.formatAt(index)
        pendingFormat = format
        showGeometryDialog()
    }

    /** 当前可用作排版的版面项：连接点显器时第一项是「按点显器方数」。 */
    private fun geometryChoices(): List<GeometryChoice> = ExportGeometryCatalog.choices(
        reportedCells = mReportedCells,
        fixedColumns = PageGeometry.PRESET_COLUMNS,
        fixedLines = PageGeometry.PRESET_LINES,
        linesForFollow = loadGeometry().linesPerPage,
        wordWrap = wordWrapEnabled(),
    )

    private fun showGeometryDialog() {
        val choices = geometryChoices()
        val labels = choices.map { choice ->
            when (choice) {
                is GeometryChoice.FollowDisplay -> getString(
                    R.string.braille_reader_export_geometry_follow,
                    choice.cells, choice.lines)
                is GeometryChoice.Fixed -> getString(
                    R.string.braille_reader_export_geometry_item,
                    choice.columns, choice.lines)
                GeometryChoice.Custom ->
                    getString(R.string.braille_reader_export_geometry_custom)
                is GeometryChoice.WrapToggle -> getString(
                    if (choice.wordWrap) R.string.braille_reader_export_wrap_word
                    else R.string.braille_reader_export_wrap_cell)
            }
        }
        val reported = ExportGeometryCatalog.followCells(mReportedCells)
        val hint = if (reported != null) {
            getString(R.string.braille_reader_export_geometry_hint_connected, reported)
        } else {
            getString(R.string.braille_reader_export_geometry_hint_offline)
        }
        readerDialog = ReaderDialog.ExportGeometry(
            getString(R.string.braille_reader_export_geometry_title), labels, hint)
    }

    private fun onGeometryPicked(which: Int) {
        when (val choice = geometryChoices().getOrNull(which)) {
            is GeometryChoice.FollowDisplay -> {
                readerDialog = null
                saveFollowDisplay(true)
                startExport(pendingFormat ?: BrailleExportFormat.BRF)
            }
            is GeometryChoice.Fixed -> {
                readerDialog = null
                saveFollowDisplay(false)
                saveGeometry(PageGeometry(choice.columns, choice.lines))
                startExport(pendingFormat ?: BrailleExportFormat.BRF)
            }
            GeometryChoice.Custom, null -> {
                saveFollowDisplay(false)
                readerDialog = ReaderDialog.ExportCustomGeometry(
                    getString(R.string.braille_reader_export_geometry_title))
            }
            is GeometryChoice.WrapToggle -> {
                // 折行开关不导出、不关弹窗：切完立刻把弹窗按新状态重建，
                // 让用户马上听到「现在是按词断/按方数」，再决定选哪一档版面。
                val next = !choice.wordWrap
                saveWordWrap(next)
                announce(getString(
                    if (next) R.string.braille_reader_export_wrap_word_announce
                    else R.string.braille_reader_export_wrap_cell_announce))
                showGeometryDialog()
            }
        }
    }

    private fun onCustomGeometryConfirmed(columns: Int, lines: Int) {
        val geometry = PageGeometry(
            columns.coerceIn(EXPORT_MIN_COLUMNS, EXPORT_MAX_COLUMNS),
            lines.coerceIn(EXPORT_MIN_LINES, EXPORT_MAX_LINES),
        )
        saveGeometry(geometry)
        saveFollowDisplay(false)
        readerDialog = null
        startExport(pendingFormat ?: BrailleExportFormat.BRF)
    }

    private fun loadGeometry(): PageGeometry {
        val prefs = SettingsStore.get(this)
        val columns = prefs.getInt(KEY_EXPORT_COLUMNS, PageGeometry.Default.columns)
        val lines = prefs.getInt(KEY_EXPORT_LINES, PageGeometry.Default.linesPerPage)
        return PageGeometry(
            columns.coerceIn(EXPORT_MIN_COLUMNS, EXPORT_MAX_COLUMNS),
            lines.coerceIn(EXPORT_MIN_LINES, EXPORT_MAX_LINES),
        )
    }

    private fun saveGeometry(geometry: PageGeometry) {
        SettingsStore.get(this).edit()
            .putInt(KEY_EXPORT_COLUMNS, geometry.columns)
            .putInt(KEY_EXPORT_LINES, geometry.linesPerPage)
            .apply()
    }

    /** 导出排版是否跟随点显器方数；默认开启，但只在有可用设备方数时才生效。 */
    private fun followDisplayEnabled(): Boolean =
        SettingsStore.get(this).getBoolean(KEY_EXPORT_FOLLOW_DISPLAY, true)

    private fun saveFollowDisplay(enabled: Boolean) {
        SettingsStore.get(this).edit()
            .putBoolean(KEY_EXPORT_FOLLOW_DISPLAY, enabled)
            .apply()
    }

    /** 导出折行是否按词断；默认开启，关掉就是改造前的纯方数硬切。 */
    private fun wordWrapEnabled(): Boolean =
        SettingsStore.get(this).getBoolean(KEY_EXPORT_WORD_WRAP, true)

    private fun saveWordWrap(enabled: Boolean) {
        SettingsStore.get(this).edit()
            .putBoolean(KEY_EXPORT_WORD_WRAP, enabled)
            .apply()
    }

    /** 真正用于导出/播报的版面：跟随开启且有设备方数时按设备方数折行。 */
    private fun resolvedGeometry(): PageGeometry = ExportGeometryCatalog.resolve(
        stored = loadGeometry(),
        followEnabled = followDisplayEnabled(),
        reportedCells = mReportedCells,
    )

    private fun startExport(format: BrailleExportFormat) {
        if (mExporting || isFinishing) return
        mExporting = true
        pendingFormat = format
        val geometry = resolvedGeometry()
        val pages = BrailleExporter.pageCount(mStream, geometry)
        announce(getString(R.string.braille_reader_export_geometry_preview,
            geometry.columns, geometry.linesPerPage, pages))
        Toast.makeText(this, R.string.braille_reader_export_preparing,
            Toast.LENGTH_SHORT).show()

        val base = BrailleExporter.sanitizeFileName(currentBookName())
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault())
            .format(Date())
        val initialName = "$base$stamp.${format.extension}"
        when (format) {
            BrailleExportFormat.BRF -> brfExportLauncher.launch(initialName)
            BrailleExportFormat.BRL,
            BrailleExportFormat.UNICODE_TXT -> textExportLauncher.launch(initialName)
            BrailleExportFormat.JSON -> jsonExportLauncher.launch(initialName)
        }
    }

    private fun onExportUri(uri: Uri?) {
        if (isFinishing) {
            mExporting = false
            return
        }
        if (uri == null) {
            mExporting = false
            announce(getString(R.string.braille_reader_export_cancelled))
            Toast.makeText(this, R.string.braille_reader_export_cancelled,
                Toast.LENGTH_SHORT).show()
            return
        }
        val format = pendingFormat ?: BrailleExportFormat.BRF
        // 后台线程不碰 Activity 状态：先把版面定死再进线程，
        // 保证「播报的页数」和「写进文件的行列」是同一个版面。
        val geometry = resolvedGeometry()
        // 折行方式同样在主线程定死，后台线程只按参数编码。
        val wrapMode = if (wordWrapEnabled()) BrailleWrapMode.WORD else BrailleWrapMode.CELL
        Thread {
            val result = try {
                BrailleExporter.export(mStream, format, geometry,
                    currentBookName(), mCellSpans, wrapMode)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "导出编码失败: ${e.message}")
                null
            }
            if (result == null) {
                runOnUiThread { exportFail() }
                return@Thread
            }
            try {
                contentResolver.openOutputStream(uri)?.use { out ->
                    val bytes = if (format == BrailleExportFormat.BRF) {
                        result.content.toByteArray(StandardCharsets.US_ASCII)
                    } else {
                        result.content.toByteArray(StandardCharsets.UTF_8)
                    }
                    out.write(bytes)
                } ?: throw IOException("openOutputStream returned null")
                runOnUiThread { exportSuccess(uri, result) }
            } catch (e: Exception) {
                Log.w(LOG_TAG, "导出写盘失败: ${e.message}")
                runOnUiThread { exportFail() }
            }
        }.start()
    }

    private fun exportSuccess(uri: Uri, result: BrailleExportResult) {
        mExporting = false
        val warnings = buildList {
            if (result.unmappableCells > 0) {
                add(getString(R.string.braille_reader_export_warn_unmappable,
                    result.unmappableCells))
            }
            if (result.droppedChars > 0) {
                add(getString(R.string.braille_reader_export_warn_dropped,
                    result.droppedChars))
            }
            if (result.eightDotCells > 0) {
                add(getString(R.string.braille_reader_export_warn_eightdot,
                    result.eightDotCells))
            }
        }
        val fileName = uri.lastPathSegment ?: ""
        val message = buildString {
            append(getString(R.string.braille_reader_export_done, fileName))
            if (warnings.isNotEmpty()) {
                append("；").append(warnings.joinToString("；"))
            }
        }
        announce(message)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun exportFail() {
        mExporting = false
        announce(getString(R.string.braille_reader_export_fail))
        Toast.makeText(this, R.string.braille_reader_export_fail,
            Toast.LENGTH_LONG).show()
    }

    private fun currentBookName(): String {
        val fromResolver = mFileUri?.let { displayNameOf(it) }
        return fromResolver ?: mFileUri?.lastPathSegment ?: "book"
    }

    private fun announce(msg: CharSequence) {
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
                labels = ReaderLabels(
                    prev = getString(R.string.braille_reader_prev),
                    next = getString(R.string.braille_reader_next),
                    toc = getString(R.string.braille_reader_toc),
                    bookmark = getString(R.string.braille_reader_bookmark),
                    goto = getString(R.string.braille_reader_goto),
                    export = getString(R.string.braille_reader_export),
                    fontSize = getString(R.string.braille_reader_font_size),
                    navHint = getString(R.string.braille_reader_nav_hint),
                    ok = getString(android.R.string.ok),
                    cancel = getString(android.R.string.cancel),
                    delete = getString(R.string.braille_reader_bm_delete),
                    clearAll = getString(R.string.braille_reader_bm_clear_all),
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
            )
        }

        // 书架/最近打开跳转而来：直接加载指定文件
        intent.getStringExtra(EXTRA_URI)?.let { uriStr ->
            try {
                val uri = Uri.parse(uriStr)
                mFileUri = uri
                // 同一本书续读上次进度；换了一本书从第 1 页开始。
                // 否则盲人用户从书架重开书永远回到第 1 页，长书重新定位成本高。
                val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                val savedOffset = if (prefs.getString(KEY_URI, null) == uriStr) {
                    prefs.getInt(KEY_OFFSET, 0)
                } else 0
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
        saveProgress()
        // 离开正文页就交还点显器，否则服务一直冻着，别的界面摸不到字。
        releaseDisplay()
        mDisplay?.shutdown()
        mDisplay = null
        mConnected = false
    }

    override fun onDestroy() {
        super.onDestroy()
        mTranslatorManager.removeOnTablesChangedListener(mTranslationReadyListener)
        mTranslatorManager.shutdown()
    }

    override fun onConnectionStateChanged(state: Int) {
        val wasConnected = mConnected
        val oldDisplayCells = mDisplayCells
        mConnected = state == Display.STATE_CONNECTED
        if (mConnected) {
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
            // 断开后冻结没有意义，清掉独占标记；重连时正文在屏会重新申请。
            holdingDisplay = false
        }
        // 阅读单元随连接状态/方数变化：连接后按点显器一屏步进、断开后回屏幕一屏。
        // 页表步进单位变了必须重建，但只换「翻页的格子」，不移动当前阅读位置。
        if ((mConnected != wasConnected || mDisplayCells != oldDisplayCells)
                && mStream.isNotEmpty()) {
            rebuildPageOffsets(keepPosition = true)
        }
        runOnUiThread {
            updateUiState()
            // 连上点显器立即推送当前屏
            if (mConnected && mStream.isNotEmpty()) {
                sendToDisplay()
            }
        }
    }

    // ------------------------------------------------------------------
    // 文件选择 / 读取
    // ------------------------------------------------------------------

    private fun loadFile(uri: Uri, targetOffset: Int? = null) {
        try {
            loadBytes(uri, resetPosition = true, targetOffset = targetOffset)
        } catch (e: Exception) {
            Toast.makeText(this,
                getString(R.string.braille_reader_load_fail, e.message),
                Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 读取并解码文件，重建阅读流。
     *
     * 按文件名后缀分派：`.bdo`/`.bdoc` 走二进制结构化解码，其余按文本解码。
     * 解码后再做内容类型检测：已是盲文点位/ASCII 盲文的直接进入阅读流；
     * 明眼文字（txt 电子书）则进入**翻译模式**——后台线程调用翻译服务
     * 转成盲文点显（屏幕与点显器均显示盲文，与原书排版一致）。
     *
     * @param targetOffset 非 null 时流就绪后直接跳到该偏移（用于续读）；
     *   null 则按 [resetPosition] 决定从 0 还是按比例保持。
     */
    private fun loadBytes(uri: Uri, resetPosition: Boolean, targetOffset: Int? = null) {
        // 读文件 + 解码是 IO/CPU 密集操作（txt/bdo 可能数 MB），放在后台线程，
        // 避免阻塞主线程导致打开大书时卡顿/ANR；UI 更新（startTranslation /
        // setBrailleStream）回到主线程执行。失败时在主线程 Toast 提示。
        Thread {
            val decoded: Pair<String, String?>? = try {
                val name = displayNameOf(uri)
                checkSupportedFormat(name)
                val bytes = readAllBytes(uri)
                // EPUB/HTML/Markdown/Word 是容器或标记文本，先提取成明眼文字，
                // 再交给下面的内容类型检测走翻译链路。
                val text = if (EbookTextExtractor.supports(name)) {
                    when (val extracted = EbookTextExtractor.extract(bytes, name)) {
                        is EbookTextExtractor.Result.Success -> extracted.text
                        is EbookTextExtractor.Result.Failure ->
                            throw IllegalStateException(ebookFailureMessage(extracted))
                    }
                } else {
                    when (val document =
                            BrailleFileDecoder.decodeDocument(bytes, name)) {
                        is BrailleFileDecoder.DecodedDocument.Decoded -> document.text
                        is BrailleFileDecoder.DecodedDocument.Failure ->
                            throw IllegalStateException(loadFailureMessage(document))
                    }
                }
                text to name
            } catch (e: Exception) {
                if (!isFinishing) {
                    runOnUiThread {
                        Toast.makeText(this,
                            getString(R.string.braille_reader_load_fail, e.message),
                            Toast.LENGTH_SHORT).show()
                    }
                }
                null
            }
            if (decoded == null || isFinishing) {
                return@Thread
            }
            val text = decoded.first
            val name = decoded.second
            Log.d(LOG_TAG, "load done: name=$name chars=${text.length}")
            runOnUiThread {
                when (BrailleFileDecoder.detectKind(text)) {
                    BrailleFileDecoder.ContentKind.TEXT -> {
                        mSourceText = text
                        mSourceKind = ReaderSourceKind.TEXT
                        startTranslation(text, name, uri, resetPosition, targetOffset)
                    }
                    BrailleFileDecoder.ContentKind.ASCII -> {
                        mSourceText = ""
                        mSourceKind = ReaderSourceKind.ASCII
                        setBrailleStream(text, name, uri, resetPosition, targetOffset)
                    }
                    BrailleFileDecoder.ContentKind.BRAILLE -> {
                        mSourceText = ""
                        mSourceKind = ReaderSourceKind.BRAILLE
                        setBrailleStream(text, name, uri, resetPosition, targetOffset)
                    }
                }
            }
        }.start()
    }

    /**
     * 解码失败的具体原因，翻成一句用户能听懂的话。
     *
     * 盲人用户看不到界面，只能听到这一句：必须说清是「文件空」「不是 bdo」
     * 还是「内容太少」，而不是统一的「不是有效的盲文文档」。
     */
    private fun loadFailureMessage(
        failure: BrailleFileDecoder.DecodedDocument.Failure
    ): String = when (failure) {
        BrailleFileDecoder.DecodedDocument.Failure.EmptyFile ->
            getString(R.string.braille_reader_err_empty)
        BrailleFileDecoder.DecodedDocument.Failure.NotBwordArchive ->
            getString(R.string.braille_reader_err_not_bword)
        is BrailleFileDecoder.DecodedDocument.Failure.TooFewBrailleCells ->
            getString(R.string.braille_reader_err_too_few, failure.cellCount)
    }

    /**
     * 电子书容器提取失败的原因。和 bdo 的失败提示分开，用户听到的是
     * 「不是 EPUB」「只有图片」这种能据此行动的话。
     */
    private fun ebookFailureMessage(
        failure: EbookTextExtractor.Result.Failure
    ): String = when (failure) {
        EbookTextExtractor.Result.Failure.EmptyFile ->
            getString(R.string.braille_reader_err_empty)
        EbookTextExtractor.Result.Failure.NotEpub ->
            getString(R.string.braille_reader_err_epub_invalid)
        EbookTextExtractor.Result.Failure.NotWord ->
            getString(R.string.braille_reader_err_word_invalid)
        EbookTextExtractor.Result.Failure.NoText ->
            getString(R.string.braille_reader_err_no_text)
        EbookTextExtractor.Result.Failure.TooLarge ->
            getString(R.string.braille_reader_err_too_large)
    }

    /**
     * 设置盲文阅读流并完成收尾：记录最近打开、重建页表、刷新屏幕与点显器。
     * 翻译模式翻译完成后也走这里，与非翻译路径完全一致。
     */
    private fun setBrailleStream(
        brailleText: String,
        name: String?,
        uri: Uri,
        resetPosition: Boolean,
        targetOffset: Int? = null,
        isTranslated: Boolean = false,
        cellSpans: List<CellSource>? = null,
        notifyLoaded: Boolean = true,
    ) {
        val oldLength = mStream.length
        mStream = BrailleFileDecoder.toReadingStream(brailleText)
        mIsTranslated = isTranslated
        mCellSpans = cellSpans
        // 每进一次流记一条：同一本书若出现两条以上，说明真的重复解码/翻译了。
        Log.d(LOG_TAG, "stream set: len=${mStream.length} translated=$isTranslated " +
            "reset=$resetPosition target=$targetOffset")
        // 记录最近打开（书架「最近」列表数据源），解码/翻译成功才算真正打开
        BookshelfActivity.recordRecent(this, uri.toString(),
            name ?: uri.lastPathSegment ?: "file")
        if (targetOffset != null) {
            mOffset = targetOffset.coerceIn(0, maxOffset())
        } else if (resetPosition) {
            mOffset = 0
        } else if (oldLength > 0) {
            // 屏幕尺寸或点显器方数变化后按位置比例还原，保持阅读进度
            mOffset = (mOffset * mStream.length / oldLength).coerceIn(0, maxOffset())
        }
        rebuildPageOffsets()
        showPage()
        if (notifyLoaded) {
            Toast.makeText(this, getString(R.string.braille_reader_loaded,
                totalUnits()), Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------
    // 翻译模式：明眼 txt 电子书 -> 盲文
    // ------------------------------------------------------------------

    /**
     * 启动翻译。翻译服务（TranslatorService）是异步绑定的，首次打开 txt
     * 时可能尚未就绪；未就绪则挂起请求，服务就绪后由 [mTranslationReadyListener]
     * 自动继续。
     */
    private fun startTranslation(
        text: String,
        name: String?,
        uri: Uri,
        resetPosition: Boolean,
        targetOffset: Int? = null
    ) {
        if (mTranslating) return
        val translator = mTranslatorManager.getTranslator()
        if (translator == null) {
            mPendingTranslation = PendingTranslation(text, name, uri, resetPosition, targetOffset)
            mTranslatorManager.addOnTablesChangedListener(mTranslationReadyListener)
            // 翻译服务没就绪也不能让用户对着空屏干等：先把原文铺进阅读流，
            // 至少能听到正文、能翻页、能进目录；服务就绪后自动切成盲文流。
            setBrailleStream(text, name, uri, resetPosition, targetOffset,
                isTranslated = false)
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
        Log.d(LOG_TAG, "translate start: chars=${text.length}")
        uiState = uiState.copy(
            readerText = getString(R.string.braille_reader_translating))
        warnIfTableMismatch(text, translator)
        // 后台翻译要「边翻边能读」：进度挂在字段里，翻出第一块就先上屏，
        // 之后按间隔续块，用户不用干等整本（3MB 书整本约一分钟）。
        mPendingRestoreOffset = targetOffset
        Thread {
            translateParagraphs(text, translator) { chunk ->
                runOnUiThread {
                    applyTranslatedChunk(chunk, name, uri, resetPosition, targetOffset)
                }
            }
        }.start()
    }

    /**
     * 应用一块翻译结果。第一块整块铺流（分配位置、记最近打开），之后只往末尾追：
     * 追加不改变已有偏移，所以用户正读的那一屏不会被拽走。
     */
    private fun applyTranslatedChunk(
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
                notifyLoaded = chunk.done)
            if (!chunk.done) {
                // 先报一句「已经能读了、后面还在翻」，免得用户以为就这些。
                announce(getString(R.string.braille_reader_translate_partial,
                    totalUnits()))
            }
        } else if (chunk.text.isNotEmpty()) {
            appendBrailleStream(chunk.text, chunk.cellPositions)
        }
        restorePendingOffsetIfReached()
        if (chunk.done) {
            mTranslating = false
            val msg = if (chunk.failedAtParagraph > 0) {
                getString(R.string.braille_reader_translate_partial_stop,
                    chunk.failedAtParagraph, totalUnits())
            } else {
                getString(R.string.braille_reader_translate_done, totalUnits())
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

    /**
     * 只往末尾追加一块翻译流。
     *
     * 追加不改动原有偏移，故页表按 keepPosition 重建；屏与点显器按当前位置刷新。
     */
    private fun appendBrailleStream(deltaText: String, deltaCells: IntArray) {
        val before = mStream.length
        val table = mCellSpans as? CellSourceTable
        if (table != null && table.sourceText == mSourceText) {
            // 表对象不换，只往尾部追加位置：屏显按格取字依赖的正是这一个表。
            table.appendAll(deltaCells)
        } else {
            mCellSpans = CellSourceTable(mSourceText, deltaCells)
        }
        mStream += deltaText
        rebuildPageOffsets(keepPosition = true)
        showPage()
        Log.d(LOG_TAG, "append: +${deltaText.length} stream=$before->${mStream.length} " +
            "cells=${mCellSpans?.size} units=${totalUnits()} pos=$mOffset")
    }

    /**
     * 上次读到的位置可能还没翻到：记住目标偏移，等流长够了再一次性回去，
     * 免得刚开书就停在「还没翻完的部分末尾」。
     */
    private fun restorePendingOffsetIfReached() {
        val target = mPendingRestoreOffset ?: return
        if (mStream.length <= target) return
        Log.d(LOG_TAG, "restore pending: target=$target stream=${mStream.length}")
        mPendingRestoreOffset = null
        mOffset = target.coerceIn(0, maxOffset())
        rebuildPageOffsets(keepPosition = true)
        mOffset = target.coerceIn(0, maxOffset())
        showPage()
        Log.d(LOG_TAG, "restore applied: pos=$mOffset unit=${currentUnit()} units=${totalUnits()}")
        announce(getString(R.string.braille_reader_restored, currentUnit() + 1, totalUnits()))
    }

    /**
     * 内容语言与盲文表不匹配检测：中文/日文/韩文内容配非 CJK 表时，
     * liblouis 对表内无定义的字符会输出 \xNNNN/ 转义，屏幕成串乱码且
     * 无任何提示（与 S7 分词降级同款静默降级）。这里给出明确提示；
     * 是否切换盲文表属用户决策，不擅自改设置。
     */
    private fun warnIfTableMismatch(text: String, translator: BrailleTranslator) {
        if (!CJK_CONTENT.containsMatchIn(text)) return
        val tableLang = translator.tableInfo?.locale?.language
        if (tableLang in CJK_TABLE_LANGUAGES) return
        Toast.makeText(this,
            getString(R.string.braille_reader_table_mismatch,
                translator.tableInfo?.locale?.displayLanguage ?: "?"),
            Toast.LENGTH_LONG).show()
        Log.w(LOG_TAG, "表不匹配：CJK 内容配 $tableLang 表，翻译可能输出转义乱码")
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
    private data class TranslatedChunk(
        val text: String,
        val cellPositions: IntArray,
        val done: Boolean,
        val failedAtParagraph: Int,
        val paragraphs: Int,
    )

    /**
     * 逐段翻译，边翻边交块：每满 [PUBLISH_INTERVAL_MS] 毫秒（或第一块攒够
     * [FIRST_PUBLISH_CHARS] 字）就把当前成果交出去上屏，用户在后台翻译还在跑时
     * 就能开始读；收尾再交最后一块。
     *
     * 段落起点记在全书坐标系里，跨段不串位；逐格源下标进 int 缓冲，
     * 不用 List<CellSource>（378 万格逐格建对象会把 256MB 堆吃穿）。
     * 某段翻译失败时不再整本回退，而是把已经翻好的部分交出去并说明停在哪。
     */
    private fun translateParagraphs(
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
                while (res == null && attempt < TRANSLATE_ATTEMPTS) {
                    Log.w(LOG_TAG, "translate retry #$attempt at paragraph #$index len=${paragraph.length}")
                    SystemClock.sleep(TRANSLATE_RETRY_DELAY_MS)
                    res = translator.translate(paragraph, -1, false)
                    attempt++
                }
                if (res == null) {
                    Log.w(LOG_TAG, "translate null at paragraph #$index len=${paragraph.length} " +
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
                // 一次至少交够 MIN_PUBLISH_CHARS，别把一段话当一页先推上去：
                // 冷启动等分词器的那几秒会把「2 秒交一块」的时钟提前打满。
                if (sb.length >= MIN_PUBLISH_CHARS &&
                    ((publishedChars == 0 && sb.length >= FIRST_PUBLISH_CHARS) ||
                        sincePublish >= PUBLISH_INTERVAL_MS)) {
                    publish(false)
                }
            }
        } catch (t: Throwable) {
            // 包括 OutOfMemoryError：别让翻译线程把进程带走，已翻好的部分照样交出去。
            Log.w(LOG_TAG, "翻译中断: ${t.message}")
            if (failedAt == 0) failedAt = index + 1
        }

        Log.d(LOG_TAG, "translate end: paragraphs=$index chars=${sb.length} " +
            "invalidCells=$invalidCells failedAt=$failedAt " +
            "elapsed=${System.currentTimeMillis() - startedAt}ms")
        publish(true)
    }

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
    private fun rebuildPageOffsets(keepPosition: Boolean = false) {
        val keep = keepPosition && mStream.isNotEmpty()
            && mOffset >= 0 && mOffset <= maxOffset()
        mPageOffsets = if (mStream.isEmpty()) {
            intArrayOf()
        } else {
            BrailleFileDecoder.pageOffsets(mStream, readingUnit())
        }
        if (mPageOffsets.isNotEmpty() && !keep) {
            mOffset = mPageOffsets[currentUnit()]
        }
    }

    /**
     * 拒绝明确不支持的格式，避免用户打开 Word/PDF 等得到一堆乱码却以为是 bug。
     * 未列出的后缀（含无后缀）一律放行，按文本尝试解码。
     */
    private fun checkSupportedFormat(name: String?) {
        val ext = name?.substringAfterLast('.', "")?.lowercase() ?: return
        if (ext == "doc") {
            // 97-2003 是 OLE 复合文档，不是 zip，解不出正文；给出可执行的下一步。
            throw IllegalStateException(getString(R.string.braille_reader_err_doc_legacy))
        }
        if (ext in UNSUPPORTED_EXT) {
            throw IllegalStateException(
                getString(R.string.braille_reader_unsupported_format, ext))
        }
    }

    /** 取文件显示名（用于按后缀判断格式）；查询失败时回落到 URI 末段。 */
    private fun displayNameOf(uri: Uri): String? {
        try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) {
                    return cursor.getString(idx)
                }
            }
        } catch (e: Exception) {
            // 部分 provider 不支持查询，回落到 URI 末段
        }
        return uri.lastPathSegment
    }

    private fun readAllBytes(uri: Uri): ByteArray {
        contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
        throw IllegalStateException("cannot open $uri")
    }

    /** 翻译流的段落结构已固定，与屏幕尺寸无关：只需按新阅读单元重建页表。 */
    private fun reloadKeepingPosition() {
        val uri = mFileUri ?: return
        if (mIsTranslated) {
            rebuildPageOffsets(keepPosition = true)
            showPage()
            return
        }
        try {
            loadBytes(uri, resetPosition = false)
        } catch (e: Exception) {
            // 重读失败保留原有内容
        }
    }

    // ------------------------------------------------------------------
    // 翻页 / 显示
    // ------------------------------------------------------------------

    /** 前进/后退 [delta] 屏。 */
    private fun advance(delta: Int) {
        if (mPageOffsets.isEmpty()) return
        gotoUnit(currentUnit() + delta)
    }

    /** 跳到第 [unit] 屏（0 基）。起点取自页表，故不会把字切成两半。 */
    private fun gotoUnit(unit: Int) {
        if (mPageOffsets.isEmpty()) return
        mOffset = mPageOffsets[unit.coerceIn(0, mPageOffsets.size - 1)]
        showPage()
    }

    /**
     * 上一行/下一行（长按翻页键触发）。
     *
     * 翻页是一整屏（多行），逐行是微调：一次前进一行。行步进——
     * 连接点显器时按**点显器一屏**（单行点显器的「一行」就是它的整屏），
     * 未连接时按**屏幕一行**。两种操作经 [showPage] 都会自动同步点显器。
     */
    private fun moveLine(delta: Int) {
        if (mStream.isEmpty()) return
        val step = if (mConnected && mDisplayCells > 0) mDisplayCells else mCellsPerLine
        mOffset = (mOffset + delta * step).coerceIn(0, maxOffset())
        showPage()
    }

    /**
     * 点显器按键的逐行移动：到文首 / 文末时 [moveLine] 会原地钳住，不播报的话用户会以为
     * 按键失灵（两键设备尤其明显，因为它只有这一个前进方向）。
     */
    private fun moveLineFromKey(delta: Int) {
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
    private fun onDisplayKey(command: Int) {
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
                announce(listOf(uiState.pageInfo, firstLine)
                    .filter { it.isNotEmpty() }.joinToString("，"))
            }
            ReaderDisplayAction.NONE -> return
        }
    }

    private fun showPage() {
        val brailleText = if (mStream.isEmpty()) {
            getString(R.string.braille_reader_hint)
        } else {
            BrailleFileDecoder.screenAt(
                mStream, mOffset, mCellsPerLine, mLinesPerPage)
        }
        val count = mCellsPerLine * mLinesPerPage
        val view = ReaderSourceModel.build(
            kind = mSourceKind,
            sourceText = mSourceText,
            brailleScreenText = brailleText,
            spans = mCellSpans,
            offset = mOffset,
            count = count,
            currentUnit = currentUnit(),
            totalUnits = totalUnits(),
            stream = mCellSpans?.let { mStream },
        )
        mLastView = view
        uiState = uiState.copy(
            readerText = view.displayText,
            focusSegments = view.focusSegments,
        )
        // 翻页后自动同步点显器，省去盲文用户每次手动点「发送点显」
        if (mConnected) {
            sendToDisplay()
        }
        updateUiState()
        val unit = currentUnit()
        if (unit != mAnnouncedUnit) {
            mAnnouncedUnit = unit
            val firstLine = mLastView?.focusSegments?.firstOrNull().orEmpty()
            announce(listOf(uiState.pageInfo, firstLine)
                .filter { it.isNotEmpty() }.joinToString("，"))
        }
    }

    private fun updateUiState() {
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
            val base = getString(R.string.braille_reader_page,
                currentUnit() + 1, totalUnits())
            val percent = view?.let {
                getString(R.string.braille_reader_page_percent, it.percent)
            }.orEmpty()
            val sourcePos = if (mSourceKind == ReaderSourceKind.TEXT &&
                view != null && view.paragraphCount > 0
            ) {
                getString(R.string.braille_reader_source_position,
                    view.paragraphIndex + 1, view.paragraphCount)
            } else ""
            listOf(base, percent, sourcePos).filter { it.isNotEmpty() }
                .joinToString(" · ")
        }
        val rawFont = SettingsStore.get(this)
            .getInt(KEY_READER_FONT_SP, DEFAULT_READER_FONT_SP)
        val fontSp = FONT_SIZES.firstOrNull { it == rawFont } ?: DEFAULT_READER_FONT_SP
        uiState = uiState.copy(
            pageInfo = pageInfo,
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

    // ------------------------------------------------------------------
    // 目录 / 书签 / 跳页
    // ------------------------------------------------------------------

    /**
     * 目录：优先按书自己的章节标题（「第X章」「序言」「尾声」…）列，识别不到才按页分组。
     *
     * 早先一律按 10 页一组：一本 300 万字的书页数上万，目录变成上万条「第 1-10 页」，
     * 听下来全是页码，没有任何能用来找内容的线索。章节标题是书自己给的结构，
     * 出发点是「第几章 什么题目」，比页码有用得多。
     */
    private fun showToc() {
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
                    Log.d(LOG_TAG, "toc: chapters=${chapters.size} entries")
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
    private fun mapChaptersToStream(
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

    /** 兜底目录：按页分组，条目数控制在 [TOC_MAX_ENTRIES] 以内，尽量带上首行预览。 */
    private fun showPageGroupedToc(table: CellSourceTable?) {
        val total = totalUnits()
        val step = maxOf(TOC_PAGE_STEP, (total + TOC_MAX_ENTRIES - 1) / TOC_MAX_ENTRIES)
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
    private fun pagePreview(unit: Int, table: CellSourceTable?): String {
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
        while (i < mSourceText.length && sb.length < PREVIEW_CHARS) {
            val c = mSourceText[i]
            if (c == '\n' || c == '\r') break
            if (c != '\u3000' && c != ' ') sb.append(c)
            i++
        }
        return sb.toString()
    }

    private fun onTocPicked(which: Int) {
        val dialog = readerDialog as? ReaderDialog.Toc ?: return
        readerDialog = null
        if (which in dialog.targets.indices) gotoUnit(dialog.targets[which])
    }

    /** 书签：尚无书签时直接把当前页加为书签，否则弹出列表供跳转/删除。 */
    private fun showBookmarks() {
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

    private fun onBookmarkPicked(which: Int) {
        val dialog = readerDialog as? ReaderDialog.Bookmarks ?: return
        readerDialog = null
        if (which in dialog.offsets.indices) jumpToOffset(dialog.offsets[which])
    }

    private fun onBookmarkDeleteRequested() {
        val dialog = readerDialog as? ReaderDialog.Bookmarks ?: return
        showDeleteBookmark(dialog.offsets)
    }

    private fun onBookmarkClearRequested() {
        clearBookmarks()
        readerDialog = null
    }

    /** 删除单条书签。 */
    private fun showDeleteBookmark(offsets: List<Int>) {
        val labels = offsets.map { offsetLabel(it) }
        readerDialog = ReaderDialog.DeleteBookmark(
            getString(R.string.braille_reader_bm_delete), labels, offsets)
    }

    private fun onDeletePicked(which: Int) {
        val dialog = readerDialog as? ReaderDialog.DeleteBookmark ?: return
        val remaining = dialog.offsets.toMutableList().also { it.removeAt(which) }
        saveBookmarkOffsets(remaining)
        readerDialog = null
        Toast.makeText(this, R.string.braille_reader_bm_deleted,
            Toast.LENGTH_SHORT).show()
    }

    private fun addBookmark() {
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

    /** 跳到书签位置：直接落到该偏移，不做单元对齐以精确保位。 */
    private fun jumpToOffset(offset: Int) {
        if (mStream.isEmpty()) return
        mOffset = offset.coerceIn(0, maxOffset())
        showPage()
    }

    private fun offsetLabel(offset: Int): String =
        getString(R.string.braille_reader_bm_item,
            unitForOffset(offset) + 1, previewAt(offset))

    /** 取某偏移处的一行预览（去掉首尾空白方）。 */
    private fun previewAt(offset: Int): String {
        val line = BrailleFileDecoder.screenAt(mStream, offset, mCellsPerLine, 1)
            .trim('\u2800')
        return if (line.length <= PREVIEW_CELLS) line else line.substring(0, PREVIEW_CELLS)
    }

    private fun clearBookmarks() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .remove(bookmarkKey()).apply()
        Toast.makeText(this, R.string.braille_reader_bm_cleared, Toast.LENGTH_SHORT).show()
    }

    /** 跳到指定页（阅读单元序号，从 1 开始）。 */
    private fun showGotoDialog() {
        if (mStream.isEmpty()) {
            toastNoContent()
            return
        }
        readerDialog = ReaderDialog.Goto(
            getString(R.string.braille_reader_goto_title),
            getString(R.string.braille_reader_goto_hint, totalUnits()))
    }

    private fun onGotoConfirmed(n: Int) {
        readerDialog = null
        if (n in 1..totalUnits()) {
            gotoUnit(n - 1)
        }
    }

    private fun showFontSizeDialog() {
        val labels = FONT_SIZES.map {
            getString(R.string.braille_reader_font_size_item, it)
        }
        readerDialog = ReaderDialog.FontSize(
            getString(R.string.braille_reader_font_size_title), labels)
    }

    private fun onFontSizePicked(index: Int) {
        readerDialog = null
        val size = FONT_SIZES.getOrElse(index) { DEFAULT_READER_FONT_SP }
        SettingsStore.get(this).edit()
            .putInt(KEY_READER_FONT_SP, size)
            .apply()
        updateUiState()
    }

    private fun toastNoContent() {
        Toast.makeText(this, R.string.braille_reader_no_content, Toast.LENGTH_SHORT).show()
    }

    /**
     * 书签按文件 URI 隔离存储，值为**字符偏移**。
     * 存偏移是为了让屏幕尺寸或点显器方数变化引起重排后仍能定位到原处。
     */
    private fun bookmarkKey(): String = KEY_BM_PREFIX + (mFileUri?.toString() ?: "")

    private fun loadBookmarkOffsets(): MutableList<Int> {
        val raw = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getStringSet(bookmarkKey(), null) ?: return ArrayList()
        return raw.mapNotNull { it.toIntOrNull() }.toMutableList().also { it.sort() }
    }

    private fun saveBookmarkOffsets(offsets: List<Int>) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putStringSet(bookmarkKey(), offsets.map { it.toString() }.toSet())
            .apply()
    }

    // ------------------------------------------------------------------
    // 发送到点显器
    // ------------------------------------------------------------------

    /**
     * 发送当前屏到点显器：取从 [mOffset] 开始的 [mDisplayCells] 个方。
     * 旧实现发送「当前屏幕页的前 N 方」，一页可达数百方，
     * 20 方点显器只能读到开头一小段，其余内容永远读不到。
     */
    private fun sendToDisplay() {
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
        val cells = BrailleFileDecoder.cellsAt(mStream, mOffset, mDisplayCells)
        if (cells.isEmpty()) {
            releaseDisplay()
            return
        }
        // 先冻服务再画点位：反过来的话，服务那次文字刷新有可能落在点位之后把它盖掉。
        holdDisplay()
        val len = cells.length
        val patterns = ByteArray(len)
        val map = IntArray(len)
        for (i in 0 until len) {
            patterns[i] = (BrailleFileDecoder.toDots(cells[i]) and 0xFF).toByte()
            map[i] = i
        }
        display.displayDots(patterns, cells, map)
    }

    /**
     * 正文在屏期间独占点显器：冻结服务侧文字输出，屏上只剩这一页点位。
     * 服务没起来时返回 false，行为回落到普通模式，不报错。
     */
    private fun holdDisplay() {
        if (holdingDisplay) return
        holdingDisplay = BrailleBackService.holdDisplayForApp()
    }

    /** 交还点显器；重复调用无副作用。 */
    private fun releaseDisplay() {
        if (!holdingDisplay) return
        holdingDisplay = false
        BrailleBackService.releaseDisplayForApp()
    }

    // ------------------------------------------------------------------
    // 阅读进度记忆
    // ------------------------------------------------------------------

    private fun saveProgress() {
        if (mStream.isEmpty()) return
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(KEY_URI, mFileUri?.toString())
            .putInt(KEY_OFFSET, mOffset)
            .apply()
    }

    private fun restoreProgress() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val uriStr = prefs.getString(KEY_URI, null) ?: return
        val uri = try {
            Uri.parse(uriStr)
        } catch (e: Exception) {
            return
        }
        try {
            val savedOffset = prefs.getInt(KEY_OFFSET, 0)
            loadBytes(uri, resetPosition = true, targetOffset = savedOffset)
            mFileUri = uri
            showPage()
        } catch (e: Exception) {
            // 文件已被删除或权限失效：清空进度，回落到提示态
            mStream = ""
            mOffset = 0
            mPageOffsets = intArrayOf()
            mFileUri = null
            prefs.edit().remove(KEY_URI).remove(KEY_OFFSET).apply()
        }
    }

    companion object {
        private const val LOG_TAG = "BrailleReader"

        /** CJK 字符（汉字扩展 A 区+基本区/假名/谚文），用于内容语言检测。 */
        private val CJK_CONTENT = Regex("[\\u3400-\\u4DBF\\u4E00-\\u9FFF\\u3040-\\u30FF\\uAC00-\\uD7AF]")

        /** 以 CJK 为主要书写语言的表 locale.language 集合。 */
        private val CJK_TABLE_LANGUAGES = setOf("zh", "ja", "ko")

        /** 书架/最近打开跳转时携带待打开文件 uri（见 BookshelfActivity）。 */
        const val EXTRA_URI = "com.linxi.brailleback.extra.OPEN_URI"

        private const val DEFAULT_CELLS = 40
        private const val LINES_PER_PAGE = 20

        /** 翻译服务超过这个时长仍未就绪，就播报一次“先按原文读”。 */
        private const val TRANSLATOR_WARN_DELAY_MS = 8000L

        /** 单段翻译失败后的原地重试次数与间隔（桥抖动多为瞬时）。 */
        private const val TRANSLATE_ATTEMPTS = 3
        private const val TRANSLATE_RETRY_DELAY_MS = 300L

        /**
         * 边翻边读：第一块攒够这么多字就先上屏（约 1–2 秒），之后每
         * [PUBLISH_INTERVAL_MS] 毫秒续一块，免得用户对着「正在翻译」干等一分钟。
         */
        private const val FIRST_PUBLISH_CHARS = 12_000
        private const val PUBLISH_INTERVAL_MS = 2_000L
        /** 单块最少这么多字；不到就先攒着，免得先推上去半页空屏。约 1.5 页正文。 */
        private const val MIN_PUBLISH_CHARS = 1_200
        /**
         * 点显器方数上限，仅用于兜住驱动误报。
         * 常见单行点显器为 12–80 方，多行显示器最多数百方；
         * 超过此值基本可判定为上报异常（照单全收会让「一屏」变成整本书）。
         */
        private const val MAX_DISPLAY_CELLS = 4096
        /** 低于此值视为布局未完成或极端小屏，不据此重排。 */
        private const val MIN_LINES_PER_PAGE = 5
        private const val MIN_CELLS_PER_LINE = 8
        /** 满方盲文，用于测量单个盲文字符的实际宽度。 */
        private const val CELL_SAMPLE = "\u283F"
        private const val PREFS_NAME = "braille_reader"
        private const val KEY_URI = "last_uri"
        private const val KEY_OFFSET = "last_offset"
        private const val KEY_BM_PREFIX = "bm_"
        private const val KEY_EXPORT_COLUMNS = "export_columns"
        private const val KEY_EXPORT_LINES = "export_lines_per_page"
        /** 导出排版是否跟随点显器上报的方数；默认开启。 */
        private const val KEY_EXPORT_FOLLOW_DISPLAY = "export_follow_display"
        /** 导出折行是否按词断（空方处断行）；默认开启。 */
        private const val KEY_EXPORT_WORD_WRAP = "export_word_wrap"
        private const val EXPORT_MIN_COLUMNS = 20
        private const val EXPORT_MAX_COLUMNS = 40
        private const val EXPORT_MIN_LINES = 20
        private const val EXPORT_MAX_LINES = 28
        private const val KEY_READER_FONT_SP = "reader_font_sp"
        private const val DEFAULT_READER_FONT_SP = 18
        private val FONT_SIZES = listOf(18, 22, 26)
        /** 目录每 N 页一条。 */
        private const val TOC_PAGE_STEP = 10
        /** 兜底（按页分组）目录最多这么多条，超了就把每组页数加大。 */
        private const val TOC_MAX_ENTRIES = 150
        /** 目录/书签列表里预览用到的方数。 */
        private const val PREVIEW_CELLS = 40
        /** 目录预览取多少个原文字。 */
        private const val PREVIEW_CHARS = 14
        /** 翻译服务未就绪时挂起的翻译请求。 */
        private data class PendingTranslation(
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
        private val UNSUPPORTED_EXT = setOf(
            "doc", "wps", "rtf", "odt",
            "pdf", "mobi", "azw3",
            "rar", "zip", "7z",
            "jpg", "jpeg", "png", "gif", "bmp", "webp"
        )
    }
}
