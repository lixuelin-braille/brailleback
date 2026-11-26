/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import com.linxi.brailleback.R
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * 开发者选项里「查看 / 分享 / 复制 / 清除日志」的单一真源。
 *
 * 第五批 Compose 迁移把这段逻辑从 SettingsActivity 的 PreferenceFragment 里
 * 抽出来：旧 XML 子屏（回退路径）与 Compose 子屏都必须调用本对象，
 * 不允许各自再实现一遍 Intent / Clipboard / Toast，否则两代 UI 的导出行为
 * 会漂移，而这类漂移只能靠真机导出日志才能发现。
 *
 * 大日志出口（2026-11-26 现场报障）：整份日志塞 `EXTRA_TEXT` 走 Binder，
 * 单次事务上限约 1 MB，超过必然 `TransactionTooLargeException`，表现为
 * 点了没反应或提示失败。超过 [SHARE_TEXT_MAX_BYTES] 的分享改走 cache 临时
 * 文件 + FileProvider `content://` URI；剪贴板放不下时只复制最近若干行。
 */
object DebugLogActions {

    /** 文本分享 / 复制上限：超过走 FileProvider 文件；≤ 100 KB 才直传 EXTRA_TEXT。 */
    internal const val MAX_TEXT_BYTES = 100 * 1024

    /** 剪贴板放不下时保留的最近行数（复制截断口径）。 */
    internal const val COPY_MAX_LINES = 200

    /** 临时分享文件放在 cacheDir 下的子目录，FileProvider 只暴露这一条路径。 */
    private const val CACHE_SUBDIR = "logs"

    /** 分享出口：文本直传 vs 文件 URI。 */
    internal enum class ShareRoute {
        /** `EXTRA_TEXT` 直传，体积小、接收方直接读到文本。 */
        EXTRA_TEXT,

        /** cache 临时文件 + FileProvider `content://` URI。 */
        FILE_PROVIDER,
    }

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

    /** 按 UTF-8 字节数判定分享走哪条出口。 */
    internal fun shareRoute(text: String): ShareRoute =
        if (utf8Bytes(text) > MAX_TEXT_BYTES) {
            ShareRoute.FILE_PROVIDER
        } else {
            ShareRoute.EXTRA_TEXT
        }

    /** 只保留最后 [maxLines] 行；原本不超时原样返回。 */
    internal fun tailLines(text: String, maxLines: Int): String {
        val lines = text.lines()
        if (lines.size <= maxLines) return text
        return lines.takeLast(maxLines).joinToString("\n")
    }

    /** 把指定文本放进剪贴板；服务缺失或系统抛异常都要给提示。 */
    @JvmStatic
    fun copyLogToClipboard(context: Context, text: String) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (manager == null) {
            Toast.makeText(context, R.string.debug_log_copy_fail, Toast.LENGTH_SHORT).show()
            return
        }
        val truncated = utf8Bytes(text) > MAX_TEXT_BYTES
        val clipText = if (truncated) tailLines(text, COPY_MAX_LINES) else text
        try {
            manager.setPrimaryClip(
                ClipData.newPlainText(context.getString(R.string.debug_log_view_title), clipText)
            )
            val toast = if (truncated) {
                context.getString(R.string.debug_log_copy_truncated, COPY_MAX_LINES)
            } else {
                context.getString(R.string.debug_log_copied)
            }
            Toast.makeText(context, toast, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(context, R.string.debug_log_copy_fail, Toast.LENGTH_SHORT).show()
        }
    }

    /** 用 ACTION_SEND 选择器分享日志；没有可用应用时给提示而不是崩溃。 */
    @JvmStatic
    fun shareLog(context: Context, text: String) {
        when (shareRoute(text)) {
            ShareRoute.EXTRA_TEXT -> shareAsText(context, text)
            ShareRoute.FILE_PROVIDER -> shareAsFile(context, text)
        }
    }

    private fun shareAsText(context: Context, text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startShareChooser(context, intent)
    }

    private fun shareAsFile(context: Context, text: String) {
        val dir = File(context.cacheDir, CACHE_SUBDIR)
        if (!dir.isDirectory && !dir.mkdirs()) {
            Toast.makeText(context, R.string.debug_log_share_fail, Toast.LENGTH_SHORT).show()
            return
        }
        val file = File(dir, "brailleback-log-${System.currentTimeMillis()}.txt")
        file.writeText(text, StandardCharsets.UTF_8)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startShareChooser(context, intent)
    }

    private fun startShareChooser(context: Context, intent: Intent) {
        try {
            context.startActivity(
                Intent.createChooser(intent, context.getString(R.string.debug_log_share))
            )
        } catch (e: Exception) {
            Toast.makeText(context, R.string.debug_log_share_fail, Toast.LENGTH_SHORT).show()
        }
    }

    private fun utf8Bytes(text: String): Int =
        text.toByteArray(StandardCharsets.UTF_8).size

    /** 清除全部日志文件并给结果提示。二次确认由各自的 UI 层负责。 */
    @JvmStatic
    fun clear(context: Context) {
        LogFileWriter.clear(context)
        Toast.makeText(context, R.string.debug_log_cleared, Toast.LENGTH_SHORT).show()
    }
}
