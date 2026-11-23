/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback.compose

import com.linxi.brailleback.PageGeometry

/**
 * 导出排版选项：连接点显器时，第一项是「按点显器方数排版」。
 *
 * 为什么需要它：点显器会通过驱动上报自己的方数（numTextCells），但导出排版此前只有
 * 20/30/32/40 四档固定方数，连接 32 方设备的人要自己算该选哪一档。这里把设备方数
 * 变成选项本身，并在「跟随设备」时用它作为每行方数，断开后自动回落上次的固定版面。
 *
 * 全部纯逻辑，可在 JVM 单测里覆盖；Activity 只负责把字符串资源和偏好喂进来。
 */
internal sealed interface GeometryChoice {

    /** 按点显器方数排版：方数取自设备上报，随连接变化。 */
    data class FollowDisplay(val cells: Int, val lines: Int) : GeometryChoice

    /** 固定版面（20/30/32/40 × 20/25/28）。 */
    data class Fixed(val columns: Int, val lines: Int) : GeometryChoice

    /** 自定义方数 × 行数。 */
    data object Custom : GeometryChoice
}

internal object ExportGeometryCatalog {

    /** 可用的方数下限：再低就不是一行盲文了，按设备方数排也不成立。 */
    const val MIN_FOLLOW_CELLS = 8

    /** 可用的方数上限：常见点显器 12–80 方，超出视为驱动误报。 */
    const val MAX_FOLLOW_CELLS = 80

    /** 设备上报的方数是否可用；未连接（null）、0、负数、超上限都视为不可用。 */
    fun followCells(reportedCells: Int?): Int? =
        reportedCells?.takeIf { it in MIN_FOLLOW_CELLS..MAX_FOLLOW_CELLS }

    /**
     * 版面菜单项：能跟随时第一项是 [GeometryChoice.FollowDisplay]，
     * 其后按固定方数 × 固定行数铺开，最后一项自定义。
     */
    fun choices(
        reportedCells: Int?,
        fixedColumns: List<Int>,
        fixedLines: List<Int>,
        linesForFollow: Int,
    ): List<GeometryChoice> {
        val cells = followCells(reportedCells)
        return buildList {
            if (cells != null) add(GeometryChoice.FollowDisplay(cells, linesForFollow))
            fixedColumns.forEach { columns ->
                fixedLines.forEach { lines ->
                    add(GeometryChoice.Fixed(columns, lines))
                }
            }
            add(GeometryChoice.Custom)
        }
    }

    /**
     * 真正用于导出的版面：
     * 跟随开启且有可用设备方数时，每行方数取设备方数、每页行数沿用用户上次的行数；
     * 否则一律用已保存的固定版面（因此断开点显器不会改变导出结果）。
     */
    fun resolve(
        stored: PageGeometry,
        followEnabled: Boolean,
        reportedCells: Int?,
    ): PageGeometry {
        if (!followEnabled) return stored
        val cells = followCells(reportedCells) ?: return stored
        return PageGeometry(cells, stored.linesPerPage)
    }
}
