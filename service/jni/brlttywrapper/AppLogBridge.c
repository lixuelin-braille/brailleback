/*
 * Bridges brltty's native log records into the host application's log file.
 *
 * brltty writes every record through logMessage(), which on Android also
 * writes to logcat.  logcat is not part of the in-app log export, so the
 * records are forwarded to a Java sink as well.
 */

#include <stdarg.h>
#include <stdio.h>

#include <android/log.h>

#include "AppLogBridge.h"
#include "third_party/brltty/Headers/system_java.h"

#define APP_LOG_CLASS \
  "com/googlecode/eyesfree/braille/service/display/BrlttyNativeLog"
#define APP_LOG_MAX_RECORD 2048

static jclass appLogClass = NULL;
static jmethodID appLogMethod = NULL;

void
brlttyAppLogInit (JNIEnv *env) {
  if (appLogClass) return;

  jclass cls = (*env)->FindClass(env, APP_LOG_CLASS);
  if (!cls) {
    (*env)->ExceptionClear(env);
    __android_log_print(ANDROID_LOG_WARN, "BrlttyAppLog",
                        "sink class not found: %s", APP_LOG_CLASS);
    return;
  }

  jmethodID method = (*env)->GetStaticMethodID(env, cls, "log",
                                               "(ILjava/lang/String;)V");
  if (!method) {
    (*env)->ExceptionClear(env);
    (*env)->DeleteLocalRef(env, cls);
    __android_log_print(ANDROID_LOG_WARN, "BrlttyAppLog",
                        "sink method not found on %s", APP_LOG_CLASS);
    return;
  }

  appLogClass = (jclass) (*env)->NewGlobalRef(env, cls);
  appLogMethod = method;
  (*env)->DeleteLocalRef(env, cls);
}

void
brlttyAppLog (int priority, const char *tag, const char *message) {
  if (!appLogClass || !appLogMethod || !message) return;

  JavaVM *vm = getJavaInvocationInterface();
  if (!vm) return;

  JNIEnv *env = NULL;
  int attached = 0;

  jint result = (*vm)->GetEnv(vm, (void **) &env, JAVA_JNI_VERSION);
  if (result == JNI_EDETACHED) {
    if ((*vm)->AttachCurrentThread(vm, &env, NULL) != JNI_OK) return;
    attached = 1;
  } else if (result != JNI_OK) {
    return;
  }

  jstring text = (*env)->NewStringUTF(env, message);
  if (text) {
    (*env)->CallStaticVoidMethod(env, appLogClass, appLogMethod,
                                 (jint) priority, text);
    (*env)->DeleteLocalRef(env, text);
  }

  if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
  if (attached) (*vm)->DetachCurrentThread(vm);
}

void
brlttyAppLogf (int priority, const char *tag, const char *format, ...) {
  char record[APP_LOG_MAX_RECORD];
  va_list arguments;

  va_start(arguments, format);
  vsnprintf(record, sizeof(record), format, arguments);
  va_end(arguments);

  brlttyAppLog(priority, tag, record);
}
