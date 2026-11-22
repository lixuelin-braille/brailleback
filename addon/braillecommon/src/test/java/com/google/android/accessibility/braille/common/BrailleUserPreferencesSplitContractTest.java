/*
 * Copyright 2026 The BrailleBack Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.braille.common;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * {@link BrailleUserPreferences} 按偏好域拆分后的结构契约。
 *
 * <p>拆分前 BrailleUserPreferences.kt 共 1340 行（非空 1226 行），是全应用偏好/设置的单一入口。
 * 拆分后门面只保留"对外统一入口 + 转发"，实现落到同包四个委托对象：
 *
 * <ul>
 *   <li>{@link BrailleUserPreferencesMigration}：SharedPreferences 文件入口与历史版本迁移。
 *   <li>{@link BrailleUserPreferencesTranslator}：输入/输出盲文码、首选语言、contracted 与翻译工厂。
 *   <li>{@link BrailleUserPreferencesKeyboard}：盲文键盘（BK）模式、布局校准、教程与计数。
 *   <li>{@link BrailleUserPreferencesDisplay}：点显器（BD）显示、提示、闪烁/定时/滚动、字幕与手势。
 * </ul>
 *
 * <p>本测试是源码文本契约：读 .kt 文本做静态断言，拦住三类回潮——实现塞回门面、键名/默认值漂移、
 * 归属文件搬错。测试 cwd 是模块目录（addon/braillecommon），路径同时给模块相对与仓库根相对兜底。
 */
@RunWith(JUnit4.class)
public class BrailleUserPreferencesSplitContractTest {

  private static final String PKG_DIR =
      "src/main/java/com/google/android/accessibility/braille/common/";
  private static final String REPO_DIR = "addon/braillecommon/" + PKG_DIR;

  private File projectFile(String repoRelativePath) {
    List<File> candidates = new ArrayList<>();
    candidates.add(new File(repoRelativePath));
    candidates.add(new File("..", repoRelativePath));
    candidates.add(new File("../..", repoRelativePath));
    candidates.add(new File(PKG_DIR + fileNameOf(repoRelativePath)));
    candidates.add(new File("..", ".." + File.separator + repoRelativePath));
    for (File candidate : candidates) {
      if (candidate.isFile()) {
        return candidate;
      }
    }
    throw new IllegalStateException(
        "找不到 " + repoRelativePath + "（cwd=" + System.getProperty("user.dir") + "）");
  }

  private static String fileNameOf(String path) {
    return path.substring(path.lastIndexOf('/') + 1);
  }

  private String text(String fileName) {
    File file = projectFile(REPO_DIR + fileName);
    try {
      return new String(java.nio.file.Files.readAllBytes(file.toPath()), "UTF-8");
    } catch (Exception e) {
      throw new IllegalStateException("读取失败 " + file, e);
    }
  }

  private static long nonBlankLines(String text) {
    long count = 0;
    for (String line : text.split("\r?\n")) {
      if (!line.trim().isEmpty()) {
        count++;
      }
    }
    return count;
  }

  @Test
  public void 四个域委托文件都存在且为同包顶层类() {
    assertTrue(text("BrailleUserPreferencesMigration.kt").contains("class BrailleUserPreferencesMigration"));
    assertTrue(text("BrailleUserPreferencesTranslator.kt").contains("class BrailleUserPreferencesTranslator"));
    assertTrue(text("BrailleUserPreferencesKeyboard.kt").contains("class BrailleUserPreferencesKeyboard"));
    assertTrue(text("BrailleUserPreferencesDisplay.kt").contains("class BrailleUserPreferencesDisplay"));
  }

  @Test
  public void 门面体积守住预算() {
    // 拆分前 1340 行（非空 1226）。拆后门面只留转发；上限 600 行非空，再堆逻辑先在这里断。
    long lines = nonBlankLines(text("BrailleUserPreferences.kt"));
    assertTrue("BrailleUserPreferences 非空行数 " + lines + " 超出门面预算 600", lines <= 600);
  }

  @Test
  public void 门面转发到四个委托对象() {
    String facade = text("BrailleUserPreferences.kt");
    assertTrue(facade.contains("BrailleUserPreferencesMigration."));
    assertTrue(facade.contains("BrailleUserPreferencesTranslator."));
    assertTrue(facade.contains("BrailleUserPreferencesKeyboard."));
    assertTrue(facade.contains("BrailleUserPreferencesDisplay."));
  }

  /** 键（R.string 符号）与默认值必须留在归属域文件里，逐条对账，防止漂移。 */
  private static final String[][] TRANSLATOR_PREFS = {
    // R.string 键符号, 默认值字面量（无字面量默认值时给 null）
    {"R.string.pref_brailleime_translator_code", null},
    {"R.string.pref_brailleime_translator_codes_preferred", null},
    {"R.string.pref_braille_contracted_mode", "false"},
    {"R.string.pref_bd_output_code", null},
    {"\"pref_brailleime_recommended_codes_seeded\"", "false"},
  };

  private static final String[][] KEYBOARD_PREFS = {
    {"R.string.pref_show_switch_input_code_gesture_tip", "true"},
    {"R.string.pref_brailleime_accumulate_mode", "true"},
    {"R.string.pref_brailleime_reverse_dots_mode", "false"},
    {"R.string.pref_brailleime_layout_mode", null},
    {"R.string.pref_brailleime_auto_launch_tutorial", "true"},
    {"R.string.pref_brailleime_exit_keyboard_count", "0"},
    {"R.string.pref_brailleime_show_option_dialog_count", "0"},
    {"R.string.pref_brailleime_change_gesture_intro_again", "false"},
  };

  private static final String[][] DISPLAY_PREFS = {
    {"R.string.pref_braille_word_wrap_key", "true"},
    {"R.string.pref_braille_overlay_key", "false"},
    {"R.string.pref_search_tutorial_never_show", "false"},
    {"R.string.pref_bd_show_switch_input_code_gesture_tip", "true"},
    {"R.string.pref_bd_show_switch_output_code_gesture_tip", "true"},
    {"R.string.pref_bd_show_navigation_command_unavailable_tip", "true"},
    {"R.string.pref_bd_show_usb_connect_dialog", "true"},
    {"R.string.pref_bd_reverse_panning_buttons", "false"},
    {"R.string.pref_bd_timed_message_duration_fraction_key", "\"1\""},
    {"R.string.pref_bd_blinking_interval_key", "\"750\""},
    {"R.string.pref_bd_switch_contracted_count_key", "0"},
    {"R.string.pref_bd_auto_scroll_duration_key", "3000"},
    {"R.string.pref_bd_auto_adjust_duration_enable_key", "true"},
    {"R.string.pref_bd_popup_announcement_key", "false"},
    {"R.string.pref_bd_popup_announcement_history_key", "false"},
    {"R.string.pref_bd_caption_key", "false"},
    {"R.string.pref_bd_show_caption_tutorial_dialog_again", "true"},
    {"R.string.pref_bd_show_caption_tutorial_dialog_timestamp", "0"},
    {"R.string.pref_bd_gesture_action_key", "\"\""},
    {"R.string.pref_bd_show_command_change_dialog", "false"},
  };

  @Test
  public void 翻译域键名与默认值零漂移() {
    assertOwnerPrefs("BrailleUserPreferencesTranslator.kt", TRANSLATOR_PREFS);
  }

  @Test
  public void 键盘域键名与默认值零漂移() {
    assertOwnerPrefs("BrailleUserPreferencesKeyboard.kt", KEYBOARD_PREFS);
  }

  @Test
  public void 点显器域键名与默认值零漂移() {
    assertOwnerPrefs("BrailleUserPreferencesDisplay.kt", DISPLAY_PREFS);
  }

  private void assertOwnerPrefs(String fileName, String[][] prefs) {
    String owner = text(fileName);
    for (String[] pref : prefs) {
      assertTrue(fileName + " 缺少键 " + pref[0], owner.contains(pref[0]));
      if (pref[1] != null) {
        assertTrue(fileName + " 键 " + pref[0] + " 默认值 " + pref[1] + " 漂移",
            owner.contains(pref[1]));
      }
    }
  }

  @Test
  public void 迁移域字面量与版本号零漂移() {
    String migration = text("BrailleUserPreferencesMigration.kt");
    // SharedPreferences 文件名与版本键名逐字保留。
    assertTrue(migration.contains("\"braille_keyboard\""));
    assertTrue(migration.contains("\"brailleime_shared_prefs_version\""));
    assertTrue(migration.contains("\"pref_brailleime_contracted_mode\""));
    // 历史遗留码名迁移常量逐字保留。
    assertTrue(migration.contains("\"UEB_1\""));
    assertTrue(migration.contains("\"UEB_2\""));
    assertTrue(migration.contains("\"SINHALA_IN\""));
    // 版本号 1..5 不变。
    for (String version : new String[] {"= 1", "= 2", "= 3", "= 4", "= 5"}) {
      assertTrue("迁移版本号 " + version + " 丢失", migration.contains(version));
    }
  }

  @Test
  public void 门面不许回潮实现细节() {
    String facade = text("BrailleUserPreferences.kt");
    // 门面不得再直接读写 SharedPreferences：读写实现必须留在域文件里。
    assertFalse(facade.contains(".getBoolean("));
    assertFalse(facade.contains(".getInt("));
    assertFalse(facade.contains(".getLong("));
    assertFalse(facade.contains(".getString("));
    assertFalse(facade.contains(".getStringSet("));
    assertFalse(facade.contains(".putBoolean("));
    assertFalse(facade.contains(".putInt("));
    assertFalse(facade.contains(".putLong("));
    assertFalse(facade.contains(".putString("));
    assertFalse(facade.contains(".edit()"));
    assertFalse(facade.contains(".remove("));
    // 迁移内部状态与序列化实现不许塞回门面。
    assertFalse(facade.contains("SHARED_PREFS_VERSION"));
    assertFalse(facade.contains("DEPRECATED_UEB_1"));
    assertFalse(facade.contains("currentVersion"));
    assertFalse(facade.contains("getOldKey"));
    assertFalse(facade.contains("Gson()"));
    assertFalse(facade.contains("TypeToken"));
  }

  @Test
  public void 公开常量仍从门面对外暴露() {
    String facade = text("BrailleUserPreferences.kt");
    assertTrue(facade.contains("BRAILLE_SHARED_PREFS_FILENAME"));
    assertTrue(facade.contains("AUTO_SCROLL_DURATION_INTERVAL_MS"));
    assertTrue(facade.contains("MAXIMUM_AUTO_SCROLL_DURATION_MS"));
    assertTrue(facade.contains("MINIMUM_AUTO_SCROLL_DURATION_MS"));
  }
}
