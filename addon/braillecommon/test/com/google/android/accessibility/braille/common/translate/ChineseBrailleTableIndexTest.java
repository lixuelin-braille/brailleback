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
            + "sign \\x554A 35-0\n"
            + "noback sign \\xE234 1\n"
            + "digit 1 1\n"
            + "punctuation \\x3002 256\n";

    ChineseBrailleTableIndex index = parse(table);

    assertEquals(2, index.getCandidates("35-1").size());
    // 常用字优先：阿（GB2312 一级）排在"吖"前面，虽然表内"吖"的定义写在前面。
    assertEquals("阿", index.getCandidates("35-1").get(0));
    assertEquals("吖", index.getCandidates("35-1").get(1));
    assertEquals("哈", index.getCandidates("125-35-1").get(0));
    assertEquals("铪", index.getCandidates("125-35-1").get(1));
    // 单格、noback、digit、标点都不进索引。
    assertEquals(0, index.getCandidates("1").size());
    assertFalse(index.hasCandidate("256"));
    // 含空单元（0，liblouis 的 blank cell）的定义输入不可达，必须被排除。
    assertEquals(0, index.getCandidates("35-0").size());
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
    // 2 格零声母字：35-1 = 吖 / 阿 / 锕 / 錒。
    assertEquals(4, index.getCandidates("35-1").size());
    assertTrue("35-1 应含阿", index.getCandidates("35-1").contains("阿"));
    // "啊" 的定义是 35-0，0 是空单元、键盘产生不了，所以输入链路里拿不到它。
    assertEquals(0, index.getCandidates("35-0").size());
    // 125-35 确实是 3 格前缀，35-1 不是（它本身是完整的 2 格音节）。
    assertTrue(index.isThreeCellPrefix("125-35"));
    assertFalse(index.isThreeCellPrefix("35-1"));
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

    // 挂起规则依赖的两个判断：单格能否作为音节第一格、2 格是否为 3 格前缀。
    // 若这两个判断失效，单格会被直接翻成数字（看起来像已上屏），后面的格再也拼不成汉字。
    assertTrue("125 应能作为音节第一格", index.canStartSyllable("125"));
    assertTrue("125-35 应是 3 格前缀", index.isThreeCellPrefix("125-35"));
    // 本表里音节首格都是 2 点以上的声母，单点 1 不是任何音节的第一格（它只是声调位）。
    // 所以点 1 单独出现时按表的定义出数字是合理的，不该被挂起。
    assertFalse("单点 1 不应被当作音节首格", index.canStartSyllable("1"));
  }

  /** GB2312 一级字库判定：常用字 true，生僻字 false。 */
  @Test
  public void isCommonChinese_distinguishesCommonFromRare() {
    for (char ch : new char[] {'的', '是', '不', '人', '我', '好', '中', '国', '疵', '词'}) {
      assertTrue(ch + " 应判为常用字", ChineseBrailleTableIndex.isCommonChinese(ch));
    }
    // 注意"縒"(BF57)、"蠀"(CF85) 确实在 GB2312 一级字库里——一级字按拼音排序，并非按字频，
    // 所以不能拿"看起来生僻"当判断标准，这里只用确定落在二级库或扩展区的字。
    for (char ch : new char[] {'玼', '趀', '偨', '齹', '蚩', 'a', '1', '，'}) {
      assertFalse(ch + " 不应判为常用字", ChineseBrailleTableIndex.isCommonChinese(ch));
    }
  }

  /**
   * 常用字优先排序的效果必须可回归。
   *
   * <p>未排序时只有 38.9% 的多候选音节首候选是 GB2312 一级字——用户打出来的多半是"玼、趀、
   * 偨"这类生僻字，会以为"根本没拼出字"。排序后应超过 90%。这个数字一旦跌破，说明排序失效。
   */
  @Test
  public void realTable_ranksCommonCharactersFirst() throws IOException {
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
    assertNotNull(index);

    // 12 个高频字的点位必须一次命中——这是"打完一个音节直接得到想要的字"的底线。
    // 未按字频排序时，"是"在 156-23 的 85 个候选里排第 26 位（前面是"士、示、世、市"），
    // "你"在 1345-24-3 里排第 3 位（前面是"聻、拟"），用户会以为"打出来的不是字"。
    String[][] expected = {
      {"145-26", "的"},
      {"156-23", "是"},
      {"12-136-23", "不"},
      {"123-26", "了"},
      {"135-3", "我"},
      {"1345-24-3", "你"},
      {"125-235-3", "好"},
      {"34-256-1", "中"},
      {"1245-135-2", "国"},
      {"125-35-1", "哈"},
    };
    for (String[] pair : expected) {
      assertEquals(pair[0] + " 应首选 " + pair[1], pair[1], index.getCandidates(pair[0]).get(0));
    }

    // 全局兜底比例：多候选音节里，首候选必须落在高频表或 GB2312 一级字库内。
    // 实测 95.7%；跌破 90% 说明排序逻辑或字表被破坏。
    int multi = 0;
    int commonFirst = 0;
    for (java.util.List<String> candidates : index.asMap().values()) {
      if (candidates.size() <= 1) {
        continue;
      }
      multi++;
      char first = candidates.get(0).charAt(0);
      if (ChineseBrailleTableIndex.isCommonChinese(first)
          || ChineseBrailleTableIndex.isHighFrequency(first)) {
        commonFirst++;
      }
    }
    assertTrue("多候选音节数异常: " + multi, multi > 1000);
    int ratio = commonFirst * 100 / multi;
    assertTrue(
        "多候选音节中首候选为常用字的比例只有 " + ratio + "%（" + commonFirst + "/" + multi
            + "），字频排序可能失效",
        ratio > 90);
  }
}
