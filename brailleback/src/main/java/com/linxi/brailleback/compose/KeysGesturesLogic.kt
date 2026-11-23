/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback.compose

/**
 * 「按键与手势」子屏的纯逻辑：不依赖 Android Context，便于 JVM 单测。
 *
 * 六个 key 与 res/xml/settings.xml 中同名子屏一一对应；其中前两个是
 * 纯入口（不写 SharedPreferences），后四个才是持久化项。
 * 键名常量由 KeysGesturesContractTest 与 donottranslate.xml 交叉校验，
 * 防止迁移后读写到另一份数据。
 */
object KeysGesturesLogic {

    /** 只读的按键说明入口，仅连接设备后可用。 */
    const val KEY_KEY_BINDINGS = "key_bindings"

    /** 用户可编辑的按键映射入口。 */
    const val KEY_CUSTOM_KEY_MAP = "custom_key_map"

    const val KEY_VOLUME_GESTURES_ENABLED = "volume_gestures_enabled"
    const val KEY_LONG_PRESS_UP = "volume_long_press_up"
    const val KEY_LONG_PRESS_DOWN = "volume_long_press_down"
    const val KEY_DOUBLE_PRESS = "volume_double_press"

    /** 与 Display.STATE_CONNECTED 同值；刻意不引用 Android 类，保持零依赖。 */
    const val STATE_CONNECTED = 1

    /** 持久化项，顺序即界面上的顺序（不含两个纯入口）。 */
    val PERSISTED_KEYS = listOf(
        KEY_VOLUME_GESTURES_ENABLED,
        KEY_LONG_PRESS_UP,
        KEY_LONG_PRESS_DOWN,
        KEY_DOUBLE_PRESS,
    )

    /** 三个动作槽位的出厂默认动作，必须与 settings.xml 的 app:defaultValue 一致。 */
    fun defaultValueFor(key: String, none: String, pause: String, grade: String): String =
        when (key) {
            KEY_LONG_PRESS_UP -> pause
            KEY_DOUBLE_PRESS -> grade
            KEY_LONG_PRESS_DOWN -> none
            else -> none
        }

    /** 首次读取或历史脏值时回落到出厂默认，避免列表行朗读空白。 */
    fun initialValue(stored: String?, default: String): String =
        if (stored.isNullOrEmpty()) default else stored

    /** 当前动作值对应的可读标签；值不在表中时退回第一项，不朗读空白。 */
    fun labelForValue(values: List<String>, entries: List<String>, value: String?): String {
        val index = values.indexOf(value)
        return entries.getOrElse(index) { entries.firstOrNull().orEmpty() }
    }

    /** 键盘帮助依赖真实设备属性，只有已连接时才允许进入。 */
    fun isHelpAvailable(state: Int): Boolean = state == STATE_CONNECTED
}
