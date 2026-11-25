/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 三段式（封面 → 图形带 → 封底）翻页与当前屏渲染：翻页步进、空图提示、把当前屏字节
 * 下发到点显器并刷新状态行。纯重构拆分自 TactileStripActivity.kt，方法体逐字未改；
 * 以同包 internal 扩展函数承载，状态仍由 Activity 持有。
 */

package com.linxi.brailleback.tactile

import com.linxi.brailleback.R

internal fun TactileStripActivity.goFirst() = moveTo { first() }
internal fun TactileStripActivity.goPrev() = moveTo { prev() }
internal fun TactileStripActivity.goNext() = moveTo { next() }
internal fun TactileStripActivity.goLast() = moveTo { last() }

/** 统一翻页入口：空图（pager 为 null）只提示选图，不碰空指针。 */
internal fun TactileStripActivity.moveTo(step: TactileStripPager.() -> Boolean) {
    val p = pager ?: run {
        showStatus(getString(R.string.tactile_strip_need_image))
        return
    }
    if (p.step()) {
        renderCurrent(announce = true)
    }
    // 已在边界时 pager 序号不变，不重复播报结束提示。
}

/** 把当前屏字节下发到点显器，并刷新状态行；announce 控制是否语音播报。 */
internal fun TactileStripActivity.renderCurrent(announce: Boolean) {
    // 图形页（视口 / 三段式）在屏时，点显器归本页独占，先挡住服务侧文字。
    syncDisplayHold()
    if (viewport != null) {
        renderViewport(announce)
        return
    }
    val p = pager ?: return
    // 只下发当前屏字节；页签 / 带号走 TTS，不写进点位字节、不预留显示格。
    display?.show(p.current.bytes, p.label())
    val note = if (displayConnected) {
        getString(R.string.tactile_strip_connected)
    } else {
        getString(R.string.tactile_strip_not_connected)
    }
    status = when (p.current.kind) {
        StripPageKind.COVER -> getString(R.string.tactile_strip_status_cover, note)
        StripPageKind.GRAPHIC ->
            getString(R.string.tactile_strip_status_graphic, p.label(), note)
        StripPageKind.BACK -> getString(R.string.tactile_strip_status_back, note)
    }
    if (announce) {
        // 封面额外补一句操作引导；图形 / 封底直接播核心层生成的页签文案。
        val speak = if (p.current.kind == StripPageKind.COVER) {
            getString(R.string.tactile_strip_cover_hint)
        } else {
            p.label()
        }
        announce(speak)
    }
}
