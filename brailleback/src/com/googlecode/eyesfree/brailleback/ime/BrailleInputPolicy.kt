/*
 * 盲文输入统一层（第 1 层）：点显器按键 → 输入动作。
 *
 * 引擎彻底合并后，本项目只有一条输入链路：
 *   任意输入源（触屏键盘 / 点显器按键）
 *     → BrailleInputPolicy（本类，统一按键语义）
 *     → EditBuffer（BrailleLanguages.createEditBuffer 按码表创建，软件键盘同款工厂）
 *     → InputConnection
 *
 * 屏幕键盘的 sendBrailleDots()（BrailleIme.java:1715）硬编码的四个分支与本类逐条对应：
 * 空点位→ADD_SPACE_OR_NEXT_ITEM、DOT7→DELETE_CHARACTER_OR_PREVIOUS_ITEM、
 * DOT8→上屏并回车、其余→performTyping。合并后两种输入源不会再各自解释按键。
 *
 * 刻意做成纯 Kotlin、零 Android 依赖：这一段是将来最容易改错的分支判断
 * （退格吃掉上屏键、候选键吃掉导航键、八点表把 DOT7/DOT8 误当编辑键），
 * 必须能在 JVM 单测里锁死，并做变异测试。
 */
package com.googlecode.eyesfree.brailleback.ime

object BrailleInputPolicy {

    /** 8 点盲文点位掩码。brltty 传来的 argument 只应落在这一范围。 */
    const val DOTS_MASK = 0xFF

    /** 六点表下这两个点位在原生 BrailleIME 里被借作退格 / 回车，语义沿用。 */
    const val DOT7 = 0x40
    const val DOT8 = 0x80
    const val EMPTY_CELL = 0

    // 数值与 client/.../display/BrailleInputEvent.kt 一一对应。
    // 这里不直接 import 那个 Parcelable 类，是为了让本类在纯 JVM 单测里无需 android.jar。
    // 一致性由 UnifiedBrailleInput 初始化时的 require 校验兜底。
    const val CMD_NAV_LINE_PREVIOUS = 1
    const val CMD_NAV_LINE_NEXT = 2
    const val CMD_BRAILLE_KEY = 60
    const val CMD_KEY_ENTER = 70
    const val CMD_KEY_DEL = 71
    const val CMD_KEY_FORWARD_DEL = 72

    /** 一次硬件事件统一后的动作。LEGACY 表示交回原 liblouis / 导航链路。 */
    enum class Action {
        /** 追加一格点位到中文音节缓冲。 */
        APPEND_CELL,

        /** 上屏：EditBufferChinese.appendSpace 的中文语义（有内容提交且不插空格）。 */
        COMMIT,

        /** 退格：中文按「格」删，holdings 空时才删输入框已有的字。 */
        DELETE,

        /** 上屏并回车，对齐软件键盘 DOT8 / Enter。 */
        ENTER,

        /** 换下一个同音候选；无候选时调用方应降级回原来的导航动作。 */
        NEXT_CANDIDATE,
        PREVIOUS_CANDIDATE,

        /** 不归统一引擎处理（非法点位、前删、非中文码、开关关闭等）。 */
        LEGACY,
    }

    data class Route(val action: Action, val cell: Int = 0)

    /**
     * 把一条点显器输入事件映射到统一动作。
     *
     * @param unified 统一引擎是否可用；false 时一律 LEGACY。
     * @param eightDot 当前输入码表是否为八点电脑盲文。八点表里 DOT7/DOT8 是正常点位，
     *   绝不能被借作退格/回车；六点表才沿用 BrailleBack 原有的借用语义。
     */
    fun route(command: Int, argument: Int, unified: Boolean, eightDot: Boolean = false): Route {
        if (!unified) {
            return Route(Action.LEGACY)
        }
        return when (command) {
            CMD_BRAILLE_KEY -> routeDots(argument, eightDot)
            CMD_KEY_DEL -> Route(Action.DELETE)
            CMD_KEY_ENTER -> Route(Action.ENTER)
            CMD_NAV_LINE_PREVIOUS -> Route(Action.PREVIOUS_CANDIDATE)
            CMD_NAV_LINE_NEXT -> Route(Action.NEXT_CANDIDATE)
            // 前删、路由键、全局键等维持原链路；不为了统一而破坏导航。
            else -> Route(Action.LEGACY)
        }
    }

    /** 把 brltty 的 8 位点位掩码映射为动作。 */
    fun routeDots(mask: Int, eightDot: Boolean = false): Route = when {
        // brltty 的 BRL_DOTC（第 9 位，空格条 chord）等扩展值不属于可输入的盲文格。
        mask < 0 || mask > DOTS_MASK -> Route(Action.LEGACY)
        // 八点电脑盲文一格一字符，没有「空格判格」「借点当编辑键」这回事。
        eightDot && mask == EMPTY_CELL -> Route(Action.LEGACY)
        eightDot -> Route(Action.APPEND_CELL, mask)
        mask == EMPTY_CELL -> Route(Action.COMMIT)
        mask == DOT7 -> Route(Action.DELETE)
        mask == DOT8 -> Route(Action.ENTER)
        else -> Route(Action.APPEND_CELL, mask)
    }

    /**
     * 点位掩码 → 点号列表（dot 1..8）。
     *
     * 软件侧 BrailleCharacter 用的是完全相同的规则（dot n 对应 bit n-1），
     * 这个方法把契约显式写出来，供单测与日志取证使用。
     */
    fun dotMaskToDotNumbers(mask: Int): List<Int> =
        (1..8).filter { mask and (1 shl (it - 1)) != 0 }
}
