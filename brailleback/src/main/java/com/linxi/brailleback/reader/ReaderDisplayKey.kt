/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback.reader

import com.googlecode.eyesfree.braille.display.BrailleInputEvent

/**
 * 阅读器正文页对点显器按键的映射。
 *
 * 正文在屏时服务侧的文字输出被冻结（86 号），屏上只有本页点位；翻页、逐行、跳首末
 * 都交给点显器自己的键，用户不用离开点位去摸手机。映射写成纯查表，便于单测覆盖。
 *
 * 键位与 81 号视口一致：平移键左右、上下行键；另外把顶/底键接到首/末屏，
 * 回车做「报一下现在在哪」——冻结期间屏上不再有服务文字，用户需要一个确认手段。
 */
enum class ReaderDisplayAction {
    NONE,

    /** 上一行 / 下一行（一行 = 点显器一屏）。 */
    PREV_LINE,
    NEXT_LINE,

    /** 上一屏 / 下一屏（一整屏 = 屏幕上的一页）。 */
    PREV_PAGE,
    NEXT_PAGE,

    /** 文首 / 文末。 */
    FIRST_PAGE,
    LAST_PAGE,

    /** 播报当前页码与首行。 */
    ANNOUNCE_PAGE,
}

object ReaderDisplayKey {

    /**
     * @param command 点显器上报的命令码
     * @return 该命令在正文页要执行的动作，未映射的返回 [ReaderDisplayAction.NONE]
     */
    fun resolve(command: Int): ReaderDisplayAction {
        return when (command) {
            BrailleInputEvent.CMD_NAV_PAN_LEFT -> ReaderDisplayAction.PREV_LINE
            BrailleInputEvent.CMD_NAV_PAN_RIGHT -> ReaderDisplayAction.NEXT_LINE
            BrailleInputEvent.CMD_NAV_LINE_PREVIOUS -> ReaderDisplayAction.PREV_PAGE
            BrailleInputEvent.CMD_NAV_LINE_NEXT -> ReaderDisplayAction.NEXT_PAGE
            BrailleInputEvent.CMD_NAV_TOP -> ReaderDisplayAction.FIRST_PAGE
            BrailleInputEvent.CMD_NAV_BOTTOM -> ReaderDisplayAction.LAST_PAGE
            BrailleInputEvent.CMD_KEY_ENTER -> ReaderDisplayAction.ANNOUNCE_PAGE
            else -> ReaderDisplayAction.NONE
        }
    }
}
