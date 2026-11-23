/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 仪器测试：只验证「在真实设备上真的能起来」。
 *
 * 为什么需要它：JVM 单测与契约测试（读源码字符串）能挡住回退，但挡不住
 * 「逻辑对、设备上起不来」——资源数组缺失、主题解析失败、Compose 初始化崩、
 * Activity 走到 onResume 前的 ActivityNotFound 之类，都只在设备上才暴露。
 * 这里刻意不写 UI 交互（缓存里没有与 Compose 1.7.6 同版的 ui-test），
 * 只断言 Activity 到 RESUMED 与关键资源能解析。
 */

package com.linxi.brailleback

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.linxi.brailleback.compose.StabilityComposeActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppLaunchSmokeTest {

    private val target get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun settingsActivityReachesResumed() {
        // 设置主屏已迁到 HomeContainerActivity 的设置 Tab；它起不来，所有子屏都无从进入。
        val intent = android.content.Intent(target, HomeContainerActivity::class.java)
            .putExtra(HomeContainerActivity.EXTRA_TAB, HomeContainerActivity.TAB_SETTINGS)
        ActivityScenario.launch(intent).use { scenario ->
            assertEquals(
                "设置主屏没有走到 RESUMED：多半是主题或 Preference 解析失败",
                Lifecycle.State.RESUMED,
                scenario.state,
            )
        }
    }

    @Test
    fun stabilityScreenReachesResumed() {
        // 这一屏在 onCreate 里读系统状态并首次 Composable 渲染，
        // 资源数组/文案缺失会在这里直接崩，不用等用户点进来才发现。
        ActivityScenario.launch(StabilityComposeActivity::class.java).use { scenario ->
            assertEquals(
                "稳定性与省电屏没有走到 RESUMED",
                Lifecycle.State.RESUMED,
                scenario.state,
            )
        }
    }

    @Test
    fun stabilityVendorStepsResolveToTwelveEntries() {
        // 按厂商切区间，少一项就会有一家厂商读到空段落。
        val steps = target.resources.getStringArray(R.array.stability_vendor_steps)
        assertEquals("厂商指引应为 12 条（小米 3 / 华为 2 / OPPO·一加 2 / vivo 2 / 其余 3）", 12, steps.size)
        steps.forEachIndexed { index, step ->
            assertTrue("第 ${index + 1} 条厂商指引是空的", step.isNotBlank())
        }
    }

    @Test
    fun keyBindingsActivityReachesResumed() {
        // 这屏 2026-11-18 从 TextView+ListView 换成 Compose，基类随之改成 ComponentActivity；
        // 主题是 Theme.DeviceDefault（非 AppCompat），起不来就是主题/基类不匹配。
        ActivityScenario.launch(KeyBindingsActivity::class.java).use { scenario ->
            assertEquals(
                "键盘帮助屏没有走到 RESUMED：多半是基类与主题不匹配",
                Lifecycle.State.RESUMED,
                scenario.state,
            )
        }
    }

    @Test
    fun keyBindingsInstructionsResolve() {
        // 屏首那段说明由资源提供；老版本里它和 ListView 一起放在 key_bindings.xml，
        // 布局删掉后只剩这一个来源，读不到就等于屏幕空白。
        val instructions = target.resources.getString(R.string.key_bindings_instructions)
        assertTrue("键盘帮助说明为空", instructions.isNotBlank())
    }

    @Test
    fun bookshelfActivityReachesResumed() {
        // 书架刚从 android.app.Activity 换成 ComponentActivity 承载 launcher，
        // 主题仍是 DeviceDefault。这一步专门盯「换基类后窗口能不能正常挂载」。
        ActivityScenario.launch(BookshelfActivity::class.java).use { scenario ->
            assertEquals(
                "书架没有走到 RESUMED：换基类后主题或窗口挂载失败",
                Lifecycle.State.RESUMED,
                scenario.state,
            )
        }
    }

    @Test
    fun brailleReaderActivityReachesResumed() {
        // 阅读器同样换了基类，且 onCreate 里绑点显器客户端；起不来会在 RESUMED 前暴露。
        ActivityScenario.launch(BrailleReaderActivity::class.java).use { scenario ->
            assertEquals(
                "阅读器没有走到 RESUMED",
                Lifecycle.State.RESUMED,
                scenario.state,
            )
        }
    }

    @Test
    fun imeWizardLaunchesWithoutThemeCrash() {
        // 这页用的是 @android:style/Theme.Holo.Dialog，换基类后最容易在窗口背景上报错。
        // 若手机上的盲文输入法已经是默认输入法，checkStatus 会立刻 finish，属正常路径。
        ActivityScenario.launch(IMESetupWizardActivity::class.java).use { scenario ->
            assertTrue(
                "IME 向导弹窗既没停在 RESUMED 也没正常结束：${scenario.state}",
                scenario.state == Lifecycle.State.RESUMED ||
                    scenario.state == Lifecycle.State.DESTROYED,
            )
        }
    }

    @Test
    fun settingsCategoryKeysAndProtocolLabelsResolve() {
        val res = target.resources
        // 六个分类入口的 key 必须都能解析，否则 wireCategory 会静默不生效。
        listOf(
            R.string.pref_category_device_key,
            R.string.pref_category_keys_key,
            R.string.pref_category_braille_key,
            R.string.pref_category_sound_behavior_key,
            R.string.pref_category_developer_key,
            R.string.pref_category_stability_key,
        ).forEach { id ->
            assertTrue("分类 key 为空：$id", res.getString(id).isNotBlank())
        }

        // 连接方式三项：顺序与文案都要在设备上真的读得出来。
        val values = res.getStringArray(R.array.pref_connection_protocol_values)
        val entries = res.getStringArray(R.array.pref_connection_protocol_entries)
        assertEquals("连接方式应为三项", 3, values.size)
        assertEquals("values 与 entries 必须同序等长", values.size, entries.size)
        assertEquals("自动必须排第一", "auto", values[0])
        entries.forEach { label ->
            assertTrue("连接方式文案为空", label.isNotBlank())
        }
    }
}
