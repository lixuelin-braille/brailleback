/*
 * Copyright (C) 2021 Google Inc.
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

package com.google.android.accessibility.braille.common.lib

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.RECEIVER_EXPORTED
import com.google.errorprone.annotations.CanIgnoreReturnValue

/** A subclass of BroadcastReceiver that reduces the tedium of registering and unregistering. */
abstract class ActionReceiver<R : ActionReceiver<R, C>, C>(
  protected val context: Context,
  private val callback: C,
) : BroadcastReceiver(), Receiver<R> {

  private var registered = false

  override fun onReceive(context: Context, intent: Intent) {
    onReceive(callback, intent.action, intent.extras)
  }

  protected abstract fun onReceive(callback: C, action: String?, extras: Bundle?)

  protected abstract fun getActionsList(): Array<String>

  @CanIgnoreReturnValue
  override fun registerSelf(): R {
    if (!registered) {
      val filter = IntentFilter()
      for (action in getActionsList()) {
        filter.addAction(action)
      }
      ContextCompat.registerReceiver(context, this, filter, RECEIVER_EXPORTED)
      registered = true
    }
    return this as R
  }

  override fun unregisterSelf() {
    if (registered) {
      context.unregisterReceiver(this)
      registered = false
    }
  }
}
