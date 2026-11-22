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

package com.google.android.accessibility.braille.common.translate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * {@link BrailleLanguages} 结构拆分的源码文本契约。
 *
 * <p>预算账目：拆前 BrailleLanguages.kt 共 1135 行（非空 1120）；拆后门面文件非空行数必须 ≤ 600。
 * 清单基线：枚举条目 92 个、语言标签常量 58 个、带独立 EditBuffer 装配体的条目 34 个，
 * 与拆分前逐字对账一致；本测试锚定条目计数、关键语言 id 与关键显示名资源，防止清单回潮或漂移。
 */
@RunWith(JUnit4.class)
public class BrailleLanguagesSplitContractTest {

  private static final String TRANSLATE_DIR =
      "addon/braillecommon/src/main/java/com/google/android/accessibility/braille/common/translate/";

  private File projectFile(String path) {
    List<File> candidates =
        List.of(new File(path), new File("..", path), new File("../..", path));
    for (File candidate : candidates) {
      if (candidate.isFile()) {
        return candidate;
      }
    }
    throw new IllegalStateException("找不到 " + path + "（cwd=" + System.getProperty("user.dir") + ")");
  }

  private String readText(String relativePath) {
    try {
      return new String(java.nio.file.Files.readAllBytes(projectFile(relativePath).toPath()));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static int nonEmptyLines(String text) {
    int count = 0;
    for (String line : text.split("\r?\n")) {
      if (!line.trim().isEmpty()) {
        count++;
      }
    }
    return count;
  }

  private static List<String> entryNames(String text) {
    List<String> names = new ArrayList<>();
    Matcher matcher = Pattern.compile("(?m)^\\s{8}([A-Z0-9_]+)\\(").matcher(text);
    while (matcher.find()) {
      names.add(matcher.group(1));
    }
    return names;
  }

  @Test
  public void splitFiles_exist() {
    assertTrue(projectFile(TRANSLATE_DIR + "BrailleLanguages.kt").isFile());
    assertTrue(projectFile(TRANSLATE_DIR + "BrailleLanguagesLocales.kt").isFile());
    assertTrue(projectFile(TRANSLATE_DIR + "BrailleLanguageEditBuffers.kt").isFile());
  }

  @Test
  public void facade_isUnderLineBudget() {
    String facade = readText(TRANSLATE_DIR + "BrailleLanguages.kt");
    // 预算：门面文件非空 ≤ 600 行；拆前为 1120 非空行，条目装配体已外拆到派发文件。
    assertTrue(
        "门面文件非空行数超预算：" + nonEmptyLines(facade), nonEmptyLines(facade) <= 600);
  }

  @Test
  public void catalog_hasNinetyTwoCodesAndKeyIds() {
    String facade = readText(TRANSLATE_DIR + "BrailleLanguages.kt");
    List<String> names = entryNames(facade);
    assertEquals("枚举条目总数不能变", 92, names.size());

    // 关键语言 id 必须完整出现在归属文件（门面嵌套枚举）里。
    for (String id :
        new String[] {
          "UEB",
          "ARABIC",
          "ARABIC_COMP8",
          "CANTONESE",
          "CHINESE_CHINA_CURRENT_WITH_TONES",
          "CHINESE_CHINA_CURRENT_WITHOUT_TONES",
          "CHINESE_CHINA_TWO_CELLS",
          "CHINESE_CHINA_COMMON",
          "CHINESE_TAIWAN",
          "EN_US_EBAE",
          "EN_NABCC",
          "EN_COMP6",
          "KOREAN_2006",
          "KURDISH",
          "NORWEGIAN_8_6FALLBACK",
          "RUSSIAN_DETAILED",
          "SPANISH",
          "SWEDISH_COMP8_1996",
          "SWEDISH_COMP8_1989",
          "UKRAINIAN",
          "URDU",
          "VIETNAMESE",
          "WELSH",
          "STUB",
        }) {
      assertTrue("缺少关键语言 id：" + id, facade.contains(id + "("));
    }

    // 关键显示名资源字面量必须随条目一起保留。
    for (String stringId :
        new String[] {
          "R.string.code_user_facing_name_ueb1",
          "R.string.code_user_facing_name_arabic",
          "R.string.code_user_facing_name_chinese_china_current_with_tones",
          "R.string.code_user_facing_name_korean_2006",
        }) {
      assertTrue("缺少关键显示名资源：" + stringId, facade.contains(stringId));
    }
  }

  @Test
  public void localesFile_holdsFiftyEightTags() {
    String locales = readText(TRANSLATE_DIR + "BrailleLanguagesLocales.kt");
    Matcher matcher = Pattern.compile("@JvmField val LOCALE_").matcher(locales);
    int count = 0;
    while (matcher.find()) {
      count++;
    }
    assertEquals("语言标签常量总数不能变", 58, count);

    // 关键语言标签字面量逐字保留。
    for (String tag :
        new String[] {
          "Locale.forLanguageTag(\"ar\")",
          "Locale.forLanguageTag(\"en-IN\")",
          "Locale.forLanguageTag(\"yue\")",
          "Locale.forLanguageTag(\"sd-IN\")",
          "Locale.forLanguageTag(\"ne-IN\")",
        }) {
      assertTrue("缺少关键语言标签：" + tag, locales.contains(tag));
    }
  }

  @Test
  public void dispatcherFile_coversOverrideCodesAndDefaultFallback() {
    String dispatcher = readText(TRANSLATE_DIR + "BrailleLanguageEditBuffers.kt");

    // 原 34 个带独立装配体的条目，分支必须齐全。
    for (String id :
        new String[] {
          "ARABIC",
          "BULGARIAN",
          "CATALAN",
          "CHINESE_CHINA_CURRENT_WITH_TONES",
          "CROATIAN",
          "DANISH",
          "DANISH_8",
          "DUTCH_NL",
          "UEB",
          "EN_UK",
          "EN_US_EBAE",
          "EN_COMP6",
          "FRENCH",
          "GREEK",
          "GERMAN",
          "HUNGARIAN",
          "ITALIAN_COMP6",
          "KOREAN_2006",
          "LATVIAN",
          "LITHUANIAN",
          "NORWEGIAN",
          "POLISH",
          "PORTUGUESE",
          "RUSSIAN",
          "RUSSIAN_DETAILED",
          "SERBIAN",
          "SPANISH",
          "SWEDEN",
          "TURKISH",
          "UKRAINIAN",
          "URDU",
          "VIETNAMESE",
          "WELSH",
          "STUB",
        }) {
      assertTrue("派发分支缺少：" + id, dispatcher.contains("Code." + id + " ->"));
    }

    // 未覆盖条目回落到默认 UnContracted，与原枚举默认实现一致。
    assertTrue("缺少默认回落分支", dispatcher.contains("else -> EditBufferUnContracted("));
  }

  @Test
  public void overrideBodiesAndTagDefinitions_didNotFlowBackToFacade() {
    String facade = readText(TRANSLATE_DIR + "BrailleLanguages.kt");

    // 反向断言：条目装配体（override 方法体）不许回潮到门面文件，必须留在派发文件。
    assertFalse(
        "门面文件不得再出现枚举条目的 override 装配体",
        facade.contains("override fun createEditBuffer"));
    // 反向断言：语言标签字面量定义不许回潮到门面文件，必须留在 Locales 文件。
    assertFalse(
        "门面文件不得再出现 Locale.forLanguageTag 标签定义",
        facade.contains("Locale.forLanguageTag("));
  }
}
