/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback.utils

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.core.content.FileProvider
import com.googlecode.eyesfree.utils.LogUtils
import com.linxi.brailleback.R
import com.googlecode.eyesfree.utils.LogFileWriter
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
 * 点了没反应或提示失败。超过 [MAX_TEXT_BYTES] 的分享改走 cache 临时
 * 文件 + FileProvider `content://` URI；剪贴板放不下时只复制最近若干行。
 *
 * 失败留痕（2026-11-26 第二轮）：分享失败不再只弹一句短 Toast。异常类名 +
 * message 进文件日志，分类结果落到 [DebugLogExportState] 供日志页朗读；
 * 同时自动降级到剪贴板——对读屏用户这是不依赖第三个 App 的唯一出口。
 */
object DebugLogActions {

    /** 文本分享 / 复制上限：超过走 FileProvider 文件；≤ 100 KB 才直传 EXTRA_TEXT。 */
    internal const val MAX_TEXT_BYTES = 100 * 1024

    /** 导出硬上限 256 KB：分享 / 复制出去的总量一律不超过，超出只取尾部。 */
    internal const val EXPORT_MAX_BYTES = LogFileWriter.EXPORT_MAX_BYTES

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

    /** 分享失败分类：用于分场景播报、诊断字段与单测断言。 */
    internal enum class ExportFailureCategory {
        /** 没有可处理该 Intent 的应用（resolveActivity 为空 / ActivityNotFoundException）。 */
        NO_RECEIVER,

        /** 接收方拒绝 URI 授权（SecurityException）。 */
        URI_PERMISSION,

        /** Intent 参数 / URI 构造无效（IllegalArgumentException）。 */
        BAD_ARGUMENT,

        /** 临时文件创建 / 写入失败。 */
        WRITE_FAILED,

        /** 其余未归类异常。 */
        UNKNOWN,
    }

    /** 一次导出尝试的结果；[reason] 带异常类名与 message，供日志与诊断页读取。 */
    internal data class ExportOutcome(
        val success: Boolean,
        val category: ExportFailureCategory?,
        val reason: String,
    )

    /** 读取全部日志（历史在前，当前文件最后），按 256 KB 尾部截断。 */
    @JvmStatic
    fun readAll(context: Context): String = LogFileWriter.readAll(context)

    /**
     * 导出准备：先取未截断全文判断是否超限，再截到 [EXPORT_MAX_BYTES] 尾部。
     * @return 截尾后的文本与「全文是否超过 256 KB」。
     */
    internal fun prepareExport(context: Context): Pair<String, Boolean> {
        val full = LogFileWriter.readAll(context, Int.MAX_VALUE)
        val truncated = utf8Bytes(full) > EXPORT_MAX_BYTES
        return LogFileWriter.tailByBytes(full, EXPORT_MAX_BYTES) to truncated
    }

    /**
     * 异常 → 分类 + 诊断文案。纯函数，不碰 Android 系统服务，可直接单测覆盖
     * 三类已命名异常与兜底分支。
     */
    internal fun classifyFailure(e: Throwable): ExportOutcome {
        val reason = "${e.javaClass.simpleName}: ${e.message.orEmpty()}"
        val category = when (e) {
            is ActivityNotFoundException -> ExportFailureCategory.NO_RECEIVER
            is SecurityException -> ExportFailureCategory.URI_PERMISSION
            is IllegalArgumentException -> ExportFailureCategory.BAD_ARGUMENT
            else -> ExportFailureCategory.UNKNOWN
        }
        return ExportOutcome(false, category, reason)
    }

    /** 分类对应的短文案资源 id；未知分类兜底为通用失败。 */
    internal fun categoryMessageRes(category: ExportFailureCategory?): Int = when (category) {
        ExportFailureCategory.NO_RECEIVER -> R.string.debug_log_share_no_receiver
        ExportFailureCategory.URI_PERMISSION -> R.string.debug_log_share_uri_denied
        ExportFailureCategory.BAD_ARGUMENT -> R.string.debug_log_share_bad_argument
        ExportFailureCategory.WRITE_FAILED -> R.string.debug_log_share_write_failed
        ExportFailureCategory.UNKNOWN, null -> R.string.debug_log_share_unknown
    }

    /** 空日志不能静默无反应，读屏用户需要一句明确提示。 */
    @JvmStatic
    fun showEmptyToast(context: Context) {
        Toast.makeText(context, R.string.debug_log_empty, Toast.LENGTH_SHORT).show()
    }

    /** 从开发者选项直接分享当前日志（总量 ≤ 256 KB，超出只取尾部）。 */
    @JvmStatic
    fun shareCurrentLog(context: Context) {
        val (log, truncated) = prepareExport(context)
        if (log.isBlank()) {
            showEmptyToast(context)
            return
        }
        shareLog(context, log)
        if (truncated) {
            Toast.makeText(
                context,
                R.string.debug_log_share_truncated,
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    /** 从开发者选项直接复制当前日志（先 ≤256 KB，再按最近 200 行截断）。 */
    @JvmStatic
    fun copyCurrentLog(context: Context) {
        val (full, byteTruncated) = prepareExport(context)
        if (full.isBlank()) {
            showEmptyToast(context)
            return
        }
        val clip = tailLines(full, COPY_MAX_LINES)
        val lineTruncated = clip.lines().size < full.lines().size
        copyLogToClipboard(context, clip, byteTruncated || lineTruncated)
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
        copyLogToClipboard(context, text, false)
    }

    /** 内部入口：[truncated] 表示全文超 256 KB 或超过 200 行，需播报省略说明。 */
    internal fun copyLogToClipboard(
        context: Context,
        text: String,
        truncated: Boolean,
    ) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (manager == null) {
            Toast.makeText(context, R.string.debug_log_copy_fail, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            manager.setPrimaryClip(
                ClipData.newPlainText(context.getString(R.string.debug_log_view_title), text)
            )
            val toast = if (truncated) {
                context.getString(R.string.debug_log_copy_truncated, COPY_MAX_LINES)
            } else {
                context.getString(R.string.debug_log_copied)
            }
            Toast.makeText(context, toast, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            LogUtils.e(this, e, "copy log to clipboard failed")
            Toast.makeText(context, R.string.debug_log_copy_fail, Toast.LENGTH_SHORT).show()
        }
    }

    /** 用 ACTION_SEND 选择器分享；没有可用应用时给提示而不是崩溃。 */
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
            putExtra(Intent.EXTRA_TITLE, context.getString(R.string.debug_log_view_title))
        }
        launchShareChooser(context, intent, text)
    }

    private fun shareAsFile(context: Context, text: String) {
        val dir = File(context.cacheDir, CACHE_SUBDIR)
        val file = try {
            if (!dir.isDirectory && !dir.mkdirs()) {
                error("cannot create cache dir ${dir.absolutePath}")
            }
            // 清理上一次分享遗留的临时日志，避免 cache 里堆积多份大文件。
            dir.listFiles()?.forEach {
                if (it.name.startsWith("brailleback-log-")) it.delete()
            }
            File(dir, "brailleback-log-${System.currentTimeMillis()}.txt")
                .also { it.writeText(text, StandardCharsets.UTF_8) }
        } catch (e: Exception) {
            recordFailure(
                context,
                ExportOutcome(false, ExportFailureCategory.WRITE_FAILED,
                    "${e.javaClass.simpleName}: ${e.message.orEmpty()}"),
                text,
            )
            return
        }
        val uri = try {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
        } catch (e: Exception) {
            recordFailure(context, classifyFailure(e), text)
            return
        }
        val clip = ClipData.newUri(context.contentResolver, "brailleback-log", uri)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, context.getString(R.string.debug_log_view_title))
            // ClipData 带上 URI，选择器预览与接收方都能拿到读授权。
            clipData = clip
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        launchShareChooser(context, intent, text)
    }

    private fun launchShareChooser(context: Context, intent: Intent, text: String) {
        // 分享前预判接收方：resolveActivity 为空时直接归为「无可用应用」，
        // 不等到 startActivity 抛 ActivityNotFoundException。
        val resolved = try {
            context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        } catch (e: Exception) {
            null
        }
        if (resolved == null) {
            recordFailure(
                context,
                ExportOutcome(false, ExportFailureCategory.NO_RECEIVER,
                    "resolveActivity=null"),
                text,
            )
            return
        }
        val chooser = Intent.createChooser(
            intent,
            context.getString(R.string.debug_log_share),
        ).apply {
            // 兼容将来从服务侧调用；选择器同样带上 URI 读授权。
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(chooser)
            DebugLogExportState.lastOutcome =
                ExportOutcome(true, null, "ok")
        } catch (e: Exception) {
            recordFailure(context, classifyFailure(e), text)
        }
    }

    /**
     * 失败留痕 + 分类播报 + 剪贴板兜底：异常进文件日志，结果落状态字段，
     * 再把最近 [COPY_MAX_LINES] 行复制到剪贴板作为不依赖第三 App 的出口。
     */
    private fun recordFailure(
        context: Context,
        outcome: ExportOutcome,
        text: String,
    ) {
        DebugLogExportState.lastOutcome = outcome
        LogUtils.e(this, "log export failed [%s] %s", outcome.category, outcome.reason)

        // 自动降级到剪贴板；200 行截断口径与「复制日志」一致。
        val clip = tailLines(text, COPY_MAX_LINES)
        copyLogToClipboard(context, clip, true)

        val reasonLabel = context.getString(categoryMessageRes(outcome.category))
        val announce = context.getString(
            R.string.debug_log_share_fallback,
            reasonLabel,
            COPY_MAX_LINES,
        )
        Toast.makeText(context, announce, Toast.LENGTH_LONG).show()
    }

    /** 日志页可朗读的状态行；无历史结果时返回 null（不占一行）。 */
    @JvmStatic
    fun lastStatusText(context: Context): String? {
        val outcome = DebugLogExportState.lastOutcome ?: return null
        val detail = if (outcome.success) {
            context.getString(R.string.debug_log_export_ok)
        } else {
            context.getString(categoryMessageRes(outcome.category))
        }
        return context.getString(R.string.debug_log_export_status, detail)
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

/**
 * 最近一次日志导出结果的内存持有者。日志页（开发者选项）读取后渲染成
 * 一行可聚焦朗读的状态文本；不扩 AIDL，不持久化。
 */
object DebugLogExportState {
    @Volatile
    internal var lastOutcome: DebugLogActions.ExportOutcome? = null
}
