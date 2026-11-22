package com.google.android.accessibility.brailleime

import android.content.Context
import android.content.res.Configuration
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.Size
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.View.OnAttachStateChangeListener
import android.view.animation.AlphaAnimation
import android.widget.FrameLayout
import androidx.annotation.ColorInt
import androidx.annotation.NonNull
import androidx.core.content.res.ResourcesCompat
import com.google.android.accessibility.braille.common.BrailleUserPreferences
import com.google.android.accessibility.braille.common.Constants.BrailleType
import com.google.android.accessibility.braille.common.TouchDots
import com.google.android.accessibility.braille.interfaces.BrailleCharacter
import com.google.android.accessibility.brailleime.BrailleIme.OrientationSensitive
import com.google.android.accessibility.brailleime.LayoutOrientator.LayoutOrientatorCallback
import com.google.android.accessibility.brailleime.input.BrailleInputView
import com.google.android.accessibility.brailleime.input.DotHoldSwipe
import com.google.android.accessibility.brailleime.input.Swipe
import com.google.android.accessibility.brailleime.tutorial.VerticalTextView
import com.google.android.material.color.MaterialColors
import com.google.common.annotations.VisibleForTesting
import com.google.android.accessibility.brailleime.BrailleInputOptions
import java.util.Optional

/** A view for showing custom gesture hint. */
class CustomGestureView(
    context: Context,
    private val customGestureCallback: CustomGestureCallback,
    private var screenSize: Size,
) : FrameLayout(context), OnAttachStateChangeListener, OrientationSensitive {
  private val brailleInputViewCallback =
      object : BrailleInputView.Callback {
        override fun onInvalidGesture(): Boolean {
          return customGestureCallback.onInvalidGesture()
        }

        override fun onBrailleProduced(brailleCharacter: BrailleCharacter): String {
          val unused = onInvalidGesture()
          return ""
        }

        override fun onSwipeProduced(swipe: Swipe): Boolean {
          if (!BrailleImeGestureAction.isValid(swipe)) {
            return onInvalidGesture()
          }
          return customGestureCallback.onSwipeProduced(swipe)
        }

        override fun onDotHoldAndDotSwipe(dotHoldSwipe: DotHoldSwipe): Boolean {
          if (!BrailleImeGestureAction.isValid(dotHoldSwipe)) {
            return onInvalidGesture()
          }
          return customGestureCallback.onDotHoldAndDotSwipe(dotHoldSwipe)
        }

        override fun onHoldProduced(pointersHeldCount: Int): Boolean {
          return customGestureCallback.onHoldProduced(pointersHeldCount)
        }
      }

  private val layoutOrientatorCallback =
      object : LayoutOrientatorCallback {

        override fun useSensorsToDetectLayout(): Boolean {
          return BrailleUserPreferences.readLayoutMode(context) == TouchDots.AUTO_DETECT
        }

        override fun onDetectionChanged(isTabletop: Boolean, firstChangedEvent: Boolean) {
          customGestureCallback.onDetectionChanged(isTabletop)
        }
      }

  private val inputView: BrailleInputView
  private val hintToastAnimation: AlphaAnimation
  private val layoutOrientator: LayoutOrientator
  private val verticalTextView: VerticalTextView
  private var tableTopMode: Boolean = false

  /** A callback for custom gesture event. */
  interface CustomGestureCallback {
    /** Called when an invalid gesture is detected. */
    fun onInvalidGesture(): Boolean

    /** Called when a swipe gesture is detected. */
    fun onSwipeProduced(swipe: Swipe): Boolean

    /** Called when a dot hold and dot swipe gesture is detected. */
    fun onDotHoldAndDotSwipe(dotHoldSwipe: DotHoldSwipe): Boolean

    /** Called when a hold gesture is detected. */
    fun onHoldProduced(pointersHeldCount: Int): Boolean

    /** Called when the detection of the layout is changed. */
    fun onDetectionChanged(isTabletop: Boolean)
  }

  init {
    layoutOrientator = LayoutOrientator(context, layoutOrientatorCallback)
    layoutOrientator.startIfNeeded()
    val options =
        BrailleInputOptions.builder()
            .setTutorialMode(true)
            .setBrailleType(BrailleType.SIX_DOT)
            .build()
    val tableTopMode = isCurrentTableTopMode()
    inputView =
        BrailleInputView(context, brailleInputViewCallback, screenSize, options, tableTopMode)
    hintToastAnimation = AlphaAnimation(/* fromAlpha= */ 0.0f, /* toAlpha= */ 1.0f)
    hintToastAnimation.duration = HINT_ANIMATION_DURATION_MS.toLong()
    verticalTextView =
        VerticalTextView(ContextThemeWrapper(context, R.style.CustomGestureHint))
    addView(inputView)
    addView(verticalTextView, getTextViewLayoutParams())
    addOnAttachStateChangeListener(this)
  }

  override fun onViewAttachedToWindow(@NonNull v: View) {}

  override fun onViewDetachedFromWindow(@NonNull v: View) {
    layoutOrientator.stop()
    removeOnAttachStateChangeListener(this)
  }

  override fun onOrientationChanged(orientation: Int, screenSize: Size) {
    this.screenSize = screenSize
    verticalTextView.onOrientationChanged(orientation, screenSize)
    updateViewLayout(verticalTextView, getTextViewLayoutParams())
    inputView.onOrientationChanged(orientation, screenSize)
  }

  /** Sets keyboard to table layout. */
  fun setTableTopMode(tableTopMode: Boolean) {
    this.tableTopMode = tableTopMode
    updateViewLayout(verticalTextView, getTextViewLayoutParams())
    inputView.setTabletopMode(tableTopMode)
  }

  /** Shows the hint. */
  fun showHint(sb: SpannableStringBuilder, title: String, hint: String, warning: Boolean) {
    colorSubstring(sb, title, warning)
    resizeSubstring(sb, hint)
    verticalTextView.text = sb
    verticalTextView.visibility = VISIBLE
    verticalTextView.startAnimation(hintToastAnimation)
  }

  /** Returns true if the current layout is tabletop. */
  fun isCurrentTableTopMode(): Boolean {
    val layoutOptional: Optional<TouchDots> = layoutOrientator.getDetectedLayout()
    val mode = BrailleUserPreferences.readLayoutMode(context)
    return mode == TouchDots.TABLETOP ||
        layoutOptional
            .map { touchDots -> touchDots == TouchDots.TABLETOP }
            .orElseGet {
              resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            }
  }

  private fun getTextViewLayoutParams(): FrameLayout.LayoutParams {
    val layoutParams =
        FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT)
    val margin =
        ResourcesCompat.getFloat(resources, R.dimen.custom_gesture_hint_margin).toInt()
    layoutParams.setMargins(
        /* left= */ margin, /* top= */ margin, /* right= */ margin, /* bottom= */ margin)
    if (isPortrait()) {
      layoutParams.height =
          if (tableTopMode) (screenSize.height / HINT_HEIGHT_FRACTION).toInt()
          else (screenSize.height / HINT_WIDTH_FRACTION).toInt()
    } else {
      layoutParams.height =
          if (tableTopMode) (screenSize.width / HINT_HEIGHT_FRACTION).toInt()
          else (screenSize.width / HINT_WIDTH_FRACTION).toInt()
    }
    layoutParams.gravity = getGravity()
    return layoutParams
  }

  private fun getGravity(): Int {
    return if (tableTopMode)
        if (isPortrait()) Gravity.START or Gravity.CENTER else Gravity.TOP or Gravity.CENTER
    else Gravity.CENTER
  }

  private fun isPortrait(): Boolean {
    return resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
  }

  private fun resizeSubstring(allString: SpannableStringBuilder, substring: CharSequence) {
    val indexStart = allString.toString().indexOf(substring.toString())
    val indexEnd = indexStart + substring.length
    if (indexStart == -1) {
      return
    }
    allString.setSpan(
        RelativeSizeSpan(0.9f), indexStart, indexEnd, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
  }

  private fun colorSubstring(
      allString: SpannableStringBuilder,
      substring: CharSequence,
      warning: Boolean,
  ) {
    val indexStart = allString.toString().indexOf(substring.toString())
    val indexEnd = indexStart + substring.length
    if (indexStart == -1) {
      return
    }
    allString.setSpan(
        ForegroundColorSpan(getTextColor(warning)),
        indexStart,
        indexEnd,
        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
  }

  @ColorInt
  private fun getTextColor(warning: Boolean): Int {
    val themeContext = ContextThemeWrapper(context, R.style.CustomGestureView)
    return MaterialColors.getColor(
        themeContext,
        if (warning) R.attr.colorErrorContainer else R.attr.colorTertiaryFixed,
        "Unsupported color palette")
  }

  @VisibleForTesting
  fun testing_getBrailleInputView(): BrailleInputView {
    return inputView
  }

  companion object {
    private const val HINT_ANIMATION_DURATION_MS = 300
    private const val HINT_HEIGHT_FRACTION = 2.5
    private const val HINT_WIDTH_FRACTION = 4.5
  }
}
