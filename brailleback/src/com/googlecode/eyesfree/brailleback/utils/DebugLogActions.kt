/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.googlecode.eyesfree.brailleback.R

/**
 * 开发者选项里「查看 / 分享 / 复制 / 清除日志」的单一真源。
 *
 * 第五批 Compose 迁移把这段逻辑从 SettingsActivity 的 PreferenceFragment 里
 * 抽出来：旧 XML 子屏（回退路径）与 Compose 子屏都必须调用本对象，
 * 不允许各自再实现一遍 Intent / Clipboard / Toast，否则两代 UI 的导出行为
 * 会漂移，而这类漂移只能靠真机导出日志才能发现。
 */
object DebugLogActions {

    /** 读取全部日志（历史在前，当前文件最后）。 */
    @JvmStatic
    fun readAll(context: Context): String = LogFileWriter.readAll(context)

    /** 空日志不能静默无反应，读屏用户需要一句明确提示。 */
    @JvmStatic
    fun showEmptyToast(context: Context) {
        Toast.makeText(context, R.string.debug_log_empty, Toast.LENGTH_SHORT).show()
    }

    /** 从开发者选项直接分享当前日志。 */
    @JvmStatic
    fun shareCurrentLog(context: Context) {
        val log = readAll(context)
        if (log.isBlank()) {
            showEmptyToast(context)
            return
        }
        shareLog(context, log)
    }

    /** 从开发者选项直接复制当前日志。 */
    @JvmStatic
    fun copyCurrentLog(context: Context) {
        val log = readAll(context)
        if (log.isBlank()) {
            showEmptyToast(context)
            return
        }
        copyLogToClipboard(context, log)
    }

    /** 把指定文本放进剪贴板；服务缺失或系统抛异常都要给提示。 */
    @JvmStatic
    fun copyLogToClipboard(context: Context, text: String) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (manager == null) {
            Toast.makeText(context, R.string.debug_log_copy_fail, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            manager.setPrimaryClip(
                ClipData.newPlainText(context.getString(R.string.debug_log_view_title), text)
            )
            Toast.makeText(context, R.string.debug_log_copied, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(context, R.string.debug_log_copy_fail, Toast.LENGTH_SHORT).show()
        }
    }

    /** 用 ACTION_SEND 选择器分享日志；没有可用应用时给提示而不是崩溃。 */
    @JvmStatic
    fun shareLog(context: Context, text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        try {
            context.startActivity(
                Intent.createChooser(intent, context.getString(R.string.debug_log_share))
            )
        } catch (e: Exception) {
            Toast.makeText(context, R.string.debug_log_share_fail, Toast.LENGTH_SHORT).show()
        }
    }

    /** 清除全部日志文件并给结果提示。二次确认由各自的 UI 层负责。 */
    @JvmStatic
    fun clear(context: Context) {
        LogFileWriter.clear(context)
        Toast.makeText(context, R.string.debug_log_cleared, Toast.LENGTH_SHORT).show()
    }
}
