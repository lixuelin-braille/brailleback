/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import com.googlecode.eyesfree.utils.SettingsStore
import android.util.Log
import com.googlecode.eyesfree.braille.translate.BrailleTranslator
import com.googlecode.eyesfree.braille.translate.TableInfo
import com.googlecode.eyesfree.braille.translate.TranslatorClient
import com.googlecode.eyesfree.utils.LogUtils
import java.util.Collections
import java.util.Locale

/**
 * Keeps track of the current braille translator as determined by the current
 * user locale and settings.
 *
 * Threading: This class is not thread safe, except [getTranslator] and
 * [getUncontractedTranslator] may be called from any thread.  Note that the
 * result from calling those functions may not be consistent while the selected
 * tables change.  This shouldn't be a problem if a caller uses the callback
 * API to retranslate the display content on configuration changes.
 */
class TranslatorManager(context: Context) :
    SharedPreferences.OnSharedPreferenceChangeListener {

    /** Callback interface to be invoked when one or both of the current
     * translation tables have changed. */
    interface OnTablesChangedListener {
        /** Called when one or both translation tables have changed, including
         * when the translation service is initialized so that tables are
         * available. */
        fun onTablesChanged()
    }

    private val mPrefBrailleTableKey: String
    private val mLegacySixDotBrailleTableKey: String
    private val mPrefContractedModeKey: String
    private val mPrefPreferredCodesKey: String
    private val mTableValueDefault: String

    private val mSharedPreferences: SharedPreferences
    private val mTranslatorClient: TranslatorClient
    @Volatile
    private var mTranslator: BrailleTranslator? = null
    @Volatile
    private var mUncontractedTranslator: BrailleTranslator? = null
    private val mOnTablesChangedListeners = ArrayList<OnTablesChangedListener>()
    private var mTables: List<TableInfo>? = null
    private val mLocalesToTables = HashMap<Locale, ArrayList<TableInfo>>()
    private var mLocale: Locale? = null
    private var mClientInitialized = false
    /** 表清单首次就绪后是否已广播过一次清单变更；只广播一次，避免界面被重复刷新。 */
    private var mTablesListNotified = false
    /** 默认表选择记忆（翻译器与界面标签共用同一口径）；偏好/语言变化时失效。 */
    private var mDefaultTableSelector: DefaultTableSelector<TableInfo>? = null

    init {
        mPrefBrailleTableKey = context.getString(R.string.pref_braille_table_key)
        mLegacySixDotBrailleTableKey =
            context.getString(R.string.pref_six_dot_braille_table_key)
        mPrefContractedModeKey = context.getString(R.string.pref_braille_contracted_mode_key)
        mPrefPreferredCodesKey = context.getString(R.string.pref_preferred_codes_key)
        mTableValueDefault = context.getString(R.string.table_value_default)

        mSharedPreferences = SettingsStore.get(context)
        migrateLegacyTablePreference()
        mSharedPreferences.registerOnSharedPreferenceChangeListener(this)
        mTranslatorClient = TranslatorClient(
            context,
            object : TranslatorClient.OnInitListener {
                override fun onInit(status: Int) {
                    if (status != TranslatorClient.SUCCESS) {
                        LogUtils.log(this@TranslatorManager, Log.ERROR,
                            "Couldn't initialize braille translator")
                        return
                    }
                    mClientInitialized = true
                    onConfigurationChanged(context.resources.configuration)
                }
            })
    }

    /** Frees resources used by this instance, most notably the connection to
     * the translation service. */
    fun shutdown() {
        mSharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
        mTranslatorClient.destroy()
        mClientInitialized = false
        mTablesListNotified = false
        mDefaultTableSelector = null
        mTranslator = null
        mUncontractedTranslator = null
    }

    /** Adds a listener to be called when the translation tables have changed. */
    fun addOnTablesChangedListener(listener: OnTablesChangedListener) {
        mOnTablesChangedListeners.add(listener)
    }

    /** Removes a table change listener. */
    fun removeOnTablesChangedListener(listener: OnTablesChangedListener) {
        mOnTablesChangedListeners.remove(listener)
    }

    /** Returns the main braille translator to use. */
    fun getTranslator(): BrailleTranslator? {
        return mTranslator
    }

    /** Returns a translator to be used when contractions shouldn't be used.
     * This is for use when editing text. */
    fun getUncontractedTranslator(): BrailleTranslator? {
        return mUncontractedTranslator
    }

    /** Returns the translator client used by this instance. */
    fun getTranslatorClient(): TranslatorClient {
        return mTranslatorClient
    }

    /** Returns all braille tables for the same locale as `tableInfo`. */
    fun getRelatedTables(tableInfo: TableInfo): List<TableInfo> {
        return Collections.unmodifiableList(mLocalesToTables[tableInfo.locale])
    }

    /** Reevaluates what should be the current translation table based on the
     * new configuration. */
    fun onConfigurationChanged(newConfiguration: Configuration) {
        if (!mClientInitialized || newConfiguration.locale == mLocale) {
            return
        }
        mLocale = newConfiguration.locale
        mTables = mTranslatorClient.getTables()
        updateLocalesToTables()
        invalidateDefaultTableCache()
        notifyTablesListReady()
        updateTranslators()
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        if (mClientInitialized && key != null && isTablePreferenceKey(key)) {
            invalidateDefaultTableCache()
            updateTranslators()
        }
    }

    private fun updateLocalesToTables() {
        mLocalesToTables.clear()
        mTables?.forEach { info ->
            val locale = info.locale
            val list = mLocalesToTables.getOrPut(locale) { ArrayList() }
            list.add(info)
        }
    }

    private fun updateTranslators() {
        val eightDot = isEightDotBrailleSelected()
        val newTranslator = findBrailleTranslator(true /*fallback*/)
        if (newTranslator == null) {
            LogUtils.log(this, Log.ERROR,
                "Couldn't find braille translator for %s", mLocale)
            // 翻译器造不出不代表清单没就绪：表清单已拿到时仍要广播一次，
            // 否则界面永远停在空清单；清单通知与翻译器可用性解耦。
            notifyTablesListReady()
            return
        }
        // 无缩写（uncontracted）翻译器用于编辑/搜索的逐键反译，必须是 grade 最小的表：
        // 六点模式下取六点 grade 最小表（原逻辑强制八点表——中文等六点语种没有八点表，
        // 会静默回退到英文表，逐键反译全错）。上游 TODO 称需改 IME，但本项目 IME
        // 已改用 getTranslator()（文学盲文），不再依赖八点表，故可安全消项。
        // 八点模式保留旧路径作后备。
        var newUncontractedTranslator: BrailleTranslator? = null
        if (!eightDot) {
            val info = findDefaultTableInfo(false /*eightDot*/, false /*prefer uncontracted*/)
            val id = info?.id
            if (id != null) {
                newUncontractedTranslator = mTranslatorClient.getTranslator(id)
            }
            LogUtils.log(this, Log.DEBUG,
                "Uncontracted table for %s: %s", mLocale, info?.id)
        }
        if (newUncontractedTranslator == null) {
            newUncontractedTranslator = findBrailleTranslator(true /*fallback*/)
        }
        if (newUncontractedTranslator == null) {
            newUncontractedTranslator = newTranslator
        }
        val changed = newTranslator != mTranslator
            || newUncontractedTranslator != mUncontractedTranslator
        mTranslator = newTranslator
        mUncontractedTranslator = newUncontractedTranslator
        if (changed) {
            callOnTablesChangedListeners()
        }
    }

    fun findDefaultTableInfo(eightDot: Boolean): TableInfo? {
        return findDefaultTableInfo(eightDot, isContractedModeSelected())
    }

    /**
     * Finds the default table, preferring a contracted (grade 2) table when
     * `preferContracted` is set, or an uncontracted (grade 1) table otherwise.
     *
     * 与翻译器走同一套候选构造、探测函数与选择记忆：返回第一张能加载的表；
     * 全部候选都加载失败时，退回目录推断的 rank 最高表作为显示兜底（不代表
     * 该表真能加载，翻译器仍可能为 null）。
     */
    fun findDefaultTableInfo(eightDot: Boolean, preferContracted: Boolean): TableInfo? {
        if (mTables == null) {
            LogUtils.log(this, Log.ERROR, "Couldn't get translation tables")
            return null
        }
        return selectDefaultTable(eightDot, preferContracted, includeFallback = true)
    }

    private fun getTranslatorFromPreferences(): BrailleTranslator? {
        val value = mSharedPreferences.getString(mPrefBrailleTableKey, mTableValueDefault)
        if (mTableValueDefault == value) {
            return null
        }
        return mTranslatorClient.getTranslator(value ?: return null)
    }

    /**
     * Finds a translation table for the `userLocale`, falling back on US
     * English if no match is found.
     */
    private fun findBrailleTranslator(fallback: Boolean): BrailleTranslator? {
        // 用户显式选过的表优先级最高；加载失败时再进入自动候选逐个探测。
        getTranslatorFromPreferences()?.let { return it }
        val info = selectDefaultTable(false, isContractedModeSelected(), fallback)
            ?: return null
        return mTranslatorClient.getTranslator(info.id ?: return null)
    }

    /**
     * 翻译器与界面标签共用的默认表口径：同一份候选构造、同一个探测函数、同一份
     * 选择记忆与探测上界，保证翻译器实际用的表和标签念的表一致，不会分裂成两张。
     */
    private fun selectDefaultTable(
        eightDot: Boolean,
        preferContracted: Boolean,
        includeFallback: Boolean
    ): TableInfo? {
        val candidates = buildDefaultTableCandidates(eightDot, preferContracted, includeFallback)
        if (candidates.isEmpty()) {
            return null
        }
        val cacheKey = Triple(eightDot, preferContracted, mLocale?.toString())
        val selector = defaultTableSelector()
        val result = selector.select(
            candidates = candidates,
            key = cacheKey,
            catalogFallback = {
                val fallback = highestRankedCatalogTable(eightDot, preferContracted)
                if (fallback != null) {
                    LogUtils.log(this, Log.ERROR,
                        "No braille table loadable; label falls back to catalog inference: id=%s",
                        fallback.id)
                }
                fallback
            }
        )
        selector.lastFailed.forEach { info ->
            LogUtils.log(this, Log.WARN,
                "Skipping braille table that failed to load: id=%s, variant=%s",
                info.id, info.variant)
        }
        return result
    }

    private fun defaultTableSelector(): DefaultTableSelector<TableInfo> {
        mDefaultTableSelector?.let { return it }
        val selector = DefaultTableSelector<TableInfo>(
            probe = { info ->
                val id = info.id
                id != null && mTranslatorClient.getTranslator(id) != null
            }
        )
        mDefaultTableSelector = selector
        return selector
    }

    /**
     * 清空默认表选择记忆。触发时机：
     * - 用户改了盲文表偏好或缩写模式（[onSharedPreferenceChanged]）；
     * - 系统语言 / 配置变化（[onConfigurationChanged]）；
     * - 调用方需要强制重算时直接调用本方法。
     */
    private fun invalidateDefaultTableCache() {
        mDefaultTableSelector?.invalidate()
    }

    /**
     * 按优先级排出自动模式下要逐个探测的候选表（去重后保持顺序）：
     * 当前语言、指定点数的表按匹配度与缩写模式对应 grade 排序；允许兜底时再
     * 追加英文（US）表。用户显式选择的表已在 [findBrailleTranslator] 最前面
     * 单独试过，这里不再重复。
     */
    private fun buildDefaultTableCandidates(
        eightDot: Boolean,
        preferContracted: Boolean,
        includeFallback: Boolean
    ): List<TableInfo> {
        val tables = mTables ?: return emptyList()
        val gradeComparator = if (preferContracted) {
            compareByDescending<TableInfo> { it.grade }
        } else {
            compareBy { it.grade }
        }
        val comparator = compareByDescending<TableInfo> { matchRank(it.locale, mLocale) }
            .then(gradeComparator)
        val localeTables = tables
            .filter { it.isEightDot == eightDot && it.locale.language == mLocale?.language }
            .sortedWith(comparator)
        val ordered = ArrayList<TableInfo>(localeTables)
        if (includeFallback) {
            findFallbackTableInfo()?.let { ordered.add(it) }
        }
        return ordered.distinctBy { it.id }
    }

    /** 目录推断的 rank 最高表（不做加载校验）；仅用于全部候选都加载失败时的显示兜底。 */
    private fun highestRankedCatalogTable(
        eightDot: Boolean,
        preferContracted: Boolean
    ): TableInfo? {
        var best: TableInfo? = null
        mTables?.forEach { info ->
            if (eightDot != info.isEightDot) {
                return@forEach
            }
            if (betterTable(info, best, preferContracted)) {
                best = info
            }
        }
        return best
    }

    private fun findFallbackTableInfo(): TableInfo? {
        mTables?.forEach { table ->
            if (table.locale == Locale.US) {
                return table
            }
        }
        return null
    }

    private fun betterTable(first: TableInfo, second: TableInfo?, preferContracted: Boolean): Boolean {
        if (second == null) {
            return true
        }
        val firstLocale = first.locale
        val secondLocale = second.locale
        val firstRank = matchRank(firstLocale, mLocale)
        val secondRank = matchRank(secondLocale, mLocale)
        if (firstRank != secondRank) {
            return firstRank > secondRank
        }
        // Same locale match: prefer grade according to contracted mode.
        return if (preferContracted) {
            first.grade > second.grade
        } else {
            first.grade < second.grade
        }
    }

    private fun matchRank(first: Locale, second: Locale?): Int {
        var ret = if (first.language == second?.language) 1 else 0
        if (ret > 0) {
            ret += if (first.country == second?.country) 1 else 0
            if (ret > 1) {
                ret += if (first.variant == second?.variant) 1 else 0
            }
        }
        return ret
    }

    /**
     * 表清单首次非空时无条件广播一次。清单能不能列出，取决于服务有没有把
     * 目录返回给我们，与当前能否造出翻译器是两件事；旧逻辑在翻译器失败时
     * 直接 return，吞掉了这次通知，界面就再也收不到清单。
     *
     * 线程沿用既有回调通道：本方法直接同步遍历监听器，由各监听器自行投递到
     * 主线程（界面监听器内部已 runOnUiThread），这里不另开第二条 Handler。
     */
    private fun notifyTablesListReady() {
        if (mTablesListNotified) return
        if (mTables.isNullOrEmpty()) return
        mTablesListNotified = true
        callOnTablesChangedListeners()
    }

    private fun callOnTablesChangedListeners() {
        // 快照遍历：监听器回调里可能把自己 remove 掉（如阅读器的
        // 「翻译器就绪后自动续翻」一次性监听器），直接遍历原列表
        // 会 ConcurrentModificationException 导致阅读器崩溃。
        for (listener in mOnTablesChangedListeners.toList()) {
            listener.onTablesChanged()
        }
    }

    private fun isTablePreferenceKey(key: String): Boolean {
        return mPrefBrailleTableKey == key
            || mPrefContractedModeKey == key
    }

    /** Returns whether contracted (grade 2) braille is selected in the
     * preferences. Defaults to false (uncontracted / grade 1). */
    private fun isContractedModeSelected(): Boolean {
        return mSharedPreferences.getBoolean(mPrefContractedModeKey, false)
    }

    /** 当前选中的表；选的是“默认（跟随语言）”时返回 null。 */
    private fun selectedTableInfo(): TableInfo? {
        val value = mSharedPreferences.getString(mPrefBrailleTableKey, mTableValueDefault)
        if (value == null || value == mTableValueDefault) {
            return null
        }
        return mTables?.firstOrNull { it.id == value }
    }

    /** 当前选中的是不是八点（计算机）表。 */
    private fun isEightDotBrailleSelected(): Boolean =
        selectedTableInfo()?.isEightDot == true

    /**
     * 在六点与八点（计算机）盲文方案之间切换，供 CMD_TOGGLE_BRAILLE_GRADE 使用。
     *
     * 多选方案模型：只在「当前语言 + 已启用方案」里找目标，绝不跨语言。
     * 旧实现用 `findDefaultTableInfo(!eightDot)` 全局按 matchRank 挑表，中文环境下
     * 可能跳到英文八点表，再切回也回不到原中文表。找不到目标时保持原状。
     *
     * 返回切换后是否为八点。
     */
    fun toggleBrailleDots(): Boolean {
        val tables = mTables ?: return false
        val currentId = mSharedPreferences.getString(mPrefBrailleTableKey, mTableValueDefault)
        val currentInfo = tables.firstOrNull { it.id == currentId }
        val wasEightDot = currentInfo?.isEightDot == true
        // 未初始化过多选列表时按「全部启用」处理，兼容升级前的用户。
        val enabled = mSharedPreferences.getStringSet(mPrefPreferredCodesKey, null)
            ?.takeIf { it.isNotEmpty() }
            ?: tables.mapNotNull { it.id }.toSet()
        val language = currentInfo?.locale?.language
            ?: mLocale?.language
            ?: return wasEightDot
        val target = tables.firstOrNull { info ->
            info.locale.language.equals(language, ignoreCase = true) &&
                info.isEightDot != wasEightDot &&
                info.id != null && info.id in enabled
        } ?: return wasEightDot
        mSharedPreferences.edit()
            .putString(mPrefBrailleTableKey, target.id)
            .apply()
        return target.isEightDot
    }

    /** 老版本的“书面盲文表格”并入统一表：只在用户改过表时才迁移。 */
    private fun migrateLegacyTablePreference() {
        if (mSharedPreferences.contains(mPrefBrailleTableKey)) {
            return
        }
        val legacy = mSharedPreferences.getString(
            mLegacySixDotBrailleTableKey, mTableValueDefault)
        if (legacy != null && legacy != mTableValueDefault) {
            mSharedPreferences.edit().putString(mPrefBrailleTableKey, legacy).apply()
        }
    }
}
