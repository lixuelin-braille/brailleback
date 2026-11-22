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
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.PowerManager
import android.provider.Settings
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Size
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.Toast
import androidx.annotation.Nullable
import androidx.core.content.ContextCompat
import com.google.android.accessibility.braille.common.BrailleCommonTalkBackSpeaker
import com.google.android.accessibility.braille.common.BrailleUserPreferences
import com.google.android.accessibility.braille.common.BrailleUtils
import com.google.android.accessibility.braille.common.FeedbackManager
import com.google.android.accessibility.braille.common.ImeConnection
import com.google.android.accessibility.braille.common.translate.EditBuffer
import com.google.android.accessibility.braille.interfaces.BrailleDisplayForBrailleIme
import com.google.android.accessibility.braille.interfaces.BrailleImeForBrailleDisplay
import com.google.android.accessibility.braille.interfaces.BrailleImeForTalkBack
import com.google.android.accessibility.braille.interfaces.TalkBackForBrailleCommon
import com.google.android.accessibility.braille.interfaces.TalkBackForBrailleIme
import com.google.android.accessibility.braille.interfaces.TalkBackForBrailleIme.ServiceStatus
import com.google.android.accessibility.brailleime.analytics.BrailleImeAnalytics
import com.google.android.accessibility.brailleime.dialog.ContextMenuDialog
import com.google.android.accessibility.brailleime.dialog.TalkBackOffDialog
import com.google.android.accessibility.brailleime.dialog.TalkBackSuspendDialog
import com.google.android.accessibility.brailleime.dialog.TooFewTouchPointsDialog
import com.google.android.accessibility.brailleime.dialog.ViewAttachedDialog
import com.google.android.accessibility.brailleime.input.BrailleDisplayImeStripView
import com.google.android.accessibility.brailleime.input.BrailleInputView
import com.google.android.accessibility.brailleime.keyboardview.AccessibilityOverlayKeyboardView
import com.google.android.accessibility.brailleime.keyboardview.KeyboardView
import com.google.android.accessibility.brailleime.keyboardview.StandardKeyboardView
import com.google.android.accessibility.brailleime.settings.BrailleImeGestureCommandActivity
import com.google.android.accessibility.brailleime.settings.BrailleImePreferencesActivity
import com.google.android.accessibility.brailleime.tutorial.TutorialView.TutorialState.State
import com.google.android.accessibility.utils.AccessibilityServiceCompatUtils
import com.google.android.accessibility.utils.AccessibilityServiceCompatUtils.Constants
import com.google.common.annotations.VisibleForTesting
import com.google.errorprone.annotations.CanIgnoreReturnValue

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

  internal var tutorialState: State? = null
  internal var editBuffer: EditBuffer? = null
  internal var originalDefaultUncaughtExceptionHandler: Thread.UncaughtExceptionHandler? = null
  internal var orientationCallbackDelegate: OrientationMonitor.Callback? = null
  internal var talkbackOffDialog: ViewAttachedDialog? = null
  internal var contextMenuDialog: ViewAttachedDialog? = null
  internal var tooFewTouchPointsDialog: ViewAttachedDialog? = null
  internal var talkBackSuspendDialog: ViewAttachedDialog? = null
  internal var layoutOrientator: LayoutOrientator? = null
  internal var escapeReminder: EscapeReminder? = null
  internal var brailleImeAnalytics: BrailleImeAnalytics? = null
  internal var keyboardView: KeyboardView? = null
  internal var brailleImeGestureController: BrailleImeGestureController? = null
  internal var brailleImeActor: BrailleImeActor? = null
  internal var mainHandler: Handler? = null
  internal var brailleDisplayConnectedAndNotSuspended = false
  internal var orientation = 0
  internal var isVisible = false
  internal var feedbackManager: FeedbackManager? = null

  // ── 拆分出去的协作对象：回调实现、校准播报、对话框协调等，见同目录各 *Bridge/*Callback 文件 ──

  internal val calibrationAnnouncer = BrailleImeCalibrationAnnouncer(this)
  internal val dialogCoordinator = BrailleImeDialogCoordinator(this)
  internal val tutorialCallbackOwner = BrailleImeTutorialCallback(this, dialogCoordinator)
  internal val viewCallbacks = BrailleImeViewCallbacks(this)
  internal val layoutCallbacks = BrailleImeLayoutCallbacks(this, calibrationAnnouncer)
  internal val settingsObserver = BrailleImeSettingsObserver(this)
  internal val systemListeners = BrailleImeSystemListeners(this)
  internal val inputPlaneCallbackOwner = BrailleImeInputPlaneCallback(this, calibrationAnnouncer)
  internal val inputConnection = BrailleImeInputConnection(this)

  internal val brailleImeForTalkBack: BrailleImeForTalkBack = BrailleImeTalkBackBridge(this)
  internal val brailleImeForBrailleDisplay: BrailleImeForBrailleDisplay =
    BrailleImeDisplayBridge(this)

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
    // onEvaluateFullscreenMode() to always return false (which is counterintuitive since this IME
    // is to be fullscreen), or expand the bounds of the inputArea by overriding setInputView(View)
    // and making an ill-advised modification to the LayoutParams of the parent of the
    // BrailleInputView. This code uses the first of these two options; this allows our inputArea,
    // which we furnish in the override of onCreateInputView, to take up the entire view region.
    @Nullable
    internal var talkBackForBrailleIme: TalkBackForBrailleIme? = null

    @Nullable
    internal var talkBackForBrailleCommon: TalkBackForBrailleCommon? = null

    @Nullable
    internal var brailleDisplayForBrailleIme: BrailleDisplayForBrailleIme? = null

    internal const val ANNOUNCE_DELAY_MS =
      800 // Delay, so that it follows previous-IME-is-hidden announcement.

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
    if (brailleDisplayForBrailleIme != null) {
      brailleDisplayConnectedAndNotSuspended =
        brailleDisplayForBrailleIme!!.isBrailleDisplayConnectedAndNotSuspended()
    }
    keyboardView = createKeyboardView()
    escapeReminder = EscapeReminder(this, tutorialCallbackOwner.escapeReminderCallback)
    talkbackOffDialog = TalkBackOffDialog(this, dialogCoordinator.talkBackOffDialogCallback)
    contextMenuDialog = ContextMenuDialog(this, dialogCoordinator.contextMenuDialogCallback)
    tooFewTouchPointsDialog =
      TooFewTouchPointsDialog(this, dialogCoordinator.tooFewTouchPointsDialogCallback)
    talkBackSuspendDialog = TalkBackSuspendDialog(this, dialogCoordinator.talkBackSuspendDialogCallback)
    tutorialState = State.NONE
    originalDefaultUncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler(systemListeners.localUncaughtExceptionHandler)

    BrailleUserPreferences.getSharedPreferences(this, BrailleUserPreferences.BRAILLE_SHARED_PREFS_FILENAME)
      .registerOnSharedPreferenceChangeListener(settingsObserver.onSharedPreferenceChangeListener)

    ContextCompat.registerReceiver(
      this,
      dialogCoordinator.imeChangeListener,
      IntentFilter(Intent.ACTION_INPUT_METHOD_CHANGED),
      ContextCompat.RECEIVER_NOT_EXPORTED)
    val uri = Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
    contentResolver
      .registerContentObserver(uri, false, systemListeners.accessibilityServiceStatusChangeObserver)

    brailleImeAnalytics = BrailleImeAnalytics.getInstance(this)
    OrientationMonitor.init(this)
    layoutOrientator = LayoutOrientator(this, layoutCallbacks.layoutOrientatorCallback)

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
        this, viewCallbacks.keyboardViewCallback, /* fullScreen= */ !brailleDisplayConnectedAndNotSuspended)
    else AccessibilityOverlayKeyboardView(this, viewCallbacks.keyboardViewCallback)
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
    if (Thread.getDefaultUncaughtExceptionHandler() == systemListeners.localUncaughtExceptionHandler) {
      Thread.setDefaultUncaughtExceptionHandler(originalDefaultUncaughtExceptionHandler)
    }
    instance = null
    if (talkBackForBrailleIme != null) {
      talkBackForBrailleIme!!.setBrailleImeForTalkBack(null)
    }
    BrailleUserPreferences.getSharedPreferences(this, BrailleUserPreferences.BRAILLE_SHARED_PREFS_FILENAME)
      .unregisterOnSharedPreferenceChangeListener(settingsObserver.onSharedPreferenceChangeListener)
    unregisterReceiver(dialogCoordinator.imeChangeListener)
    contentResolver.unregisterContentObserver(systemListeners.accessibilityServiceStatusChangeObserver)
    keyboardView!!.tearDown()
    keyboardView = null
    brailleImeAnalytics!!.sendAllLogs()
  }

  @CanIgnoreReturnValue
  internal fun activateIfNeeded(): Boolean {
    BrailleImeLog.d(TAG, "activateIfNeeded")
    if (Utils.isPhonePermissionGranted(this)) {
      val telephonyManager =
        getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
      telephonyManager.listen(systemListeners.phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
    }
    if (talkBackForBrailleIme!!.isVibrationFeedbackEnabled()) {
      BrailleImeVibrator.getInstance(this).enable()
    }
    talkBackForBrailleIme!!.resetGranularity()
    if (tutorialState == State.NONE) {
      escapeReminder!!.startTimer()
    }
    activateBrailleIme()
    settingsObserver.createEditBuffer()
    OrientationMonitor.getInstance().enable()
    OrientationMonitor.getInstance().registerCallback(layoutCallbacks.orientationMonitorCallback)
    layoutOrientator!!.startIfNeeded()
    updateNavigationBarColor()
    brailleImeActor =
      BrailleImeActor(
        this,
        viewCallbacks.brailleImeActorCallback,
        talkBackForBrailleIme,
        feedbackManager)
    brailleImeGestureController = BrailleImeGestureController(this, brailleImeActor)
    return true
  }

  internal fun isBrailleDisplayStateChanged(): Boolean {
    val brailleDisplayConnectedAndNotIgnored =
      brailleDisplayForBrailleIme != null &&
        brailleDisplayForBrailleIme!!.isBrailleDisplayConnectedAndNotSuspended()
    if (this.brailleDisplayConnectedAndNotSuspended != brailleDisplayConnectedAndNotIgnored) {
      this.brailleDisplayConnectedAndNotSuspended = brailleDisplayConnectedAndNotIgnored
      return true
    }
    return false
  }

  internal fun showKeyboardView(): Boolean {
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

  internal fun createViewContainerAndAddView() {
    BrailleImeLog.d(TAG, "createViewContainerAndAddView")
    keyboardView!!.setWindowManager(talkBackForBrailleIme!!.getWindowManager())
    keyboardView!!.updateImeView()
    keyboardView!!.createViewContainer()
    if (brailleDisplayConnectedAndNotSuspended) {
      keyboardView!!.createAndAddStripView(viewCallbacks.brailleDisplayKeyboardCallback)
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
      keyboardView!!.createAndAddInputView(inputPlaneCallbackOwner, isCurrentTableTopMode())
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
    keyboardView!!.createAndAddTutorialView(tutorialState!!, tutorialCallbackOwner)
    talkBackForBrailleIme!!.disableSilenceOnProximity()
  }

  internal fun activateBrailleIme() {
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

  internal fun deactivateBrailleIme() {
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

  internal fun showTalkBackOffDialog() = dialogCoordinator.showTalkBackOffDialog()

  internal fun showTalkBackSuspendDialog() = dialogCoordinator.showTalkBackSuspendDialog()

  internal fun showTooFewTouchPointsDialog() = dialogCoordinator.showTooFewTouchPointsDialog()

  internal fun dismissDialogs() = dialogCoordinator.dismissDialogs()

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
  internal fun deactivateIfNeeded(): Boolean {
    BrailleImeLog.d(TAG, "deactivateIfNeeded")
    if (Utils.isPhonePermissionGranted(this)) {
      val telephonyManager =
        getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
      telephonyManager.listen(systemListeners.phoneStateListener, PhoneStateListener.LISTEN_NONE)
    }
    escapeReminder!!.cancelTimer()
    layoutOrientator!!.stop()
    BrailleImeVibrator.getInstance(this).disable()
    if (editBuffer != null && isConnectionValid()) {
      editBuffer!!.commit(getImeConnection())
    }
    deactivateBrailleIme()
    calibrationAnnouncer.clearCallbacks()
    OrientationMonitor.getInstance().unregisterCallback()
    OrientationMonitor.getInstance().disable()
    return true
  }

  internal fun reactivate(state: State) {
    tutorialState = state
    keyboardView!!.tearDown()
    createViewContainerAndAddView()
  }

  internal fun hideSelf() {
    requestHideSelf(0)
  }

  internal fun performEditorAction(inputConnection: InputConnection?) {
    this.inputConnection.performEditorAction(inputConnection)
  }

  internal fun updateInputView() {
    if (keyboardView != null) {
      keyboardView!!.tearDown()
    }
    keyboardView = createKeyboardView()
    setInputView(keyboardView!!.createImeInputView())
    createViewContainerAndAddView()
  }

  @VisibleForTesting
  internal fun switchToNextInputMethod(): Boolean {
    return inputConnection.switchToNextInputMethod()
  }

  // Starts to log keyboard session only in non tutorial mode.
  internal fun startAnalyticsPossibly() {
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

  internal fun showOnBrailleDisplay() {
    inputConnection.showOnBrailleDisplay()
  }

  internal fun showOnBrailleDisplay(retranslate: Boolean) {
    inputConnection.showOnBrailleDisplay(retranslate)
  }

  internal fun isConnectionValid(): Boolean {
    return inputConnection.isConnectionValid()
  }

  @VisibleForTesting
  internal fun isCurrentTableTopMode(): Boolean {
    return inputConnection.isCurrentTableTopMode()
  }

  internal fun getImeConnection(): ImeConnection {
    return inputConnection.getImeConnection()
  }

  internal fun isTalkBackEnabled(): Boolean {
    return AccessibilityServiceCompatUtils.isAccessibilityServiceEnabled(
      this, Constants.TALKBACK_SERVICE.flattenToShortString())
  }

  // ── 测试钩子：保持原有签名与语义，转发到对应协作对象 ──

  @VisibleForTesting
  fun testing_getContextMenuDialogCallback(): ContextMenuDialog.Callback {
    return dialogCoordinator.contextMenuDialogCallback
  }

  @VisibleForTesting
  fun testing_getBrailleImeForTalkBack(): BrailleImeForTalkBack {
    return brailleImeForTalkBack
  }

  @VisibleForTesting
  fun testing_getInputPlaneCallback(): BrailleInputView.Callback {
    return inputPlaneCallbackOwner
  }

  @VisibleForTesting
  fun testing_getActorCallback(): BrailleImeActor.Callback {
    return viewCallbacks.brailleImeActorCallback
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
    return viewCallbacks.brailleDisplayKeyboardCallback
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
