/*
 * Copyright (C) 2026 The BrailleBack Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package com.google.android.accessibility.brailleime

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BrailleIme.kt 结构拆分的源码文本契约。
 *
 * 拆分前 BrailleIme.kt 共 1807 行（非空 1652 行），本次把按键/手势/点位上屏、点显器与读屏
 * 桥接、对话框与教程回调、设置联动、系统监听器、输入连接与上屏发布等职责拆到同包的独立文件，
 * 主类只保留输入法生命周期与装配编排。
 *
 * 主类非空行数预算：700 行（拆后实测 663 行；留 37 行缓冲，不放宽上限）。
 *
 * 注意：这是源码文本契约，不替代行为测试；运行时行为仍由 brailleime/braillecommon/
 * brailleback/service 既有单测覆盖。
 */
class BrailleImeSplitContractTest {

  private val mainSourceDir =
    "addon/brailleime/src/main/java/com/google/android/accessibility/brailleime/"

  private fun projectFile(path: String): File {
    val candidates = listOf(File(path), File("..", path), File("../..", path))
    return candidates.firstOrNull { it.isFile }
      ?: error("找不到 $path（cwd=${System.getProperty("user.dir")}）")
  }

  private fun text(path: String) = projectFile(path).readText()

  private fun nonEmptyLines(path: String): Int =
    projectFile(path).readLines().count { it.trim().isNotEmpty() }

  private val splitFiles = listOf(
    "BrailleImeTalkBackBridge.kt",
    "BrailleImeDisplayBridge.kt",
    "BrailleImeCalibrationAnnouncer.kt",
    "BrailleImeInputPlaneCallback.kt",
    "BrailleImeLayoutCallbacks.kt",
    "BrailleImeDialogCoordinator.kt",
    "BrailleImeTutorialCallback.kt",
    "BrailleImeViewCallbacks.kt",
    "BrailleImeSettingsObserver.kt",
    "BrailleImeSystemListeners.kt",
    "BrailleImeInputConnection.kt",
  )

  @Test
  fun splitFilesExist() {
    for (fileName in splitFiles) {
      assertTrue("拆分文件缺失：$fileName", projectFile(mainSourceDir + fileName).isFile)
    }
  }

  @Test
  fun mainFileNonEmptyLinesWithinBudget() {
    // 预算 700 非空行；拆后实测 663，缓冲 37 行。若继续上移职责导致主类变大，应先拆再合入。
    val nonEmpty = nonEmptyLines(mainSourceDir + "BrailleIme.kt")
    assertTrue("主类非空行数 $nonEmpty 不得超过 700", nonEmpty <= 700)
  }

  @Test
  fun responsibilitiesResideInSplitFiles() {
    assertTrue(
      "TalkBack 桥接应在 BrailleImeTalkBackBridge.kt",
      text(mainSourceDir + "BrailleImeTalkBackBridge.kt")
        .contains(": BrailleImeForTalkBack"))
    assertTrue(
      "点显器桥接应在 BrailleImeDisplayBridge.kt",
      text(mainSourceDir + "BrailleImeDisplayBridge.kt")
        .contains(": BrailleImeForBrailleDisplay"))
    assertTrue(
      "触屏输入平面回调应在 BrailleImeInputPlaneCallback.kt",
      text(mainSourceDir + "BrailleImeInputPlaneCallback.kt")
        .contains(": BrailleInputView.Callback"))
    assertTrue(
      "教程回调应在 BrailleImeTutorialCallback.kt",
      text(mainSourceDir + "BrailleImeTutorialCallback.kt")
        .contains(": TutorialCallback"))
    assertTrue(
      "设置监听器应在 BrailleImeSettingsObserver.kt",
      text(mainSourceDir + "BrailleImeSettingsObserver.kt")
        .contains("OnSharedPreferenceChangeListener"))
    assertTrue(
      "系统监听器应在 BrailleImeSystemListeners.kt",
      text(mainSourceDir + "BrailleImeSystemListeners.kt")
        .contains("Thread.UncaughtExceptionHandler") &&
        text(mainSourceDir + "BrailleImeSystemListeners.kt")
          .contains("ContentObserver") &&
        text(mainSourceDir + "BrailleImeSystemListeners.kt")
          .contains("PhoneStateListener"))
    assertTrue(
      "菜单对话框回调应在 BrailleImeDialogCoordinator.kt",
      text(mainSourceDir + "BrailleImeDialogCoordinator.kt")
        .contains("ContextMenuDialog.Callback"))
    assertTrue(
      "输入连接与上屏发布应在 BrailleImeInputConnection.kt",
      text(mainSourceDir + "BrailleImeInputConnection.kt")
        .contains("ResultForDisplay.builder") &&
        text(mainSourceDir + "BrailleImeInputConnection.kt").contains("ImeConnection("))
  }

  @Test
  fun implementationDidNotMoveBackIntoMainFile() {
    val main = text(mainSourceDir + "BrailleIme.kt")

    // 各回调实现不得以匿名对象形式回潮到主类。
    assertFalse("TalkBack 桥接匿名对象不得回潮", main.contains("object : BrailleImeForTalkBack"))
    assertFalse("点显器桥接匿名对象不得回潮", main.contains("object : BrailleImeForBrailleDisplay"))
    assertFalse("输入平面回调匿名对象不得回潮", main.contains("object : BrailleInputView.Callback"))
    assertFalse("菜单对话框回调匿名对象不得回潮", main.contains("object : ContextMenuDialog.Callback"))
    assertFalse("教程回调匿名对象不得回潮", main.contains("object : TutorialCallback"))
    assertFalse(
      "设置监听匿名对象不得回潮",
      main.contains("object : OnSharedPreferenceChangeListener"))
    assertFalse("主类不得残留匿名回调对象", main.contains("object :"))

    // 已迁出的常量与实现片段不得回潮。
    assertFalse("VALID_GRANULARITIES 不得回潮", main.contains("VALID_GRANULARITIES"))
    assertFalse("BARD_PACKAGE_NAME 不得回潮", main.contains("BARD_PACKAGE_NAME"))
    assertFalse("校准重复播报实现不得回潮", main.contains("speakAnnouncementRepeatedly"))
    assertFalse("校准重复播报回调不得回潮", main.contains("getRepeatAnnouncementRunnable"))
    assertFalse("点显器上屏拼装实现不得回潮", main.contains("ResultForDisplay.builder"))
    assertFalse("ImeConnection 构造实现不得回潮", main.contains("shouldAnnounceCharacter"))
    assertFalse("编辑器动作实现不得回潮", main.contains("IME_MASK_ACTION"))
    assertFalse("切换输入法实现不得回潮", main.contains("switchToNextInputMethod(false)"))
  }

  @Test
  fun retainedApiSurfaceIsIntact() {
    val main = text(mainSourceDir + "BrailleIme.kt")
    assertTrue("主类仍是 open class BrailleIme : InputMethodService",
      main.contains("open class BrailleIme : InputMethodService()"))
    assertTrue("OrientationSensitive 嵌套接口保留",
      main.contains("interface OrientationSensitive"))
    assertTrue("companion initialize 静态入口保留",
      main.contains("@JvmStatic") && main.contains("fun initialize("))
    assertTrue("测试钩子保留",
      main.contains("testing_getContextMenuDialogCallback") &&
        main.contains("testing_getInputPlaneCallback") &&
        main.contains("testing_getActorCallback"))
  }
}
