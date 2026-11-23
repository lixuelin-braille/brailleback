/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback.compose

import com.linxi.brailleback.BrailleExportFormat

/**
 * 导出格式目录：弹窗序号 → 导出格式。
 *
 * 弹窗里的标题与说明按 [formats] 的声明顺序排列，序号越界回落 BRF，
 * 这样新增格式时只要改动枚举就能自动出现在弹窗，不需要同步改 Activity 的下标。
 */
internal object ExportFormatCatalog {

    /** 弹窗渲染顺序，与 [BrailleExportFormat.entries] 一致。 */
    val formats: List<BrailleExportFormat> = BrailleExportFormat.entries

    /** 序号 → 格式；越界或负数一律回落 BRF（默认格式）。 */
    fun formatAt(index: Int): BrailleExportFormat =
        formats.getOrElse(index) { BrailleExportFormat.BRF }
}
