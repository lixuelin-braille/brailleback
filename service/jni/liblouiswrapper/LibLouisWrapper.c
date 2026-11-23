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

#include <assert.h>
#include <stdlib.h>
#include <string.h>
#include <jni.h>
#include "alog.h"
#include "liblouis/liblouis.h"
#include "liblouis/internal.h"  // for MAXSTRING

#define LOG_TAG "LibLouisWrapper_Native"

// 诊断专用 tag：liblouis 自身的 error/warning 原文从这里转发到 logcat，
// 与上面这个 wrapper 自身的 tag 区分开，方便单独过滤。
#define NATIVE_LOG_TAG "LibLouisNative"

// 同一条完全相同的 liblouis 消息，进程内最多转发到 logcat 的次数；
// 超出后静默丢弃，避免加载坏表时同一条告警反复刷屏。
#define LIBLOUIS_DUP_MSG_MAX 3
// 失败诊断时回放的最近上下文消息条数（环形缓冲长度）。
#define LIBLOUIS_CONTEXT_SLOTS 8
// 单条消息在本地缓冲中的最大长度（含结尾 NUL）。
#define LIBLOUIS_MSG_BUF_LEN 512
// 去重表最多跟踪的不同消息条数。
#define LIBLOUIS_DEDUP_SLOTS 16

#define TRANSLATE_PACKAGE "com/googlecode/eyesfree/braille/translate/"

#define MAX(a, b) ((a) > (b) ? (a) : (b))

static jclass class_TranslationResult;
static jmethodID method_TranslationResult_ctor;
static jclass class_OutOfMemoryError;

// ---- liblouis 日志桥接：回调收集最近上下文，并对重复消息限流。 ----

typedef struct {
  int level;
  char text[LIBLOUIS_MSG_BUF_LEN];
} LouisLogEntry;

static LouisLogEntry g_contextRing[LIBLOUIS_CONTEXT_SLOTS];
static int g_contextHead = 0;   // 下一个写入槽位
static int g_contextCount = 0;  // 已填充条数（不超过 SLOTS）

typedef struct {
  char text[LIBLOUIS_MSG_BUF_LEN];
  int count;
} LouisDedupEntry;

static LouisDedupEntry g_dedup[LIBLOUIS_DEDUP_SLOTS];
static int g_dedupCount = 0;

static void
louCopyTruncated(char *dst, size_t dstLen, const char *src) {
  if (src == NULL || dstLen == 0) {
    return;
  }
  strncpy(dst, src, dstLen - 1);
  dst[dstLen - 1] = '\0';
}

static void
louCaptureContext(logLevels level, const char *message) {
  LouisLogEntry *slot = &g_contextRing[g_contextHead];
  slot->level = (int)level;
  louCopyTruncated(slot->text, sizeof(slot->text), message);
  g_contextHead = (g_contextHead + 1) % LIBLOUIS_CONTEXT_SLOTS;
  if (g_contextCount < LIBLOUIS_CONTEXT_SLOTS) {
    g_contextCount++;
  }
}

// 返回 1 表示该消息已超过单次进程内转发上限，应丢弃；0 表示可以转发。
static int
louShouldDropDuplicate(const char *message) {
  for (int i = 0; i < g_dedupCount; ++i) {
    if (strcmp(g_dedup[i].text, message) == 0) {
      g_dedup[i].count++;
      return g_dedup[i].count > LIBLOUIS_DUP_MSG_MAX;
    }
  }
  if (g_dedupCount < LIBLOUIS_DEDUP_SLOTS) {
    louCopyTruncated(g_dedup[g_dedupCount].text,
        sizeof(g_dedup[g_dedupCount].text), message);
    g_dedup[g_dedupCount].count = 1;
    g_dedupCount++;
  }
  // 去重表已满：不再跟踪新消息，照常转发（宁可多打，不漏诊断）。
  return 0;
}

static void
louLogCallback(logLevels level, const char *message) {
  if (message == NULL || message[0] == '\0') {
    return;
  }
  android_LogPriority prio;
  if (level >= LOU_LOG_ERROR) {
    prio = ANDROID_LOG_ERROR;
  } else if (level >= LOU_LOG_WARN) {
    prio = ANDROID_LOG_WARN;
  } else {
    // DEBUG/INFO 不转发，避免正常使用时刷屏。
    return;
  }

  louCaptureContext(level, message);
  if (louShouldDropDuplicate(message)) {
    return;
  }
  __android_log_print(prio, NATIVE_LOG_TAG, "%s", message);
}

static void
louInstallLogging(void) {
  // 只让 WARN/ERROR/FATAL 到达回调；INFO/DEBUG 在 liblouis 侧就丢掉。
  lou_setLogLevel(LOU_LOG_WARN);
  lou_registerLogCallback(louLogCallback);
}

// 仅在表加载失败路径调用：回放回调捕获到的最近若干条上下文。
static void
louDumpTableFailure(const char *tableName) {
  __android_log_print(ANDROID_LOG_ERROR, NATIVE_LOG_TAG,
      "checkTable failed: table='%s', lou_getTable returned NULL, liblouis %s",
      tableName != NULL ? tableName : "(null)", lou_version());
  if (g_contextCount == 0) {
    __android_log_print(ANDROID_LOG_ERROR, NATIVE_LOG_TAG,
        "checkTable failed: no liblouis warning/error was captured");
  } else {
    for (int i = 0; i < g_contextCount; ++i) {
      int idx = (g_contextHead - g_contextCount + i + LIBLOUIS_CONTEXT_SLOTS)
          % LIBLOUIS_CONTEXT_SLOTS;
      const LouisLogEntry *entry = &g_contextRing[idx];
      android_LogPriority prio = entry->level >= LOU_LOG_ERROR
          ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN;
      __android_log_print(prio, NATIVE_LOG_TAG, "context[%d/%d]: %s",
          i + 1, g_contextCount, entry->text);
    }
  }
  // 回放后清空上下文，避免下一次检查回放陈旧消息。
  g_contextHead = 0;
  g_contextCount = 0;
}

static jclass getGlobalClassRef(JNIEnv* env, const char *name);

jboolean
Java_com_googlecode_eyesfree_braille_service_translate_LibLouisWrapper_checkTableNative
(JNIEnv* env, jclass clazz, jstring tableName) {
  jboolean ret = JNI_FALSE;
  const jbyte *tableNameUtf8 = (*env)->GetStringUTFChars(env, tableName, NULL);
  if (lou_getTable(tableNameUtf8) == NULL) {
    // 仅失败路径：把表名与回调捕获到的最近上下文打进 logcat。成功路径不打。
    louDumpTableFailure((const char *)tableNameUtf8);
    goto out;
  }
  ret = JNI_TRUE;
 out:
  (*env)->ReleaseStringUTFChars(env, tableName, tableNameUtf8);
  return ret;
}

jobject
Java_com_googlecode_eyesfree_braille_service_translate_LibLouisWrapper_translateNative
(JNIEnv* env, jclass clazz, jstring text, jstring tableName,
 jint cursorPosition, jboolean computerBrailleAtCursor) {
  jobject ret = NULL;
  const jchar* textUtf16 = (*env)->GetStringChars(env, text, NULL);
  const jbyte* tableNameUtf8 = (*env)->GetStringUTFChars(env, tableName, NULL);

  int inlen = (*env)->GetStringLength(env, text);
  int* outputpos = malloc(sizeof(int) * inlen); // Maps char -> cell pos.

  int cursoroutpos;
  int* cursorposp = NULL;
  if (cursorPosition < 0) {
    cursoroutpos = -1;
  } else if (cursorPosition < inlen) {
    cursoroutpos = cursorPosition;
    cursorposp = &cursoroutpos;
  }

  // See <https://crrev.com/243251> for equivalent ChromeVox implementation.
  // Invoke liblouis.  Do this in a loop since we can't precalculate the
  // translated size.  We start with the min allocation size (8 jchars or 16
  // bytes); for a larger input length, we start at double the input length.
  // We also set an arbitrary upper bound for the allocation to make sure the
  // loop exits without running out of memory. For non-small input lengths, the
  // loop runs up to 4 times (inlen * 2, inlen * 4, inlen * 8, inlen * 16).
  int inused = 0;
  int outused = 0;
  jchar* outbuf = NULL;
  int* inputpos = NULL; // The oposite of outputpos: maps cell -> char pos.
  // Min buffer size is 8 jchars or 16 bytes.
  // For non-small values of inlen, the loop repeats up to 4 times (inlen * 2,
  // inlen * 4, inlen * 8, inlen * 16).
  for (int outlen = MAX(8, inlen * 2), maxoutlen = inlen * 16;
      outlen <= maxoutlen;
      outlen *= 2) {
    inused = inlen;
    outused = outlen;

    // realloc 失败时原指针仍然有效，所以用临时指针接收：
    // 既不把 NULL 传给 lou_translate（会段错误），也不丢掉原有内存块（交给 freebufs 释放）。
    jchar* new_outbuf = realloc(outbuf, sizeof(jchar) * outlen);
    int* new_inputpos = realloc(inputpos, sizeof(int) * outlen);
    if (new_outbuf == NULL || outputpos == NULL || new_inputpos == NULL) {
      free(new_outbuf);
      free(new_inputpos);
      (*env)->ThrowNew(env, class_OutOfMemoryError, NULL);
      goto freebufs;
    }
    outbuf = new_outbuf;
    inputpos = new_inputpos;

    int result = lou_translate(tableNameUtf8, textUtf16, &inused,
        outbuf, &outused,
        NULL /*typeform*/, NULL /*spacing*/,
        outputpos, inputpos, cursorposp,
        computerBrailleAtCursor ? compbrlAtCursor | dotsIO : dotsIO);
    if (result == 0) {
      LOGE("Translation failed.");
      goto freebufs;
    }

    // If all of inbuf was not consumed, the output buffer must be too small
    // and we have to retry with a larger buffer.
    // In addition, if all of outbuf was exhausted, there's no way to know if
    // more space was needed, so we'll have to retry the translation in that
    // corner case as well.
    if (inused == inlen && outused < outlen) {
      break;
    }
  }
  LOGV("Successfully translated %d characters to %d cells, "
       "consuming %d characters", (*env)->GetStringLength(env, text),
       outused, inused);
  jbyteArray cellsarray = (*env)->NewByteArray(env, outused);
  if (cellsarray == NULL) {
    goto freebufs;
  }
  jbyte* cells = (*env)->GetByteArrayElements(env, cellsarray, NULL);
  if (cells == NULL) {
    goto freebufs;
  }
  int i;
  for (i = 0; i < outused; ++i) {
    cells[i] = outbuf[i] & 0xff;
  }
  (*env)->ReleaseByteArrayElements(env, cellsarray, cells, 0);
  jintArray outputposarray = (*env)->NewIntArray(env, inlen);
  if (outputposarray == NULL) {
    goto freebufs;
  }
  (*env)->SetIntArrayRegion(env, outputposarray, 0, inlen, outputpos);
  jintArray inputposarray = (*env)->NewIntArray(env, outused);
  if (inputposarray == NULL) {
    goto freebufs;
  }
  (*env)->SetIntArrayRegion(env, inputposarray, 0, outused, inputpos);
  if (cursorposp == NULL && cursorPosition >= 0) {
    // The cursor position was past-the-end of the input, normalize to
    // past-the-end of the output.
    cursoroutpos = outused;
  }
  ret = (*env)->NewObject(
      env, class_TranslationResult, method_TranslationResult_ctor,
      cellsarray, outputposarray, inputposarray, cursoroutpos);

 freebufs:
  free(outbuf);
  free(inputpos);
  free(outputpos);
  (*env)->ReleaseStringChars(env, text, textUtf16);
  (*env)->ReleaseStringUTFChars(env, tableName, tableNameUtf8);
  return ret;
}

jstring
Java_com_googlecode_eyesfree_braille_service_translate_LibLouisWrapper_backTranslateNative
(JNIEnv* env, jclass clazz, jbyteArray cells, jstring tableName) {
  jstring ret = NULL;
  const jbyte* tableNameUtf8 = (*env)->GetStringUTFChars(env, tableName, NULL);
  if (!tableNameUtf8) {
    goto out;
  }

  int inlen = (*env)->GetArrayLength(env, cells);
  jbyte* cellsBytes = (*env)->GetByteArrayElements(env, cells, NULL);
  widechar* inbuf = malloc(sizeof(widechar) * inlen);
  int i;
  for (i = 0; i < inlen; ++i) {
    // Cast to avoid sign extension.
    inbuf[i] = ((unsigned char) cellsBytes[i]) | 0x8000;
  }
  (*env)->ReleaseByteArrayElements(env, cells, cellsBytes, JNI_ABORT);

  // See <https://crrev.com/254023> for equivalent ChromeVox implementation.
  // Invoke liblouis.  Do this in a loop since we can't precalculate the
  // translated size.  We start with the min allocation size (8 jchars or 16
  // bytes); for a larger input length, we start at double the input length.
  // We also set an arbitrary upper bound for the allocation to make sure the
  // loop exits without running out of memory. For non-small input lengths, the
  // loop runs up to 4 times (inlen * 2, inlen * 4, inlen * 8, inlen * 16).
  int inused = 0;
  int outused = 0;
  jchar* outbuf = NULL;
  for (int outlen = MAX(8, inlen * 2), maxoutlen = inlen * 16;
      outlen <= maxoutlen;
      outlen *= 2) {
    inused = inlen;
    outused = outlen;

    // 同上：realloc 失败时保留原指针交由 freebufs 释放，
    // 避免把 NULL 传给 lou_backTranslateString。
    jchar* new_outbuf = realloc(outbuf, sizeof(jchar) * outlen);
    if (new_outbuf == NULL) {
      (*env)->ThrowNew(env, class_OutOfMemoryError, NULL);
      goto freebufs;
    }
    outbuf = new_outbuf;

    int result = lou_backTranslateString(tableNameUtf8, inbuf, &inused,
        outbuf, &outused,
        NULL /*typeform*/, NULL /*spacing*/, dotsIO);
    if (result == 0) {
      LOGE("Back translation failed.");
      goto freebufs;
    }

    // If all of inbuf was not consumed, the output buffer must be too small
    // and we have to retry with a larger buffer.
    // In addition, if all of outbuf was exhausted, there's no way to know if
    // more space was needed, so we'll have to retry the translation in that
    // corner case as well.
    // Example: 0x1f -> "quite"; we initially allocate space for 4 chars, but
    // we need 5. After lou_backTranslateString, inused = 1 and outused = 4.
    // So it appears that the translation finished, but we're missing a char.
    if (inused == inlen && outused < outlen) {
      break;
    }
  }
  LOGV("Successfully translated %d cells into %d characters, "
       "consuming %d cells", (*env)->GetArrayLength(env, cells),
       outused, inused);
  ret = (*env)->NewString(env, outbuf, outused);
 freebufs:
  free(inbuf);
  free(outbuf);
  (*env)->ReleaseStringUTFChars(env, tableName, tableNameUtf8);
 out:
  return ret;
}

void
Java_com_googlecode_eyesfree_braille_service_translate_LibLouisWrapper_setTablesDirNative
(JNIEnv* env, jclass clazz, jstring path) {
  // liblouis has a static buffer, which we don't want to overflow.
  if ((*env)->GetStringUTFLength(env, path) >= MAXSTRING) {
    LOGE("Braille table path too long");
    return;
  }
  const jbyte* pathUtf8 = (*env)->GetStringUTFChars(env, path, NULL);
  if (!pathUtf8) {
    return;
  }
  // The path gets copied.
  // Cast needed to get rid of const.
  LOGV("Setting tables path to: %s", pathUtf8);
  lou_setDataPath((char*)pathUtf8);
  (*env)->ReleaseStringUTFChars(env, path, pathUtf8);
}

void
Java_com_googlecode_eyesfree_braille_service_translate_LibLouisWrapper_classInitNative(
    JNIEnv* env, jclass clazz) {
  // 先装好 liblouis 日志回调，后续任何表加载/翻译的告警都能进 logcat。
  louInstallLogging();
  if (!(class_TranslationResult = getGlobalClassRef(env,
          TRANSLATE_PACKAGE "TranslationResult"))) {
    return;
  }
  if (!(method_TranslationResult_ctor = (*env)->GetMethodID(
          env, class_TranslationResult, "<init>", "([B[I[II)V"))) {
    return;
  }
  if (!(class_OutOfMemoryError =
        getGlobalClassRef(env, "java/lang/OutOfMemoryError"))) {
    return;
  }
}

static jclass
getGlobalClassRef(JNIEnv* env, const char *name) {
  jclass localRef = (*env)->FindClass(env, name);
  if (!localRef) {
    LOGE("Couldn't find class %s", name);
    return NULL;
  }
  jclass globalRef = (*env)->NewGlobalRef(env, localRef);
  if (globalRef == NULL) {
    LOGE("Couldn't create global ref for class %s", name);
  }
  return globalRef;
}
