/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 阅读器进度记忆：按文件 URI 存/取字符偏移，重读失败回落到提示态。
 * 同包扩展函数，状态字段仍留在 BrailleReaderActivity；不引入 DI 框架。
 */

package com.linxi.brailleback

import android.content.Context
import android.net.Uri

internal fun BrailleReaderActivity.saveProgress() {
        if (mStream.isEmpty()) return
        getSharedPreferences(BrailleReaderActivity.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(BrailleReaderActivity.KEY_URI, mFileUri?.toString())
            .putInt(BrailleReaderActivity.KEY_OFFSET, mOffset)
            .apply()
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
            mFileUri = null
            prefs.edit().remove(BrailleReaderActivity.KEY_URI).remove(BrailleReaderActivity.KEY_OFFSET).apply()
        }
    }
