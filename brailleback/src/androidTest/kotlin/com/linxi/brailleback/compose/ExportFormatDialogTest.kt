/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 仪器测试：导出格式弹窗的「用途说明」是否真的能被读到、点得到。
 *
 * 为什么需要它：弹窗原来是三个纯格式名（BRF / BRL / Text），读屏用户听到
 * 名字也不知道哪个能上点显器、哪个能刻印。现在每条格式都带一句用途说明，并且
 * 格式名与说明合并成同一个焦点。合并语义、说明文字是否存在、点击是否回到正确下标，
 * 这三件事只有真实渲染才能验证，纯 JVM 测不到。
 *
 * 不启动 Activity：BrailleReaderScreen 是纯展示层（状态走参数、动作走回调），
 * 直接渲染它，测试不依赖 TranslatorManager 与点显器连接。
 */

package com.linxi.brailleback.compose

import com.linxi.brailleback.ReaderSegment
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExportFormatDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val labels = listOf(
        "BRF (ASCII braille)",
        "BRL (Unicode braille)",
        "Text (Unicode braille)",
    )

    private val descriptions = listOf(
        "BRF 用途说明：刻印机与支持 .brf 的点显器",
        "BRL 用途说明：认 U+2800 的设备与编辑器",
        "TXT 用途说明：手机电脑上直接读或改",
    )

    private val hint = "想刻印或上点显器，先选 BRF"

    private val readerLabels = ReaderLabels(
        prev = "上一页",
        next = "下一页",
        toc = "目录",
        bookmark = "书签",
        goto = "跳页",
        export = "导出为盲文",
        fontSize = "字号",
        navHint = "长按逐行微调",
        ok = "确定",
        cancel = "取消",
        delete = "删除",
        clearAll = "清空",
        pageLine = { _, _ -> "行" },
        pageScrubber = { _, _ -> "页" },
        pageScrubberAction = "页码动作",
        pageLineAction = "行号动作",
    )

    @Composable
    private fun Dialog(picked: MutableList<Int>) {
        BrailleReaderScreen(
            uiState = ReaderUiState(
                readerText = "一个",
                focusSegments = listOf(ReaderSegment("一个", 0)),
            ),
            scrollState = ReaderScrollState(),
            labels = readerLabels,
            dialog = ReaderDialog.ExportFormat(
                title = "选择导出格式",
                labels = labels,
                descriptions = descriptions,
                hint = hint,
            ),
            onPrev = {}, onPrevLine = {}, onNext = {}, onNextLine = {},
            onToc = {}, onBookmark = {}, onGoto = {}, onExport = {}, onFont = {},
            onFontSizePick = {}, onTocPick = {}, onBookmarkPick = {},
            onBookmarkDelete = {}, onBookmarkClear = {}, onDeletePick = {},
            onGotoConfirm = {}, onExportPick = { picked += it },
            onGeometryPick = {}, onCustomGeometryConfirm = { _, _ -> }, onDismissDialog = {},
        )
    }

    @Test
    fun everyFormatShowsItsPurposeAndTheHintIsRead() {
        composeRule.setContent { MaterialTheme { Dialog(mutableListOf()) } }
        composeRule.onNodeWithText(hint, substring = true).assertIsDisplayed()
        labels.forEach { label ->
            composeRule.onNodeWithText(label).assertIsDisplayed()
        }
        descriptions.forEach { description ->
            composeRule.onNodeWithText(description, substring = true).assertIsDisplayed()
        }
    }

    @Test
    fun tappingFormatRowReportsItsIndex() {
        val picked = mutableListOf<Int>()
        composeRule.setContent { MaterialTheme { Dialog(picked) } }
        composeRule.onNodeWithText(labels[0]).performClick()
        composeRule.onNodeWithText(labels[2]).performClick()
        assertEquals(listOf(0, 2), picked)
    }
}
