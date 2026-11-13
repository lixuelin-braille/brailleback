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

package com.googlecode.eyesfree.brailleback;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import androidx.core.content.ContextCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import com.googlecode.eyesfree.braille.display.BrailleInputEvent;
import com.googlecode.eyesfree.braille.display.Display;
import com.googlecode.eyesfree.braille.service.display.HidBridge;
import com.googlecode.eyesfree.brailleback.rule.BrailleRuleRepository;
import com.googlecode.eyesfree.brailleback.utils.PreferenceUtils;
import com.googlecode.eyesfree.brailleback.utils.LogFileWriter;
import com.googlecode.eyesfree.labeling.CustomLabelManager;
import com.googlecode.eyesfree.labeling.PackageRemovalReceiver;
import com.googlecode.eyesfree.utils.LogUtils;
import java.lang.ref.WeakReference;
import java.util.List;

/**
 * An accessibility service that provides feedback through a braille
 * display.
 */
public class BrailleBackService
        extends AccessibilityService
        implements Display.OnConnectionStateChangeListener,
        DisplayManager.OnMappedInputEventListener,
        DisplayManager.OnPanOverflowListener,
        SearchNavigationMode.SearchStateListener,
        BrailleMenuNavigationMode.BrailleMenuListener {

    /** Start the service, initializing a few components. */
    private static final int WHAT_START = 2;

    // Braille dot bit pattern constants.
    public static final int DOT1 = 0x01;
    public static final int DOT2 = 0x02;
    public static final int DOT3 = 0x04;
    public static final int DOT4 = 0x08;
    public static final int DOT5 = 0x10;
    public static final int DOT6 = 0x20;
    public static final int DOT7 = 0x40;
    public static final int DOT8 = 0x80;

  public static final boolean LABELING_ENABLED = false;

  private static BrailleBackService instance;

  private FeedbackManager feedbackManager;

  private final SharedPreferences.OnSharedPreferenceChangeListener
      mPreferenceListener =
      new SharedPreferences.OnSharedPreferenceChangeListener() {
        @Override
        public void onSharedPreferenceChanged(
            SharedPreferences prefs, String key) {
          if (displayManager == null) {
            return;
          }
          if (getString(R.string.pref_motion_mode_key).equals(key)) {
            displayManager.setMotionMode(prefs.getBoolean(key, false));
          } else if (getString(R.string.pref_pause_behavior_key).equals(key)) {
            displayManager.setPauseClear(
                getString(R.string.pref_pause_behavior_clear_value)
                    .equals(prefs.getString(key, "")));
          } else if (getString(R.string.pref_idle_clear_key).equals(key)) {
            displayManager.setIdleClearTimeoutMs(
                parseIdleClearMinutes(prefs.getString(key, "3")));
          } else if (getString(R.string.pref_cursor_dots_key).equals(key)) {
            displayManager.setCursorDotsEnabled(prefs.getBoolean(key, false));
          }
        }
      };
  private TranslatorManager translatorManager;
  private DisplayManager displayManager;
  private SelfBrailleManager selfBrailleManager;
  private NodeBrailler nodeBrailler;
  private BrailleRuleRepository ruleRepository;
  private FocusTracker focusTracker;
  private IMEHelper imeHelper;
  private ModeSwitcher modeSwitcher;
  private SearchNavigationMode searchNavigationMode;
  private BrailleMenuNavigationMode brailleMenuNavigationMode;
  // Package-private so that Kotlin code in the same package (DisplayManager.kt)
  // can read it; Kotlin cannot access Java protected members from a non-subclass.
  IMENavigationMode imeNavigationMode;
  private CustomLabelManager labelManager;
  private PackageRemovalReceiver packageReceiver;

    /** Set if the infrastructure is initialized. */
    private boolean isInfrastructureInitialized;
    // 屏幕盲文键盘（移植自 TalkBack brailleime）的宿主桥接。
    // 仅在首次初始化时创建，onDestroy 时释放其 TTS / SoundPool。
    private com.googlecode.eyesfree.brailleback.ime.BrailleImeHostBridge brailleImeHostBridge;

    /**
     * Dot combination for switching navigation mode.
     * At this time, this is only used for debugging.
     */
    private static final int SWITCH_NAVIGATION_MODE_DOTS = DOT7 | DOT8;

  /** {@link Handler} for executing messages on the service main thread. */
  private final Handler handler = new StartHandler(this);

  /**
   * Static to avoid retaining the accessibility service through the main
   * Looper while a start message is queued.
   */
  private static final class StartHandler extends Handler {
    private final WeakReference<BrailleBackService> serviceRef;

    StartHandler(BrailleBackService service) {
      super(Looper.getMainLooper());
      serviceRef = new WeakReference<>(service);
    }

    @Override
    public void handleMessage(Message message) {
      BrailleBackService service = serviceRef.get();
      if (service == null) {
        return;
      }
      switch (message.what) {
        case WHAT_START:
          service.updateServiceInfo();
          service.initializeDependencies();
          return;
        default: // fall out
      }
    }
  }

    @Override
    public void onConnectionStateChanged(int state) {
    if (!displayManager.isSimulatedDisplay()) {
            if (state == Display.STATE_NOT_CONNECTED) {
        feedbackManager.emitFeedback(FeedbackManager.TYPE_DISPLAY_DISCONNECTED);
            } else if (state == Display.STATE_CONNECTED) {
        feedbackManager.emitFeedback(FeedbackManager.TYPE_DISPLAY_CONNECTED);
            }
        }
    if (focusTracker != null) {
      focusTracker.onConnectionStateChanged(state);
        }
    }

    @Override
    public void onMappedInputEvent(BrailleInputEvent event,
            DisplayManager.Content content) {
    if (modeSwitcher == null) {
            return;
        }
        // Global commands can't be overriden.
        if (handleGlobalCommands(event)) {
            return;
        }
        if (BuildConfig.DEBUG
                && event.getCommand() == BrailleInputEvent.CMD_BRAILLE_KEY
                && event.getArgument() == SWITCH_NAVIGATION_MODE_DOTS) {
      modeSwitcher.switchMode();
            return;
        }
        if (event.getCommand() == BrailleInputEvent.CMD_TOGGLE_BRAILLE_MENU) {
      if (!LABELING_ENABLED) {
        return;
      }
      if (brailleMenuNavigationMode.isActive()) {
        modeSwitcher.overrideMode(null);
            } else {
        modeSwitcher.overrideMode(brailleMenuNavigationMode);
            }
            return;
        }
    if (modeSwitcher.onMappedInputEvent(event, content)) {
            return;
        }
        // Check native case after navigation mode handler to allow navigation
        // mode chance to deal with webview incremental search.
        if (event.getCommand() ==
                BrailleInputEvent.CMD_TOGGLE_INCREMENTAL_SEARCH) {
            // Search Navigation mode will handle disabling the mode.
            startSearchWithTutorial();
            return;
        }
    if (imeHelper.onInputEvent(event)) {
            return;
        }
        if (event.getCommand() == BrailleInputEvent.CMD_TOGGLE_BRAILLE_GRADE) {
            if (translatorManager == null) {
                return;
            }
            // 统一盲文表后，级切换直接在所选语言的六点与八点表之间切换。
            final boolean eightDot = translatorManager.toggleBrailleDots();
            feedbackManager.emitFeedback(eightDot
                    ? FeedbackManager.TYPE_GRADE_8_DOT
                    : FeedbackManager.TYPE_GRADE_6_DOT);

            // If showing preference screen... update displayed preference.
            Intent refreshPrefIntent =
                new Intent(SettingsActivity.INTENT_REFRESH_DISPLAY);
            refreshPrefIntent.setPackage(getPackageName());
            sendBroadcast(refreshPrefIntent);

            return;
        }
    feedbackManager.emitFeedback(FeedbackManager.TYPE_UNKNOWN_COMMAND);
    }

    private boolean handleGlobalCommands(BrailleInputEvent event) {
        boolean success;
        switch (event.getCommand()) {
            case BrailleInputEvent.CMD_GLOBAL_HOME:
                success = performGlobalAction(GLOBAL_ACTION_HOME);
                break;
            case BrailleInputEvent.CMD_GLOBAL_BACK:
                success = performGlobalAction(GLOBAL_ACTION_BACK);
                break;
            case BrailleInputEvent.CMD_GLOBAL_RECENTS:
                success = performGlobalAction(GLOBAL_ACTION_RECENTS);
                break;
            case BrailleInputEvent.CMD_GLOBAL_NOTIFICATIONS:
                success = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
                break;
            case BrailleInputEvent.CMD_HELP:
                success = runHelp();
                break;
            default:
                return false;
        }
        if (!success) {
      feedbackManager.emitFeedback(FeedbackManager.TYPE_COMMAND_FAILED);
        }
        // Don't fall through even if the command failed, we own these
        // commands.
        return true;
    }

    @Override
    public void onPanLeftOverflow(DisplayManager.Content content) {
    if (modeSwitcher != null && !modeSwitcher.onPanLeftOverflow(content)) {
      feedbackManager.emitFeedback(FeedbackManager.TYPE_NAVIGATE_OUT_OF_BOUNDS);
        }
    }

    @Override
    public void onPanRightOverflow(DisplayManager.Content content) {
    if (modeSwitcher != null && !modeSwitcher.onPanRightOverflow(content)) {
      feedbackManager.emitFeedback(FeedbackManager.TYPE_NAVIGATE_OUT_OF_BOUNDS);
        }
    }

    // Volume key gesture detection (long press up/down, double press).
    // While gestures are enabled the service CONSUMES every volume key and
    // re-applies the single volume step for an ordinary ("slow") press itself.
    // That is the only way to keep a long press or a fast double press from
    // also changing the volume: onKeyEvent() must decide synchronously and
    // cannot observe a gesture first, then consume it afterwards.
    // When gestures are disabled the keys go to the system untouched.
    private static final int GESTURE_LONG_UP = 0;
    private static final int GESTURE_LONG_DOWN = 1;
    private static final int GESTURE_DOUBLE = 2;

    private final Handler mVolumeKeyHandler = new Handler();
    private long mVolumeLastTapTime = 0;
    private int mVolumeLastTapKeyCode = 0;
    private int mVolumeLongPressKeyCode = 0;
    private boolean mVolumeLongPressFired = false;
    private int mPendingSlowPressKeyCode = 0;
    private AudioManager mAudioManager;

    private final Runnable mVolumeLongPressRunnable = new Runnable() {
        @Override
        public void run() {
            final boolean up = mVolumeLongPressKeyCode
                    == KeyEvent.KEYCODE_VOLUME_UP;
            final String action = readVolumeAction(
                    up ? GESTURE_LONG_UP : GESTURE_LONG_DOWN);
            if (isNoneVolumeAction(action)) {
                // Nothing configured for this slot: let the release fall back
                // to the slow-press branch so the key is not swallowed.
                return;
            }
            cancelPendingSlowPress();
            clearVolumeTapHistory();
            mVolumeLongPressFired = true;
            performVolumeAction(action);
        }
    };

    /** Re-applies the single volume step for a confirmed slow press. */
    private final Runnable mSlowPressRunnable = new Runnable() {
        @Override
        public void run() {
            adjustSystemVolume(mPendingSlowPressKeyCode);
            mPendingSlowPressKeyCode = 0;
            clearVolumeTapHistory();
        }
    };

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        final int keyCode = event.getKeyCode();
        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP
                && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false;
        }
        // Never take ownership until every piece needed to honour a press is
        // ready: consuming a key we cannot act on (or cannot put the volume
        // step back) is exactly the "swallowed volume key" regression.
        final boolean gesturesEnabled = hasVolumeGestures()
                && mAudioManager != null
                && displayManager != null
                && feedbackManager != null;
        final boolean consume =
                VolumeKeyGesturePolicy.shouldConsumeVolumeKeyEvent(gesturesEnabled);
        if (consume) {
            switch (event.getAction()) {
                case KeyEvent.ACTION_DOWN:
                    onVolumeKeyDown(event);
                    break;
                case KeyEvent.ACTION_UP:
                    onVolumeKeyUp(event);
                    break;
                default:
                    break;
            }
        } else {
            // Gestures may have been switched off mid-press; drop any timer.
            clearVolumeGestureState();
        }
        return consume;
    }

    private void onVolumeKeyDown(KeyEvent event) {
        if (!VolumeKeyGesturePolicy.shouldStartLongPress(
                event.getRepeatCount(), /* gesturesEnabled= */ true,
                mVolumeLongPressFired)) {
            // Repeat DOWN events arrive while the key is held. Restarting the
            // long-press timer on each repeat would prevent it ever firing.
            return;
        }
        mVolumeKeyHandler.removeCallbacks(mVolumeLongPressRunnable);
        mVolumeLongPressFired = false;
        mVolumeLongPressKeyCode = event.getKeyCode();
        mVolumeKeyHandler.postDelayed(mVolumeLongPressRunnable,
                VolumeKeyGesturePolicy.LONG_PRESS_TIMEOUT_MS);
    }

    private void onVolumeKeyUp(KeyEvent event) {
        mVolumeKeyHandler.removeCallbacks(mVolumeLongPressRunnable);
        final int keyCode = event.getKeyCode();
        final long now = SystemClock.uptimeMillis();
        final boolean doubleActionEnabled =
                !isNoneVolumeAction(readVolumeAction(GESTURE_DOUBLE));
        final VolumeKeyGesturePolicy.TapOutcome outcome =
                VolumeKeyGesturePolicy.classifyTap(
                        /* gesturesEnabled= */ true,
                        mVolumeLongPressFired,
                        doubleActionEnabled,
                        now,
                        mVolumeLastTapTime,
                        keyCode == mVolumeLastTapKeyCode,
                        VolumeKeyGesturePolicy.DOUBLE_PRESS_TIMEOUT_MS);
        mVolumeLongPressFired = false;
        switch (outcome) {
            case ADJUST_VOLUME_NOW:
                // No double-press action configured, so there is nothing to
                // wait for: the slow press takes effect immediately.
                cancelPendingSlowPress();
                adjustSystemVolume(keyCode);
                clearVolumeTapHistory();
                break;
            case SCHEDULE_SLOW_PRESS:
                mVolumeKeyHandler.removeCallbacks(mSlowPressRunnable);
                mPendingSlowPressKeyCode = keyCode;
                mVolumeKeyHandler.postDelayed(mSlowPressRunnable,
                        VolumeKeyGesturePolicy.DOUBLE_PRESS_TIMEOUT_MS);
                mVolumeLastTapTime = now;
                mVolumeLastTapKeyCode = keyCode;
                break;
            case RUN_DOUBLE_ACTION:
                // Cancel the first tap's pending step: a double press never
                // changes the volume, it only runs the configured action.
                cancelPendingSlowPress();
                clearVolumeTapHistory();
                performVolumeAction(readVolumeAction(GESTURE_DOUBLE));
                break;
            case SUPPRESS_AFTER_LONG_PRESS:
                cancelPendingSlowPress();
                clearVolumeTapHistory();
                break;
            case PASSTHROUGH:
            default:
                break;
        }
    }

    /**
     * Applies the single volume step that Android would have applied, because
     * the key event was consumed. Only a confirmed slow press reaches this
     * method: long press and double press run their action instead and never
     * change the volume.
     */
    private void adjustSystemVolume(int keyCode) {
        if (mAudioManager == null
                || (keyCode != KeyEvent.KEYCODE_VOLUME_UP
                    && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN)) {
            return;
        }
        final int direction = keyCode == KeyEvent.KEYCODE_VOLUME_UP
                ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER;
        final int mode = mAudioManager.getMode();
        final boolean inCall = mode == AudioManager.MODE_IN_CALL
                || mode == AudioManager.MODE_IN_COMMUNICATION;
        final VolumeKeyGesturePolicy.VolumeStream stream =
                VolumeKeyGesturePolicy.volumeStream(inCall);
        final int streamType =
                stream == VolumeKeyGesturePolicy.VolumeStream.VOICE_CALL
                        ? AudioManager.STREAM_VOICE_CALL
                        : AudioManager.STREAM_MUSIC;
        try {
            mAudioManager.adjustStreamVolume(streamType, direction,
                    AudioManager.FLAG_SHOW_UI);
        } catch (SecurityException e) {
            Log.w("BrailleBack", "adjustStreamVolume denied: " + e);
        }
    }

    private void cancelPendingSlowPress() {
        mVolumeKeyHandler.removeCallbacks(mSlowPressRunnable);
        mPendingSlowPressKeyCode = 0;
    }

    private void clearVolumeTapHistory() {
        mVolumeLastTapTime = 0;
        mVolumeLastTapKeyCode = 0;
    }

    private void clearVolumeGestureState() {
        mVolumeKeyHandler.removeCallbacks(mVolumeLongPressRunnable);
        cancelPendingSlowPress();
        mVolumeLongPressFired = false;
        clearVolumeTapHistory();
    }

    /**
     * Whether the service should take ownership of the volume keys. This is
     * also the escape hatch: switching the preference off (or setting every
     * slot to "none") restores stock Android behaviour instantly, including
     * long-press auto-repeat.
     */
    private boolean hasVolumeGestures() {
        final SharedPreferences prefs =
                PreferenceManager.getDefaultSharedPreferences(this);
        if (!prefs.getBoolean(getString(R.string.pref_volume_gestures_enabled_key),
                getResources().getBoolean(
                        R.bool.pref_volume_gestures_enabled_default))) {
            return false;
        }
        return !isNoneVolumeAction(readVolumeAction(GESTURE_LONG_UP))
                || !isNoneVolumeAction(readVolumeAction(GESTURE_LONG_DOWN))
                || !isNoneVolumeAction(readVolumeAction(GESTURE_DOUBLE));
    }

    private String readVolumeAction(int gesture) {
        final String key;
        switch (gesture) {
            case GESTURE_LONG_UP:
                key = getString(R.string.pref_volume_long_press_up_key);
                break;
            case GESTURE_LONG_DOWN:
                key = getString(R.string.pref_volume_long_press_down_key);
                break;
            case GESTURE_DOUBLE:
                key = getString(R.string.pref_volume_double_press_key);
                break;
            default:
                return getString(R.string.pref_volume_action_none_value);
        }
        return PreferenceManager.getDefaultSharedPreferences(this)
                .getString(key, getString(R.string.pref_volume_action_none_value));
    }

    private boolean isNoneVolumeAction(String action) {
        return getString(R.string.pref_volume_action_none_value).equals(action);
    }

    private void performVolumeAction(String action) {
        if (displayManager == null || feedbackManager == null) {
            return;
        }
        if (getString(R.string.pref_volume_action_pause_value).equals(action)) {
            final boolean paused = displayManager.toggleFrozen();
            feedbackManager.emitFeedback(paused
                    ? FeedbackManager.TYPE_NAVIGATE_INTO_HIERARCHY
                    : FeedbackManager.TYPE_NAVIGATE_OUT_OF_HIERARCHY);
        } else if (getString(R.string.pref_volume_action_grade_value)
                .equals(action)) {
            final String contractedKey =
                    getString(R.string.pref_braille_contracted_mode_key);
            final SharedPreferences prefs =
                    PreferenceManager.getDefaultSharedPreferences(this);
            final boolean contracted = prefs.getBoolean(contractedKey, false);
            prefs.edit().putBoolean(contractedKey, !contracted).apply();
            feedbackManager.emitFeedback(!contracted
                    ? FeedbackManager.TYPE_GRADE_8_DOT
                    : FeedbackManager.TYPE_GRADE_6_DOT);
        } else if (getString(R.string.pref_volume_action_bluetooth_value)
                .equals(action)) {
            final boolean wasConnected = displayManager.isDisplayConnected();
            displayManager.toggleBluetoothConnection();
            feedbackManager.emitFeedback(wasConnected
                    ? FeedbackManager.TYPE_DISPLAY_DISCONNECTED
                    : FeedbackManager.TYPE_DISPLAY_CONNECTED);
        }
    }

    private long parseIdleClearMinutes(String value) {
        try {
            int minutes = Integer.parseInt(value);
            return minutes <= 0 ? 0 : minutes * 60L * 1000L;
        } catch (NumberFormatException e) {
            return 3 * 60L * 1000L;
        }
    }

    private static final String FGS_CHANNEL_ID = "braille_display_connection";
    private static final int FGS_NOTIFICATION_ID = 1;

    /**
     * Promotes this accessibility service to a foreground service with a
     * persistent, low-importance notification.  Uses the manifest-declared
     * {@code connectedDevice} type on API 29+ (required subset check passes
     * because FOREGROUND_SERVICE_TYPE_MANIFEST resolves to the manifest
     * flags).  Callers must tolerate failure: on Android 14+ the platform
     * validates the connectedDevice prerequisites (e.g. BLUETOOTH_CONNECT)
     * and throws if they are unmet.
     */
    private void promoteToForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    FGS_CHANNEL_ID,
                    getString(R.string.braille_display_notif_channel),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(
                    getString(R.string.braille_display_notif_title));
            getSystemService(NotificationManager.class)
                    .createNotificationChannel(channel);
        }
        Notification.Builder builder =
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        ? new Notification.Builder(this, FGS_CHANNEL_ID)
                        : new Notification.Builder(this);
        Notification notification = builder
                .setContentTitle(getString(R.string.braille_display_notif_title))
                .setContentText(getString(R.string.braille_display_notif_text))
                .setSmallIcon(R.drawable.ic_brailleback)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(FGS_NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST);
        } else {
            startForeground(FGS_NOTIFICATION_ID, notification);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // 文件日志要在任何可能抛异常的重活之前就绪：onServiceConnected 里的
        // 前台提升 / BrailleDisplayController / HidBridge 一旦抛错，日志通道
        // 不能跟着一起丢，否则用户点“清除日志/分享日志”只会看到上一次进程的旧文件。
        PreferenceUtils.initLogLevel(this);
        LogFileWriter.init(this);
    }

    @Override

    public void onServiceConnected() {
    instance = this;
    // Required to put the slow-press volume step back after consuming the key.
    mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
    // Promote to a foreground service so the braille display connection
    // survives idle pruning and aggressive OEM battery managers.  Best
    // effort only: if the platform rejects the promotion (e.g. missing
    // BLUETOOTH_CONNECT grant for the connectedDevice type on Android 14+)
    // the service still runs, just with normal background priority.
    try {
      promoteToForeground();
    } catch (RuntimeException e) {
      Log.w("BrailleBack", "Foreground promotion failed: " + e);
    }
    // Hand the BrailleDisplayController over to the display service
    // so it can drive Braille HID standard (usage page 0x41) displays.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
      // BrailleDisplayController is flag-gated on Android 15 and guaranteed
      // only on Android 16+.  When the platform flag is off the call throws;
      // fall back to classic RFCOMM instead of crashing the service.
      android.accessibilityservice.BrailleDisplayController bdc = null;
      try {
        bdc = getBrailleDisplayController();
      } catch (RuntimeException e) {
        Log.w("BrailleBack", "BrailleDisplayController unavailable: " + e);
      }
      Log.i("BrailleBack", "onServiceConnected pid=" + android.os.Process.myPid()
          + " BrailleDisplayController=" + bdc);
      // Publish with this instance as owner: a rebind can create this service
      // again before the previous instance is destroyed, and the stale
      // onDestroy() must not clear the fresh controller.
      Log.i("BrailleBack", "Publishing BrailleDisplayController=" + bdc
          + " sdk=" + Build.VERSION.SDK_INT);
      HidBridge.setController(this, bdc);
    }
        PreferenceManager.getDefaultSharedPreferences(this)
                .registerOnSharedPreferenceChangeListener(mPreferenceListener);
        PreferenceUtils.initLogLevel(this);
        LogFileWriter.init(this);
        // 触摸探索透传探针（实验性临时代码，见 PassthroughProbe）。
        // 只注册广播监听，不自动生效；收到 adb 广播后才置 flag 与区域，并会在 60 秒后自行撤销。
        PassthroughProbe.install(this);
        if (isInfrastructureInitialized) {
            return;
        }

    handler.sendEmptyMessage(WHAT_START);
        // We are in an initialized state now.
        isInfrastructureInitialized = true;

    // 把宿主能力（语音、音效、焦点、全局动作）注入移植过来的屏幕盲文键盘。
    // 注入失败绝不能影响点显器主链路，因此整体包在 try/catch 里。
    try {
      brailleImeHostBridge = new com.googlecode.eyesfree.brailleback.ime.BrailleImeHostBridge(this);
      com.google.android.accessibility.brailleime.BrailleIme.initialize(
          this,
          brailleImeHostBridge,
          brailleImeHostBridge,
          /* brailleDisplayForBrailleIme= */ null);
      Log.i("BrailleBack", "BrailleIme(port) initialized");
    } catch (RuntimeException | LinkageError e) {
      Log.w("BrailleBack", "BrailleIme(port) init failed: " + e);
    }
    }

    @Override
    public void onDestroy() {
    instance = null;
    // Do not let a queued WHAT_START re-initialize a destroyed service.
    handler.removeCallbacksAndMessages(null);
    mVolumeKeyHandler.removeCallbacksAndMessages(null);
    // Leave the foreground state explicitly; the notification is removed.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      stopForeground(STOP_FOREGROUND_REMOVE);
    } else {
      stopForeground(true);
    }
    // Drop the static BrailleDisplayController reference held by HidBridge.
    // It must be cleared before shutdownDependencies(): otherwise the static
    // outlives this service, HidBridge.isAvailable() keeps returning true, and
    // DisplayService may try to connect using a controller bound to a dead
    // service.  Cleared early so the shutdown path sees no stale controller.
    // Ownership is checked: when this instance has already been superseded,
    // the newer controller stays published.
    HidBridge.setController(this, null);
        PreferenceManager.getDefaultSharedPreferences(this)
                .unregisterOnSharedPreferenceChangeListener(mPreferenceListener);
        // 服务销毁前必须撤销透传，否则设备会停留在"读屏摸不动"的状态。
        PassthroughProbe.uninstall(this);
        super.onDestroy();
    if (displayManager != null) {
      displayManager.setContent(new DisplayManager.Content(getString(R.string.shutting_down)));

            // We are not in an initialized state anymore.
            isInfrastructureInitialized = false;
        }
        // 释放移植键盘持有的 TTS / SoundPool，避免重复绑定后泄漏。
        if (brailleImeHostBridge != null) {
            // 先撤掉触摸探索直通区，避免服务被系统回收后整屏仍处于直通状态（读屏会"摸不动"）。
            brailleImeHostBridge.forceClearPassthrough();
            brailleImeHostBridge.shutdown();
            brailleImeHostBridge = null;
        }
        shutdownDependencies();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // A full event dump is several hundred bytes and the framework emits
        // dozens of events per second. Writing all of them buried every other
        // record and rotated the log file within minutes, so the compact
        // summary is what a normal (DEBUG) log keeps; the complete dump is
        // reserved for VERBOSE.
        if (LogUtils.shouldLog(Log.DEBUG)) {
            LogUtils.log(this, Log.DEBUG, "Event: %s", describeEvent(event));
        }
        if (LogUtils.shouldLog(Log.VERBOSE)) {
            LogUtils.log(this, Log.VERBOSE, "Event detail: %s", event.toString());
            LogUtils.log(this, Log.VERBOSE, "Node detail: %s", event.getSource());
        }
    if (modeSwitcher != null) {
      modeSwitcher.onObserveAccessibilityEvent(event);
      modeSwitcher.onAccessibilityEvent(event);
        }
    if (labelManager != null) {
      labelManager.onAccessibilityEvent(event);
        }
    }

    /** Upper bound on the characters kept for one field of the summary. */
    private static final int EVENT_TEXT_LIMIT = 120;

    /** Builds the single-line summary logged for every accessibility event. */
    private static String describeEvent(AccessibilityEvent event) {
        StringBuilder summary = new StringBuilder(96);
        summary.append(AccessibilityEvent.eventTypeToString(event.getEventType()));
        appendEventField(summary, "pkg", event.getPackageName());
        appendEventField(summary, "cls", event.getClassName());
        appendEventField(summary, "text", joinEventText(event.getText()));
        appendEventField(summary, "desc", event.getContentDescription());
        return summary.toString();
    }

    private static void appendEventField(
            StringBuilder summary, String name, CharSequence value) {
        if (TextUtils.isEmpty(value)) {
            return;
        }
        summary.append(' ').append(name).append('=').append(clipEventText(value));
    }

    private static String joinEventText(List<CharSequence> parts) {
        if (parts == null || parts.isEmpty()) {
            return null;
        }
        StringBuilder joined = new StringBuilder();
        for (CharSequence part : parts) {
            if (TextUtils.isEmpty(part)) {
                continue;
            }
            if (joined.length() > 0) {
                joined.append(" | ");
            }
            joined.append(part);
        }
        return joined.length() == 0 ? null : joined.toString();
    }

    private static String clipEventText(CharSequence value) {
        String text = value.toString().replace('\n', ' ').replace('\r', ' ').trim();
        if (text.length() <= EVENT_TEXT_LIMIT) {
            return text;
        }
        return text.substring(0, EVENT_TEXT_LIMIT) + "...";
    }

    @Override
    public void onInterrupt() {
        // Nothing to interrupt.
    }

    @Override
    public void onConfigurationChanged(Configuration newConfiguration) {
    if (translatorManager != null) {
      translatorManager.onConfigurationChanged(newConfiguration);
        }
    }

    @Override
    public void onSearchStarted() {
        // Nothing to do here.
    }

    @Override
    public void onSearchFinished() {
    modeSwitcher.overrideMode(null);
    }

    @Override
    public void onMenuClosed() {
    modeSwitcher.overrideMode(null);
    }

    /**
     * Starts search mode in BrailleBack. Optionally tries to start the tutorial
     * first.
     */
    public void startSearchMode() {
    modeSwitcher.overrideMode(searchNavigationMode);
    }

    private void startSearchWithTutorial() {
    searchNavigationMode.setInitialNodeToCurrent();
        // Try to start the search tutorial activity first. Only if it
        // doesn't start do we activate search mode.
        if (SearchTutorialActivity.tryStartActivity(this)) {
            return;
        }
        startSearchMode();
    }

    private void initializeDependencies() {
        // Must initialize label manager before navigation modes.
        initializeLabelManager();
    feedbackManager = new FeedbackManager(this);
    translatorManager = new TranslatorManager(this);
    selfBrailleManager = new SelfBrailleManager();
    ruleRepository = new BrailleRuleRepository(this);
    nodeBrailler = new NodeBrailler(this, ruleRepository, selfBrailleManager);
        initializeDisplayManager();
        initializeNavigationMode();
    imeHelper = new IMEHelper(this);
    focusTracker = new FocusTracker(this);
    focusTracker.register();
    }

    private void updateServiceInfo() {
        // Publicize service meta-data stating that this service does braille. Would be done in
        // accessibility service config if it supported accessibilityFeedbackType FEEDBACK_BRAILLE.
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) {
          LogUtils.log(this, Log.ERROR, "getServiceInfo() returned null");
          return;
        }
        info.feedbackType |= AccessibilityServiceInfo.FEEDBACK_BRAILLE;
        info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        setServiceInfo(info);
    }

    private void initializeLabelManager() {
        if (Build.VERSION.SDK_INT >= CustomLabelManager.MIN_API_LEVEL) {
            try {
        labelManager = new CustomLabelManager(this);
            } catch (SecurityException e) {
                // Don't use labeling features if there's a permission denial
                // due to a key mismatch
                LogUtils.log(this, Log.ERROR,
                        "Not using labeling due to permission denial.");
            }

      if (labelManager != null) {
        packageReceiver = new PackageRemovalReceiver();
        ContextCompat.registerReceiver(this, packageReceiver,
            packageReceiver.getFilter(), ContextCompat.RECEIVER_NOT_EXPORTED);
        labelManager.ensureDataConsistency();
            }
        }
    }

    private void initializeDisplayManager() {
    displayManager =
        new DisplayManager(
            translatorManager,
            this /*context*/,
            this /*panOverflowListener*/,
            this /*connectionStateChangeListener*/,
            this /*inputEventListener*/);
    displayManager.setContent(new DisplayManager.Content(getString(R.string.display_connected)));
    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
    displayManager.setMotionMode(prefs.getBoolean(
            getString(R.string.pref_motion_mode_key), false));
    displayManager.setPauseClear(
            getString(R.string.pref_pause_behavior_clear_value)
                    .equals(prefs.getString(
                            getString(R.string.pref_pause_behavior_key), "")));
    displayManager.setIdleClearTimeoutMs(parseIdleClearMinutes(
            prefs.getString(getString(R.string.pref_idle_clear_key), "3")));
    displayManager.setCursorDotsEnabled(prefs.getBoolean(
            getString(R.string.pref_cursor_dots_key), false));
    }

    private void initializeNavigationMode() {
    DefaultNavigationMode defaultNavigationMode =
        new DefaultNavigationMode(
            displayManager,
            this,
            feedbackManager,
            selfBrailleManager,
            nodeBrailler,
            ruleRepository);
    IMENavigationMode newImeNavMode =
        new IMENavigationMode(
            defaultNavigationMode,
            this,
            displayManager,
            feedbackManager,
            selfBrailleManager,
            translatorManager);
    imeNavigationMode = newImeNavMode;
    BrailleIME.setSingletonHost(newImeNavMode);
    modeSwitcher =
        new ModeSwitcher(
            newImeNavMode, new TreeDebugNavigationMode(displayManager, feedbackManager, this));
    modeSwitcher.onActivate();

    // Create separate SearchNavigationMode.
    searchNavigationMode =
        new SearchNavigationMode(
            displayManager,
            this /*accessibilityService*/,
            feedbackManager,
            translatorManager,
            selfBrailleManager,
            nodeBrailler,
            this /*searchStateListener*/,
            labelManager);

    // Create separate BrailleMenuNavigationMode.
    brailleMenuNavigationMode =
        new BrailleMenuNavigationMode(
            displayManager,
            this /*accessibilityService*/,
            feedbackManager,
            labelManager,
            this /*brailleMenuListener*/);
    }

    private void shutdownDependencies() {
    if (displayManager != null) {
      displayManager.shutdown();
      displayManager = null;
        }
    if (translatorManager != null) {
      translatorManager.shutdown();
      translatorManager = null;
        }
    if (feedbackManager != null) {
      // Releases the TTS/audio/MIDI resources held by FeedbackController.
      // Deliberately NOT nulling the reference: feedbackManager is used by
      // callbacks such as onConnectionStateChanged()/onMappedInputEvent(),
      // and nulling it could turn a leak into an NPE.  shutdown() is what
      // actually frees the resources.
      feedbackManager.shutdown();
    }
    if (focusTracker != null) {
      focusTracker.unregister();
      focusTracker = null;
        }
    if (labelManager != null) {
      labelManager.shutdown();
      labelManager = null;
        }
    if (packageReceiver != null) {
      unregisterReceiver(packageReceiver);
      packageReceiver = null;
        }

        BrailleIME.setSingletonHost(null);
    }

    public boolean runHelp() {
        Intent intent = new Intent(this, KeyBindingsActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(intent);
        return true;
    }

    public static BrailleBackService getActiveInstance() {
    return instance;
    }

    /**
     * 单注册 IME 用它判断本次会话该用「点显器硬件」还是「触屏点字」形态。
     * 服务未启动或点显器尚未连接时返回 false（走触屏键盘），不做任何推测。
     */
    public static boolean isBrailleDisplayConnected() {
      BrailleBackService service = instance;
      return service != null
          && service.displayManager != null
          && service.displayManager.isDisplayConnected();
    }

    public CustomLabelManager getLabelManager() {
    return labelManager;
    }

    /**
     * Signal that this node, or one of its descendants, has been changed in a
     * way that could affect how it is displayed.  If the node is contributing
     * to the content of the display, the display content will be updated
     * accordingly.
     */
    public void invalidateNode(AccessibilityNodeInfo node) {
        AccessibilityNodeInfoCompat wrapped =
                new AccessibilityNodeInfoCompat(node);
    modeSwitcher.onInvalidateAccessibilityNode(wrapped);
    }
}


