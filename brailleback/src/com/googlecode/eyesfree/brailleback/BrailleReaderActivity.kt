/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 盲文阅读器：用系统文件选择器打开 BRF / 文本文件，
 * 自动识别编码（BOM / UTF-8 / GBK）并把 ASCII 盲文(0x20-0x5F)转为 Unicode 盲文，
 * 按点显器方数折行分页显示，可发送当前屏到点字显示器，并记忆阅读进度。
 */

package com.googlecode.eyesfree.brailleback

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Button
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

    private var mPages: List<String> = emptyList()
    private var mPage = 0
    private var mConnected = false
    private var mFileUri: Uri? = null

    /** 点显器每行方数，连接后由 getDisplayProperties() 更新；未连接用默认值。 */
    private var mCellsPerLine = DEFAULT_CELLS
    private val mLinesPerPage = LINES_PER_PAGE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.braille_reader)
        mReaderText = findViewById(R.id.reader_text)
        mPageInfo = findViewById(R.id.reader_page_info)
        mBtnPrev = findViewById(R.id.btn_prev)
        mBtnNext = findViewById(R.id.btn_next)
        mBtnSend = findViewById(R.id.btn_send)

        findViewById<Button>(R.id.btn_open).setOnClickListener { openFile() }
        mBtnPrev?.setOnClickListener { gotoPage(mPage - 1) }
        mBtnNext?.setOnClickListener { gotoPage(mPage + 1) }
        mBtnSend?.setOnClickListener { sendToDisplay() }
        updateUiState()
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
            if (cells > 0 && cells != mCellsPerLine) {
                // 点显器方数变化：按新宽度重新分页，尽量保持当前页
                mCellsPerLine = cells
                if (mFileUri != null) {
                    reloadKeepingPage()
                }
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

    /** 点显器宽度变化后按新宽度重排，尽量停留在原来的阅读位置。 */
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
        mPageInfo?.text = if (hasContent) {
            getString(R.string.braille_reader_page, mPage + 1, mPages.size)
        } else {
            ""
        }
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
        val cells = BrailleFileDecoder.pageToCells(mPages, mPage, mCellsPerLine)
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
        private const val PREFS_NAME = "braille_reader"
        private const val KEY_URI = "last_uri"
        private const val KEY_PAGE = "last_page"

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
