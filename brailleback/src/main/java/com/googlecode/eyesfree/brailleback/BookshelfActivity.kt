/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 盲文书架：只做「导入 + 书籍管理（增删改查）」，不做文件夹树浏览/收藏。
 *
 * - 增：导入文件（系统选择器），或阅读器里打开过的书自动进书架
 * - 查：书架列表展示全部藏书，点击直接阅读；读屏念书名 + 格式 + 上次打开时间
 * - 删：长按条目删除单本（带确认）；顶栏「清空书架」清空全部（带确认）
 * - 改：长按条目重命名显示名
 *
 * 2026-11-18：界面从 ListView + ArrayAdapter 换成 Compose（`bookshelf.xml` 已删除），
 * 存储层与编码格式刻意不动 —— 书架用的是自己的 `bookshelf` 偏好文件，
 * 不与 `SettingsStore`（默认偏好）共用。
 */

package com.googlecode.eyesfree.brailleback

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.googlecode.eyesfree.brailleback.compose.BookshelfActions
import com.googlecode.eyesfree.brailleback.compose.BookshelfDialog
import com.googlecode.eyesfree.brailleback.compose.BookshelfEntry
import com.googlecode.eyesfree.brailleback.compose.BookshelfLabels
import com.googlecode.eyesfree.brailleback.compose.BookshelfScreen
import com.googlecode.eyesfree.utils.LogUtils

class BookshelfActivity : ComponentActivity() {

    private var mBooks: List<Book> = emptyList()

    // 列表与对话框状态放 Activity 层：内容来自偏好文件，onResume 时要整体刷新。
    private var entries by mutableStateOf<List<BookshelfEntry>>(emptyList())
    private var dialog by mutableStateOf<BookshelfDialog?>(null)

    /** 导入书籍：系统文档选择器，取消时回调为 null。 */
    private val importBookLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            onDocumentSelected(uri)
        }
    }

    /**
     * 一本书：uri 唯一标识，name 为显示名（可重命名）。
     * ext/openedAt 用于列表朗读（书名 + 格式 + 上次打开时间）；
     * 旧版存储无这两个字段，[decode] 自动回退为「按 uri 推断 + 未知时间」。
     */
    private class Book(
        val uri: String,
        var name: String,
        val ext: String,
        val openedAt: Long)

    /** 朗读文本：书名 + 类型 + 上次打开时间（旧数据无时间则省略）。 */
    private fun describe(book: Book): String {
        val type = typeLabel(book.ext)
        return if (book.openedAt > 0) {
            val when_ = DateUtils.getRelativeTimeSpanString(
                book.openedAt, System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS)
            getString(R.string.bookshelf_item_desc, book.name, type, when_)
        } else {
            getString(R.string.bookshelf_item_desc_no_time, book.name, type)
        }
    }

    private fun typeLabel(ext: String): String = when (ext) {
        "brf" -> getString(R.string.bookshelf_type_brf)
        "bdo" -> getString(R.string.bookshelf_type_bdo)
        "txt" -> getString(R.string.bookshelf_type_txt)
        "epub" -> getString(R.string.bookshelf_type_epub)
        "html", "htm", "xhtml" -> getString(R.string.bookshelf_type_html)
        "md", "markdown" -> getString(R.string.bookshelf_type_md)
        "docx", "docm" -> getString(R.string.bookshelf_type_docx)
        "" -> getString(R.string.bookshelf_type_unknown)
        else -> ext.uppercase()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshList()

        setContent {
            val labels = BookshelfLabels(
                title = getString(R.string.bookshelf_title),
                importBook = getString(R.string.bookshelf_import),
                empty = getString(R.string.bookshelf_empty),
                clear = getString(R.string.bookshelf_clear),
                open = getString(R.string.bookshelf_open),
                rename = getString(R.string.bookshelf_rename),
                delete = getString(R.string.bookshelf_delete),
                confirm = getString(android.R.string.ok),
                cancel = getString(android.R.string.cancel),
            )
            BookshelfScreen(
                labels = labels,
                entries = entries,
                dialog = dialog,
                actions = bookshelfActions(),
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // 阅读器里打开过的书会 recordRecent 进书架，返回时刷新列表。
        // 列表用 uri 做 key，刷新后滚动位置仍然保持。
        refreshList()
    }

    // ------------------------------------------------------------------
    // 屏幕回调（匿名实现，状态改动都收在这里）
    // ------------------------------------------------------------------

    /** 内部类型不能出现在 public 成员签名里，所以用匿名对象而不是让 Activity 实现接口。 */
    private fun bookshelfActions() = object : BookshelfActions {
        override fun onImport() {
            importBook()
        }

        override fun onOpen(entry: BookshelfEntry) {
            openInReader(Uri.parse(entry.uri))
        }

        override fun onLongPress(entry: BookshelfEntry) {
            dialog = BookshelfDialog.Menu(entry, entry.name)
        }

        override fun onClear() {
            requestClear()
        }

        override fun onDismissDialog() {
            dialog = null
        }

        override fun onMenuOpen(entry: BookshelfEntry) {
            dialog = null
            openInReader(Uri.parse(entry.uri))
        }

        override fun onMenuRename(entry: BookshelfEntry) {
            dialog = BookshelfDialog.Rename(entry.uri, entry.name)
        }

        override fun onMenuDelete(entry: BookshelfEntry) {
            dialog = BookshelfDialog.DeleteConfirm(
                uri = entry.uri,
                title = entry.name,
                message = getString(R.string.bookshelf_delete_confirm, entry.name),
            )
        }

        override fun onConfirmDelete() {
            val pending = dialog as? BookshelfDialog.DeleteConfirm
            dialog = null
            if (pending != null) removeBook(pending.uri)
        }

        override fun onConfirmRename(newName: String) {
            val pending = dialog as? BookshelfDialog.Rename
            dialog = null
            val name = newName.trim()
            if (pending != null && name.isNotEmpty()) {
                renameBook(pending.uri, name)
            }
        }

        override fun onConfirmClear() {
            dialog = null
            clearBooks()
        }
    }

    // ------------------------------------------------------------------
    // 增：导入
    // ------------------------------------------------------------------

    private fun importBook() {
        importBookLauncher.launch(arrayOf("*/*"))
    }

    private fun onDocumentSelected(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: SecurityException) {
            // 部分 provider 不支持持久化，本次仍可打开；但要留痕，
            // 否则「重启后书打不开」会变成找不到原因的神秘问题。
            LogUtils.logWithTag(Log.WARN, TAG, "无法持久化 URI 权限：%s", e.toString())
        }
        addBook(uri)
        refreshList()
        openInReader(uri)
    }

    // ------------------------------------------------------------------
    // 查：列表
    // ------------------------------------------------------------------

    private fun refreshList() {
        mBooks = loadBooks()
        entries = mBooks.map { book ->
            BookshelfEntry(uri = book.uri, name = book.name, description = describe(book))
        }
    }

    private fun requestClear() {
        if (mBooks.isEmpty()) {
            Toast.makeText(this, R.string.bookshelf_empty, Toast.LENGTH_SHORT).show()
            return
        }
        // 清空一次最多丢 200 本书的记录，必须二次确认。
        dialog = BookshelfDialog.ClearConfirm(
            title = getString(R.string.bookshelf_clear),
            message = getString(R.string.bookshelf_clear_confirm, mBooks.size),
        )
    }

    // ------------------------------------------------------------------
    // 存储（SharedPreferences：uri\u0001name 集合）
    // ------------------------------------------------------------------

    private fun loadBooks(): List<Book> = decode(getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        .getStringSet(KEY_BOOKS, null))

    private fun saveBooks(books: List<Book>) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putStringSet(KEY_BOOKS, encode(books)).apply()
    }

    /** 导入后加入书架：去重置顶、上限 [BOOK_LIMIT]。 */
    private fun addBook(uri: Uri) {
        val name = uri.lastPathSegment?.substringAfterLast("/") ?: "file"
        addBook(uri.toString(), name)
    }

    private fun addBook(uri: String, name: String) {
        val books = loadBooks().toMutableList()
        books.removeAll { it.uri == uri }
        books.add(0, Book(uri, name, extOf(uri), System.currentTimeMillis()))
        while (books.size > BOOK_LIMIT) books.removeAt(books.size - 1)
        saveBooks(books)
    }

    private fun removeBook(uri: String) {
        val books = loadBooks().filterNot { it.uri == uri }
        saveBooks(books)
        refreshList()
        Toast.makeText(this, R.string.bookshelf_deleted, Toast.LENGTH_SHORT).show()
    }

    private fun renameBook(uri: String, newName: String) {
        val books = loadBooks().map { if (it.uri == uri) Book(it.uri, newName, it.ext, it.openedAt) else it }
        saveBooks(books)
        refreshList()
    }

    private fun clearBooks() {
        saveBooks(emptyList())
        refreshList()
        Toast.makeText(this, R.string.bookshelf_cleared, Toast.LENGTH_SHORT).show()
    }

    /** 带 URI 跳转阅读器。 */
    private fun openInReader(uri: Uri) {
        val intent = Intent(this, BrailleReaderActivity::class.java).apply {
            putExtra(BrailleReaderActivity.EXTRA_URI, uri.toString())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(intent)
    }

    private fun encode(books: List<Book>): Set<String> =
        books.map { "${it.uri}$SEP${it.name}$SEP${it.ext}$SEP${it.openedAt}" }.toSet()

    /** 兼容旧存储：2 段格式（uri+name）自动回退为「按 uri 推断格式、无时间」。 */
    private fun decode(set: Set<String>?): List<Book> {
        if (set == null) return emptyList()
        return set.mapNotNull { s ->
            val p = s.split(SEP)
            when {
                p.size >= 4 -> Book(p[0], p[1], p[2], p[3].toLongOrNull() ?: 0L)
                p.size == 2 -> Book(p[0], p[1], extOf(p[0]), 0L)
                else -> null
            }
        }
    }

    companion object {
        private const val TAG = "BookshelfActivity"
        private const val SEP = "\u0001"

        private const val PREFS_NAME = "bookshelf"
        private const val KEY_BOOKS = "books"
        private const val BOOK_LIMIT = 200

        /** 从 uri 推断文件扩展名（小写，无扩展名返回空串）。 */
        private fun extOf(uri: String): String =
            Uri.parse(uri).lastPathSegment
                ?.substringAfterLast('.', "")?.lowercase() ?: ""

        /**
         * 记录一本打开过的书进书架（阅读器解码/翻译成功后调用）。
         * 去重置顶、上限 [BOOK_LIMIT]；同时刷新格式与打开时间供列表朗读。
         */
        fun recordRecent(context: Activity, uri: String, name: String) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Activity.MODE_PRIVATE)
            val set = prefs.getStringSet(KEY_BOOKS, null) ?: emptySet()
            val list = set.mapNotNull { s ->
                val p = s.split(SEP)
                when {
                    p.size >= 4 -> Triple(p[0], p[1], p[2] to (p[3].toLongOrNull() ?: 0L))
                    p.size == 2 -> Triple(p[0], p[1], "" to 0L)
                    else -> null
                }
            }.map { Book(it.first, it.second, it.third.first, it.third.second) }
                .toMutableList()
            list.removeAll { it.uri == uri }
            list.add(0, Book(uri, name, extOf(uri), System.currentTimeMillis()))
            while (list.size > BOOK_LIMIT) list.removeAt(list.size - 1)
            prefs.edit()
                .putStringSet(KEY_BOOKS,
                    list.map { "${it.uri}$SEP${it.name}$SEP${it.ext}$SEP${it.openedAt}" }
                        .toSet())
                .apply()
        }
    }
}
