/*
 * Copyright 2026 The BrailleBack Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.accessibility.brailleime.tutorial;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * {@link TutorialView} 拆分后的结构护栏。
 *
 * <p>2026-11-22：TutorialView.kt 从 1895 行（非空 1635）降到 505 行（非空 433）。引导步骤
 * 按流程搬进同包的四个状态文件，播报/触感反馈搬进 {@link TutorialFeedback} 扩展函数文件；
 * 主文件只保留装配、状态编排与对外 API。
 *
 * <p>这个测试防两件事：
 * <ol>
 *   <li>主文件不许再长回 700 非空行以上——那正是这次拆分的预算线；
 *   <li>搬走的步骤实现与反馈逻辑不许被抄回主文件：两份实现必然漂移，且没人知道哪份在跑。
 * </ol>
 *
 * <p>行数账目：拆前 1895 总行 / 1635 非空；拆后主文件 505 总行 / 433 非空，预算定为
 * 非空 ≤ 700（留余量，后续再加编排逻辑不至于立刻撞线）。各新文件另设小预算，防止单文件
 * 重新长成大文件。
 */
@RunWith(JUnit4.class)
public class TutorialViewSplitContractTest {

  private static final String DIR =
      "addon/brailleime/src/main/java/com/google/android/accessibility/brailleime/tutorial/";

  private File projectFile(String path) {
    File[] candidates = {new File(path), new File("..", path), new File("../..", path)};
    for (File candidate : candidates) {
      if (candidate.isFile()) {
        return candidate;
      }
    }
    throw new IllegalStateException(
        "找不到 " + path + "（cwd=" + System.getProperty("user.dir") + "）");
  }

  private String src(String name) {
    try {
      return new String(
          Files.readAllBytes(projectFile(DIR + name).toPath()), StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private int nonBlank(String source) {
    int count = 0;
    for (String line : source.split("\n")) {
      if (!line.trim().isEmpty()) {
        count++;
      }
    }
    return count;
  }

  @Test
  public void mainFileUnderSplitBudget() {
    int lines = nonBlank(src("TutorialView.kt"));
    // 拆前 1895 总行 / 1635 非空；拆后 433 非空。预算 700 非空，留一点余量。
    assertTrue("TutorialView.kt 非空行数 " + lines + " 已超出拆分预算", lines <= 700);
  }

  @Test
  public void newResponsibilityFilesExistAndHoldTheirStates() {
    String entry = src("TutorialEntryStates.kt");
    for (String signature :
        new String[] {
          "class Intro(",
          "class RotateOrientation(",
          "class RotateOrientationContinue(",
          "class HoldSixFingers(",
        }) {
      assertTrue(signature + " 必须留在 TutorialEntryStates.kt", entry.contains(signature));
    }

    String letter = src("TutorialLetterStates.kt");
    for (String signature :
        new String[] {"class TypeLetterA(", "class TypeLetterBCD("}) {
      assertTrue(signature + " 必须留在 TutorialLetterStates.kt", letter.contains(signature));
    }

    String swipe = src("TutorialSwipeStates.kt");
    for (String signature :
        new String[] {
          "class SwipeLeft(",
          "class SwipeRight(",
          "class SwipeUp(",
          "class SwipeDown(",
          "class SwipeDown2Fingers(",
          "class SwipeDown3Fingers(",
          "class SwipeUp3Fingers(",
        }) {
      assertTrue(signature + " 必须留在 TutorialSwipeStates.kt", swipe.contains(signature));
    }

    String terminal = src("TutorialTerminalStates.kt");
    for (String signature :
        new String[] {"class ContextMenuOpened(", "class TutorialFinished"}) {
      assertTrue(signature + " 必须留在 TutorialTerminalStates.kt", terminal.contains(signature));
    }

    String feedback = src("TutorialFeedback.kt");
    for (String signature :
        new String[] {
          "internal fun TutorialView.speakAnnouncement(",
          "internal fun TutorialView.speakUserEventAnnouncement(",
          "internal fun TutorialView.speakSwipeEvent(",
          "internal fun TutorialView.vibrateForSwipeGestures(",
          "internal fun TutorialView.speakBrailleCharacter(",
          "internal fun TutorialView.openContextMenu(",
          "internal fun TutorialView.getDotTargetsForBrailleCharacter(",
        }) {
      assertTrue(signature + " 必须留在 TutorialFeedback.kt", feedback.contains(signature));
    }
  }

  @Test
  public void movedImplementationDoesNotComeBackToMainFile() {
    String main = src("TutorialView.kt");
    // 步骤类不许以内部类形式回潮到主文件。
    for (String declaration :
        new String[] {
          "inner class Intro",
          "inner class RotateOrientation",
          "inner class HoldSixFingers",
          "inner class TypeLetterA",
          "inner class TypeLetterBCD",
          "inner class SwipeLeft",
          "inner class SwipeUp3Fingers",
          "inner class ContextMenuOpened",
        }) {
      assertFalse(declaration + " 已搬走，不许在主文件里出现第二份", main.contains(declaration));
    }
    // 反馈与播报逻辑不许在主文件里再写一遍。
    for (String method :
        new String[] {
          "private fun speakAnnouncement(",
          "private fun speakUserEventAnnouncement(",
          "private fun speakSwipeEvent(",
          "private fun vibrateForSwipeGestures(",
          "private fun speakBrailleCharacter(",
          "private fun openContextMenu(",
          "private fun getDotTargetsForBrailleCharacter(",
        }) {
      assertFalse(method + " 已搬走，不许在主文件里出现第二份", main.contains(method));
    }
    // 手势文案映射属于反馈文件，主文件只做编排。
    assertFalse(
        "perform_hide_keyboard_announcement 的映射必须留在 TutorialFeedback.kt",
        main.contains("perform_hide_keyboard_announcement"));
  }

  @Test
  public void splitFilesDoNotGrowIntoNewLargeFiles() {
    assertTrue(
        "TutorialEntryStates.kt 非空行数过大，该再按职责拆",
        nonBlank(src("TutorialEntryStates.kt")) < 450);
    assertTrue(
        "TutorialLetterStates.kt 非空行数过大，该再按职责拆",
        nonBlank(src("TutorialLetterStates.kt")) < 350);
    assertTrue(
        "TutorialSwipeStates.kt 非空行数过大，该再按职责拆",
        nonBlank(src("TutorialSwipeStates.kt")) < 600);
    assertTrue(
        "TutorialTerminalStates.kt 非空行数过大，该再按职责拆",
        nonBlank(src("TutorialTerminalStates.kt")) < 100);
    assertTrue(
        "TutorialFeedback.kt 非空行数过大，该再按职责拆",
        nonBlank(src("TutorialFeedback.kt")) < 250);
  }
}
