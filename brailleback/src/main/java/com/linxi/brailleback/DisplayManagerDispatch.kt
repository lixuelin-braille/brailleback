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

import android.annotation.SuppressLint
import com.googlecode.eyesfree.braille.display.BrailleInputEvent

/**
 * 按键分发中的平移与坐标换算、空闲清屏与唤醒：从 [DisplayManager] 拆出的分发层。
 *
 * 这一层只把硬件输入事件换算到当前平移位置、走主线程回调，以及维护空闲清屏
 * 定时器与唤醒锁；整屏刷新链路与写入通道仍留在管理器本体。写成同包 internal
 * 扩展函数，方法体与拆分前逐字一致。
 */

internal fun DisplayManager.sendMappedEvent(event: BrailleInputEvent) {
    var mappedEvent = event
    if (BrailleInputEvent.argumentType(mappedEvent.command)
            == BrailleInputEvent.ARGUMENT_POSITION) {
        val oldArgument = mappedEvent.argument
        // Offset argument by pan position and make sure it is less than
        // the next split position.
        val offsetArgument = oldArgument + wrapStrategy.getDisplayStart()
        if (offsetArgument >= wrapStrategy.getDisplayEnd()) {
            // The event is outisde the currently displayed
            // content, drop the event.
            return
        }
        // The mapped event argument is the translated offset argument.
        val newArgument =
            displayToTextPosition(
                currentTranslationResult, cursorPositionToTranslate, offsetArgument)
        // Create a new event if the argument actually differs.
        if (newArgument != oldArgument) {
            mappedEvent = BrailleInputEvent(mappedEvent.command,
                    newArgument, mappedEvent.eventTime)
        }
    }
    callbackHandler.onMappedInputEvent(mappedEvent)
}

internal fun DisplayManager.panLeft() {
    if (wrapStrategy.panLeft()) {
        updateDisplayedContent()
    } else {
        callbackHandler.onPanLeftOverflow()
    }
}

internal fun DisplayManager.panRight() {
    if (wrapStrategy.panRight()) {
        updateDisplayedContent()
    } else {
        callbackHandler.onPanRightOverflow()
    }
}

/** Resets the idle-clearing timer; call on any display activity. */
internal fun DisplayManager.resetIdleTimer() {
    // 每次显示活动都重置：安静满 mIdleClearTimeoutMs 才清屏，之后按同一间隔继续清。
    idleClearTimer.restart(mIdleClearTimeoutMs) { doSendEmptyCommand() }
}

/** Sends an all-blank dot pattern. Runs on the display handler thread. */
internal fun DisplayManager.doSendEmptyCommand() {
    val currentDisplay = display ?: return
    if (!connected) {
        return
    }
    val props = currentDisplay.displayProperties
    val cells = props?.numTextCells ?: 0
    if (cells <= 0) {
        return
    }
    currentDisplay.displayDots(ByteArray(cells), "", null)
}

/**
 * Keeps the phone awake as if there was a 'user activity' registered
 * by the system.
 */
@SuppressLint("WakelockTimeout")
internal fun DisplayManager.keepAwake() {
    // Acquiring the lock and immediately releasing it keesp the phone
    // awake.  We don't use aqcuire() with a timeout because it just
    // adds an unnecessary context switch.
    wakeLock.acquire()
    wakeLock.release()
}
