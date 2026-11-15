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

package com.googlecode.eyesfree.brailleback

import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.googlecode.eyesfree.brailleback.compose.StabilityComposeActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppLaunchSmokeTest {

    private val target get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun settingsActivityReachesResumed() {
        // 设置主屏是六个分类入口的壳；它起不来，所有子屏都无从进入。
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
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
