/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback

import android.text.Spanned
import android.util.Log
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.googlecode.eyesfree.braille.translate.BrailleTranslator
import com.googlecode.eyesfree.braille.translate.TranslationResult
import com.googlecode.eyesfree.utils.AccessibilityNodeInfoUtils
import com.googlecode.eyesfree.utils.LogUtils
/**
 * Builder-like class used to construct the content to put on the display.
 *
 * This object contains a [CharSequence] that represents what
 * characters to put on the display.  This sequence can be a
 * Spannable so that the characters can be annotated with
 * information about cursors and focus which will affect how the content
 * is presented on the display.  Arbitrary java objects may also be
 * included in the Spannable which can be used to determine what
 * action to take when the user invokes key commands related to a
 * particular position on the display (i.e. involving a cursor routing
 * key).  In particular, [AccessibilityNodeInfoCompat]s may be
 * included, in which case they will be recycled by the
 * [Content.recycle] method.  To facilitate movement outside the
 * bounds of the current [Content],
 * [AccessibilityNodeInfoCompat]s that represent the extent of the
 * content can also be added, but in that case, they are not included in
 * the Spannable.
 */
/**
 * 构造一块要送到点显器的内容。
 *
 * 只有一个参数时等于“只设文本”的快捷构造（原来两个重载构造函数合并成一个
 * 带默认值的主构造函数，Kotlin 调用 `Content()` / `Content(text)` 都不变）。
 */
class Content @JvmOverloads constructor(text: CharSequence? = null) {
    companion object {
        /**
         * Pan strategy that moves the display to the leftmost position.
         * This is the default panning strategy.
         */
        const val PAN_RESET = 0

        /**
         * Pan strategy that positions the display so that it overlaps the
         * start of a selection or focus mark.  Falls back on [PAN_RESET]
         * if there is no selection or focus.
         */
        const val PAN_CURSOR = 1

        /**
         * Pan strategy that tries to position the display close to the
         * position that corresponds to the panning position in the previously
         * displayed content.  Spans of type
         * [AccessibilityNodeInfoCompat] are used to identify the
         * corresponding content in the old and new display content.
         * Falls back on [PAN_CURSOR] if a corresponding position can't
         * be found.
         */
        const val PAN_KEEP = 2

        /**
         * Default contraction behaviour, allow contractions unless there is a
         * selection span in the content.
         */
        const val CONTRACT_DEFAULT = 0

        /**
         * Allow contraction, regardless of the presence of a selection
         * span.
         */
        const val CONTRACT_ALWAYS_ALLOW = 1
    }

    private var text: CharSequence? = text
    private var firstNode: AccessibilityNodeInfoCompat? = null
    private var lastNode: AccessibilityNodeInfoCompat? = null
    private var panStrategy = PAN_RESET
    private var contractionMode = CONTRACT_DEFAULT
    private var splitParagraphs = false
    private var paragraphIndent = false
    private var firstParagraphIndent = false
    private var heading = false
    private var editable = false

    fun setText(textArg: CharSequence?): Content {
        text = textArg
        return this
    }

    fun getText(): CharSequence? {
        return text
    }

    fun getSpanned(): Spanned? {
        return text as? Spanned
    }

    fun setFirstNode(node: AccessibilityNodeInfoCompat): Content {
        AccessibilityNodeInfoUtils.recycleNodes(firstNode)
        firstNode = AccessibilityNodeInfoCompat.obtain(node)
        return this
    }

    fun getFirstNode(): AccessibilityNodeInfoCompat? {
        return firstNode
    }

    fun setLastNode(node: AccessibilityNodeInfoCompat): Content {
        AccessibilityNodeInfoUtils.recycleNodes(lastNode)
        lastNode = AccessibilityNodeInfoCompat.obtain(node)
        return this
    }

    fun getLastNode(): AccessibilityNodeInfoCompat? {
        return lastNode
    }

    fun setPanStrategy(strategy: Int): Content {
        panStrategy = strategy
        return this
    }

    fun getPanStrategy(): Int {
        return panStrategy
    }

    fun setContractionMode(mode: Int): Content {
        contractionMode = mode
        return this
    }

    fun getContractionMode(): Int {
        return contractionMode
    }

    fun setSplitParagraphs(value: Boolean): Content {
        splitParagraphs = value
        return this
    }

    fun isSplitParagraphs(): Boolean {
        return splitParagraphs
    }

    /**
     * 是否按“空行分段”的阅读文本排版：段首空两方，并强制下一段从
     * 行首开始。与 [splitParagraphs] 不同，单个换行不算段边界。
     */
    fun setParagraphIndent(value: Boolean): Content {
        paragraphIndent = value
        return this
    }

    fun isParagraphIndent(): Boolean {
        return paragraphIndent
    }

    /**
     * 整块内容就是一段正文时（公众号每段一个节点），段首（下标 0）
     * 也补两方空白；与 [setParagraphIndent] 配合使用。
     */
    fun setFirstParagraphIndent(value: Boolean): Content {
        firstParagraphIndent = value
        return this
    }

    fun isFirstParagraphIndent(): Boolean {
        return firstParagraphIndent
    }

    /** 整块内容是一个标题：翻译后按点显器宽度居中。 */
    fun setHeading(value: Boolean): Content {
        heading = value
        return this
    }

    fun isHeading(): Boolean {
        return heading
    }

    fun setEditable(value: Boolean): Content {
        editable = value
        return this
    }

    fun isEditable(): Boolean {
        return editable
    }

    /**
     * Translates the text content, preserving any verbatim braille that is embedded in a
     * BrailleSpan. The current implementation of this method only handles the first
     * BrailleSpan; all subsequent BrailleSpans are ignored.
     *
     * @param translator The translator used for translating the subparts of the text without
     *     embedded BrailleSpans.
     * @param cursorPosition The position of the cursor; if it occurs in a section of the text
     *     without BrailleSpans, then the final cursor position in the output braille by the
     *     translator. Otherwise, if the cursor occurs within a BrailleSpan section, the final
     *     cursor position in the output braille is set to the first braille cell of the
     *     BrailleSpan.
     * @param computerBrailleAtCursor This parameter is passed through to the translator; if
     *     true,then contracted translators are instructed to translate the word under the
     *     cursor using computer braille (instead of contracted braille) to make editing
     *     easier.
     * @return The result of translation, possibly empty, not null.
     */
    fun translateWithVerbatimBraille(
        translator: BrailleTranslator?,
        cursorPosition: Int,
        computerBrailleAtCursor: Boolean
    ): TranslationResult {
        if (translator == null) {
            return createEmptyTranslation(text)
        }
        // 原实现假定「有翻译器就一定有文本」，空文本会直接 NPE。
        // 空内容按空翻译返回，与 createEmptyTranslation 的可空语义一致。
        val text = this.text ?: return createEmptyTranslation(this.text)

        // Assume that we have at most one BrailleSpan since we currently
        // never add more than one BrailleSpan.
        // Also ignore BrailleSpans with zero-length span or no braille for
        // now because we don't currently add such BrailleSpans.
        var brailleSpan: DisplaySpans.BrailleSpan? = null
        var start = -1
        var end = -1
        val spanned = text as? Spanned
        if (spanned != null) {
            val spans =
                spanned.getSpans(0, spanned.length, DisplaySpans.BrailleSpan::class.java)
            if (spans.size > 1) {
                LogUtils.log(this, Log.WARN,
                        "More than one BrailleSpan, handling first only")
            }
            if (spans.isNotEmpty()) {
                val spanStart = spanned.getSpanStart(spans[0])
                val spanEnd = spanned.getSpanEnd(spans[0])
                if (spans[0].braille.isNotEmpty() && spanStart < spanEnd) {
                    brailleSpan = spans[0]
                    start = spanStart
                    end = spanEnd
                }
            }
        }

        if (brailleSpan != null) {
            // Chunk the text into three sections:
            // left: [0, start) - needs translation
            // mid: [start, end) - use the literal braille provided
            // right: [end, length) - needs translation
            val left = text.subSequence(0, start)
            val leftTrans = translator.translate(
                    left.toString(),
                    if (cursorPosition < start) cursorPosition else -1,
                    cursorPosition < start && computerBrailleAtCursor)

            val right = text.subSequence(end, text.length)
            val rightTrans = translator.translate(
                    right.toString(),
                    if (cursorPosition >= end) cursorPosition - end else -1,
                    cursorPosition >= end && computerBrailleAtCursor)

            // If one of the left or right translations is not valid, then
            // we will fall back by ignoring the BrailleSpan and
            // translating everything normally. (Chances are that
            // translating the whole text will fail also, but it wouldn't
            // hurt to try.)
            if (leftTrans == null || rightTrans == null) {
                LogUtils.log(this, Log.ERROR,
                        "Could not translate left or right subtranslation, "
                        + "falling back on default translation")
                return translateOrDefault(translator, cursorPosition,
                        computerBrailleAtCursor)
            }

            val startBraille = leftTrans.cells.size
            val endBraille = startBraille + brailleSpan.braille.size
            val totalBraille = endBraille + rightTrans.cells.size

            // Copy braille cells.
            val cells = ByteArray(totalBraille)
            System.arraycopy(leftTrans.cells, 0,
                    cells, 0, leftTrans.cells.size)
            System.arraycopy(brailleSpan.braille, 0,
                    cells, startBraille, brailleSpan.braille.size)
            System.arraycopy(rightTrans.cells, 0,
                    cells, endBraille, rightTrans.cells.size)

            // Copy text-to-braille indices.
            val leftTtb = leftTrans.textToBraillePositions
            val rightTtb = rightTrans.textToBraillePositions
            val textToBraille = IntArray(text.length)

            System.arraycopy(leftTtb, 0, textToBraille, 0, start)
            for (i in start until end) {
                textToBraille[i] = startBraille
            }
            for (i in end until textToBraille.size) {
                textToBraille[i] = endBraille + rightTtb[i - end]
            }

            // Copy braille-to-text indices.
            val leftBtt = leftTrans.brailleToTextPositions
            val rightBtt = rightTrans.brailleToTextPositions
            val brailleToText = IntArray(cells.size)

            System.arraycopy(leftBtt, 0, brailleToText, 0, startBraille)
            for (i in startBraille until endBraille) {
                brailleToText[i] = start
            }
            for (i in endBraille until totalBraille) {
                brailleToText[i] = end + rightBtt[i - endBraille]
            }

            // Get cursor.
            val cursor: Int = when {
                cursorPosition < 0 -> -1
                cursorPosition < start -> leftTrans.cursorPosition
                cursorPosition < end -> startBraille
                else -> endBraille + rightTrans.cursorPosition
            }

            return TranslationResult(cells, textToBraille,
                    brailleToText, cursor)
        }

        return translateOrDefault(translator, cursorPosition,
                computerBrailleAtCursor)
    }

    private fun translateOrDefault(
        translator: BrailleTranslator,
        cursorPosition: Int,
        computerBrailleAtCursor: Boolean
    ): TranslationResult {
        val content = text ?: return createEmptyTranslation(null)
        val translation =
            translator.translate(content.toString(), cursorPosition, computerBrailleAtCursor)
        if (translation != null) {
            return translation
        }

        return createEmptyTranslation(text)
    }

    fun recycle() {
        AccessibilityNodeInfoUtils.recycleNodes(firstNode, lastNode)
        firstNode = null
        lastNode = null
        text?.let { DisplaySpans.recycleSpans(it) }
        text = null
    }

    override fun toString(): String {
        return String.format("Content {text=%s}", getText())
    }
}

/**
 * 空翻译结果：只有与文本等长的“文本→盲文位置”映射，没有点阵。
 * 原来藏在 `DisplayManager` 的私有 companion 里，`Content` 与 `DisplayManager`
 * 都要用；迁到这里是为了让 `Content` 不再依赖外层类的作用域。
 */
internal fun createEmptyTranslation(text: CharSequence?): TranslationResult {
    val textLength = text?.length ?: 0
    return TranslationResult(ByteArray(0), IntArray(textLength), IntArray(0), 0)
}
