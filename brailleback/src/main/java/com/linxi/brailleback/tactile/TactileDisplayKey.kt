package com.linxi.brailleback.tactile

import com.googlecode.eyesfree.braille.display.BrailleInputEvent

/**
 * 视口态下「点显器自带按键」到视口动作的映射表。
 *
 * 为什么要有这一层：
 * 单行设备用户在触觉图里挪视口时，屏幕按钮要腾出手去摸，点显器自己的按键才是顺手的位置。
 * 这里只做纯查表，不碰 UI，便于单测锁住命令码；界面侧按结果调 [TactileStripActivity] 的
 * 视口方法（vpNavigate / vpOpenLegend / vpCloseLegend）。
 *
 * 映射（命令码取 client 模块的 [BrailleInputEvent] 常量）：
 * - 平移键左 / 右（CMD_NAV_PAN_LEFT / RIGHT）→ 左移 / 右移
 * - 上一行 / 下一行键（CMD_NAV_LINE_PREVIOUS / NEXT）→ 上移 / 下移
 * - 回车 / 确定键（CMD_KEY_ENTER）→ 图例开合
 *
 * 注意：同一事件会被 DisplayService 广播给所有已绑定客户端，视口页自己那份监听只处理上表，
 * 其余命令一律放过（返回 null），不吞键。
 */
object TactileDisplayKey {

    /** 视口动作；与屏幕按钮一一对应。 */
    enum class Action { Legend, Up, Down, Left, Right }

    fun actionFor(command: Int): Action? = when (command) {
        BrailleInputEvent.CMD_KEY_ENTER -> Action.Legend
        BrailleInputEvent.CMD_NAV_LINE_PREVIOUS -> Action.Up
        BrailleInputEvent.CMD_NAV_LINE_NEXT -> Action.Down
        BrailleInputEvent.CMD_NAV_PAN_LEFT -> Action.Left
        BrailleInputEvent.CMD_NAV_PAN_RIGHT -> Action.Right
        else -> null
    }
}
