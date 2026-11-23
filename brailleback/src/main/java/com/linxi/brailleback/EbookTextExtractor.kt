/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 电子书/文档容器文本提取：EPUB（zip + OPF spine）、HTML/XHTML、Markdown、
 * Word（.docx/.docm 的 word/document.xml）。
 * 纯 Kotlin，无 Android 依赖，可在 JVM 单测里跑。
 *
 * 为什么单独一层：阅读器只认识两种东西——盲文点位/ASCII 盲文，和明眼文字。
 * EPUB、DOCX 都是 zip 容器，直接按文本解码会得到一坨二进制；这里先把容器
 * 拆成纯文字，后面的「明眼文字 → 盲文」翻译链路完全复用 txt 那条路。
 */

package com.linxi.brailleback

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.util.zip.ZipInputStream

object EbookTextExtractor {

    /** EPUB 的入口清单，路径固定。 */
    private const val CONTAINER_PATH = "META-INF/container.xml"

    /** DOCX/DOCM 的正文，OOXML 规定的位置。 */
    private const val WORD_DOCUMENT_PATH = "word/document.xml"

    /** 单个 zip 条目的解压上限；一章 XHTML 正常远小于这个值。 */
    private const val MAX_ENTRY_BYTES = 16L * 1024 * 1024

    /** 整本解压上限，防 zip 炸弹。 */
    private const val MAX_TOTAL_BYTES = 128L * 1024 * 1024

    /** 走本提取器而不是直接文本解码的后缀。 */
    private val SUPPORTED_EXT = setOf(
        "epub", "html", "htm", "xhtml", "xht", "md", "markdown",
        "docx", "docm")

    sealed interface Result {
        /** 提取成功，[text] 是明眼文字，交给翻译链路。 */
        data class Success(val text: String) : Result

        sealed interface Failure : Result {
            data object EmptyFile : Failure
            data object NotEpub : Failure
            data object NotWord : Failure
            data object NoText : Failure
            data object TooLarge : Failure
        }
    }

    /** 这个文件后缀是否由本提取器处理。 */
    fun supports(fileName: String?): Boolean =
        extensionOf(fileName) in SUPPORTED_EXT

    /**
     * 从容器里取文字。
     *
     * EPUB 按 OPF spine 顺序拼接各章；HTML 与 Markdown 直接转纯文字。
     * 失败原因用类型区分，调用方可以分别给出「文件空 / 不是 EPUB /
     * 只有图片」三种提示，避免一句笼统的「打开失败」。
     */
    fun extract(bytes: ByteArray, fileName: String?): Result {
        if (bytes.isEmpty()) {
            return Result.Failure.EmptyFile
        }
        return when (extensionOf(fileName)) {
            "epub" -> extractEpub(bytes)
            "docx", "docm" -> extractWord(bytes)
            "html", "htm", "xhtml", "xht" -> {
                val text = htmlToText(BrailleFileDecoder.decode(bytes))
                if (text.isBlank()) Result.Failure.NoText else Result.Success(text)
            }
            "md", "markdown" -> {
                val text = BrailleFileDecoder.decode(bytes).replace("\r\n", "\n")
                if (text.isBlank()) Result.Failure.NoText else Result.Success(text)
            }
            else -> {
                // 到这里说明 supports() 判定与 extract() 分派不一致。
                Result.Failure.NotEpub
            }
        }
    }

    private fun extensionOf(fileName: String?): String =
        fileName?.substringAfterLast('.', "")?.lowercase() ?: ""

    // ------------------------------------------------------------------
    // EPUB
    // ------------------------------------------------------------------

    private fun extractEpub(bytes: ByteArray): Result {
        val entries = unzip(bytes) ?: return Result.Failure.TooLarge
        val container = entries[CONTAINER_PATH] ?: return Result.Failure.NotEpub
        val rootPath = parseRootFilePath(container)
            ?: entries.keys.firstOrNull { it.lowercase().endsWith(".opf") }
            ?: return Result.Failure.NotEpub
        val opfBytes = entries[rootPath] ?: return Result.Failure.NotEpub
        val packageDoc = parsePackage(opfBytes, rootPath)
        val chapterPaths = packageDoc.chapterPaths()
        val text = chapterPaths
            .mapNotNull { path -> entries[path] }
            .map { chapterBytes -> htmlToText(BrailleFileDecoder.decode(chapterBytes)) }
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
        return if (text.isBlank()) Result.Failure.NoText else Result.Success(text)
    }

    /**
     * 读 zip 到内存。超过上限返回 null。
     *
     * 电子书一次只读一本，128 MB 上限对纯文字 EPUB 足够宽松，
     * 同时挡住恶意压缩包把手机内存打爆。
     */
    private fun unzip(bytes: ByteArray): Map<String, ByteArray>? {
        val result = LinkedHashMap<String, ByteArray>()
        var totalBytes = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val data = readEntry(zip, MAX_TOTAL_BYTES - totalBytes)
                        ?: return null
                    totalBytes += data.size
                    result[entry.name] = data
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return result
    }

    /** 读一个 zip 条目；单条或剩余总量超限都返回 null。 */
    private fun readEntry(zip: ZipInputStream, remainingBytes: Long): ByteArray? {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var entryBytes = 0L
        var read = zip.read(chunk)
        while (read > 0) {
            entryBytes += read
            if (entryBytes > MAX_ENTRY_BYTES || entryBytes > remainingBytes) {
                return null
            }
            buffer.write(chunk, 0, read)
            read = zip.read(chunk)
        }
        return buffer.toByteArray()
    }

    // ------------------------------------------------------------------
    // Word（.docx / .docm）
    // ------------------------------------------------------------------

    /**
     * DOCX 是 OOXML zip，正文全在 `word/document.xml`。
     *
     * 只取正文：页眉页脚、脚注、批注、图片里的文字都不在 document.xml，
     * 也就不还原——阅读器要的是可读的连续文字，不是版式。
     */
    private fun extractWord(bytes: ByteArray): Result {
        if (!looksLikeZip(bytes)) {
            return Result.Failure.NotWord
        }
        return when (val entry = readNamedEntry(bytes, WORD_DOCUMENT_PATH)) {
            EntryRead.Missing -> Result.Failure.NotWord
            EntryRead.TooLarge -> Result.Failure.TooLarge
            is EntryRead.Data -> {
                val text = wordXmlToText(BrailleFileDecoder.decode(entry.bytes))
                if (text.isBlank()) Result.Failure.NoText else Result.Success(text)
            }
        }
    }

    /** DOCX 本来就是 zip；不是 zip 的多半是旧版 .doc 改了后缀，或文件损坏。 */
    private fun looksLikeZip(bytes: ByteArray): Boolean =
        bytes.size >= 2 &&
            bytes[0] == 'P'.code.toByte() &&
            bytes[1] == 'K'.code.toByte()

    private sealed interface EntryRead {
        class Data(val bytes: ByteArray) : EntryRead
        data object Missing : EntryRead
        data object TooLarge : EntryRead
    }

    /** 只解压指定条目，避免把 DOCX 里的图片全读进内存。 */
    private fun readNamedEntry(bytes: ByteArray, wantedPath: String): EntryRead {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory &&
                    entry.name.equals(wantedPath, ignoreCase = true)
                ) {
                    val data = readEntry(zip, MAX_ENTRY_BYTES)
                        ?: return EntryRead.TooLarge
                    return EntryRead.Data(data)
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return EntryRead.Missing
    }

    /**
     * 从 document.xml 取正文。
     *
     * 只认 `<w:t>` 里的文字（`w:delText` 是修订删除、`w:instrText` 是域代码，
     * 都不该读给用户）；`</w:p>` 断段，`w:br` 断行，`w:tab` 变空格。
     */
    private fun wordXmlToText(xml: String): String {
        val builder = StringBuilder()
        for (match in WORD_TOKEN.findAll(xml)) {
            val textGroup = match.groups[1]
            if (textGroup != null) {
                builder.append(decodeEntities(textGroup.value))
                continue
            }
            when {
                match.value.equals("</w:p>", ignoreCase = true) -> builder.append('\n')
                match.value.contains("br", ignoreCase = true) -> builder.append('\n')
                else -> builder.append(' ')
            }
        }
        return builder.toString()
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lineSequence()
            .joinToString("\n") { it.trimEnd() }
            .replace(THREE_NEWLINES, "\n\n")
            .trim()
    }

    private val WORD_TOKEN = Regex(
        """<w:t(?:\s[^>]*)?>(.*?)</w:t>|<w:br\b[^>]*/>|<w:tab\b[^>]*/>|</w:p>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )

    /** container.xml 里的第一个 rootfile full-path。 */
    private fun parseRootFilePath(container: ByteArray): String? {
        val text = BrailleFileDecoder.decode(container)
        val rootFileTag = ROOTFILE_TAG.find(text)?.value ?: return null
        return attribute(rootFileTag, "full-path")?.takeIf { it.isNotBlank() }
    }

    /** OPF 清单与 spine 的中间结果。 */
    private class PackageDoc(
        private val items: Map<String, Item>,
        private val spineIds: List<String>
    ) {
        class Item(val path: String, val mediaType: String) {
            val isHtml: Boolean
                get() = mediaType == "application/xhtml+xml" ||
                    mediaType == "text/html" ||
                    path.endsWith(".xhtml") || path.endsWith(".html") ||
                    path.endsWith(".htm")
        }

        /** spine 优先；spine 为空时退回清单里所有 HTML 条目。 */
        fun chapterPaths(): List<String> {
            if (spineIds.isNotEmpty()) {
                return spineIds.mapNotNull { items[it] }.filter { it.isHtml }.map { it.path }
            }
            return items.values.filter { it.isHtml }.map { it.path }
        }
    }

    private fun parsePackage(opf: ByteArray, rootPath: String): PackageDoc {
        val text = BrailleFileDecoder.decode(opf)
        val baseDir = rootPath.substringBeforeLast('/', "")
        val manifestItems = LinkedHashMap<String, PackageDoc.Item>()
        for (match in ITEM_TAG.findAll(text)) {
            val tag = match.value
            val id = attribute(tag, "id")
            val href = attribute(tag, "href")
            if (id.isNullOrBlank() || href.isNullOrBlank()) {
                continue
            }
            manifestItems[id] = PackageDoc.Item(
                path = resolvePath(baseDir, href),
                mediaType = (attribute(tag, "media-type") ?: "").lowercase(),
            )
        }
        val spine = ArrayList<String>()
        for (match in ITEMREF_TAG.findAll(text)) {
            val idRef = attribute(match.value, "idref")
            if (!idRef.isNullOrBlank()) {
                spine.add(idRef)
            }
        }
        return PackageDoc(manifestItems, spine)
    }

    /** 从标签文本里取属性值，单双引号都认。 */
    private fun attribute(tag: String, name: String): String? {
        val match = Regex(
            """\b${Regex.escape(name)}\s*=\s*(?:"([^"]*)"|'([^']*)')""")
            .find(tag) ?: return null
        return match.groupValues[1].ifEmpty { match.groupValues[2] }
    }

    private val ROOTFILE_TAG = Regex("""<[\w:]*rootfile\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val ITEM_TAG = Regex("""<[\w:]*item\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val ITEMREF_TAG = Regex("""<[\w:]*itemref\b[^>]*>""", RegexOption.IGNORE_CASE)

    /**
     * 把 OPF 里的相对 href 解析成 zip 内的绝对路径。
     *
     * 处理 `../`、`./`、百分号转义与 `#fragment`；不处理指向其它协议的情况
     * （EPUB 规范禁止外部资源）。
     */
    private fun resolvePath(baseDir: String, href: String): String {
        val withoutFragment = href.substringBefore('#')
        val decoded = runCatching { URLDecoder.decode(withoutFragment, "UTF-8") }
            .getOrDefault(withoutFragment)
        val raw = if (decoded.startsWith("/")) {
            decoded.trimStart('/')
        } else if (baseDir.isEmpty()) {
            decoded
        } else {
            "$baseDir/$decoded"
        }
        val segments = ArrayList<String>()
        for (segment in raw.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isNotEmpty()) {
                    segments.removeAt(segments.size - 1)
                }
                else -> segments.add(segment)
            }
        }
        return segments.joinToString("/")
    }

    // ------------------------------------------------------------------
    // HTML → 纯文字
    // ------------------------------------------------------------------

    /**
     * 去标签、解码实体、按块级元素断行。
     *
     * 不做 HTML 解析器级别的严格校验：EPUB 里的 XHTML 与网页 HTML 都能过，
     * 目标只是给翻译链路一段文字，不还原排版。
     */
    internal fun htmlToText(html: String): String {
        var text = html
        text = COMMENT.replace(text, "")
        text = SCRIPT_STYLE_HEAD.replace(text, "")
        text = BLOCK_TAG.replace(text, "\n")
        text = ANY_TAG.replace(text, "")
        text = decodeEntities(text)
        text = text.replace("\r\n", "\n").replace('\r', '\n')
        text = text.lineSequence()
            .joinToString("\n") { it.trimEnd() }
            .replace(THREE_NEWLINES, "\n\n")
            .trim()
        return text
    }

    private val COMMENT = Regex("(?is)<!--.*?-->")
    private val SCRIPT_STYLE_HEAD = Regex("(?is)<(script|style|head)[^>]*>.*?</\\1>")
    private val BLOCK_TAG = Regex(
        "(?i)<(?:br|p|div|li|tr|h[1-6]|section|article|header|footer|" +
            "blockquote|pre|table|ul|ol|dl|dt|dd|hr)[^>]*>|" +
            "</(?:p|div|li|tr|h[1-6]|section|article|header|footer|" +
            "blockquote|pre|table|ul|ol|dl|dt|dd)>"
    )
    private val ANY_TAG = Regex("<[^>]+>")
    private val THREE_NEWLINES = Regex("\n{3,}")

    /** 具名实体覆盖中文电子书最常见的几种；其余交给数字实体处理。 */
    private fun decodeEntities(source: String): String {
        var text = source
        text = NUMERIC_ENTITY.replace(text) { match ->
            val isHex = match.groupValues[1].isNotEmpty()
            val code = runCatching {
                if (isHex) {
                    match.groupValues[2].toInt(16)
                } else {
                    match.groupValues[2].toInt()
                }
            }.getOrNull()
            if (code != null && code in MIN_CODE_POINT..MAX_CODE_POINT) {
                String(Character.toChars(code))
            } else {
                match.value
            }
        }
        text = text.replace("&nbsp;", "\u00A0")
        text = text.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
        return text
    }

    private val NUMERIC_ENTITY = Regex("&#(x?)([0-9a-fA-F]+);")
    private const val MIN_CODE_POINT = 1
    private const val MAX_CODE_POINT = 0x10FFFF
}
