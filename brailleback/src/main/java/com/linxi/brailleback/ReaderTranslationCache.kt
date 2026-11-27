/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 翻译结果落盘：同一本书第二次打开直接读上次的点位，不再重翻。
 *
 * 一本书一对文件，按「书 + 盲文表」分目录：
 * - `<key>.brl`  Unicode 盲文阅读流，UTF-8，只追加
 * - `<key>.pos`  逐格源字符下标，小端 int32，与 .brl 一一对应
 * - `<key>.ok`   整本翻完后写下的完成标记；没有它就不算命中
 *
 * 完成标记单独一个文件，而不是在 .brl 末尾塞个尾巴：翻译是「边翻边存」，
 * 中途被杀进程、点停止、翻译失败都会留下半本书，那些半截结果不能当缓存用。
 *
 * 写入方只有翻译线程一个（见 translateParagraphs 的 onPersist），顺序天然是对的；
 * 读取方是主线程，只在打开书时读一次。
 */

package com.linxi.brailleback

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

internal object ReaderTranslationCache {

    private const val TAG = "BrailleReaderCache"
    private const val DIR = "reader_cache"

    /** 读回来的一份完整翻译。 */
    internal class Stored(val text: String, val cells: IntArray)

    internal fun isComplete(context: Context, uri: Uri, table: String): Boolean =
        okFile(context, uri, table).isFile

    /**
     * 读完整缓存。没有完成标记、文件缺失或读失败都返回 null，调用方照常翻译。
     *
     * 读失败不抛：缓存是可丢弃的加速层，坏了就当没有。
     */
    internal fun read(context: Context, uri: Uri, table: String): Stored? {
        if (!isComplete(context, uri, table)) return null
        return try {
            val text = brlFile(context, uri, table).readText(Charsets.UTF_8)
            val bytes = posFile(context, uri, table).readBytes()
            if (text.isEmpty()) return null
            val cells = IntArray(bytes.size / 4)
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            for (i in cells.indices) cells[i] = buf.getInt()
            Log.d(TAG, "hit: chars=${text.length} cells=${cells.size}")
            Stored(text, cells)
        } catch (e: Exception) {
            Log.w(TAG, "read failed, treating as miss: ${e.message}")
            null
        }
    }

    /** 开新一轮翻译：清掉旧结果和完成标记，新建空文件。 */
    internal fun begin(context: Context, uri: Uri, table: String) {
        try {
            val dir = dir(context)
            if (!dir.isDirectory && !dir.mkdirs()) return
            okFile(context, uri, table).delete()
            brlFile(context, uri, table).delete()
            posFile(context, uri, table).delete()
            brlFile(context, uri, table).createNewFile()
            posFile(context, uri, table).createNewFile()
        } catch (e: Exception) {
            Log.w(TAG, "begin failed: ${e.message}")
        }
    }

    /** 追加一块成果。翻译线程独占调用，append 顺序与上屏顺序一致。 */
    internal fun append(
        context: Context,
        uri: Uri,
        table: String,
        deltaText: String,
        deltaCells: IntArray,
    ) {
        if (deltaText.isEmpty() && deltaCells.isEmpty()) return
        try {
            if (deltaText.isNotEmpty()) {
                FileOutputStream(brlFile(context, uri, table), true)
                    .use { it.write(deltaText.toByteArray(Charsets.UTF_8)) }
            }
            if (deltaCells.isNotEmpty()) {
                val buf = ByteBuffer.allocate(deltaCells.size * 4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                for (cell in deltaCells) buf.putInt(cell)
                FileOutputStream(posFile(context, uri, table), true)
                    .use { it.write(buf.array()) }
            }
        } catch (e: Exception) {
            // 存不住就存不住：这一轮照常读，下次再翻一遍。
            Log.w(TAG, "append failed: ${e.message}")
        }
    }

    /** 整本翻完：写下完成标记，下次打开才认这份缓存。 */
    internal fun complete(context: Context, uri: Uri, table: String) {
        try {
            okFile(context, uri, table).writeText("1")
            Log.d(TAG, "done: ${okFile(context, uri, table).name}")
        } catch (e: Exception) {
            Log.w(TAG, "complete failed: ${e.message}")
        }
    }

    /** 清除本书这一轮的缓存（重新翻译、清除缓存都走这里）。 */
    internal fun clear(context: Context, uri: Uri, table: String) {
        okFile(context, uri, table).delete()
        brlFile(context, uri, table).delete()
        posFile(context, uri, table).delete()
        Log.d(TAG, "cleared")
    }

    private fun dir(context: Context): File = File(context.filesDir, DIR)

    private fun brlFile(context: Context, uri: Uri, table: String) =
        File(dir(context), key(uri, table) + ".brl")

    private fun posFile(context: Context, uri: Uri, table: String) =
        File(dir(context), key(uri, table) + ".pos")

    private fun okFile(context: Context, uri: Uri, table: String) =
        File(dir(context), key(uri, table) + ".ok")

    /**
     * 缓存键：书 uri + 盲文表。
     *
     * 表名必须进来：换表（通用盲文 / 双拼 / 英文）之后点位完全不同，
     * 拿旧表的结果当新表的读，出来的是错的字，而且看不出来错在哪。
     */
    private fun key(uri: Uri, table: String): String {
        val raw = uri.toString() + "|" + table
        val digest = MessageDigest.getInstance("SHA-1").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }
}
