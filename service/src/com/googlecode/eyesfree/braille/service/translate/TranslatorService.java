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
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import com.googlecode.eyesfree.braille.service.R;
import com.googlecode.eyesfree.braille.translate.ITranslatorService;
import com.googlecode.eyesfree.braille.translate.ITranslatorServiceCallback;
import com.googlecode.eyesfree.braille.translate.TableInfo;
import com.googlecode.eyesfree.braille.translate.TranslationResult;
import com.googlecode.eyesfree.braille.translate.TranslatorClient;
import com.googlecode.eyesfree.braille.utils.ZipResourceExtractor;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
    private static final String SEG_TAG = "BrailleSegmenter";

    @Override
    public void onCreate() {
        super.onCreate();
        mTableList = new TableList(getResources());
        extractDataFiles();
        startSegmenterInit();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // TODO: Deallocate native data.
    }

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
                        try { is.close(); } catch (Exception e) {}
                    }
                    if (mJiebaDict.isInitialized()) {
                        mSegmenter = new BrailleSegmenter(mJiebaDict);
                        // 加载阳光强制分写词库
                        try {
                            InputStream si = getAssets().open("sunshine_split.txt");
                            mSegmenter.loadSplitDict(si);
                            if (si != null) { try { si.close(); } catch (Exception e) {} }
                            Log.i(LOG_TAG, "Split dict loaded");
                        } catch (Exception e) {
                            Log.w(LOG_TAG, "Split dict load failed", e);
                        }
                        mSegmenterReady = true;
                        Log.i(LOG_TAG, "Braille segmenter ready");
                    }
                } catch (Exception e) {
                    Log.e(LOG_TAG, "Segmenter init failed", e);
                }
            }
        }, "BrailleSegmenterInit");
        t.start();
    }

    private boolean isChineseTable(String tableName) {
        if (tableName == null) {
            return false;
        }
        String n = tableName.toLowerCase();
        return n.startsWith("zh");
    }

    private TranslationResult translateWithSegment(String text, String tableName,
            int cursorPosition, boolean computerBrailleAtCursor) {
        try {
            BrailleSegmenter.Result seg = mSegmenter.segment(text);
            Log.d("SegDebug", "IN : [" + text + "] L=" + text.length());
            Log.d("SegDebug", "OUT: [" + seg.text + "] segL=" + seg.text.length());
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

    private void extractDataFiles() {
        File tablesDir = getDir("translator", MODE_PRIVATE);
        LibLouisWrapper.setTablesDir(tablesDir.getPath());
        ZipResourceExtractor extractor = new ZipResourceExtractor(
            this, R.raw.translationtables, tablesDir) {
            @Override
            protected void onPostExecute(Integer result) {
                synchronized (TranslatorService.this) {
                    if (result == RESULT_OK) {
                        mDataFileState = FILES_EXTRACTED;
                    } else {
                        Log.e(LOG_TAG, "Couldn't extract data files");
                        mDataFileState = FILES_ERROR;
                    }
                }
                callPendingOnInits();
            }
        };
        extractor.execute();
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
                return;
            }
        }
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
            if (mSegmenterReady && isChineseTable(tableName)) {
                return translateWithSegment(text, tableName, cursorPosition,
                        computerBrailleAtCursor);
            }
            return LibLouisWrapper.translate(text, tableName, cursorPosition,
                    computerBrailleAtCursor);
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
