/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 仪器测试：新手引导页（OverlayTutorialActivity）的两个演示动画。
 *
 * 为什么需要它：这两段原来各是一个自调度 Runnable，暂停/恢复靠
 * removeCallbacks 配对；换成 lifecycleScope 的协程循环后，「暂停真的会停」
 * 这件事只有真跑一遍才看得见——静态契约测试只能证明写法变了，证明不了
 * 协程在 onPause 后确实不再排队。
 */

package com.googlecode.eyesfree.brailleback

import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OverlayTutorialSmokeTest {

    @Test
    fun overlayTutorialReachesResumed() {
        ActivityScenario.launch(OverlayTutorialActivity::class.java).use { scenario ->
            assertEquals(
                "新手引导页没有走到 RESUMED：多半是布局或 BrailleDisplayProperties 解析失败",
                Lifecycle.State.RESUMED,
                scenario.state,
            )
            // 让两个演示各跑几拍，确认协程循环不会在首轮就把页面带崩。
            Thread.sleep(1_500)
            assertEquals(
                "演示跑了一秒半后页面已经不是 RESUMED（协程里抛了异常）",
                Lifecycle.State.RESUMED,
                scenario.state,
            )
        }
    }

    @Test
    fun panDemoTogglesWhileResumed() {
        ActivityScenario.launch(OverlayTutorialActivity::class.java).use { scenario ->
            val samples = mutableListOf<Boolean>()
            repeat(4) {
                Thread.sleep(300)
                scenario.onActivity { activity ->
                    samples += activity.findViewById<View>(R.id.pan_left_button).isPressed
                }
            }
            assertTrue(
                "左右按钮的按起演示没在跑（1.2 秒内采样为 $samples，应当有翻转）",
                samples.toSet().size >= 2,
            )
        }
    }

    @Test
    fun demoTimersStopWhenPaused() {
        ActivityScenario.launch(OverlayTutorialActivity::class.java).use { scenario ->
            scenario.moveToState(Lifecycle.State.CREATED)

            var first = false
            scenario.onActivity { activity ->
                first = activity.findViewById<View>(R.id.pan_left_button).isPressed
            }
            Thread.sleep(800)
            var second = false
            scenario.onActivity { activity ->
                second = activity.findViewById<View>(R.id.pan_left_button).isPressed
            }
            assertEquals(
                "onPause 之后协程还在跑：按钮状态从 $first 变成了 $second",
                first,
                second,
            )
        }
    }
}
