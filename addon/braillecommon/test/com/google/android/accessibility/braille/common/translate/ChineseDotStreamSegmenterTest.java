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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * 验证"不靠时间、按音节整串识别"的切分器。
 *
 * <p>这些用例守的是用户报过的 bug：打慢一点字就拼不成。根因是过去用停顿判格边界，猜早猜晚都错。
 * 现在判据换成"这串点是不是一个不能再长的音节"，所以关键断言就是两条：慢按不能提前切，
 * 快按不能粘连。
 */
@RunWith(JUnit4.class)
public class ChineseDotStreamSegmenterTest {

  private static ChineseDotStreamSegmenter segmenter;

  @BeforeClass
  public static void loadRealTable() throws IOException {
    File zipFile =
        new File(System.getProperty("user.dir"), "../brailletranslate/res/raw/translationtables.zip");
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
    segmenter = index.getDotStreamSegmenter();
  }

  @Test
  public void tableHasSyllables() {
    // 表内 2/3 格音节约 1300 个；数量掉到几百说明解析出了问题，切分全部会失效。
    assertTrue("音节数异常: " + segmenter.syllableCount(), segmenter.syllableCount() > 1000);
  }

  /**
   * 打"读"（145-136-2）时，第 4 个点按下后<b>不能</b>切成"搭"。
   *
   * <p>这是最关键的一条：{@code 1451} 本身能当"搭"（d + 一声），只看它能成字就切，会把
   * 用户正在打的"读"打断。旧的时间窗方案正是这样坏的——用户手一慢，145 被结算，后面 1362
   * 全成了废格。
   */
  @Test
  public void du_notCutIntoDaAtFourthDot() {
    assertNull("1451 不能提前切，它还是 读(1451362) 的开头", segmenter.decide("1451", false));
    assertNull(segmenter.decide("14513", false));
    assertNull(segmenter.decide("145136", false));
  }

  @Test
  public void du_isDecidedWhenUserConfirms() {
    // 二声（单点 2）永远是四声（23）的前缀，所以按完最后一个点还不会自动切——
    // 用户可能还要按 3 打"杜"(145-136-23)。这时按空格/右滑表示"打完了"，强制切。
    assertNull(
        "1451362 还可能是 杜(14513623) 的开头，不能自动切",
        segmenter.decide("1451362", false));
    ChineseDotStreamSegmenter.Decision decision = segmenter.decide("1451362", true);
    assertNotNull(decision);
    assertArrayEquals(new String[] {"145", "136", "2"}, decision.cells);
    assertEquals(7, decision.consumedPoints);
    assertFalse(decision.ambiguous);
  }

  /** 打"波"（12-26-1）：五个点齐了，但必须按上屏键才出。 */
  @Test
  public void bo_isDecidedWhenUserCommits() {
    assertNull("12 还不能定，后面还可能是别的音节", segmenter.decide("12", false));
    assertNull(segmenter.decide("1226", false));
    assertNull("没有上屏键就不自动出字", segmenter.decide("12261", false));
    ChineseDotStreamSegmenter.Decision decision = segmenter.decide("12261", true);
    assertNotNull(decision);
    assertArrayEquals(new String[] {"12", "26", "1"}, decision.cells);
    assertEquals(5, decision.consumedPoints);
  }

  /**
   * 打"勒"（123-26-23）：七个点齐了，同样要按上屏键。
   *
   * <p>{@code 123} 单独看既可能是"勒"的开头，也可能是别的字的开头——这正是不能按格切的证据。
   */
  @Test
  public void le_isDecidedWhenUserCommits() {
    assertNull("123 还不能定", segmenter.decide("123", false));
    assertNull(segmenter.decide("1232623", false));
    ChineseDotStreamSegmenter.Decision decision = segmenter.decide("1232623", true);
    assertNotNull(decision);
    assertArrayEquals(new String[] {"123", "26", "23"}, decision.cells);
  }

  /**
   * 连打时绝不自动切，整串点一直攒到上屏键。
   *
   * <p>这是 38.1% 错切 bug 的直接回归断言。旧实现用最长匹配自动切：打"的"({@code 145-26})
   * 后接"是"的首点 1，前 6 点 {@code 145261} 恰好也是音节 {@code 145-26-1}，"的"就被吞掉了；
   * 随机连打 30 字 x 200 轮实测 6000 字里 2286 处切错。改成上屏键定边界后正确率 98.8%。
   */
  @Test
  public void noAutomaticCutWhileTyping() {
    assertNull("连打两个字不能自动切", segmenter.decide("122611345243", false));
    assertNull("的 + 下一个字首点不能切成 145-26-1", segmenter.decide("145261", false));
    assertNull("读 + 下一个字首点也不能自动切", segmenter.decide("14513621", false));
    assertNull("打完一个完整音节也不自动出，一律等上屏键", segmenter.decide("12261", false));
  }

  /** 上屏键一次只切一个音节：剩下的点留着，下次按上屏键再切下一个。 */
  @Test
  public void commitKeySplitsOneSyllableAtATime() {
    String stream = "12261" + "1345243";
    ChineseDotStreamSegmenter.Decision first = segmenter.decide(stream, true);
    assertNotNull(first);
    assertArrayEquals(new String[] {"12", "26", "1"}, first.cells);
    assertEquals(5, first.consumedPoints);

    String rest = stream.substring(first.consumedPoints);
    ChineseDotStreamSegmenter.Decision second = segmenter.decide(rest, true);
    assertNotNull(second);
    assertArrayEquals(new String[] {"1345", "24", "3"}, second.cells);
  }

  /**
   * 按上屏键时"读"仍可能被当成"杜"的开头，强制最长匹配必须切出用户已打完的"读"。
   */
  @Test
  public void commitKeyForcesShorterSyllable() {
    ChineseDotStreamSegmenter.Decision decision = segmenter.decide("1451362", true);
    assertNotNull(decision);
    assertArrayEquals(new String[] {"145", "136", "2"}, decision.cells);
    assertEquals(7, decision.consumedPoints);
  }

  /** 撞串必须如实报告，由上层按字频择优。 */
  @Test
  public void collidingStreamsReportAmbiguity() {
    ChineseDotStreamSegmenter.Decision decision = segmenter.decide("12352", true);
    assertNotNull(decision);
    assertTrue("12352 应同时是 拔(12-35-2) 与 儿(1235-2)", decision.ambiguous);
    assertTrue(decision.matchCount >= 2);
    // 撞串时先给格数少的那个（1235-2），用户仍可上下滑换候选。
    assertArrayEquals(new String[] {"1235", "2"}, decision.cells);
  }

  /** 走不通的点流必须能识别出来，否则键盘会一直挂着等一个不会来的点。 */
  @Test
  public void deadEndIsDetected() {
    assertFalse(segmenter.canStillGrow("48"));
    assertTrue(segmenter.canStillGrow("145"));
  }

  @Test
  public void emptyStreamIsNotDecided() {
    assertNull(segmenter.decide("", false));
    assertNull(segmenter.decide(null, false));
  }
}
