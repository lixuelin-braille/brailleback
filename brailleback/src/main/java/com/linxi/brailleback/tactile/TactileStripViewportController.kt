/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 单行设备视口链路：总览 / 细节 / 图例三态渲染、平移与翻块导航、图例翻页、点显器
 * 独占申请与交还、边界短震。纯重构拆分自 TactileStripActivity.kt，方法体逐字未改；
 * 以同包 internal 扩展函数承载，状态仍由 Activity 持有。
 */

package com.linxi.brailleback.tactile

import android.os.Build
import android.os.VibrationEffect
import com.linxi.brailleback.BrailleBackService
import com.linxi.brailleback.R

/** 渲染当前视口屏：总览 / 细节 / 图例三态；页签走 TTS，不写进点位字节。 */
internal fun TactileStripActivity.renderViewport(announce: Boolean) {
    syncDisplayHold()
    val vp = viewport ?: return
    val note = if (displayConnected) {
        getString(R.string.tactile_strip_connected)
    } else {
        getString(R.string.tactile_strip_not_connected)
    }

    if (vpLegend) {
        val pages = legendPageCount(vp)
        val page = vpLegendPage.coerceIn(0, pages - 1)
        val pageLabel = if (pages > 1) {
            getString(R.string.tactile_vp_legend_page_label, page + 1, pages)
        } else {
            getString(R.string.tactile_vp_legend)
        }
        display?.show(legendBytes(vp, page), pageLabel)
        status = if (pages > 1) {
            getString(R.string.tactile_vp_status_legend_page, page + 1, pages, note)
        } else {
            getString(R.string.tactile_vp_status_legend, note)
        }
        if (announce) announce(pageLabel)
        return
    }

    if (vp.isOverview) {
        display?.show(vp.overviewBytes(), vp.overviewLabel)
        status = getString(R.string.tactile_vp_status_overview, note)
        if (announce) announce(getString(R.string.tactile_vp_hint))
    } else {
        val label = getString(
            R.string.tactile_vp_block_page,
            vp.blockIndex + 1,
            vp.blockCount,
            vp.blockPageIndex + 1,
            vp.blockPageCount(),
        )
        display?.show(vp.windowBytes(), label)
        status = getString(R.string.tactile_vp_status_detail, label, note)
        if (announce) announce(label)
    }
}

/** 图例屏总页数；单行设备一行一屏，所以候选越多页越多。 */
internal fun TactileStripActivity.legendPageCount(vp: TactileViewport): Int =
    TactileLegendPage.pageCount(legendBrailleLines.size, vp.spec.linesPerScreen)

/** 图例第 [page] 屏的点位；无候选时出「未检测到图内标注」屏。 */
internal fun TactileStripActivity.legendBytes(vp: TactileViewport, page: Int): ByteArray =
    TactileLegendPage.build(
        TactileLegendPage.pageLines(legendBrailleLines, page, vp.spec.linesPerScreen),
        vp.spec,
    )

/** 图例翻页：越界短震并播报「已是第一屏 / 最后一屏」，不重复朗读整页。 */
internal fun TactileStripActivity.vpLegendNavigate(delta: Int) {
    val vp = viewport ?: return
    val pages = legendPageCount(vp)
    val next = (vpLegendPage + delta).coerceIn(0, pages - 1)
    if (next == vpLegendPage) {
        buzz()
        announce(
            getString(
                if (delta < 0) R.string.tactile_vp_legend_first_page
                else R.string.tactile_vp_legend_last_page
            )
        )
        return
    }
    vpLegendPage = next
    renderViewport(announce = true)
}

/**
 * 点显器按键分发：只在视口态生效，映射见 [TactileDisplayKey]。
 *
 * 图例屏：回车（或再按图例键）退回图形页；四个方向键翻图例页——单行设备一行一屏，
 * 候选多的图例必然超过一屏，翻页比「一按就退出」有用。
 */
internal fun TactileStripActivity.onDisplayKey(command: Int) {
    if (viewport == null) return
    val action = TactileDisplayKey.actionFor(command) ?: return
    if (vpLegend) {
        when (action) {
            TactileDisplayKey.Action.Legend -> vpCloseLegend()
            TactileDisplayKey.Action.Up, TactileDisplayKey.Action.Left -> vpLegendNavigate(-1)
            TactileDisplayKey.Action.Down, TactileDisplayKey.Action.Right -> vpLegendNavigate(1)
        }
        return
    }
    when (action) {
        TactileDisplayKey.Action.Legend -> vpOpenLegend()
        TactileDisplayKey.Action.Up -> vpNavigate(0, -1)
        TactileDisplayKey.Action.Down -> vpNavigate(0, 1)
        TactileDisplayKey.Action.Left -> vpNavigate(-1, 0)
        TactileDisplayKey.Action.Right -> vpNavigate(1, 0)
    }
}

/**
 * 视口导航统一入口：总览态移动选择框（横向按方、纵向按块）；细节态横向平移，纵向先翻
 * 块内屏，到块首 / 块末再换相邻块。成功刷新当前屏，受阻时短震并播报边界，状态不变。
 *
 * 横向到边改成「换行」而不是「到头」：两键点显器的按键只上报平移键，
 * 到边后按阅读顺序继续纵向走（见 [TactileViewport.wrapRight]/[TactileViewport.wrapLeft]），
 * 只有整图首 / 末屏才真的播报边界。
 */
internal fun TactileStripActivity.vpNavigate(dxCells: Int, dyBands: Int) {
    val vp = viewport ?: run {
        showStatus(getString(R.string.tactile_strip_need_image))
        return
    }
    if (vpLegend) return
    val horizontal = dxCells != 0
    val result: MoveResult = when {
        vp.isOverview -> {
            val box = vp.moveBox(dxCells, dyBands)
            when {
                box.moved -> box
                horizontal && box.boundary == Boundary.LEFT -> vp.wrapLeft()
                horizontal && box.boundary == Boundary.RIGHT -> vp.wrapRight()
                else -> box
            }
        }
        dxCells < 0 -> {
            val r = vp.panLeft()
            if (!r.moved && r.boundary == Boundary.LEFT) vp.wrapLeft() else r
        }
        dxCells > 0 -> {
            val r = vp.panRight()
            if (!r.moved && r.boundary == Boundary.RIGHT) vp.wrapRight() else r
        }
        dyBands < 0 -> {
            val r = vp.prevPage()
            if (!r.moved && r.boundary == Boundary.BLOCK_START) vp.prevBlock() else r
        }
        else -> {
            val r = vp.nextPage()
            if (!r.moved && r.boundary == Boundary.BLOCK_END) vp.nextBlock() else r
        }
    }
    if (result.moved) {
        renderViewport(announce = true)
    } else {
        buzz()
        result.boundary?.let { announce(boundaryText(it)) }
    }
}

internal fun TactileStripActivity.boundaryText(boundary: Boundary): String = when (boundary) {
    Boundary.LEFT -> getString(R.string.tactile_vp_left_end)
    Boundary.RIGHT -> getString(R.string.tactile_vp_right_end)
    Boundary.TOP -> getString(R.string.tactile_vp_top_end)
    Boundary.BOTTOM -> getString(R.string.tactile_vp_bottom_end)
    Boundary.BLOCK_START -> getString(R.string.tactile_vp_block_start)
    Boundary.BLOCK_END -> getString(R.string.tactile_vp_block_end)
    Boundary.FIRST_BLOCK -> getString(R.string.tactile_vp_first_block)
    Boundary.LAST_BLOCK -> getString(R.string.tactile_vp_last_block)
}

internal fun TactileStripActivity.vpEnterDetail() {
    viewport?.enterDetail()
    vpLegend = false
    renderViewport(announce = true)
}

internal fun TactileStripActivity.vpBackOverview() {
    viewport?.backToOverview()
    vpLegend = false
    renderViewport(announce = true)
}

/**
 * 图形页在屏时独占点显器：视口三态（总览 / 细节 / 图例）和三段式的图形页都是我们自己
 * 下发的点位，服务侧文字一翻上来就会盖住。只要图形页还在，就冻结服务输出；
 * 回到没有图形页的状态（或没连点显器）就交还。
 */
internal fun TactileStripActivity.syncDisplayHold() {
    if ((viewport != null || pager != null) && displayConnected) holdDisplay() else releaseDisplay()
}

/** 申请独占；服务没起 / 点显器没连时按「没冻上」处理，不报错。 */
internal fun TactileStripActivity.holdDisplay() {
    if (holdingDisplay) return
    holdingDisplay = BrailleBackService.holdDisplayForApp()
}

/** 交还点显器；重复调用无副作用，未持有过也不打扰服务。 */
internal fun TactileStripActivity.releaseDisplay() {
    if (!holdingDisplay) return
    holdingDisplay = false
    BrailleBackService.releaseDisplayForApp()
}

internal fun TactileStripActivity.vpOpenLegend() {
    vpLegend = true
    // 每次进图例都从第一屏开始，免得上次停在半截让人以为图例缺内容。
    vpLegendPage = 0
    renderViewport(announce = true)
}

internal fun TactileStripActivity.vpCloseLegend() {
    vpLegend = false
    vpLegendPage = 0
    renderViewport(announce = true)
}

/** 边界短震；马达 / 权限不可用时静默降级，不抛错、不阻断浏览。 */
internal fun TactileStripActivity.buzz() {
    try {
        val v = vibrator ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(20)
        }
    } catch (e: Exception) {
        // 无震动能力：忽略。
    }
}
