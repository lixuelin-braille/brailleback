/*
 * Copyright (C) 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.googlecode.eyesfree.braille.service.translate;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.util.Log;
import com.googlecode.eyesfree.braille.service.R;
import com.googlecode.eyesfree.braille.translate.ITranslatorService;
import com.googlecode.eyesfree.braille.translate.ITranslatorServiceCallback;
import com.googlecode.eyesfree.braille.translate.TableInfo;
import com.googlecode.eyesfree.braille.translate.TranslationResult;
import com.googlecode.eyesfree.braille.translate.TranslatorClient;
import com.googlecode.eyesfree.braille.utils.ZipResourceExtractor;
import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import timber.log.Timber;

/**
 * The service for the {@link TranslatorClient} client.
 */
public class TranslatorService extends Service {
    private static final String LOG_TAG =
            TranslatorService.class.getSimpleName();

    private static final int FILES_ERROR = -1;
    private static final int FILES_NOT_EXTRACTED = 0;
    private static final int FILES_EXTRACTED = 1;

    // Written in main thread, read in binder threads.
    private final ServiceImpl mServiceImpl = new ServiceImpl();
    private final Set<ITranslatorServiceCallback> mPendingCallbacks =
            new HashSet<ITranslatorServiceCallback>();
    private int mDataFileState = FILES_NOT_EXTRACTED;
    private TableList mTableList;
    private JiebaDict mJiebaDict;
    private BrailleSegmenter mSegmenter;
    private volatile boolean mSegmenterReady = false;
    /** 分词器初始化彻底失败（词典缺失等）：此后不再等它，直接走无分词兜底。 */
    private volatile boolean mSegmenterInitFailed = false;
    /**
     * 长文本翻译等分词器就绪的上限。冷启动服务第一次翻书时词典还在加载，
     * 早先直接走 liblouis，等于整段整段地「按字出盲文」，用户读到的头几页就是
     * 没有分词的（真机日志：'zhcn-cbs.ctb' translated WITHOUT segmentation）。
     * 长文本宁可等几秒也要出分词结果；短文本（界面标签/播报）不等，免得拖慢读屏。
     */
    private static final long SEGMENTER_WAIT_MS = 8000L;
    /** 只打前若干段落的分词诊断日志，避免整本书刷屏。 */
    private volatile int mSegLogCount = 0;
    // Logged once so the log is not flooded by repeated translate() calls
    // while the segmenter is still loading.
    private volatile boolean mWarnedUnsegmented = false;
    private static final String SEG_TAG = "BrailleSegmenter";
    /** 用户分词词库文件名，放在应用私有目录下，重启服务后生效。 */
    private static final String USER_SEG_FILE = "user_seg.txt";
    /** Whether word segmentation is applied for Chinese tables. Controlled by
     * the "chinese_segmentation" preference; defaults to on. */
    private volatile boolean mChineseSegmentationEnabled = true;

    @Override
    public void onCreate() {
        super.onCreate();
        mTableList = new TableList(getResources());
        extractDataFiles();
        SharedPreferences prefs =
                PreferenceManager.getDefaultSharedPreferences(this);
        mChineseSegmentationEnabled = prefs.getBoolean("chinese_segmentation", true);
        prefs.registerOnSharedPreferenceChangeListener(mSegPrefListener);
        startSegmenterInit();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        PreferenceManager.getDefaultSharedPreferences(this)
                .unregisterOnSharedPreferenceChangeListener(mSegPrefListener);
        // TODO: Deallocate native data.
    }

    private final SharedPreferences.OnSharedPreferenceChangeListener
            mSegPrefListener =
        new SharedPreferences.OnSharedPreferenceChangeListener() {
            @Override
            public void onSharedPreferenceChanged(
                    SharedPreferences prefs, String key) {
                if ("chinese_segmentation".equals(key)) {
                    mChineseSegmentationEnabled = prefs.getBoolean(key, true);
                }
            }
        };

    @Override
    public IBinder onBind(Intent intent) {
        return mServiceImpl;
    }

    private void startSegmenterInit() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    mJiebaDict = new JiebaDict();
                    InputStream is = getAssets().open("jiebadt.txt");
                    mJiebaDict.init(is);
                    if (is != null) {
                        try {
                            is.close();
                        } catch (Exception e) {
                            Log.w(LOG_TAG, "Failed to close jieba dict stream", e);
                        }
                    }
                    if (mJiebaDict.isInitialized()) {
                        // 加载阳光读屏补充词库（主词典缺失的低频词，不覆盖主词典）
                        try {
                            InputStream ss = getAssets().open("sunshine_supplement.txt");
                            try {
                                int added = mJiebaDict.loadSupplement(ss);
                                Log.i(LOG_TAG, "Supplement dict loaded, added=" + added);
                            } finally {
                                try {
                                    ss.close();
                                } catch (Exception e) {
                                    Log.w(LOG_TAG, "Failed to close supplement dict stream", e);
                                }
                            }
                        } catch (Exception e) {
                            Log.w(LOG_TAG, "Supplement dict load failed", e);
                        }
                        // 加载高优先级覆盖词表（专名/领域词 + 主词典词频纠正）
                        try {
                            InputStream os = getAssets().open("zh_segment_overrides.txt");
                            try {
                                int applied = mJiebaDict.loadOverrides(os);
                                Log.i(LOG_TAG, "Segment overrides loaded, count=" + applied);
                            } finally {
                                try {
                                    os.close();
                                } catch (Exception e) {
                                    Log.w(LOG_TAG, "Failed to close override stream", e);
                                }
                            }
                        } catch (Exception e) {
                            Log.w(LOG_TAG, "Segment overrides load failed", e);
                        }
                        // 加载 HMM 发射概率表（未登录词识别）。失败只告警，退回纯 DAG。
                        try {
                            InputStream hs = getAssets().open("hmm_emit.txt");
                            try {
                                int chars = mJiebaDict.loadHmm(hs);
                                Log.i(LOG_TAG, "HMM emit table loaded, chars=" + chars);
                            } finally {
                                try {
                                    hs.close();
                                } catch (Exception e) {
                                    Log.w(LOG_TAG, "Failed to close hmm emit stream", e);
                                }
                            }
                        } catch (Exception e) {
                            Log.w(LOG_TAG, "HMM emit table load failed, fallback to DAG", e);
                        }
                        mSegmenter = new BrailleSegmenter(mJiebaDict);
                        // 加载阳光强制分写词库
                        try {
                            InputStream si = getAssets().open("sunshine_split.txt");
                            mSegmenter.loadSplitDict(si);
                            if (si != null) {
                                try {
                                    si.close();
                                } catch (Exception e) {
                                    Log.w(LOG_TAG, "Failed to close split dict stream", e);
                                }
                            }
                            Log.i(LOG_TAG, "Split dict loaded");
                        } catch (Exception e) {
                            Log.w(LOG_TAG, "Split dict load failed", e);
                        }
                        // 规范连写词表（成语/三音节整体/词典词）：优先级高于阳光分写库。
                        try {
                            InputStream ci = getAssets().open("segment_connect.txt");
                            try {
                                int added = mSegmenter.loadConnectExtra(ci);
                                Log.i(LOG_TAG, "Connect dict loaded, added=" + added);
                            } finally {
                                try {
                                    ci.close();
                                } catch (Exception e) {
                                    Log.w(LOG_TAG, "Failed to close connect dict stream", e);
                                }
                            }
                        } catch (Exception e) {
                            Log.w(LOG_TAG, "Connect dict load failed", e);
                        }
                        // liblouis 多音字词组：避免词间空格拆散 always 规则
                        try {
                            InputStream lp = getAssets().open("liblouis_phrases.txt");
                            try {
                                int added = mSegmenter.loadProtectedPhrases(lp);
                                Log.i(LOG_TAG, "Liblouis phrase protection loaded, added=" + added);
                            } finally {
                                try {
                                    lp.close();
                                } catch (Exception e) {
                                    Log.w(LOG_TAG, "Failed to close liblouis phrase stream", e);
                                }
                            }
                        } catch (Exception e) {
                            Log.w(LOG_TAG, "Liblouis phrase protection load failed", e);
                        }
                        // 用户分词词库（可选）：外部私有目录优先，其次内部私有目录。
                        loadUserSegDict();
                        mSegmenterReady = true;
                        Log.i(LOG_TAG, "Braille segmenter ready");
                    } else {
                        // 词典没初始化起来：不会再有 ready，别让每次长文本都白等。
                        mSegmenterInitFailed = true;
                        Log.e(LOG_TAG, "Jieba dict not initialised, segmentation unavailable");
                    }
                } catch (Exception e) {
                    Log.e(LOG_TAG, "Segmenter init failed", e);
                    mSegmenterInitFailed = true;
                }
            }
        }, "BrailleSegmenterInit");
        t.start();
    }

    /**
     * 加载可选的用户分词词库。文件不存在时静默跳过；格式为每行
     * "词面\t分片1 分片2"，单字段行表示整体连写。
     */
    private void loadUserSegDict() {
        File externalDir = getExternalFilesDir(null);
        File external = externalDir == null ? null : new File(externalDir, USER_SEG_FILE);
        File internal = new File(getFilesDir(), USER_SEG_FILE);
        File source = external != null && external.isFile() ? external
                : (internal.isFile() ? internal : null);
        if (source == null) {
            return;
        }
        try (InputStream in = new FileInputStream(source)) {
            int applied = mSegmenter.loadUserSegDict(in);
            Timber.tag(SEG_TAG).i("User segment dict loaded: %s (%d entries)",
                    source.getAbsolutePath(), applied);
        } catch (Exception e) {
            Timber.tag(SEG_TAG).w(e, "User segment dict load failed: %s",
                    source.getAbsolutePath());
        }
    }

    private boolean isChineseTable(String tableName) {
        if (tableName == null) {
            return false;
        }
        String n = tableName.toLowerCase(Locale.ROOT);
        return n.startsWith("zh");
    }

    /** 头 n 个点位的十进制值，逗号分隔；0 = 空方。仅用于分词诊断日志。 */
    private String cellHead(byte[] cells, int n) {
        if (cells == null) return "null";
        StringBuilder sb = new StringBuilder();
        int end = Math.min(cells.length, n);
        for (int i = 0; i < end; i++) {
            if (i > 0) sb.append(',');
            sb.append(cells[i]);
        }
        return sb.toString();
    }

    private TranslationResult translateWithSegment(String text, String tableName,
            int cursorPosition, boolean computerBrailleAtCursor) {
        try {
            BrailleSegmenter.Result seg = mSegmenter.segment(text);
            int segCursor = cursorPosition;
            if (cursorPosition >= 0 && cursorPosition < seg.origToSeg.length) {
                segCursor = seg.origToSeg[cursorPosition];
            } else if (cursorPosition >= text.length()) {
                segCursor = seg.text.length();
            }
            TranslationResult tr = LibLouisWrapper.translate(seg.text, tableName,
                    segCursor, computerBrailleAtCursor);
            if (tr == null) {
                return null;
            }
            if (mSegLogCount < 20) {
                // 分词前后的长度、空格数、盲文方数，以及头几个点位的十进制值
                // （0 表示空方，用来确证「分词」真的在输出里留下了词间空格）。
                mSegLogCount++;
                Log.d(LOG_TAG, "segment: in=" + text.length() + " out="
                        + seg.text.length() + " spaces="
                        + Math.max(0, seg.text.length() - text.length())
                        + " cells=" + tr.getCells().length
                        + " head=" + cellHead(tr.getCells(), 36));
            }
            int[] ttb = tr.getTextToBraillePositions();
            int[] btt = tr.getBrailleToTextPositions();
            if (ttb == null || btt == null) {
                return tr;
            }
            int L = text.length();
            int[] newTtb = new int[L];
            for (int i = 0; i < L; i++) {
                int sp = (i < seg.origToSeg.length) ? seg.origToSeg[i] : -1;
                newTtb[i] = (sp >= 0 && sp < ttb.length) ? ttb[sp]
                        : (ttb.length > 0 ? ttb[ttb.length - 1] : 0);
            }
            int[] newBtt = new int[btt.length];
            for (int c = 0; c < btt.length; c++) {
                int sp = btt[c];
                newBtt[c] = (sp >= 0 && sp < seg.segToOrig.length)
                        ? seg.segToOrig[sp] : 0;
            }
            return new TranslationResult(tr.getCells(), newTtb, newBtt,
                    tr.getCursorPosition());
        } catch (Exception e) {
            Log.e(LOG_TAG, "Segment translate failed, fallback", e);
            return LibLouisWrapper.translate(text, tableName, cursorPosition,
                    computerBrailleAtCursor);
        }
    }

    /** Returns whether {@code c} is a Unicode braille pattern character
     * (U+2800-U+28FF).  Such characters directly encode the dots of one
     * braille cell, so they must be passed through to the display verbatim
     * instead of being re-translated as ordinary text (which the braille
     * tables have no rule for and would corrupt). */
    private static boolean isBrailleCellChar(char c) {
        return c >= 0x2800 && c <= 0x28FF;
    }

    private static boolean containsBrailleUnicode(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (isBrailleCellChar(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Translates text that may contain Unicode braille pattern characters.
     * Ordinary runs are translated with the configured table (using the
     * segmenter for Chinese tables); runs of braille pattern characters are
     * mapped one-to-one to their dot cells ({@code codepoint & 0xFF}), so a
     * standard electronic braille document displays correctly on the braille
     * display instead of being re-translated and scrambled.
     */
    private TranslationResult translateWithBraillePassThrough(String text,
            String tableName, int cursorPosition,
            boolean computerBrailleAtCursor) {
        int len = text.length();
        ByteArrayOutputStream cellBuf = new ByteArrayOutputStream();
        int[] ttb = new int[len];
        for (int i = 0; i < len; i++) {
            ttb[i] = -1;
        }
        List<Integer> btt = new ArrayList<Integer>();
        int cellOffset = 0;
        int i = 0;
        while (i < len) {
            int start = i;
            boolean braille = isBrailleCellChar(text.charAt(i));
            while (i < len && isBrailleCellChar(text.charAt(i)) == braille) {
                i++;
            }
            String run = text.substring(start, i);
            if (braille) {
                int n = run.length();
                for (int k = 0; k < n; k++) {
                    int cell = run.charAt(k) & 0xFF;
                    cellBuf.write(cell);
                    ttb[start + k] = cellOffset + k;
                    btt.add(start + k);
                }
                cellOffset += n;
            } else {
                TranslationResult sub;
                if (mChineseSegmentationEnabled && mSegmenterReady
                        && isChineseTable(tableName)) {
                    sub = translateWithSegment(run, tableName, -1,
                            computerBrailleAtCursor);
                } else {
                    sub = LibLouisWrapper.translate(run, tableName, -1,
                            computerBrailleAtCursor);
                }
                if (sub == null) {
                    return null;
                }
                byte[] subCells = sub.getCells();
                int[] subTtb = sub.getTextToBraillePositions();
                int[] subBtt = sub.getBrailleToTextPositions();
                for (int k = 0; k < subCells.length; k++) {
                    cellBuf.write(subCells[k]);
                    int st = (subBtt != null && k < subBtt.length
                            && subBtt[k] >= 0) ? subBtt[k] : k;
                    btt.add(start + st);
                }
                for (int k = 0; k < run.length(); k++) {
                    int sc = (subTtb != null && k < subTtb.length
                            && subTtb[k] >= 0) ? subTtb[k]
                            : (k < subCells.length ? k : subCells.length);
                    ttb[start + k] = cellOffset + sc;
                }
                cellOffset += subCells.length;
            }
        }
        byte[] cells = cellBuf.toByteArray();
        int[] bttArr = new int[btt.size()];
        for (int k = 0; k < bttArr.length; k++) {
            bttArr[k] = btt.get(k);
        }
        int cursor = -1;
        if (cursorPosition >= 0) {
            if (cursorPosition < len && ttb[cursorPosition] >= 0) {
                cursor = ttb[cursorPosition];
            } else if (cursorPosition >= len) {
                cursor = cellOffset;
            }
        }
        return new TranslationResult(cells, ttb, bttArr, cursor);
    }

    private void extractDataFiles() {
        File tablesDir = getDir("translator", MODE_PRIVATE);
        LibLouisWrapper.setTablesDir(tablesDir.getPath());
        new TranslatorDataFileExtractor(this, tablesDir).execute();
    }

    private static class TranslatorDataFileExtractor extends ZipResourceExtractor {
        private final WeakReference<TranslatorService> mService;

        TranslatorDataFileExtractor(TranslatorService service, File tablesDir) {
            super(service.getApplicationContext(), R.raw.translationtables, tablesDir);
            mService = new WeakReference<>(service);
        }

        @Override
        protected void onPostExecute(Integer result) {
            TranslatorService service = mService.get();
            if (service == null) {
                Log.w(LOG_TAG, "onPostExecute: service already gone, result=" + result);
                return;
            }
            Log.i(LOG_TAG, "onPostExecute: extractor result=" + result);
            synchronized (service) {
                if (result == RESULT_OK) {
                    service.mDataFileState = FILES_EXTRACTED;
                } else {
                    Log.e(LOG_TAG, "Couldn't extract data files");
                    service.mDataFileState = FILES_ERROR;
                }
            }
            service.callPendingOnInits();
        }
    }

    private synchronized boolean checkDataFiles() {
        return (mDataFileState == FILES_EXTRACTED);
    }

    private void callOnInit(ITranslatorServiceCallback callback,
            int dataFileState) {
        try {
            callback.onInit(dataFileState == FILES_EXTRACTED
                    ? TranslatorClient.SUCCESS
                    : TranslatorClient.ERROR);
        } catch (RemoteException ex) {
            // The client died before we initialized.  This is rare and
            // harmless for us.
        }
    }

    private void setCallback(ITranslatorServiceCallback callback) {
        int dataFileState;
        synchronized (this) {
            dataFileState = mDataFileState;
            if (dataFileState == FILES_NOT_EXTRACTED) {
                mPendingCallbacks.add(callback);
                Log.i(LOG_TAG, "setCallback: pending, extraction in flight (pending="
                        + mPendingCallbacks.size() + ")");
                return;
            }
        }
        Log.i(LOG_TAG, "setCallback: immediate onInit, state=" + dataFileState);
        callOnInit(callback, dataFileState);
    }

    private void callPendingOnInits() {
        Collection<ITranslatorServiceCallback> pendingCallbacks;
        int dataFileState;
        synchronized (this) {
            pendingCallbacks = new ArrayList<ITranslatorServiceCallback>(
                    mPendingCallbacks);
            dataFileState = mDataFileState;
            mPendingCallbacks.clear();
        }
        Log.i(LOG_TAG, "callPendingOnInits: pending=" + pendingCallbacks.size()
                + " state=" + dataFileState);

        for (ITranslatorServiceCallback callback : pendingCallbacks) {
            callOnInit(callback, dataFileState);
        }
    }

    private class ServiceImpl extends ITranslatorService.Stub {

        @Override
        public void setCallback(ITranslatorServiceCallback callback) {
            if (callback == null) {
                Log.e(LOG_TAG, "Received null callback");
                return;
            }
            TranslatorService.this.setCallback(callback);
        }

        @Override
        public TableInfo[] getTableInfos() {
            List<TableInfo> l = mTableList.getTables();
            return l.toArray(new TableInfo[l.size()]);
        }

        @Override
        public boolean checkTable(String tableId) {
            if (tableId == null) {
                Log.e(LOG_TAG, "Received null table id in checkTable");
                return false;
            }
            if (!checkDataFiles()) {
                return false;
            }
            String tableName = mTableList.getFileName(tableId);
            if (tableName == null) {
                Log.e(LOG_TAG, "Unknown table id in checkTable: " + tableId);
                return false;
            }
            return LibLouisWrapper.checkTable(tableName);
        }

        @Override
        public TranslationResult translate(String text, String tableId,
                int cursorPosition, boolean computerBrailleAtCursor) {
            if (text == null) {
                Log.e(LOG_TAG, "Received null text in translate");
                return null;
            }
            if (tableId == null) {
                Log.e(LOG_TAG, "Received null table name in translate");
                return null;
            }
            if (!checkDataFiles()) {
                return null;
            }
            String tableName = mTableList.getFileName(tableId);
            if (tableName == null) {
                Log.e(LOG_TAG, "Unknown table id in translate: " + tableId);
                return null;
            }
            // Pass Unicode braille pattern characters (U+2800-U+28FF) through
            // verbatim: each such character already encodes the dots of one
            // cell, and re-translating it as ordinary text corrupts the
            // display of standard electronic braille documents.
            if (containsBrailleUnicode(text)) {
                TranslationResult passThrough = translateWithBraillePassThrough(
                        text, tableName, cursorPosition, computerBrailleAtCursor);
                if (passThrough != null) {
                    return passThrough;
                }
            }
            if (mChineseSegmentationEnabled && mSegmenterReady
                    && isChineseTable(tableName)) {
                return translateWithSegment(text, tableName, cursorPosition,
                        computerBrailleAtCursor);
            }
            // 分词器还在加载（服务刚起来那几秒）：先等它就绪，别把这段文字按
            // 「一字一方」翻出去——阅读器最先上屏、用户最先读到的就是这几页，
            // 一旦漏分词，整本书的开头听起来就是没有分词的。
            // 任何长度的中文文本都等：短段落（小说里很多）同样要分词；
            // 读屏标签在这段时间最多多等几百毫秒到几秒，但结果是对的。
            if (mChineseSegmentationEnabled && isChineseTable(tableName)
                    && !mSegmenterReady && !mSegmenterInitFailed) {
                if (awaitSegmenter()) {
                    return translateWithSegment(text, tableName, cursorPosition,
                            computerBrailleAtCursor);
                }
            }
            if (mChineseSegmentationEnabled && isChineseTable(tableName)
                    && !mSegmenterReady && !mWarnedUnsegmented) {
                // Chinese table but the segmenter is not up yet (dictionary
                // still loading, or initialization failed).  Falling back to
                // liblouis means NO word segmentation, so the braille degrades
                // to per-character output.  This used to be completely silent;
                // make it visible so the cause is diagnosable.
                mWarnedUnsegmented = true;
                Log.w(LOG_TAG, "Chinese table '" + tableName
                        + "' translated WITHOUT segmentation"
                        + " (segmenter not ready yet)");
            }
            return LibLouisWrapper.translate(text, tableName, cursorPosition,
                    computerBrailleAtCursor);
        }

        /**
         * 等分词器就绪，最多 [SEGMENTER_WAIT_MS]。
         *
         * 只在分词器初始化线程里短暂等待，调用方（阅读器翻译线程）本来就是后台线程；
         * 超时或初始化失败返回 false，调用方继续走无分词兜底，不让调用卡死。
         */
        private boolean awaitSegmenter() {
            long deadline = System.currentTimeMillis() + SEGMENTER_WAIT_MS;
            while (System.currentTimeMillis() < deadline) {
                if (mSegmenterReady) {
                    Log.i(LOG_TAG, "waited for segmenter before translating");
                    return true;
                }
                if (mSegmenterInitFailed) return false;
                try {
                    Thread.sleep(20L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            Log.w(LOG_TAG, "segmenter still not ready after " + SEGMENTER_WAIT_MS + "ms");
            return false;
        }

        @Override
        public String backTranslate(byte[] cells, String tableId) {
            if (cells == null) {
                Log.e(LOG_TAG, "Received null text in backTranslate");
                return null;
            }
            if (tableId == null) {
                Log.e(LOG_TAG, "Received null table name in translate");
                return null;
            }
            if (!checkDataFiles()) {
                return null;
            }
            String tableName = mTableList.getFileName(tableId);
            if (tableName == null) {
                Log.e(LOG_TAG, "Unknown table id in backTranslate: "
                        + tableId);
                return null;
            }
            return LibLouisWrapper.backTranslate(cells, tableName);
        }
    }
}
