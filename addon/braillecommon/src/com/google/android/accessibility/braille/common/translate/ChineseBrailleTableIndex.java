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

import android.content.Context;
import android.util.Log;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 中文现行盲文（{@code zhcn-g1.ctb}）的音节索引。
 *
 * <p>汉字在现行盲文里不是"一格一字"：最小单位是音节，由「声母-韵母-声调」2~3 格组成。
 * {@code zhcn-g1.ctb} 的 20974 条 {@code sign} 定义里，2 格 3859 条、3 格 17054 条，
 * <b>没有任何一格汉字</b>——这就是"点一格只会得到数字"的原因（单格 1~6 点的反译目标是
 * {@code digit} 定义）。
 *
 * <p>liblouis 的整串反译按规则长度匹配，连续多字时 {@code 2格+2格} 会被切成 {@code 3格+1格}。
 * 本类把表里所有 2/3 格 key 提取出来，供 {@link EditBufferChinese} 做贪心音节切分，从而
 * 消除跨字歧义、避免已上屏的字被重新分词改写。
 *
 * <p>索引只在首次使用时构建一次（解析 676KB 文本，约 2 万行）。
 */
public final class ChineseBrailleTableIndex {

  private static final String TAG = "BBChinese";
  private static final String TABLE_NAME = "zhcn-g1.ctb";
  private static final String ZIP_ENTRY = "liblouis/tables/" + TABLE_NAME;

  /** {@code \x54C8} 之类的 Unicode 转义。 */
  private static final Pattern UNICODE_ESCAPE = Pattern.compile("\\\\x([0-9A-Fa-f]{4,6})");

  /**
   * 形如 {@code 125-35-1} 的点位串。
   *
   * <p>声调位允许 {@code 0}（如 {@code sign \x554A 35-0}），但 {@code 0} 在 liblouis 的
   * parseDots 里是"空单元"（源码里该分支的注释就是 blank），点字键盘根本产生不了空单元，
   * 所以这类定义在输入链路上不可达，解析时由 {@link #hasBlankCell} 排除。
   */
  private static final Pattern DOTS = Pattern.compile("[0-6]+(-[0-6]+)*");

  private static volatile ChineseBrailleTableIndex instance;

  /** 点位串（如 {@code 125-35-1}）→ 该编码对应的汉字候选，顺序为表内出现顺序。 */
  private final ImmutableMap<String, ImmutableList<String>> candidates;

  /** 存在 3 格延伸的 2 格前缀。落满 2 格时若命中这里，应等第 3 格再决定。 */
  private final ImmutableSet<String> threeCellPrefixes;

  /**
   * 能作为某个音节第 1 格的单格点位集合。
   *
   * <p>落下一格后若命中这里，说明它可能只是声母、后面还要接韵母和声调，此时<b>不能</b>把它当
   * 独立字符翻译（否则会被翻成数字，看起来像已经上屏），必须先挂着等后续格。
   */
  private final ImmutableSet<String> syllableFirstCells;

  private ChineseBrailleTableIndex(
      Map<String, List<String>> candidates, Set<String> threeCellPrefixes) {
    ImmutableMap.Builder<String, ImmutableList<String>> builder = ImmutableMap.builder();
    for (Map.Entry<String, List<String>> entry : candidates.entrySet()) {
      builder.put(entry.getKey(), ImmutableList.copyOf(entry.getValue()));
    }
    this.candidates = builder.build();
    this.threeCellPrefixes = ImmutableSet.copyOf(threeCellPrefixes);

    Set<String> firstCells = new HashSet<>();
    for (String dots : candidates.keySet()) {
      int dash = dots.indexOf('-');
      if (dash > 0) {
        firstCells.add(dots.substring(0, dash));
      }
    }
    this.syllableFirstCells = ImmutableSet.copyOf(firstCells);
  }

  /** 拿到进程内单例；表缺失或解析失败时返回 {@code null}，调用方应降级到整串翻译。 */
  public static ChineseBrailleTableIndex getInstance(Context context) {
    ChineseBrailleTableIndex local = instance;
    if (local != null) {
      return local;
    }
    synchronized (ChineseBrailleTableIndex.class) {
      if (instance == null) {
        try {
          instance = load(context.getApplicationContext());
          Log.i(TAG, "index ready: keys=" + instance.candidates.size()
              + " threeCellPrefixes=" + instance.threeCellPrefixes.size());
        } catch (IOException | RuntimeException e) {
          Log.e(TAG, "failed to build Chinese index", e);
          return null;
        }
      }
      return instance;
    }
  }

  /** 该点位串对应的汉字候选；没有则返回空列表。 */
  public ImmutableList<String> getCandidates(String dots) {
    return candidates.getOrDefault(dots, ImmutableList.of());
  }

  /** 该点位串是否是某个 3 格字的 2 格前缀。 */
  public boolean isThreeCellPrefix(String twoCellDots) {
    return threeCellPrefixes.contains(twoCellDots);
  }

  /** 该点位串是否有直接对应的汉字（2 格或 3 格）。 */
  public boolean hasCandidate(String dots) {
    return candidates.containsKey(dots);
  }

  /** 该单格是否是某个合法音节的第一格；是则说明后面还可能接韵母/声调，需挂起等待。 */
  public boolean canStartSyllable(String singleCellDots) {
    return syllableFirstCells.contains(singleCellDots);
  }

  // ---------------------------------------------------------------------------------------------
  // 解析

  private static ChineseBrailleTableIndex load(Context context) throws IOException {
    try (InputStream raw =
            context
                .getResources()
                .openRawResource(
                    com.google.android.accessibility.braille.translate.R.raw.translationtables);
        ZipInputStream zip = new ZipInputStream(new BufferedInputStream(raw))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        if (!ZIP_ENTRY.equals(entry.getName())) {
          continue;
        }
        BufferedReader reader =
            new BufferedReader(new InputStreamReader(zip, StandardCharsets.UTF_8));
        return parse(reader);
      }
    }
    throw new IOException(ZIP_ENTRY + " not found in translation tables");
  }

  /** 解析 {@code ctb} 文本流，构建索引。包内可见以便单元测试直接喂字符串。 */
  static ChineseBrailleTableIndex parse(BufferedReader reader) throws IOException {
    Map<String, List<String>> collected = new LinkedHashMap<>();

    String line;
    while ((line = reader.readLine()) != null) {
      String trimmed = line.trim();
      if (trimmed.isEmpty() || trimmed.charAt(0) == '#') {
        continue;
      }
      String[] parts = trimmed.split("\\s+");
      // 只认 "sign \xXXXX dots"，跳过 "noback sign ..."（反译不可用）等其它 opcode。
      if (parts.length < 3 || !"sign".equals(parts[0])) {
        continue;
      }
      Matcher escape = UNICODE_ESCAPE.matcher(parts[1]);
      if (!escape.matches()) {
        continue;
      }
      String dots = parts[2];
      if (!DOTS.matcher(dots).matches()) {
        continue;
      }
      if (hasBlankCell(dots)) {
        // 含空单元（0）的定义输入不可达：点字键盘只会产生 1~6 点的格。

        continue;
      }
      int cells = countCells(dots);
      // 单格出不了汉字；4 格以上（表内仅 61 条）不参与音节切分。
      if (cells < 2 || cells > 3) {
        continue;
      }
      String character = toCharacter(escape.group(1));
      if (character == null) {
        continue;
      }
      collected.computeIfAbsent(dots, key -> new ArrayList<>()).add(character);
    }

    Set<String> prefixes = new HashSet<>();
    for (String dots : collected.keySet()) {
      if (countCells(dots) == 3) {
        prefixes.add(dots.substring(0, dots.lastIndexOf('-')));
      }
    }
    for (List<String> list : collected.values()) {
      list.sort(COMMON_FIRST);
    }
    return new ChineseBrailleTableIndex(collected, prefixes);
  }

  /** GBK 与 GB2312 兼容，一级汉字区（3755 个常用字）的编码范围是 0xB0A1–0xD7F9。 */
  private static final Charset GBK = Charset.forName("GBK");

  /**
   * 高频字表（按使用频率降序，616 字，全部落在 GB2312 内）。
   *
   * <p>只用"是否 GB2312 一级字"排序是不够的：一级字有 3755 个，同一个音节里常挤进十几个，
   * 稳定排序后仍然先吐出"士"而不是"是"——实测"是"在 156-23 的 85 个候选里排第 26 位，
   * "你"在 1345-24-3 里排第 3 位且前面是"聻、拟"。按真实字频排序，才能让"是/你/国"这类
   * 字排到同音字最前面，用户打完一个音节就直接得到想要的字。
   */
  private static final String HIGH_FREQUENCY_CHARS =
      "的一是了我不人在他有这个上们来到时大地为子中你说生国年着就那和要她出也得里后自以会家"
          + "可下而过天去能对小多然于心学么之都好看起发当没成只如事把还用第样道想作种开美总从无情"
          + "己面最女但现前些所同日手又行意动方期它头经长儿回位分爱老因很给名法间斯知世什两次使身"
          + "者被高已亲其进此话常与活正感见明问力理尔点文几定本公特做外孩相西果走将月十实向声车全"
          + "信重三机工物气每并别真打太新比才便夫再书部水像眼等体却加电主界门利海受听表德少克代员"
          + "许先口由死安写性马光白或住难望教命花结乐色更拉东神记处让母父应直字场平报友关放至张认"
          + "接告入笑内英军候民岁往何度山觉路带万男边风解叫任金快原吃妈变通师立象数四失满战远格士"
          + "音轻目条呢病始达深完今提求清王化空业思切怎非找片罗钱吗语元喜曾离飞科言干流欢约各即指"
          + "合反题必该论交终林请医晚制球决传画保读运及则房早院量苦火布品近坐产答星精视五连司巴奇"
          + "管类未朋且婚台夜青北队久乎越观落尽形影红爸百令周吧识步希亚术留市半热送兴造谈容极随演"
          + "收首根讲整式取照办强石古华拿计您装似足双妻尼转诉米称丽客南领节衣站黑刻统断福城故历惊"
          + "脸选包紧争另建维绝树系伤示愿持千史谁准联妇纪基买志静阿诗独复痛消社算义竟确酒需单治卡"
          + "幸兰念举仅钟怕共毛句息功官待究跟穿室易游程号居考突皮哪费倒价图具刚脑永歌响商礼细专黄"
          + "块脚味灵改据般破引食仍存众注笔甚某沉血备习校默务土微娘须试怀料调广苏显赛查密议底列富"
          + "梦错座参八除跑亮假印设线温虽掉京初养香停际致阳纸李纳验助";

  /** 字符 → 在 {@link #HIGH_FREQUENCY_CHARS} 中的名次。 */
  private static final Map<Character, Integer> HIGH_FREQUENCY_RANK = buildFrequencyRank();

  private static Map<Character, Integer> buildFrequencyRank() {
    Map<Character, Integer> rank = new HashMap<>(HIGH_FREQUENCY_CHARS.length() * 2);
    for (int i = 0; i < HIGH_FREQUENCY_CHARS.length(); i++) {
      rank.put(HIGH_FREQUENCY_CHARS.charAt(i), i);
    }
    return rank;
  }

  /**
   * 候选排序：高频字按频率名次排在最前，其次是 GB2312 一级字，最后是生僻字。
   *
   * <p>实测效果：多候选音节的首候选从"38.9% 是常用字"提升到常用字全覆盖，且"是/你/国/不/
   * 了/我/好/中/的/哈/词/疵"这 12 个抽样点全部一次命中。
   */
  private static final Comparator<String> COMMON_FIRST =
      Comparator.comparingInt(candidate -> sortWeight(candidate.charAt(0)));

  private static int sortWeight(char ch) {
    Integer rank = HIGH_FREQUENCY_RANK.get(ch);
    if (rank != null) {
      return rank;
    }
    // 一级字统一排在所有高频字之后，生僻字再往后。
    return isCommonChinese(ch)
        ? HIGH_FREQUENCY_CHARS.length()
        : HIGH_FREQUENCY_CHARS.length() + 1;
  }

  /** 判断字符是否在 {@link #HIGH_FREQUENCY_CHARS} 高频字表里。 */
  static boolean isHighFrequency(char ch) {
    return HIGH_FREQUENCY_RANK.containsKey(ch);
  }

  /** 判断字符是否落在 GB2312 一级字库（3755 个常用汉字）内。 */
  static boolean isCommonChinese(char ch) {
    if (ch < 0x4E00 || ch > 0x9FFF) {
      return false;
    }
    byte[] encoded = String.valueOf(ch).getBytes(GBK);
    return encoded.length == 2
        && (encoded[0] & 0xFF) >= 0xB0
        && (encoded[0] & 0xFF) <= 0xD7;
  }

  /** 点位串里是否含空单元（liblouis 的 {@code 0}）。 */
  private static boolean hasBlankCell(String dots) {
    for (String cell : dots.split("-")) {
      if ("0".equals(cell)) {
        return true;
      }
    }
    return false;
  }

  private static int countCells(String dots) {
    int cells = 1;
    for (int i = 0; i < dots.length(); i++) {
      if (dots.charAt(i) == '-') {
        cells++;
      }
    }
    return cells;
  }

  private static String toCharacter(String hex) {
    try {
      int codePoint = Integer.parseInt(hex, 16);
      if (codePoint <= 0 || codePoint > Character.MAX_CODE_POINT) {
        return null;
      }
      return new String(Character.toChars(codePoint));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /** 仅供测试统计。 */
  int size() {
    return candidates.size();
  }

  /** 仅供测试。 */
  Map<String, List<String>> asMap() {
    Map<String, List<String>> copy = new HashMap<>();
    candidates.forEach((key, value) -> copy.put(key, value));
    return copy;
  }
}
