/*
 * JiebaDict - 基于 jieba 词典的 DAG + 最大概率路径中文分词器（自实现，零第三方依赖）。
 * 算法与 jieba (HMM=False) 一致：词典加载 freq + 前缀表 -> DAG -> 动态规划最大概率路径。
 */
package com.googlecode.eyesfree.braille.service.translate;

import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class JiebaDict {
    private static final String LOG_TAG = "JiebaDict";
    // word -> freq；前缀为 0（仅用于 DAG 遍历）
    private final Map<String, Integer> freqMap = new HashMap<String, Integer>();
    private double logTotal = 0.0;
    private volatile boolean initialized = false;

    public boolean isInitialized() {
        return initialized;
    }

    /** 从 UTF-8 词典流初始化（每行：word freq [pos]）。 */
    public synchronized void init(InputStream dictStream) {
        if (initialized) {
            return;
        }
        try {
            BufferedReader br = new BufferedReader(
                    new InputStreamReader(dictStream, "UTF-8"));
            String line;
            long total = 0;
            List<String[]> rows = new ArrayList<String[]>();
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) {
                    continue;
                }
                String[] p = line.split("\\s+");
                if (p.length < 2) {
                    continue;
                }
                String w = p[0];
                int f = Integer.parseInt(p[1]);
                rows.add(new String[] { w, Integer.toString(f) });
                total += f;
                // 前缀加入 freqMap（值为 0），供 DAG 前缀遍历
                for (int i = 1; i < w.length(); i++) {
                    String pre = w.substring(0, i);
                    if (!freqMap.containsKey(pre)) {
                        freqMap.put(pre, 0);
                    }
                }
            }
            logTotal = Math.log(total);
            for (String[] r : rows) {
                freqMap.put(r[0], Integer.parseInt(r[1]));
            }
            initialized = true;
            Log.i(LOG_TAG, "dict loaded, words=" + freqMap.size());
        } catch (Exception e) {
            Log.e(LOG_TAG, "init failed", e);
        }
    }


    /** 增加自定义词（高优先），用于专名/连写词。 */
    public synchronized void addWord(String word, int freq) {
        if (word == null || word.isEmpty()) {
            return;
        }
        freqMap.put(word, freq);
        for (int i = 1; i < word.length(); i++) {
            String pre = word.substring(0, i);
            if (!freqMap.containsKey(pre)) {
                freqMap.put(pre, 0);
            }
        }
    }

    /** 删除词（用于应分写的整体词），使其被切分。 */
    public synchronized void delWord(String word) {
        if (word != null) {
            freqMap.remove(word);
        }
    }

    /** 是否为词典中的完整词。 */
    public boolean isWord(String word) {
        if (word == null) {
            return false;
        }
        Integer f = freqMap.get(word);
        return f != null && f > 0;
    }
    /** 构建 DAG：每个位置 k -> 可达结束位置列表（含 k 自身）。 */
    private Map<Integer, List<Integer>> buildDag(String sentence) {
        Map<Integer, List<Integer>> dag = new HashMap<Integer, List<Integer>>();
        int n = sentence.length();
        for (int k = 0; k < n; k++) {
            List<Integer> list = new ArrayList<Integer>();
            int i = k;
            String frag = sentence.substring(k, k + 1);
            while (i < n && freqMap.containsKey(frag)) {
                Integer f = freqMap.get(frag);
                if (f != null && f > 0) {
                    list.add(i);
                }
                i++;
                if (i < n) {
                    frag = sentence.substring(k, i + 1);
                } else {
                    break;
                }
            }
            if (list.isEmpty()) {
                list.add(k);
            }
            dag.put(k, list);
        }
        return dag;
    }

    /** 从右往左动态规划最大概率路径。route[i] = (最大score, 结束位置x)。 */
    private double[] calc(String sentence, Map<Integer, List<Integer>> dag) {
        int n = sentence.length();
        double[] score = new double[n + 1];
        int[] end = new int[n + 1];
        score[n] = 0.0;
        end[n] = n;
        for (int i = n - 1; i >= 0; i--) {
            double best = Double.NEGATIVE_INFINITY;
            int bestX = i;
            for (Integer x : dag.get(i)) {
                String w = sentence.substring(i, x + 1);
                int f = freqMap.containsKey(w) ? freqMap.get(w) : 1;
                if (f <= 0) {
                    f = 1;
                }
                double s = (Math.log(f) - logTotal) + score[x + 1];
                if (s > best) {
                    best = s;
                    bestX = x;
                }
            }
            score[i] = best;
            end[i] = bestX;
        }
        // 用 end 数组表示路径
        return score; // 实际路径通过 segment 重算
    }


    /** 分词并返回词及位置：[word, start, end]（相对输入字符串）。 */
    public List<String[]> segmentWithPos(String sentence) {
        List<String[]> result = new ArrayList<String[]>();
        if (sentence == null || sentence.isEmpty()) {
            return result;
        }
        Map<Integer, List<Integer>> dag = buildDag(sentence);
        int n = sentence.length();
        double[] score = new double[n + 1];
        int[] end = new int[n + 1];
        score[n] = 0.0;
        end[n] = n;
        for (int i = n - 1; i >= 0; i--) {
            double best = Double.NEGATIVE_INFINITY;
            int bestX = i;
            for (Integer x : dag.get(i)) {
                String w = sentence.substring(i, x + 1);
                int f = freqMap.containsKey(w) ? freqMap.get(w) : 1;
                if (f <= 0) {
                    f = 1;
                }
                double s = (Math.log(f) - logTotal) + score[x + 1];
                if (s > best) {
                    best = s;
                    bestX = x;
                }
            }
            score[i] = best;
            end[i] = bestX;
        }
        int i = 0;
        while (i < n) {
            int x = end[i];
            result.add(new String[] { sentence.substring(i, x + 1),
                    Integer.toString(i), Integer.toString(x + 1) });
            i = x + 1;
        }
        return result;
    }
    /** 分词：返回词列表（无 HMM）。 */
    public List<String> segment(String sentence) {
        List<String> words = new ArrayList<String>();
        if (sentence == null || sentence.isEmpty()) {
            return words;
        }
        Map<Integer, List<Integer>> dag = buildDag(sentence);
        int n = sentence.length();
        double[] score = new double[n + 1];
        int[] end = new int[n + 1];
        score[n] = 0.0;
        end[n] = n;
        for (int i = n - 1; i >= 0; i--) {
            double best = Double.NEGATIVE_INFINITY;
            int bestX = i;
            for (Integer x : dag.get(i)) {
                String w = sentence.substring(i, x + 1);
                int f = freqMap.containsKey(w) ? freqMap.get(w) : 1;
                if (f <= 0) {
                    f = 1;
                }
                double s = (Math.log(f) - logTotal) + score[x + 1];
                if (s > best) {
                    best = s;
                    bestX = x;
                }
            }
            score[i] = best;
            end[i] = bestX;
        }
        int i = 0;
        while (i < n) {
            int x = end[i];
            words.add(sentence.substring(i, x + 1));
            i = x + 1;
        }
        return words;
    }
}