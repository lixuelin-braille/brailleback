/*
 * Copyright (C) 2012 Google Inc.
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

package com.googlecode.eyesfree.brailleback

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.preference.PreferenceManager
import androidx.core.content.ContextCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import android.text.TextUtils
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.googlecode.eyesfree.braille.display.BrailleInputEvent
import com.googlecode.eyesfree.braille.display.Display
import com.googlecode.eyesfree.braille.service.display.HidBridge
import com.googlecode.eyesfree.brailleback.rule.BrailleRuleRepository
import com.googlecode.eyesfree.brailleback.utils.PreferenceUtils
import com.googlecode.eyesfree.brailleback.utils.LogFileWriter
import com.googlecode.eyesfree.labeling.CustomLabelManager
import com.googlecode.eyesfree.labeling.PackageRemovalReceiver
import com.googlecode.eyesfree.utils.LogUtils
import java.lang.ref.WeakReference

/**
 * An accessibility service that provides feedback through a braille
 * display.
 */
class BrailleBackService : AccessibilityService(),
        Display.OnConnectionStateChangeListener,
        DisplayManager.OnMappedInputEventListener,
        DisplayManager.OnPanOverflowListener,
        SearchNavigationMode.SearchStateListener,
        BrailleMenuNavigationMode.BrailleMenuListener {

    private var feedbackManager: FeedbackManager? = null

    private val mPreferenceListener =
        object : SharedPreferences.OnSharedPreferenceChangeListener {
            override fun onSharedPreferenceChanged(
                    prefs: SharedPreferences?, key: String?) {
                if (displayManager == null) {
                    return
                }
                if (getString(R.string.pref_motion_mode_key).equals(key)) {
                    displayManager!!.setMotionMode(prefs!!.getBoolean(key, false))
                } else if (getString(R.string.pref_pause_behavior_key).equals(key)) {
                    displayManager!!.setPauseClear(
                        getString(R.string.pref_pause_behavior_clear_value)
                            .equals(prefs!!.getString(key, "")))
                } else if (getString(R.string.pref_idle_clear_key).equals(key)) {
                    displayManager!!.setIdleClearTimeoutMs(
                        parseIdleClearMinutes(prefs!!.getString(key, "3") ?: "3"))
                } else if (getString(R.string.pref_cursor_dots_key).equals(key)) {
                    displayManager!!.setCursorDotsEnabled(prefs!!.getBoolean(key, false))
                }
            }
        }
    private var translatorManager: TranslatorManager? = null
    private var displayManager: DisplayManager? = null
    private var selfBrailleManager: SelfBrailleManager? = null
    private var nodeBrailler: NodeBrailler? = null
    private var ruleRepository: BrailleRuleRepository? = null
    private var focusTracker: FocusTracker? = null
    private var imeHelper: IMEHelper? = null
    private var modeSwitcher: ModeSwitcher? = null
    private var searchNavigationMode: SearchNavigationMode? = null
    private var brailleMenuNavigationMode: BrailleMenuNavigationMode? = null
    // Package-private so that Kotlin code in the same package (DisplayManager.kt)
    // can read it; Kotlin cannot access Java protected members from a non-subclass.
    var imeNavigationMode: IMENavigationMode? = null
    private var labelManager: CustomLabelManager? = null
    private var packageReceiver: PackageRemovalReceiver? = null

    /** Set if the infrastructure is initialized. */
    private var isInfrastructureInitialized = false
    // 屏幕盲文键盘（移植自 TalkBack brailleime）的宿主桥接。
    // 仅在首次初始化时创建，onDestroy 时释放其 TTS / SoundPool。
    private var brailleImeHostBridge: com.googlecode.eyesfree.brailleback.ime.BrailleImeHostBridge? = null

    /** {@link Handler} for executing messages on the service main thread. */
    private val handler = StartHandler(this)

    /**
     * Static to avoid retaining the accessibility service through the main
     * Looper while a start message is queued.
     */
    private class StartHandler(service: BrailleBackService) : Handler(Looper.getMainLooper()) {
        private val serviceRef: WeakReference<BrailleBackService> = WeakReference(service)

        override fun handleMessage(message: Message) {
            val service = serviceRef.get()
            if (service == null) {
                return
            }
            when (message.what) {
                BrailleBackService.WHAT_START -> {
                    service.updateServiceInfo()
                    service.initializeDependencies()
                    return
                }
                else -> { // fall out
                }
            }
        }
    }

    override fun onConnectionStateChanged(state: Int) {
        if (!displayManager!!.isSimulatedDisplay()) {
            if (state == Display.STATE_NOT_CONNECTED) {
                feedbackManager!!.emitFeedback(FeedbackManager.TYPE_DISPLAY_DISCONNECTED)
            } else if (state == Display.STATE_CONNECTED) {
                feedbackManager!!.emitFeedback(FeedbackManager.TYPE_DISPLAY_CONNECTED)
            }
        }
        if (focusTracker != null) {
            focusTracker!!.onConnectionStateChanged(state)
        }
    }

    override fun onMappedInputEvent(event: BrailleInputEvent,
            content: DisplayManager.Content) {
        if (modeSwitcher == null) {
            return
        }
        // Global commands can't be overriden.
        if (handleGlobalCommands(event)) {
            return
        }
        if (BuildConfig.DEBUG
                && event.command == BrailleInputEvent.CMD_BRAILLE_KEY
                && event.argument == SWITCH_NAVIGATION_MODE_DOTS) {
            modeSwitcher!!.switchMode()
            return
        }
        if (event.command == BrailleInputEvent.CMD_TOGGLE_BRAILLE_MENU) {
            if (!LABELING_ENABLED) {
                return
            }
            if (brailleMenuNavigationMode!!.isActive()) {
                modeSwitcher!!.overrideMode(null)
            } else {
                modeSwitcher!!.overrideMode(brailleMenuNavigationMode)
            }
            return
        }
        if (modeSwitcher!!.onMappedInputEvent(event, content)) {
            return
        }
        // Check native case after navigation mode handler to allow navigation
        // mode chance to deal with webview incremental search.
        if (event.command ==
                BrailleInputEvent.CMD_TOGGLE_INCREMENTAL_SEARCH) {
            // Search Navigation mode will handle disabling the mode.
            startSearchWithTutorial()
            return
        }
        if (imeHelper!!.onInputEvent(event)) {
            return
        }
        if (event.command == BrailleInputEvent.CMD_TOGGLE_BRAILLE_GRADE) {
            if (translatorManager == null) {
                return
            }
            // 统一盲文表后，级切换直接在所选语言的六点与八点表之间切换。
            val eightDot = translatorManager!!.toggleBrailleDots()
            feedbackManager!!.emitFeedback(
                if (eightDot) FeedbackManager.TYPE_GRADE_8_DOT
                else FeedbackManager.TYPE_GRADE_6_DOT)

            // If showing preference screen... update displayed preference.
            val refreshPrefIntent =
                Intent(SettingsActivity.INTENT_REFRESH_DISPLAY)
            refreshPrefIntent.setPackage(packageName)
            sendBroadcast(refreshPrefIntent)

            return
        }
        feedbackManager!!.emitFeedback(FeedbackManager.TYPE_UNKNOWN_COMMAND)
    }

    private fun handleGlobalCommands(event: BrailleInputEvent): Boolean {
        val success: Boolean
        when (event.command) {
            BrailleInputEvent.CMD_GLOBAL_HOME -> {
                success = performGlobalAction(GLOBAL_ACTION_HOME)
            }
            BrailleInputEvent.CMD_GLOBAL_BACK -> {
                success = performGlobalAction(GLOBAL_ACTION_BACK)
            }
            BrailleInputEvent.CMD_GLOBAL_RECENTS -> {
                success = performGlobalAction(GLOBAL_ACTION_RECENTS)
            }
            BrailleInputEvent.CMD_GLOBAL_NOTIFICATIONS -> {
                success = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            }
            BrailleInputEvent.CMD_HELP -> {
                success = runHelp()
            }
            else -> {
                return false
            }
        }
        if (!success) {
            feedbackManager!!.emitFeedback(FeedbackManager.TYPE_COMMAND_FAILED)
        }
        // Don't fall through even if the command failed, we own these
        // commands.
        return true
    }

    override fun onPanLeftOverflow(content: DisplayManager.Content) {
        if (modeSwitcher != null && !modeSwitcher!!.onPanLeftOverflow(content)) {
            feedbackManager!!.emitFeedback(FeedbackManager.TYPE_NAVIGATE_OUT_OF_BOUNDS)
        }
    }

    override fun onPanRightOverflow(content: DisplayManager.Content) {
        if (modeSwitcher != null && !modeSwitcher!!.onPanRightOverflow(content)) {
            feedbackManager!!.emitFeedback(FeedbackManager.TYPE_NAVIGATE_OUT_OF_BOUNDS)
        }
    }

    // Volume key gesture detection (long press up/down, double press).
    // While gestures are enabled the service CONSUMES every volume key and
    // re-applies the single volume step for an ordinary ("slow") press itself.
    // That is the only way to keep a long press or a fast double press from
    // also changing the volume: onKeyEvent() must decide synchronously and
    // cannot observe a gesture first, then consume it afterwards.
    // When gestures are disabled the keys go to the system untouched.

    private val mVolumeKeyHandler = Handler()
    private var mVolumeLastTapTime = 0L
    private var mVolumeLastTapKeyCode = 0
    private var mVolumeLongPressKeyCode = 0
    private var mVolumeLongPressFired = false
    private var mPendingSlowPressKeyCode = 0
    private var mAudioManager: AudioManager? = null

    private val mVolumeLongPressRunnable = object : Runnable {
        override fun run() {
            val up = (mVolumeLongPressKeyCode == KeyEvent.KEYCODE_VOLUME_UP)
            val action = readVolumeAction(
                    if (up) BrailleBackService.GESTURE_LONG_UP
                    else BrailleBackService.GESTURE_LONG_DOWN)
            if (isNoneVolumeAction(action)) {
                // Nothing configured for this slot: let the release fall back
                // to the slow-press branch so the key is not swallowed.
                return
            }
            cancelPendingSlowPress()
            clearVolumeTapHistory()
            mVolumeLongPressFired = true
            performVolumeAction(action)
        }
    }

    /** Re-applies the single volume step for a confirmed slow press. */
    private val mSlowPressRunnable = object : Runnable {
        override fun run() {
            adjustSystemVolume(mPendingSlowPressKeyCode)
            mPendingSlowPressKeyCode = 0
            clearVolumeTapHistory()
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP
                && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false
        }
        // Never take ownership until every piece needed to honour a press is
        // ready: consuming a key we cannot act on (or cannot put the volume
        // step back) is exactly the "swallowed volume key" regression.
        val gesturesEnabled = hasVolumeGestures()
                && mAudioManager != null
                && displayManager != null
                && feedbackManager != null
        val consume =
                VolumeKeyGesturePolicy.shouldConsumeVolumeKeyEvent(gesturesEnabled)
        if (consume) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    onVolumeKeyDown(event)
                }
                KeyEvent.ACTION_UP -> {
                    onVolumeKeyUp(event)
                }
                else -> {
                }
            }
        } else {
            // Gestures may have been switched off mid-press; drop any timer.
            clearVolumeGestureState()
        }
        return consume
    }

    private fun onVolumeKeyDown(event: KeyEvent) {
        if (!VolumeKeyGesturePolicy.shouldStartLongPress(
                event.repeatCount, /* gesturesEnabled= */ true,
                mVolumeLongPressFired)) {
            // Repeat DOWN events arrive while the key is held. Restarting the
            // long-press timer on each repeat would prevent it ever firing.
            return
        }
        mVolumeKeyHandler.removeCallbacks(mVolumeLongPressRunnable)
        mVolumeLongPressFired = false
        mVolumeLongPressKeyCode = event.keyCode
        mVolumeKeyHandler.postDelayed(mVolumeLongPressRunnable,
                VolumeKeyGesturePolicy.LONG_PRESS_TIMEOUT_MS)
    }

    private fun onVolumeKeyUp(event: KeyEvent) {
        mVolumeKeyHandler.removeCallbacks(mVolumeLongPressRunnable)
        val keyCode = event.keyCode
        val now = SystemClock.uptimeMillis()
        val doubleActionEnabled =
                !isNoneVolumeAction(readVolumeAction(GESTURE_DOUBLE))
        val outcome =
                VolumeKeyGesturePolicy.classifyTap(
                        /* gesturesEnabled= */ true,
                        mVolumeLongPressFired,
                        doubleActionEnabled,
                        now,
                        mVolumeLastTapTime,
                        keyCode == mVolumeLastTapKeyCode,
                        VolumeKeyGesturePolicy.DOUBLE_PRESS_TIMEOUT_MS)
        mVolumeLongPressFired = false
        when (outcome) {
            VolumeKeyGesturePolicy.TapOutcome.ADJUST_VOLUME_NOW -> {
                // No double-press action configured, so there is nothing to
                // wait for: the slow press takes effect immediately.
                cancelPendingSlowPress()
                adjustSystemVolume(keyCode)
                clearVolumeTapHistory()
            }
            VolumeKeyGesturePolicy.TapOutcome.SCHEDULE_SLOW_PRESS -> {
                mVolumeKeyHandler.removeCallbacks(mSlowPressRunnable)
                mPendingSlowPressKeyCode = keyCode
                mVolumeKeyHandler.postDelayed(mSlowPressRunnable,
                        VolumeKeyGesturePolicy.DOUBLE_PRESS_TIMEOUT_MS)
                mVolumeLastTapTime = now
                mVolumeLastTapKeyCode = keyCode
            }
            VolumeKeyGesturePolicy.TapOutcome.RUN_DOUBLE_ACTION -> {
                // Cancel the first tap's pending step: a double press never
                // changes the volume, it only runs the configured action.
                cancelPendingSlowPress()
                clearVolumeTapHistory()
                performVolumeAction(readVolumeAction(GESTURE_DOUBLE))
            }
            VolumeKeyGesturePolicy.TapOutcome.SUPPRESS_AFTER_LONG_PRESS -> {
                cancelPendingSlowPress()
                clearVolumeTapHistory()
            }
            VolumeKeyGesturePolicy.TapOutcome.PASSTHROUGH -> {
            }
            else -> {
            }
        }
    }

    /**
     * Applies the single volume step that Android would have applied, because
     * the key event was consumed. Only a confirmed slow press reaches this
     * method: long press and double press run their action instead and never
     * change the volume.
     */
    private fun adjustSystemVolume(keyCode: Int) {
        if (mAudioManager == null
                || (keyCode != KeyEvent.KEYCODE_VOLUME_UP
                    && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN)) {
            return
        }
        val direction = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP)
                AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        val mode = mAudioManager!!.mode
        val inCall = mode == AudioManager.MODE_IN_CALL
                || mode == AudioManager.MODE_IN_COMMUNICATION
        val stream =
                VolumeKeyGesturePolicy.volumeStream(inCall)
        val streamType =
                if (stream == VolumeKeyGesturePolicy.VolumeStream.VOICE_CALL)
                        AudioManager.STREAM_VOICE_CALL
                else AudioManager.STREAM_MUSIC
        try {
            mAudioManager!!.adjustStreamVolume(streamType, direction,
                    AudioManager.FLAG_SHOW_UI)
        } catch (e: SecurityException) {
            Log.w("BrailleBack", "adjustStreamVolume denied: " + e)
        }
    }

    private fun cancelPendingSlowPress() {
        mVolumeKeyHandler.removeCallbacks(mSlowPressRunnable)
        mPendingSlowPressKeyCode = 0
    }

    private fun clearVolumeTapHistory() {
        mVolumeLastTapTime = 0L
        mVolumeLastTapKeyCode = 0
    }

    private fun clearVolumeGestureState() {
        mVolumeKeyHandler.removeCallbacks(mVolumeLongPressRunnable)
        cancelPendingSlowPress()
        mVolumeLongPressFired = false
        clearVolumeTapHistory()
    }

    /**
     * Whether the service should take ownership of the volume keys. This is
     * also the escape hatch: switching the preference off (or setting every
     * slot to "none") restores stock Android behaviour instantly, including
     * long-press auto-repeat.
     */
    private fun hasVolumeGestures(): Boolean {
        val prefs =
                PreferenceManager.getDefaultSharedPreferences(this)
        if (!prefs.getBoolean(getString(R.string.pref_volume_gestures_enabled_key),
                resources.getBoolean(
                        R.bool.pref_volume_gestures_enabled_default))) {
            return false
        }
        return !isNoneVolumeAction(readVolumeAction(GESTURE_LONG_UP))
                || !isNoneVolumeAction(readVolumeAction(GESTURE_LONG_DOWN))
                || !isNoneVolumeAction(readVolumeAction(GESTURE_DOUBLE))
    }

    private fun readVolumeAction(gesture: Int): String {
        val key: String
        when (gesture) {
            GESTURE_LONG_UP -> {
                key = getString(R.string.pref_volume_long_press_up_key)
            }
            GESTURE_LONG_DOWN -> {
                key = getString(R.string.pref_volume_long_press_down_key)
            }
            GESTURE_DOUBLE -> {
                key = getString(R.string.pref_volume_double_press_key)
            }
            else -> {
                return getString(R.string.pref_volume_action_none_value)
            }
        }
        return PreferenceManager.getDefaultSharedPreferences(this)
                .getString(key, getString(R.string.pref_volume_action_none_value))
                ?: getString(R.string.pref_volume_action_none_value)
    }

    private fun isNoneVolumeAction(action: String): Boolean {
        return getString(R.string.pref_volume_action_none_value).equals(action)
    }

    private fun performVolumeAction(action: String) {
        if (displayManager == null || feedbackManager == null) {
            return
        }
        if (getString(R.string.pref_volume_action_pause_value).equals(action)) {
            val paused = displayManager!!.toggleFrozen()
            feedbackManager!!.emitFeedback(
                if (paused) FeedbackManager.TYPE_NAVIGATE_INTO_HIERARCHY
                else FeedbackManager.TYPE_NAVIGATE_OUT_OF_HIERARCHY)
        } else if (getString(R.string.pref_volume_action_grade_value)
                .equals(action)) {
            val contractedKey =
                    getString(R.string.pref_braille_contracted_mode_key)
            val prefs =
                    PreferenceManager.getDefaultSharedPreferences(this)
            val contracted = prefs.getBoolean(contractedKey, false)
            prefs.edit().putBoolean(contractedKey, !contracted).apply()
            feedbackManager!!.emitFeedback(
                if (!contracted) FeedbackManager.TYPE_GRADE_8_DOT
                else FeedbackManager.TYPE_GRADE_6_DOT)
        } else if (getString(R.string.pref_volume_action_bluetooth_value)
                .equals(action)) {
            val wasConnected = displayManager!!.isDisplayConnected()
            displayManager!!.toggleBluetoothConnection()
            feedbackManager!!.emitFeedback(
                if (wasConnected) FeedbackManager.TYPE_DISPLAY_DISCONNECTED
                else FeedbackManager.TYPE_DISPLAY_CONNECTED)
        }
    }

    private fun parseIdleClearMinutes(value: String): Long {
        try {
            val minutes = Integer.parseInt(value)
            return if (minutes <= 0) 0L else minutes * 60L * 1000L
        } catch (e: NumberFormatException) {
            return 3 * 60L * 1000L
        }
    }

    /**
     * Promotes this accessibility service to a foreground service with a
     * persistent, low-importance notification.  Uses the manifest-declared
     * {@code connectedDevice} type on API 29+ (required subset check passes
     * because FOREGROUND_SERVICE_TYPE_MANIFEST resolves to the manifest
     * flags).  Callers must tolerate failure: on Android 14+ the platform
     * validates the connectedDevice prerequisites (e.g. BLUETOOTH_CONNECT)
     * and throws if they are unmet.
     */
    private fun promoteToForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                    FGS_CHANNEL_ID,
                    getString(R.string.braille_display_notif_channel),
                    NotificationManager.IMPORTANCE_LOW)
            channel.setDescription(
                    getString(R.string.braille_display_notif_title))
            getSystemService(NotificationManager::class.java)!!
                    .createNotificationChannel(channel)
        }
        val builder =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        Notification.Builder(this, FGS_CHANNEL_ID)
                else Notification.Builder(this)
        val notification = builder
                .setContentTitle(getString(R.string.braille_display_notif_title))
                .setContentText(getString(R.string.braille_display_notif_text))
                .setSmallIcon(R.drawable.ic_brailleback)
                .setOngoing(true)
                .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(FGS_NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST)
        } else {
            startForeground(FGS_NOTIFICATION_ID, notification)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // 文件日志要在任何可能抛异常的重活之前就绪：onServiceConnected 里的
        // 前台提升 / BrailleDisplayController / HidBridge 一旦抛错，日志通道
        // 不能跟着一起丢，否则用户点"清除日志/分享日志"只会看到上一次进程的旧文件。
        PreferenceUtils.initLogLevel(this)
        LogFileWriter.init(this)
    }

    override fun onServiceConnected() {
        instance = this
        // Required to put the slow-press volume step back after consuming the key.
        mAudioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // Promote to a foreground service so the braille display connection
        // survives idle pruning and aggressive OEM battery managers.  Best
        // effort only: if the platform rejects the promotion (e.g. missing
        // BLUETOOTH_CONNECT grant for the connectedDevice type on Android 14+)
        // the service still runs, just with normal background priority.
        try {
            promoteToForeground()
        } catch (e: RuntimeException) {
            Log.w("BrailleBack", "Foreground promotion failed: " + e)
        }
        // Hand the BrailleDisplayController over to the display service
        // so it can drive Braille HID standard (usage page 0x41) displays.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            // BrailleDisplayController is flag-gated on Android 15 and guaranteed
            // only on Android 16+.  When the platform flag is off the call throws;
            // fall back to classic RFCOMM instead of crashing the service.
            var bdc: android.accessibilityservice.BrailleDisplayController? = null
            try {
                bdc = getBrailleDisplayController()
            } catch (e: RuntimeException) {
                Log.w("BrailleBack", "BrailleDisplayController unavailable: " + e)
            }
            Log.i("BrailleBack", "onServiceConnected pid=" + android.os.Process.myPid()
                + " BrailleDisplayController=" + bdc)
            // Publish with this instance as owner: a rebind can create this service
            // again before the previous instance is destroyed, and the stale
            // onDestroy() must not clear the fresh controller.
            Log.i("BrailleBack", "Publishing BrailleDisplayController=" + bdc
                + " sdk=" + Build.VERSION.SDK_INT)
            HidBridge.setController(this, bdc)
        }
        PreferenceManager.getDefaultSharedPreferences(this)
                .registerOnSharedPreferenceChangeListener(mPreferenceListener)
        PreferenceUtils.initLogLevel(this)
        LogFileWriter.init(this)
        // 触摸探索透传探针（实验性临时代码，见 PassthroughProbe）。
        // 只注册广播监听，不自动生效；收到 adb 广播后才置 flag 与区域，并会在 60 秒后自行撤销。
        PassthroughProbe.install(this)
        if (isInfrastructureInitialized) {
            return
        }

        handler.sendEmptyMessage(WHAT_START)
        // We are in an initialized state now.
        isInfrastructureInitialized = true

        // 把宿主能力（语音、音效、焦点、全局动作）注入移植过来的屏幕盲文键盘。
        // 注入失败绝不能影响点显器主链路，因此整体包在 try/catch 里。
        try {
            brailleImeHostBridge = com.googlecode.eyesfree.brailleback.ime.BrailleImeHostBridge(this)
            com.google.android.accessibility.brailleime.BrailleIme.initialize(
                this,
                brailleImeHostBridge,
                brailleImeHostBridge,
                /* brailleDisplayForBrailleIme= */ null)
            Log.i("BrailleBack", "BrailleIme(port) initialized")
        } catch (e: RuntimeException) {
            Log.w("BrailleBack", "BrailleIme(port) init failed: " + e)
        } catch (e: LinkageError) {
            Log.w("BrailleBack", "BrailleIme(port) init failed: " + e)
        }
    }

    override fun onDestroy() {
        instance = null
        // Do not let a queued WHAT_START re-initialize a destroyed service.
        handler.removeCallbacksAndMessages(null)
        mVolumeKeyHandler.removeCallbacksAndMessages(null)
        // Leave the foreground state explicitly; the notification is removed.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
        // Drop the static BrailleDisplayController reference held by HidBridge.
        // It must be cleared before shutdownDependencies(): otherwise the static
        // outlives this service, HidBridge.isAvailable() keeps returning true, and
        // DisplayService may try to connect using a controller bound to a dead
        // service.  Cleared early so the shutdown path sees no stale controller.
        // Ownership is checked: when this instance has already been superseded,
        // the newer controller stays published.
        HidBridge.setController(this, null)
        PreferenceManager.getDefaultSharedPreferences(this)
                .unregisterOnSharedPreferenceChangeListener(mPreferenceListener)
        // 服务销毁前必须撤销透传，否则设备会停留在"读屏摸不动"的状态。
        PassthroughProbe.uninstall(this)
        super.onDestroy()
        if (displayManager != null) {
            displayManager!!.setContent(DisplayManager.Content(getString(R.string.shutting_down)))

            // We are not in an initialized state anymore.
            isInfrastructureInitialized = false
        }
        // 释放移植键盘持有的 TTS / SoundPool，避免重复绑定后泄漏。
        if (brailleImeHostBridge != null) {
            // 先撤掉触摸探索直通区，避免服务被系统回收后整屏仍处于直通状态（读屏会"摸不动"）。
            brailleImeHostBridge!!.forceClearPassthrough()
            brailleImeHostBridge!!.shutdown()
            brailleImeHostBridge = null
        }
        shutdownDependencies()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // A full event dump is several hundred bytes and the framework emits
        // dozens of events per second. Writing all of them buried every other
        // record and rotated the log file within minutes, so the compact
        // summary is what a normal (DEBUG) log keeps; the complete dump is
        // reserved for VERBOSE.
        //
        // 诊断包（DIAG_LOG_LEVEL>0）是发给用户排查点显器连接问题的，事件流
        // 会把 1MB 滚动日志在一小时内冲干净，所以诊断包里连摘要也只在
        // VERBOSE 保留。
        val eventSummaryLevel =
            if (BuildConfig.DIAG_LOG_LEVEL > 0) Log.VERBOSE else Log.DEBUG
        if (LogUtils.shouldLog(eventSummaryLevel)) {
            LogUtils.log(this, eventSummaryLevel, "Event: %s", describeEvent(event))
        }
        if (LogUtils.shouldLog(Log.VERBOSE)) {
            LogUtils.log(this, Log.VERBOSE, "Event detail: %s", event.toString())
            LogUtils.log(this, Log.VERBOSE, "Node detail: %s", event.source)
        }
        if (modeSwitcher != null) {
            modeSwitcher!!.onObserveAccessibilityEvent(event)
            modeSwitcher!!.onAccessibilityEvent(event)
        }
        if (labelManager != null) {
            labelManager!!.onAccessibilityEvent(event)
        }
    }

    override fun onInterrupt() {
        // Nothing to interrupt.
    }

    override fun onConfigurationChanged(newConfiguration: Configuration) {
        if (translatorManager != null) {
            translatorManager!!.onConfigurationChanged(newConfiguration)
        }
    }

    override fun onSearchStarted() {
        // Nothing to do here.
    }

    override fun onSearchFinished() {
        modeSwitcher!!.overrideMode(null)
    }

    override fun onMenuClosed() {
        modeSwitcher!!.overrideMode(null)
    }

    /**
     * Starts search mode in BrailleBack. Optionally tries to start the tutorial
     * first.
     */
    fun startSearchMode() {
        modeSwitcher!!.overrideMode(searchNavigationMode)
    }

    private fun startSearchWithTutorial() {
        searchNavigationMode!!.setInitialNodeToCurrent()
        // Try to start the search tutorial activity first. Only if it
        // doesn't start do we activate search mode.
        if (SearchTutorialActivity.tryStartActivity(this)) {
            return
        }
        startSearchMode()
    }

    private fun initializeDependencies() {
        // Must initialize label manager before navigation modes.
        initializeLabelManager()
        feedbackManager = FeedbackManager(this)
        translatorManager = TranslatorManager(this)
        selfBrailleManager = SelfBrailleManager()
        ruleRepository = BrailleRuleRepository(this)
        nodeBrailler = NodeBrailler(this, ruleRepository!!, selfBrailleManager!!)
        initializeDisplayManager()
        initializeNavigationMode()
        imeHelper = IMEHelper(this)
        focusTracker = FocusTracker(this)
        focusTracker!!.register()
    }

    private fun updateServiceInfo() {
        // Publicize service meta-data stating that this service does braille. Would be done in
        // accessibility service config if it supported accessibilityFeedbackType FEEDBACK_BRAILLE.
        val info: AccessibilityServiceInfo? = serviceInfo
        if (info == null) {
            LogUtils.log(this, Log.ERROR, "getServiceInfo() returned null")
            return
        }
        info.feedbackType = info.feedbackType or AccessibilityServiceInfo.FEEDBACK_BRAILLE
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        setServiceInfo(info)
    }

    private fun initializeLabelManager() {
        if (Build.VERSION.SDK_INT >= CustomLabelManager.MIN_API_LEVEL) {
            try {
                labelManager = CustomLabelManager(this)
            } catch (e: SecurityException) {
                // Don't use labeling features if there's a permission denial
                // due to a key mismatch
                LogUtils.log(this, Log.ERROR,
                        "Not using labeling due to permission denial.")
            }

            if (labelManager != null) {
                packageReceiver = PackageRemovalReceiver()
                ContextCompat.registerReceiver(this, packageReceiver,
                    packageReceiver!!.getFilter(), ContextCompat.RECEIVER_NOT_EXPORTED)
                labelManager!!.ensureDataConsistency()
            }
        }
    }

    private fun initializeDisplayManager() {
        displayManager =
            DisplayManager(
                translatorManager!!,
                this /*context*/,
                this /*panOverflowListener*/,
                this /*connectionStateChangeListener*/,
                this /*inputEventListener*/)
        displayManager!!.setContent(DisplayManager.Content(getString(R.string.display_connected)))
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        displayManager!!.setMotionMode(prefs.getBoolean(
                getString(R.string.pref_motion_mode_key), false))
        displayManager!!.setPauseClear(
                getString(R.string.pref_pause_behavior_clear_value)
                        .equals(prefs.getString(
                                getString(R.string.pref_pause_behavior_key), "")))
        displayManager!!.setIdleClearTimeoutMs(parseIdleClearMinutes(
                prefs.getString(getString(R.string.pref_idle_clear_key), "3") ?: "3"))
        displayManager!!.setCursorDotsEnabled(prefs.getBoolean(
                getString(R.string.pref_cursor_dots_key), false))
    }

    private fun initializeNavigationMode() {
        val defaultNavigationMode =
            DefaultNavigationMode(
                displayManager!!,
                this,
                feedbackManager!!,
                selfBrailleManager!!,
                nodeBrailler!!,
                ruleRepository!!)
        val newImeNavMode =
            IMENavigationMode(
                defaultNavigationMode,
                this,
                displayManager!!,
                feedbackManager!!,
                selfBrailleManager!!,
                translatorManager!!)
        imeNavigationMode = newImeNavMode
        BrailleIME.setSingletonHost(newImeNavMode)
        modeSwitcher =
            ModeSwitcher(
                newImeNavMode, TreeDebugNavigationMode(displayManager!!, feedbackManager!!, this))
        modeSwitcher!!.onActivate()

        // Create separate SearchNavigationMode.
        searchNavigationMode =
            SearchNavigationMode(
                displayManager!!,
                this /*accessibilityService*/,
                feedbackManager!!,
                translatorManager!!,
                selfBrailleManager!!,
                nodeBrailler!!,
                this /*searchStateListener*/,
                labelManager)

        // Create separate BrailleMenuNavigationMode.
        brailleMenuNavigationMode =
            BrailleMenuNavigationMode(
                displayManager!!,
                this /*accessibilityService*/,
                feedbackManager!!,
                labelManager!!,
                this /*brailleMenuListener*/)
    }

    private fun shutdownDependencies() {
        if (displayManager != null) {
            displayManager!!.shutdown()
            displayManager = null
        }
        if (translatorManager != null) {
            translatorManager!!.shutdown()
            translatorManager = null
        }
        if (feedbackManager != null) {
            // Releases the TTS/audio/MIDI resources held by FeedbackController.
            // Deliberately NOT nulling the reference: feedbackManager is used by
            // callbacks such as onConnectionStateChanged()/onMappedInputEvent(),
            // and nulling it could turn a leak into an NPE.  shutdown() is what
            // actually frees the resources.
            feedbackManager!!.shutdown()
        }
        if (focusTracker != null) {
            focusTracker!!.unregister()
            focusTracker = null
        }
        if (labelManager != null) {
            labelManager!!.shutdown()
            labelManager = null
        }
        if (packageReceiver != null) {
            unregisterReceiver(packageReceiver!!)
            packageReceiver = null
        }

        BrailleIME.setSingletonHost(null)
    }

    fun runHelp(): Boolean {
        val intent = Intent(this, KeyBindingsActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                or Intent.FLAG_ACTIVITY_CLEAR_TOP
                or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(intent)
        return true
    }

    fun getLabelManager(): CustomLabelManager? {
        return labelManager
    }

    /**
     * Signal that this node, or one of its descendants, has been changed in a
     * way that could affect how it is displayed.  If the node is contributing
     * to the content of the display, the display content will be updated
     * accordingly.
     */
    fun invalidateNode(node: AccessibilityNodeInfo) {
        val wrapped =
                AccessibilityNodeInfoCompat(node)
        modeSwitcher!!.onInvalidateAccessibilityNode(wrapped)
    }

    companion object {
        /** Start the service, initializing a few components. */
        private const val WHAT_START = 2

        // Braille dot bit pattern constants.
        const val DOT1 = 0x01
        const val DOT2 = 0x02
        const val DOT3 = 0x04
        const val DOT4 = 0x08
        const val DOT5 = 0x10
        const val DOT6 = 0x20
        const val DOT7 = 0x40
        const val DOT8 = 0x80

        const val LABELING_ENABLED = false

        private var instance: BrailleBackService? = null

        /**
         * Dot combination for switching navigation mode.
         * At this time, this is only used for debugging.
         */
        private val SWITCH_NAVIGATION_MODE_DOTS = DOT7 or DOT8

        private const val GESTURE_LONG_UP = 0
        private const val GESTURE_LONG_DOWN = 1
        private const val GESTURE_DOUBLE = 2

        private const val FGS_CHANNEL_ID = "braille_display_connection"
        private const val FGS_NOTIFICATION_ID = 1

        /** Upper bound on the characters kept for one field of the summary. */
        private const val EVENT_TEXT_LIMIT = 120

        /** Builds the single-line summary logged for every accessibility event. */
        private fun describeEvent(event: AccessibilityEvent): String {
            val summary = StringBuilder(96)
            summary.append(AccessibilityEvent.eventTypeToString(event.eventType))
            appendEventField(summary, "pkg", event.packageName)
            appendEventField(summary, "cls", event.className)
            appendEventField(summary, "text", joinEventText(event.text))
            appendEventField(summary, "desc", event.contentDescription)
            return summary.toString()
        }

        private fun appendEventField(
                summary: StringBuilder, name: String, value: CharSequence?) {
            if (TextUtils.isEmpty(value)) {
                return
            }
            summary.append(' ').append(name).append('=').append(clipEventText(value))
        }

        private fun joinEventText(parts: List<CharSequence>?): String? {
            if (parts == null || parts.isEmpty()) {
                return null
            }
            val joined = StringBuilder()
            for (part in parts) {
                if (TextUtils.isEmpty(part)) {
                    continue
                }
                if (joined.length > 0) {
                    joined.append(" | ")
                }
                joined.append(part)
            }
            return if (joined.length == 0) null else joined.toString()
        }

        private fun clipEventText(value: CharSequence?): String {
            val text = value.toString().replace('\n', ' ').replace('\r', ' ').trim()
            if (text.length <= EVENT_TEXT_LIMIT) {
                return text
            }
            return text.substring(0, EVENT_TEXT_LIMIT) + "..."
        }

        @JvmStatic
        fun getActiveInstance(): BrailleBackService? {
            return instance
        }

        /**
         * 单注册 IME 用它判断本次会话该用「点显器硬件」还是「触屏点字」形态。
         * 服务未启动或点显器尚未连接时返回 false（走触屏键盘），不做任何推测。
         */
        @JvmStatic
        fun isBrailleDisplayConnected(): Boolean {
            val service = instance
            return service != null
                && service.displayManager != null
                && service.displayManager!!.isDisplayConnected()
        }
    }
}
