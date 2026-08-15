/*
 * BrailleSegmenter - 盲文分词规则引擎（Android 版）。
 * 与电脑验证版一致：书名号内连写 + 数字整体保护 + 专名 addWord + jieba DAG 分词
 * + 后附成分合并 + 四字词 2+2 语节分写 + 黑白名单。
 * 输出"带词间空格的文本"并给出 原始<->分段 位置映射（供 textToBraillePositions 修正）。
 */
package com.googlecode.eyesfree.braille.service.translate;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class BrailleSegmenter {
    private static final String LOG_TAG = "BrailleSegmenter";

    // 应分写的四字整体词（12.2.3 按词语分写）—— 从词典删除
    private static final String[] SPLIT4_ARR = {
        "风声鹤唳","草木皆兵","中华人民共和国","无缝钢管","劳动模范",
        "特殊教育词典","环境保护规划","生产关系","人民共和国"
    };
    // 应连写的词（12.2.1/2.2/2.4/2.5/2.6）—— 强制成词
    private static final String[] CONNECT_ARR = {
        "孩子们","艺术家","科学性","超声波","很好","山上","每年","对不起","一刀切",
        "中小学","图书馆","大红花","研究生院","高锰酸钾","古生物学家",
        "秋高气爽","山清水秀","风和日丽","兴高采烈","实事求是","脚踏实地",
        "层出不穷","鸟语花香","万紫千红","欣欣向荣","蒸蒸日上","络绎不绝",
        // 数理化: 分数连写 + 复合单位整体（电脑版已验证）
        "二分之一","三分之一","四分之一","五分之一","六分之一","七分之一","八分之一",
        "九分之一","十分之一","百分之一","千分之一","三分之二","四分之三","五分之二",
        "五分之三","五分之四","六分之五",
        "米每秒","千米每小时","克每升","千克每立方米","牛每米","焦耳每千克","安培每米",
        "伏特每米","弧度每秒","平方米每秒","千克每平方米","摩尔每升","瓦特每平方米"
    };
    // 分写内部词（12.2.3 分写后需要的内部双字词）
    private static final String[] SPLIT4_INNER_ARR = {
        "风声","鹤唳","草木","皆兵","中华","人民","共和国","劳动","模范","无缝","钢管"
    };
    // 专名词表（人名/音译/机构）—— 强制成词
    private static final String[] NAMES_ARR = {
        "塞维利亚","德沃夏克","勃拉姆斯","安塞尔米","忽必力","朱良春","卫鞅","汤姆生",
        "霍金","刘柏龄","侯彪","庄王","斯美塔那","哈森姆","维京古尔","卡达西亚","桑托斯",
        "博尔顿","史蒂文","舒马赫","硕贝德","汪美成","黄道亮","习近平","灵娃","埃特纳火山",
        "汤因比","马克思","恩格斯","列宁","斯大林","牛顿","爱因斯坦","莎士比亚","贝多芬",
        "莫扎特","达芬奇","亚里士多德","柏拉图","苏格拉底","汉斯利克","贾宝玉","林黛玉",
        "中国共产党","人民代表大会","无界点显器","无界点显器(HID)"
    };
    private static final String SUFFIX_STR = "们子儿头性者员家手化度法学论品";
    // 强虚词（4字结构分写，借鉴阳光读屏 rules.conf 虚词分写）—— 之/而/所/如/即/若/亦/俱/尚/犹/且/及
    private static final String FUNC_STR = "之而所如即若亦俱尚犹且及焉哉乎耳";
    // 化学元素表（118 常用元素，用于化学式校验）
    private static final String[] ELEMENTS_ARR = {
        "H","He","Li","Be","B","C","N","O","F","Ne","Na","Mg","Al","Si","P","S","Cl","Ar",
        "K","Ca","Sc","Ti","V","Cr","Mn","Fe","Co","Ni","Cu","Zn","Ga","Ge","As","Se","Br",
        "Kr","Rb","Sr","Y","Zr","Nb","Mo","Tc","Ru","Rh","Pd","Ag","Cd","In","Sn","Sb","Te",
        "I","Xe","Cs","Ba","La","Ce","Pr","Nd","Pm","Sm","Eu","Gd","Tb","Dy","Ho","Er","Tm",
        "Yb","Lu","Hf","Ta","W","Re","Os","Ir","Pt","Au","Hg","Tl","Pb","Bi","Po","At","Rn",
        "Fr","Ra","Ac","Th","Pa","U","Np","Pu","Am","Cm","Bk","Cf","Es","Fm","Md","No","Lr",
        "Rf","Db","Sg","Bh","Hs","Mt","Ds","Rg","Cn","Nh","Fl","Mc","Lv","Ts","Og"
    };

    private static final String PUNCT_STR =
            "，。、；：？！“”‘’（）《》〈〉—…··,.;:!?()[]{} ";

    private static final Set<String> SPLIT4 = new HashSet<String>(Arrays.asList(SPLIT4_ARR));
    private static final Set<String> CONNECT = new HashSet<String>(Arrays.asList(CONNECT_ARR));
    private static final Set<String> SUFFIX = new HashSet<String>();
    private static final Set<String> PUNCT = new HashSet<String>();
    private static final Set<String> NAMESET = new HashSet<String>(Arrays.asList(NAMES_ARR));
    private static final Set<String> SPLIT4_INNER =
            new HashSet<String>(Arrays.asList(SPLIT4_INNER_ARR));
    private static final Set<String> FUNC = new HashSet<String>();
    private static final Set<String> ELEMENTS =
            new HashSet<String>(Arrays.asList(ELEMENTS_ARR));

    private static final Pattern NUM_PAT = Pattern.compile(
            "(-?\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?%?|-?\\d+(?:\\.\\d+)?%?)");
    private static final Pattern BOOK_RE = Pattern.compile("(《[^》]*》)");
    private static final Pattern LETTER_RE = Pattern.compile("[A-Za-z]+");

    static {
        for (char c : SUFFIX_STR.toCharArray()) {
            SUFFIX.add(String.valueOf(c));
        }
        for (char c : PUNCT_STR.toCharArray()) {
            PUNCT.add(String.valueOf(c));
        }
        for (char c : FUNC_STR.toCharArray()) {
            FUNC.add(String.valueOf(c));
        }
    }

    private final JiebaDict dict;
    // 阳光读屏强制分写词库（对标：一丘之貉 -> 一丘 之 貉）
    private Map<String, String[]> splitDict = new HashMap<String, String[]>();

    public BrailleSegmenter(JiebaDict dict) {
        this.dict = dict;
        for (String w : SPLIT4) {
            dict.delWord(w);
        }
        for (String w : CONNECT) {
            dict.addWord(w, 100000);
        }
        for (String w : NAMES_ARR) {
            dict.addWord(w, 100000);
        }
        for (String w : SPLIT4_INNER) {
            dict.addWord(w, 100000);
        }
    }

    /** 加载阳光强制分写词库：每行 "词\t分1 分2 分3"。 */
    public synchronized void loadSplitDict(InputStream is) {
        if (is == null) {
            return;
        }
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int tab = line.indexOf('\t');
                if (tab <= 0) {
                    continue;
                }
                String word = line.substring(0, tab);
                String[] parts = line.substring(tab + 1).trim().split(" ");
                if (parts.length >= 2 && !splitDict.containsKey(word)) {
                    splitDict.put(word, parts);
                }
            }
            br.close();
        } catch (Exception e) {
            // 词库加载失败不阻塞主流程
        }
    }

    public static class Result {
        public String text;
        public int[] origToSeg;
        public int[] segToOrig;
    }

    private static class Tok {
        int start;
        int end;
        boolean punct;
        Tok(int s, int e, boolean p) {
            start = s;
            end = e;
            punct = p;
        }
    }

    private boolean isPunct(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (char c : s.toCharArray()) {
            if (!PUNCT.contains(String.valueOf(c))) {
                return false;
            }
        }
        return true;
    }

    /** 是否下标数字（₂₃₄...） */
    private boolean isSubscript(char c) {
        return (c >= 0x2080 && c <= 0x2089);
    }

    /** 校验是否为合法化学式（元素表 + 含数字下标）。 */
    private boolean isChemFormula(String s) {
        if (s == null || s.length() < 2) {
            return false;
        }
        if (!Character.isUpperCase(s.charAt(0))) {
            return false;
        }
        boolean hasDigit = false;
        int pos = 0;
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (c >= 'A' && c <= 'Z') {
                String el = String.valueOf(c);
                pos++;
                if (pos < s.length() && s.charAt(pos) >= 'a' && s.charAt(pos) <= 'z') {
                    el += s.charAt(pos);
                    pos++;
                }
                if (!ELEMENTS.contains(el)) {
                    return false;
                }
            } else if (c >= '0' && c <= '9') {
                hasDigit = true;
                pos++;
            } else if (isSubscript(c)) {
                hasDigit = true;
                pos++;
            } else {
                return false;
            }
        }
        return hasDigit;
    }

    /** 化学式保护：合并相邻 [字母][数字][字母]... token（如 H2O / CO2 / H2SO4）。 */
    private void mergeChemFormulas(List<Tok> toks, String text) {
        if (toks == null || toks.size() < 2) {
            return;
        }
        List<Tok> merged = new ArrayList<Tok>();
        int i = 0;
        while (i < toks.size()) {
            Tok t = toks.get(i);
            String ts = text.substring(t.start, t.end);
            if (ts.matches("[A-Za-z]+")) {
                StringBuilder sb = new StringBuilder(ts);
                int j = i + 1;
                while (j < toks.size()) {
                    Tok tj = toks.get(j);
                    String tjs = text.substring(tj.start, tj.end);
                    if (tjs.matches("[A-Za-z0-9\u2080-\u2089]+")) {
                        sb.append(tjs);
                        j++;
                    } else {
                        break;
                    }
                }
                if (j - i > 1 && isChemFormula(sb.toString())) {
                    merged.add(new Tok(t.start, toks.get(j - 1).end, false));
                    i = j;
                    continue;
                }
            }
            merged.add(t);
            i++;
        }
        toks.clear();
        toks.addAll(merged);
    }

    /** 普通段：数字分段后，非数字段走 jieba + 规则。 */
    private void addPlainTokens(List<Tok> toks, String text, int from, int to) {
        if (from >= to) {
            return;
        }
        String seg = text.substring(from, to);
        Matcher nm = NUM_PAT.matcher(seg);
        int last = 0;
        while (nm.find()) {
            addLetterTokens(toks, text, from + last, from + nm.start());
            toks.add(new Tok(from + nm.start(), from + nm.end(), false)); // 数字整体
            last = nm.end();
        }
        addLetterTokens(toks, text, from + last, to);
    }

    /** 连续 ASCII 字母串整体保护（BrailleBack/HID 等不逐字母切）。 */
    private void addLetterTokens(List<Tok> toks, String text, int from, int to) {
        if (from >= to) {
            return;
        }
        String seg = text.substring(from, to);
        Matcher lm = LETTER_RE.matcher(seg);
        int last = 0;
        while (lm.find()) {
            addJiebaTokens(toks, text, from + last, from + lm.start());
            toks.add(new Tok(from + lm.start(), from + lm.end(), false));
            last = lm.end();
        }
        addJiebaTokens(toks, text, from + last, to);
    }

    /** jieba 分词 + 附成分合并 + 2+2 分写。 */
    private void addJiebaTokens(List<Tok> toks, String text, int from, int to) {
        if (from >= to) {
            return;
        }
        String seg = text.substring(from, to);
        if (seg.isEmpty()) {
            return;
        }
        List<String[]> words = dict.segmentWithPos(seg);
        List<String[]> merged = new ArrayList<String[]>();
        for (String[] w : words) {
            String word = w[0];
            int ws = Integer.parseInt(w[1]);
            int we = Integer.parseInt(w[2]);
            // 阳光强制分写词库命中：直接按词库切分
            if (splitDict.containsKey(word) && word.length() >= 2) {
                String[] parts = splitDict.get(word);
                int pos = ws;
                for (String part : parts) {
                    int next = pos + part.length();
                    merged.add(new String[] { part, Integer.toString(pos),
                            Integer.toString(next) });
                    pos = next;
                }
                continue;
            }
            // 后附成分合并（孩子们 <- 孩子 + 们）
            if (word.length() == 1 && SUFFIX.contains(word) && !merged.isEmpty()
                    && !isPunct(merged.get(merged.size() - 1)[0])) {
                String[] prev = merged.get(merged.size() - 1);
                merged.set(merged.size() - 1,
                        new String[] { prev[0] + word, prev[1], Integer.toString(we) });
                continue;
            }
            // 数理化/虚词分写：4字结构中强虚词(之/而/所/如/即/若...)在第2/3位时独立成段
            if (word.length() == 4 && !CONNECT.contains(word) && !SPLIT4.contains(word)
                    && !NAMESET.contains(word)) {
                String c1 = word.substring(1, 2);
                String c2 = word.substring(2, 3);
                if (FUNC.contains(c1)) {
                    // 1+1+2：一丘之貉 -> 一丘 之 貉
                    merged.add(new String[] { word.substring(0, 1), Integer.toString(ws),
                            Integer.toString(ws + 1) });
                    merged.add(new String[] { c1, Integer.toString(ws + 1),
                            Integer.toString(ws + 2) });
                    merged.add(new String[] { word.substring(2, 4), Integer.toString(ws + 2),
                            Integer.toString(we) });
                    continue;
                }
                if (FUNC.contains(c2)) {
                    // 2+1+1：一挥而就 -> 一挥 而 就
                    merged.add(new String[] { word.substring(0, 2), Integer.toString(ws),
                            Integer.toString(ws + 2) });
                    merged.add(new String[] { c2, Integer.toString(ws + 2),
                            Integer.toString(ws + 3) });
                    merged.add(new String[] { word.substring(3, 4), Integer.toString(ws + 3),
                            Integer.toString(we) });
                    continue;
                }
            }
            // 四字词 2+2 语节分写（不在黑白名单/专名，且前2后2各自成词）
            if (word.length() == 4 && !CONNECT.contains(word) && !SPLIT4.contains(word)
                    && !NAMESET.contains(word) && !SPLIT4_INNER.contains(word)) {
                String a = word.substring(0, 2);
                String b = word.substring(2, 4);
                if (dict.isWord(a) && dict.isWord(b)) {
                    merged.add(new String[] { a, Integer.toString(ws),
                            Integer.toString(ws + 2) });
                    merged.add(new String[] { b, Integer.toString(ws + 2),
                            Integer.toString(we) });
                    continue;
                }
            }
            merged.add(w);
        }
        for (String[] w : merged) {
            int ws = from + Integer.parseInt(w[1]);
            int we = from + Integer.parseInt(w[2]);
            toks.add(new Tok(ws, we, isPunct(w[0])));
        }
    }

    /** 主入口：分词并生成带空格文本 + 位置映射。 */
    public Result segment(String text) {
        Result r = new Result();
        if (text == null || text.isEmpty()) {
            r.text = text == null ? "" : text;
            r.origToSeg = new int[0];
            r.segToOrig = new int[0];
            return r;
        }
        List<Tok> toks = new ArrayList<Tok>();
        Matcher bm = BOOK_RE.matcher(text);
        int last = 0;
        while (bm.find()) {
            addPlainTokens(toks, text, last, bm.start());
            // 《书名》 -> 《 + 书名(整体) + 》
            toks.add(new Tok(bm.start(), bm.start() + 1, true));   // 《
            toks.add(new Tok(bm.start() + 1, bm.end() - 1, false)); // 书名整体
            toks.add(new Tok(bm.end() - 1, bm.end(), true));        // 》
            last = bm.end();
        }
        addPlainTokens(toks, text, last, text.length());

        // 化学式保护：合并 H2O/CO2/H2SO4 等
        mergeChemFormulas(toks, text);

        // 拼装带空格文本 + 映射
        StringBuilder sb = new StringBuilder();
        int L = text.length();
        int[] origToSeg = new int[L];
        Arrays.fill(origToSeg, -1);
        boolean prevWord = false;
        for (Tok t : toks) {
            if (t.punct) {
                sb.append(text, t.start, t.end);
                prevWord = false;
            } else {
                if (prevWord) {
                    sb.append(' ');
                }
                for (int i = t.start; i < t.end; i++) {
                    sb.append(text.charAt(i));
                    origToSeg[i] = sb.length() - 1;
                }
                prevWord = true;
            }
        }
        // 未覆盖的原始字符（如孤立标点 token 之外的）兜底
        int M = sb.length();
        int[] segToOrig = new int[M];
        Arrays.fill(segToOrig, -1);
        for (int i = 0; i < L; i++) {
            int sp = origToSeg[i];
            if (sp >= 0) {
                segToOrig[sp] = i;
            }
        }
        // 空格位置映射到前一个原始字符；未映射位置兜底
        int lastOrig = -1;
        for (int i = 0; i < M; i++) {
            if (segToOrig[i] >= 0) {
                lastOrig = segToOrig[i];
            } else {
                segToOrig[i] = (lastOrig >= 0) ? lastOrig : 0;
            }
        }
        r.text = sb.toString();
        r.origToSeg = origToSeg;
        r.segToOrig = segToOrig;
        return r;
    }
}