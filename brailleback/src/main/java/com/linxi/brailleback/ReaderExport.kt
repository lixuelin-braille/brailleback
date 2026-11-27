/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 阅读器导出：格式/版面选择、版面偏好读写、后台编码写盘与成功/失败播报、书名取定。
 * 同包扩展函数，状态字段仍留在 BrailleReaderActivity；不引入 DI 框架。
 */

package com.linxi.brailleback

import android.net.Uri
import android.util.Log
import android.widget.Toast
import com.googlecode.eyesfree.utils.SettingsStore
import com.linxi.brailleback.compose.ExportFormatCatalog
import com.linxi.brailleback.compose.ExportGeometryCatalog
import com.linxi.brailleback.compose.GeometryChoice
import com.linxi.brailleback.compose.ReaderDialog
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun BrailleReaderActivity.exportFormatLabelRes(format: BrailleExportFormat): Int = when (format) {
        BrailleExportFormat.BRF -> R.string.braille_reader_export_fmt_brf
        BrailleExportFormat.BRL -> R.string.braille_reader_export_fmt_brl
        BrailleExportFormat.UNICODE_TXT -> R.string.braille_reader_export_fmt_utf
    }

internal fun BrailleReaderActivity.exportFormatDescRes(format: BrailleExportFormat): Int = when (format) {
        BrailleExportFormat.BRF -> R.string.braille_reader_export_fmt_brf_desc
        BrailleExportFormat.BRL -> R.string.braille_reader_export_fmt_brl_desc
        BrailleExportFormat.UNICODE_TXT -> R.string.braille_reader_export_fmt_utf_desc
    }

internal fun BrailleReaderActivity.onExportPicked(index: Int) {
        val format = ExportFormatCatalog.formatAt(index)
        pendingFormat = format
        showGeometryDialog()
    }

    /** 当前可用作排版的版面项：连接点显器时第一项是「按点显器方数」。 */
internal fun BrailleReaderActivity.geometryChoices(): List<GeometryChoice> = ExportGeometryCatalog.choices(
        reportedCells = mReportedCells,
        fixedColumns = PageGeometry.PRESET_COLUMNS,
        fixedLines = PageGeometry.PRESET_LINES,
        linesForFollow = loadGeometry().linesPerPage,
        wordWrap = wordWrapEnabled(),
    )

internal fun BrailleReaderActivity.showGeometryDialog() {
        val choices = geometryChoices()
        val labels = choices.map { choice ->
            when (choice) {
                is GeometryChoice.FollowDisplay -> getString(
                    R.string.braille_reader_export_geometry_follow,
                    choice.cells, choice.lines)
                is GeometryChoice.Fixed -> getString(
                    R.string.braille_reader_export_geometry_item,
                    choice.columns, choice.lines)
                GeometryChoice.Custom ->
                    getString(R.string.braille_reader_export_geometry_custom)
                is GeometryChoice.WrapToggle -> getString(
                    if (choice.wordWrap) R.string.braille_reader_export_wrap_word
                    else R.string.braille_reader_export_wrap_cell)
            }
        }
        val reported = ExportGeometryCatalog.followCells(mReportedCells)
        val hint = if (reported != null) {
            getString(R.string.braille_reader_export_geometry_hint_connected, reported)
        } else {
            getString(R.string.braille_reader_export_geometry_hint_offline)
        }
        readerDialog = ReaderDialog.ExportGeometry(
            getString(R.string.braille_reader_export_geometry_title), labels, hint)
    }

internal fun BrailleReaderActivity.onGeometryPicked(which: Int) {
        when (val choice = geometryChoices().getOrNull(which)) {
            is GeometryChoice.FollowDisplay -> {
                readerDialog = null
                saveFollowDisplay(true)
                startExport(pendingFormat ?: BrailleExportFormat.BRF)
            }
            is GeometryChoice.Fixed -> {
                readerDialog = null
                saveFollowDisplay(false)
                saveGeometry(PageGeometry(choice.columns, choice.lines))
                startExport(pendingFormat ?: BrailleExportFormat.BRF)
            }
            GeometryChoice.Custom, null -> {
                saveFollowDisplay(false)
                readerDialog = ReaderDialog.ExportCustomGeometry(
                    getString(R.string.braille_reader_export_geometry_title))
            }
            is GeometryChoice.WrapToggle -> {
                // 折行开关不导出、不关弹窗：切完立刻把弹窗按新状态重建，
                // 让用户马上听到「现在是按词断/按方数」，再决定选哪一档版面。
                val next = !choice.wordWrap
                saveWordWrap(next)
                announce(getString(
                    if (next) R.string.braille_reader_export_wrap_word_announce
                    else R.string.braille_reader_export_wrap_cell_announce))
                showGeometryDialog()
            }
        }
    }

internal fun BrailleReaderActivity.onCustomGeometryConfirmed(columns: Int, lines: Int) {
        val geometry = PageGeometry(
            columns.coerceIn(BrailleReaderActivity.EXPORT_MIN_COLUMNS, BrailleReaderActivity.EXPORT_MAX_COLUMNS),
            lines.coerceIn(BrailleReaderActivity.EXPORT_MIN_LINES, BrailleReaderActivity.EXPORT_MAX_LINES),
        )
        saveGeometry(geometry)
        saveFollowDisplay(false)
        readerDialog = null
        startExport(pendingFormat ?: BrailleExportFormat.BRF)
    }

internal fun BrailleReaderActivity.loadGeometry(): PageGeometry {
        val prefs = SettingsStore.get(this)
        val columns = prefs.getInt(BrailleReaderActivity.KEY_EXPORT_COLUMNS, PageGeometry.Default.columns)
        val lines = prefs.getInt(BrailleReaderActivity.KEY_EXPORT_LINES, PageGeometry.Default.linesPerPage)
        return PageGeometry(
            columns.coerceIn(BrailleReaderActivity.EXPORT_MIN_COLUMNS, BrailleReaderActivity.EXPORT_MAX_COLUMNS),
            lines.coerceIn(BrailleReaderActivity.EXPORT_MIN_LINES, BrailleReaderActivity.EXPORT_MAX_LINES),
        )
    }

internal fun BrailleReaderActivity.saveGeometry(geometry: PageGeometry) {
        SettingsStore.get(this).edit()
            .putInt(BrailleReaderActivity.KEY_EXPORT_COLUMNS, geometry.columns)
            .putInt(BrailleReaderActivity.KEY_EXPORT_LINES, geometry.linesPerPage)
            .apply()
    }

    /** 导出排版是否跟随点显器方数；默认开启，但只在有可用设备方数时才生效。 */
internal fun BrailleReaderActivity.followDisplayEnabled(): Boolean =
        SettingsStore.get(this).getBoolean(BrailleReaderActivity.KEY_EXPORT_FOLLOW_DISPLAY, true)

internal fun BrailleReaderActivity.saveFollowDisplay(enabled: Boolean) {
        SettingsStore.get(this).edit()
            .putBoolean(BrailleReaderActivity.KEY_EXPORT_FOLLOW_DISPLAY, enabled)
            .apply()
    }

    /** 导出折行是否按词断；默认开启，关掉就是改造前的纯方数硬切。 */
internal fun BrailleReaderActivity.wordWrapEnabled(): Boolean =
        SettingsStore.get(this).getBoolean(BrailleReaderActivity.KEY_EXPORT_WORD_WRAP, true)

internal fun BrailleReaderActivity.saveWordWrap(enabled: Boolean) {
        SettingsStore.get(this).edit()
            .putBoolean(BrailleReaderActivity.KEY_EXPORT_WORD_WRAP, enabled)
            .apply()
    }

    /** 真正用于导出/播报的版面：跟随开启且有设备方数时按设备方数折行。 */
internal fun BrailleReaderActivity.resolvedGeometry(): PageGeometry = ExportGeometryCatalog.resolve(
        stored = loadGeometry(),
        followEnabled = followDisplayEnabled(),
        reportedCells = mReportedCells,
    )

internal fun BrailleReaderActivity.startExport(format: BrailleExportFormat) {
        if (mExporting || isFinishing) return
        mExporting = true
        pendingFormat = format
        val geometry = resolvedGeometry()
        val pages = BrailleExporter.pageCount(mStream, geometry)
        announce(getString(R.string.braille_reader_export_geometry_preview,
            geometry.columns, geometry.linesPerPage, pages))
        Toast.makeText(this, R.string.braille_reader_export_preparing,
            Toast.LENGTH_SHORT).show()

        val base = BrailleExporter.sanitizeFileName(currentBookName())
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault())
            .format(Date())
        val initialName = "$base$stamp.${format.extension}"
        when (format) {
            BrailleExportFormat.BRF -> brfExportLauncher.launch(initialName)
            BrailleExportFormat.BRL,
            BrailleExportFormat.UNICODE_TXT -> textExportLauncher.launch(initialName)
        }
    }

internal fun BrailleReaderActivity.onExportUri(uri: Uri?) {
        if (isFinishing) {
            mExporting = false
            return
        }
        if (uri == null) {
            mExporting = false
            announce(getString(R.string.braille_reader_export_cancelled))
            Toast.makeText(this, R.string.braille_reader_export_cancelled,
                Toast.LENGTH_SHORT).show()
            return
        }
        val format = pendingFormat ?: BrailleExportFormat.BRF
        // 后台线程不碰 Activity 状态：先把版面定死再进线程，
        // 保证「播报的页数」和「写进文件的行列」是同一个版面。
        val geometry = resolvedGeometry()
        // 折行方式同样在主线程定死，后台线程只按参数编码。
        val wrapMode = if (wordWrapEnabled()) BrailleWrapMode.WORD else BrailleWrapMode.CELL
        Thread {
            val result = try {
                BrailleExporter.export(mStream, format, geometry, wrapMode)
            } catch (e: Exception) {
                Log.w(BrailleReaderActivity.LOG_TAG, "导出编码失败: ${e.message}")
                null
            }
            if (result == null) {
                runOnUiThread { exportFail() }
                return@Thread
            }
            try {
                contentResolver.openOutputStream(uri)?.use { out ->
                    val bytes = if (format == BrailleExportFormat.BRF) {
                        result.content.toByteArray(StandardCharsets.US_ASCII)
                    } else {
                        result.content.toByteArray(StandardCharsets.UTF_8)
                    }
                    out.write(bytes)
                } ?: throw IOException("openOutputStream returned null")
                runOnUiThread { exportSuccess(uri, result) }
            } catch (e: Exception) {
                Log.w(BrailleReaderActivity.LOG_TAG, "导出写盘失败: ${e.message}")
                runOnUiThread { exportFail() }
            }
        }.start()
    }

internal fun BrailleReaderActivity.exportSuccess(uri: Uri, result: BrailleExportResult) {
        mExporting = false
        val warnings = buildList {
            if (result.unmappableCells > 0) {
                add(getString(R.string.braille_reader_export_warn_unmappable,
                    result.unmappableCells))
            }
            if (result.droppedChars > 0) {
                add(getString(R.string.braille_reader_export_warn_dropped,
                    result.droppedChars))
            }
            if (result.eightDotCells > 0) {
                add(getString(R.string.braille_reader_export_warn_eightdot,
                    result.eightDotCells))
            }
        }
        val fileName = uri.lastPathSegment ?: ""
        val message = buildString {
            append(getString(R.string.braille_reader_export_done, fileName))
            if (warnings.isNotEmpty()) {
                append("；").append(warnings.joinToString("；"))
            }
        }
        announce(message)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

internal fun BrailleReaderActivity.exportFail() {
        mExporting = false
        announce(getString(R.string.braille_reader_export_fail))
        Toast.makeText(this, R.string.braille_reader_export_fail,
            Toast.LENGTH_LONG).show()
    }

internal fun BrailleReaderActivity.currentBookName(): String {
        val fromResolver = mFileUri?.let { displayNameOf(it) }
        return fromResolver ?: mFileUri?.lastPathSegment ?: "book"
    }
