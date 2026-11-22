/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * DisplayManager 拆分后的结构契约。
 *
 * 拆开的目的是让“内容模型 / 刷新决策 / 指纹 / 计数 / 主线程回调”各自能单独读、
 * 单独测。这个测试防的是回退：改动一旦把某个类塞回大文件，或者把 Handler
 * 换回强引用，就要在这里断。
 */
class DisplaySplitContractTest {

    private fun projectFile(repoRelativePath: String): File {
        val candidates = listOf(
            File(repoRelativePath),
            File("..", repoRelativePath),
            File("../..", repoRelativePath),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("找不到 $repoRelativePath（cwd=${System.getProperty("user.dir")}）")
    }

    private fun src(name: String) = projectFile("brailleback/src/main/java/com/googlecode/eyesfree/brailleback/$name")

    private fun srcText(name: String) = src(name).readText()

    private fun projectDir(repoRelativePath: String): File {
        val candidates = listOf(
            File(repoRelativePath),
            File("..", repoRelativePath),
            File("../..", repoRelativePath),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("找不到目录 $repoRelativePath（cwd=${System.getProperty("user.dir")}）")
    }

    @Test
    fun `内容模型已在独立文件且不再是嵌套类`() {
        val manager = srcText("DisplayManager.kt")
        assertFalse(
            "Content 不能又被塞回 DisplayManager；它是数据模型，跟消息队列没有关系",
            manager.contains("class Content"),
        )

        val content = srcText("DisplayContent.kt")
        assertTrue(content.contains("class Content "))
        // 三个对外行为：取文本、回收节点、带内嵌盲文的翻译。
        assertTrue(content.contains("fun getText()"))
        assertTrue(content.contains("fun recycle()"))
        assertTrue(content.contains("fun translateWithVerbatimBraille("))
        // 常量仍从 Content 上取（PAN_* / CONTRACT_*），调用方不用改。
        assertTrue(content.contains("const val PAN_KEEP = 2"))
    }

    @Test
    fun `旧的全限定写法不许回潮`() {
        val offenders = mutableListOf<String>()
        val root = projectDir("brailleback/src/main/java")
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    if (line.contains("DisplayManager.Content")) {
                        offenders += "${file.name}:${index + 1}"
                    }
                }
            }
        assertTrue(
            "Content 已顶层化，限定写法应消失：$offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `主线程回调独立成文件并保留弱引用`() {
        val handler = srcText("DisplayCallbackHandler.kt")
        assertTrue(handler.contains("internal class DisplayCallbackHandler"))
        // 主线程队列会持有 Handler：强引用管理器等于把无障碍服务一起泄漏。
        assertTrue(
            "Handler 必须继续通过 WeakReference 访问管理器",
            handler.contains("WeakReference"),
        )
        assertFalse(
            "Handler 不能直接持有管理器实例：${'$'}manager",
            handler.contains("private val manager: DisplayManager"),
        )

        val manager = srcText("DisplayManager.kt")
        assertTrue(
            "管理器要构造这个独立 Handler",
            manager.contains("DisplayCallbackHandler(this)"),
        )
    }

    @Test
    fun `占线器体积守住`() {
        val lines = src("DisplayManager.kt").readLines().count { it.isNotBlank() }
        // 拆分前 1527 行（含 329 行 Content 与 78 行 CallbackHandler）。
        // 2026-11-21 点位回显拆成独立 DisplayPressEcho（管理器只留 1 行转发），
        // 并把重复的 mFrozen/mMotionMode 访问器并回 DisplayRuntimeState 的只读访问器：
        // 拆分后 1098 → 现在 1101。上限 1105 是「只许比拆分后多 7 行」，
        // 再往里堆逻辑还是先在这里断，而不是等文件长回 1500 行。
        assertTrue("DisplayManager 非空行数 $lines 已超出拆分后的预算", lines <= 1105)
    }

    @Test
    fun `刷新链路定时器已全部协程化`() {
        val manager = srcText("DisplayManager.kt")
        // 三个定时器（空闲清屏 / 运动去抖 / 闪烁）已换成 Job + delay：
        // 自续 Runnable 和 Handler 延迟消息不许回潮。
        assertFalse("不许再出现自续 Runnable 定时器", manager.contains("mIdleClearRunnable"))
        assertFalse("不许再出现运动去抖 Runnable", manager.contains("mMotionModeRefreshRunnable"))
        assertFalse("闪烁不该再走消息队列", manager.contains("MSG_PULSE"))
        assertFalse("不该再用 postDelayed 排定时", manager.contains(".postDelayed("))
        assertTrue("调度器必须绑定 display HandlerThread 的 looper",
            manager.contains("Handler(displayLooper).asCoroutineDispatcher()"))
        // 独立的 telemetry 线程不能并进来：它要在 display 线程卡死时仍然出日志。
        assertTrue("统计上报线程必须保留", manager.contains("DisplayStats"))
    }

    @Test
    fun `跨线程状态只走一份快照`() {
        val manager = srcText("DisplayManager.kt")
        // 五个跨线程布尔已收进 DisplayRuntimeState，不许再有散落的 @Volatile 布尔。
        for (field in listOf(
            "private var connected",
            "private var simulatedDisplay",
            "private var mFrozen",
            "private var mMotionMode",
            "private var cursorDotsEnabled",
        )) {
            assertFalse("$field 应改为 DisplayRuntimeState 的只读访问器", manager.contains(field))
        }
        // 限速三件套是一组，收进 RefreshGate 后不许再散着写。
        for (field in listOf(
            "lastDisplayedFingerprint",
            "pendingFingerprint",
            "lastRefreshUptime",
        )) {
            assertFalse("$field 应改走 RefreshGate", manager.contains(field))
        }
        assertTrue("限速三件套必须收进 RefreshGate", manager.contains("RefreshGate()"))
        assertTrue(
            "指纹去重必须走闸门（送达前不许转正）",
            manager.contains("refreshGate.isDuplicateOfLast("),
        )
        assertTrue("刷新成功必须走闸门记时",
            manager.contains("refreshGate.onDelivered("))
        assertTrue("状态必须来自 MutableStateFlow", manager.contains("MutableStateFlow(DisplayRuntimeState())"))
        // 裁决必须一次取快照：连读三个字段就是原来那个撕裂读。
        assertTrue(
            "refresh() 必须用 runtimeState.value.refreshDecision()",
            manager.contains("runtimeState.value.refreshDecision()"),
        )
        assertFalse(
            "不许再出现 decideRefresh(connected, mFrozen, mMotionMode)",
            manager.contains("decideRefresh(connected, mFrozen, mMotionMode)"),
        )
    }
}
