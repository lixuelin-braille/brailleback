/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 盲文书架：用系统文件夹授权（SAF ACTION_OPEN_DOCUMENT_TREE）浏览盲文电子书。
 *
 * 背景：用户有 12848 本 bdo/bdoc 盲文电子书，靠系统文件选择器逐本找很痛苦。
 * 书架一次性授权整个文件夹（授权可持久化，重启仍在），按目录树浏览
 * bdo/bdoc/brf/brl/txt 文件，支持收藏多个文件夹与最近打开列表，点书即读。
 *
 * 不依赖任何章节/标题识别启发式，纯按文件系统结构浏览，100% 可靠。
 */

package com.googlecode.eyesfree.brailleback

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

class BookshelfActivity : Activity() {

    private var mPathText: TextView? = null
    private var mList: ListView? = null
    private var mEntries: List<Entry> = emptyList()

    /** 当前授权的文件夹树。 */
    private var mTreeUri: Uri? = null
    /** 目录导航栈：栈底为树根 docId，栈顶为当前所在目录 docId。 */
    private val mDocStack = ArrayList<String>()

    private class Entry(val docId: String, val name: String, val isDir: Boolean)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.bookshelf)
        mPathText = findViewById(R.id.bookshelf_path)
        mList = findViewById(R.id.bookshelf_list)

        findViewById<Button>(R.id.btn_bookshelf_pick).setOnClickListener { pickFolder() }
        findViewById<Button>(R.id.btn_bookshelf_folders).setOnClickListener { showFolders() }
        findViewById<Button>(R.id.btn_bookshelf_recent).setOnClickListener { showRecent() }

        mList?.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            when (position) {
                0 -> if (mDocStack.size > 1) {
                    // 返回上级
                    mDocStack.removeAt(mDocStack.size - 1)
                    refresh()
                }
                else -> {
                    val e = mEntries[position - 1]
                    if (e.isDir) {
                        mDocStack.add(e.docId)
                        refresh()
                    } else {
                        openFile(e)
                    }
                }
            }
        }

        // 恢复上次浏览的文件夹（若授权仍在）
        val folders = loadFolders()
        if (folders.isNotEmpty()) {
            enterTree(Uri.parse(folders[0].first))
        } else {
            mPathText?.setText(R.string.bookshelf_empty)
        }
    }

    // ------------------------------------------------------------------
    // 文件夹授权与浏览
    // ------------------------------------------------------------------

    private fun pickFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        startActivityForResult(intent, REQUEST_TREE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_TREE && resultCode == Activity.RESULT_OK) {
            val treeUri = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                // 部分 provider 不支持持久化，本次仍可浏览
            }
            addFolder(treeUri)
            enterTree(treeUri)
        }
    }

    /** 进入一个文件夹树，重置导航栈到树根。 */
    private fun enterTree(treeUri: Uri) {
        mTreeUri = treeUri
        mDocStack.clear()
        mDocStack.add(DocumentsContract.getTreeDocumentId(treeUri))
        refresh()
    }

    private fun refresh() {
        val treeUri = mTreeUri
        if (treeUri == null) {
            mPathText?.setText(R.string.bookshelf_empty)
            return
        }
        val parentDocId = mDocStack.lastOrNull() ?: return
        mPathText?.text = treeUri.lastPathSegment
        mEntries = try {
            listChildren(treeUri, parentDocId)
        } catch (e: Exception) {
            Toast.makeText(this,
                getString(R.string.bookshelf_list_fail, e.message), Toast.LENGTH_LONG).show()
            emptyList()
        }
        val labels = ArrayList<String>()
        if (mDocStack.size > 1) labels.add(getString(R.string.bookshelf_up))
        for (e in mEntries) {
            labels.add(if (e.isDir) "${e.name}/" else e.name)
        }
        mList?.adapter = ArrayAdapter(this,
            android.R.layout.simple_list_item_1, labels)
    }

    /** 用 DocumentsContract 枚举子目录与盲文文件（排除其他类型）。 */
    private fun listChildren(treeUri: Uri, parentDocId: String): List<Entry> {
        val out = ArrayList<Entry>()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri, parentDocId)
        contentResolver.query(childrenUri, null, null, null, null)?.use { c ->
            val idCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (c.moveToNext()) {
                val id = c.getString(idCol) ?: continue
                val name = c.getString(nameCol) ?: continue
                val mime = c.getString(mimeCol)
                val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                if (isDir || isBookFile(name)) {
                    out.add(Entry(id, name, isDir))
                }
            }
        }
        // 目录在前、各自按名称排序
        return out.sortedWith(
            compareBy<Entry>({ !it.isDir }, { it.name.lowercase() })
        )
    }

    /** 打开一个盲文文件：跳转阅读器并让其直接加载。 */
    private fun openFile(entry: Entry) {
        val treeUri = mTreeUri ?: return
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, entry.docId)
        val intent = Intent(this, BrailleReaderActivity::class.java).apply {
            putExtra(BrailleReaderActivity.EXTRA_URI, docUri.toString())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(intent)
    }

    private fun isBookFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in BOOK_EXTENSIONS
    }

    // ------------------------------------------------------------------
    // 收藏文件夹 / 最近打开
    // ------------------------------------------------------------------

    private fun addFolder(treeUri: Uri) {
        val folders = loadFolders().toMutableList()
        val uri = treeUri.toString()
        folders.removeAll { it.first == uri }
        val name = treeUri.lastPathSegment?.substringAfterLast(":") ?: uri
        folders.add(0, uri to name)
        saveFolders(folders)
    }

    /** 收藏夹列表：切换浏览的文件夹。 */
    private fun showFolders() {
        val folders = loadFolders()
        if (folders.isEmpty()) {
            Toast.makeText(this, R.string.bookshelf_no_folders, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.bookshelf_folders_title)
            .setItems(folders.map { it.second }.toTypedArray()) { _, which ->
                enterTree(Uri.parse(folders[which].first))
            }
            .setPositiveButton(R.string.bookshelf_clear_folders) { _, _ ->
                saveFolders(emptyList())
                Toast.makeText(this, R.string.bookshelf_folders_cleared,
                    Toast.LENGTH_SHORT).show()
                if (mTreeUri == null) mPathText?.setText(R.string.bookshelf_empty)
            }
            .show()
    }

    /** 最近打开：直接打开文件。 */
    private fun showRecent() {
        val recent = loadRecent()
        if (recent.isEmpty()) {
            Toast.makeText(this, R.string.bookshelf_no_recent, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.bookshelf_recent_title)
            .setItems(recent.map { it.second }.toTypedArray()) { _, which ->
                val uri = Uri.parse(recent[which].first)
                val intent = Intent(this, BrailleReaderActivity::class.java).apply {
                    putExtra(BrailleReaderActivity.EXTRA_URI, uri.toString())
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(intent)
            }
            .show()
    }

    private fun loadFolders(): List<Pair<String, String>> =
        decode(getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getStringSet(KEY_FOLDERS, null))

    private fun saveFolders(folders: List<Pair<String, String>>) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit()
            .putStringSet(KEY_FOLDERS, encode(folders)).apply()
    }

    private fun loadRecent(): List<Pair<String, String>> =
        decode(getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getStringSet(KEY_RECENT, null))

    /** 编码/解码 uri\u0001name 对。 */
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
        private const val REQUEST_TREE = 2
        private const val SEP = "\u0001"

        private const val PREFS_NAME = "bookshelf"
        private const val KEY_FOLDERS = "folders"
        private const val KEY_RECENT = "recent"
        private const val RECENT_LIMIT = 20

        private val BOOK_EXTENSIONS = setOf("bdo", "bdoc", "brf", "brl", "txt")

        /** 记录一条最近打开（去重置顶、上限 [RECENT_LIMIT]）。阅读器打开成功后调用。 */
        fun recordRecent(context: Activity, uri: String, name: String) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Activity.MODE_PRIVATE)
            val set = prefs.getStringSet(KEY_RECENT, null) ?: emptySet()
            val list = set.mapNotNull { s ->
                val i = s.indexOf(SEP)
                if (i <= 0) null else s.substring(0, i) to s.substring(i + 1)
            }.toMutableList()
            list.removeAll { it.first == uri }
            list.add(0, uri to name)
            while (list.size > RECENT_LIMIT) list.removeAt(list.size - 1)
            prefs.edit()
                .putStringSet(KEY_RECENT,
                    list.map { it.first + SEP + it.second }.toSet())
                .apply()
        }
    }
}
