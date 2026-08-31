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
 */

package com.googlecode.eyesfree.brailleback

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.googlecode.eyesfree.braille.display.Display
import com.googlecode.eyesfree.braille.display.DisplayClient
import java.io.ByteArrayOutputStream
import kotlin.math.min

class BrailleReaderActivity : Activity(), Display.OnConnectionStateChangeListener {

    private var mDisplay: DisplayClient? = null
    private var mReaderText: TextView? = null
    private var mPageInfo: TextView? = null
    private var mBtnPrev: Button? = null
    private var mBtnNext: Button? = null
    private var mBtnSend: Button? = null
    private var mBtnToc: Button? = null
    private var mBtnBookmark: Button? = null
    private var mBtnGoto: Button? = null

    /** 阅读流：段落以 '\n' 分隔，空行已折叠。 */
    private var mStream = ""
    /** 当前阅读位置（字符偏移）。 */
    private var mOffset = 0
    private var mConnected = false
    private var mFileUri: Uri? = null

    /**
     * 屏幕每行显示的方数，由屏幕宽度实测得出（见 [updatePaginationMetrics]）。
     * 必须按屏幕算：若按固定值折行，超出屏宽时 TextView 会自动换行，
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.braille_reader)
        mReaderText = findViewById(R.id.reader_text)
        mPageInfo = findViewById(R.id.reader_page_info)
        mBtnPrev = findViewById(R.id.btn_prev)
        mBtnNext = findViewById(R.id.btn_next)
        mBtnSend = findViewById(R.id.btn_send)
        mBtnToc = findViewById(R.id.btn_toc)
        mBtnBookmark = findViewById(R.id.btn_bookmark)
        mBtnGoto = findViewById(R.id.btn_goto)

        findViewById<Button>(R.id.btn_open).setOnClickListener { openFile() }
        mBtnPrev?.setOnClickListener { advance(-1) }
        mBtnNext?.setOnClickListener { advance(1) }
        // 长按 = 上一行/下一行（微调）。与翻页一样会同步点显器。
        mBtnPrev?.setOnLongClickListener {
            moveLine(-1)
            true
        }
        mBtnNext?.setOnLongClickListener {
            moveLine(1)
            true
        }
        mBtnSend?.setOnClickListener { sendToDisplay() }
        mBtnToc?.setOnClickListener { showToc() }
        mBtnBookmark?.setOnClickListener { showBookmarks() }
        mBtnGoto?.setOnClickListener { showGotoDialog() }
        updateUiState()
        // 布局完成后按屏幕实测尺寸决定每页行数与每行方数，
        // 避免固定值在小屏显示不全（要滚动）或大屏留白。
        mReaderText?.post { updatePaginationMetrics() }
    }

    override fun onResume() {
        super.onResume()
        val display = DisplayClient(this)
        mDisplay = display
        display.setOnConnectionStateChangeListener(this)
        onConnectionStateChanged(Display.STATE_NOT_CONNECTED)
        // 首次进入且尚无内容时，尝试恢复上次阅读进度
        if (mStream.isEmpty()) {
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

    private fun openFile() {
        // 不限定 MIME：.bdo/.bdoc/.brf 无标准 MIME 类型，
        // 用 */* 才能在各家文件管理器中选中它们。格式校验放在读取时做。
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQUEST_OPEN)
    }

    override fun onActivityResult(
        requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_OPEN && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            // 持久化读权限，使下次启动仍能直接续读
            try {
                contentResolver.takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                // 部分选择器不支持持久化，忽略（本次仍可正常读取）
            }
            mFileUri = uri
            loadFile(uri)
        }
    }

    private fun loadFile(uri: Uri) {
        try {
            loadBytes(uri, resetPosition = true)
            showPage()
            Toast.makeText(this, getString(R.string.braille_reader_loaded,
                totalUnits()), Toast.LENGTH_SHORT).show()
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
     * bdo 解码失败（不是有效的结构化盲文文档）时抛异常，由调用方提示。
     */
    private fun loadBytes(uri: Uri, resetPosition: Boolean) {
        val name = displayNameOf(uri)
        checkSupportedFormat(name)
        val bytes = readAllBytes(uri)
        val text = BrailleFileDecoder.decodeDocument(bytes, name)
            ?: throw IllegalStateException(getString(R.string.braille_reader_bdo_fail))
        val oldLength = mStream.length
        mStream = BrailleFileDecoder.toReadingStream(text)
        if (resetPosition) {
            mOffset = 0
        } else if (oldLength > 0) {
            // 屏幕尺寸或点显器方数变化后按位置比例还原，保持阅读进度
            mOffset = (mOffset * mStream.length / oldLength).coerceIn(0, maxOffset())
        }
        rebuildPageOffsets()
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

    /**
     * 按屏幕实测尺寸计算每行方数与每页行数。
     *
     * 原先固定「40 方 × 20 行」：字号 28sp + 行距 8dp 下，小屏会显示不全
     * （要滚动才能看完一页），大屏则留白；且行宽超出屏宽时 TextView 会自动换行，
     * 把一页 20 行撑成 40 行，排版完全乱掉。
     *
     * 这里用 TextView 实测的字符宽度与行高推导，变化后按比例保持阅读位置。
     */
    private fun updatePaginationMetrics() {
        val tv = mReaderText ?: return
        val scroll = tv.parent as? ScrollView ?: return
        var changed = false

        val charWidth = tv.paint.measureText(CELL_SAMPLE)
        if (charWidth > 0) {
            val available = tv.width - tv.paddingLeft - tv.paddingRight
            val cells = (available / charWidth).toInt()
            if (cells >= MIN_CELLS_PER_LINE && cells != mCellsPerLine) {
                mCellsPerLine = cells
                changed = true
            }
        }
        val lineHeight = tv.lineHeight
        if (lineHeight > 0) {
            val visible = scroll.height / lineHeight
            if (visible >= MIN_LINES_PER_PAGE && visible != mLinesPerPage) {
                mLinesPerPage = visible
                changed = true
            }
        }
        // 过滤异常值（布局未完成或极端小屏），避免把书切成上千页
        if (changed && mFileUri != null) {
            reloadKeepingPosition()
        }
    }

    /** 屏幕分页指标变化后重读并按比例保持阅读位置。 */
    private fun reloadKeepingPosition() {
        val uri = mFileUri ?: return
        try {
            loadBytes(uri, resetPosition = false)
            showPage()
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
        if (mStream.isEmpty()) {
            mReaderText?.setText(R.string.braille_reader_hint)
        } else {
            mReaderText?.text = BrailleFileDecoder.screenAt(
                mStream, mOffset, mCellsPerLine, mLinesPerPage)
        }
        // 翻页后自动同步点显器，省去盲文用户每次手动点「发送点显」
        if (mConnected) {
            sendToDisplay()
        }
        updateUiState()
    }

    private fun updateUiState() {
        val hasContent = mStream.isNotEmpty()
        val unit = readingUnit()
        mBtnPrev?.isEnabled = hasContent && mOffset > 0
        mBtnNext?.isEnabled = hasContent && mOffset + unit <= maxOffset()
        mBtnSend?.isEnabled = hasContent && mConnected
        mBtnToc?.isEnabled = hasContent
        mBtnBookmark?.isEnabled = hasContent
        mBtnGoto?.isEnabled = hasContent
        mPageInfo?.text = if (!hasContent) {
            ""
        } else if (mConnected) {
            // 连上点显器时把驱动上报的方数一并显示，
            // 万一某设备上报不准，一眼就能看出是驱动问题而非阅读器问题。
            getString(R.string.braille_reader_page_cells,
                currentUnit() + 1, totalUnits(), mDisplayCells)
        } else {
            getString(R.string.braille_reader_page, currentUnit() + 1, totalUnits())
        }
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
        AlertDialog.Builder(this)
            .setTitle(R.string.braille_reader_toc_title)
            .setItems(labels.toTypedArray()) { _, which -> gotoUnit(targets[which]) }
            .show()
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
        val labels = offsets.map { offsetLabel(it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.braille_reader_bookmarks_title)
            .setItems(labels) { _, which -> jumpToOffset(offsets[which]) }
            .setPositiveButton(R.string.braille_reader_bm_delete) { _, _ ->
                showDeleteBookmark(offsets)
            }
            .setNeutralButton(R.string.braille_reader_bm_clear_all) { _, _ ->
                clearBookmarks()
            }
            .show()
    }

    /** 删除单条书签。 */
    private fun showDeleteBookmark(offsets: List<Int>) {
        val labels = offsets.map { offsetLabel(it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.braille_reader_bm_delete)
            .setItems(labels) { _, which ->
                val remaining = offsets.toMutableList().also { it.removeAt(which) }
                saveBookmarkOffsets(remaining)
                Toast.makeText(this, R.string.braille_reader_bm_deleted,
                    Toast.LENGTH_SHORT).show()
            }
            .show()
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
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.braille_reader_goto_hint, totalUnits())
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.braille_reader_goto_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val n = input.text.toString().toIntOrNull()
                if (n != null && n in 1..totalUnits()) {
                    gotoUnit(n - 1)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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
            loadBytes(uri, resetPosition = true)
            mFileUri = uri
            mOffset = prefs.getInt(KEY_OFFSET, 0).coerceIn(0, maxOffset())
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

        private const val REQUEST_OPEN = 1
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
        /** 目录每 N 页一条。 */
        private const val TOC_PAGE_STEP = 10
        /** 目录/书签列表里预览用到的方数。 */
        private const val PREVIEW_CELLS = 12

        /**
         * 明确不支持的二进制/排版格式。盲文阅读器只处理盲文与纯文本：
         * .bdo/.bdoc（Bword 二进制盲文）、.brf/.brl/.txt（文本类）。
         * Word/PDF 等需先转成盲文，直接打开只会得到乱码。
         */
        private val UNSUPPORTED_EXT = setOf(
            "doc", "docx", "wps", "rtf", "odt",
            "pdf", "epub", "mobi", "azw3",
            "rar", "zip", "7z",
            "jpg", "jpeg", "png", "gif", "bmp", "webp"
        )
    }
}
