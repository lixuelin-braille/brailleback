/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 界面层定时任务的静态契约：Activity 里不再用自建 Handler 的 postDelayed 排延迟。
 *
 * 为什么值得一条测试：这类「new Handler 排一次延迟」的写法在页面被销毁后仍会
 * 触发，表现为弹窗、播放、闪一下等幽灵行为；而它不是编译错误、也不是 lint
 * 默认规则能抓到的（前面几轮真出过这种问题）。换成 lifecycleScope 后行为一致、
 * 生命周期到点自动取消，所以把它钉成契约，防止以后有人顺手写回来。
 *
 * 不受此约束、也刻意保留的 Handler：盲文驱动链（DriverThread / UsbDisplayConnection 的
 * 同步 bulk transfer、GATT/HID 回调）、IPC 回调落点、View 级高亮清除
 * （[BrailleView] 的 postDelayed 随 attach/detach 走）、
 * 手势时序对齐（[DraggableOverlay] 用 sendMessageAtTime 对齐 MotionEvent.eventTime）。
 * 详见 docs/34-线程与Handler盘点.md 与 docs/36-界面层协程化.md。
 */
class CoroutineScopeContractTest {

    private companion object {
        const val BRAILLEBACK_SRC = "brailleback/src/main/java"
        const val OVERLAY_TUTORIAL =
            "brailleback/src/main/java/com/googlecode/eyesfree/brailleback/OverlayTutorialActivity.kt"
        const val IME_WIZARD =
            "brailleback/src/main/java/com/googlecode/eyesfree/brailleback/IMESetupWizardActivity.kt"
        const val IME_HOST_BRIDGE =
            "brailleback/src/main/java/com/googlecode/eyesfree/brailleback/ime/BrailleImeHostBridge.kt"
        const val PASSTHROUGH_PROBE =
            "brailleback/src/main/java/com/googlecode/eyesfree/brailleback/PassthroughProbe.kt"
    }

    private fun repoRoot(): File =
        listOf(File("."), File(".."), File("../.."))
            .first { File(it, "settings.gradle").isFile }

    private fun read(relativePath: String): String =
        File(repoRoot(), relativePath).readText()

    private fun activitySources(): List<Pair<String, String>> {
        val root = File(repoRoot(), BRAILLEBACK_SRC)
        return root.walkTopDown()
            .filter { it.isFile && it.name.endsWith("Activity.kt") }
            .map { it.relativeTo(repoRoot()).path.replace('\\', '/') to it.readText() }
            .toList()
    }

    @Test
    fun activitiesDoNotScheduleDelayedWorkWithBareHandler() {
        val offenders = activitySources()
            .filter { (_, text) -> text.contains("postDelayed(") }
            .map { it.first }
        assertTrue(
            "这些 Activity 又用 postDelayed 排延迟了，改回 lifecycleScope.launch { delay(...) }：$offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun overlayTutorialDemosRunInLifecycleScope() {
        val text = read(OVERLAY_TUTORIAL)
        assertTrue("引导页必须是 LifecycleOwner 才能用 lifecycleScope",
            text.contains(": ComponentActivity()"))
        assertTrue("左右按钮演示必须挂到生命周期作用域",
            text.contains("mPanDemoJob = lifecycleScope.launch"))
        assertTrue("路由键演示必须挂到生命周期作用域",
            text.contains("mRoutingKeyDemoJob = lifecycleScope.launch"))
        assertTrue("两个演示的间隔必须还在（500ms / 1500ms）",
            text.contains("delay(PAN_DEMO_INTERVAL)") &&
                text.contains("delay(ROUTING_KEY_DEMO_INTERVAL)"))
        assertTrue("页面不可见时必须取消这两个循环",
            text.contains("mPanDemoJob?.cancel()") &&
                text.contains("mRoutingKeyDemoJob?.cancel()"))
        assertFalse("自调度 Runnable 写法必须消失（否则等于两个定时器并存）",
            text.contains("object : Runnable"))
    }

    @Test
    fun imeWizardPickerDelayRunsInHostActivityScope() {
        val text = read(IME_WIZARD)
        assertTrue("300ms 必须还是常量，别再散落字面量",
            text.contains("INPUT_METHOD_PICKER_DELAY_MS = 300L"))
        assertTrue("延迟必须走协程",
            text.contains("delay(INPUT_METHOD_PICKER_DELAY_MS)"))
        assertTrue(
            "嵌套 Fragment 拿不到外部 this，必须挂宿主 Activity 的作用域",
            text.contains("activity as? ComponentActivity") &&
                text.contains("host.lifecycleScope.launch"),
        )
    }

    @Test
    fun imeWizardHandlerSurvivesOnlyForContentObserver() {
        val text = read(IME_WIZARD)
        // ContentObserver(Handler) 是 API 硬约束，这一处必须留着；
        // 除它之外若再出现第二个 Handler()，说明又有人拿它排延迟。
        assertEquals(
            "IMESetupWizardActivity 只允许一个 Handler，且只给 ContentObserver 用",
            1,
            Regex("""\bHandler\(\)""").findAll(text).count(),
        )
        assertTrue(
            "那个 Handler 必须是 ContentObserver 的构造参数",
            text.contains("ContentObserver(handler)"),
        )
    }

    @Test
    fun imeHostBridgeCancelsItsScopeOnShutdown() {
        val text = read(IME_HOST_BRIDGE)
        assertFalse(
            "播报/直通区的延迟必须走 mainScope，不要再自建 Handler",
            text.contains("android.os.Handler("),
        )
        assertTrue(
            "shutdown() 必须先取消作用域，否则服务销毁后还会往已关的 TTS 里说话",
            text.contains("mainScope.cancel()"),
        )
        assertTrue(
            "直通区落地那一拍要能单独取消",
            text.contains("passthroughApplyJob?.cancel()"),
        )
    }

    @Test
    fun passthroughProbeScopeIsTiedToInstallLifecycle() {
        val text = read(PASSTHROUGH_PROBE)
        assertFalse("探针的两个延迟必须走作用域，不要再自建 Handler",
            text.contains("Handler("))
        assertTrue(
            "作用域必须在 install 里建立、主线程 immediate",
            text.contains("scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)"),
        )
        assertTrue(
            "uninstall 必须撤掉作用域（原来只管自动撤销，400ms 落地那一拍会漏）",
            text.contains("scope?.cancel()"),
        )
        assertTrue(
            "400ms 落地拍与自动撤销都必须能被单独取消",
            text.contains("settleJob?.cancel()") && text.contains("autoDisarmJob?.cancel()"),
        )
    }
}
