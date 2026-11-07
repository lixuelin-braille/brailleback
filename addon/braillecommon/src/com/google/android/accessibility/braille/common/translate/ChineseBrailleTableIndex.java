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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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

  private ChineseBrailleTableIndex(
      Map<String, List<String>> candidates, Set<String> threeCellPrefixes) {
    ImmutableMap.Builder<String, ImmutableList<String>> builder = ImmutableMap.builder();
    for (Map.Entry<String, List<String>> entry : candidates.entrySet()) {
      builder.put(entry.getKey(), ImmutableList.copyOf(entry.getValue()));
    }
    this.candidates = builder.build();
    this.threeCellPrefixes = ImmutableSet.copyOf(threeCellPrefixes);
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
    return new ChineseBrailleTableIndex(collected, prefixes);
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
