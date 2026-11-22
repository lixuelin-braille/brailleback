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

import android.content.Context
import android.content.Context.SENSOR_SERVICE
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.google.android.accessibility.braille.common.TouchDots
import java.util.Optional

/** Auto detects whether the device is tabletop or screen away. */
class LayoutOrientator(
  context: Context,
  layoutOrientatorCallback: LayoutOrientatorCallback,
) {
  private val context: Context = context
  private val sensorManager: SensorManager =
    context.getSystemService(SENSOR_SERVICE) as SensorManager
  private val callback: LayoutOrientatorCallback = layoutOrientatorCallback
  private var autoModeLayout: Optional<TouchDots> = Optional.empty()

  /** Callback for clients of this class. */
  interface LayoutOrientatorCallback {
    fun useSensorsToDetectLayout(): Boolean

    fun onDetectionChanged(isTabletop: Boolean, firstChangedEvent: Boolean)
  }

  /** Starts listening to sensors that inform the orientation detection */
  fun startIfNeeded() {
    if (callback.useSensorsToDetectLayout()) {
      val accelerometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
      sensorManager.registerListener(
        sensorEventListener, accelerometer, SensorManager.SENSOR_STATUS_ACCURACY_HIGH)
    }
  }

  /** Stops listening to sensors that inform the orientation detection */
  fun stop() {
    sensorManager.unregisterListener(sensorEventListener)
    autoModeLayout = Optional.empty()
  }

  /** Returns detected layout. Empty before sensor receives events. */
  fun getDetectedLayout(): Optional<TouchDots> {
    return autoModeLayout
  }

  private val sensorEventListener =
    object : SensorEventListener {
      override fun onSensorChanged(sensorEvent: SensorEvent) {
        val sensorEventValues =
          Utils.adjustAccelOrientation(
            Utils.getDisplayRotationDegrees(context), sensorEvent.values)
        val isFlat = Utils.isFlat(sensorEventValues)
        val firstChangedEvent = autoModeLayout.isEmpty
        val newLayout = if (isFlat) TouchDots.TABLETOP else TouchDots.SCREEN_AWAY
        val shouldChange = firstChangedEvent || (autoModeLayout.get() != newLayout)
        autoModeLayout = Optional.of(newLayout)
        if (shouldChange) {
          callback.onDetectionChanged(isFlat, firstChangedEvent)
        }
      }

      override fun onAccuracyChanged(sensor: Sensor?, i: Int) {}
    }
}
