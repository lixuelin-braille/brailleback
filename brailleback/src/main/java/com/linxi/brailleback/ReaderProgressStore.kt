/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 阅读器进度记忆：按文件 URI 存/取字符偏移，重读失败回落到提示态。
 * 同包扩展函数，状态字段仍留在 BrailleReaderActivity；不引入 DI 框架。
 */

package com.linxi.brailleback

import android.content.Context
import android.net.Uri
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 进度写盘去抖窗（毫秒）：滚屏连续换页时不每翻一页就落一次 SharedPreferences。 */
private const val SAVE_PROGRESS_DEBOUNCE_MS = 900L

internal fun BrailleReaderActivity.saveProgress() {
        if (mStream.isEmpty()) return
        progressSaveJob?.cancel()
        // 延时不走裸 Handler：Activity 销毁时 lifecycleScope 自动取消。
        progressSaveJob = lifecycleScope.launch {
            delay(SAVE_PROGRESS_DEBOUNCE_MS)
            writeProgressNow()
        }
    }

/** 离开页面等时机立即落盘：取消待触发的去抖任务，直接写一次。 */
internal fun BrailleReaderActivity.flushProgress() {
        progressSaveJob?.cancel()
        progressSaveJob = null
        writeProgressNow()
    }

/**
 * 单本书位置在偏好里的键。
 *
 * 位置必须按书分开存：只留「最近一本」一个槽时，读 B 书会把 A 书的进度
 * 顶掉，回头再开 A 就是从第 1 页 —— 对一本长书来说等于白读。
 */
internal fun BrailleReaderActivity.perBookOffsetKey(uri: String): String = "off_$uri"

private fun BrailleReaderActivity.writeProgressNow() {
        if (mStream.isEmpty()) return
        val uriStr = mFileUri?.toString()
        val editor = getSharedPreferences(BrailleReaderActivity.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            // 最近一本仍记一份：阅读器单独打开（不带 uri）时靠它续读。
            .putString(BrailleReaderActivity.KEY_URI, uriStr)
            .putInt(BrailleReaderActivity.KEY_OFFSET, mOffset)
        if (uriStr != null) {
            editor.putInt(perBookOffsetKey(uriStr), mOffset)
        }
        editor.apply()
    }

internal fun BrailleReaderActivity.restoreProgress() {
        val prefs = getSharedPreferences(BrailleReaderActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val uriStr = prefs.getString(BrailleReaderActivity.KEY_URI, null) ?: return
        val uri = try {
            Uri.parse(uriStr)
        } catch (e: Exception) {
            return
        }
        try {
            val savedOffset = prefs.getInt(BrailleReaderActivity.KEY_OFFSET, 0)
            loadBytes(uri, resetPosition = true, targetOffset = savedOffset)
            mFileUri = uri
            showPage()
        } catch (e: Exception) {
            // 文件已被删除或权限失效：清空进度，回落到提示态
            mStream = ""
            mOffset = 0
            mPageOffsets = intArrayOf()
            mPageUnit = -1
            mFileUri = null
            prefs.edit().remove(BrailleReaderActivity.KEY_URI).remove(BrailleReaderActivity.KEY_OFFSET).apply()
        }
    }
