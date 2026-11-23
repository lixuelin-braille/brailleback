/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 盲文书书架薄壳：状态与动作全部在 [com.linxi.brailleback.compose.BookshelfController]，
 * 本类只负责承载 Compose 界面，并保留阅读器回写「最近打开」所需的静态入口。
 *
 * 存储层与编码格式刻意不动 —— 书架用的是自己的 `bookshelf` 偏好文件，
 * 不与 `SettingsStore`（默认偏好）共用。
 */

package com.linxi.brailleback

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.linxi.brailleback.compose.BookshelfController
import com.linxi.brailleback.compose.BookshelfScreen

class BookshelfActivity : ComponentActivity() {

    private lateinit var controller: BookshelfController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = BookshelfController(this)
        controller.refreshList()

        setContent {
            BookshelfScreen(
                labels = controller.labels(),
                entries = controller.entries,
                dialog = controller.dialog,
                actions = controller.actions(),
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // 阅读器里打开过的书会 recordRecent 进书架，返回时刷新列表。
        // 列表用 uri 做 key，刷新后滚动位置仍然保持。
        controller.refreshList()
    }

    companion object {
        /**
         * 记录一本打开过的书进书架（阅读器解码/翻译成功后调用）。
         * 去重置顶、上限 200；签名保持不变：阅读器按此静态入口回写。
         * 实际存储由 [BookshelfController.recordRecent] 承接。
         */
        fun recordRecent(context: Activity, uri: String, name: String) {
            BookshelfController.recordRecent(context, uri, name)
        }
    }
}
