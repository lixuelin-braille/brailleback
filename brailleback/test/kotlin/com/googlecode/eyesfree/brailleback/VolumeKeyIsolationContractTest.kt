package com.googlecode.eyesfree.brailleback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Static contract for the volume-key isolation wiring.
 *
 * The pure state machine is covered by [VolumeKeyGesturePolicyTest]; this file
 * makes sure the Java service actually honours it. In particular it locks down
 * the user-visible promise: a long press and a fast double press run their
 * configured action and **never** call [adjustSystemVolume]. Only the slow
 * press branch (and its delayed runnable) may touch the volume.
 */
class VolumeKeyIsolationContractTest {

    private companion object {
        const val SERVICE = "brailleback/src/com/googlecode/eyesfree/brailleback/BrailleBackService.java"
        const val POLICY = "brailleback/src/com/googlecode/eyesfree/brailleback/VolumeKeyGesturePolicy.kt"
        const val STRINGS = "brailleback/res/values/strings.xml"
        const val STRINGS_ZH = "brailleback/res/values-zh-rCN/strings.xml"
    }

    /** Gradle 单测的 cwd 可能是仓库根，也可能是 brailleback/ 模块目录。 */
    private fun projectFile(repoRelativePath: String): File {
        val candidates = listOf(
            File(repoRelativePath),
            File("..", repoRelativePath),
            File("../..", repoRelativePath),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("找不到 $repoRelativePath（cwd=${System.getProperty("user.dir")}）")
    }

    private fun text(path: String) = projectFile(path).readText()

    /** 去掉 // 行注释，避免注释里解释设计的话干扰代码断言。 */
    private fun withoutLineComments(source: String) = source
        .lineSequence()
        .map { line ->
            val idx = line.indexOf("//")
            if (idx >= 0) line.substring(0, idx) else line
        }
        .joinToString("\n")

    /** 抽出单个 Java 方法体（按花括号配对）。 */
    private fun methodBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "找不到方法 $signature" }
        val open = source.indexOf('{', start)
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, i + 1)
                }
            }
        }
        error("方法 $signature 括号不匹配")
    }

    /** 抽出 switch 中某个 case 分支，直到下一个 case / default。 */
    private fun switchBranch(source: String, caseName: String): String {
        val start = source.indexOf("case $caseName:")
        check(start >= 0) { "找不到分支 $caseName" }
        val tail = source.substring(start + 1)
        val ends = listOf(tail.indexOf("case "), tail.indexOf("default:"))
            .filter { it >= 0 }
        val end = start + 1 + (ends.minOrNull() ?: tail.length)
        return source.substring(start, end)
    }

    @Test
    fun onKeyEventDelegatesConsumptionToPolicy() {
        val body = methodBody(serviceCode(), "public boolean onKeyEvent(")
        assertTrue(body.contains("shouldConsumeVolumeKeyEvent(gesturesEnabled)"))
        assertTrue(body.contains("return consume;"))
        assertFalse(
            "旧的无参 shouldConsumeVolumeKeyEvent() 会把手势启用时的按键也放行",
            body.contains("shouldConsumeVolumeKeyEvent()"),
        )
        assertTrue(
            "没有 AudioManager 时不得吞掉音量键",
            body.contains("mAudioManager != null"),
        )
        assertTrue(
            "核心组件未就绪时不得吞掉音量键",
            body.contains("displayManager != null"),
        )
        assertTrue(body.contains("feedbackManager != null"))
        assertTrue(body.contains("onVolumeKeyUp(event)"))
    }

    @Test
    fun gesturesOffRestoresStockAndroid() {
        val body = methodBody(serviceCode(), "private boolean hasVolumeGestures()")
        assertTrue(body.contains("pref_volume_gestures_enabled_key"))
        // 三个槽位全为 none 时必须整体放行，恢复系统原生的长按连调。
        assertTrue(body.contains("isNoneVolumeAction(readVolumeAction(GESTURE_LONG_UP))"))
        assertTrue(body.contains("isNoneVolumeAction(readVolumeAction(GESTURE_LONG_DOWN))"))
        assertTrue(body.contains("isNoneVolumeAction(readVolumeAction(GESTURE_DOUBLE))"))
        val onKeyEvent = methodBody(serviceCode(), "public boolean onKeyEvent(")
        assertTrue(onKeyEvent.contains("clearVolumeGestureState()"))
    }

    @Test
    fun longPressRunsActionAndNeverAdjustsVolume() {
        val source = serviceCode()
        val runnable = methodBody(source, "private final Runnable mVolumeLongPressRunnable")
        assertTrue(runnable.contains("performVolumeAction(action)"))
        assertTrue(runnable.contains("cancelPendingSlowPress()"))
        assertFalse("长按分支不得调音量", runnable.contains("adjustSystemVolume("))
        assertTrue(runnable.contains("mVolumeLongPressFired = true"))
    }

    @Test
    fun doublePressCancelsPendingStepAndNeverAdjustsVolume() {
        val body = methodBody(serviceCode(), "private void onVolumeKeyUp(")
        assertTrue(body.contains("VolumeKeyGesturePolicy.classifyTap("))
        val branch = switchBranch(body, "RUN_DOUBLE_ACTION")
        assertTrue(branch.contains("cancelPendingSlowPress()"))
        assertTrue(branch.contains("performVolumeAction(readVolumeAction(GESTURE_DOUBLE))"))
        assertFalse("双击分支不得调音量", branch.contains("adjustSystemVolume("))

        val suppressed = switchBranch(body, "SUPPRESS_AFTER_LONG_PRESS")
        assertFalse("长按后的抬键不得调音量", suppressed.contains("adjustSystemVolume("))
    }

    @Test
    fun onlySlowPressBranchesMayAdjustVolume() {
        val source = serviceCode()
        val upBody = methodBody(source, "private void onVolumeKeyUp(")
        val nowBranch = switchBranch(upBody, "ADJUST_VOLUME_NOW")
        assertTrue(nowBranch.contains("adjustSystemVolume(keyCode)"))
        val slowRunnable = methodBody(source, "private final Runnable mSlowPressRunnable")
        assertTrue(slowRunnable.contains("adjustSystemVolume(mPendingSlowPressKeyCode)"))

        // 定义 1 次 + 慢按立即分支 1 次 + 慢按延时 runnable 1 次，多一次都算回归。
        assertEquals(
            "调音量只允许出现在慢按的两条路径上",
            3,
            source.split("adjustSystemVolume(").size - 1,
        )
    }

    @Test
    fun slowPressIsDelayedOnlyWhenDoubleActionEnabled() {
        val body = methodBody(serviceCode(), "private void onVolumeKeyUp(")
        val branch = switchBranch(body, "SCHEDULE_SLOW_PRESS")
        assertTrue(branch.contains("postDelayed(mSlowPressRunnable"))
        assertTrue(branch.contains("DOUBLE_PRESS_TIMEOUT_MS"))
        assertTrue(branch.contains("mPendingSlowPressKeyCode = keyCode"))
    }

    @Test
    fun volumeStepUsesAudioManagerAndStreamPolicy() {
        val body = methodBody(serviceCode(), "private void adjustSystemVolume(")
        assertTrue(body.contains("adjustStreamVolume(streamType, direction"))
        assertTrue(body.contains("AudioManager.FLAG_SHOW_UI"))
        assertTrue(body.contains("VolumeKeyGesturePolicy.volumeStream(inCall)"))
        assertTrue(body.contains("MODE_IN_COMMUNICATION"))
        assertTrue(body.contains("AudioManager.STREAM_VOICE_CALL"))
        assertTrue(body.contains("AudioManager.STREAM_MUSIC"))
    }

    @Test
    fun audioManagerIsInitializedBeforeKeysAreConsumed() {
        val body = methodBody(serviceCode(), "public void onServiceConnected()")
        assertTrue(body.contains("mAudioManager = (AudioManager) getSystemService"))
        val destroy = methodBody(serviceCode(), "public void onDestroy()")
        assertTrue(destroy.contains("mVolumeKeyHandler.removeCallbacksAndMessages(null)"))
    }

    @Test
    fun policyConsumptionIsConditionalNotHardcodedFalse() {
        // Kotlin 表达式体函数没有花括号，不能走 methodBody()。
        val line = text(POLICY).lineSequence()
            .first { it.contains("fun shouldConsumeVolumeKeyEvent(") }
        assertTrue(line.contains("gesturesEnabled"))
        assertFalse(line.contains("= false"))
    }

    @Test
    fun bothLocalesDescribeTheIsolationAndDropTheOldCopy() {
        val en = text(STRINGS)
        val zh = text(STRINGS_ZH)
        assertTrue(en.contains("never changes the volume"))
        assertTrue(zh.contains("不会改变音量"))
        assertFalse("英文旧文案还在，会误导用户", en.contains("also run the actions below"))
        assertFalse("中文旧文案还在，会误导用户", zh.contains("触发手势时音量也会随之变化"))
        assertTrue(en.contains("Fast double press (same key)"))
        assertTrue(zh.contains("快速双击音量键（同一键）"))
    }

    private fun serviceCode() = withoutLineComments(text(SERVICE))
}
