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

package com.google.android.accessibility.braille.common

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.PointF
import android.util.Size
import androidx.annotation.StringRes
import java.text.ParseException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Reads and writes user preferences for the calibration points. */
class BrailleUserPreferencesTouchDots private constructor() {

  companion object {
    private const val JSON_KEY_CALIBRATION_ORIENTATION = "orientation"
    private const val JSON_KEY_CALIBRATION_POINTS = "points"
    private const val JSON_KEY_CALIBRATION_POINTS_X = "x"
    private const val JSON_KEY_CALIBRATION_POINTS_Y = "y"
    private const val JSON_KEY_SCREEN_SIZE_WIDTH = "screen_width"
    private const val JSON_KEY_SCREEN_SIZE_HEIGHT = "screen_height"

    @JvmStatic
    @Throws(ParseException::class)
    fun readLayoutPointsPhone(
      context: Context,
      sharedPreferences: SharedPreferences,
      isTableTop: Boolean,
      orientation: Int,
      screenSize: Size,
    ): List<PointF> {
      val pointsString =
        sharedPreferences.getString(
          context.getString(
            getPhoneCalibrationPreferenceKey(
              isTableTop,
              BrailleUserPreferences.isCurrentActiveInputCodeEightDot(context),
            ),
          ),
          "",
        )!!
      return try {
        pointsStringToPoints(orientation, screenSize, pointsString)
      } catch (e: JSONException) {
        throw ParseException(e.message, -1)
      }
    }

    @JvmStatic
    @Throws(ParseException::class)
    fun writeLayoutPointsPhone(
      context: Context,
      sharedPreferences: SharedPreferences,
      isTableTop: Boolean,
      orientation: Int,
      screenSize: Size,
      points: List<PointF>,
    ) {
      try {
        sharedPreferences
          .edit()
          .putString(
            context.getString(
              getPhoneCalibrationPreferenceKey(
                isTableTop,
                BrailleUserPreferences.isCurrentActiveInputCodeEightDot(context),
              ),
            ),
            generateLayoutPointsString(points, orientation, screenSize),
          )
          .apply()
      } catch (e: JSONException) {
        throw ParseException(e.message, -1)
      }
    }

    @JvmStatic
    @Throws(ParseException::class)
    fun readLayoutPointsTablet(
      context: Context,
      sharedPreferences: SharedPreferences,
      isTabletop: Boolean,
      orientation: Int,
    ): List<PointF> {
      try {
        val pointsString =
          sharedPreferences.getString(
            context.getString(
              getTabletCalibrationPreferenceKey(
                isTabletop,
                orientation,
                BrailleUserPreferences.isCurrentActiveInputCodeEightDot(context),
              ),
            ),
            "",
          )!!
        var points: MutableList<PointF> = mutableListOf()
        if (pointsString.isNotEmpty()) {
          points = parseLayoutPointsString(pointsString)
        }
        return points
      } catch (e: JSONException) {
        throw ParseException(e.message, -1)
      }
    }

    @JvmStatic
    @Throws(ParseException::class)
    fun writeLayoutPointsTablet(
      context: Context,
      sharedPreferences: SharedPreferences,
      isTabletop: Boolean,
      orientation: Int,
      points: List<PointF>,
      screenSize: Size,
    ) {
      try {
        sharedPreferences
          .edit()
          .putString(
            context.getString(
              getTabletCalibrationPreferenceKey(
                isTabletop,
                orientation,
                BrailleUserPreferences.isCurrentActiveInputCodeEightDot(context),
              ),
            ),
            generateLayoutPointsString(points, orientation, screenSize),
          )
          .apply()
      } catch (e: JSONException) {
        throw ParseException(e.message, -1)
      }
    }

    @StringRes
    private fun getTabletCalibrationPreferenceKey(
      isTabletop: Boolean,
      orientation: Int,
      eightDot: Boolean,
    ): Int {
      if (eightDot) {
        if (isTabletop) {
          return if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            R.string.pref_brailleime_calibration_points_tablet_eightDot_tabletop_portrait
          } else {
            R.string.pref_brailleime_calibration_points_tablet_eightDot_tabletop_landscape
          }
        } else {
          return if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            R.string.pref_brailleime_calibration_points_tablet_eightDot_screenaway_portrait
          } else {
            R.string.pref_brailleime_calibration_points_tablet_eightDot_screenaway_landscape
          }
        }
      } else {
        if (isTabletop) {
          return if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            R.string.pref_brailleime_calibration_points_tablet_tabletop_portrait
          } else {
            R.string.pref_brailleime_calibration_points_tablet_tabletop_landscape
          }
        } else {
          return if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            R.string.pref_brailleime_calibration_points_tablet_screenaway_portrait
          } else {
            R.string.pref_brailleime_calibration_points_tablet_screenaway_landscape
          }
        }
      }
    }

    @StringRes
    private fun getPhoneCalibrationPreferenceKey(tabletop: Boolean, eightDot: Boolean): Int {
      if (eightDot) {
        return if (tabletop) {
          R.string.pref_brailleime_calibration_points_phone_eightDot_tabletop
        } else {
          R.string.pref_brailleime_calibration_points_phone_eightDot_screenaway
        }
      } else {
        return if (tabletop) {
          R.string.pref_brailleime_calibration_points_phone_tabletop
        } else {
          R.string.pref_brailleime_calibration_points_phone_screenaway
        }
      }
    }

    @Throws(JSONException::class)
    private fun pointsStringToPoints(
      orientation: Int,
      screenSize: Size,
      pointsString: String,
    ): List<PointF> {
      val points = mutableListOf<PointF>()
      if (pointsString.isNotEmpty()) {
        val savedOrientation = JSONObject(pointsString).getInt(JSON_KEY_CALIBRATION_ORIENTATION)
        val width =
          JSONObject(pointsString).optInt(JSON_KEY_SCREEN_SIZE_WIDTH, screenSize.width)
        val height =
          JSONObject(pointsString).optInt(JSON_KEY_SCREEN_SIZE_HEIGHT, screenSize.height)
        val oldScreenSize = Size(width, height)
        val unorientedPoints = parseLayoutPointsString(pointsString)
        for (i in unorientedPoints.indices) {
          var point = unorientedPoints[i]
          if (savedOrientation == Configuration.ORIENTATION_PORTRAIT &&
            orientation == Configuration.ORIENTATION_LANDSCAPE
          ) {
            point = BrailleUtils.mapPortraitToLandscapeForPhone(point, oldScreenSize, screenSize)
          } else if (savedOrientation == Configuration.ORIENTATION_LANDSCAPE &&
            orientation == Configuration.ORIENTATION_PORTRAIT
          ) {
            point = BrailleUtils.mapLandscapeToPortraitForPhone(point, oldScreenSize, screenSize)
          }
          points.add(point)
        }
      }
      return points
    }

    @Throws(JSONException::class)
    private fun parseLayoutPointsString(pointsString: String): MutableList<PointF> {
      val points = mutableListOf<PointF>()
      val jsonObject = JSONObject(pointsString)
      val jArray = jsonObject.getJSONArray(JSON_KEY_CALIBRATION_POINTS)
      for (i in 0 until jArray.length()) {
        val x = jArray.getJSONObject(i).getDouble(JSON_KEY_CALIBRATION_POINTS_X).toFloat()
        val y = jArray.getJSONObject(i).getDouble(JSON_KEY_CALIBRATION_POINTS_Y).toFloat()
        points.add(PointF(x, y))
      }
      return points
    }

    @Throws(JSONException::class)
    private fun generateLayoutPointsString(
      points: List<PointF>,
      orientation: Int,
      screenSize: Size,
    ): String {
      val jsonObject = JSONObject()
      jsonObject.put(JSON_KEY_CALIBRATION_ORIENTATION, orientation)
      jsonObject.put(JSON_KEY_SCREEN_SIZE_WIDTH, screenSize.width)
      jsonObject.put(JSON_KEY_SCREEN_SIZE_HEIGHT, screenSize.height)
      val pointArray = JSONArray()
      for (point in points) {
        val jPoint = JSONObject()
        jPoint.put(JSON_KEY_CALIBRATION_POINTS_X, point.x)
        jPoint.put(JSON_KEY_CALIBRATION_POINTS_Y, point.y)
        pointArray.put(jPoint)
      }
      jsonObject.put(JSON_KEY_CALIBRATION_POINTS, pointArray)
      return jsonObject.toString()
    }
  }
}
