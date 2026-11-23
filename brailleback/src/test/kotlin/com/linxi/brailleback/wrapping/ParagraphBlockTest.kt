package com.linxi.brailleback.wrapping

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 公众号正文段块识别：只缩进网页正文的段落，不碰标题、作者、时间、按钮。
 */
class ParagraphBlockTest {

    private fun shouldIndent(
        className: String? = "android.view.View",
        viewId: String? = null,
        contentLength: Int = 24,
        widthPx: Int = 972,
        screenWidthPx: Int = 1080,
        insideWebView: Boolean = true,
        visible: Boolean = true
    ): Boolean = ParagraphBlock.shouldIndent(
        className, viewId, contentLength, widthPx, screenWidthPx,
        insideWebView, visible
    )

    @Test
    fun `wechat article paragraph is a body block`() {
        assertTrue(shouldIndent())
    }

    @Test
    fun `article title is excluded by view id`() {
        assertFalse(shouldIndent(viewId = "activity-name", contentLength = 25))
    }

    @Test
    fun `short labels and counts are excluded`() {
        assertFalse(shouldIndent(contentLength = 4, widthPx = 176))
        assertFalse(shouldIndent(contentLength = 9))
    }

    @Test
    fun `narrow nodes are excluded`() {
        // 时间栏 440/1080 = 41%，低于 60%。
        assertFalse(shouldIndent(widthPx = 440))
    }

    @Test
    fun `only webview content is formatted`() {
        assertFalse(shouldIndent(insideWebView = false))
    }

    @Test
    fun `buttons and images are excluded`() {
        assertFalse(shouldIndent(className = "android.widget.Button"))
        assertFalse(shouldIndent(className = "android.widget.Image"))
    }

    @Test
    fun `invisible or unsized nodes are excluded`() {
        assertFalse(shouldIndent(visible = false))
        assertFalse(shouldIndent(widthPx = 0))
        assertFalse(shouldIndent(screenWidthPx = 0))
    }

    @Test
    fun `parent description equal to child text is a duplicate`() {
        val paragraph = "那天我在写，全球风暴降临，而人类，也只剩押涨跌。"
        assertTrue(ParagraphBlock.isDuplicateText(paragraph, paragraph))
        assertTrue(ParagraphBlock.isDuplicateText("$paragraph ", " $paragraph"))
        assertFalse(ParagraphBlock.isDuplicateText(paragraph, "下一段"))
        assertFalse(ParagraphBlock.isDuplicateText(null, paragraph))
        assertFalse(ParagraphBlock.isDuplicateText("  ", paragraph))
        assertFalse(ParagraphBlock.isDuplicateText(paragraph, null))
    }

    private fun shouldIndentNative(
        className: String? = "android.widget.TextView",
        viewId: String? = null,
        contentLength: Int = 165,
        widthPx: Int = 990,
        screenWidthPx: Int = 1080,
        focusable: Boolean = true,
        clickable: Boolean = false,
        visible: Boolean = true
    ): Boolean = ParagraphBlock.shouldIndentNative(
        className, viewId, contentLength, widthPx, screenWidthPx,
        focusable, clickable, visible
    )

    @Test
    fun `zhihu answer paragraph is a native reading block`() {
        // 实测：[45,823][1035,1418]、165 字、可聚焦、不可点击、无 view id。
        assertTrue(shouldIndentNative())
    }

    @Test
    fun `zhihu question title is clickable and excluded`() {
        assertFalse(shouldIndentNative(contentLength = 21, clickable = true))
    }

    @Test
    fun `native author and timestamp rows are excluded`() {
        // 作者简介 523/1080 = 48%，低于 60%。
        assertFalse(shouldIndentNative(contentLength = 15, widthPx = 523))
        assertFalse(shouldIndentNative(contentLength = 10, widthPx = 302))
    }

    @Test
    fun `native nodes with view ids are chrome not prose`() {
        assertFalse(shouldIndentNative(viewId = "com.zhihu.android:id/statusTv", contentLength = 40))
    }

    @Test
    fun `native blocks must be focusable and not clickable`() {
        assertFalse(shouldIndentNative(focusable = false))
        assertFalse(shouldIndentNative(clickable = true))
    }

    @Test
    fun `native blocks must be text or view nodes with enough text`() {
        // Compose 的语义文本节点类名是 android.view.View。
        assertTrue(shouldIndentNative(className = "android.view.View"))
        assertFalse(shouldIndentNative(className = "android.widget.Button"))
        assertFalse(shouldIndentNative(className = "android.widget.Image"))
        assertFalse(shouldIndentNative(contentLength = 9))
        assertFalse(shouldIndentNative(visible = false))
    }

    @Test
    fun `compose paragraph without view id is a native reading block`() {
        assertTrue(
            shouldIndentNative(
                className = "android.view.View",
                viewId = null,
                contentLength = 42,
                widthPx = 1008,
                focusable = true,
                clickable = false
            )
        )
    }

    @Test
    fun `custom clickable control is excluded even when wide`() {
        assertFalse(
            shouldIndentNative(
                className = "android.view.View",
                contentLength = 60,
                widthPx = 1080,
                clickable = true
            )
        )
    }

    private fun containerShape(
        className: String? = "android.widget.FrameLayout",
        contentLength: Int = 391,
        widthPx: Int = 1024,
        screenWidthPx: Int = 1080,
        focusable: Boolean = true,
        visible: Boolean = true,
        hasDuplicateChildText: Boolean = true
    ): Boolean = ParagraphBlock.isParagraphContainerShape(
        className, contentLength, widthPx, screenWidthPx,
        focusable, visible, hasDuplicateChildText
    )

    @Test
    fun `tiantan paragraph container with duplicate child text is a block`() {
        // 实测：FrameLayout [28,121][1052,256]、391 字、可聚焦、可点击；
        // 子 TextView(id/text1) 文本完全相同。
        assertTrue(containerShape())
    }

    @Test
    fun `container requires duplicate child text`() {
        assertFalse(containerShape(hasDuplicateChildText = false))
    }

    @Test
    fun `ordinary clickable cards are not paragraph containers`() {
        assertFalse(containerShape(className = "android.widget.Button"))
        assertFalse(containerShape(className = "android.widget.TextView"))
        assertFalse(containerShape(className = "android.widget.FrameLayout", focusable = false))
        assertFalse(containerShape(className = "android.widget.FrameLayout", contentLength = 9))
        assertFalse(containerShape(className = "android.widget.FrameLayout", widthPx = 400))
        assertFalse(containerShape(className = "android.widget.FrameLayout", visible = false))
    }

    @Test
    fun `repeated view id is allowed only for a prose template`() {
        assertFalse(ParagraphBlock.isRepeatedViewIdAllowed(1))
        assertTrue(ParagraphBlock.isRepeatedViewIdAllowed(2))
        assertTrue(ParagraphBlock.isRepeatedViewIdAllowed(15))
    }
}
