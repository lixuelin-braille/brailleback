/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 仪器测试：导出排版弹窗里「按点显器方数」这一项与顶部提示是否真的渲染出来。
 *
 * 为什么需要它：这一项只在连接点显器时才存在，纯 JVM 测不到渲染；而它是给
 * 读屏用户确认「设备方数读到了」的唯一可见证据，必须真渲染一遍。
 */

package com.linxi.brailleback.compose

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
class ExportGeometryDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val followLabel = "按点显器：32 方 × 25 行（跟随设备）"
    private val fixedLabel = "30 方 × 25 行"
    private val customLabel = "自定义…"
    private val hint = "已连接点显器，设备上报 32 方，可以按它排版"

    private val readerLabels = ReaderLabels(
        open = "打开", prev = "上一页", next = "下一页", send = "发送点显",
        toc = "目录", bookmark = "书签", goto = "跳页", export = "导出为盲文",
        fontSize = "字号", navHint = "长按逐行微调", ok = "确定", cancel = "取消",
        delete = "删除", clearAll = "清空",
    )

    @Composable
    private fun Dialog(picked: MutableList<Int>) {
        BrailleReaderScreen(
            uiState = ReaderUiState(readerText = "一个", focusSegments = listOf("一个")),
            labels = readerLabels,
            dialog = ReaderDialog.ExportGeometry(
                title = "选择版面",
                labels = listOf(followLabel, fixedLabel, customLabel),
                hint = hint,
            ),
            onOpen = {}, onPrev = {}, onPrevLine = {}, onNext = {}, onNextLine = {},
            onSend = {}, onToc = {}, onBookmark = {}, onGoto = {}, onExport = {}, onFont = {},
            onFontSizePick = {}, onTocPick = {}, onBookmarkPick = {},
            onBookmarkDelete = {}, onBookmarkClear = {}, onDeletePick = {},
            onGotoConfirm = {}, onExportPick = {},
            onGeometryPick = { picked += it },
            onCustomGeometryConfirm = { _, _ -> }, onDismissDialog = {},
        )
    }

    @Test
    fun followItemAndDeviceCellsHintAreShown() {
        composeRule.setContent { MaterialTheme { Dialog(mutableListOf()) } }
        composeRule.onNodeWithText(hint, substring = true).assertIsDisplayed()
        composeRule.onNodeWithText(followLabel, substring = true).assertIsDisplayed()
        composeRule.onNodeWithText(customLabel).assertIsDisplayed()
    }

    @Test
    fun tappingFollowItemReportsFirstIndex() {
        val picked = mutableListOf<Int>()
        composeRule.setContent { MaterialTheme { Dialog(picked) } }
        composeRule.onNodeWithText(followLabel, substring = true).performClick()
        composeRule.onNodeWithText(fixedLabel, substring = true).performClick()
        assertEquals(listOf(0, 1), picked)
    }
}
