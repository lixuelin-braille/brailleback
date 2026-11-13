/*
 * Copyright (C) 2026 The BrailleBack Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.googlecode.eyesfree.brailleback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Locks down the refresh de-duplication / throttling contract that keeps the
 * braille display from flickering.
 *
 * The accessibility framework re-delivers identical content whenever a window
 * is redrawn (the Xiaomi launcher does it dozens of times per second), and the
 * display protocol has no partial update: every frame rewrites all cells, so
 * every redundant refresh is a visible flash. Three behaviours have to hold:
 *
 *  1. identical content is dropped before translation,
 *  2. dense bursts are coalesced to a minimum interval while single changes
 *     still go through immediately (no delay on ordinary focus movement),
 *  3. an empty frame does not blank the display.
 *
 * Each assertion names the failure mode it prevents; the matching mutants are
 * listed in the class comment of the mutation run so a future refactor that
 * "simplifies" one of them fails here instead of on a user's desk.
 */
class DisplayRefreshThrottleContractTest {

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

    private fun displayManagerSource() =
        projectFile("brailleback/src/com/googlecode/eyesfree/brailleback/DisplayManager.kt")
            .readText()

    /** 抽出单个 Kotlin 方法体，避免同名字符串造成假通过。 */
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

    /** 从 fromIndex 起取配对花括号内的代码块。 */
    private fun braceBlock(source: String, fromIndex: Int): String {
        val open = source.indexOf('{', fromIndex)
        check(open >= 0) { "找不到起始花括号（index=$fromIndex）" }
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(fromIndex, i + 1)
                }
            }
        }
        error("括号不匹配（index=$fromIndex）")
    }

    private fun setContentBody() =
        methodBody(displayManagerSource(), "fun setContent(content: Content) {")

    private fun handleSetContentBody() =
        methodBody(displayManagerSource(), "private fun handleSetContent(content: Content)")

    private fun doRefreshBody() =
        methodBody(displayManagerSource(), "private fun doRefresh()")

    private fun fingerprintBody() =
        methodBody(
            displayManagerSource(),
            "private fun contentFingerprint(content: Content)")

    // ---- 1. 内容指纹必须覆盖所有会影响显示的字段 ----

    @Test
    fun fingerprintCoversEveryInputThatChangesTheDisplay() {
        val body = fingerprintBody()
        // 只比文本不够：光标移动、选区变化、换表都要求重新显示。
        assertTrue("指纹漏掉平移策略", body.contains("content.getPanStrategy()"))
        assertTrue("指纹漏掉收缩模式", body.contains("content.getContractionMode()"))
        assertTrue("指纹漏掉可编辑标志", body.contains("content.isEditable()"))
        assertTrue("指纹漏掉分段标志", body.contains("content.isSplitParagraphs()"))
        assertTrue("指纹漏掉选区区间",
            body.contains("DisplaySpans.SelectionSpan::class.java"))
        assertTrue("指纹漏掉焦点区间（焦点移动必须重画）",
            body.contains("DisplaySpans.FocusSpan::class.java"))
        assertTrue("指纹漏掉 BrailleSpan（内嵌盲文改了必须重画）",
            body.contains("DisplaySpans.BrailleSpan::class.java"))
    }

    // ---- 2. 去重必须发生在翻译与整屏重算之前 ----

    @Test
    fun duplicateContentIsDroppedBeforeTranslation() {
        val body = handleSetContentBody()
        val compareAt = body.indexOf("if (fingerprint == lastDisplayedFingerprint)")
        val translateAt = body.indexOf("translateCurrentContent()")
        assertTrue("handleSetContent 里找不到指纹比较", compareAt >= 0)
        assertTrue("handleSetContent 里找不到翻译调用", translateAt >= 0)
        assertTrue(
            "指纹比较必须在翻译之前，否则重复帧仍然整屏重算",
            compareAt < translateAt)
        // 指纹计算本身也不能放在翻译之后。
        val computeAt = body.indexOf("contentFingerprint(content)")
        assertTrue("指纹必须在进入重算流程时就计算", computeAt in 0 until compareAt)
    }

    @Test
    fun droppedContentIsRecycledInsteadOfLeaked() {
        val body = handleSetContentBody()
        val compareAt = body.indexOf("if (fingerprint == lastDisplayedFingerprint)")
        val recycleAt = body.indexOf("callbackHandler.recycleContent(content)", compareAt)
        val returnAt = body.indexOf("return", compareAt)
        assertTrue("丢弃重复帧时必须归还节点引用", recycleAt > compareAt)
        assertTrue("recycle 必须在提前返回之前", recycleAt < returnAt)
    }

    @Test
    fun fingerprintOnlyAdvancesAfterASuccessfulRefresh() {
        val body = doRefreshBody()
        val dotsAt = body.indexOf("displayDots(")
        val commitAt = body.indexOf("lastDisplayedFingerprint = pendingFingerprint")
        assertTrue("找不到 displayDots 调用", dotsAt >= 0)
        assertTrue("找不到指纹转正语句", commitAt >= 0)
        assertTrue(
            "指纹只能在真正下发之后转正：连接未建立/冻结时提前记录，" +
                "恢复后第一帧会被自己丢掉，显示停在旧内容",
            dotsAt < commitAt)
    }

    // ---- 3. 密集刷新限速，但单次变化不能有延迟 ----

    @Test
    fun minimumRefreshIntervalIs250Millis() {
        val source = displayManagerSource()
        assertTrue(
            "最小刷新间隔应为 250ms（实测峰值 5.6 帧/秒，80ms 窗口压不住）",
            source.contains("private const val CONTENT_MIN_INTERVAL_MILLIS: Long = 250"))
    }

    @Test
    fun burstIsDelayedButSingleChangeIsImmediate() {
        val body = setContentBody()
        assertTrue("限速必须按「距上次刷屏的时间」计算延迟",
            body.contains("CONTENT_MIN_INTERVAL_MILLIS - elapsed"))
        assertTrue("延迟不能为负", body.contains("coerceAtLeast(0)"))
        assertTrue("必须记录上次刷屏时间",
            body.contains("SystemClock.uptimeMillis() - lastRefreshUptime"))
    }

    @Test
    fun coalesceTimerIsNotResetByIncomingFrames() {
        val body = setContentBody()
        // 重置定时器会让持续的事件洪流把首帧无限推迟，表现为「盲文不动」。
        assertFalse("替换待处理帧时不能取消/重置定时器",
            body.contains("removeMessages("))
        // 定时器只允许在「没有待处理帧」的分支里启动一次。
        assertTrue("定时器启动点必须唯一",
            body.split("sendEmptyMessageDelayed(MSG_SET_CONTENT").size == 2)
        val supersededAt = body.indexOf("if (superseded != null)")
        val scheduleAt = body.indexOf("sendEmptyMessageDelayed(MSG_SET_CONTENT")
        assertTrue("替换分支必须在安排定时器之前返回", supersededAt in 0 until scheduleAt)
    }

    @Test
    fun pendingFrameIsThreadSafe() {
        val source = displayManagerSource()
        assertTrue("待处理帧要跨线程交接，必须用原子引用",
            source.contains("private val pendingContent = AtomicReference<Content?>(null)"))
        assertTrue("定时器去重标志必须是原子的",
            source.contains("private val coalescePending = AtomicBoolean(false)"))
        assertTrue("定时器必须用 CAS 抢占",
            source.contains("coalescePending.compareAndSet(false, true)"))
    }

    @Test
    fun pendingFrameIsRecycledOnShutdown() {
        val body = methodBody(displayManagerSource(), "private fun handleStop()")
        assertTrue("关闭时未处理的帧必须归还，不能随 handler 丢弃",
            body.contains("callbackHandler.recycleContent(pending)"))
    }

    // ---- 4. 空帧不能清屏 ----

    @Test
    fun emptyFrameDoesNotBlankTheDisplay() {
        val body = doRefreshBody()
        assertTrue("空帧抑制条件缺失",
            body.contains("toDisplay.isEmpty() && statLastCells > 0"))
        val guardAt = body.indexOf("if (toDisplay.isEmpty()")
        val dotsAt = body.indexOf("displayDots(")
        assertTrue("空帧抑制必须在 displayDots 之前返回", guardAt in 0 until dotsAt)
        // 只取抑制分支自己的花括号块，不能把后面的正常刷新代码算进来。
        val guardBlock = braceBlock(body, guardAt)
        assertTrue("空帧抑制块必须位于下发之前",
            guardAt + guardBlock.length <= dotsAt)
        assertTrue("被抑制的空帧要单独计数，便于日志核对",
            guardBlock.contains("statBlockedEmpty += 1"))
        assertTrue("被抑制的空帧必须提前返回", guardBlock.contains("return"))
        // 抑制时不能把 statLastCells 归零，否则第二次空帧就会漏出去清屏。
        assertFalse("抑制空帧时不能清掉已显示格数", guardBlock.contains("statLastCells = 0"))
        assertFalse("被抑制的空帧不能计入已刷新次数",
            guardBlock.contains("statRefreshOk += 1"))
    }

    @Test
    fun refreshStatsExposeTheNewCounters() {
        val source = displayManagerSource()
        // 没有这几个计数，现场日志无法区分「没内容可推」和「被限速拦住」。
        assertTrue(source.contains("identical=%d"))
        assertTrue(source.contains("empty=%d"))
        assertTrue(source.contains("coalesced=%d"))
        assertTrue(source.contains("statBlockedIdentical"))
        assertTrue(source.contains("statCoalesced"))
    }
}
