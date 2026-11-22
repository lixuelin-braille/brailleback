/*
 * Copyright (C) 2023 Google Inc.
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

package com.google.android.accessibility.brailleime.input

import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.TransitionDrawable
import android.util.AttributeSet
import android.util.Size
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.ImageView
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.annotation.Nullable
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.google.android.accessibility.brailleime.BrailleIme.OrientationSensitive
import com.google.android.accessibility.brailleime.FeatureFlagReader
import com.google.android.accessibility.brailleime.R
import com.google.android.accessibility.brailleime.Utils
import com.google.common.collect.ImmutableMap
import java.util.Locale
import kotlin.math.min

/** A strip which shows when braille display is connected. */
class BrailleDisplayImeStripView : RelativeLayout, OrientationSensitive {
  private val multiTouchSupported: Boolean
  private var callBack: CallBack? = null
  private lateinit var dotsBackground: ImageView
  private lateinit var switchToTouchScreenKeyboard: View
  private lateinit var switchToNextKeyboard: View
  private lateinit var locale: Locale
  private lateinit var brailleKeyboardStripSummary: TextView

  /** Callback when BrailleDisplayImeStripView is clicked. */
  interface CallBack {
    fun onSwitchToOnscreenKeyboard()

    fun onSwitchToNextKeyboard()
  }

  constructor(context: Context) : this(context, null)

  constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0)

  constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) :
      super(context, attrs, defStyleAttr) {
    multiTouchSupported = Utils.isMultiTouchSupported(context)
    locale = Locale.getDefault()
    addView()
  }

  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    var heightMeasureSpec = heightMeasureSpec
    val heightSize = MeasureSpec.getSize(heightMeasureSpec)
    val heightMode = MeasureSpec.getMode(heightMeasureSpec)
    val maxHeight =
        (context.display!!.height *
                ResourcesCompat.getFloat(
                    context.resources, R.dimen.braille_display_keyboard_height_fraction))
            .toInt()
    heightMeasureSpec =
        if (heightMode == MeasureSpec.UNSPECIFIED)
            MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST)
        else MeasureSpec.makeMeasureSpec(min(heightSize, maxHeight), heightMode)
    super.onMeasure(widthMeasureSpec, heightMeasureSpec)
  }

  private fun addView() {
    val container: View =
        View.inflate(
            ContextThemeWrapper(context, R.style.BrailleDisplayKeyboardTheme),
            R.layout.brailledisplay_ime,
            this)
    switchToTouchScreenKeyboard = container.findViewById(R.id.switch_to_touch_screen_keyboard)
    switchToNextKeyboard = container.findViewById(R.id.switch_to_next_keyboard)
    dotsBackground = container.findViewById(R.id.dots_background)
    brailleKeyboardStripSummary =
        container.findViewById(R.id.braille_keyboard_strip_summary_text)
    updateMultiTouchSupportedContent()
    setCallBack(callBack)
  }

  /** Updates the view content based on device support for multi-touch. */
  private fun updateMultiTouchSupportedContent() {
    if (!FeatureFlagReader.enableBrailleImeOnDeviceWithoutFivePointers(context)) {
      return
    }
    val stripSummaryText =
        if (multiTouchSupported) R.string.braille_keyboard_strip_summary
        else R.string.braille_keyboard_strip_summary_no_multitouch
    brailleKeyboardStripSummary.setText(stripSummaryText)
    switchToTouchScreenKeyboard.visibility =
        if (multiTouchSupported) View.VISIBLE else View.GONE
  }

  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    val newLocale = Locale.getDefault()
    if (locale != newLocale) {
      refreshViews()
      locale = newLocale
    }
  }

  override fun onOrientationChanged(orientation: Int, screenSize: Size) {
    refreshViews()
  }

  /** Sets callback for strip view. If null, remove callback for listeners. */
  fun setCallBack(@Nullable callBack: CallBack?) {
    this.callBack = callBack
    val cb = callBack
    switchToTouchScreenKeyboard.setOnClickListener(
        if (cb == null) null else View.OnClickListener { cb.onSwitchToOnscreenKeyboard() })
    switchToNextKeyboard.setOnClickListener(
        if (cb == null) null else View.OnClickListener { cb.onSwitchToNextKeyboard() })
  }

  /** Animates tapped dots. */
  fun animateInput(dotNumbers: List<Int>) {
    val layers = arrayOfNulls<Drawable>(dotNumbers.size)
    for (i in 0 until dotNumbers.size) {
      layers[i] = ContextCompat.getDrawable(context, dotsResMap[dotNumbers[i]]!!)
    }
    val layerDrawable = LayerDrawable(layers)
    val transitionDrawable =
        TransitionDrawable(
            arrayOf(
                layerDrawable, ContextCompat.getDrawable(context, R.drawable.dots_untapped)))
    dotsBackground.setImageDrawable(transitionDrawable)
    transitionDrawable.isCrossFadeEnabled = true
    transitionDrawable.startTransition(DURATION_MILLISECONDS)
  }

  private fun refreshViews() {
    removeAllViews()
    addView()
  }

  private val dotsResMap: ImmutableMap<Int, Int> =
      ImmutableMap.builder<Int, Int>()
          .put(1, R.drawable.dots_tapped_1)
          .put(2, R.drawable.dots_tapped_2)
          .put(3, R.drawable.dots_tapped_3)
          .put(4, R.drawable.dots_tapped_4)
          .put(5, R.drawable.dots_tapped_5)
          .put(6, R.drawable.dots_tapped_6)
          .put(7, R.drawable.dots_tapped_7)
          .put(8, R.drawable.dots_tapped_8)
          .buildOrThrow()

  companion object {
    private const val DURATION_MILLISECONDS = 150
  }
}
