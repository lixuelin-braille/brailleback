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

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import com.googlecode.eyesfree.braille.display.BrailleInputEvent

/**
 * 点显器命令与导航溢出分发：从 [BrailleBackService] 拆出的命令路由层。
 *
 * 负责全局动作拦截、导航模式转交、搜索模式入口、输入法事件与盲文表级切换；
 * 不持有初始化链路与前后台保活逻辑。写成同包 internal 扩展函数，方法体与
 * 拆分前逐字一致；被扩展方需要访问的依赖引用在 [BrailleBackService] 里由
 * private 放宽为 internal。
 */

internal fun BrailleBackService.dispatchMappedInputEvent(event: BrailleInputEvent,
        content: Content) {
    // 未初始化时直接忽略；后面全部用这份局部快照。
    val switcher = modeSwitcher ?: return
    // Global commands can't be overriden.
    if (handleGlobalCommands(event)) {
        return
    }
    if (BuildConfig.DEBUG
            && event.command == BrailleInputEvent.CMD_BRAILLE_KEY
            && event.argument == BrailleBackService.SWITCH_NAVIGATION_MODE_DOTS) {
        switcher.switchMode()
        return
    }
    if (event.command == BrailleInputEvent.CMD_TOGGLE_BRAILLE_MENU) {
        if (!BrailleBackService.LABELING_ENABLED) {
            return
        }
        // 菜单模式可能还没建起来（LABELING 关闭时），活跃与否都用同一份引用。
        val menuMode = brailleMenuNavigationMode
        if (menuMode?.isActive() == true) {
            switcher.overrideMode(null)
        } else {
            switcher.overrideMode(menuMode)
        }
        return
    }
    if (switcher.onMappedInputEvent(event, content)) {
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
    if (imeHelper?.onInputEvent(event) == true) {
        return
    }
    if (event.command == BrailleInputEvent.CMD_TOGGLE_BRAILLE_GRADE) {
        // 统一盲文表后，级切换直接在所选语言的六点与八点表之间切换。
        val translator = translatorManager ?: return
        val eightDot = translator.toggleBrailleDots()
        feedbackManager?.emitFeedback(
            if (eightDot) FeedbackManager.TYPE_GRADE_8_DOT
            else FeedbackManager.TYPE_GRADE_6_DOT)

        // If showing preference screen... update displayed preference.
        val refreshPrefIntent =
            Intent(SettingsActivity.INTENT_REFRESH_DISPLAY)
        refreshPrefIntent.setPackage(packageName)
        sendBroadcast(refreshPrefIntent)

        return
    }
    feedbackManager?.emitFeedback(FeedbackManager.TYPE_UNKNOWN_COMMAND)
}

internal fun BrailleBackService.handleGlobalCommands(event: BrailleInputEvent): Boolean {
    val success: Boolean
    when (event.command) {
        BrailleInputEvent.CMD_GLOBAL_HOME -> {
            success = performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        }
        BrailleInputEvent.CMD_GLOBAL_BACK -> {
            success = performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        }
        BrailleInputEvent.CMD_GLOBAL_RECENTS -> {
            success = performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
        }
        BrailleInputEvent.CMD_GLOBAL_NOTIFICATIONS -> {
            success = performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
        }
        BrailleInputEvent.CMD_HELP -> {
            success = runHelp()
        }
        else -> {
            return false
        }
    }
    if (!success) {
        feedbackManager?.emitFeedback(FeedbackManager.TYPE_COMMAND_FAILED)
    }
    // Don't fall through even if the command failed, we own these
    // commands.
    return true
}

internal fun BrailleBackService.dispatchPanLeftOverflow(content: Content) {
    // 未初始化时没有可用的导航模式，直接当作「到边界」处理。
    val switcher = modeSwitcher ?: return
    if (!switcher.onPanLeftOverflow(content)) {
        feedbackManager?.emitFeedback(FeedbackManager.TYPE_NAVIGATE_OUT_OF_BOUNDS)
    }
}

internal fun BrailleBackService.dispatchPanRightOverflow(content: Content) {
    // 同上：没有可用的导航模式就当作已到边界。
    val switcher = modeSwitcher ?: return
    if (!switcher.onPanRightOverflow(content)) {
        feedbackManager?.emitFeedback(FeedbackManager.TYPE_NAVIGATE_OUT_OF_BOUNDS)
    }
}

internal fun BrailleBackService.startSearchWithTutorial() {
    searchNavigationMode?.setInitialNodeToCurrent()
    // Try to start the search tutorial activity first. Only if it
    // doesn't start do we activate search mode.
    if (SearchTutorialActivity.tryStartActivity(this)) {
        return
    }
    startSearchMode()
}
