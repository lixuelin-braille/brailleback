/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
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

import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import androidx.core.content.ContextCompat
import com.googlecode.eyesfree.labeling.CustomLabelManager
import com.googlecode.eyesfree.labeling.PackageRemovalReceiver
import com.googlecode.eyesfree.utils.LogUtils
import com.googlecode.eyesfree.utils.SettingsStore
import com.linxi.brailleback.rule.BrailleRuleRepository

/**
 * 依赖装配与拆解：从 [BrailleBackService] 拆出的初始化链路层。
 *
 * 负责反馈/翻译/规则/点显/导航等协作对象的构造、服务信息回填与退出时的
 * 有序释放；无障碍回调、音量手势与前后台保活仍留在服务本体。写成同包
 * internal 扩展函数，方法体与拆分前逐字一致；被扩展方需要赋值的依赖引用
 * 在 [BrailleBackService] 里由 private 放宽为 internal。
 */

internal fun BrailleBackService.parseIdleClearMinutes(value: String): Long {
    try {
        val minutes = Integer.parseInt(value)
        return if (minutes <= 0) 0L else minutes * 60L * 1000L
    } catch (e: NumberFormatException) {
        return 3 * 60L * 1000L
    }
}

internal fun BrailleBackService.initializeDependencies() {
    // Must initialize label manager before navigation modes.
    initializeLabelManager()
    // 全部先落局部再赋给属性：构建 NodeBrailler 时直接用这两份引用，
    // 不必在构造函数参数里塞断言（属性本身不参与智能转换）。
    feedbackManager = FeedbackManager(this)
    translatorManager = TranslatorManager(this)
    val selfBraille = SelfBrailleManager()
    selfBrailleManager = selfBraille
    val rules = BrailleRuleRepository(this)
    ruleRepository = rules
    nodeBrailler = NodeBrailler(this, rules, selfBraille)
    initializeDisplayManager()
    initializeNavigationMode()
    imeHelper = IMEHelper(this)
    val tracker = FocusTracker(this)
    focusTracker = tracker
    tracker.register()
}

internal fun BrailleBackService.updateServiceInfo() {
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

internal fun BrailleBackService.initializeLabelManager() {
    try {
        labelManager = CustomLabelManager(this)
    } catch (e: SecurityException) {
        // Don't use labeling features if there's a permission denial
        // due to a key mismatch
        LogUtils.log(this, Log.ERROR,
                "Not using labeling due to permission denial.")
    }

    // CustomLabelManager 可能因签名不匹配而没建起来：下面三行都用同一份局部引用。
    val labels = labelManager
    if (labels != null) {
        val receiver = PackageRemovalReceiver()
        packageReceiver = receiver
        ContextCompat.registerReceiver(this, receiver,
            receiver.getFilter(), ContextCompat.RECEIVER_NOT_EXPORTED)
        labels.ensureDataConsistency()
    }
}

internal fun BrailleBackService.initializeDisplayManager() {
    // 依赖在 initializeDependencies 里已建好；取局部后构造与后续设置用同一份引用。
    val translator = requireNotNull(translatorManager) {
        "initializeDisplayManager 早于 translatorManager 初始化"
    }
    val dm = DisplayManager(
            translator,
            this /*context*/,
            this /*panOverflowListener*/,
            this /*connectionStateChangeListener*/,
            this /*inputEventListener*/)
    displayManager = dm
    dm.setContent(Content(getString(R.string.display_connected)))
    val prefs = SettingsStore.get(this)
    applyMotionModeDefault(prefs)
    dm.setPauseClear(
            getString(R.string.pref_pause_behavior_clear_value)
                    .equals(prefs.getString(
                            getString(R.string.pref_pause_behavior_key), "")))
    dm.setIdleClearTimeoutMs(parseIdleClearMinutes(
            prefs.getString(getString(R.string.pref_idle_clear_key), "3") ?: "3"))
    dm.setCursorDotsEnabled(prefs.getBoolean(
            getString(R.string.pref_cursor_dots_key), false))
}

internal fun BrailleBackService.initializeNavigationMode() {
    // 这一步的依赖都刚在 initializeDependencies 里建好：取局部后整段构造不再需要断言。
    val dm = requireNotNull(displayManager) { "displayManager 尚未初始化" }
    val feedback = requireNotNull(feedbackManager) { "feedbackManager 尚未初始化" }
    val selfBraille = requireNotNull(selfBrailleManager) { "selfBrailleManager 尚未初始化" }
    val brailler = requireNotNull(nodeBrailler) { "nodeBrailler 尚未初始化" }
    val rules = requireNotNull(ruleRepository) { "ruleRepository 尚未初始化" }
    val translator = requireNotNull(translatorManager) { "translatorManager 尚未初始化" }

    val defaultNavigationMode =
        DefaultNavigationMode(dm, this, feedback, selfBraille, brailler, rules)
    val newImeNavMode =
        IMENavigationMode(defaultNavigationMode, this, dm, feedback, selfBraille, translator)
    imeNavigationMode = newImeNavMode
    BrailleIME.setSingletonHost(newImeNavMode)
    val switcher = ModeSwitcher(
        newImeNavMode, TreeDebugNavigationMode(dm, feedback, this))
    modeSwitcher = switcher
    switcher.onActivate()

    // Create separate SearchNavigationMode.
    searchNavigationMode =
        SearchNavigationMode(
            dm,
            this /*accessibilityService*/,
            feedback,
            translator,
            selfBraille,
            brailler,
            this /*searchStateListener*/,
            labelManager)

    // Create separate BrailleMenuNavigationMode.
    brailleMenuNavigationMode =
        BrailleMenuNavigationMode(
            dm,
            this /*accessibilityService*/,
            feedback,
            requireNotNull(labelManager) { "labelManager 尚未初始化" },
            this /*brailleMenuListener*/)
}

internal fun BrailleBackService.shutdownDependencies() {
    // 先摘引用再关闭：关闭过程中若回调重入，也不会再拿到半关闭的对象。
    val dm = displayManager
    displayManager = null
    dm?.shutdown()

    val translator = translatorManager
    translatorManager = null
    translator?.shutdown()
    // Releases the TTS/audio/MIDI resources held by FeedbackController.
    // Deliberately NOT nulling the reference: feedbackManager is used by
    // callbacks such as onConnectionStateChanged()/onMappedInputEvent(),
    // and nulling it could turn a leak into an NPE.  shutdown() is what
    // actually frees the resources.
    feedbackManager?.shutdown()
    val tracker = focusTracker
    focusTracker = null
    tracker?.unregister()

    val labels = labelManager
    labelManager = null
    labels?.shutdown()

    val receiver = packageReceiver
    packageReceiver = null
    receiver?.let { unregisterReceiver(it) }

    BrailleIME.setSingletonHost(null)
}
