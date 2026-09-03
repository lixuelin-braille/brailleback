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
import com.googlecode.eyesfree.braille.translate.BrailleTranslator
import com.googlecode.eyesfree.brailleback.utils.LogFileWriter
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

    /** 翻译服务（明眼 txt 电子书 → 盲文）。懒初始化：绑定 service 模块的翻译服务。 */
    private val mTranslatorManager by lazy { TranslatorManager(this) }

    /** 正在翻译标志，防止重复触发。 */
    private var mTranslating = false

    /** 当前流是否来自明眼 txt 翻译。翻译流与屏幕尺寸无关，重排时无需重翻。 */
    private var mIsTranslated = false

    /** 翻译服务未就绪时挂起的翻译请求，就绪后自动继续。 */
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
        // 阅读器可独立于无障碍服务使用，单独初始化文件日志
        LogFileWriter.init(this)
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
                val text = BrailleFileDecoder.decodeDocument(bytes, name)
                    ?: throw IllegalStateException(getString(R.string.braille_reader_bdo_fail))
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
                    BrailleFileDecoder.ContentKind.TEXT ->
                        startTranslation(text, name, uri, resetPosition, targetOffset)
                    else ->
                        setBrailleStream(text, name, uri, resetPosition, targetOffset)
                }
            }
        }.start()
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
        isTranslated: Boolean = false
    ) {
        val oldLength = mStream.length
        mStream = BrailleFileDecoder.toReadingStream(brailleText)
        mIsTranslated = isTranslated
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
            mReaderText?.text = getString(R.string.braille_reader_translator_waiting)
            return
        }
        mTranslating = true
        mReaderText?.text = getString(R.string.braille_reader_translating)
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
                if (result != null) {
                    setBrailleStream(result, name, uri, resetPosition, targetOffset,
                        isTranslated = true)
                } else {
                    // 翻译失败：屏幕仍可看原文，点显器暂为空方，给出明确提示
                    setBrailleStream(text, name, uri, resetPosition, targetOffset)
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
    private fun translateParagraphs(
        text: String,
        translator: BrailleTranslator
    ): String? {
        val sb = StringBuilder(text.length)
        for (paragraph in text.split('\n')) {
            if (paragraph.isBlank()) {
                sb.append('\n')
                continue
            }
            val res = translator.translate(paragraph, -1, false) ?: return null
            sb.append(BrailleFileDecoder.cellsToUnicodeText(res.cells)).append('\n')
        }
        return sb.toString()
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
            // 内容尚未就绪（如首次翻译中）时无需重读：就绪后的 setBrailleStream
            // 本来就会用最新指标重建分页；此时重读会以 targetOffset=null 覆盖
            // mPendingTranslation，把书架续读的偏移冲掉（实测致永远回第 1 页）。
            if (mStream.isNotEmpty()) {
                reloadKeepingPosition()
            }
        }
    }

    /** 屏幕分页指标变化后重读并按比例保持阅读位置。 */
    private fun reloadKeepingPosition() {
        val uri = mFileUri ?: return
        // 翻译流的段落结构已固定，与屏幕尺寸无关：只需按新阅读单元重建页表
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
        const val EXTRA_URI = "com.googlecode.eyesfree.brailleback.extra.OPEN_URI"

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
