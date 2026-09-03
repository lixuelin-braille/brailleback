/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 盲文书架：只做「导入 + 书籍管理（增删改查）」，不做文件夹树浏览/收藏。
 *
 * - 增：导入文件（系统选择器），或阅读器里打开过的书自动进书架
 * - 查：书架列表展示全部藏书，点击直接阅读；TalkBack 朗读书名 + 格式 + 上次打开时间
 * - 删：长按条目删除单本（带确认）；菜单「清空书架」清空全部（带确认）
 * - 改：长按条目重命名显示名
 */

package com.googlecode.eyesfree.brailleback

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.text.format.DateUtils
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

class BookshelfActivity : Activity() {

    private var mEmptyText: TextView? = null
    private var mList: ListView? = null
    private var mBooks: List<Book> = emptyList()

    /**
     * 一本书：uri 唯一标识，name 为显示名（可重命名）。
     * ext/openedAt 用于列表朗读（书名 + 格式 + 上次打开时间）；
     * 旧版存储无这两个字段，[decode] 自动回退为「按 uri 推断 + 未知时间」。
     */
    private class Book(
        val uri: String,
        var name: String,
        val ext: String,
        val openedAt: Long) {
        /** ArrayAdapter 默认用 toString 渲染可见文本，必须返回书名。 */
        override fun toString(): String = name
    }

    /** ListView 条目：视觉显示书名，TalkBack 朗读完整上下文。 */
    private inner class BookAdapter(books: List<Book>)
        : ArrayAdapter<Book>(this@BookshelfActivity,
            android.R.layout.simple_list_item_1, books) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = super.getView(position, convertView, parent) as TextView
            val book = getItem(position) ?: return view
            view.contentDescription = describe(book)
            return view
        }
    }

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
        "" -> getString(R.string.bookshelf_type_unknown)
        else -> ext.uppercase()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.bookshelf)
        mEmptyText = findViewById(R.id.bookshelf_empty)
        mList = findViewById(R.id.bookshelf_list)

        findViewById<Button>(R.id.btn_bookshelf_open).setOnClickListener { importBook() }

        mList?.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            openInReader(Uri.parse(mBooks[position].uri))
        }
        mList?.onItemLongClickListener = AdapterView.OnItemLongClickListener { _, _, position, _ ->
            showItemMenu(position)
            true
        }
        refreshList()
    }

    override fun onResume() {
        super.onResume()
        // 阅读器里打开过的书会 recordRecent 进书架，返回时刷新列表。
        refreshList()
    }

    // ------------------------------------------------------------------
    // 增：导入
    // ------------------------------------------------------------------

    private fun importBook() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQUEST_OPEN)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return
        if (requestCode == REQUEST_OPEN) {
            val uri = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                // 部分 provider 不支持持久化，本次仍可打开
            }
            addBook(uri)
            refreshList()
            openInReader(uri)
        }
    }

    // ------------------------------------------------------------------
    // 查：列表
    // ------------------------------------------------------------------

    private fun refreshList() {
        // 保留滚动位置：从阅读器返回时无条件重建 adapter 会让盲人用户
        // 丢失在长列表里的位置，重新定位成本很高。
        val list = mList
        val savedPosition = list?.firstVisiblePosition ?: 0
        mBooks = loadBooks()
        mEmptyText?.visibility = if (mBooks.isEmpty()) TextView.VISIBLE else TextView.GONE
        list?.adapter = BookAdapter(mBooks)
        if (list != null && mBooks.isNotEmpty()) {
            list.setSelection(savedPosition.coerceAtMost(mBooks.size - 1))
        }
    }

    // ------------------------------------------------------------------
    // 删 / 改：长按菜单
    // ------------------------------------------------------------------

    private fun showItemMenu(position: Int) {
        val book = mBooks[position]
        val items = arrayOf(
            getString(R.string.bookshelf_open),
            getString(R.string.bookshelf_rename),
            getString(R.string.bookshelf_delete))
        AlertDialog.Builder(this)
            .setTitle(book.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> openInReader(Uri.parse(book.uri))
                    1 -> showRenameDialog(book)
                    2 -> showDeleteConfirm(book)
                }
            }
            .show()
    }

    /** 删除前确认：盲人用户误触长按菜单的代价高，直接删不可接受。 */
    private fun showDeleteConfirm(book: Book) {
        AlertDialog.Builder(this)
            .setTitle(book.name)
            .setMessage(getString(R.string.bookshelf_delete_confirm, book.name))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                removeBook(book.uri)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showRenameDialog(book: Book) {
        val input = EditText(this).apply {
            setText(book.name)
            setSelection(book.name.length)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.bookshelf_rename)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    renameBook(book.uri, newName)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, MENU_CLEAR, 0, R.string.bookshelf_clear)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == MENU_CLEAR) {
            if (mBooks.isEmpty()) {
                Toast.makeText(this, R.string.bookshelf_empty, Toast.LENGTH_SHORT).show()
            } else {
                // 清空一次最多丢 200 本书的记录，必须二次确认。
                AlertDialog.Builder(this)
                    .setTitle(R.string.bookshelf_clear)
                    .setMessage(getString(R.string.bookshelf_clear_confirm, mBooks.size))
                    .setPositiveButton(android.R.string.ok) { _, _ -> clearBooks() }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            return true
        }
        return super.onOptionsItemSelected(item)
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
        private const val REQUEST_OPEN = 3
        private const val MENU_CLEAR = 1
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
