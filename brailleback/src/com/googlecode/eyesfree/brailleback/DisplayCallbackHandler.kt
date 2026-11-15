/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback

import android.os.Handler
import android.os.Looper
import android.os.Message
import com.googlecode.eyesfree.braille.display.BrailleInputEvent
import com.googlecode.eyesfree.braille.display.Display
import java.lang.ref.WeakReference

/**
 * 把点显器回调线程上的事件搬回主线程。
 *
 * 从 `DisplayManager` 抽出来单独成文件，但**保留 WeakReference**：
 * 主线程 MessageQueue 里的消息会持有 Handler，如果 Handler 强引用管理器，
 * 无障碍服务会跟着排队消息一起泄漏。
 *
 * 依赖的四个成员在 `DisplayManager` 里放宽为 internal（同模块可见），
 * 而不用构造参数直接持有——那样等于把服务本身挂到主线程队列上。
 */
private const val MSG_ON_CONNECTION_STATE_CHANGED = 1
private const val MSG_ON_MAPPED_INPUT_EVENT = 2
private const val MSG_ON_PAN_LEFT_OVERFLOW = 3
private const val MSG_ON_PAN_RIGHT_OVERFLOW = 4
private const val MSG_RECYCLE_CONTENT = 5
private class OnMappedInputEventArgs(
    val event: BrailleInputEvent,
    val content: Content
)

/**
 * Static so queued callbacks do not retain the DisplayManager (and its
 * accessibility service) through the main Looper.
 */
internal class DisplayCallbackHandler(manager: DisplayManager) : Handler(Looper.getMainLooper()) {
    private val managerRef = WeakReference(manager)

    fun onConnectionStateChanged(state: Int) {
        obtainMessage(MSG_ON_CONNECTION_STATE_CHANGED, state, 0)
                .sendToTarget()
    }

    fun onMappedInputEvent(event: BrailleInputEvent) {
        val manager = managerRef.get() ?: return
        val args = OnMappedInputEventArgs(event, manager.currentContent)
        obtainMessage(MSG_ON_MAPPED_INPUT_EVENT, args).sendToTarget()
    }

    fun onPanLeftOverflow() {
        val manager = managerRef.get() ?: return
        obtainMessage(MSG_ON_PAN_LEFT_OVERFLOW, manager.currentContent).sendToTarget()
    }

    fun onPanRightOverflow() {
        val manager = managerRef.get() ?: return
        obtainMessage(MSG_ON_PAN_RIGHT_OVERFLOW, manager.currentContent).sendToTarget()
    }

    fun recycleContent(content: Content) {
        obtainMessage(MSG_RECYCLE_CONTENT, content).sendToTarget()
    }

    override fun handleMessage(msg: Message) {
        when (msg.what) {
            MSG_ON_CONNECTION_STATE_CHANGED ->
                handleOnConnectionStateChanged(msg.arg1)
            MSG_ON_MAPPED_INPUT_EVENT -> {
                val args = msg.obj as OnMappedInputEventArgs
                handleOnMappedInputEvent(args.event, args.content)
            }
            MSG_ON_PAN_LEFT_OVERFLOW ->
                handleOnPanLeftOverflow(msg.obj as Content)
            MSG_ON_PAN_RIGHT_OVERFLOW ->
                handleOnPanRightOverflow(msg.obj as Content)
            MSG_RECYCLE_CONTENT ->
                handleRecycleContent(msg.obj as Content)
            else -> {
                // Fall out.
            }
        }
    }

    private fun handleOnConnectionStateChanged(state: Int) {
        managerRef.get()?.connectionStateChangeListener?.onConnectionStateChanged(state)
    }

    private fun handleOnMappedInputEvent(event: BrailleInputEvent,
                                         content: Content) {
        managerRef.get()?.mappedInputEventListener?.onMappedInputEvent(event, content)
    }

    private fun handleOnPanLeftOverflow(content: Content) {
        managerRef.get()?.panOverflowListener?.onPanLeftOverflow(content)
    }

    private fun handleOnPanRightOverflow(content: Content) {
        managerRef.get()?.panOverflowListener?.onPanRightOverflow(content)
    }

    private fun handleRecycleContent(content: Content) {
        content.recycle()
    }
}
