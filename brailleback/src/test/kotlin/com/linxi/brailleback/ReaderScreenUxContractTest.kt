/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 阅读界面的可达性/交互契约：
 *  1. 根布局不得 mergeDescendants，否则整屏合成一个语义节点，正文读不出来；
 *  2. 没有内容时不摆一排停用按钮，只留提示；
 *  3. 阅读界面不再有「打开书籍」按钮，入口统一在书架；
 *  4. 翻译服务未就绪时先铺原文，不让用户对着空屏等。
 */

package com.linxi.brailleback

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderScreenUxContractTest {

    private fun source(rel: String): String {
        val candidates = listOf(
            File("src/main/java/com/linxi/brailleback/$rel"),
            File("brailleback/src/main/java/com/linxi/brailleback/$rel"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("找不到源文件：$rel")
        return file.readText()
    }

    @Test
    fun `根布局不再整屏合并语义`() {
        val screen = source("compose/BrailleReaderScreen.kt")
        // 根 Column 的修饰符链里不能出现 mergeDescendants；
        // 弹窗里的合并是另一回事，它只包住单行选项。
        val rootBlock = screen.substringAfter("Column(")
            .substringBefore(") {")
        assertTrue("根布局仍在合并整屏语义：$rootBlock",
            !rootBlock.contains("mergeDescendants"))
    }

    @Test
    fun `无内容时不摆一排停用按钮`() {
        val screen = source("compose/BrailleReaderScreen.kt")
        assertTrue("按钮区没有被 hasContent 包住",
            screen.contains("if (uiState.hasContent) {"))
        assertTrue("状态里缺少 hasContent", screen.contains("val hasContent: Boolean"))
    }

    @Test
    fun `阅读界面没有打开书籍按钮`() {
        val screen = source("compose/BrailleReaderScreen.kt")
        assertTrue("阅读界面仍有「打开」按钮", !screen.contains("labels.open"))
        assertTrue("onOpen 回调没清掉", !screen.contains("onOpen"))
    }

    @Test
    fun `翻译未就绪先显示原文而不是空屏`() {
        val activity = source("BrailleReaderActivity.kt")
        val waitingBlock = activity.substringAfter("if (translator == null) {")
            .substringBefore("return\n        }")
        assertTrue("未就绪时没有铺原文：$waitingBlock",
            waitingBlock.contains("setBrailleStream(") &&
                waitingBlock.contains("isTranslated = false"))
        assertTrue("未就绪时没有给出提示", waitingBlock.contains(
            "braille_reader_translator_pending"))
    }
}
