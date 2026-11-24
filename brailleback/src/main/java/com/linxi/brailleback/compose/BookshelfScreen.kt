/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 盲文书架的 Compose 实现。
 *
 * 这屏对读屏用户的核心不是「长什么样」，而是「一本书念什么」：
 * 条目必须把书名、格式、上次打开时间合成一条朗读（旧实现靠 contentDescription），
 * 长按弹出的三项菜单与删除确认都不能省 —— 误触长按的代价是丢掉一本书的记录。
 * 「清空书架」按用户要求已去掉：一次抹掉全部记录不可逆。
 */

package com.linxi.brailleback.compose

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** 一本书在列表里的展示数据：name 给眼睛看，description 给读屏念。 */
internal data class BookshelfEntry(
    val uri: String,
    val name: String,
    val description: String,
)

/** 一次性收集好的文案，让 Screen 不依赖 Context。 */
internal data class BookshelfLabels(
    val title: String,
    val importBook: String,
    val stripEntry: String,
    val mathEntry: String,
    val empty: String,
    val open: String,
    val rename: String,
    val delete: String,
    val confirm: String,
    val cancel: String,
)

/** 当前要弹的对话框；null 表示没有。状态放在 Activity，因为文案要现算。 */
internal sealed interface BookshelfDialog {
    /** 长按菜单：打开 / 重命名 / 删除。 */
    data class Menu(val entry: BookshelfEntry, val title: String) : BookshelfDialog

    data class DeleteConfirm(val uri: String, val title: String, val message: String) : BookshelfDialog

    data class Rename(val uri: String, val initialName: String) : BookshelfDialog
}

/** 屏幕要回调给 Activity 的动作。收进接口是刻意的：11 个 lambda 参数会撞 detekt 的参数上限，
 *  而且这些动作都作用在同一份书架状态上，本来就是一组的。 */
internal interface BookshelfActions {
    fun onImport()
    /** 图片转点显：跳到图片分页送点显器的独立页。 */
    fun onOpenStrip()
    /** 数学公式转盲文：跳到数学公式编码独立页。 */
    fun onOpenMath()
    fun onOpen(entry: BookshelfEntry)
    fun onLongPress(entry: BookshelfEntry)
    fun onDismissDialog()
    fun onMenuOpen(entry: BookshelfEntry)
    fun onMenuRename(entry: BookshelfEntry)
    fun onMenuDelete(entry: BookshelfEntry)
    fun onConfirmDelete()
    fun onConfirmRename(newName: String)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BookshelfScreen(
    labels: BookshelfLabels,
    entries: List<BookshelfEntry>,
    dialog: BookshelfDialog?,
    actions: BookshelfActions,
) {
    Scaffold(
        topBar = {
            // 「清空书架」按钮已按用户要求去掉：一次抹掉全部记录不可逆，
            // 逐本长按删除足够用，也避免误触。
            TopAppBar(title = { Text(labels.title, maxLines = 1) })
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = padding,
        ) {
            if (entries.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = labels.empty,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 24.dp),
                    )
                }
            }
            item(key = "import") {
                Button(
                    onClick = actions::onImport,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(labels.importBook)
                }
            }
            item(key = "strip_entry") {
                TextButton(
                    onClick = actions::onOpenStrip,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Text(labels.stripEntry)
                }
            }
            item(key = "math_entry") {
                TextButton(
                    onClick = actions::onOpenMath,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Text(labels.mathEntry)
                }
            }
            // key 里带下标：同一本书可能被导入两次（同一 URI 出现两行），
            // 只用 URI 作 key 会撞车，Compose 对重复 key 直接抛异常崩屏。
            itemsIndexed(entries, key = { index, entry -> "book:$index:${entry.uri}" }) { _, entry ->
                BookRow(
                    entry = entry,
                    onClick = { actions.onOpen(entry) },
                    onLongClick = { actions.onLongPress(entry) },
                )
            }
        }
    }

    when (dialog) {
        is BookshelfDialog.Menu -> AlertDialog(
            onDismissRequest = actions::onDismissDialog,
            title = { Text(dialog.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { actions.onMenuOpen(dialog.entry) }) { Text(labels.open) }
                    TextButton(onClick = { actions.onMenuRename(dialog.entry) }) { Text(labels.rename) }
                    TextButton(onClick = { actions.onMenuDelete(dialog.entry) }) { Text(labels.delete) }
                }
            },
            // 菜单项已在正文里，确认/取消按钮不再重复，只留一个「取消」出口。
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = actions::onDismissDialog) { Text(labels.cancel) }
            },
        )

        is BookshelfDialog.DeleteConfirm -> AlertDialog(
            onDismissRequest = actions::onDismissDialog,
            title = { Text(dialog.title) },
            text = { Text(dialog.message) },
            confirmButton = { TextButton(onClick = actions::onConfirmDelete) { Text(labels.confirm) } },
            dismissButton = { TextButton(onClick = actions::onDismissDialog) { Text(labels.cancel) } },
        )

        is BookshelfDialog.Rename -> {
            var name by remember(dialog.uri) { mutableStateOf(dialog.initialName) }
            AlertDialog(
                onDismissRequest = actions::onDismissDialog,
                title = { Text(labels.rename) },
                text = {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        label = { Text(labels.rename) },
                    )
                },
                confirmButton = { TextButton(onClick = { actions.onConfirmRename(name) }) { Text(labels.confirm) } },
                dismissButton = { TextButton(onClick = actions::onDismissDialog) { Text(labels.cancel) } },
            )
        }

        null -> Unit
    }
}

/**
 * 条目：整行可点、可长按；朗读内容用 description 覆盖（旧实现是 contentDescription）。
 * 视觉上仍显示书名，不把「格式 + 时间」铺成三行。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookRow(
    entry: BookshelfEntry,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Text(
        text = entry.name,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics(mergeDescendants = true) { contentDescription = entry.description }
            .padding(horizontal = 16.dp, vertical = 14.dp),
    )
}
