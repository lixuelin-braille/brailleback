/*
 * Copyright 2019 Google Inc.
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

package com.google.android.accessibility.brailleime

import android.database.ContentObserver
import android.os.Handler
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import com.google.android.accessibility.utils.KeyboardUtils

/**
 * 系统级监听器：进程未捕获异常时拆掉键盘并切走输入法；读屏服务启停状态变化时重新激活或切走；
 * 来电响铃时收起键盘。
 */
class BrailleImeSystemListeners(private val ime: BrailleIme) {

  val localUncaughtExceptionHandler: Thread.UncaughtExceptionHandler =
    object : Thread.UncaughtExceptionHandler {
      override fun uncaughtException(thread: Thread, throwable: Throwable) {
        BrailleImeLog.e(TAG, "Uncaught exception", throwable)
        try {
          ime.keyboardView!!.tearDown()
          if (ime.isInputViewShown()) {
            ime.switchToNextInputMethod()
          }
        } catch (e: RuntimeException) {
          BrailleImeLog.e(TAG, "Uncaught exception in handler", throwable)
        } finally {
          if (ime.originalDefaultUncaughtExceptionHandler != null) {
            ime.originalDefaultUncaughtExceptionHandler!!.uncaughtException(thread, throwable)
          }
        }
      }
    }

  // We need this because in some situations BrailleImeForTalkBack is set to null. There is no
  // callback (set when onBrailleImeActivated()) in TB for us to know it turns off or on. For
  // example, when TalkBackOff dialog shows up, first page of tutorial or context menu shows up.
  // Note: TalkBack turns from active to suspended and suspended to resumed will not come in through
  // this callback.
  val accessibilityServiceStatusChangeObserver: ContentObserver =
    object : ContentObserver(Handler()) {
      override fun onChange(selfChange: Boolean) {
        super.onChange(selfChange)
        if (!ime.isInputViewShown()) {
          return
        }
        if (ime.isTalkBackEnabled()) {
          BrailleImeLog.d(TAG, "TalkBack becomes active.")
          // This listener is triggered before TB service is ready. Call activateIfNeeded() will
          // get service state is off so we need to set BrailleImeForTalkBack in TB to get
          // onTalkBackResumed() to make sure the state has been set to active.
          ime.activateBrailleIme()
        } else {
          BrailleImeLog.d(TAG, "TalkBack becomes inactive.")
          if (KeyboardUtils.areMultipleImesEnabled(ime)) {
            ime.switchToNextInputMethod()
          } else {
            ime.keyboardView!!.tearDown()
            ime.showTalkBackOffDialog()
          }
        }
      }
    }

  val phoneStateListener: PhoneStateListener =
    object : PhoneStateListener() {
      override fun onCallStateChanged(state: Int, incomingNumber: String?) {
        if (state == TelephonyManager.CALL_STATE_RINGING) {
          // Close keyboard when phone call coming.
          if (ime.keyboardView!!.isViewContainerCreated()) {
            ime.hideSelf()
          }
        }
      }
    }

  private companion object {
    private const val TAG = "BrailleIme"
  }
}
