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

package com.google.android.accessibility.braille.common.translate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** 验证中文音节索引的解析与真实表的音节覆盖。 */
@RunWith(JUnit4.class)
public class ChineseBrailleTableIndexTest {

  private static ChineseBrailleTableIndex parse(String text) throws IOException {
    return ChineseBrailleTableIndex.parse(new BufferedReader(new StringReader(text)));
  }

  @Test
  public void parse_extractsTwoAndThreeCellDefinitions() throws IOException {
    String table =
        "# comment line\n"
            + "sign \\x5416 35-1\n"
            + "sign \\x963F 35-1\n"
            + "sign \\x54C8 125-35-1\n"
            + "sign \\x94EA 125-35-1\n"
            + "noback sign \\xE234 1\n"
            + "digit 1 1\n"
            + "punctuation \\x3002 256\n";

    ChineseBrailleTableIndex index = parse(table);

    assertEquals(2, index.getCandidates("35-1").size());
    assertEquals("吖", index.getCandidates("35-1").get(0));
    assertEquals("阿", index.getCandidates("35-1").get(1));
    assertEquals("哈", index.getCandidates("125-35-1").get(0));
    assertEquals("铪", index.getCandidates("125-35-1").get(1));
    // 单格、noback、digit、标点都不进索引。
    assertEquals(0, index.getCandidates("1").size());
    assertFalse(index.hasCandidate("256"));
    // 3 格 key 的前两格应登记为"可等待第 3 格"的前缀。
    assertTrue(index.isThreeCellPrefix("125-35"));
    assertFalse(index.isThreeCellPrefix("35-1"));
  }

  /**
   * 直接解析 APK 里那份真实表，确认"单格无汉字、125-35-1 是哈、35-1 是双格字"这三条
   * 支撑当前切分策略的事实没有变。表换了版本这条测试会先失败，提醒重新核对切分假设。
   */
  @Test
  public void realTable_hasExpectedSyllables() throws IOException {
    File zipFile =
        new File(
            System.getProperty("user.dir"),
            "../brailletranslate/res/raw/translationtables.zip");
    assertTrue("找不到翻译表: " + zipFile.getAbsolutePath(), zipFile.isFile());

    ChineseBrailleTableIndex index = null;
    try (InputStream raw = new java.io.FileInputStream(zipFile);
        ZipInputStream zip = new ZipInputStream(raw)) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        if (!"liblouis/tables/zhcn-g1.ctb".equals(entry.getName())) {
          continue;
        }
        index =
            ChineseBrailleTableIndex.parse(
                new BufferedReader(new InputStreamReader(zip, StandardCharsets.UTF_8)));
        break;
      }
    }

    assertNotNull("zhcn-g1.ctb 未在压缩包内", index);
    // 3 格带声调字：125-35-1 = 哈 / 铪 / 鉿。
    assertTrue("125-35-1 应含哈", index.getCandidates("125-35-1").contains("哈"));
    // 2 格零声母字：35-1 = 吖 / 阿 / 锕 / 錒（注意"啊"是 35-0，不在这里）。
    assertEquals(4, index.getCandidates("35-1").size());
    assertTrue("35-1 应含阿", index.getCandidates("35-1").contains("阿"));
    assertEquals("啊", index.getCandidates("35-0").get(0));
    // 125-35 确实是 3 格前缀，35-0 不是。
    assertTrue(index.isThreeCellPrefix("125-35"));
    assertFalse(index.isThreeCellPrefix("35-0"));
    // 单格不产生汉字——这是"点一格只会出数字"的根因。
    for (String single : new String[] {"1", "12", "14", "145", "15", "124", "1245", "125", "24",
        "245", "2", "3", "4", "5", "6", "13", "16", "23", "26", "34", "35", "36", "45", "46",
        "56", "123", "234", "345", "456"}) {
      assertEquals("单格 " + single + " 不应有汉字候选", 0, index.getCandidates(single).size());
    }
    // key 数是"音节数"不是"字数"：同音字共享同一个点位串。
    // 20913 条 2/3 格定义落到约 1300 个音节，平均每个音节十几个同音字——这就是必须做候选选择的量化依据。
    assertTrue("音节数异常: " + index.size(), index.size() > 1000 && index.size() < 3000);
    int totalCharacters = 0;
    for (java.util.List<String> chars : index.asMap().values()) {
      totalCharacters += chars.size();
    }
    assertTrue("候选总数异常: " + totalCharacters, totalCharacters > 20000);
  }
}
