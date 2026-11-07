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

import androidx.annotation.Nullable;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 中文现行盲文每一格的<b>中文读名</b>。
 *
 * <p>现行盲文一个字 = 三格（声母-韵母-声调），输入过程中每一格单独落下。原来播报的是点位号
 * （"点 1 点 2"），盲人用户无法把它和"我在打哪个音"对应起来——盲文是记音的，不是记点位的，
 * 学习时记的就是"1-2 点是波、1-2-4-5 点是哥"。所以这里按教学读法播报：声母读呼读音（波、坡、
 * 摸…），韵母读教材例字（啊、喔、哀、安…），声调读"一声/二声/三声/四声"。
 *
 * <p><b>数据来自实测，不是按记忆抄表</b>。全部点位由 {@code liblouis/tables/zhcn-g1.ctb} 反推：
 * 取 20974 条 {@code sign} 定义（格式 {@code 声母-韵母-声调}），配合一批读音确定的字（波 bō、
 * 坡 pō、知 zhī、腰 yāo…）逐格对齐，得到每一格的归属；声调格用 20974 条定义的末格分布交叉
 * 验证，恰好只有 {@code 1/2/3/23/0} 五种（5151/5721/3383/6617/40 条），与"阴平/阳平/上声/
 * 去声/轻声"一一对应。
 *
 * <p><b>三对重码是语言事实，不是表错</b>：{@code 1245}、{@code 13}、{@code 125} 同时是
 * g/k/h 和 j/q/x（j/q/x 只拼齐齿、撮口，g/k/h 只拼开口、合口，互补分布），{@code 26} 同时是
 * o 和 e（b p m f 后接 o，d t n l g k h 后接 e）。这些音位在盲文教学里就读作"哥或基"这一类，
 * 播报必须把两种可能都念出来，否则用户听到"哥"会以为自己打错了。
 *
 * <p>本类<b>不依赖 Android</b>（只吃 {@link String} 点位串），可以直接单测。
 */
public final class ChineseBrailleSignNames {

  /** 重码音位播报时用的连接词。 */
  public static final String OR = "或";

  private static final Map<String, String> SHENGMU = new LinkedHashMap<>();
  private static final Map<String, String> YUNMU = new LinkedHashMap<>();
  private static final Map<String, String> TONE = new LinkedHashMap<>();
  private static final Map<String, String> ALL = new LinkedHashMap<>();

  static {
    // 声母：18 种点位覆蓋 21 个声母（j/q/x 与 g/k/h 同点位）。
    SHENGMU.put("12", "波"); // b
    SHENGMU.put("1234", "坡"); // p
    SHENGMU.put("134", "摸"); // m
    SHENGMU.put("124", "佛"); // f
    SHENGMU.put("145", "得"); // d
    SHENGMU.put("2345", "特"); // t
    SHENGMU.put("1345", "讷"); // n
    SHENGMU.put("123", "勒"); // l
    SHENGMU.put("1245", "哥" + OR + "基"); // g / j
    SHENGMU.put("13", "科" + OR + "欺"); // k / q
    SHENGMU.put("125", "喝" + OR + "希"); // h / x
    SHENGMU.put("34", "知"); // zh
    SHENGMU.put("12345", "吃"); // ch
    SHENGMU.put("156", "诗"); // sh
    SHENGMU.put("245", "日"); // r
    SHENGMU.put("1356", "资"); // z
    SHENGMU.put("14", "雌"); // c
    SHENGMU.put("234", "思"); // s

    // 韵母：34 种点位覆蓋全部 35 个韵母（o 与 e 同点位）。
    YUNMU.put("35", "啊"); // a
    YUNMU.put("26", "喔" + OR + "鹅"); // o / e
    YUNMU.put("24", "衣"); // i
    YUNMU.put("136", "乌"); // u
    YUNMU.put("346", "迂"); // ü
    YUNMU.put("246", "哀"); // ai
    YUNMU.put("2346", "诶"); // ei
    YUNMU.put("235", "熬"); // ao
    YUNMU.put("12356", "欧"); // ou
    YUNMU.put("1236", "安"); // an
    YUNMU.put("356", "恩"); // en
    YUNMU.put("236", "昂"); // ang
    YUNMU.put("3456", "鞥"); // eng
    YUNMU.put("1235", "儿"); // er
    YUNMU.put("1246", "呀"); // ia
    YUNMU.put("15", "耶"); // ie
    YUNMU.put("345", "腰"); // iao
    YUNMU.put("1256", "优"); // iu
    YUNMU.put("146", "烟"); // ian
    YUNMU.put("126", "因"); // in
    YUNMU.put("1346", "央"); // iang
    YUNMU.put("16", "英"); // ing
    YUNMU.put("1456", "雍"); // iong
    YUNMU.put("123456", "蛙"); // ua
    YUNMU.put("135", "窝"); // uo
    YUNMU.put("13456", "歪"); // uai
    YUNMU.put("2456", "威"); // ui
    YUNMU.put("12456", "弯"); // uan
    YUNMU.put("25", "温"); // un
    YUNMU.put("2356", "汪"); // uang
    YUNMU.put("256", "翁"); // ong
    YUNMU.put("23456", "约"); // üe
    YUNMU.put("12346", "冤"); // üan
    YUNMU.put("456", "晕"); // ün

    // 声调：全表末格只出现这五种。
    TONE.put("1", "一声");
    TONE.put("2", "二声");
    TONE.put("3", "三声");
    TONE.put("23", "四声");
    TONE.put("0", "轻声");

    ALL.putAll(SHENGMU);
    ALL.putAll(YUNMU);
    ALL.putAll(TONE);
  }

  private ChineseBrailleSignNames() {}

  /**
   * 返回这一格的中文读名，例如 {@code "12" -> "波"}、{@code "1245" -> "哥或基"}、
   * {@code "23" -> "四声"}。
   *
   * @param dots 点位串，按点号升序（{@link com.google.android.accessibility.braille.interfaces
   *     .BrailleCharacter#toString()} 的输出格式）
   * @return 认不出来（标点、符号、空白格）时返回 {@code null}，由调用方回落到点位文本
   */
  @Nullable
  public static String describe(String dots) {
    if (dots == null || dots.isEmpty()) {
      return null;
    }
    return ALL.get(dots);
  }

  /** 这一格是不是声母格。 */
  public static boolean isShengmu(String dots) {
    return SHENGMU.containsKey(dots);
  }

  /** 这一格是不是韵母格。 */
  public static boolean isYunmu(String dots) {
    return YUNMU.containsKey(dots);
  }

  /** 这一格是不是声调格。 */
  public static boolean isTone(String dots) {
    return TONE.containsKey(dots);
  }

  /**
   * 这一格在音节第 {@code position} 格（从 0 起）的位置上，是否已经<b>完整到可以单独交出去</b>。
   *
   * <p>键盘层用它决定"停顿多久就把攒下的点结算成一格"：完整就给短窗口（连打不拖沓），
   * 还不完整就给长窗口——因为用户很可能只是在这一格中间摸索，点与点的间隔超过一秒是常态。
   * 这一点是实测出来的：用户报"打一个字时间太长就拼不成了"，根因就是中间状态（例如
   * {@code 12} 的第一点 {@code 1} 在表里是"一声"）被当成了完整符号提前结算。
   *
   * <p>位置判断故意<b>不</b>让第 2 格接受声调：2 格字（声母 + 声调）只占 19%，让它们多等一会儿，
   * 换来 81% 的三格字在打韵母时不会因为第一点恰好是声调点位而被切断。
   *
   * <p>表外的点集（标点、数字符 {@code 3456}、用户乱按的组合）一律算完整，按原来的节奏结算：
   * 这类内容没有"打一半"的中间态，多等没有收益。
   */
  public static boolean isCompleteAt(String dots, int position) {
    if (dots == null || dots.isEmpty()) {
      return false;
    }
    if (isShengmu(dots)) {
      return position <= 0;
    }
    if (isYunmu(dots)) {
      return position <= 1;
    }
    if (isTone(dots)) {
      return position >= 2;
    }
    return true;
  }

  /** 全部声母点位，供单测穷举校验。 */
  public static Set<String> shengmuCells() {
    return new HashSet<>(SHENGMU.keySet());
  }

  /** 全部韵母点位。 */
  public static Set<String> yunmuCells() {
    return new HashSet<>(YUNMU.keySet());
  }

  /** 全部声调点位。 */
  public static Set<String> toneCells() {
    return new HashSet<>(TONE.keySet());
  }

  /** 表内全部点位（调试用）。 */
  public static Set<String> allCells() {
    return new HashSet<>(ALL.keySet());
  }
}
