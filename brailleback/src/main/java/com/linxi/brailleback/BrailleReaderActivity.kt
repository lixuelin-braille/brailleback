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
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import com.linxi.brailleback.utils.LogFileWriter
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.nio.charset.StandardCharsets
import kotlin.math.min

class BrailleReaderActivity : ComponentActivity(), Display.OnConnectionStateChangeListener {

    private var mDisplay: DisplayClient? = null

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
                if (pending != null) {
                    mPendingTranslation = null
                    mTranslatorManager.removeOnTablesChangedListener(mTranslationReadyListener)
                    startTranslation(pending.text, pending.name, pending.uri,
                        pending.resetPosition, pending.targetOffset)
                }
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

    /**
     * 每一屏的起始偏移。
     *
     * 采用边界对齐步进（[BrailleFileDecoder.nextOffset]）后步进长度不再固定，
     * 页码无法再用「偏移 / 单元」换算，故预先算好页表（见 [rebuildPageOffsets]）。
     */
    private var mPageOffsets: IntArray = intArrayOf()

    /**
     * 阅读单元：一次前进多少个方。
     *
     * 连接点显器时按**点显器一屏**推进（20/30/40 方不等），
     * 未连接时按屏幕一屏推进。这是适配不同方数点显器的关键。
     */
    private fun readingUnit(): Int =
        if (mConnected && mDisplayCells > 0) {
            mDisplayCells
        } else {
            (mCellsPerLine * mLinesPerPage).coerceAtLeast(1)
        }

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
                    send = getString(R.string.braille_reader_send),
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
                onSend = ::sendToDisplay,
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
    ) {
        val oldLength = mStream.length
        mStream = BrailleFileDecoder.toReadingStream(brailleText)
        mIsTranslated = isTranslated
        mCellSpans = cellSpans
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
        Toast.makeText(this, getString(R.string.braille_reader_loaded,
            totalUnits()), Toast.LENGTH_SHORT).show()
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
            return
        }
        mTranslating = true
        uiState = uiState.copy(
            readerText = getString(R.string.braille_reader_translating))
        warnIfTableMismatch(text, translator)
        Thread {
            val result = try {
                translateParagraphs(text, translator)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "翻译失败: ${e.message}")
                null
            }
            runOnUiThread {
                mTranslating = false
                // 若此刻屏幕上正读着兜底原文，切盲文流时按位置比例接手，
                // 不把用户已经在读的位置拽回第 1 页。
                val keepPlace = mSourceKind == ReaderSourceKind.TEXT &&
                    !mIsTranslated && mStream.isNotEmpty()
                if (result != null) {
                    setBrailleStream(result.first, name, uri,
                        if (keepPlace) false else resetPosition,
                        if (keepPlace) null else targetOffset,
                        isTranslated = true, cellSpans = result.second)
                } else {
                    // 翻译失败：屏幕仍可看原文，点显器暂为空方，给出明确提示
                    setBrailleStream(text, name, uri,
                        if (keepPlace) false else resetPosition,
                        if (keepPlace) null else targetOffset)
                    Toast.makeText(this,
                        getString(R.string.braille_reader_translate_fail),
                        Toast.LENGTH_LONG).show()
                }
            }
        }.start()
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
    private fun translateParagraphs(
        text: String,
        translator: BrailleTranslator
    ): Pair<String, List<CellSource>>? {
        val sb = StringBuilder(text.length)
        val spans = ArrayList<CellSource>()
        for (paragraph in text.split('\n')) {
            if (paragraph.isBlank()) {
                sb.append('\n')
                continue
            }
            val res = translator.translate(paragraph, -1, false) ?: return null
            sb.append(BrailleFileDecoder.cellsToUnicodeText(res.cells)).append('\n')
            spans += SourceSpanMapper.buildParagraphSpans(
                paragraph, res.brailleToTextPositions)
        }
        return sb.toString() to spans
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
            sendEnabled = hasContent && mConnected,
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
     * 目录：按固定页数分组的导航。
     *
     * 不做章节自动识别——实测不可靠：1787 行的书只能识别出 3 个「候选标题」，
     * 且全是封面/版权信息，真实章节标题与正文连在一起无法自动切分。
     * 改为按页分组 + 首行预览，行为可预期。
     */
    private fun showToc() {
        if (mStream.isEmpty()) {
            toastNoContent()
            return
        }
        val unit = readingUnit()
        val total = totalUnits()
        val labels = ArrayList<String>()
        val targets = ArrayList<Int>()
        var u = 0
        while (u < total) {
            val last = min(u + TOC_PAGE_STEP, total) - 1
            labels.add(getString(R.string.braille_reader_toc_item, u + 1, last + 1))
            targets.add(u)
            u = last + 1
        }
        readerDialog = ReaderDialog.Toc(
            getString(R.string.braille_reader_toc_title), labels, targets)
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
        if (mStream.isEmpty()) return
        // 明眼原文（翻译尚未就绪时的兜底流）不是点位，发过去只会是乱码。
        if (mSourceKind == ReaderSourceKind.TEXT && !mIsTranslated) return
        val cells = BrailleFileDecoder.cellsAt(mStream, mOffset, mDisplayCells)
        if (cells.isEmpty()) return
        val len = cells.length
        val patterns = ByteArray(len)
        val map = IntArray(len)
        for (i in 0 until len) {
            patterns[i] = (BrailleFileDecoder.toDots(cells[i]) and 0xFF).toByte()
            map[i] = i
        }
        display.displayDots(patterns, cells, map)
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
        /** 目录/书签列表里预览用到的方数。 */
        private const val PREVIEW_CELLS = 40
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
