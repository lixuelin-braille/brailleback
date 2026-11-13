/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.preference.PreferenceManager
import android.util.Log
import com.googlecode.eyesfree.braille.translate.BrailleTranslator
import com.googlecode.eyesfree.braille.translate.TableInfo
import com.googlecode.eyesfree.braille.translate.TranslatorClient
import com.googlecode.eyesfree.utils.LogUtils
import java.util.ArrayList
import java.util.Collections
import java.util.HashMap
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

    init {
        mPrefBrailleTableKey = context.getString(R.string.pref_braille_table_key)
        mLegacySixDotBrailleTableKey =
            context.getString(R.string.pref_six_dot_braille_table_key)
        mPrefContractedModeKey = context.getString(R.string.pref_braille_contracted_mode_key)
        mTableValueDefault = context.getString(R.string.table_value_default)

        mSharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
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
        updateTranslators()
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        if (mClientInitialized && key != null && isTablePreferenceKey(key)) {
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
     */
    fun findDefaultTableInfo(eightDot: Boolean, preferContracted: Boolean): TableInfo? {
        if (mTables == null) {
            LogUtils.log(this, Log.ERROR, "Couldn't get translation tables")
            return null
        }
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
        var translator = getTranslatorFromPreferences()
        if (translator != null) {
            return translator
        }
        var info = findDefaultTableInfo(false)
        if (info == null && fallback) {
            info = findFallbackTableInfo()
        }
        if (info != null) {
            translator = mTranslatorClient.getTranslator(info.id ?: return null)
        }
        return translator
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
     * 在六点与八点（计算机）盲文表之间切换，供 CMD_TOGGLE_BRAILLE_GRADE 使用。
     *
     * 返回切换后是否为八点；当前语言没有八点表时保持原状。
     */
    fun toggleBrailleDots(): Boolean {
        val eightDot = isEightDotBrailleSelected()
        val target = findDefaultTableInfo(!eightDot)
        if (target == null) {
            return eightDot
        }
        mSharedPreferences.edit()
            .putString(mPrefBrailleTableKey, target.id)
            .apply()
        return !eightDot
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
