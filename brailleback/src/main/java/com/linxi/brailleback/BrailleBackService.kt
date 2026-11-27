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

package com.linxi.brailleback

import android.accessibilityservice.AccessibilityService
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
import com.google.android.accessibility.braille.common.translate.PinyinIndex
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import com.googlecode.eyesfree.utils.SettingsStore
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.googlecode.eyesfree.braille.display.BrailleInputEvent
import com.googlecode.eyesfree.braille.display.Display
import com.linxi.brailleback.compose.KeysGesturesLogic
import com.linxi.brailleback.compose.SoundBehaviorLogic
import com.googlecode.eyesfree.braille.service.display.HidBridge
import com.linxi.brailleback.rule.BrailleRuleRepository
import com.linxi.brailleback.utils.PreferenceUtils
import com.linxi.brailleback.utils.LogFileWriter
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

    internal var feedbackManager: FeedbackManager? = null

    private val mPreferenceListener =
        object : SharedPreferences.OnSharedPreferenceChangeListener {
            override fun onSharedPreferenceChanged(
                    prefs: SharedPreferences?, key: String?) {
                // 监听器可能在初始化前或销毁后收到回调：两份引用各取一次，为空就忽略（原来会崩）。
                val dm = displayManager ?: return
                val p = prefs ?: return
                if (getString(R.string.pref_motion_mode_key).equals(key)) {
                    dm.setMotionMode(p.getBoolean(key, false))
                } else if (getString(R.string.pref_pause_behavior_key).equals(key)) {
                    dm.setPauseClear(
                        getString(R.string.pref_pause_behavior_clear_value)
                            .equals(p.getString(key, "")))
                } else if (getString(R.string.pref_idle_clear_key).equals(key)) {
                    dm.setIdleClearTimeoutMs(
                        parseIdleClearMinutes(p.getString(key, "3") ?: "3"))
                } else if (getString(R.string.pref_cursor_dots_key).equals(key)) {
                    dm.setCursorDotsEnabled(p.getBoolean(key, false))
                }
            }
        }
    internal var translatorManager: TranslatorManager? = null
    internal var displayManager: DisplayManager? = null
    internal var selfBrailleManager: SelfBrailleManager? = null
    internal var nodeBrailler: NodeBrailler? = null
    internal var ruleRepository: BrailleRuleRepository? = null
    internal var focusTracker: FocusTracker? = null
    internal var imeHelper: IMEHelper? = null
    internal var modeSwitcher: ModeSwitcher? = null
    internal var searchNavigationMode: SearchNavigationMode? = null
    internal var brailleMenuNavigationMode: BrailleMenuNavigationMode? = null
    // Package-private so that Kotlin code in the same package (DisplayManager.kt)
    // can read it; Kotlin cannot access Java protected members from a non-subclass.
    var imeNavigationMode: IMENavigationMode? = null
    internal var labelManager: CustomLabelManager? = null
    internal var packageReceiver: PackageRemovalReceiver? = null

    /** Set if the infrastructure is initialized. */
    private var isInfrastructureInitialized = false
    // 屏幕盲文键盘（移植自 TalkBack brailleime）的宿主桥接。
    // 仅在首次初始化时创建，onDestroy 时释放其 TTS / SoundPool。
    private var brailleImeHostBridge: com.linxi.brailleback.ime.BrailleImeHostBridge? = null

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
        // 回调可能早于 initializeDependencies 到达：取一次局部，为空就跳过（原来会崩）。
        val dm = displayManager
        if (dm != null && !dm.isSimulatedDisplay()) {
            if (state == Display.STATE_NOT_CONNECTED) {
                feedbackManager?.emitFeedback(FeedbackManager.TYPE_DISPLAY_DISCONNECTED)
            } else if (state == Display.STATE_CONNECTED) {
                feedbackManager?.emitFeedback(FeedbackManager.TYPE_DISPLAY_CONNECTED)
            }
        }
        if (state == Display.STATE_CONNECTED && dm != null) {
            applyMotionModeDefault(SettingsStore.get(this))
        }
        focusTracker?.onConnectionStateChanged(state)
    }

    override fun onMappedInputEvent(event: BrailleInputEvent,
            content: Content) =
        dispatchMappedInputEvent(event, content)

    override fun onPanLeftOverflow(content: Content) =
        dispatchPanLeftOverflow(content)

    override fun onPanRightOverflow(content: Content) =
        dispatchPanRightOverflow(content)

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
        if (BuildConfig.DEBUG) {
            Log.d("BrailleBack", "onKeyEvent $keyCode action=${event.action} " +
                    "gestures=$gesturesEnabled consume=$consume " +
                    "longUp=${readVolumeAction(GESTURE_LONG_UP)}")
        }
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
        // 取一次引用：下面调音量要用两次，中间被置空就炸（原来靠断言顶着）。
        val audio = mAudioManager
        if (audio == null
                || (keyCode != KeyEvent.KEYCODE_VOLUME_UP
                    && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN)) {
            return
        }
        val direction = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP)
                AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        val mode = audio.mode
        val inCall = mode == AudioManager.MODE_IN_CALL
                || mode == AudioManager.MODE_IN_COMMUNICATION
        val stream =
                VolumeKeyGesturePolicy.volumeStream(inCall)
        val streamType =
                if (stream == VolumeKeyGesturePolicy.VolumeStream.VOICE_CALL)
                        AudioManager.STREAM_VOICE_CALL
                else AudioManager.STREAM_MUSIC
        try {
            audio.adjustStreamVolume(streamType, direction,
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
                SettingsStore.get(this)
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
        // 偏好没写过时按界面同一份出厂默认算，而不是一律 none——否则全新安装的三个槽位
        // 都是 none，音量键不再被接管，长按暂停直接失灵。
        return KeysGesturesLogic.effectiveValue(
                SettingsStore.get(this).getString(key, null),
                key,
                getString(R.string.pref_volume_action_none_value),
                getString(R.string.pref_volume_action_pause_value),
                getString(R.string.pref_volume_action_grade_value))
    }

    private fun isNoneVolumeAction(action: String): Boolean {
        return getString(R.string.pref_volume_action_none_value).equals(action)
    }

    private fun performVolumeAction(action: String) {
        // 两个依赖各取一次：三个分支都要用，逐处断言会留下中间被清空的窗口。
        val dm = displayManager ?: return
        val feedback = feedbackManager ?: return
        if (BuildConfig.DEBUG) {
            Log.d("BrailleBack", "volume action: $action")
        }
        if (getString(R.string.pref_volume_action_pause_value).equals(action)) {
            val paused = dm.toggleFrozen()
            feedback.emitFeedback(
                if (paused) FeedbackManager.TYPE_NAVIGATE_INTO_HIERARCHY
                else FeedbackManager.TYPE_NAVIGATE_OUT_OF_HIERARCHY)
        } else if (getString(R.string.pref_volume_action_grade_value)
                .equals(action)) {
            val contractedKey =
                    getString(R.string.pref_braille_contracted_mode_key)
            val prefs =
                    SettingsStore.get(this)
            val contracted = prefs.getBoolean(contractedKey, false)
            prefs.edit().putBoolean(contractedKey, !contracted).apply()
            feedback.emitFeedback(
                if (!contracted) FeedbackManager.TYPE_GRADE_8_DOT
                else FeedbackManager.TYPE_GRADE_6_DOT)
        } else if (getString(R.string.pref_volume_action_bluetooth_value)
                .equals(action)) {
            val wasConnected = dm.isDisplayConnected()
            dm.toggleBluetoothConnection()
            feedback.emitFeedback(
                if (wasConnected) FeedbackManager.TYPE_DISPLAY_DISCONNECTED
                else FeedbackManager.TYPE_DISPLAY_CONNECTED)
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
        val channel = NotificationChannel(
                FGS_CHANNEL_ID,
                getString(R.string.braille_display_notif_channel),
                NotificationManager.IMPORTANCE_LOW)
        channel.setDescription(
                getString(R.string.braille_display_notif_title))
        // 极少数机型可能没有这个系统服务：取不到就跳过渠道创建，不要崩。
        getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        val builder = Notification.Builder(this, FGS_CHANNEL_ID)
        val notification = builder
                .setContentTitle(getString(R.string.braille_display_notif_title))
                .setContentText(getString(R.string.braille_display_notif_text))
                .setSmallIcon(R.drawable.ic_brailleback)
                .setOngoing(true)
                .build()
        startForeground(FGS_NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST)
    }

    override fun onCreate() {
        super.onCreate()
        // 文件日志要在任何可能抛异常的重活之前就绪：onServiceConnected 里的
        // 前台提升 / BrailleDisplayController / HidBridge 一旦抛错，日志通道
        // 不能跟着一起丢，否则用户点"清除日志/分享日志"只会看到上一次进程的旧文件。
        PreferenceUtils.initLogLevel(this)
        LogFileWriter.init(this)
        // 中文输入用的拼音侧索引（字频 / 点位串→拼音 / 拼音串→词）有 3.6 万条，
        // 在服务启动时预热：第一次打字就不用等它解析，日志里也能直接看到是否加载成功。
        try {
            PinyinIndex.getInstance(this)
        } catch (e: RuntimeException) {
            Log.w("BrailleBack", "PinyinIndex warm-up failed: " + e)
        }
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
        SettingsStore.get(this)
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
            brailleImeHostBridge = com.linxi.brailleback.ime.BrailleImeHostBridge(this)
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
        stopForeground(STOP_FOREGROUND_REMOVE)
        // Drop the static BrailleDisplayController reference held by HidBridge.
        // It must be cleared before shutdownDependencies(): otherwise the static
        // outlives this service, HidBridge.isAvailable() keeps returning true, and
        // DisplayService may try to connect using a controller bound to a dead
        // service.  Cleared early so the shutdown path sees no stale controller.
        // Ownership is checked: when this instance has already been superseded,
        // the newer controller stays published.
        HidBridge.setController(this, null)
        SettingsStore.get(this)
                .unregisterOnSharedPreferenceChangeListener(mPreferenceListener)
        // 服务销毁前必须撤销透传，否则设备会停留在"读屏摸不动"的状态。
        PassthroughProbe.uninstall(this)
        super.onDestroy()
        val dm = displayManager
        if (dm != null) {
            dm.setContent(Content(getString(R.string.shutting_down)))

            // We are not in an initialized state anymore.
            isInfrastructureInitialized = false
        }
        // 释放移植键盘持有的 TTS / SoundPool，避免重复绑定后泄漏。
        val imeBridge = brailleImeHostBridge
        if (imeBridge != null) {
            // 先撤掉触摸探索直通区，避免服务被系统回收后整屏仍处于直通状态（读屏会"摸不动"）。
            imeBridge.forceClearPassthrough()
            imeBridge.shutdown()
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
        // 应用内界面（阅读器）独占点显器期间，前台一旦换成别的应用就立刻交还。
        // 只认 WINDOW_STATE_CHANGED：内容变化的噪音太大，下拉通知栏这类浮层
        // 不该被当成「离开本应用」。
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString()
            if (pkg != null && pkg != packageName) {
                displayManager?.onForeignAppWindow()
            }
        }
        modeSwitcher?.let { switcher ->
            switcher.onObserveAccessibilityEvent(event)
            switcher.onAccessibilityEvent(event)
        }
        labelManager?.onAccessibilityEvent(event)
    }

    override fun onInterrupt() {
        // Nothing to interrupt.
    }

    override fun onConfigurationChanged(newConfiguration: Configuration) {
        translatorManager?.onConfigurationChanged(newConfiguration)
    }

    override fun onSearchStarted() {
        // Nothing to do here.
    }

    override fun onSearchFinished() {
        modeSwitcher?.overrideMode(null)
    }

    override fun onMenuClosed() {
        modeSwitcher?.overrideMode(null)
    }

    /**
     * Starts search mode in BrailleBack. Optionally tries to start the tutorial
     * first.
     */
    fun startSearchMode() {
        modeSwitcher?.overrideMode(searchNavigationMode)
    }

    /**
     * 运动模式一律默认关闭，不再按连接方式推导。
     *
     * 用户在设置里手动拨过开关后（motion_mode_explicit 为 true），这个显式选择优先，
     * 完全以用户存下的值为准；从未手动拨过时，把运动模式归一化为关闭写回并下发。
     *
     * 历史口径：早期按连接方式推导（USB 默认开、蓝牙默认关）。USB 默认开会让每次刷新
     * 都被运动去抖压住 MOTION_MODE_DEBOUNCE_MS 再补刷，翻页有可感知延迟；现改为一律
     * 默认关闭，需要「快速滑动合并刷新、停下再刷」的用户可在设置里手动开启。
     */
    internal fun applyMotionModeDefault(prefs: SharedPreferences) {
        val motionKey = getString(R.string.pref_motion_mode_key)
        val explicit = prefs.getBoolean(
                getString(R.string.pref_motion_mode_explicit_key), false)
        val stored = prefs.getBoolean(motionKey, false)
        val value = SoundBehaviorLogic.resolveMotionMode(explicit, stored)
        if (!explicit && stored) {
            // 未显式拨过：旧版本可能按 USB 推导写入过 true，归一化写回关闭。
            prefs.edit().putBoolean(motionKey, false).apply()
        }
        displayManager?.setMotionMode(value)
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
        modeSwitcher?.onInvalidateAccessibilityNode(wrapped)
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
        internal val SWITCH_NAVIGATION_MODE_DOTS = DOT7 or DOT8

        private const val GESTURE_LONG_UP = 0
        private const val GESTURE_LONG_DOWN = 1
        private const val GESTURE_DOUBLE = 2

        private const val FGS_CHANNEL_ID = "braille_display_connection"
        private const val FGS_NOTIFICATION_ID = 1

        /**
         * 应用内界面（图片转点位视口）请求独占点显器：冻结服务侧的文字输出，
         * 屏上只留界面自己下发的点位。
         *
         * 为什么要独占：服务会把前台界面的无障碍文本翻译成盲文往下发，视口里每换一屏、
         * 每报一次状态都会被「文字」盖住点位，用户摸到的是字不是图。
         * 传 clear=false：冻结时不清屏，否则刚画上的点位可能被这条清屏命令擦掉。
         *
         * @return 是否真的冻上了（服务未启动 / 点显器没连时返回 false，调用方可不管你）
         */
        @JvmStatic
        fun holdDisplayForApp(): Boolean {
            val dm = instance?.displayManager ?: return false
            dm.holdForHostApp()
            return true
        }

        /** 交还点显器：恢复输入，等前台界面稳定一拍再按焦点重画。 */
        @JvmStatic
        fun releaseDisplayForApp() {
            instance?.displayManager?.releaseFromHostApp()
        }

        /**
         * 应用内界面把点显器整屏清空（全空方），不改冻结状态。
         *
         * 用在「离开阅读器回到书架」这类场景：屏上不该再留着上一本书的点位。
         * 服务没起来、点显器没连时静默返回，调用方不必判断。
         */
        @JvmStatic
        fun clearDisplayContent() {
            instance?.displayManager?.sendEmptyCommand()
        }

        /**
         * 清屏之后补一帧。
         *
         * 书架这类静止界面不产生焦点事件，清完不主动重画就会一直空着；
         * 延后一点是为了等阅读器交还独占（解冻）先落地。
         */
        @JvmStatic
        fun refreshDisplayContent() {
            instance?.displayManager?.requestRefresh(200L)
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
            // 取一次局部：实例与它的 displayManager 都可能在读取途中被销毁。
            val dm = instance?.displayManager ?: return false
            return dm.isDisplayConnected()
        }
    }
}