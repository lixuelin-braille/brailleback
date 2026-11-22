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

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.res.Configuration
import android.database.ContentObserver
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.PowerManager
import android.provider.Settings
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.text.TextUtils
import android.util.Size
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.annotation.Nullable
import androidx.core.content.ContextCompat
import com.google.android.accessibility.braille.common.BrailleCommonTalkBackSpeaker
import com.google.android.accessibility.braille.common.BrailleCommonUtils
import com.google.android.accessibility.braille.common.BrailleImeAction
import com.google.android.accessibility.braille.common.BrailleUserPreferences
import com.google.android.accessibility.braille.common.BrailleUtils
import com.google.android.accessibility.braille.common.FeedbackManager
import com.google.android.accessibility.braille.common.ImeConnection
import com.google.android.accessibility.braille.common.ImeConnection.AnnounceType
import com.google.android.accessibility.braille.common.TalkBackSpeaker
import com.google.android.accessibility.braille.common.TouchDots
import com.google.android.accessibility.braille.common.translate.BrailleLanguages
import com.google.android.accessibility.braille.common.translate.BrailleLanguages.Code
import com.google.android.accessibility.braille.common.translate.EditBuffer
import com.google.android.accessibility.braille.common.translate.EditBufferUtils
import com.google.android.accessibility.braille.interfaces.BrailleCharacter
import com.google.android.accessibility.braille.interfaces.BrailleDisplayForBrailleIme
import com.google.android.accessibility.braille.interfaces.BrailleDisplayForBrailleIme.ResultForDisplay
import com.google.android.accessibility.braille.interfaces.BrailleImeForBrailleDisplay
import com.google.android.accessibility.braille.interfaces.BrailleImeForTalkBack
import com.google.android.accessibility.braille.interfaces.BrailleWord
import com.google.android.accessibility.braille.interfaces.ImeAction
import com.google.android.accessibility.braille.interfaces.TalkBackForBrailleCommon
import com.google.android.accessibility.braille.interfaces.TalkBackForBrailleIme
import com.google.android.accessibility.braille.interfaces.TalkBackForBrailleIme.ServiceStatus
import com.google.android.accessibility.braille.translate.BrailleTranslator
import com.google.android.accessibility.braille.translate.TranslatorFactory
import com.google.android.accessibility.brailleime.LayoutOrientator.LayoutOrientatorCallback
import com.google.android.accessibility.brailleime.analytics.BrailleImeAnalytics
import com.google.android.accessibility.brailleime.dialog.ContextMenuDialog
import com.google.android.accessibility.brailleime.dialog.TalkBackOffDialog
import com.google.android.accessibility.brailleime.dialog.TalkBackSuspendDialog
import com.google.android.accessibility.brailleime.dialog.TooFewTouchPointsDialog
import com.google.android.accessibility.brailleime.dialog.ViewAttachedDialog
import com.google.android.accessibility.brailleime.input.BrailleDisplayImeStripView
import com.google.android.accessibility.brailleime.input.BrailleInputView
import com.google.android.accessibility.brailleime.input.BrailleInputView.CalibrationTriggeredType
import com.google.android.accessibility.brailleime.input.BrailleInputView.FingersPattern
import com.google.android.accessibility.brailleime.input.DotHoldSwipe
import com.google.android.accessibility.brailleime.input.Swipe
import com.google.android.accessibility.brailleime.keyboardview.AccessibilityOverlayKeyboardView
import com.google.android.accessibility.brailleime.keyboardview.KeyboardView
import com.google.android.accessibility.brailleime.keyboardview.KeyboardView.KeyboardViewCallback
import com.google.android.accessibility.brailleime.keyboardview.StandardKeyboardView
import com.google.android.accessibility.brailleime.settings.BrailleImeGestureCommandActivity
import com.google.android.accessibility.brailleime.settings.BrailleImePreferencesActivity
import com.google.android.accessibility.brailleime.tutorial.TutorialView.TutorialCallback
import com.google.android.accessibility.brailleime.tutorial.TutorialView.TutorialState.State
import com.google.android.accessibility.utils.AccessibilityServiceCompatUtils
import com.google.android.accessibility.utils.AccessibilityServiceCompatUtils.Constants
import com.google.android.accessibility.utils.BuildVersionUtils
import com.google.android.accessibility.utils.KeyboardUtils
import com.google.android.accessibility.utils.input.CursorGranularity
import com.google.android.accessibility.utils.output.SpeechController.UtteranceCompleteRunnable
import com.google.common.annotations.VisibleForTesting
import com.google.common.collect.ImmutableSet
import com.google.errorprone.annotations.CanIgnoreReturnValue
import java.util.Objects
import java.util.Optional
import java.util.concurrent.atomic.AtomicInteger

/**
 * An input method intended for blind/low-vision users that displays braille dot touch targets and
 * converts taps on those braille dots into print characters.
 *
 * <p>Since it is the root of the object graph and the class that has access to the {@link
 * InputConnection}, this class coordinates the flow of user input. Chronologically, that input
 * begins with the {@link BrailleInputView}, which is instantiated and owned by this class, and
 * which converts touch events into {@link BrailleInputView.Callback} callbacks, which are
 * implemented inside this class. In the case of {@link
 * BrailleInputView.Callback#onBrailleProduced(BrailleCharacter)} this class passes the {@link
 * BrailleCharacter} object to the {@link EditBuffer}, which holds a list of accumulated {@link
 * BrailleCharacter} until the time comes to translate braille to print and send it to the IME
 * Editor via the {@link InputConnection}.
 *
 * <p>Difficulty arises because the prototypical IME envisioned by the super class has an input area
 * with a candidates bar atop it, and possibly an extracted Editor view at the very top in case the
 * IME needs to be fullscreen. This IME differs from that protoype in two major ways:
 *
 * <ol>
 *   <li>It wants to be immersive - as fullscreen as possible
 *   <li>It has no need to display the underlying Editor nor an extracted Editor
 * </ol>
 *
 * <p>Therefore this class avoids the default View structure of the super class-provided Window in a
 * somewhat complex way, with the strategy depending on the version of the operating system. For
 * more information, see {@link KeyboardView}. One of these strategies involves adding a whole new
 * Window object to the WindowManager, and the other involves defining touch exploration passthrough
 * regions; both of these abilities rely on the BrailleIme being colocated with an
 * AccessibilityService, namely TalkBack. Indeed BrailleIme has a close dependency upon TalkBack,
 * which means that moving BrailleIme out of the TalkBack application would require much work.
 */
open class BrailleIme : InputMethodService() {

  private val instructionSpeechId = AtomicInteger()
  private var tutorialState: State? = null
  private var editBuffer: EditBuffer? = null
  private var originalDefaultUncaughtExceptionHandler: Thread.UncaughtExceptionHandler? = null
  private var orientationCallbackDelegate: OrientationMonitor.Callback? = null
  private var talkbackOffDialog: ViewAttachedDialog? = null
  private var contextMenuDialog: ViewAttachedDialog? = null
  private var tooFewTouchPointsDialog: ViewAttachedDialog? = null
  private var talkBackSuspendDialog: ViewAttachedDialog? = null
  private var layoutOrientator: LayoutOrientator? = null
  private var escapeReminder: EscapeReminder? = null
  private var brailleImeAnalytics: BrailleImeAnalytics? = null
  private var keyboardView: KeyboardView? = null
  private var brailleImeGestureController: BrailleImeGestureController? = null
  private var brailleImeActor: BrailleImeActor? = null
  private var mainHandler: Handler? = null
  private var calibrationAnnouncementHandler: Handler? = null
  private var brailleDisplayConnectedAndNotSuspended = false
  private var orientation = 0
  private var isVisible = false
  private var feedbackManager: FeedbackManager? = null

  /** An interface to notify orientation change. */
  interface OrientationSensitive {
    fun onOrientationChanged(orientation: Int, screenSize: Size)
  }

  companion object {
    private const val TAG = "BrailleIme"

    // Follow the lifecycle of keyboard, onDestroy() when switching to other keyboard. onCreate() when
    // switching from other keyboard.
    private var instance: BrailleIme? = null

    // A note on how the desired hiding of the default IME views is achieved:
    // - Hiding the candidatesArea is simple - simply do not override onCreateCandidatesView.
    // - Hiding the extractArea can be accomplished in either of two ways - either override
    // onEvaluateFullscreenMode() to always return false (which is counterintuitive since this IME is
    // to be fullscreen), or expand the bounds of the inputArea by overriding setInputView(View)
    // and making an ill-advised modification to the LayoutParams of the parent of the
    // BrailleInputView. This code uses the first of these two options; this allows our inputArea,
    // which we furnish in the override of onCreateInputView, to take up the entire view region.
    @Nullable
    private var talkBackForBrailleIme: TalkBackForBrailleIme? = null

    @Nullable
    private var talkBackForBrailleCommon: TalkBackForBrailleCommon? = null

    @Nullable
    private var brailleDisplayForBrailleIme: BrailleDisplayForBrailleIme? = null

    private const val BARD_PACKAGE_NAME = "gov.loc.nls.dtb"
    private const val ANNOUNCE_DELAY_MS =
      800 // Delay, so that it follows previous-IME-is-hidden announcement.
    private const val ANNOUNCE_CALIBRATION_DELAY_MS = 1500
    private const val CALIBRATION_EARCON_DELAY_MS = 500
    private const val CALIBRATION_EARCON_REPEAT_COUNT = 3
    private const val CALIBRATION_ANNOUNCEMENT_REPEAT_MS = 8000

    // An Immutable set includes the granularities which are related to editing.
    private val VALID_GRANULARITIES: ImmutableSet<CursorGranularity> =
      ImmutableSet.of(
        CursorGranularity.CHARACTER,
        CursorGranularity.WORD,
        CursorGranularity.LINE,
        CursorGranularity.PARAGRAPH)

    /** TalkBack invokes this to provide us with the TalkBackForBrailleIme instance. */
    @JvmStatic
    fun initialize(
      context: Context,
      talkBackForBrailleIme: TalkBackForBrailleIme?,
      talkBackForBrailleCommon: TalkBackForBrailleCommon?,
      brailleDisplayForBrailleIme: BrailleDisplayForBrailleIme?,
    ) {
      this.talkBackForBrailleIme = talkBackForBrailleIme
      this.talkBackForBrailleCommon = talkBackForBrailleCommon
      this.brailleDisplayForBrailleIme = brailleDisplayForBrailleIme
      if (talkBackForBrailleIme != null) {
        talkBackForBrailleIme!!.setBrailleImeForTalkBack(
          if (instance == null) null else instance!!.brailleImeForTalkBack)
      }
      if (instance != null && talkBackForBrailleCommon != null) {
        instance!!.feedbackManager =
          FeedbackManager(talkBackForBrailleCommon!!.getFeedBackController())
      }
      BrailleCommonTalkBackSpeaker.getInstance().initialize(talkBackForBrailleCommon!!)
      BrailleImePreferencesActivity.initialize(talkBackForBrailleIme)
      BrailleImeGestureCommandActivity.initialize(talkBackForBrailleIme)
      Utils.setComponentEnabled(context, Constants.BRAILLE_KEYBOARD, true)
    }
  }

  open override fun onCreate() {
    super.onCreate()
    instance = this
    BrailleImeLog.d(TAG, "onCreate")
    // 首次运行补齐"语言"列表，否则下拉里只有一项，"首选输入语言"看起来像切不动。
    BrailleUserPreferences.ensureRecommendedPreferredCodes(this)
    if (talkBackForBrailleCommon != null) {
      feedbackManager = FeedbackManager(talkBackForBrailleCommon!!.getFeedBackController())
    }
    orientation = resources.configuration.orientation
    mainHandler = Handler()
    calibrationAnnouncementHandler = Handler()
    if (brailleDisplayForBrailleIme != null) {
      brailleDisplayConnectedAndNotSuspended =
        brailleDisplayForBrailleIme!!.isBrailleDisplayConnectedAndNotSuspended()
    }
    keyboardView = createKeyboardView()
    escapeReminder = EscapeReminder(this, escapeReminderCallback)
    talkbackOffDialog = TalkBackOffDialog(this, talkBackOffDialogCallback)
    contextMenuDialog = ContextMenuDialog(this, contextMenuDialogCallback)
    tooFewTouchPointsDialog = TooFewTouchPointsDialog(this, tooFewTouchPointsDialogCallback)
    talkBackSuspendDialog = TalkBackSuspendDialog(this, talkBackSuspendDialogCallback)
    tutorialState = State.NONE
    originalDefaultUncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler(localUncaughtExceptionHandler)

    BrailleUserPreferences.getSharedPreferences(this, BrailleUserPreferences.BRAILLE_SHARED_PREFS_FILENAME)
      .registerOnSharedPreferenceChangeListener(onSharedPreferenceChangeListener)

    ContextCompat.registerReceiver(
      this,
      imeChangeListener,
      IntentFilter(Intent.ACTION_INPUT_METHOD_CHANGED),
      ContextCompat.RECEIVER_NOT_EXPORTED)
    val uri = Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
    contentResolver
      .registerContentObserver(uri, false, accessibilityServiceStatusChangeObserver)

    brailleImeAnalytics = BrailleImeAnalytics.getInstance(this)
    OrientationMonitor.init(this)
    layoutOrientator = LayoutOrientator(this, layoutOrientatorCallback)

    if (talkBackForBrailleIme != null) {
      talkBackForBrailleIme!!.setBrailleImeForTalkBack(brailleImeForTalkBack)
    }
  }

  open override fun onBindInput() {
    super.onBindInput()
    BrailleImeLog.d(TAG, "onBindInput")
  }

  open override fun onCreateInputView(): View {
    val viewForImeFrameworks = keyboardView!!.createImeInputView()
    if (viewForImeFrameworks.parent != null) {
      // Remove any old one, to prevent a leak.
      (viewForImeFrameworks.parent as ViewGroup).removeView(viewForImeFrameworks)
    }
    return viewForImeFrameworks
  }

  private fun createKeyboardView(): KeyboardView {
    return if (Utils.useImeSuppliedInputWindow() || brailleDisplayConnectedAndNotSuspended)
      StandardKeyboardView(
        this, keyboardViewCallback, /* fullScreen= */ !brailleDisplayConnectedAndNotSuspended)
    else AccessibilityOverlayKeyboardView(this, keyboardViewCallback)
  }

  override fun onShowInputRequested(flags: Int, configChange: Boolean): Boolean {
    if (talkBackForBrailleIme != null) {
      if (talkBackForBrailleIme!!.isContextMenuExist()) {
        BrailleImeLog.d(TAG, "TalkBack context menu is running.")
        // Reject the request since TalkBack context menu is showing.
        return false
      }
    }

    return super.onShowInputRequested(flags, configChange)
  }

  open override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
    BrailleImeLog.d(TAG, "onStartInputView")
    // 移植版键盘独立注册为系统输入法后，存在一种 TalkBack 原版不会出现的情形：
    // 用户只在「语言和输入法」里勾选了本键盘，却没有开启 BrailleBack 无障碍服务。
    // 此时宿主桥接未被注入，下面 18 处 talkBackForBrailleIme.* 调用会全部抛
    // NullPointerException，键盘一弹出就崩。这里提前拦住并引导用户去开启服务。
    if (talkBackForBrailleIme == null) {
      BrailleImeLog.e(
        TAG, "host bridge is null: BrailleBack accessibility service is not running")
      Toast.makeText(this, getString(R.string.bb_port_ime_need_service), Toast.LENGTH_LONG)
        .show()
      requestHideSelf(0)
      return
    }
    window!!.setTitle(Utils.getBrailleKeyboardDisplayName(this))

    // Surprisingly, framework sometimes invokes onStartInputView just after the screen turns off;
    // therefore we first confirm that the screen is indeed on before invoking activateIfNeeded.
    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
    if (pm.isInteractive) {
      if (showKeyboardView() && !restarting) {
        if (isBrailleDisplayStateChanged()) {
          updateInputView()
        } else {
          createViewContainerAndAddView()
        }
      }
    } else {
      hideSelf()
    }
  }

  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    if (orientation != newConfig.orientation) {
      orientation = newConfig.orientation
      keyboardView!!.onOrientationChanged(newConfig.orientation)
    }
  }

  @SuppressLint("MissingSuperCall")
  override fun onEvaluateInputViewShown(): Boolean {
    // Always show the Braille IME. The super implementation only allows an InputMethodService
    // when there is no hardware keyboard, or the hardware keyboard is hidden.
    return true
  }

  open override fun onFinishInputView(finishingInput: Boolean) {
    super.onFinishInputView(finishingInput)
    // Of the teardown methods, this is the most reliable, so we use it to deactivate.
    BrailleImeLog.d(TAG, "onFinishInputView")
    dismissDialogs()
    if (!keyboardView!!.isViewContainerCreated()) {
      BrailleImeLog.d(TAG, "already deactivated")
      // Deactivation is not needed because we're already deactivated (this is not an error).
      return
    }
    keyboardView!!.tearDown()
  }

  open override fun onEvaluateFullscreenMode(): Boolean {
    // Why return false here? - see the note atop the class regarding how we suppress Views.
    return false
  }

  open override fun onDestroy() {
    super.onDestroy()
    BrailleImeLog.d(TAG, "onDestroy")
    // 单注册合并后本类会随唯一注册的 BrailleIME 反复创建/销毁。
    // 原实现只在 onCreate() 里替换进程级 UncaughtExceptionHandler，却从不还原，
    // 于是 handler 长期持有已销毁的 IME 实例，造成累积泄漏。这里在销毁时还原；
    // 判断当前 handler 是否仍是自己，避免覆盖别的组件后来设置的处理链。
    if (Thread.getDefaultUncaughtExceptionHandler() == localUncaughtExceptionHandler) {
      Thread.setDefaultUncaughtExceptionHandler(originalDefaultUncaughtExceptionHandler)
    }
    instance = null
    if (talkBackForBrailleIme != null) {
      talkBackForBrailleIme!!.setBrailleImeForTalkBack(null)
    }
    BrailleUserPreferences.getSharedPreferences(this, BrailleUserPreferences.BRAILLE_SHARED_PREFS_FILENAME)
      .unregisterOnSharedPreferenceChangeListener(onSharedPreferenceChangeListener)
    unregisterReceiver(imeChangeListener)
    contentResolver.unregisterContentObserver(accessibilityServiceStatusChangeObserver)
    keyboardView!!.tearDown()
    keyboardView = null
    brailleImeAnalytics!!.sendAllLogs()
  }

  @CanIgnoreReturnValue
  private fun activateIfNeeded(): Boolean {
    BrailleImeLog.d(TAG, "activateIfNeeded")
    if (Utils.isPhonePermissionGranted(this)) {
      val telephonyManager =
        getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
      telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
    }
    if (talkBackForBrailleIme!!.isVibrationFeedbackEnabled()) {
      BrailleImeVibrator.getInstance(this).enable()
    }
    talkBackForBrailleIme!!.resetGranularity()
    if (tutorialState == State.NONE) {
      escapeReminder!!.startTimer()
    }
    activateBrailleIme()
    createEditBuffer()
    OrientationMonitor.getInstance().enable()
    OrientationMonitor.getInstance().registerCallback(orientationMonitorCallback)
    layoutOrientator!!.startIfNeeded()
    updateNavigationBarColor()
    brailleImeActor =
      BrailleImeActor(
        this,
        brailleImeActorCallback,
        talkBackForBrailleIme,
        feedbackManager)
    brailleImeGestureController = BrailleImeGestureController(this, brailleImeActor)
    return true
  }

  private fun isBrailleDisplayStateChanged(): Boolean {
    val brailleDisplayConnectedAndNotIgnored =
      brailleDisplayForBrailleIme != null &&
        brailleDisplayForBrailleIme!!.isBrailleDisplayConnectedAndNotSuspended()
    if (this.brailleDisplayConnectedAndNotSuspended != brailleDisplayConnectedAndNotIgnored) {
      this.brailleDisplayConnectedAndNotSuspended = brailleDisplayConnectedAndNotIgnored
      return true
    }
    return false
  }

  private fun showKeyboardView(): Boolean {
    if (keyboardView == null) {
      BrailleImeLog.e(TAG, "keyboardView is null. Activate should not invoke before onCreate()")
      return false
    }
    if (!isInputViewShown()) {
      // Defer to superclass, if it knows that our input view is not showing (this is not an error).
      return false
    }
    if (talkBackForBrailleIme == null ||
      talkBackForBrailleIme!!.getServiceStatus() == ServiceStatus.OFF
    ) {
      BrailleImeLog.e(TAG, "talkBackForBrailleIme is null or Talkback is off.")
      showTalkBackOffDialog()
      return false
    } else if (talkBackForBrailleIme!!.getServiceStatus() == ServiceStatus.SUSPEND) {
      BrailleImeLog.e(TAG, "Talkback is suspend.")
      showTalkBackSuspendDialog()
      return false
    }
    if (FeatureFlagReader.enableBrailleImeOnDeviceWithoutFivePointers(applicationContext) &&
      !Utils.isMultiTouchSupported(this)
    ) {
      BrailleUserPreferences.setTutorialFinished(applicationContext)
    }
    if (shouldShowTooFewTouchPointsDialog()) {
      showTooFewTouchPointsDialog()
      return false
    }
    return true
  }

  private fun createViewContainerAndAddView() {
    BrailleImeLog.d(TAG, "createViewContainerAndAddView")
    keyboardView!!.setWindowManager(talkBackForBrailleIme!!.getWindowManager())
    keyboardView!!.updateImeView()
    keyboardView!!.createViewContainer()
    if (brailleDisplayConnectedAndNotSuspended) {
      keyboardView!!.createAndAddStripView(brailleDisplayKeyboardCallback)
    } else if (tutorialState != State.NONE ||
      BrailleUserPreferences.shouldLaunchTutorial(applicationContext)
    ) {
      if (tutorialState == State.NONE) {
        // Launch tutorial for the first usage.
        tutorialState = State.INTRO
      }
      // Restore to previous tutorial state.
      createAndAddTutorialView()
    } else if (!keyboardView!!.isInputViewCreated()) {
      keyboardView!!.createAndAddInputView(inputPlaneCallback, isCurrentTableTopMode())
    }
  }

  private fun createAndAddTutorialView() {
    BrailleImeLog.d(TAG, "createAndAddTutorialView")
    // Correct tutorial state according to phone size.
    if (BrailleUtils.isPhoneSizedDevice(resources)) {
      if (tutorialState == State.HOLD_6_FINGERS) {
        tutorialState = State.ROTATE_ORIENTATION
      }
    } else {
      if (tutorialState == State.ROTATE_ORIENTATION ||
        tutorialState == State.ROTATE_ORIENTATION_CONTINUE
      ) {
        tutorialState = State.HOLD_6_FINGERS
      }
    }
    keyboardView!!.createAndAddTutorialView(tutorialState!!, tutorialCallback)
    talkBackForBrailleIme!!.disableSilenceOnProximity()
  }

  private fun activateBrailleIme() {
    BrailleImeLog.d(TAG, "activateBrailleIme")
    if (talkBackForBrailleIme != null && isInputViewShown()) {
      // 移植改造：把键盘内容容器的引用交给宿主，宿主据此用无障碍公告（TYPE_ANNOUNCEMENT）
      // 把播报内容转给屏幕阅读器，避免"读屏读一段、宿主自带 TTS 读另一段"的双声。
      if (talkBackForBrailleCommon != null) {
        talkBackForBrailleCommon!!.setAnnouncementView(keyboardView!!.getViewContainer())
      }
      talkBackForBrailleIme!!.onBrailleImeActivated(
        !brailleDisplayConnectedAndNotSuspended,
        Utils.useImeSuppliedInputWindow(),
        // Region might be null for short time before onTalkBackResumed() is called.
        keyboardView!!.obtainImeViewRegion().orElse(null))
      if (brailleDisplayForBrailleIme != null &&
        brailleDisplayConnectedAndNotSuspended &&
        !isVisible
      ) {
        isVisible = true
        brailleDisplayForBrailleIme!!.onImeVisibilityChanged(true)
      }
    }
  }

  private fun deactivateBrailleIme() {
    BrailleImeLog.d(TAG, "deactivateBrailleIme")
    if (talkBackForBrailleIme != null) {
      talkBackForBrailleIme!!.onBrailleImeInactivated(Utils.useImeSuppliedInputWindow())
    }
    if (brailleDisplayForBrailleIme != null &&
      brailleDisplayConnectedAndNotSuspended &&
      isVisible
    ) {
      isVisible = false
      brailleDisplayForBrailleIme!!.onImeVisibilityChanged(false)
    }
  }

  private fun showTalkBackOffDialog() {
    // When screen rotates, onStartInputView is called and if there is a dialog showing, keep it
    // showing instead of adding a new one.
    if (!talkbackOffDialog!!.isShowing()) {
      brailleImeAnalytics!!.logTalkBackOffDialogDisplay()
      keyboardView!!.showViewAttachedDialog(talkbackOffDialog!!)
    }
  }

  private fun showTalkBackSuspendDialog() {
    // When screen rotates, onStartInputView is called and if there is a dialog showing, keep it
    // showing instead of adding a new one.
    if (!talkBackSuspendDialog!!.isShowing()) {
      brailleImeAnalytics!!.logTalkBackOffDialogDisplay()
      keyboardView!!.showViewAttachedDialog(talkBackSuspendDialog!!)
    }
  }

  private fun showTooFewTouchPointsDialog() {
    // When screen rotates, onStartInputView is called and if there is a dialog showing, keep it
    // showing instead of adding a new one.
    if (!tooFewTouchPointsDialog!!.isShowing()) {
      brailleImeAnalytics!!.logFewTouchPointsDialogDisplay()
      keyboardView!!.showViewAttachedDialog(tooFewTouchPointsDialog!!)
    }
  }

  private fun createEditBuffer() {
    val code = BrailleUserPreferences.readCurrentActiveInputCodeAndCorrect(this)
    val contractedMode =
      BrailleUserPreferences.readContractedMode(this) && code.isSupportsContracted(this)
    BrailleImeLog.d(
      TAG, "Code: " + code.getUserFacingName(this) + " contracted: " + contractedMode)

    val translatorFactory = BrailleUserPreferences.readTranslatorFactory(this)
    editBuffer =
      BrailleLanguages.createEditBuffer(
        this,
        BrailleCommonTalkBackSpeaker.getInstance(),
        code,
        translatorFactory,
        contractedMode)
    // 诊断：确认当前使用的盲文码、是否缩略、以及编辑缓冲类型（tag=BBTranslate）。
    android.util.Log.d(
      "BBTranslate",
      "createEditBuffer code=" +
        code.name +
        " contracted=" +
        contractedMode +
        " factory=" +
        translatorFactory.javaClass.simpleName +
        " buffer=" +
        editBuffer!!.javaClass.simpleName)
  }

  /**
   * Returns true if device can not support at least 5 pointers and not connected to braille
   * display. Otherwise, returns false.
   */
  private fun shouldShowTooFewTouchPointsDialog(): Boolean {
    if (FeatureFlagReader.enableBrailleImeOnDeviceWithoutFivePointers(applicationContext) &&
      brailleDisplayForBrailleIme != null &&
      brailleDisplayForBrailleIme!!.isBrailleDisplayConnectedAndNotSuspended()
    ) {
      return false
    }
    return !Utils.isMultiTouchSupported(this)
  }

  override fun onComputeInsets(outInsets: InputMethodService.Insets) {
    if (Utils.useImeSuppliedInputWindow()) {
      // Set the contentTopInsets, which is measured from the top edge of the display, positively
      // downward, to be as tall as possible allowing the underlying framework to provide plenty of
      // vertical space to layout the underlying Activity.  In the absence of setting this value to
      // be large, the underlying Activity, in case it uses windowSoftInputMode adjustResize or
      // adjustUnspecified, will have very little (or zero) vertical room to perform a valid layout
      // - and that causes many problems, such as the IME getting closed or the Editor not receiving
      // our input.
      outInsets.contentTopInsets = Utils.getDisplaySizeInPixels(this).height
    }
    if (keyboardView!!.obtainImeViewRegion().isPresent) {
      val region = keyboardView!!.obtainImeViewRegion().get()
      if (Build.VERSION.SDK_INT == Build.VERSION_CODES.P) {
        if (brailleDisplayConnectedAndNotSuspended) {
          outInsets.visibleTopInsets = region.bounds.top
        } else {
          // In Android P, we need to manually set the size of the outInsets which represent the
          // area north of the IME window, otherwise any dialog attached to the unused IME window
          // will not show any foreground contents. But we also need to take care not to set this
          // insets area to be the entire screen, because doing that causes the inputView to be
          // ignored by an accessibility framework class responsible for sending info to Talkback,
          // and this prevents the proper announcement of the IME by TalkBack.
          outInsets.visibleTopInsets = region.bounds.bottom - 1
        }
        outInsets.contentTopInsets = outInsets.visibleTopInsets
      }
    }
  }

  @CanIgnoreReturnValue
  private fun deactivateIfNeeded(): Boolean {
    BrailleImeLog.d(TAG, "deactivateIfNeeded")
    if (Utils.isPhonePermissionGranted(this)) {
      val telephonyManager =
        getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
      telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE)
    }
    escapeReminder!!.cancelTimer()
    layoutOrientator!!.stop()
    BrailleImeVibrator.getInstance(this).disable()
    if (editBuffer != null && isConnectionValid()) {
      editBuffer!!.commit(getImeConnection())
    }
    deactivateBrailleIme()
    calibrationAnnouncementHandler!!.removeCallbacksAndMessages(null)
    OrientationMonitor.getInstance().unregisterCallback()
    OrientationMonitor.getInstance().disable()
    return true
  }

  private fun reactivate(state: State) {
    tutorialState = state
    keyboardView!!.tearDown()
    createViewContainerAndAddView()
  }

  private fun hideSelf() {
    requestHideSelf(0)
  }

  /**
   * Performs the 'actions' specified, via imeOptions, by the application such as 'Send'. A typical
   * IME usually surfaces the trigger for such an action with an (often blue-colored) action button.
   *
   * <p>By far the most common case is for an application to specify a single action (as opposed to
   * multiple). We don't currently support the distinguishment of multiple actions in that case.
   *
   * <p>Return {@code true} if the keyboard should remain showing.
   */
  private fun performEditorAction(inputConnection: InputConnection?) {
    val editorInfo = currentInputEditorInfo!!
    val editorAction = editorInfo.imeOptions and EditorInfo.IME_MASK_ACTION
    BrailleImeLog.d(TAG, "performEnterAction editorAction = $editorAction")
    if (editorAction != EditorInfo.IME_ACTION_UNSPECIFIED &&
      editorAction != EditorInfo.IME_ACTION_NONE
    ) {
      if (TextUtils.equals(editorInfo.packageName, Constants.ANDROID_MESSAGES_PACKAGE_NAME)) {
        // Messages uses async thread to check conditions when performing submit. We pend the task
        // with 50 millis seconds to avoid perform action failed.
        Handler().postDelayed({ inputConnection!!.performEditorAction(editorAction) }, 50)
      } else {
        inputConnection!!.performEditorAction(editorAction)
      }
      if (editorAction == EditorInfo.IME_ACTION_NEXT) {
        BrailleCommonTalkBackSpeaker.getInstance().speak(getString(R.string.perform_action_next))
      } else {
        BrailleCommonTalkBackSpeaker.getInstance()
          .speak(getString(R.string.perform_action_submitting))
      }
    } else {
      // 输入框没声明提交动作（IME_ACTION_NONE / IME_ACTION_UNSPECIFIED）。原来这里静默返回，
      // 调用方又已经先把键盘收掉了，用户会以为"手势失效"。现在明确告知没有提交动作。
      BrailleCommonTalkBackSpeaker.getInstance()
        .speak(getString(R.string.perform_action_unavailable))
    }
  }

  private fun updateInputView() {
    if (keyboardView != null) {
      keyboardView!!.tearDown()
    }
    keyboardView = createKeyboardView()
    setInputView(keyboardView!!.createImeInputView())
    createViewContainerAndAddView()
  }

  /**
   * Attempt to exit this IME and switch to another.
   *
   * <p>First, try switching to Gboard if it exists. Otherwise, switch to the next IME if one
   * exists.
   *
   * <p>If switching to the next IME fails (which can happen because there are no other IMEs
   * installed and enabled OR for an unknown reason (which DOES occur on some phones), show the
   * system IME picker if there is another IME installed and enabled.
   *
   * <p>Finally, if there are not other IMEs installed and enabled, launch IME Settings.
   */
  @VisibleForTesting
  internal fun switchToNextInputMethod(): Boolean {
    if (talkBackForBrailleIme != null) {
      talkBackForBrailleIme!!.interruptSpeak()
    }
    if (isConnectionValid() && editBuffer != null) {
      // Commit holdings here, otherwise InputConnect will become invalid after switch keyboard.
      editBuffer!!.commit(getImeConnection())
    }
    if (!KeyboardUtils.areMultipleImesEnabled(this)) {
      // Show a toast and bring up Ime settings to user.
      Toast.makeText(this, getString(R.string.bring_ime_settings_page), Toast.LENGTH_SHORT).show()
      val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
      intent.addFlags(
        Intent.FLAG_ACTIVITY_NEW_TASK or
          Intent.FLAG_ACTIVITY_CLEAR_TASK or
          Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
      startActivity(intent)
      return false
    }
    var succeeded: Boolean
    // Default switch to gboard.
    val inputMethodInfoId = KeyboardUtils.getEnabledImeId(this, Constants.GBOARD_PACKAGE_NAME)
    if (!TextUtils.isEmpty(inputMethodInfoId)) {
      // This api doesn't tell us switch succeed or not. Assume it switch successfully.
      switchInputMethod(inputMethodInfoId)
      succeeded = true
    } else if (BuildVersionUtils.isAtLeastP()) {
      succeeded = switchToNextInputMethod(false)
    } else {
      val token = window!!.window!!.attributes.token
      val inputMethodManager =
        getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
      succeeded = inputMethodManager.switchToNextInputMethod(token, false)
    }
    // REFERTO: Switch to next keyboard manually by giving ime id.
    if (!succeeded) {
      // This api doesn't tell us switch succeed or not. Assume it switch successfully.
      switchInputMethod(KeyboardUtils.getNextEnabledImeId(this))
    }
    return true
  }

  private fun dismissDialogs() {
    talkbackOffDialog!!.dismiss()
    contextMenuDialog!!.dismiss()
    tooFewTouchPointsDialog!!.dismiss()
    talkBackSuspendDialog!!.dismiss()
  }

  // Starts to log keyboard session only in non tutorial mode.
  private fun startAnalyticsPossibly() {
    if (tutorialState == State.NONE) {
      brailleImeAnalytics!!.startSession()
    }
  }

  private fun updateNavigationBarColor() {
    window!!.window!!.setNavigationBarColor(
      ContextCompat.getColor(
        this,
        if (brailleDisplayConnectedAndNotSuspended) R.color.braille_keyboard_background
        else R.color.google_transparent))
  }

  private fun isEightDotsBraille(): Boolean {
    return BrailleUserPreferences.isCurrentActiveInputCodeEightDot(applicationContext)
  }

  private fun getTwoStepsCalibrationAnnounceString(fingersPattern: FingersPattern): String {
    val reverseDot = BrailleUserPreferences.readReverseDotsMode(this)
    val sb = StringBuilder()
    return when (fingersPattern) {
      FingersPattern.NO_FINGERS,
      FingersPattern.FIVE_FINGERS,
      FingersPattern.SIX_FINGERS,
      FingersPattern.SEVEN_FINGERS -> {
        sb.append(
            getString(
              R.string.calibration_step1_hold_left_or_right_finger_announcement,
              getString(if (reverseDot) R.string.right_hand else R.string.left_hand)))
          .append(" ")
          .append(
            getString(
              if (isEightDotsBraille())
                R.string.calibration_hold_left_or_right_four_finger_announcement
              else R.string.calibration_hold_left_or_right_three_finger_announcement,
              getString(if (reverseDot) R.string.right_hand else R.string.left_hand)))
        sb.toString()
      }
      FingersPattern.FIRST_THREE_FINGERS -> {
        sb.append(
            getString(
              R.string.calibration_step2_hold_left_or_right_finger_announcement,
              getString(if (reverseDot) R.string.left_hand else R.string.right_hand)))
          .append(" ")
          .append(
            getString(
              if (isEightDotsBraille())
                R.string.calibration_hold_left_or_right_four_finger_announcement
              else R.string.calibration_hold_left_or_right_three_finger_announcement,
              getString(if (reverseDot) R.string.left_hand else R.string.right_hand)))
        sb.toString()
      }
      FingersPattern.FIRST_FOUR_FINGERS -> {
        sb.append(
            getString(
              R
                .string
                .eightDot_braille_calibration_step2_hold_left_or_right_finger_announcement,
              getString(if (reverseDot) R.string.left_hand else R.string.right_hand)))
          .append(" ")
          .append(
            getString(
              if (isEightDotsBraille())
                R.string.calibration_hold_left_or_right_four_finger_announcement
              else R.string.calibration_hold_left_or_right_three_finger_announcement,
              getString(if (reverseDot) R.string.left_hand else R.string.right_hand)))
        sb.toString()
      }
      else -> ""
    }
  }

  private fun getRepeatedTwoStepCalibrationAnnounceString(fingersPattern: FingersPattern): String {
    val reverseDot = BrailleUserPreferences.readReverseDotsMode(this)
    return when (fingersPattern) {
      FingersPattern.NO_FINGERS,
      FingersPattern.FIVE_FINGERS,
      FingersPattern.SIX_FINGERS,
      FingersPattern.SEVEN_FINGERS ->
        getString(
          if (isEightDotsBraille())
            R.string.calibration_hold_left_or_right_four_finger_announcement
          else R.string.calibration_hold_left_or_right_three_finger_announcement,
          getString(if (reverseDot) R.string.right_hand else R.string.left_hand))
      FingersPattern.FIRST_THREE_FINGERS, FingersPattern.FIRST_FOUR_FINGERS ->
        getString(
          if (isEightDotsBraille())
            R.string.calibration_hold_left_or_right_four_finger_announcement
          else R.string.calibration_hold_left_or_right_three_finger_announcement,
          getString(if (reverseDot) R.string.left_hand else R.string.right_hand))
      else -> ""
    }
  }

  private fun speakAnnouncementRepeatedly(announcement: CharSequence, delay: Int) {
    calibrationAnnouncementHandler!!.removeCallbacksAndMessages(null)
    // Do not use the delay in Talkback because we want to be able to cancel it.
    calibrationAnnouncementHandler!!.postDelayed(
      {
        BrailleCommonTalkBackSpeaker.getInstance()
          .speak(announcement, getRepeatAnnouncementRunnable(announcement))
      },
      delay.toLong())
  }

  private fun getRepeatAnnouncementRunnable(repeatedAnnouncement: CharSequence): UtteranceCompleteRunnable {
    val speechId = instructionSpeechId.incrementAndGet()
    return UtteranceCompleteRunnable { status ->
      if (speechId == instructionSpeechId.get() && keyboardView!!.inTwoStepCalibration()) {
        speakAnnouncementRepeatedly(repeatedAnnouncement, CALIBRATION_ANNOUNCEMENT_REPEAT_MS)
      }
    }
  }

  private fun isTalkBackEnabled(): Boolean {
    return AccessibilityServiceCompatUtils.isAccessibilityServiceEnabled(
      this, Constants.TALKBACK_SERVICE.flattenToShortString())
  }

  private val layoutOrientatorCallback: LayoutOrientatorCallback =
    object : LayoutOrientatorCallback {
      override fun useSensorsToDetectLayout(): Boolean {
        return BrailleUserPreferences.readLayoutMode(this@BrailleIme) == TouchDots.AUTO_DETECT &&
          !keyboardView!!.isTutorialShown() &&
          !brailleDisplayConnectedAndNotSuspended
      }

      override fun onDetectionChanged(isTabletop: Boolean, isFirstChangedEvent: Boolean) {
        val layout =
          getString(
            if (isTabletop) R.string.switch_to_tabletop_announcement else R.string.screen_away)
        var calibrationTips = ""
        if (keyboardView!!.inTwoStepCalibration()) {
          if (!isFirstChangedEvent) {
            calibrationTips = getTwoStepsCalibrationAnnounceString(FingersPattern.NO_FINGERS)
          }
        } else if (isTabletop) {
          calibrationTips =
            getString(
              R.string.calibration_tip_announcement,
              BrailleUserPreferences.getCurrentTypingLanguageType(applicationContext).getDotCount())
        }
        if (isFirstChangedEvent) {
          val finalCalibrationTips = calibrationTips
          BrailleCommonTalkBackSpeaker.getInstance().speak(layout, ANNOUNCE_DELAY_MS)
          calibrationAnnouncementHandler!!.postDelayed(
            { BrailleCommonTalkBackSpeaker.getInstance().speak(finalCalibrationTips) },
            ANNOUNCE_DELAY_MS.toLong())
        } else {
          BrailleCommonTalkBackSpeaker.getInstance()
            .speak(layout, TalkBackSpeaker.AnnounceType.INTERRUPT)
          calibrationAnnouncementHandler!!.removeCallbacksAndMessages(null)
          BrailleCommonTalkBackSpeaker.getInstance()
            .speak(
              calibrationTips,
              if (keyboardView!!.inTwoStepCalibration())
                getRepeatAnnouncementRunnable(
                  getRepeatedTwoStepCalibrationAnnounceString(FingersPattern.NO_FINGERS))
              else null)
        }
        keyboardView!!.setTableMode(isTabletop)
      }
    }

  private val localUncaughtExceptionHandler: Thread.UncaughtExceptionHandler =
    object : Thread.UncaughtExceptionHandler {
      override fun uncaughtException(thread: Thread, throwable: Throwable) {
        BrailleImeLog.e(TAG, "Uncaught exception", throwable)
        try {
          keyboardView!!.tearDown()
          if (isInputViewShown()) {
            switchToNextInputMethod()
          }
        } catch (e: RuntimeException) {
          BrailleImeLog.e(TAG, "Uncaught exception in handler", throwable)
        } finally {
          if (originalDefaultUncaughtExceptionHandler != null) {
            originalDefaultUncaughtExceptionHandler!!.uncaughtException(thread, throwable)
          }
        }
      }
    }

  private val imeChangeListener: BroadcastReceiver =
    object : BroadcastReceiver() {
      override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action == Intent.ACTION_INPUT_METHOD_CHANGED) {
          dismissDialogs()
        }
      }
    }

  private val brailleImeForTalkBack: BrailleImeForTalkBack =
    object : BrailleImeForTalkBack {
      override fun onTalkBackSuspended() {
        BrailleImeLog.d(TAG, "onTalkBackSuspended")
        // We might get service state off when TalkBack turns off, but we'll handle it in
        // accessibilityServiceStatusChangeObserver.
        if (isInputViewShown() &&
          talkBackForBrailleIme!!.getServiceStatus() == ServiceStatus.SUSPEND
        ) {
          if (keyboardView!!.isTutorialShown()) {
            brailleImeAnalytics!!.logTutorialFinishedByTalkbackStop()
          }
          if (KeyboardUtils.areMultipleImesEnabled(this@BrailleIme)) {
            switchToNextInputMethod()
          } else {
            keyboardView!!.tearDown()
            showTalkBackSuspendDialog()
          }
        }
      }

      override fun onTalkBackResumed() {
        BrailleImeLog.d(TAG, "onTalkBackResumed")
        // This callback won't be triggered when service state changes from off to on because it's
        // set to null when off so we register it back in
        // accessibilityServiceStatusChangeObserver.
        if (isInputViewShown()) {
          dismissDialogs()
          createViewContainerAndAddView()
        }
      }

      override fun isTouchInteracting(): Boolean {
        return !brailleDisplayConnectedAndNotSuspended && keyboardView!!.isTouchInteracting()
      }

      override fun getBrailleImeForBrailleDisplay(): BrailleImeForBrailleDisplay {
        return brailleImeForBrailleDisplay
      }

      override fun onScreenDim() {
        keyboardView!!.setKeyboardViewTransparent(true)
      }

      override fun onScreenBright() {
        keyboardView!!.setKeyboardViewTransparent(false)
      }

      override fun isGranularityValid(cursorGranularity: CursorGranularity): Boolean {
        return VALID_GRANULARITIES.contains(cursorGranularity)
      }

      override fun isBrailleKeyboardActivated(): Boolean {
        return isInputViewShown()
      }
    }

  // We need this because in some situations BrailleImeForTalkBack is set to null. There is no
  // callback (set when onBrailleImeActivated()) in TB for us to know it turns off or on. For
  // example, when TalkBackOff dialog shows up, first page of tutorial or context menu shows up.
  // Note: TalkBack turns from active to suspended and suspended to resumed will not come in through
  // this callback.
  private val accessibilityServiceStatusChangeObserver: ContentObserver =
    object : ContentObserver(Handler()) {
      override fun onChange(selfChange: Boolean) {
        super.onChange(selfChange)
        if (!isInputViewShown()) {
          return
        }
        if (isTalkBackEnabled()) {
          BrailleImeLog.d(TAG, "TalkBack becomes active.")
          // This listener is triggered before TB service is ready. Call activateIfNeeded() will
          // get service state is off so we need to set BrailleImeForTalkBack in TB to get
          // onTalkBackResumed() to make sure the state has been set to active.
          activateBrailleIme()
        } else {
          BrailleImeLog.d(TAG, "TalkBack becomes inactive.")
          if (KeyboardUtils.areMultipleImesEnabled(this@BrailleIme)) {
            switchToNextInputMethod()
          } else {
            keyboardView!!.tearDown()
            showTalkBackOffDialog()
          }
        }
      }
    }

  private val brailleImeActorCallback: BrailleImeActor.Callback =
    object : BrailleImeActor.Callback {
      override fun hideBrailleKeyboard() {
        hideSelf()
        escapeReminder!!.increaseExitKeyboardCounter()
      }

      override fun switchToNextInputMethod() {
        this@BrailleIme.switchToNextInputMethod()
        escapeReminder!!.increaseExitKeyboardCounter()
      }

      override fun showContextMenu() {
        keyboardView!!.showViewAttachedDialog(contextMenuDialog!!)
        brailleImeAnalytics!!.logGestureActionOpenOptionsMenu()
        escapeReminder!!.increaseOptionDialogCounter()
      }

      override fun performEditorAction() {
        this@BrailleIme.performEditorAction(getImeConnection().inputConnection)
      }

      override fun isConnectionValid(): Boolean {
        return this@BrailleIme.isConnectionValid()
      }

      override fun getImeConnection(): ImeConnection {
        return this@BrailleIme.getImeConnection()
      }

      override fun getEditBuffer(): EditBuffer? {
        return editBuffer
      }
    }

  private val orientationMonitorCallback: OrientationMonitor.Callback =
    object : OrientationMonitor.Callback {
      override fun onOrientationChanged(orientation: OrientationMonitor.Orientation) {
        if (orientationCallbackDelegate != null) {
          orientationCallbackDelegate!!.onOrientationChanged(orientation)
        }
      }
    }

  private val inputPlaneCallback: BrailleInputView.Callback =
    object : BrailleInputView.Callback {
      override fun onSwipeProduced(swipe: Swipe): Boolean {
        if (brailleImeGestureController!!.performSwipeAction(swipe)) {
          showOnBrailleDisplay()
          if (!brailleDisplayConnectedAndNotSuspended) {
            escapeReminder!!.restartTimer()
          }
          return true
        }
        return false
      }

      override fun onDotHoldAndDotSwipe(dotHoldSwipe: DotHoldSwipe): Boolean {
        if (brailleImeGestureController!!.performDotHoldAndSwipeAction(dotHoldSwipe)) {
          showOnBrailleDisplay()
          return true
        }
        return false
      }

      override fun isCalibrationHoldRecognized(
        inTwoStepCalibration: Boolean,
        pointersHeldCount: Int,
      ): Boolean {
        return isSixDotCalibration(pointersHeldCount) ||
          isEightDotCalibration(pointersHeldCount) ||
          (inTwoStepCalibration && isConfirmedTwoStepCalibration(pointersHeldCount))
      }

      override fun onHoldProduced(pointersHeldCount: Int): Boolean {
        return brailleImeGestureController!!.performDotHoldAction(pointersHeldCount)
      }

      @Nullable
      override fun onBrailleProduced(brailleChar: BrailleCharacter): String? {
        val result = brailleImeGestureController!!.performTyping(brailleChar)
        if (!TextUtils.isEmpty(result)) {
          escapeReminder!!.restartTimer()
        }
        return result
      }

      /**
       * 把还没分格的点流交给编辑缓冲，请它切出已经能确定的音节。
       *
       * <p>键盘不再靠停顿猜格边界（那会把慢慢按的一格切断、把连着按的两格粘住），改成整串
       * 点按音节识别。没有连接时返回 {@code null}，键盘会继续攒着，等切回编辑缓冲再处理。
       */
      @Nullable
      override fun segmentDotStream(dotStream: String, force: Boolean): List<BrailleCharacter>? {
        val buffer = editBuffer
        return if (buffer == null) null else buffer.segmentDotStream(dotStream, force)
      }

      override fun onCalibration(
        calibration: CalibrationTriggeredType,
        fingersPattern: FingersPattern,
      ): Boolean {
        calibrationAnnouncementHandler!!.removeCallbacksAndMessages(null)
        var processed = false
        if (isCalibrationSucceeded(fingersPattern)) {
          playCalibrationDoneSoundAndAnnouncement(
            getString(
              if (fingersPattern == FingersPattern.REMAINING_THREE_FINGERS ||
                fingersPattern == FingersPattern.REMAINING_FOUR_FINGERS
              ) R.string.remaining_calibration_finish_announcement
              else R.string.calibration_finish_announcement))
          keyboardView!!.saveInputViewPoints()
          escapeReminder!!.startTimer()
          processed = true
          brailleImeAnalytics!!.logCalibrationFinish(
            mapCalibrationToType(calibration), isCurrentTableTopMode(), isEightDotsBraille())
        } else if ((calibration == CalibrationTriggeredType.MANUAL &&
            fingersPattern == FingersPattern.NO_FINGERS) ||
          fingersPattern == FingersPattern.FIVE_FINGERS ||
          fingersPattern == FingersPattern.SIX_FINGERS ||
          fingersPattern == FingersPattern.SEVEN_FINGERS
        ) {
          escapeReminder!!.cancelTimer()
          processed = true
          brailleImeAnalytics!!.logCalibrationStarted(
            mapCalibrationToType(calibration), isCurrentTableTopMode(), isEightDotsBraille())
          // Add 6/7 dots calibration for 8-dot braille. Wait for showing braille keyboard and
          // layout mode announcement finish.
          calibrationAnnouncementHandler!!.postDelayed(
            {
              BrailleCommonTalkBackSpeaker.getInstance()
                .speak(
                  getTwoStepsCalibrationAnnounceString(fingersPattern),
                  getRepeatAnnouncementRunnable(
                    getRepeatedTwoStepCalibrationAnnounceString(fingersPattern)))
            },
            ANNOUNCE_CALIBRATION_DELAY_MS.toLong())
        } else if (fingersPattern == FingersPattern.FIRST_THREE_FINGERS ||
          fingersPattern == FingersPattern.FIRST_FOUR_FINGERS
        ) {
          processed = true
          playCalibrationDoneSoundAndAnnouncement(
            getTwoStepsCalibrationAnnounceString(fingersPattern))
        }
        return processed
      }

      override fun onCalibrationFailed(calibration: CalibrationTriggeredType) {
        brailleImeAnalytics!!.logCalibrationFailed(
          mapCalibrationToType(calibration), isCurrentTableTopMode(), isEightDotsBraille())
        calibrationAnnouncementHandler!!.removeCallbacksAndMessages(null)
        BrailleCommonTalkBackSpeaker.getInstance()
          .speak(
            getString(R.string.calibration_fail_announcement),
            TalkBackSpeaker.AnnounceType.INTERRUPT)
        if (calibration != CalibrationTriggeredType.MANUAL) {
          BrailleCommonTalkBackSpeaker.getInstance()
            .speak(
              getString(
                R.string.calibration_fail_try_again_announcement,
                BrailleUserPreferences.getCurrentTypingLanguageType(applicationContext).getDotCount()))
        }
      }

      override fun onTwoStepCalibrationRetry(isFirstStep: Boolean) {
        calibrationAnnouncementHandler!!.removeCallbacksAndMessages(null)
        val reverseDot = BrailleUserPreferences.readReverseDotsMode(this@BrailleIme)
        val announcement =
          getString(
            if (isEightDotsBraille())
              R.string.calibration_hold_left_or_right_four_finger_announcement
            else R.string.calibration_hold_left_or_right_three_finger_announcement,
            if (isFirstStep)
              getString(if (reverseDot) R.string.right_hand else R.string.left_hand)
            else getString(if (reverseDot) R.string.left_hand else R.string.right_hand))
        BrailleCommonTalkBackSpeaker.getInstance()
          .speak(
            announcement,
            TalkBackSpeaker.AnnounceType.INTERRUPT,
            getRepeatAnnouncementRunnable(announcement))
      }

      private fun isCalibrationSucceeded(fingersPattern: FingersPattern): Boolean {
        val currentInputCodeEightDot = isEightDotsBraille()
        return (fingersPattern == FingersPattern.REMAINING_THREE_FINGERS &&
          !currentInputCodeEightDot) ||
          (fingersPattern == FingersPattern.REMAINING_FOUR_FINGERS &&
            currentInputCodeEightDot) ||
          (fingersPattern == FingersPattern.SIX_FINGERS && !currentInputCodeEightDot) ||
          (fingersPattern == FingersPattern.EIGHT_FINGERS && currentInputCodeEightDot)
      }

      private fun mapCalibrationToType(
        calibration: CalibrationTriggeredType,
      ): BrailleImeAnalytics.CalibrationTriggeredType =
        when (calibration) {
          CalibrationTriggeredType.FIVE_FINGERS ->
            BrailleImeAnalytics.CalibrationTriggeredType.FIVE_FINGER
          CalibrationTriggeredType.SIX_FINGERS ->
            BrailleImeAnalytics.CalibrationTriggeredType.SIX_FINGER
          CalibrationTriggeredType.SEVEN_FINGERS ->
            BrailleImeAnalytics.CalibrationTriggeredType.SEVEN_FINGER
          CalibrationTriggeredType.EIGHT_FINGERS ->
            BrailleImeAnalytics.CalibrationTriggeredType.EIGHT_FINGER
          CalibrationTriggeredType.MANUAL -> BrailleImeAnalytics.CalibrationTriggeredType.MANUAL
        }

      private fun playCalibrationDoneSoundAndAnnouncement(announcement: String) {
        for (i in 0 until CALIBRATION_EARCON_REPEAT_COUNT) {
          feedbackManager!!.emitFeedback(
            FeedbackManager.Type.CALIBRATION, CALIBRATION_EARCON_DELAY_MS * i)
        }
        // Wait a second for playing sound and then speak the post-action announcement.
        BrailleCommonTalkBackSpeaker.getInstance()
          .speak(
            announcement,
            ANNOUNCE_CALIBRATION_DELAY_MS,
            TalkBackSpeaker.AnnounceType.INTERRUPT)
      }

      private fun isEightDotCalibration(pointersHeldCount: Int): Boolean {
        // Do 2-step calibration for 5/6/7 dots.
        return (5 <= pointersHeldCount && pointersHeldCount <= 8) && isEightDotsBraille()
      }

      private fun isSixDotCalibration(pointersHeldCount: Int): Boolean {
        return (pointersHeldCount == 5 || pointersHeldCount == 6) && !isEightDotsBraille()
      }

      private fun isConfirmedTwoStepCalibration(pointersHeldCount: Int): Boolean {
        return if (isEightDotsBraille()) pointersHeldCount == 4 else pointersHeldCount == 3
      }
    }

  private val keyboardViewCallback: KeyboardViewCallback =
    object : KeyboardViewCallback {
      override fun onViewReady() {
        BrailleImeLog.d(TAG, "onViewReady")
        activateIfNeeded()
        startAnalyticsPossibly()
        if (!keyboardView!!.isTutorialShown()) {
          showOnBrailleDisplay()
        }
      }

      override fun onViewUpdated() {
        BrailleImeLog.d(TAG, "onViewUpdated")
        if (!contextMenuDialog!!.isShowing() && tutorialState != State.INTRO) {
          activateBrailleIme()
        }
      }

      override fun onViewCleared() {
        BrailleImeLog.d(TAG, "onViewCleared")
        deactivateIfNeeded()
        // Finish session while screen off because no called onFinishInputView() in this case.
        brailleImeAnalytics!!.collectSessionEvents()
      }

      override fun isHideScreenMode(): Boolean {
        return talkBackForBrailleIme!!.isHideScreenMode()
      }
    }

  private val brailleDisplayKeyboardCallback: BrailleDisplayImeStripView.CallBack =
    object : BrailleDisplayImeStripView.CallBack {
      override fun onSwitchToOnscreenKeyboard() {
        BrailleImeLog.d(TAG, "onStripClicked")
        brailleDisplayConnectedAndNotSuspended = false
        updateInputView()
        BrailleCommonTalkBackSpeaker.getInstance()
          .speak(
            getString(R.string.switch_on_screen_keyboard_announcement),
            TalkBackSpeaker.AnnounceType.INTERRUPT)
        keyboardView!!.setTableMode(isCurrentTableTopMode())
        brailleDisplayForBrailleIme!!.suspendInFavorOfBrailleKeyboard()
      }

      override fun onSwitchToNextKeyboard() {
        switchToNextInputMethod()
      }
    }

  private fun showOnBrailleDisplay() {
    showOnBrailleDisplay(/* retranslate= */ false)
  }

  private fun showOnBrailleDisplay(retranslate: Boolean) {
    if (!isInputViewShown()) {
      return
    }
    mainHandler!!.post(
      {
        if (brailleDisplayForBrailleIme == null || editBuffer == null || !isConnectionValid()) {
          return@post
        }
        val result: ResultForDisplay =
          ResultForDisplay.builder()
            .setHoldingsInfo(editBuffer!!.getHoldingsInfo(getImeConnection()))
            .setOnScreenText(EditBufferUtils.getTextFieldText(currentInputConnection!!))
            .setTextSelection(
              BrailleCommonUtils.getTextSelection(currentInputConnection!!))
            .setIsMultiLine(
              EditBufferUtils.isMultiLineField(currentInputEditorInfo!!.inputType))
            .setAction(Utils.getActionLabel(this, currentInputEditorInfo).toString())
            .setHint(Utils.getHint(getImeConnection()).toString())
            .setTextMasked(
              BrailleCommonUtils.isPasswordField(currentInputEditorInfo) &&
                !BrailleCommonUtils.isVisiblePasswordField(currentInputEditorInfo))
            .setRetranslate(retranslate)
            .build()
        brailleDisplayForBrailleIme!!.showOnDisplay(result)
      })
  }

  private fun isConnectionValid(): Boolean {
    if (currentInputConnection == null) {
      BrailleImeLog.e(TAG, "lack of InputConnection")
      return false
    }
    if (currentInputEditorInfo == null) {
      BrailleImeLog.e(TAG, "lack of InputEditorInfo")
      return false
    }
    return true
  }

  @VisibleForTesting
  internal fun isCurrentTableTopMode(): Boolean {
    val layoutOptional: Optional<TouchDots> = layoutOrientator!!.getDetectedLayout()
    val mode = BrailleUserPreferences.readLayoutMode(this)
    return mode == TouchDots.TABLETOP ||
      (mode == TouchDots.AUTO_DETECT && layoutOptional.isPresent &&
        layoutOptional.get() == TouchDots.TABLETOP)
  }

  private fun getImeConnection(): ImeConnection {
    var announceType: AnnounceType = AnnounceType.SILENCE
    // 移植改造：宿主（BrailleBack）的桥接对象可能在服务销毁/重建期间被注销，
    // 此时 brailleDisplayForBrailleIme 与 talkBackForBrailleIme 都可能为 null。
    // 原版 TalkBack 中这两个引用与键盘同生共死，不会出现，故无判空；此处必须补上，
    // 否则隐藏键盘时（onFinishInputView -> tearDown -> onViewCleared -> deactivateIfNeeded）
    // 会抛 NullPointerException 直接闪退。
    val displayConnected =
      brailleDisplayForBrailleIme != null &&
        brailleDisplayForBrailleIme!!.isBrailleDisplayConnectedAndNotSuspended()
    val shouldAnnounceCharacter =
      talkBackForBrailleIme != null &&
        (if (displayConnected)
          talkBackForBrailleIme!!.shouldAnnounceCharacterForPhysicalKeyboard()
        else talkBackForBrailleIme!!.shouldAnnounceCharacterForOnScreenKeyboard())
    if (shouldAnnounceCharacter) {
      announceType = AnnounceType.NORMAL
    }
    return ImeConnection(
      currentInputConnection!!, currentInputEditorInfo!!, announceType)
  }

  private val onSharedPreferenceChangeListener: OnSharedPreferenceChangeListener =
    OnSharedPreferenceChangeListener { _, key ->
      if (key == getString(R.string.pref_brailleime_translator_code)) {
        val newCode =
          BrailleUserPreferences.readCurrentActiveInputCodeAndCorrect(this@BrailleIme)
        if (!brailleDisplayConnectedAndNotSuspended) {
          BrailleCommonTalkBackSpeaker.getInstance()
            .speak(
              getString(
                R.string.switch_to_language_announcement,
                newCode.getUserFacingName(this@BrailleIme)),
              TalkBackSpeaker.AnnounceType.INTERRUPT)
        }
        if (keyboardView!!.getBrailleInputViewDotCount() !=
          BrailleUserPreferences.getCurrentTypingLanguageType(this@BrailleIme).getDotCount()
        ) {
          keyboardView!!.refreshInputView()
        }
        refreshEditBufferAndBrailleDisplay()
      } else if (key == getString(R.string.pref_braille_contracted_mode)) {
        val contractedMode = BrailleUserPreferences.readContractedMode(this@BrailleIme)
        if (BrailleUserPreferences.readCurrentActiveInputCodeAndCorrect(this@BrailleIme)
          .isSupportsContracted(this@BrailleIme)
        ) {
          BrailleImeAnalytics.getInstance(this@BrailleIme).logContractedToggle(contractedMode)
        }
        if (!brailleDisplayConnectedAndNotSuspended) {
          BrailleCommonTalkBackSpeaker.getInstance()
            .speak(
              getString(
                if (contractedMode) R.string.switched_to_contracted_announcement
                else R.string.switched_to_uncontracted_announcement),
              TalkBackSpeaker.AnnounceType.INTERRUPT)
        }
        refreshEditBufferAndBrailleDisplay()
      }
    }

  private fun refreshEditBufferAndBrailleDisplay() {
    if (editBuffer != null) {
      editBuffer!!.commit(getImeConnection())
    }
    createEditBuffer()
    window!!.setTitle(Utils.getBrailleKeyboardDisplayName(this@BrailleIme))
    showOnBrailleDisplay(/* retranslate= */ true)
  }

  private val contextMenuDialogCallback: ContextMenuDialog.Callback =
    object : ContextMenuDialog.Callback {
      override fun onDialogHidden() {
        activateBrailleIme()
        startAnalyticsPossibly()
        layoutOrientator!!.startIfNeeded()
        escapeReminder!!.startTimer()
        showOnBrailleDisplay()
      }

      override fun onDialogShown() {
        deactivateBrailleIme()
        brailleImeAnalytics!!.collectSessionEvents()
        escapeReminder!!.cancelTimer()
        layoutOrientator!!.stop()
      }

      override fun onLaunchSettings() {
        val intent = Intent()
        val name =
          ComponentName(packageName, BrailleImePreferencesActivity::class.java.name)
        intent.component = name
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
      }

      override fun onTutorialOpen() {
        reactivate(State.INTRO)
      }

      override fun onTutorialClosed() {}

      override fun onCalibration() {
        activateBrailleIme()
        layoutOrientator!!.startIfNeeded()
        keyboardView!!.calibrateBrailleInputView()
      }
    }

  private val tutorialCallback: TutorialCallback =
    object : TutorialCallback {
      override fun onBrailleImeActivated() {
        activateBrailleIme()
      }

      override fun onBrailleImeInactivated() {
        deactivateBrailleIme()
      }

      override fun onAudialAnnounce(
        announcement: String,
        delayMs: Int,
        announceType: TalkBackSpeaker.AnnounceType,
        utteranceCompleteRunnable: UtteranceCompleteRunnable?,
      ) {
        BrailleCommonTalkBackSpeaker.getInstance()
          .speak(announcement, delayMs, announceType, utteranceCompleteRunnable)
      }

      override fun onPlaySound(type: FeedbackManager.Type) {
        feedbackManager!!.emitFeedback(type)
      }

      override fun onSwitchToNextInputMethod() {
        switchToNextInputMethod()
        brailleImeAnalytics!!.logTutorialFinishedBySwitchToNextInputMethod()
      }

      override fun onLaunchSettings() {
        contextMenuDialogCallback.onLaunchSettings()
        brailleImeAnalytics!!.logTutorialFinishedByLaunchSettings()
      }

      override fun onTutorialFinished() {
        BrailleUserPreferences.setTutorialFinished(applicationContext)
        talkBackForBrailleIme!!.restoreSilenceOnProximity()
        brailleImeAnalytics!!.logTutorialFinishedByTutorialCompleted()
        reactivate(State.NONE)
      }

      override fun onRestartTutorial() {
        reactivate(keyboardView!!.getTutorialStatus())
      }

      override fun registerOrientationChange(callBack: OrientationMonitor.Callback) {
        orientationCallbackDelegate = callBack
      }

      override fun unregisterOrientationChange() {
        orientationCallbackDelegate = null
      }
    }

  private val escapeReminderCallback: EscapeReminder.Callback =
    object : EscapeReminder.Callback {
      override fun onRemind(utteranceCompleteRunnable: UtteranceCompleteRunnable) {
        BrailleCommonTalkBackSpeaker.getInstance()
          .speak(
            getString(R.string.reminder_announcement),
            ANNOUNCE_DELAY_MS,
            utteranceCompleteRunnable)
      }

      override fun shouldAnnounce(): Boolean {
        return keyboardView!!.isViewContainerCreated() && tutorialState == State.NONE
      }
    }

  private val talkBackOffDialogCallback: TalkBackOffDialog.Callback =
    object : TalkBackOffDialog.Callback {
      override fun onSwitchToNextIme() {
        switchToNextInputMethod()
      }

      override fun onLaunchSettings() {
        // Highlight TalkBack item in Accessibility Settings upon arriving there (Pixel only).
        Utils.highlightTalkBackSettings(this@BrailleIme)
        // Collapse notification panel (quick settings).
        Utils.collapseNotificationPanel(this@BrailleIme)
      }
    }

  private val talkBackSuspendDialogCallback: TalkBackSuspendDialog.Callback =
    object : TalkBackSuspendDialog.Callback {
      override fun onSwitchToNextIme() {
        switchToNextInputMethod()
      }
    }

  private val tooFewTouchPointsDialogCallback: TooFewTouchPointsDialog.Callback =
    object : TooFewTouchPointsDialog.Callback {
      override fun onSwitchToNextIme() {
        switchToNextInputMethod()
      }
    }

  private val phoneStateListener: PhoneStateListener =
    object : PhoneStateListener() {
      override fun onCallStateChanged(state: Int, incomingNumber: String?) {
        if (state == TelephonyManager.CALL_STATE_RINGING) {
          // Close keyboard when phone call coming.
          if (keyboardView!!.isViewContainerCreated()) {
            hideSelf()
          }
        }
      }
    }

  private val brailleImeForBrailleDisplay: BrailleImeForBrailleDisplay =
    object : BrailleImeForBrailleDisplay {
      override fun onBrailleDisplayConnected() {
        BrailleImeLog.d(TAG, "onBrailleDisplayConnected")
        brailleDisplayConnectedAndNotSuspended = true
        updateInputView()
      }

      override fun onBrailleDisplayDisconnected() {
        BrailleImeLog.d(TAG, "onBrailleDisplayDisconnected")
        brailleDisplayConnectedAndNotSuspended = false
        if (FeatureFlagReader.enableBrailleImeOnDeviceWithoutFivePointers(applicationContext) &&
          !Utils.isMultiTouchSupported(applicationContext)
        ) {
          switchToNextInputMethod()
          return
        }
        if (showKeyboardView()) {
          updateInputView()
        }
      }

      override fun performImeAction(action: ImeAction): Boolean {
        val result = brailleImeActor!!.performAction(action as BrailleImeAction)
        if (result) {
          showOnBrailleDisplay()
        }
        return result
      }

      override fun sendBrailleDots(brailleCharacter: BrailleCharacter): Boolean {
        // 移植接线：宿主 BrailleBackService 目前不提供 BrailleDisplayForBrailleIme
        // (BrailleBackService.java:560 传 null)，所以 stripView 可能根本没创建
        // (KeyboardView.stripView 初值 null，tearDown 也会置空)。引擎合并后硬件
        // 按键走 BrailleIME 的统一入口，这里只保证该接口一旦被接线也不会闪退。
        if (brailleImeActor == null || editBuffer == null || !isConnectionValid()) {
          BrailleImeLog.d(TAG, "sendBrailleDots ignored: keyboard not activated")
          return false
        }
        if (keyboardView != null && keyboardView!!.getStripView() != null) {
          keyboardView!!.getStripView()!!.animateInput(brailleCharacter.toDotNumbers())
        }
        val result: Boolean
        if (brailleCharacter.isEmpty()) {
          result = brailleImeActor!!.performAction(BrailleImeAction.ADD_SPACE_OR_NEXT_ITEM)
        } else if (brailleCharacter == BrailleCharacter.DOT7) {
          result =
            brailleImeActor!!.performAction(BrailleImeAction.DELETE_CHARACTER_OR_PREVIOUS_ITEM)
        } else if (brailleCharacter == BrailleCharacter.DOT8) {
          result = commitHoldingsAndPerformEnterKeyAction()
        } else {
          result = !TextUtils.isEmpty(brailleImeActor!!.performTyping(brailleCharacter))
        }
        if (result) {
          showOnBrailleDisplay()
        }
        return result
      }

      override fun moveTextFieldCursor(toIndex: Int): Boolean {
        val result = editBuffer!!.moveTextFieldCursor(getImeConnection(), toIndex)
        if (result) {
          showOnBrailleDisplay()
        }
        return result
      }

      override fun moveHoldingsCursor(toIndex: Int): Boolean {
        val result = editBuffer!!.moveHoldingsCursor(getImeConnection(), toIndex)
        if (result) {
          showOnBrailleDisplay()
        }
        return result
      }

      override fun commitHoldings() {
        if (editBuffer != null) {
          editBuffer!!.commit(getImeConnection())
        }
      }

      override fun commitHoldingsAndPerformEnterKeyAction(): Boolean {
        commitHoldings()
        if (currentInputConnection!!.sendKeyEvent(
            KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        ) {
          return currentInputConnection!!.sendKeyEvent(
            KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
        return false
      }

      override fun updateResultForDisplay() {
        showOnBrailleDisplay()
      }

      override fun isBrailleKeyboardActivated(): Boolean {
        return isInputViewShown() && isTalkBackEnabled()
      }

      override fun handleBrailleKeyForBARDMobile(keyCode: Int): Boolean {
        // Only handle Bard application.
        if (currentInputEditorInfo == null ||
          !Objects.equals(
            currentInputEditorInfo!!.packageName, BARD_PACKAGE_NAME)
        ) {
          return false
        }
        // To allow BARD Mobile to receive keyboard shortcuts, must use English computer braille.
        // See BARD Mobile keyboard shortcuts:
        // https://nlsbard.loc.gov/apidocs/BARDMobile.userguide.iOS.1.0.html#BrailleShortcutKeys7.3
        val translator: BrailleTranslator =
          BrailleUserPreferences.readTranslatorFactory(this@BrailleIme)
            .create(this@BrailleIme, Code.EN_NABCC.name, /* contractedMode= */ false)
        val key = translator.translateToPrint(BrailleWord(byteArrayOf(keyCode.toByte())))
        return currentInputConnection!!.commitText(key, /* newCursorPosition= */ 1)
      }
    }

  @VisibleForTesting
  fun testing_getContextMenuDialogCallback(): ContextMenuDialog.Callback {
    return contextMenuDialogCallback
  }

  @VisibleForTesting
  fun testing_getBrailleImeForTalkBack(): BrailleImeForTalkBack {
    return brailleImeForTalkBack
  }

  @VisibleForTesting
  fun testing_getInputPlaneCallback(): BrailleInputView.Callback {
    return inputPlaneCallback
  }

  @VisibleForTesting
  fun testing_getActorCallback(): BrailleImeActor.Callback {
    return brailleImeActorCallback
  }

  @VisibleForTesting
  fun testing_setGestureController(gestureController: BrailleImeGestureController) {
    this.brailleImeGestureController = gestureController
  }

  @VisibleForTesting
  fun testing_getGestureController(): BrailleImeGestureController? {
    return brailleImeGestureController
  }

  @VisibleForTesting
  fun testing_setEditBuffer(editBuffer: EditBuffer) {
    this.editBuffer = editBuffer
  }

  @VisibleForTesting
  fun testing_getKeyboardView(): KeyboardView? {
    return keyboardView
  }

  @VisibleForTesting
  fun testing_setBrailleImeAnalytics(brailleImeAnalytics: BrailleImeAnalytics) {
    this.brailleImeAnalytics = brailleImeAnalytics
  }

  @VisibleForTesting
  fun testing_setTalkBackOffDialog(dialog: TalkBackOffDialog) {
    talkbackOffDialog = dialog
  }

  @VisibleForTesting
  fun testing_getStripViewCallback(): BrailleDisplayImeStripView.CallBack {
    return brailleDisplayKeyboardCallback
  }

  @VisibleForTesting
  fun testing_getBrailleDisplayForBrailleIme(): BrailleDisplayForBrailleIme? {
    return brailleDisplayForBrailleIme
  }

  @VisibleForTesting
  fun testing_setTutorialState(state: State) {
    tutorialState = state
  }
}
