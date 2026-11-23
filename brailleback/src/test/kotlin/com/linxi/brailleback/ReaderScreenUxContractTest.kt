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

    @Test
    fun `翻译没完成时导出被拦下并说明原因`() {
        val activity = source("BrailleReaderActivity.kt")
        val exportBlock = activity.substringAfter("private fun showExportMenu() {")
            .substringBefore("// 标题与说明都按")
        assertTrue("导出没有拦未翻译的原文流",
            exportBlock.contains("mSourceKind == ReaderSourceKind.TEXT && !mIsTranslated"))
        assertTrue("没说明「正在翻译」", exportBlock.contains(
            "braille_reader_export_wait_translate"))
        assertTrue("没说明「翻译没就绪」", exportBlock.contains(
            "braille_reader_export_need_translate"))
        assertTrue("翻译完没接着打开导出（mPendingExport 未落地）",
            activity.contains("if (mPendingExport)"))
    }

    @Test
    fun `翻译服务久不就绪会说一次不可用`() {
        val activity = source("BrailleReaderActivity.kt")
        assertTrue("没有翻译不可用提示",
            activity.contains("braille_reader_translator_unavailable"))
        assertTrue("延时没走 lifecycleScope（裸 Handler 会在销毁后回调）",
            activity.contains("lifecycleScope.launch {") &&
                activity.contains("TRANSLATOR_WARN_DELAY_MS"))
    }
}
