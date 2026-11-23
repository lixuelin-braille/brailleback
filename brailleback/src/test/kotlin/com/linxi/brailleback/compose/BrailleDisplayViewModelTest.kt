/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「盲文显示」设置页 ViewModel 的纯 JVM 测试。
 *
 * 这一层之所以能被纯 JVM 测试覆盖，是因为它只依赖 [BrailleSettingsStore] 这个窄接口：
 * 偏好读写换成内存实现后，「读了什么 / 写了什么 / 有没有多写」都能直接断言。
 * 之前同样的逻辑散在 Activity 里（读偏好 + 写偏好 + 对账三处各写一遍），
 * 只能靠静态契约测试盯着源码文本，改行为是测不到的。
 */
class BrailleDisplayViewModelTest {

    private val tableKey = "braille_table"
    private val preferredKey = "preferred_braille_codes"

    private val keys = BrailleSettingsKeys(
        table = tableKey,
        preferredCodes = preferredKey,
        booleanDefaults = mapOf(
            BrailleDisplayLogic.KEY_WORD_WRAP to true,
            BrailleDisplayLogic.KEY_CURSOR_DOTS to false,
            BrailleDisplayLogic.KEY_CONTRACTED_MODE to false,
            BrailleDisplayLogic.KEY_CHINESE_SEGMENTATION to true,
        ),
    )

    private class FakeStore : BrailleSettingsStore {
        val values = mutableMapOf<String, Any>()
        val writes = mutableListOf<Pair<String, Any?>>()

        override fun getString(key: String): String? = values[key] as? String

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String): Set<String>? = values[key] as? Set<String>

        override fun contains(key: String): Boolean = values.containsKey(key)

        override fun getBoolean(key: String, default: Boolean): Boolean =
            values[key] as? Boolean ?: default

        override fun putString(key: String, value: String) {
            values[key] = value
            writes.add(key to value)
        }

        override fun putStringSet(key: String, value: Set<String>) {
            values[key] = value
            writes.add(key to value)
        }

        override fun putBoolean(key: String, value: Boolean) {
            values[key] = value
            writes.add(key to value)
        }
    }

    private fun code(id: String, eightDot: Boolean = false) = BrailleCodeCatalog.Code(
        id = id,
        label = id,
        summary = "",
        eightDot = eightDot,
        supportsContracted = false,
    )

    private val zhG1 = "zh-cn-cbs-g1.ctb"
    private val zhG2 = "zh-cn-cbs-g2.ctb"
    private val enG1 = "en-us-g1.ctb"
    private val enG2 = "en-us-g2.ctb"

    private val groups = listOf(
        BrailleCodeCatalog.CodeGroup(
            language = "zh",
            title = "中文",
            codes = listOf(code(zhG1), code(zhG2)),
        ),
        BrailleCodeCatalog.CodeGroup(
            language = "en",
            title = "English",
            codes = listOf(code(enG1), code(enG2)),
        ),
    )

    private fun vm(store: FakeStore) = BrailleDisplayViewModel(store, keys)

    @Test
    fun loadsResourceDefaultsWhenStoreIsEmpty() {
        val store = FakeStore()
        val state = vm(store).state.value

        assertEquals(BrailleDisplayLogic.DEFAULT_TABLE, state.currentTable)
        assertTrue(state.enabledCodes.isEmpty())
        assertTrue(state.wordWrap)
        assertFalse(state.cursorDots)
        assertFalse(state.contractedMode)
        assertTrue(state.chineseSegmentation)
        assertFalse(state.showCurrentDialog)
        // 只读不写：首屏不该因为「读了一次」就把偏好落盘。
        assertTrue(store.writes.isEmpty())
    }

    @Test
    fun setBooleanUpdatesStateAndPersists() {
        val store = FakeStore()
        val vm = vm(store)

        vm.setBoolean(BrailleDisplayLogic.KEY_CURSOR_DOTS, true)
        vm.setBoolean(BrailleDisplayLogic.KEY_WORD_WRAP, false)

        assertTrue(vm.state.value.cursorDots)
        assertFalse(vm.state.value.wordWrap)
        assertEquals(true, store.values[BrailleDisplayLogic.KEY_CURSOR_DOTS])
        assertEquals(false, store.values[BrailleDisplayLogic.KEY_WORD_WRAP])
    }

    @Test
    fun unknownBooleanKeyIsIgnored() {
        val store = FakeStore()
        val vm = vm(store)

        vm.setBoolean("unknown_key", true)

        assertTrue(store.writes.isEmpty())
    }

    @Test
    fun chooseCurrentPersistsTableKeyAndDismissesDialog() {
        val store = FakeStore()
        val vm = vm(store)
        vm.openCurrentDialog()

        vm.chooseCurrent(zhG2)

        assertEquals(zhG2, vm.state.value.currentTable)
        assertFalse(vm.state.value.showCurrentDialog)
        assertEquals(zhG2, store.values[tableKey])
    }

    @Test
    fun choosingSameCurrentCodeDoesNotTouchPreferences() {
        val store = FakeStore()
        store.values[tableKey] = zhG1
        val vm = vm(store)

        vm.chooseCurrent(zhG1)

        // 值没变就不写：避免一次点击触发服务端的偏好监听、白换一次表。
        assertTrue(store.writes.isEmpty())
    }

    @Test
    fun atLeastOneCodeStaysEnabled() {
        val store = FakeStore()
        store.values[preferredKey] = setOf(zhG1)
        val vm = vm(store)

        vm.toggleCode(zhG1, false, groups, "zh")

        assertEquals(setOf(zhG1), vm.state.value.enabledCodes)
        assertTrue(store.writes.isEmpty())
    }

    @Test
    fun uncheckingCurrentCodeReselectsAnotherInSameLanguage() {
        val store = FakeStore()
        store.values[preferredKey] = setOf(zhG1, zhG2)
        store.values[tableKey] = zhG1
        val vm = vm(store)

        vm.toggleCode(zhG1, false, groups, "zh")

        assertEquals(setOf(zhG2), vm.state.value.enabledCodes)
        assertEquals(zhG2, vm.state.value.currentTable)
        assertEquals(setOf(zhG2), store.values[preferredKey])
        assertEquals(zhG2, store.values[tableKey])
    }

    @Test
    fun enablingExtraCodeDoesNotChangeCurrentCode() {
        val store = FakeStore()
        store.values[preferredKey] = setOf(zhG1)
        store.values[tableKey] = zhG1
        val vm = vm(store)

        vm.toggleCode(zhG2, true, groups, "zh")

        assertEquals(setOf(zhG1, zhG2), vm.state.value.enabledCodes)
        assertEquals(zhG1, vm.state.value.currentTable)
        // 只写首选池，不动当前方案。
        assertEquals(listOf(preferredKey), store.writes.map { it.first })
        assertEquals(zhG1, store.values[tableKey])
    }

    @Test
    fun firstRunMergesStoredCurrentCodeExactlyOnce() {
        // 老用户：没有首选池，但存了英文表（中文系统里特意选的）。
        val store = FakeStore()
        store.values[tableKey] = enG1
        val vm = vm(store)

        vm.reconcileCodes(groups, "zh")

        // 首次迁移：英文表并入池，当前方案不被换掉——否则老用户的打字规则会静默变化。
        val firstPool = setOf(zhG1, zhG2, enG1)
        assertEquals(firstPool, vm.state.value.enabledCodes)
        assertEquals(enG1, vm.state.value.currentTable)

        // 第二次对账（池已存在）不再并入任何东西，只做过滤。
        vm.reconcileCodes(groups, "zh")
        assertEquals(firstPool, vm.state.value.enabledCodes)
        assertEquals(enG1, vm.state.value.currentTable)
    }

    @Test
    fun outOfPoolCurrentCodeIsCorrectedAndPersisted() {
        // 首选池存在但当前方案在池外（被外部写坏 / 老版本残留）。
        val store = FakeStore()
        store.values[preferredKey] = setOf(zhG2)
        store.values[tableKey] = enG1
        val vm = vm(store)

        vm.reconcileCodes(groups, "zh")

        assertEquals(setOf(zhG2), vm.state.value.enabledCodes)
        assertEquals(zhG2, vm.state.value.currentTable)
        assertEquals(zhG2, store.values[tableKey])
    }

    @Test
    fun emptyPoolFallsBackToSystemLanguageCodesAndPersistsOnce() {
        val store = FakeStore()
        val vm = vm(store)

        vm.reconcileCodes(groups, "zh")

        // 空池退化为「系统语言的全部方案」（与谷歌只给默认码 1 个是已知偏差）。
        assertEquals(setOf(zhG1, zhG2), vm.state.value.enabledCodes)
        assertEquals(setOf(zhG1, zhG2), store.values[preferredKey])
        // 当前方案是「自动」，不需要纠正，也就没有第二笔写入。
        assertEquals(
            listOf(preferredKey),
            store.writes.map { it.first }.distinct(),
        )
    }

    @Test
    fun reconcileDoesNotWriteWhenNothingChanges() {
        val store = FakeStore()
        store.values[preferredKey] = setOf(zhG1)
        store.values[tableKey] = zhG1
        val vm = vm(store)
        store.writes.clear()

        vm.reconcileCodes(groups, "zh")

        // 无变化不写偏好：否则会和服务的 OnSharedPreferenceChangeListener 形成回环。
        assertTrue(store.writes.isEmpty())
    }

    @Test
    fun reconcileIgnoresEmptyTableList() {
        val store = FakeStore()
        val vm = vm(store)

        vm.reconcileCodes(emptyList(), "zh")

        assertTrue(store.writes.isEmpty())
    }

    @Test
    fun refreshFromStorePicksUpExternalWriteButKeepsDialog() {
        val store = FakeStore()
        store.values[preferredKey] = setOf(zhG1)
        val vm = vm(store)
        vm.openCurrentDialog()

        // 模拟服务端（另一个进程的 key 写入）改了当前方案。
        store.values[tableKey] = zhG2
        vm.refreshFromStore()

        assertEquals(zhG2, vm.state.value.currentTable)
        assertTrue(vm.state.value.showCurrentDialog)
    }
}
