/*
 * Copyright 2020 Google Inc.
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

import android.content.Context
import android.view.OrientationEventListener
import com.google.common.annotations.VisibleForTesting

/**
 * Monitors the orientation changed event even when device auto rotate is locked.
 *
 * <p>OrientationMonitor is a singleton. The calling sequence is as below.
 *
 * <p>1. Call {@link #init(Context)} <b>once</b> to make sure OrientationMonitor has been
 * initialized.
 *
 * <p>2. Call {@link #enable()} to start monitor orientation changed events from system.
 */
class OrientationMonitor {
  /** Screen orientations. */
  enum class Orientation(private val degree: Int) {
    UNKNOWN(-1),
    PORTRAIT(0),
    LANDSCAPE(90),
    REVERSE_PORTRAIT(180),
    REVERSE_LANDSCAPE(270),
    ;

    fun getDegree(): Int {
      return degree
    }
  }

  /** Callback register in {@link OrientationMonitor}. */
  interface Callback {
    fun onOrientationChanged(orientation: Orientation)
  }

  private var orientationMonitorCallback: Callback? = null
  private var enabled = false
  @JvmField var currentOrientation = Orientation.UNKNOWN
  private var orientationEventListener: OrientationEventListener? = null

  public fun enable() {
    if (enabled) {
      return
    }
    enabled = true
    orientationEventListener!!.enable()
  }

  public fun disable() {
    if (!enabled) {
      return
    }
    enabled = false
    orientationEventListener!!.disable()
  }

  public fun getCurrentOrientation(): Orientation {
    return currentOrientation
  }

  public fun registerCallback(orientationMonitorCallback: Callback) {
    this.orientationMonitorCallback = orientationMonitorCallback
  }

  public fun unregisterCallback() {
    orientationMonitorCallback = null
  }

  @VisibleForTesting
  public fun testing_setOrientationEventListener(listener: OrientationEventListener) {
    orientationEventListener = listener
  }

  @VisibleForTesting
  public fun testing_getOrientationEventListener(): OrientationEventListener? {
    return orientationEventListener
  }

  companion object {
    private var singleton: OrientationMonitor? = null

    @JvmStatic
    fun getInstance(): OrientationMonitor {
      if (singleton == null) {
        throw IllegalStateException("You forget to call init()!")
      }
      return singleton!!
    }

    @JvmStatic
    fun init(context: Context) {
      if (singleton == null) {
        singleton = OrientationMonitor()
        singleton!!.orientationEventListener =
          object : OrientationEventListener(context.applicationContext) {
            override fun onOrientationChanged(degree: Int) {
              var adjustedDegree =
                if (Utils.isDeviceDefaultPortrait(context)) degree else degree - 90
              val newOrientation = convertToOrientation(adjustedDegree)
              if (newOrientation != singleton!!.currentOrientation) {
                singleton!!.currentOrientation = newOrientation
                val callback = singleton!!.orientationMonitorCallback
                if (callback != null) {
                  callback.onOrientationChanged(newOrientation)
                }
              }
            }

            private fun convertToOrientation(degree: Int): Orientation {
              for (orientation in enumValues<Orientation>()) {
                if (orientation != Orientation.UNKNOWN &&
                  Math.abs(degree - orientation.getDegree()) <= 45) {
                  return orientation
                }
              }
              // Special case for portrait when the degrees is close to 360.
              if (Math.abs(degree - 360) <= 45) {
                return Orientation.PORTRAIT
              }
              return Orientation.UNKNOWN
            }
          }
      }
    }
  }
}
