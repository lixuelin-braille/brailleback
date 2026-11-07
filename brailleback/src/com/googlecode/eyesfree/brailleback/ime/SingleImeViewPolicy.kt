/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback.ime

/**
 * 单注册后「点显器硬件形态 / 触屏点字形态」的选择策略。
 *
 * 合并前项目注册了两个 InputMethodService；合并后只剩 BrailleIME，
 * 它继承移植键盘 BrailleIme 复用触屏实现。两种形态不能同时激活：
 * 触屏形态会挂载 overlay 键盘并弹首启教程，对依赖读屏的点显器用户是干扰。
 *
 * 刻意做成纯 Kotlin、零 Android 依赖：这个分支判断最容易被改错
 * （auto 取反就会让点显器用户一连接设备反而弹出触屏键盘），
 * 必须能在 JVM 单测里锁死并做变异测试。
 */
object SingleImeViewPolicy {

    /** 与 BrailleUserPreferences.BRAILLE_SHARED_PREFS_FILENAME 是同一个文件。 */
    const val BRAILLE_KEYBOARD_PREFS = "braille_keyboard"

    /** 单注册后两种输入形态的选择：auto / on（触屏点字）/ off（点显器硬件）。 */
    const val PREF_SINGLE_IME_VIEW_MODE = "pref_single_ime_view_mode"

    const val MODE_AUTO = "auto"
    const val MODE_ON = "on"
    const val MODE_OFF = "off"

    /**
     * @param mode 用户偏好；null 或未知值一律按 auto 处理。
     * @param displayConnected 当前是否已连接点显器。
     * @return true 表示本次 IME 会话加载触屏点字键盘的 InputView。
     */
    fun useTouchKeyboard(mode: String?, displayConnected: Boolean): Boolean = when (mode) {
        MODE_ON -> true
        MODE_OFF -> false
        // 接了点显器就走硬件形态；没接才把屏幕让给触屏点字键盘。
        else -> !displayConnected
    }
}
