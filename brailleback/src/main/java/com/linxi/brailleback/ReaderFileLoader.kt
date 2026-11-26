/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 阅读器文件读取：格式校验、URI 读流、解码分派、铺流收尾与重读。
 * 同包扩展函数，状态字段仍留在 BrailleReaderActivity；不引入 DI 框架。
 */

package com.linxi.brailleback

import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import java.io.ByteArrayOutputStream

internal fun BrailleReaderActivity.loadFile(uri: Uri, targetOffset: Int? = null) {
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
internal fun BrailleReaderActivity.loadBytes(uri: Uri, resetPosition: Boolean, targetOffset: Int? = null) {
        // 读文件 + 解码是 IO/CPU 密集操作（txt/bdo 可能数 MB），放在后台线程，
        // 避免阻塞主线程导致打开大书时卡顿/ANR；UI 更新（startTranslation /
        // setBrailleStream）回到主线程执行。失败时在主线程 Toast 提示。
        Thread {
            val decoded: Pair<String, String?>? = try {
                val name = displayNameOf(uri)
                checkSupportedFormat(name)
                val bytes = readAllBytes(uri)
                // 阳光盲文图书馆电子书：正文是 PUA 私用区字符（U+E234～E273），
                // 先按阳光 docx 解；命中 PUA 走盲文链路，EPUB/HTML/Markdown/普通
                // Word 等明眼文字容器仍交提取器，再进翻译链路。
                val sunshineRaw = BrailleFileDecoder.decodeSunshineDocx(bytes)
                val text = when {
                    sunshineRaw != null && BrailleFileDecoder.isSunshinePua(sunshineRaw) ->
                        BrailleFileDecoder.reflowSunshine(sunshineRaw)
                    EbookTextExtractor.supports(name) ->
                        when (val extracted = EbookTextExtractor.extract(bytes, name)) {
                            is EbookTextExtractor.Result.Success -> extracted.text
                            is EbookTextExtractor.Result.Failure ->
                                throw IllegalStateException(ebookFailureMessage(extracted))
                        }
                    else ->
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
            Log.d(BrailleReaderActivity.LOG_TAG, "load done: name=$name chars=${text.length}")
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
internal fun BrailleReaderActivity.loadFailureMessage(
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
internal fun BrailleReaderActivity.ebookFailureMessage(
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
internal fun BrailleReaderActivity.setBrailleStream(
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
        // 换一本新内容：上一本的到头播报标记复位，别压住新书真正的文末播报。
        mAnnouncedEnd = false
        // 每进一次流记一条：同一本书若出现两条以上，说明真的重复解码/翻译了。
        Log.d(BrailleReaderActivity.LOG_TAG, "stream set: len=${mStream.length} translated=$isTranslated " +
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

    /**
     * 拒绝明确不支持的格式，避免用户打开 Word/PDF 等得到一堆乱码却以为是 bug。
     * 未列出的后缀（含无后缀）一律放行，按文本尝试解码。
     */
internal fun BrailleReaderActivity.checkSupportedFormat(name: String?) {
        val ext = name?.substringAfterLast('.', "")?.lowercase() ?: return
        if (ext == "doc") {
            // 97-2003 是 OLE 复合文档，不是 zip，解不出正文；给出可执行的下一步。
            throw IllegalStateException(getString(R.string.braille_reader_err_doc_legacy))
        }
        if (ext in BrailleReaderActivity.UNSUPPORTED_EXT) {
            throw IllegalStateException(
                getString(R.string.braille_reader_unsupported_format, ext))
        }
    }

    /** 取文件显示名（用于按后缀判断格式）；查询失败时回落到 URI 末段。 */
internal fun BrailleReaderActivity.displayNameOf(uri: Uri): String? {
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

internal fun BrailleReaderActivity.readAllBytes(uri: Uri): ByteArray {
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
internal fun BrailleReaderActivity.reloadKeepingPosition() {
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
