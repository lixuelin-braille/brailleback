/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback.compose

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 「盲文显示」设置页的状态与持久化。
 *
 * 为什么要有这一层：改造前这些状态是一堆 `mutableStateOf` 字段 + 散在 Activity 里的
 * `prefs.edit().put...apply()`，于是「读取口径」和「写入口径」可能不一致——最典型的是
 * 首选池：界面按归一化后的 N 个算，偏好里存的却是另一份。把读、写、对账收到一个
 * 有单一入口的对象里，界面只消费 [state]。
 *
 * 与 Android 的耦合只剩两处：[BrailleSettingsStore]（偏好读写窄接口）和键名（由
 * Activity 从资源解析后传入，键本身就是 ABI，见 SettingsStructureContractTest）。
 * 因此这个类可以在纯 JVM 测试里用内存 store 直接把状态和落盘行为验一遍。
 */
class BrailleDisplayViewModel(
    private val store: BrailleSettingsStore,
    private val keys: BrailleSettingsKeys,
) : ViewModel() {

    private val _state = MutableStateFlow(load())
    val state: StateFlow<BrailleSettingsState> = _state.asStateFlow()

    /** 服务改了偏好、或收到刷新广播时，重新按当前偏好重算状态；弹窗可见性保留。 */
    fun refreshFromStore() {
        _state.value = load().copy(showCurrentDialog = _state.value.showCurrentDialog)
    }

    fun openCurrentDialog() {
        _state.value = _state.value.copy(showCurrentDialog = true)
    }

    fun dismissCurrentDialog() {
        _state.value = _state.value.copy(showCurrentDialog = false)
    }

    /** 选定「当前方案」；[BrailleTableCatalog.AUTO_VALUE] 表示跟随系统语言。 */
    fun chooseCurrent(tableId: String) {
        val s = _state.value
        if (s.currentTable != tableId) {
            store.putString(keys.table, tableId)
        }
        _state.value = s.copy(currentTable = tableId, showCurrentDialog = false)
    }

    /** 四个开关都通过同一个入口写：先落盘再更新状态，读屏不会读到中间态。 */
    fun setBoolean(key: String, value: Boolean) {
        val s = _state.value
        val next = when (key) {
            BrailleDisplayLogic.KEY_WORD_WRAP -> s.copy(wordWrap = value)
            BrailleDisplayLogic.KEY_CURSOR_DOTS -> s.copy(cursorDots = value)
            BrailleDisplayLogic.KEY_CONTRACTED_MODE -> s.copy(contractedMode = value)
            BrailleDisplayLogic.KEY_CHINESE_SEGMENTATION -> s.copy(chineseSegmentation = value)
            else -> return
        }
        store.putBoolean(key, value)
        _state.value = next
    }

    /**
     * 勾选/取消一个方案。至少保留一个启用方案；取消当前方案时自动另选一个，
     * 保证服务始终有表可用。启用方案本身不改变「当前正在使用」的那个。
     */
    fun toggleCode(
        id: String,
        checked: Boolean,
        codeGroups: List<BrailleCodeCatalog.CodeGroup>,
        systemLanguage: String,
    ) {
        val s = _state.value
        val normalized = BrailleCodeCatalog.normalize(s.enabledCodes, codeGroups, systemLanguage)
        if (!checked && !BrailleCodeCatalog.canDisable(normalized, id)) {
            return
        }
        val next = BrailleCodeCatalog.toggle(normalized, id)
        store.putStringSet(keys.preferredCodes, next)
        if (checked) {
            _state.value = s.copy(enabledCodes = next)
            return
        }
        val language = BrailleCodeCatalog.languageOfCode(codeGroups, s.currentTable)
            ?: systemLanguage
        val corrected = BrailleCodeCatalog.resolveCurrent(
            next, s.currentTable, codeGroups, language
        )
        if (corrected != s.currentTable) {
            store.putString(keys.table, corrected)
        }
        _state.value = s.copy(enabledCodes = next, currentTable = corrected)
    }

    /**
     * 表清单就绪后的对账，照谷歌 `readCurrentActiveInputCodeAndCorrect`：
     *
     * 1) 归一化后的首选池落盘一次。之前只把归一化结果拿来显示，服务端读到的仍是
     *    空集并退化成「全部启用」，界面「已启用 N 个」与服务实际可选范围是两套口径；
     * 2) 当前方案必须在池内。存的是池外的表（老用户、被外部写坏）就纠正到同语言
     *    已启用第一项，并写回 `braille_table`——那是服务监听的 key，会触发换表。
     *
     * 唯一的例外是**首次迁移**：老用户存好的表（比如中文系统里特意选的英文表）
     * 并入池，只做这一次。否则第一次打开设置就会被静默换成系统语言的码，盲人用户
     * 会发现自己打字规则突然变了。池已经存在时不再并入，严格按谷歌「池管当前码」。
     * 只在 [codeGroups] 非空时跑；无变化不写偏好，避免与服务的偏好监听形成回环。
     */
    fun reconcileCodes(
        codeGroups: List<BrailleCodeCatalog.CodeGroup>,
        systemLanguage: String,
    ) {
        if (codeGroups.isEmpty()) {
            return
        }
        val s = _state.value
        val firstRun = !store.contains(keys.preferredCodes)
        var pool = BrailleCodeCatalog.normalize(s.enabledCodes, codeGroups, systemLanguage)
        if (firstRun && s.currentTable != BrailleTableCatalog.AUTO_VALUE &&
            codeGroups.any { group -> group.codes.any { it.id == s.currentTable } }
        ) {
            pool = pool + s.currentTable
        }
        val language = BrailleCodeCatalog.languageOfCode(codeGroups, s.currentTable)
            ?: systemLanguage
        val corrected = BrailleCodeCatalog.resolveCurrent(
            pool, s.currentTable, codeGroups, language
        )
        if (pool != s.enabledCodes) {
            store.putStringSet(keys.preferredCodes, pool)
        }
        if (corrected != s.currentTable) {
            store.putString(keys.table, corrected)
        }
        _state.value = s.copy(enabledCodes = pool, currentTable = corrected)
    }

    private fun load(): BrailleSettingsState {
        val defaults = keys.booleanDefaults
        fun flag(key: String) = store.getBoolean(key, defaults.getValue(key))
        return BrailleSettingsState(
            currentTable = BrailleDisplayLogic.initialTable(store.getString(keys.table)),
            enabledCodes = store.getStringSet(keys.preferredCodes).orEmpty(),
            wordWrap = flag(BrailleDisplayLogic.KEY_WORD_WRAP),
            cursorDots = flag(BrailleDisplayLogic.KEY_CURSOR_DOTS),
            contractedMode = flag(BrailleDisplayLogic.KEY_CONTRACTED_MODE),
            chineseSegmentation = flag(BrailleDisplayLogic.KEY_CHINESE_SEGMENTATION),
            showCurrentDialog = false,
        )
    }

    companion object {
        fun factory(
            store: BrailleSettingsStore,
            keys: BrailleSettingsKeys,
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                BrailleDisplayViewModel(store, keys) as T
        }
    }
}

/** 设置页状态；界面只读，写入一律走 [BrailleDisplayViewModel] 的方法。 */
data class BrailleSettingsState(
    val currentTable: String,
    val enabledCodes: Set<String>,
    val wordWrap: Boolean,
    val cursorDots: Boolean,
    val contractedMode: Boolean,
    val chineseSegmentation: Boolean,
    val showCurrentDialog: Boolean,
)

/**
 * 偏好键名与默认值。键名从资源里取（`R.string.pref_*`），默认值从 `R.bool.*` 取，
 * 这样键改名或默认值被改，仍然是同一处声明，不会出现两套口径。
 */
data class BrailleSettingsKeys(
    val table: String,
    val preferredCodes: String,
    /** [BrailleDisplayLogic.KEY_WORD_WRAP] 等四个键的默认值。 */
    val booleanDefaults: Map<String, Boolean>,
)

/** 偏好读写窄接口：ViewModel 因此不依赖 SharedPreferences，可用纯 JVM 测试替换。 */
interface BrailleSettingsStore {
    fun getString(key: String): String?
    fun getStringSet(key: String): Set<String>?
    fun contains(key: String): Boolean
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putString(key: String, value: String)
    fun putStringSet(key: String, value: Set<String>)
    fun putBoolean(key: String, value: Boolean)
}

/** 生产实现：写回一律 `apply()`（内存即时可见、磁盘异步），与原有行为一致。 */
class SharedPreferencesBrailleSettingsStore(
    private val prefs: SharedPreferences,
) : BrailleSettingsStore {
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun getStringSet(key: String): Set<String>? = prefs.getStringSet(key, null)
    override fun contains(key: String): Boolean = prefs.contains(key)
    override fun getBoolean(key: String, default: Boolean): Boolean =
        prefs.getBoolean(key, default)

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun putStringSet(key: String, value: Set<String>) {
        prefs.edit().putStringSet(key, value).apply()
    }

    override fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }
}
