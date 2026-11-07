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

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把连续按下的点流切成音节，<b>不依赖任何等待时间</b>。
 *
 * <p><b>为什么不能用"格"来判边界</b>：中文盲文一格是若干点的集合，而点位之间是包含关系——
 * 52 个可作首格的点集里有 51 个是另一个更长点集的子集（{@code 12} 是波，加 3 成勒、加 4 成佛、
 * 加 5 成喝）。所以"这一格按完了没有"在点位结构上几乎永远无法判定，只能靠停顿猜。这正是
 * 用户说的"时间太长就拼不成"的根源：猜早了把一格切断，猜晚了把两格粘住，两头都会错。
 *
 * <p><b>本类的做法</b>：完全不判格，改判"音节"。一个音节由 2~3 格组成，把所有格的点连起来
 * 是一串点（如"读"= {@code 145-136-2} → {@code 1451362}）。只要这串点落在音节表里，就不需要
 * 知道中间哪一刀切格。于是判据从"这格完了吗"变成"这串点是不是已经是一个不能更长的音节"，
 * 后者是确定的、与手速无关的。
 *
 * <p><b>确定的条件</b>（两条同时成立）：
 *
 * <ol>
 *   <li>它的某个前缀能切成 2~3 格，且这串格正好是表里的一个音节；
 *   <li>这个前缀不是任何更长音节点串的真前缀（否则用户可能还在打一个更长的音节，
 *       例如 {@code 1451} 看着能当"搭"，但它也是"读"({@code 1451362}) 的开头，不能定）。
 * </ol>
 *
 * <p>取满足条件的最短前缀，切出这个音节，剩余的点留给下一次判断。用户按多慢都不会被切断，
 * 按多快都不会粘连。
 *
 * <p><b>已知歧义</b>：极少数音节点串完全相同，例如"拔"({@code 12-35-2}) 与"儿"({@code
 * 1235-2}) 都对应 {@code 12352}，1343 个音节里有 143 个（10.6%）存在这种撞串。本类如实把多个
 * 匹配都返回，由调用方按字频挑选并告知用户可改。
 *
 * <p>纯 Java，不依赖 Android，可直接单测。
 */
public final class ChineseDotStreamSegmenter {

  /** 一格的点数上限（六点盲文）。 */
  private static final int MAX_CELL_DOTS = 6;

  /** 一个音节最多几格（现行盲文是声母-韵母-声调，2 格字省掉韵母）。 */
  private static final int MAX_SYLLABLE_CELLS = 3;

  /** 一个音节最多几个点，用于限制前缀枚举范围。 */
  private static final int MAX_SYLLABLE_POINTS = MAX_CELL_DOTS * MAX_SYLLABLE_CELLS;

  /** 表内全部音节，key 形如 {@code 145-136-2}。 */
  private final Set<String> syllableKeys = new HashSet<>();

  /**
   * 所有"更长音节的严格前缀"。
   *
   * <p>一个点串落在这里，说明用户可能还在打一个更长的音节，此刻切出去就会把字打残。
   */
  private final Set<String> longerSyllablePrefixes = new HashSet<>();

  /** 全部完整音节的点串，用于判断当前点流是否已经跑偏（死路）。 */
  private final Set<String> completeStreams = new HashSet<>();

  /** 点串 → 该串对应的音节格序列；长度 > 1 即撞串歧义。 */
  private final Map<String, List<String[]>> syllablesByStream = new HashMap<>();

  /** 一次判定的结果。 */
  public static final class Decision {
    /** 从点流头部切出来的音节，每个元素是一格的点位串（按点号升序）。 */
    public final String[] cells;

    /** 这个音节用掉了点流头部的几个点。 */
    public final int consumedPoints;

    /** 同一段点串能对应多个音节时为 true，此时 {@link #cells} 只是其中一种。 */
    public final boolean ambiguous;

    /** 该点串能对应的音节个数，正常为 1，撞串时大于 1。 */
    public final int matchCount;

    /** 同一段点串能对应的全部音节切分；长度 > 1 即撞串歧义。 */
    public final List<String[]> alternatives;

    Decision(String[] cells, List<String[]> alternatives, int consumedPoints) {
      this.cells = cells;
      this.alternatives = alternatives;
      this.consumedPoints = consumedPoints;
      this.matchCount = alternatives.size();
      this.ambiguous = matchCount > 1;
    }
  }

  private ChineseDotStreamSegmenter() {}

  /**
   * 建立切分器。
   *
   * @param syllableKeys 音节表的 key 集合，形如 {@code 145-136-2}；不合规的条目会被跳过
   */
  public static ChineseDotStreamSegmenter create(Collection<String> syllableKeys) {
    ChineseDotStreamSegmenter segmenter = new ChineseDotStreamSegmenter();
    for (String key : syllableKeys) {
      if (key == null) {
        continue;
      }
      String[] cells = key.split("-");
      if (cells.length < 2 || cells.length > MAX_SYLLABLE_CELLS) {
        continue;
      }
      boolean valid = true;
      for (String cell : cells) {
        if (cell.isEmpty() || cell.length() > MAX_CELL_DOTS) {
          valid = false;
          break;
        }
      }
      if (!valid) {
        continue;
      }
      segmenter.syllableKeys.add(key);
      String stream = key.replace("-", "");
      segmenter.completeStreams.add(stream);
      segmenter.syllablesByStream.computeIfAbsent(stream, unused -> new ArrayList<>()).add(cells);
      for (int i = 1; i < stream.length(); i++) {
        segmenter.longerSyllablePrefixes.add(stream.substring(0, i));
      }
    }
    return segmenter;
  }

  /** 表内音节个数，供单测断言。 */
  public int syllableCount() {
    return syllableKeys.size();
  }

  /**
   * 从点流头部切出一个音节。
   *
   * @param stream 尚未处理的点流，例如 {@code 1451362}
   * @param force {@code false} 表示"只切确实无疑的"（用户每按一个点后调用，绝不能猜）；
   *     {@code true} 表示"用户已经示意这串点打完了"（按空格、做手势时调用），此时按最长匹配
   *     直接切，哪怕它还可能延长
   * @return 能切时返回该音节；还需要更多点、或者这段点流已经不可能成字时返回 {@code null}
   */
  public Decision decide(String stream, boolean force) {
    if (stream == null || stream.isEmpty()) {
      return null;
    }
    int limit = Math.min(stream.length(), MAX_SYLLABLE_POINTS);
    if (force) {
      // 用户已经表示打完了，按最长匹配切：二声（单点 2）永远是四声（23）的前缀，
      // 不强制的话"读"这类二声字永远出不来。
      return longestPrefixMatch(stream, limit);
    }
    // 整串还是某个更长音节的开头 —— 现在切就是赌，宁可等。
    if (longerSyllablePrefixes.contains(stream)) {
      return null;
    }
    // 整串已经不可能再长了（用户按了下一个字的点，或者按错了）：回退到最长可行切分。
    // 这一条让"连续输入"不需要按空格：打"读"时按到 1451362 还不切（可能是 杜 14513623），
    // 一旦按下下一个字的第一个点，整串不再是任何音节的开头，就立刻把"读"切出来。
    return longestPrefixMatch(stream, limit);
  }

  private Decision longestPrefixMatch(String stream, int limit) {
    for (int length = limit; length >= 1; length--) {
      List<String[]> matches = matchesFor(stream.substring(0, length));
      if (!matches.isEmpty()) {
        return new Decision(pickBest(matches), matches, length);
      }
    }
    return null;
  }

  /**
   * 这段点流是否还能继续变长成一个音节。
   *
   * <p>返回 false 表示已经走不通了（用户按错了点），调用方应该把这点退掉或当成无效输入，
   * 不能一直挂着等。
   */
  public boolean canStillGrow(String stream) {
    if (stream == null || stream.isEmpty()) {
      return true;
    }
    if (completeStreams.contains(stream)) {
      return true;
    }
    return longerSyllablePrefixes.contains(stream);
  }

  /** 点串是否恰好是某个完整音节。 */
  public boolean isCompleteSyllable(String stream) {
    return stream != null && completeStreams.contains(stream);
  }

  /**
   * 同一串点撞上多个音节时先挑一个。
   *
   * <p>本类不知道字频，只按"格数少优先"稳定挑选（2 格字比 3 格字更可能是本意，因为多出来的那格
   * 往往是把下一格误并了进来）；真正按字频择优由调用方用候选表覆盖。
   */
  private static String[] pickBest(List<String[]> matches) {
    int bestIndex = 0;
    for (int i = 1; i < matches.size(); i++) {
      if (matches.get(i).length < matches.get(bestIndex).length) {
        bestIndex = i;
      }
    }
    return matches.get(bestIndex);
  }

  /** 枚举 prefix 所有"切成 2~3 格且音节在表里"的方式。 */
  private List<String[]> matchesFor(String prefix) {
    List<String[]> matches = new ArrayList<>();
    int total = prefix.length();
    for (int first = 1; first <= MAX_CELL_DOTS; first++) {
      int second = total - first;
      if (second >= 1 && second <= MAX_CELL_DOTS) {
        addIfSyllable(prefix, new int[] {first, second}, matches);
      }
      for (int s = 1; s <= MAX_CELL_DOTS; s++) {
        int third = total - first - s;
        if (third >= 1 && third <= MAX_CELL_DOTS) {
          addIfSyllable(prefix, new int[] {first, s, third}, matches);
        }
      }
    }
    return matches;
  }

  private void addIfSyllable(String prefix, int[] cuts, List<String[]> out) {
    String[] cells = new String[cuts.length];
    int offset = 0;
    for (int i = 0; i < cuts.length; i++) {
      cells[i] = prefix.substring(offset, offset + cuts[i]);
      offset += cuts[i];
    }
    String key = String.join("-", cells);
    if (syllableKeys.contains(key)) {
      out.add(cells);
    }
  }

  /** 供调试与单测：返回某串点能对应的全部音节格序列。 */
  public List<String[]> allMatches(String stream) {
    return new ArrayList<>(syllablesByStream.getOrDefault(stream, new ArrayList<>()));
  }
}
