/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback.compose

/**
 * 「声音与行为」子屏的纯逻辑：不依赖 Android Context，便于 JVM 单测。
 *
 * 六个 key 与 res/xml/settings.xml 中同名子屏一一对应。注意两个特殊点：
 * 1) pref_sound_enabled / pref_sound_volume 实际由 SoundSchemeManager 读写，
 *    这里的常量用于契约测试交叉校验，避免 Compose 页漂到另一套键；
 * 2) motion_mode 没有 XML 默认值，服务会按连接方式（USB 开 / 蓝牙关）动态写入；
 *    用户手动拨过之后必须同时置 motion_mode_explicit，否则下次连接会被覆盖。
 *
 * 键名常量由 SoundBehaviorContractTest 与 donottranslate.xml / strings.xml
 * 交叉校验，防止迁移后读写到另一份数据。
 */
object SoundBehaviorLogic {

    /** 音效总开关；SoundSchemeManager.isEnabled/setEnabled 读写同一个键。 */
    const val KEY_SOUND_ENABLED = "pref_sound_enabled"

    /** 音量；SoundSchemeManager.getVolume/setVolume 以 Int 类型读写同一个键。 */
    const val KEY_SOUND_VOLUME = "pref_sound_volume"

    const val KEY_PAUSE_BEHAVIOR = "pause_behavior"
    const val KEY_IDLE_CLEAR = "idle_clear_timeout"
    const val KEY_MOTION_MODE = "motion_mode"

    /** 手动选择过运动模式后的显式标记，服务据此不再套用连接方式默认值。 */
    const val KEY_MOTION_MODE_EXPLICIT = "motion_mode_explicit"

    const val KEY_QUIET_NAVIGATION = "quiet_navigation"

    /** 持久化项，顺序即界面上的顺序。 */
    val PERSISTED_KEYS = listOf(
        KEY_SOUND_ENABLED,
        KEY_SOUND_VOLUME,
        KEY_PAUSE_BEHAVIOR,
        KEY_IDLE_CLEAR,
        KEY_MOTION_MODE,
        KEY_QUIET_NAVIGATION,
    )

    /** 与 SoundSchemeManager 的 setVolume 边界、旧 SeekBar 的 max 完全一致。 */
    const val VOLUME_MIN = 0
    const val VOLUME_MAX = 100
    const val VOLUME_DEFAULT = 50

    /** 旧 CheckBoxPreference 未声明 defaultValue，首次读取为 false。 */
    const val MOTION_MODE_DEFAULT = false

    fun coerceVolume(value: Int): Int = value.coerceIn(VOLUME_MIN, VOLUME_MAX)

    /** 对话框里「减 1 / 加 1」按钮用的纯点击步进，不依赖滑杆手势。 */
    fun stepVolume(value: Int, delta: Int): Int = coerceVolume(value + delta)

    /** 首次读取或历史脏值时回落到出厂默认，避免列表行朗读空白。 */
    fun initialValue(stored: String?, default: String): String =
        if (stored.isNullOrEmpty()) default else stored

    /** 当前值对应的可读标签；值不在表中时退回第一项，不朗读空白。 */
    fun labelForValue(values: List<String>, entries: List<String>, value: String?): String {
        val index = values.indexOf(value)
        return entries.getOrElse(index) { entries.firstOrNull().orEmpty() }
    }

    /** 用资源模板（音效音量：%1$d）生成摘要，写入前先夹到合法区间。 */
    fun formatVolume(template: String, volume: Int): String =
        template.replace("%1\$d", coerceVolume(volume).toString())

    /**
     * 手动拨动运动模式时要写入的键值对。
     *
     * 显式标记必须和值一起写：BrailleBackService.applyMotionModeDefault 只在
     * motion_mode_explicit 为 false 时才按 USB / 蓝牙覆盖该值。少写一项，
     * 用户的选择会在下次连接设备时悄悄丢失。
     */
    fun motionModeWrite(value: Boolean): Map<String, Boolean> = mapOf(
        KEY_MOTION_MODE to value,
        KEY_MOTION_MODE_EXPLICIT to true,
    )
}
