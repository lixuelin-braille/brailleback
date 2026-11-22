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

import android.Manifest
import android.animation.ArgbEvaluator
import android.content.ComponentName
import android.content.Context
import android.content.Context.WINDOW_SERVICE
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Paint
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.text.style.URLSpan
import android.util.DisplayMetrics
import android.util.Size
import android.util.TypedValue
import android.view.Display
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import com.google.android.accessibility.braille.common.BrailleUserPreferences
import com.google.android.accessibility.braille.common.ImeConnection
import com.google.android.accessibility.braille.common.translate.BrailleTranslateUtils
import com.google.android.accessibility.brailleime.input.DotHoldSwipe
import com.google.android.accessibility.brailleime.input.Gesture
import com.google.android.accessibility.utils.AccessibilityServiceCompatUtils.Constants
import com.google.android.accessibility.utils.BuildVersionUtils
import com.google.android.accessibility.utils.PreferenceSettingsUtils
import java.text.NumberFormat
import java.util.Locale
import java.util.Optional
import kotlin.math.max

/** Static convenience methods for Braille Keyboard. */
class Utils private constructor() {

  companion object {
    private const val TAG = "Utils"
    // Refer from NAV_BAR_MODE_GESTURAL in
    // frameworks/base/core/java/android/view/WindowManagerPolicyConstants.java
    private const val NAV_BAR_MODE_GESTURAL = 2

    /**
     * Returns the float value associated with a resource id by delegating to {@link
     * TypedValue#getFloat}.
     */
    @JvmStatic
    fun getResourcesFloat(resources: Resources, id: Int): Float {
      val typedValue = TypedValue()
      resources.getValue(id, typedValue, true)
      return typedValue.float
    }

    /** Converts millimeters to pixels in a device-specific manner. */
    @JvmStatic
    fun mmToPixels(resources: Resources, mm: Int): Float {
      return mm *
        TypedValue.applyDimension(
          TypedValue.COMPLEX_UNIT_MM, 1f /* value */, resources.displayMetrics)
    }

    /** Computes the euclidean distance between two {@link PointF} objects. */
    @JvmStatic
    fun distance(p1: PointF, p2: PointF): Double {
      return Math.hypot((p1.x - p2.x).toDouble(), (p1.y - p2.y).toDouble())
    }

    /**
     * Returns the size of the display in pixels by delegating to {@link Display#getRealSize(Point)}.
     */
    @JvmStatic
    fun getDisplaySizeInPixels(context: Context): Size {
      val windowManager = context.getSystemService(WINDOW_SERVICE) as WindowManager
      val displaySize = Point()
      windowManager.defaultDisplay.getRealSize(displaySize)
      val width = displaySize.x
      val height = displaySize.y
      return Size(width, height)
    }

    /**
     * Returns the size of the visible display for specific View in pixels by delegating to {@link
     * View#getWindowVisibleDisplayFrame(Rect)}.
     */
    @JvmStatic
    fun getVisibleDisplaySizeInPixels(view: View): Size {
      val rect = Rect()
      view.getWindowVisibleDisplayFrame(rect)
      return Size(rect.width(), rect.height())
    }

    /**
     * Returns the rotation angle of the current configuration, by delegating to {@link
     * Display#getRotation()}.
     */
    @JvmStatic
    fun getDisplayRotationDegrees(context: Context): Int {
      val windowManager = context.getSystemService(WINDOW_SERVICE) as WindowManager
      return windowManager.defaultDisplay.rotation
    }

    /**
     * Returns the baseline height in pixels of {@code paint} by delegating to {@link
     * Paint#getFontMetrics}.
     */
    @JvmStatic
    fun getPaintTextBaselineInPixels(paint: Paint): Int {
      val fontMetrics = paint.fontMetrics
      return ((fontMetrics.bottom - fontMetrics.top) / 2 - fontMetrics.bottom).toInt()
    }

    /**
     * Returns {@code true} if the absolute values of the given coordinates have a large enough ratio
     * that the 2-D vector formed from them is nearly cardinal, where "nearly cardinal" is defined as
     * any ratio that is greater than or equal to {@code ratioThreshold}.
     *
     * <p>If either (but not both) of the coordinates are {@code 0}, then true is returned.
     *
     * <p>If both of the coordinates are {@code 0}, then false is returned.
     */
    @JvmStatic
    fun isVectorNearlyCardinal(vector: PointF, ratioThreshold: Float): Boolean {
      if (vector.x == 0f && vector.y == 0f) {
        return false
      }
      if (vector.x == 0f || vector.y == 0f) {
        return true
      }
      return max(Math.abs(vector.x / vector.y), Math.abs(vector.y / vector.x)) > ratioThreshold
    }

    /** Inserts space between characters. Example: "abc" -> "a b c". */
    @JvmStatic
    fun insertSpacesInto(arg: String): String {
      return arg.replace("", " ").trim()
    }

    /** Formats {@code substring} as {@code drawable}. */
    @JvmStatic
    fun formatSubstringAsDrawable(
      spannableString: SpannableString,
      substring: String,
      drawable: Drawable,
    ): SpannableString {
      val token = " "
      val indexIconStart = spannableString.toString().indexOf(substring)
      val indexIconEnd = indexIconStart + substring.length
      if (indexIconStart == -1) {
        return spannableString
      }
      val spannableStringBuilder = SpannableStringBuilder(spannableString)
      spannableStringBuilder.replace(indexIconStart, indexIconEnd, token)
      drawable.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
      spannableStringBuilder.setSpan(
        ImageSpan(drawable),
        indexIconStart,
        indexIconStart + token.length,
        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
      return SpannableString.valueOf(spannableStringBuilder)
    }

    /** Formats {@param substring} as {@code url} link. Returns true if success; otherwise false. */
    @JvmStatic
    fun formatSubstringAsUrl(
      spannableString: SpannableString,
      substring: String,
      url: String,
    ): Boolean {
      val indexStart = spannableString.toString().indexOf(substring)
      val indexEnd = indexStart + substring.length
      if (indexStart == -1) {
        return false
      }
      spannableString.setSpan(
        URLSpan(url), indexStart, indexEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
      return true
    }

    /**
     * Enables/disables {@code component}.
     *
     * <p>Returns absent if the PackageManager does not recognize the component. Otherwise, return
     * true if the PackageManager was told to enable the component.
     */
    @JvmStatic
    fun setComponentEnabled(
      context: Context,
      component: ComponentName,
      enabled: Boolean,
    ): Optional<Boolean> {
      try {
        val packageManager = context.packageManager
        val newEnablement =
          if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
          else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        packageManager.setComponentEnabledSetting(
          component, newEnablement, PackageManager.DONT_KILL_APP)
        return Optional.of(enabled)
      } catch (e: RuntimeException) {
        return Optional.empty()
      }
    }

    /**
     * Parses a string as int or returns {@code defValue} if the string is null or if parsing fails.
     */
    @JvmStatic
    fun parseIntWithDefault(putativeIntegerString: String?, defValue: Int): Int {
      if (putativeIntegerString == null) {
        return defValue
      }
      try {
        return Integer.parseInt(putativeIntegerString)
      } catch (e: NumberFormatException) {
        return defValue
      }
    }

    /**
     * Concatenates two strings together, separated by a newline, and accents the second string with
     * the given color.
     */
    @JvmStatic
    fun appendWithColoredString(
      firstString: CharSequence,
      secondString: CharSequence,
      @ColorInt color: Int,
    ): CharSequence {
      val coloredSecondString = SpannableString(secondString)
      coloredSecondString.setSpan(
        ForegroundColorSpan(color),
        /* start= */ 0,
        coloredSecondString.length,
        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
      return TextUtils.concat(firstString, "\n", coloredSecondString)
    }

    @JvmStatic
    fun isDebugBuild(): Boolean {
      return "eng" == Build.TYPE || "userdebug" == Build.TYPE
    }

    /** Use Ime supplied View as keyboard input view; otherwise use Accessibility Overlay. */
    @JvmStatic
    fun useImeSuppliedInputWindow(): Boolean {
      // return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
      // We encounter the problem that system UI has probabilities to invade the keyboard area and
      // make the system UI easily touched by the user. It is a bad user experience, so set it to
      // false until we come out a good solution about it.
      return false
    }

    /** Returns {@code true} if sensor event values indicate the device is in a flat orientation. */
    @JvmStatic
    fun isFlat(sensorEventValues: FloatArray): Boolean {
      val x = sensorEventValues[0]
      val y = sensorEventValues[1]
      val z = sensorEventValues[2]
      val normOfGravity = Math.sqrt((x * x + y * y + z * z).toDouble()).toFloat()
      val inclination =
        Math.round(Math.toDegrees(Math.acos((z / normOfGravity).toDouble()))).toInt()
      return inclination < 25 || inclination > 155
    }

    /**
     * Adjust sensor values based on device rotation. For example, some tablet is default landscape.
     * Its screen has been reorientated. We need to compensate the values.
     */
    @JvmStatic
    fun adjustAccelOrientation(displayRotation: Int, sensorEventValues: FloatArray): FloatArray {
      val adjustedValues = FloatArray(3)
      val axisSwap =
        arrayOf(
          intArrayOf(1, -1, 0, 1), // ROTATION_0
          intArrayOf(-1, -1, 1, 0), // ROTATION_90
          intArrayOf(-1, 1, 0, 1), // ROTATION_180
          intArrayOf(1, 1, 1, 0)) // ROTATION_270
      val row = axisSwap[displayRotation]
      adjustedValues[0] = row[0].toFloat() * sensorEventValues[row[2]]
      adjustedValues[1] = row[1].toFloat() * sensorEventValues[row[3]]
      adjustedValues[2] = sensorEventValues[2]
      return adjustedValues
    }

    /** Takes the average of two colors. */
    @JvmStatic
    fun averageColors(color1: Int, color2: Int): Int {
      return ArgbEvaluator().evaluate(0.5f, color1, color2) as Int
    }

    /** Whether the device's natural orientation is portrait. */
    @JvmStatic
    fun isDeviceDefaultPortrait(context: Context): Boolean {
      val rotation =
        (context.getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
      val dm = DisplayMetrics()
      (context.getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getMetrics(dm)
      val width = dm.widthPixels
      val height = dm.heightPixels
      return ((rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180) && height >
        width) ||
        ((rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) && width > height)
    }

    /**
     * Returns whether navigation bar is on the left. Nav bar could be located on the left side
     * starting from Android 7.1. Nav bar could be located on the bottom if full gestural navigation
     * is enabled.
     */
    @JvmStatic
    fun isNavigationBarLeftLocated(context: Context): Boolean {
      return !isFullGesturalNavigationEnabled(context) &&
        getDisplayRotationDegrees(context) == Surface.ROTATION_270
    }

    /** Returns whether full gestural navigation is enabled by user. */
    @JvmStatic
    fun isFullGesturalNavigationEnabled(context: Context): Boolean {
      val resources = context.resources
      val resId = resources.getIdentifier("config_navBarInteractionMode", "integer", "android")
      return resId > 0 && resources.getInteger(resId) == NAV_BAR_MODE_GESTURAL
    }

    /** Returns the braille keyboard display name. */
    @JvmStatic
    fun getBrailleKeyboardDisplayName(context: Context): String {
      val name: String
      if (BrailleUserPreferences.readAvailablePreferredCodes(context).size == 1) {
        name = context.getString(R.string.braille_ime_displayed_name)
      } else {
        val codeUserFacingName =
          BrailleUserPreferences.readCurrentActiveInputCodeAndCorrect(context)
            .getUserFacingName(context)
        name =
          context.getString(
            R.string.multiple_languages_braille_ime_displayed_name, codeUserFacingName)
      }
      return name
    }

    @JvmStatic
    fun isPhonePermissionGranted(context: Context): Boolean {
      return ContextCompat.checkSelfPermission(
          context, Manifest.permission.READ_PHONE_STATE) ==
        PackageManager.PERMISSION_GRANTED
    }

    /** Returns text of the editor action. */
    @JvmStatic
    fun getActionLabel(
      inputMethodService: InputMethodService,
      editorInfo: EditorInfo?,
    ): CharSequence {
      var action: CharSequence? = null
      if (editorInfo == null) {
        return ""
      }
      if (editorInfo.actionLabel != null) {
        action = editorInfo.actionLabel
      }
      if (action == null) {
        action = inputMethodService.getTextForImeAction(editorInfo.imeOptions)
      }
      if (action == null) {
        return ""
      }
      return action
    }

    /** Returns hint of the edit field. */
    @JvmStatic
    fun getHint(imeConnection: ImeConnection): CharSequence {
      var hint = imeConnection.editorInfo.hintText
      if (!TextUtils.isEmpty(hint)) {
        return hint!!
      }
      val extractedText =
        imeConnection.inputConnection.getExtractedText(ExtractedTextRequest(), 0)
      if (extractedText != null &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
        !TextUtils.isEmpty(extractedText.hint)) {
        return extractedText.hint!!
      }
      return ""
    }

    /** Highlights TalkBack item in Accessibility Settings upon arriving there (Pixel only). */
    @JvmStatic
    fun highlightTalkBackSettings(context: Context) {
      val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
      intent.addFlags(
        Intent.FLAG_ACTIVITY_NEW_TASK or
          Intent.FLAG_ACTIVITY_CLEAR_TASK or
          Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
      // Highlight TalkBack item in Accessibility Settings upon arriving there (Pixel only).
      PreferenceSettingsUtils.attachSettingsHighlightBundle(intent, Constants.TALKBACK_SERVICE)
      context.startActivity(intent)
    }

    /** Collapses the notification panel. */
    @JvmStatic
    fun collapseNotificationPanel(context: Context) {
      // The ACTION_CLOSE_SYSTEM_DIALOGS intent action is deprecated from S. The platform will
      // automatically collapse the proper system dialogs in the proper use-cases.
      if (!BuildVersionUtils.isAtLeastS()) {
        // Collapse notification panel (quick settings).
        context.sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
      }
    }

    /** Returns true if the device supports the multi-touch system feature. */
    @JvmStatic
    fun isMultiTouchSupported(context: Context): Boolean {
      return context
        .packageManager
        .hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN_MULTITOUCH_JAZZHAND)
    }

    /**
     * Returns the combined gesture description for a list of gestures.
     *
     * <p>If the list contains a DotHoldSwipe gesture, the combined gesture description will be "Hold
     * and swipe" followed by the swipe direction.
     *
     * <p>If the list contains a single gesture, the gesture description will be returned.
     *
     * <p>If the list contains multiple gestures, the first gesture will be returned.
     */
    @JvmStatic
    fun getCombinedGestureDescription(context: Context, gestures: List<Gesture>): String {
      val sb = StringBuilder()
      if (gestures.size > 1 && gestures[0] is DotHoldSwipe) {
        val sorted = gestures.sortedWith(compareBy { it.getHeldDots()!!.toInt() })
        val firsetGesture = sorted[0]
        val secondGesture = sorted[1]
        val touchCount = firsetGesture.getSwipe()!!.touchCount
        val dotDescription =
          context.getString(
            R.string.gesture1_or_gesture2,
            BrailleTranslateUtils.getDotsDescription(context.resources, firsetGesture.getHeldDots()!!),
            BrailleTranslateUtils.getDotsDescription(context.resources, secondGesture.getHeldDots()!!))
        if (touchCount > 1) {
          sb.append(
            context
              .resources
              .getString(
                R.string.gesture_hold_and_swipe_multiple_finger,
                dotDescription,
                firsetGesture.getSwipe()!!.direction.getDescription(context.resources),
                NumberFormat.getNumberInstance(Locale.getDefault()).format(touchCount)))
        } else {
          sb.append(
            context
              .resources
              .getString(
                R.string.gesture_hold_and_swipe_one_finger,
                dotDescription,
                firsetGesture.getSwipe()!!.direction.getDescription(context.resources)))
        }
      } else {
        sb.append(gestures[0].getDescription(context.resources))
      }
      return sb.toString()
    }
  }
}
