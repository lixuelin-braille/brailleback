/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback.keymap

/**
 * 盲文显示器「按键 → 动作」自定义映射的纯逻辑层，零 Android 依赖。
 *
 * 设计对照 Google TalkBack（本地参考源码 `/d/软件/TalkBack-src.zip`）：
 * - `KeyComboModel.getDefaultKeyCombo / saveKeyCombo / clearKeyCombo`：默认值 + 单条覆盖 + 单条复位；
 *   本类对应 [DEFAULT_ACTION]、[withMapping] 的覆盖与删除。
 * - `KeyComboPersister` / `BrailleUserPreferences.writeGestureAction`：整张映射表序列化后写
 *   SharedPreferences；本类对应 [parse] / [serialize]（Google 用 Gson JSON，这里用无依赖的
 *   `源=目标;...` 文本，避免为一张小表引入 JSON 库与包体开销）。
 * - `SupportedCommand`：静态的「可分配动作清单」+ 分类；本类对应 [REMAPPABLE_COMMANDS] /
 *   [ASSIGNABLE_ACTIONS]，中文标签由资源层提供。
 * - `KeyComboModel.getKeyForKeyCombo`：反向查重；本模型以「源按键」为 Map 的键，天然保证
 *   一个按键只能有一个动作；「多个按键指向同一动作」是有意允许的（盲人用户常需要两个键
 *   都能执行返回），因此不做反向冲突拒绝，只在 [duplicateTargets] 中如实列出。
 *
 * 边界（实测自本项目分发链路，勿随意放宽）：
 * - 源不含带位置参数的 `CMD_ROUTE` / `CMD_LONG_PRESS_ROUTE` / `CMD_SELECTION_START|END`：
 *   `DisplayManager.sendMappedEvent` 会对 ARGUMENT_POSITION 做坐标换算，重映射后参数语义错乱。
 * - 源不含 `CMD_BRAILLE_KEY`：其 argument 是点位，改走别的动作会毁掉盲文输入。
 * - 源不含 `CMD_GLOBAL_*` / `CMD_HELP`：Google 在 `BrailleBackService.handleGlobalCommands`
 *   明确标注「Global commands can't be overriden」，保留系统级出口防止用户把自己锁死。
 *   但这些命令允许作为**目标动作**（把某个导航键改成「返回」是常见且安全的需求）。
 * - 映射只查一层，不递归：A→B 且 B→C 时，A 只变成 B。避免循环与意外连锁。
 * - 目标里有一个虚拟动作 [NO_ACTION]（「无动作」）：它不对应任何真实命令码，
 *   运行时由 `CustomKeyMapStore.runtimeMap` 翻译成 `BrailleInputEvent.CMD_NONE`，
 *   再由 `DisplayManager.applyCustomKeyMap` 吞掉整个事件。
 *   用途：用户嫌某个物理键难按（典型是点显器侧面的滚屏/滚平条），可以把它彻底空转，
 *   改用一个顺手的键来做同一个动作。没有它，任何映射都必须指向一个真实动作，
 *   等于无法「停用」某个键。
 */
object BrailleKeyMapPolicy {

    /** UI 虚拟动作：删除该按键的自定义映射，恢复默认行为。不会写入映射表。 */
    const val DEFAULT_ACTION = "DEFAULT"

    /**
     * UI 虚拟动作：该按键不做任何事（事件在 `DisplayManager` 被吞掉）。
     *
     * 与 [DEFAULT_ACTION] 的区别：DEFAULT 表示「表里没有这一条」（行为回到出厂），
     * NONE 表示「表里这一条指向空动作」（出厂动作被显式覆盖为「不执行」）。
     * 它不是 `BrailleInputEvent.CMD_NAMES` 里登记的真实命令，因此不参与运行时解析。
     */
    const val NO_ACTION = "NONE"

    const val ENTRY_SEPARATOR = ';'
    const val KEY_VALUE_SEPARATOR = '='

    /**
     * 允许被重新定义动作的按键命令名。
     *
     * 每一项都必须在 `BrailleInputEvent.CMD_NAMES` 中登记（否则 `stringToCommand` 解析不出
     * 命令码），且必须在本项目确有消费者（见 `DefaultNavigationMode` / `BrailleBackService` /
     * `DisplayManager` / `IMEHelper`）。资源 `R.array.key_map_source_values` 必须与本表完全一致，
     * 由 `KeyMapResourceContractTest` 静态锁定。
     */
    val REMAPPABLE_COMMANDS: List<String> = listOf(
        "CMD_NAV_PAN_LEFT",
        "CMD_NAV_PAN_RIGHT",
        "CMD_NAV_ITEM_PREVIOUS",
        "CMD_NAV_ITEM_NEXT",
        "CMD_NAV_LINE_PREVIOUS",
        "CMD_NAV_LINE_NEXT",
        "CMD_SCROLL_BACKWARD",
        "CMD_SCROLL_FORWARD",
        "CMD_NAV_TOP",
        "CMD_NAV_BOTTOM",
        "CMD_ACTIVATE_CURRENT",
        "CMD_LONG_PRESS_CURRENT",
        "CMD_SECTION_PREVIOUS",
        "CMD_SECTION_NEXT",
        "CMD_CONTROL_PREVIOUS",
        "CMD_CONTROL_NEXT",
        "CMD_LIST_PREVIOUS",
        "CMD_LIST_NEXT",
        "CMD_TOGGLE_INCREMENTAL_SEARCH",
        "CMD_TOGGLE_BRAILLE_MENU",
        "CMD_TOGGLE_BRAILLE_GRADE",
        "CMD_KEY_ENTER",
        "CMD_KEY_DEL",
        "CMD_KEY_FORWARD_DEL",
    )

    /** 顶层独占的系统动作：只能作为目标，不能作为被改掉的源按键。 */
    val GLOBAL_ACTIONS: List<String> = listOf(
        "CMD_GLOBAL_BACK",
        "CMD_GLOBAL_HOME",
        "CMD_GLOBAL_RECENTS",
        "CMD_GLOBAL_NOTIFICATIONS",
        "CMD_HELP",
    )

    /** 允许选择的目标动作清单（不含虚拟项 [DEFAULT_ACTION]，含虚拟项 [NO_ACTION]）。 */
    val ASSIGNABLE_ACTIONS: List<String> =
        REMAPPABLE_COMMANDS + GLOBAL_ACTIONS + NO_ACTION

    private val REMAPPABLE_SET: Set<String> = REMAPPABLE_COMMANDS.toHashSet()
    private val ACTION_SET: Set<String> = ASSIGNABLE_ACTIONS.toHashSet()

    fun isRemappable(commandName: String): Boolean = REMAPPABLE_SET.contains(commandName)

    /** 是否是可作为目标的真实动作（不含 [DEFAULT_ACTION]；含虚拟的 [NO_ACTION]）。 */
    fun isAction(commandName: String): Boolean = ACTION_SET.contains(commandName)

    /** 是否是可合法写入单条映射的目标值（含 [DEFAULT_ACTION]）。 */
    fun isAssignable(value: String): Boolean = value == DEFAULT_ACTION || isAction(value)

    /**
     * 解析持久化文本。任何非法行都静默跳过，坏偏好数据不能让读屏服务崩溃。
     * 重复源以后出现的为准。返回不可变 Map。
     */
    fun parse(raw: String?): Map<String, String> {
        if (raw == null || raw.isBlank()) {
            return emptyMap()
        }
        val result = LinkedHashMap<String, String>()
        for (entry in raw.split(ENTRY_SEPARATOR)) {
            val separatorIndex = entry.indexOf(KEY_VALUE_SEPARATOR)
            if (separatorIndex <= 0) {
                continue
            }
            val source = entry.substring(0, separatorIndex).trim()
            val target = entry.substring(separatorIndex + 1).trim()
            if (!isRemappable(source) || !isAction(target) || source == target) {
                continue
            }
            result[source] = target
        }
        return result.toMap()
    }

    /** 按源命令名稳定排序地序列化，保证同一张表永远得到同一段文本。 */
    fun serialize(map: Map<String, String>): String {
        val entries = map.entries
            .filter { (source, target) ->
                isRemappable(source) && isAction(target) && source != target
            }
            .sortedBy { it.key }
        return entries.joinToString(ENTRY_SEPARATOR.toString()) {
            "${it.key}$KEY_VALUE_SEPARATOR${it.value}"
        }
    }

    /**
     * 返回修改一条映射后的新表。[target] 为 [DEFAULT_ACTION] 或等于 [source] 时删除该条。
     * 非法入参原样返回，不抛异常（设置页不能因为一条坏数据崩掉）。
     */
    fun withMapping(
        map: Map<String, String>,
        source: String,
        target: String
    ): Map<String, String> {
        if (!isRemappable(source) || !isAssignable(target)) {
            return map
        }
        val next = LinkedHashMap(map)
        if (target == DEFAULT_ACTION || target == source) {
            next.remove(source)
        } else {
            next[source] = target
        }
        return next.toMap()
    }

    /** 单层解析：没有自定义时返回按键自身的默认动作。 */
    fun resolvedTarget(map: Map<String, String>, source: String): String = map[source] ?: source

    /**
     * 列出「多个按键被指派同一动作」的情况，供设置页如实提示。
     * 返回 目标动作 → 源按键列表，只包含 2 个及以上源的项。
     */
    fun duplicateTargets(map: Map<String, String>): Map<String, List<String>> {
        val grouped = LinkedHashMap<String, MutableList<String>>()
        for ((source, target) in map) {
            if (!isRemappable(source) || !isAction(target)) {
                continue
            }
            // 「无动作」不算冲突：多个键都空转是用户有意的选择，提示出来只会让人困惑。
            if (target == NO_ACTION) {
                continue
            }
            val list = grouped.getOrPut(target) { ArrayList() }
            list.add(source)
        }
        return grouped
            .filterValues { it.size >= 2 }
            .mapValues { (_, sources) -> sources.sorted() }
            .toMap()
    }
}
