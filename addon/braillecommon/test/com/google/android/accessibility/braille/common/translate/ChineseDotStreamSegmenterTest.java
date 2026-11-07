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

  /** 打"波"（12-26-1）：五个点确定，不需要任何等待。 */
  @Test
  public void bo_isDecidedAtFiveDots() {
    assertNull("只看 12 还不能定，后面还可能是别的音节", segmenter.decide("12", false));
    assertNull(segmenter.decide("1226", false));
    ChineseDotStreamSegmenter.Decision decision = segmenter.decide("12261", false);
    assertNotNull(decision);
    assertArrayEquals(new String[] {"12", "26", "1"}, decision.cells);
    assertEquals(5, decision.consumedPoints);
  }

  /**
   * 打"勒"（123-26-23）：七个点确定。
   *
   * <p>{@code 123} 单独看既可能是"勒"的开头，也可能是别的字的开头——这正是不能按格切的证据。
   */
  @Test
  public void le_isDecidedAtSevenDots() {
    assertNull("123 还不能定", segmenter.decide("123", false));
    ChineseDotStreamSegmenter.Decision decision = segmenter.decide("1232623", false);
    assertNotNull(decision);
    assertArrayEquals(new String[] {"123", "26", "23"}, decision.cells);
  }

  /** 连续输入：切掉一个音节后，剩下的点流还能继续切下一个。 */
  @Test
  public void consecutiveSyllablesAreSplitOneByOne() {
    String stream = "12261" + "1345243";
    ChineseDotStreamSegmenter.Decision first = segmenter.decide(stream, false);
    assertNotNull(first);
    assertArrayEquals(new String[] {"12", "26", "1"}, first.cells);
    assertEquals(5, first.consumedPoints);

    String rest = stream.substring(first.consumedPoints);
    ChineseDotStreamSegmenter.Decision second = segmenter.decide(rest, false);
    assertNotNull(second);
    assertArrayEquals(new String[] {"1345", "24", "3"}, second.cells);
  }

  /**
   * 连续输入不必按空格：打"读"按完最后一个点还不切（可能还要按 3 变"杜"），
   * 一旦按下"你"的第一个点，整串就不再是任何音节的开头，"读"立刻被切出来。
   */
  @Test
  public void nextSyllableTriggersPreviousCut() {
    assertNull(segmenter.decide("1451362", false));
    ChineseDotStreamSegmenter.Decision decision = segmenter.decide("14513621", false);
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
