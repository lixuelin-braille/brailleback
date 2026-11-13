/*
 * Bridges brltty's native log records into the host application's log file.
 */

#ifndef BRLTTY_APP_LOG_BRIDGE_H
#define BRLTTY_APP_LOG_BRIDGE_H

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Caches the Java sink class. Must be called from a Java-attached thread. */
void brlttyAppLogInit(JNIEnv *env);

/* Sends one already-formatted record to the Java log sink. */
void brlttyAppLog(int priority, const char *tag, const char *message);

/* Formats and sends one record to the Java log sink. */
void brlttyAppLogf(int priority, const char *tag, const char *format, ...);

#ifdef __cplusplus
}
#endif

#endif /* BRLTTY_APP_LOG_BRIDGE_H */
