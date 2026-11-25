/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 选图与后台转换管线：选图摘要、IO 线程解码排版（多行三段式 Book / 单行视口两条路由）、
 * 图例检测与翻译，回主线程绑定书 / 视口。纯重构拆分自 TactileStripActivity.kt，方法体
 * 逐字未改；以同包 internal 扩展函数承载，状态仍由 Activity 持有。
 */

package com.linxi.brailleback.tactile

import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.lifecycleScope
import com.linxi.brailleback.BuildConfig
import com.linxi.brailleback.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun TactileStripActivity.onImagePicked(uri: Uri) {
    sourceSummary = describeUri(uri)
    sourceUri = uri
    rebuild()
}

/** 查询文件名与大小摘要；provider 不支持查询时回落到 URI 末段，不阻断选图。 */
internal fun TactileStripActivity.describeUri(uri: Uri): String {
    var name: String? = null
    var sizeBytes: Long = -1
    try {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIdx >= 0) name = cursor.getString(nameIdx)
                if (sizeIdx >= 0) sizeBytes = cursor.getLong(sizeIdx)
            }
        }
    } catch (e: Exception) {
        // 查询失败按无名处理，摘要退让到 URI 末段。
    }
    val fallback = uri.lastPathSegment ?: ""
    val label = name?.takeIf { it.isNotBlank() } ?: fallback
    return if (sizeBytes >= 0) {
        getString(R.string.tactile_strip_image_picked_size, label, formatSize(sizeBytes))
    } else {
        getString(R.string.tactile_strip_image_picked, label)
    }
}

internal fun TactileStripActivity.formatSize(bytes: Long): String {
    val kb = bytes / 1024.0
    return if (kb >= 1024) String.format("%.1f MB", kb / 1024.0) else "${kb.toInt()} KB"
}

/** 后台解码 + 排版；单行设备进视口模式，多行设备保留三段式 Book。失败不刷新上一版。 */
internal fun TactileStripActivity.rebuild() {
    val activity = this
    val uri = sourceUri ?: return
    converting = true
    showStatus(getString(R.string.tactile_strip_converting))
    lifecycleScope.launch(Dispatchers.IO) {
        if (BuildConfig.DEBUG) {
            Log.d(
                DEBUG_TAG,
                "rebuild start: uri=${uri.lastPathSegment} cells=$cellsPerLine " +
                    "lines=$linesPerScreen dots=$dotsPerCell norm=$normalizerMode g1=$g1SplitCover",
            )
        }
        val spec = ImageStripEncoder.Spec.forDisplay(cellsPerLine, linesPerScreen, dotsPerCell)
        val outcome: Any? = try {
            if (spec.linesPerScreen == 1) {
                if (BuildConfig.DEBUG) Log.d(DEBUG_TAG, "route=viewport")
                buildViewport(uri, spec)
            } else {
                if (BuildConfig.DEBUG) Log.d(DEBUG_TAG, "route=session")
                TactileStripSession.build(
                    activity, uri, spec,
                    g1SplitCover = g1SplitCover,
                    normalizerMode = normalizerMode,
                )
            }
        } catch (e: Exception) {
            // DEBUG 下记录异常类型 / message / stacktrace；最后一条 stage 打点即失败阶段。
            if (BuildConfig.DEBUG) {
                Log.e(
                    DEBUG_TAG,
                    "rebuild failed: type=${e.javaClass.name} msg=${e.message}",
                    e,
                )
            }
            null
        }
        withContext(Dispatchers.Main) {
            converting = false
            when (outcome) {
                is StripBook -> {
                    if (BuildConfig.DEBUG) Log.d(DEBUG_TAG, "bind=book pages=${outcome.pages.size}")
                    book = outcome
                    pager = TactileStripPager(outcome)
                    viewport = null
                    vpLegend = false
                    legendBrailleLines = emptyList()
                    renderCurrent(announce = true)
                }
                is TactileViewport -> {
                    if (BuildConfig.DEBUG) Log.d(DEBUG_TAG, "bind=viewport")
                    viewport = outcome
                    book = null
                    pager = null
                    vpLegend = false
                    renderCurrent(announce = true)
                }
                else -> showStatus(getString(R.string.tactile_strip_fail_decode))
            }
        }
    }
}

/** 单行视口链路：读图 → 全局点阵 → 视口；不切三段式封面 / 封底。 */
internal fun TactileStripActivity.buildViewport(
    uri: Uri,
    spec: ImageStripEncoder.Spec,
): TactileViewport {
    if (BuildConfig.DEBUG) Log.d(DEBUG_TAG, "stage=load viewport")
    val gray = TactileStripSource.loadGrayImage(this, uri)
    if (BuildConfig.DEBUG) {
        Log.d(DEBUG_TAG, "stage=decode viewport gray=${gray.width}x${gray.height}")
    }
    val cols = spec.dotColumns
    val rows = maxOf(
        1,
        Math.round(cols.toFloat() * gray.height.toFloat() / gray.width).toInt(),
    )
    val ink = ImageStripEncoder.dotGrid(gray, cols, rows, spec.threshold)
    val vp = TactileViewport(ink, cols, rows, spec)
    // 图例为辅助屏：检测 / 翻译失败不阻塞图形视口，图例回退空屏。
    try {
        prepareLegend(vp)
    } catch (e: Exception) {
        legendBrailleLines = emptyList()
    }
    return vp
}

/** 检测图内标注候选位置，合成源行并经翻译桥翻成盲文；只报位置，不识别文字内容。 */
internal fun TactileStripActivity.prepareLegend(vp: TactileViewport) {
    val boxes = TactileLabelDetector.detect(
        vp.ink, vp.cols, vp.rows,
        TactileLabelDetector.Config(enabled = true),
    )
    val source = TactileLegendPage.labelSourceLines(
        boxes, vp.bandRows, vp.spec.cellsPerLine,
    )
    val bridge = TactileStripCaptionBridge(this)
    try {
        legendBrailleLines = source.map { bridge.toBraille(it) }
    } finally {
        bridge.close()
    }
}
