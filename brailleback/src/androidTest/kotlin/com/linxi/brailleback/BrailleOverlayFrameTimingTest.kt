/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 隔离画布帧测：在同一个空 ComponentActivity 上先后挂 Compose 叠加层画布
 * [BrailleOverlayCanvas] 与旧 [BrailleView]，用固定点位数据集连续触发 ≥100 次
 * 重组/重绘，经 Choreographer.postFrameCallback 采集「状态变更到下一帧回调」的
 * 就绪时延（ns），并统计超时（掉帧）次数。
 *
 * 口径声明：这是「隔离画布帧测」，不等于真实叠加层窗口在 TalkBack 下的帧率；
 * 样本为 Choreographer 帧就绪时延，不是 FrameMetrics TOTAL_DURATION；真机帧率、
 * 低端机时延仍属未验证项。两组数据同机（brailleback_api35）同场景。
 *
 * 判定闸门（外部对照，不在 JUnit 内自动合入）：
 * 新版 P99 不高于旧版 P99 的 110%，且掉帧数不增加，才允许合并 overlay #2。
 */

package com.linxi.brailleback.compose

import android.app.Activity
import android.view.Choreographer
import android.view.LayoutInflater
import android.view.ViewGroup
import com.linxi.brailleback.R
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.googlecode.eyesfree.braille.display.BrailleDisplayProperties
import com.linxi.brailleback.BrailleView
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class BrailleOverlayFrameTimingTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val cellCount = 20
    private val updates = 120
    private val text = "Open Source Licenses"
    private val positions = IntArray(cellCount) { i -> i * text.length / cellCount }

    private val samples = Collections.synchronizedList(mutableListOf<Long>())
    private var droppedFrames = 0

    private fun resumedActivity(): Activity {
        var activity: Activity? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            activity = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .firstOrNull()
        }
        return requireNotNull(activity) { "没有处于 RESUMED 的 Activity" }
    }

    private fun pattern(update: Int): ByteArray {
        val data = ByteArray(cellCount)
        val base = if (update % 2 == 0) 0b10101010 else 0b01010101
        for (i in 0 until cellCount) {
            data[i] = (base xor (i * 31) xor (update * 17)).toByte()
        }
        return data
    }

    private fun measureSide(mutate: (Int) -> Unit): Triple<Long, Long, Int> {
        samples.clear()
        droppedFrames = 0
        repeat(updates) { i ->
            val latch = CountDownLatch(1)
            var before = 0L
            composeRule.runOnUiThread {
                before = System.nanoTime()
                mutate(i)
                Choreographer.getInstance().postFrameCallback {
                    samples.add(System.nanoTime() - before)
                    latch.countDown()
                }
            }
            if (!latch.await(2, TimeUnit.SECONDS)) {
                droppedFrames++
            }
        }
        composeRule.waitForIdle()
        val sorted = samples.sorted()
        val p50 = sorted[(sorted.size * 0.5).toInt()] / 1_000_000
        val p99 = sorted[(sorted.size * 0.99).toInt().coerceAtMost(sorted.size - 1)] / 1_000_000
        return Triple(p50, p99, droppedFrames)
    }

    @Test
    fun compareComposeCanvasVsLegacyView() {
        val activity = resumedActivity()

        // —— Compose 画布侧 ——
        val braille = mutableStateOf(pattern(0))
        composeRule.setContent {
            BrailleOverlayCanvas(
                numCells = cellCount,
                braille = braille.value,
                text = text,
                brailleToTextPositions = positions,
                highlightedCell = -1,
            )
        }
        composeRule.waitForIdle()
        val composeStats = measureSide { i -> braille.value = pattern(i) }
        val composeSampleCount = samples.size

        // —— 旧 BrailleView 侧 ——
        lateinit var legacy: BrailleView
        composeRule.runOnUiThread {
            val overlayRoot = LayoutInflater.from(activity).inflate(R.layout.overlay, null)
            legacy = overlayRoot.findViewById<BrailleView>(R.id.braille_view)
            (legacy.parent as? ViewGroup)?.removeView(legacy)
            legacy.layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            activity.addContentView(legacy, legacy.layoutParams)
            legacy.setDisplayProperties(
                BrailleDisplayProperties(cellCount, 0, emptyArray(), emptyMap()),
            )
        }
        composeRule.waitForIdle()
        val legacyStats = measureSide { i ->
            legacy.displayDots(pattern(i), text, positions)
        }
        val legacySampleCount = samples.size

        assertTrue("Compose 侧采样不足 100 帧：$composeSampleCount", composeSampleCount >= 100)
        assertTrue("旧 View 侧采样不足 100 帧：$legacySampleCount", legacySampleCount >= 100)

        android.util.Log.i(
            "OverlayFrameTiming",
            "compose p50=${composeStats.first}ms p99=${composeStats.second}ms " +
                "dropped=${composeStats.third} samples=$composeSampleCount | " +
                "legacy p50=${legacyStats.first}ms p99=${legacyStats.second}ms " +
                "dropped=${legacyStats.third} samples=$legacySampleCount",
        )
    }
}
