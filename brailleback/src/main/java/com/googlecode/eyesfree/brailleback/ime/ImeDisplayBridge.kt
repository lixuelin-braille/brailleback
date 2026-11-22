/*
 * Copyright 2026 The BrailleBack Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.googlecode.eyesfree.brailleback.ime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.googlecode.eyesfree.braille.display.BrailleInputEvent
import com.googlecode.eyesfree.braille.display.Display
import com.googlecode.eyesfree.braille.display.DisplayClient
import com.googlecode.eyesfree.brailleback.BrailleIME

/**
 * 输入法自己的点显器连接：不开无障碍也能用点显器打字。
 *
 * 点显器连接本来就不在无障碍服务里，而在 `DisplayService`（前台服务 + `IBrailleService`
 * 绑定，仅本应用可连）。无障碍服务只是它的一个客户端。所以输入法单独运行时，可以自己再当
 * 一个客户端：拿到点位按键（`onInput`）、拿到显示宽度（`displayProperties`）、能直接写点。
 *
 * 关键约束：`DisplayService` 把输入事件**广播给所有客户端**（`broadcastInputEvent`），
 * 所以无障碍服务在跑时这套必须完全不启用，否则每个按键会被处理两次。是否启用由
 * [BrailleIME] 按「有没有宿主」决定，这里在收发事件时再做一次防御性判断。
 */
class ImeDisplayBridge(
    private val context: Context,
    /** 取当前输入法实例；取不到就说明没有活跃的输入会话，事件直接丢弃。 */
    private val imeProvider: () -> BrailleIME?,
    /** 宿主是否已经接管显示（无障碍服务在跑）；为真时本连接必须闭嘴。 */
    private val hostActive: () -> Boolean,
) : Display.OnInputEventListener, Display.OnConnectionStateChangeListener {

    private companion object {
        const val TAG = "ImeDisplayBridge"

        /** 点位回显保持多久后清屏；与 DisplayManager 的回显时长保持一致。 */
        const val ECHO_RESTORE_DELAY_MILLIS = 1500L
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var client: DisplayClient? = null
    private var connected = false
    private var cellCount = 0
    private var restoreTask: Runnable? = null

    fun start() {
        if (client != null) {
            return
        }
        val created = DisplayClient(context)
        created.setOnConnectionStateChangeListener(this)
        created.setOnInputEventListener(this)
        client = created
        Log.i(TAG, "已建立输入法侧点显器连接（无无障碍模式）")
    }

    fun stop() {
        val created = client ?: return
        client = null
        connected = false
        cellCount = 0
        restoreTask?.let { mainHandler.removeCallbacks(it) }
        restoreTask = null
        created.setOnConnectionStateChangeListener(null)
        created.setOnInputEventListener(null)
        created.shutdown()
        Log.i(TAG, "已断开输入法侧点显器连接")
    }

    fun isConnected(): Boolean = connected

    /** 把正在按的点位画到第一格，[ECHO_RESTORE_DELAY_MILLIS] 后清掉。 */
    fun showPressedDots(dots: Int) {
        val current = client ?: return
        if (!connected || cellCount <= 0) {
            return
        }
        val pattern = ByteArray(cellCount)
        if (dots != 0) {
            // 点位掩码与点阵字节位序一致：bit0 = 1 点 … bit5 = 6 点。
            pattern[0] = dots.toByte()
        }
        current.displayDots(pattern, "", null)
        restoreTask?.let { mainHandler.removeCallbacks(it) }
        val restore = Runnable {
            restoreTask = null
            if (connected && cellCount > 0) {
                client?.displayDots(ByteArray(cellCount), "", null)
            }
        }
        restoreTask = restore
        mainHandler.postDelayed(restore, ECHO_RESTORE_DELAY_MILLIS)
    }

    override fun onConnectionStateChanged(state: Int) {
        connected = state == Display.STATE_CONNECTED
        cellCount = if (connected) {
            client?.displayProperties?.numTextCells ?: 0
        } else {
            0
        }
        Log.i(TAG, "点显器状态变化 state=$state cells=$cellCount")
    }

    override fun onInputEvent(inputEvent: BrailleInputEvent) {
        // 双保险：宿主在跑时所有按键都归宿主，不能在这里再处理一遍。
        if (hostActive()) {
            return
        }
        val ime = imeProvider() ?: return
        ime.onHardwareInputEvent(inputEvent.command, inputEvent.argument)
    }
}
