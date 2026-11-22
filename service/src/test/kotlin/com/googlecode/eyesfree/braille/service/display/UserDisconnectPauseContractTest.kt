/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.braille.service.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「用户主动断开 → 暂停自动重连，直到用户手动再连」这条规则的源码契约。
 *
 * <p>它横跨三个文件且没有 Android 替身可跑（DisplayService 起不来），所以按
 * 项目既有做法钉源码锚点。要防的回退很具体：`autoConnectBraille()` 早先开头
 * 无条件 `mAutoReconnectSuppressed = false`，于是亮屏、重新绑定、打开蓝牙
 * 任何一次隐式触发都会把用户刚按下的「断开」抹掉，断开的设备两秒后又连回来，
 * 而读屏用户完全不知道发生了什么。
 */
class UserDisconnectPauseContractTest {

    private fun repoFile(relative: String): File {
        val candidates = listOf(File(relative), File("..", relative), File("../..", relative))
        return candidates.firstOrNull { it.isFile }
            ?: error("找不到 $relative（cwd=${System.getProperty("user.dir")}）")
    }

    private fun text(relative: String) = repoFile(relative).readText()

    private val flow = "service/src/main/java/com/googlecode/eyesfree/braille/service/display/" +
        "DisplayServiceConnectionFlow.kt"
    private val pause = "service/src/main/java/com/googlecode/eyesfree/braille/service/display/" +
        "AutoConnectPause.kt"
    private val service = "service/src/main/java/com/googlecode/eyesfree/braille/service/display/" +
        "DisplayService.kt"
    private val pairing = "service/src/main/java/com/googlecode/eyesfree/braille/service/display/" +
        "PairingController.kt"

    @Test
    fun `隐式自动连接遇到用户断开必须直接返回`() {
        val body = text(flow)
        assertTrue(
            "autoConnectBraille 必须区分显式/隐式触发",
            body.contains("autoConnectBraille(userInitiated: Boolean = false)"),
        )
        assertTrue(
            "autoConnectBraille 必须用暂停判定决定要不要往下走",
            body.contains("if (!clearPauseForUserAction(this, userInitiated)) {"),
        )
        val pauseBody = text(pause)
        assertTrue(
            "只有 userInitiated 才允许清掉抑制标志",
            pauseBody.contains("if (userInitiated) {") &&
                pauseBody.contains("mAutoReconnectSuppressed = false"),
        )
        assertTrue(
            "抑制中必须返回 false（调用方随之 return，不许继续 connectBraille）",
            pauseBody.contains("if (service.mAutoReconnectSuppressed) {") &&
                pauseBody.contains("Auto-connect paused by user disconnect") &&
                pauseBody.contains("return false"),
        )
    }

    @Test
    fun `用户主动断开才设置抑制`() {
        val body = text(service)
        assertTrue(
            "disconnectBraille 必须只在 userInitiated 时抑制重连",
            body.contains("if (userInitiated) {") &&
                body.contains("mAutoReconnectSuppressed = true"),
        )
    }

    @Test
    fun `显式动作能解除暂停`() {
        val flowBody = text(flow)
        assertTrue(
            "poll() 走的 startExplicitConnect 必须清抑制",
            flowBody.contains("startExplicitConnect") &&
                flowBody.contains("mAutoReconnectSuppressed = false"),
        )
        assertTrue(
            "在设备页点已配对设备那一行要能突破暂停",
            text(pairing).contains("autoConnectBraille(userInitiated = true)"),
        )
        val serviceBody = text(service)
        val explicitCalls = Regex("autoConnectBraille\\(userInitiated = true\\)")
            .findAll(serviceBody)
            .count()
        assertEquals(
            "配对成功与插 USB 两处用户动作都要能突破暂停（现应为 2 处）",
            2,
            explicitCalls,
        )
    }
}
