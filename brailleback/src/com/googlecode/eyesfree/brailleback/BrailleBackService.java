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
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.preference.PreferenceManager;
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
import com.googlecode.eyesfree.labeling.CustomLabelManager;
import com.googlecode.eyesfree.labeling.PackageRemovalReceiver;
import com.googlecode.eyesfree.utils.LogUtils;

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
  protected IMENavigationMode imeNavigationMode;
  private CustomLabelManager labelManager;
  private PackageRemovalReceiver packageReceiver;

    /** Set if the infrastructure is initialized. */
    private boolean isInfrastructureInitialized;

    /**
     * Dot combination for switching navigation mode.
     * At this time, this is only used for debugging.
     */
    private static final int SWITCH_NAVIGATION_MODE_DOTS = DOT7 | DOT8;

  /** {@link Handler} for executing messages on the service main thread. */
  private final Handler handler =
      new Handler() {
        @Override
        public void handleMessage(Message message) {
          switch (message.what) {
            case WHAT_START:
              updateServiceInfo();
              initializeDependencies();
              return;
            default: // fall out
          }
        }
      };

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
            // Read current grade preference.
            Context context = this;
            SharedPreferences sharedPreferences =
                    PreferenceManager.getDefaultSharedPreferences(context);
            final String prefKey = context.getString(R.string.pref_braille_type_key);
            final String prefValueDefault = "";
            final String prefValueOld = sharedPreferences.getString(prefKey, prefValueDefault);
            final String prefValue6Dot =
                    context.getString(R.string.pref_braille_type_six_dot_value);
            final String prefValue8Dot =
                    context.getString(R.string.pref_braille_type_eight_dot_value);

            // Toggle and store new computer/literary grade preference.
            final String prefValueNew =
                    TextUtils.equals(prefValueOld, prefValue6Dot) ? prefValue8Dot : prefValue6Dot;
            SharedPreferences.Editor prefEditor = sharedPreferences.edit();
            prefEditor.putString(prefKey, prefValueNew);
            prefEditor.apply();

            // Play audio indicator of grade.
            final int audioId = TextUtils.equals(prefValueNew, prefValue6Dot)
                    ? FeedbackManager.TYPE_GRADE_6_DOT
                    : FeedbackManager.TYPE_GRADE_8_DOT;
      feedbackManager.emitFeedback(audioId);

            // If showing preference screen... update displayed preference.
            Intent refreshPrefIntent =
                new Intent(BrailleBackPreferencesActivity.INTENT_REFRESH_DISPLAY);
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
    private static final long VOLUME_LONG_PRESS_TIMEOUT_MS = 500;
    private static final long VOLUME_DOUBLE_PRESS_TIMEOUT_MS = 350;
    private static final int GESTURE_LONG_UP = 0;
    private static final int GESTURE_LONG_DOWN = 1;
    private static final int GESTURE_DOUBLE = 2;

    private final Handler mVolumeKeyHandler = new Handler();
    private long mVolumeLastTapTime = 0;
    private int mVolumeLongPressKeyCode = 0;
    private boolean mVolumeLongPressFired = false;

    private final Runnable mVolumeLongPressRunnable = new Runnable() {
        @Override
        public void run() {
            mVolumeLongPressFired = true;
            performVolumeAction(readVolumeAction(
                    mVolumeLongPressKeyCode == KeyEvent.KEYCODE_VOLUME_UP
                            ? GESTURE_LONG_UP : GESTURE_LONG_DOWN));
        }
    };

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        final int keyCode = event.getKeyCode();
        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP
                && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false;
        }
        switch (event.getAction()) {
            case KeyEvent.ACTION_DOWN:
                return onVolumeKeyDown(keyCode);
            case KeyEvent.ACTION_UP:
                return onVolumeKeyUp(keyCode);
            default:
                return false;
        }
    }

    private boolean onVolumeKeyDown(int keyCode) {
        if (!hasVolumeGestures()) {
            return false;
        }
        mVolumeKeyHandler.removeCallbacks(mVolumeLongPressRunnable);
        mVolumeLongPressFired = false;
        mVolumeLongPressKeyCode = keyCode;
        mVolumeKeyHandler.postDelayed(
                mVolumeLongPressRunnable, VOLUME_LONG_PRESS_TIMEOUT_MS);
        return true;
    }

    private boolean onVolumeKeyUp(int keyCode) {
        if (!hasVolumeGestures()) {
            return false;
        }
        mVolumeKeyHandler.removeCallbacks(mVolumeLongPressRunnable);
        if (mVolumeLongPressFired) {
            // Long press was already handled.
            mVolumeLongPressFired = false;
            return true;
        }
        final long now = SystemClock.uptimeMillis();
        if (now - mVolumeLastTapTime < VOLUME_DOUBLE_PRESS_TIMEOUT_MS) {
            // Double press.
            mVolumeLastTapTime = 0;
            performVolumeAction(readVolumeAction(GESTURE_DOUBLE));
            return true;
        }
        mVolumeLastTapTime = now;
        return true;
    }

    private boolean hasVolumeGestures() {
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

    public void onServiceConnected() {
    instance = this;
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
      HidBridge.setController(bdc);
    }
        PreferenceManager.getDefaultSharedPreferences(this)
                .registerOnSharedPreferenceChangeListener(mPreferenceListener);
        PreferenceUtils.initLogLevel(this);
        if (isInfrastructureInitialized) {
            return;
        }

    handler.sendEmptyMessage(WHAT_START);
        // We are in an initialized state now.
        isInfrastructureInitialized = true;
    }

    @Override
    public void onDestroy() {
    instance = null;
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
    HidBridge.setController(null);
        PreferenceManager.getDefaultSharedPreferences(this)
                .unregisterOnSharedPreferenceChangeListener(mPreferenceListener);
        super.onDestroy();
    if (displayManager != null) {
      displayManager.setContent(new DisplayManager.Content(getString(R.string.shutting_down)));

            // We are not in an initialized state anymore.
            isInfrastructureInitialized = false;
        }
        shutdownDependencies();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        LogUtils.log(this, Log.VERBOSE, "Event: %s", event.toString());
        LogUtils.log(this, Log.VERBOSE, "Node: %s", event.getSource());
    if (modeSwitcher != null) {
      modeSwitcher.onObserveAccessibilityEvent(event);
      modeSwitcher.onAccessibilityEvent(event);
        }
    if (labelManager != null) {
      labelManager.onAccessibilityEvent(event);
        }
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
        if (Build.VERSION.SDK_INT >= 33) {
    registerReceiver(packageReceiver, packageReceiver.getFilter(),
            Context.RECEIVER_NOT_EXPORTED);
} else {
    registerReceiver(packageReceiver, packageReceiver.getFilter());
}
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


