/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 盲文阅读器：用系统文件选择器打开 BRF / 文本文件，
 * 自动识别编码（BOM / UTF-8 / GBK）并把 ASCII 盲文(0x20-0x5F)转为 Unicode 盲文，
 * 按点显器方数折行分页显示，可发送当前屏到点字显示器，并记忆阅读进度。
 */

package com.googlecode.eyesfree.brailleback

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
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

    private var mPages: List<String> = emptyList()
    private var mPage = 0
    private var mConnected = false
    private var mFileUri: Uri? = null

    /**
     * 屏幕每行显示的方数，由屏幕宽度实测得出（见 [updatePaginationMetrics]）。
     * 必须按屏幕算：若按固定值折行，超出屏宽时 TextView 会自动换行，
     * 一页 20 行会被撑成 40 行，得滚动才能看完。
     */
    private var mCellsPerLine = DEFAULT_CELLS
    /** 每页行数，由屏幕高度实测得出。 */
    private var mLinesPerPage = LINES_PER_PAGE
    /** 点显器每行方数，连接后由 getDisplayProperties() 更新；未连接用默认值。 */
    private var mDisplayCells = DEFAULT_CELLS

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
        mBtnPrev?.setOnClickListener { gotoPage(mPage - 1) }
        mBtnNext?.setOnClickListener { gotoPage(mPage + 1) }
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
        if (mPages.isEmpty()) {
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
        mConnected = state == Display.STATE_CONNECTED
        if (mConnected) {
            val cells = mDisplay?.displayProperties?.numTextCells ?: 0
            if (cells > 0) {
                // 点显器方数只影响「发送一屏」的长度，不影响屏幕分页，
                // 因此连接设备时不再触发重排（以前会导致页码跳变）。
                mDisplayCells = cells
            }
        }
        runOnUiThread { updateUiState() }
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
            loadBytes(uri, resetPage = true)
            showPage()
            Toast.makeText(this, getString(R.string.braille_reader_loaded,
                mPages.size), Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this,
                getString(R.string.braille_reader_load_fail, e.message),
                Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 读取并解码文件，重建分页。
     *
     * 按文件名后缀分派：`.bdo`/`.bdoc` 走二进制结构化解码，其余按文本解码。
     * bdo 解码失败（不是有效的结构化盲文文档）时抛异常，由调用方提示。
     */
    private fun loadBytes(uri: Uri, resetPage: Boolean) {
        val name = displayNameOf(uri)
        checkSupportedFormat(name)
        val bytes = readAllBytes(uri)
        val text = BrailleFileDecoder.decodeDocument(bytes, name)
            ?: throw IllegalStateException(getString(R.string.braille_reader_bdo_fail))
        val oldCount = mPages.size
        mPages = BrailleFileDecoder.buildPages(text, mCellsPerLine, mLinesPerPage)
        if (resetPage) {
            mPage = 0
        } else if (oldCount > 0) {
            mPage = min(mPage * mPages.size / oldCount, mPages.size - 1)
            if (mPage < 0) mPage = 0
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

    // ------------------------------------------------------------------
    // 分页（解析逻辑见 BrailleFileDecoder）
    // ------------------------------------------------------------------

    /**
     * 按屏幕实测尺寸计算每行方数与每页行数。
     *
     * 原先固定「40 方 × 20 行」：字号 28sp + 行距 8dp 下，小屏会显示不全
     * （要滚动才能看完一页），大屏则留白；且行宽超出屏宽时 TextView 会自动换行，
     * 把一页 20 行撑成 40 行，排版完全乱掉。
     *
     * 这里用 TextView 实测的字符宽度与行高推导，变化后按页数比例重排以保持位置。
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
            reloadKeepingPage()
        }
    }

    /** 屏幕分页指标（每行方数/每页行数）变化后重排，尽量停留在原来的阅读位置。 */
    private fun reloadKeepingPage() {
        val uri = mFileUri ?: return
        try {
            loadBytes(uri, resetPage = false)
            showPage()
        } catch (e: Exception) {
            // 重排失败保留原分页
        }
    }

    // ------------------------------------------------------------------
    // 翻页 / 显示
    // ------------------------------------------------------------------

    private fun gotoPage(page: Int) {
        if (mPages.isEmpty()) return
        if (page < 0 || page >= mPages.size) return
        mPage = page
        showPage()
    }

    private fun showPage() {
        if (mPages.isEmpty()) {
            mReaderText?.setText(R.string.braille_reader_hint)
        } else {
            mReaderText?.text = mPages[mPage]
        }
        updateUiState()
    }

    private fun updateUiState() {
        val hasContent = mPages.isNotEmpty()
        mBtnPrev?.isEnabled = hasContent && mPage > 0
        mBtnNext?.isEnabled = hasContent && mPage < mPages.size - 1
        mBtnSend?.isEnabled = hasContent && mConnected
        mBtnToc?.isEnabled = hasContent
        mBtnBookmark?.isEnabled = hasContent
        mBtnGoto?.isEnabled = hasContent
        mPageInfo?.text = if (hasContent) {
            getString(R.string.braille_reader_page, mPage + 1, mPages.size)
        } else {
            ""
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
        if (mPages.isEmpty()) {
            toastNoContent()
            return
        }
        val labels = ArrayList<String>()
        val targets = ArrayList<Int>()
        var p = 0
        while (p < mPages.size) {
            val last = min(p + TOC_PAGE_STEP, mPages.size) - 1
            val preview = BrailleFileDecoder.pagePreview(mPages, p, PREVIEW_CELLS)
            labels.add(getString(R.string.braille_reader_toc_item, p + 1, last + 1, preview))
            targets.add(p)
            p = last + 1
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.braille_reader_toc_title)
            .setItems(labels.toTypedArray()) { _, which -> gotoPage(targets[which]) }
            .show()
    }

    /** 书签：尚无书签时直接把当前页加为书签，否则弹出列表供跳转/删除。 */
    private fun showBookmarks() {
        if (mPages.isEmpty()) {
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
            .setItems(labels) { _, which -> gotoPage(pageForOffset(offsets[which])) }
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
        val offset = pageStartOffset(mPage)
        if (offsets.contains(offset)) {
            Toast.makeText(this, getString(R.string.braille_reader_bm_exists, mPage + 1),
                Toast.LENGTH_SHORT).show()
            return
        }
        offsets.add(offset)
        offsets.sort()
        saveBookmarkOffsets(offsets)
        Toast.makeText(this, getString(R.string.braille_reader_bm_added, mPage + 1),
            Toast.LENGTH_SHORT).show()
    }

    private fun offsetLabel(offset: Int): String {
        val p = pageForOffset(offset)
        return getString(R.string.braille_reader_bm_item, p + 1,
            BrailleFileDecoder.pagePreview(mPages, p, PREVIEW_CELLS))
    }

    private fun clearBookmarks() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .remove(bookmarkKey()).apply()
        Toast.makeText(this, R.string.braille_reader_bm_cleared, Toast.LENGTH_SHORT).show()
    }

    /** 跳到指定页码。 */
    private fun showGotoDialog() {
        if (mPages.isEmpty()) {
            toastNoContent()
            return
        }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = getString(R.string.braille_reader_goto_hint, mPages.size)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.braille_reader_goto_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val n = input.text.toString().toIntOrNull()
                if (n != null && n in 1..mPages.size) {
                    gotoPage(n - 1)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toastNoContent() {
        Toast.makeText(this, R.string.braille_reader_no_content, Toast.LENGTH_SHORT).show()
    }

    /**
     * 书签按文件 URI 隔离存储，值为页首字符在去换行正文中的**偏移**而非页码。
     * 存偏移是为了让点显器方数变化引起重排后，书签仍能定位到大致位置。
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

    /** 第 [page] 页首字符在去换行正文中的偏移。 */
    private fun pageStartOffset(page: Int): Int {
        var offset = 0
        for (i in 0 until min(page, mPages.size)) {
            offset += mPages[i].count { it != '\n' }
        }
        return offset
    }

    /** 偏移落在哪一页。 */
    private fun pageForOffset(offset: Int): Int {
        var acc = 0
        for (i in mPages.indices) {
            val n = mPages[i].count { it != '\n' }
            if (acc + n > offset) return i
            acc += n
        }
        return (mPages.size - 1).coerceAtLeast(0)
    }

    // ------------------------------------------------------------------
    // 发送到点显器
    // ------------------------------------------------------------------

    /**
     * 发送当前屏到点显器：取当前页展平后的前 [mCellsPerLine] 个字符。
     * （旧实现发送整页文本，长度可达数百，远超点显器方数，显示内容不正确。）
     */
    private fun sendToDisplay() {
        val display = mDisplay ?: return
        // 用点显器方数（而非屏幕每行方数）决定这一屏发多少个方
        val cells = BrailleFileDecoder.pageToCells(mPages, mPage, mDisplayCells)
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
        if (mPages.isEmpty()) return
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putString(KEY_URI, mFileUri?.toString())
            .putInt(KEY_PAGE, mPage)
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
            loadBytes(uri, resetPage = true)
            mFileUri = uri
            mPage = prefs.getInt(KEY_PAGE, 0).coerceIn(0, (mPages.size - 1).coerceAtLeast(0))
            showPage()
        } catch (e: Exception) {
            // 文件已被删除或权限失效：清空进度，回落到提示态
            mPages = emptyList()
            mFileUri = null
            prefs.edit().remove(KEY_URI).remove(KEY_PAGE).apply()
        }
    }

    companion object {
        private const val REQUEST_OPEN = 1
        private const val DEFAULT_CELLS = 40
        private const val LINES_PER_PAGE = 20
        /** 低于此值视为布局未完成或极端小屏，不据此重排。 */
        private const val MIN_LINES_PER_PAGE = 5
        private const val MIN_CELLS_PER_LINE = 8
        /** 满方盲文，用于测量单个盲文字符的实际宽度。 */
        private const val CELL_SAMPLE = "\u283F"
        private const val PREFS_NAME = "braille_reader"
        private const val KEY_URI = "last_uri"
        private const val KEY_PAGE = "last_page"
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
