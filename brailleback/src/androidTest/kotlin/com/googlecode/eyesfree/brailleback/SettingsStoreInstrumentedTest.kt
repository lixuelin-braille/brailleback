/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * SettingsStore 的行为测试（仪器测试）。
 *
 * 三件必须在真设备上验证的事：
 * 1. `apply()` 之后立刻同步读，能读到新值（快照语义，旧代码大量这样用）；
 * 2. 跨进程键真的落在旧 SharedPreferences 文件里（否则 :service 读不到分词开关）；
 * 3. 旧文件里已有的值会被一次性迁移进 DataStore，并从旧文件清掉。
 */

package com.googlecode.eyesfree.brailleback

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.googlecode.eyesfree.utils.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsStoreInstrumentedTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun legacyFile() =
        context.getSharedPreferences(context.packageName + "_preferences", Context.MODE_PRIVATE)

    private fun store() = SettingsStore.get(context)

    @Before
    fun resetInstance() {
        // 每个用例都重新拿一次实例，迁移逻辑才会真正再跑一遍。
        SettingsStore.clearInstanceForTesting()
    }

    @Test
    fun applyIsVisibleToImmediateSyncRead() {
        val prefs = store()
        prefs.edit().putBoolean("contract_apply_visible", false).commit()
        assertFalse(prefs.getBoolean("contract_apply_visible", true))

        prefs.edit().putBoolean("contract_apply_visible", true).apply()
        // 关键：不 sleep、不等待，马上读。旧代码全是这个用法。
        assertTrue(prefs.getBoolean("contract_apply_visible", false))
    }

    @Test
    fun stringSetRoundTripsAndReturnsCopy() {
        val prefs = store()
        prefs.edit().putStringSet("contract_set", setOf("a", "b")).apply()
        val read = prefs.getStringSet("contract_set", emptySet())
        assertEquals(setOf("a", "b"), read)
        // 返回的集合不应是内部快照的引用：调用方改动它不该影响存储。
        (read as? MutableSet<String>)?.add("c")
        assertEquals(setOf("a", "b"), prefs.getStringSet("contract_set", emptySet()))
    }

    @Test
    fun crossProcessKeyLandsInLegacyFile() {
        val prefs = store()
        prefs.edit().putBoolean("chinese_segmentation", false).commit()
        // TranslatorService 在 :service 进程里读的就是这个文件。
        assertFalse(
            "分词开关必须写进旧 SharedPreferences 文件，否则 :service 读不到",
            legacyFile().getBoolean("chinese_segmentation", true),
        )
        assertFalse(prefs.getBoolean("chinese_segmentation", true))
    }

    @Test
    fun legacyValuesAreMigratedOnceAndRemovedFromTheOldFile() {
        // 模拟老用户：值还在旧文件里（还没被迁移过）。
        legacyFile().edit().putString("contract_legacy_key", "旧值").commit()
        assertTrue(legacyFile().contains("contract_legacy_key"))

        val prefs = store()
        assertEquals("旧值", prefs.getString("contract_legacy_key", null))
        assertFalse(
            "迁移后旧文件里不应再留这个键，否则就是双源",
            legacyFile().contains("contract_legacy_key"),
        )
    }

    @Test
    fun listenerIsNotifiedOnWrite() {
        val prefs = store()
        val seen = mutableListOf<String>()
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            seen.add(key ?: "")
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        try {
            prefs.edit().putBoolean("contract_listener_key", true).commit()
            // 回调统一投递到主线程（服务层一直这么假设），所以断言前要等主线程跑完。
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        } finally {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
        assertTrue("写入必须通知监听器（依赖它的服务才能即时生效）", seen.contains("contract_listener_key"))
    }

    @Test
    fun unsupportedTypeFallbackInsteadOfCrash() {
        val prefs = store()
        prefs.edit().putString("contract_type_mismatch", "不是布尔").commit()
        // 旧实现这里会抛 ClassCastException；新实现回退默认值，不能让设置页崩。
        assertFalse(prefs.getBoolean("contract_type_mismatch", false))
        assertEquals("不是布尔", prefs.getString("contract_type_mismatch", null))
    }
}
