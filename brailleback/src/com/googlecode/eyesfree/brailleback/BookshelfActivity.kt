/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 盲文书架：只做「导入 + 书籍管理（增删改查）」，不做文件夹树浏览/收藏。
 *
 * - 增：导入文件（系统选择器），或阅读器里打开过的书自动进书架
 * - 查：书架列表展示全部藏书，点击直接阅读
 * - 删：长按条目删除单本；菜单「清空书架」清空全部
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

    /** 一本书：uri 唯一标识，name 为显示名（可重命名）。 */
    private class Book(val uri: String, var name: String)

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
        list?.adapter = ArrayAdapter(
            this, android.R.layout.simple_list_item_1, mBooks.map { it.name })
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

    private fun loadBooks(): List<Book> =
        decode(getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getStringSet(KEY_BOOKS, null)).map { Book(it.first, it.second) }

    private fun saveBooks(books: List<Book>) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putStringSet(KEY_BOOKS, encode(books.map { it.uri to it.name })).apply()
    }

    /** 导入后加入书架：去重置顶、上限 [BOOK_LIMIT]。 */
    private fun addBook(uri: Uri) {
        val name = uri.lastPathSegment?.substringAfterLast("/") ?: "file"
        addBook(uri.toString(), name)
    }

    private fun addBook(uri: String, name: String) {
        val books = loadBooks().toMutableList()
        books.removeAll { it.uri == uri }
        books.add(0, Book(uri, name))
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
        val books = loadBooks().map { if (it.uri == uri) Book(it.uri, newName) else it }
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

    private fun encode(pairs: List<Pair<String, String>>): Set<String> =
        pairs.map { it.first + SEP + it.second }.toSet()

    private fun decode(set: Set<String>?): List<Pair<String, String>> {
        if (set == null) return emptyList()
        return set.mapNotNull { s ->
            val i = s.indexOf(SEP)
            if (i <= 0) null else s.substring(0, i) to s.substring(i + 1)
        }
    }

    companion object {
        private const val REQUEST_OPEN = 3
        private const val MENU_CLEAR = 1
        private const val SEP = "\u0001"

        private const val PREFS_NAME = "bookshelf"
        private const val KEY_BOOKS = "books"
        private const val BOOK_LIMIT = 200

        /**
         * 记录一本打开过的书进书架（阅读器解码/翻译成功后调用）。
         * 去重置顶、上限 [BOOK_LIMIT]。
         */
        fun recordRecent(context: Activity, uri: String, name: String) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Activity.MODE_PRIVATE)
            val set = prefs.getStringSet(KEY_BOOKS, null) ?: emptySet()
            val list = set.mapNotNull { s ->
                val i = s.indexOf(SEP)
                if (i <= 0) null else s.substring(0, i) to s.substring(i + 1)
            }.toMutableList()
            list.removeAll { it.first == uri }
            list.add(0, uri to name)
            while (list.size > BOOK_LIMIT) list.removeAt(list.size - 1)
            prefs.edit()
                .putStringSet(KEY_BOOKS,
                    list.map { it.first + SEP + it.second }.toSet())
                .apply()
        }
    }
}
