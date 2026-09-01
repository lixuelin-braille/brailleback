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

    private val mPrefBrailleTypeKey: String
    private val mPrefBrailleTypeSixDotValue: String
    private val mPrefBrailleTypeEightDotValue: String
    private val mPrefBrailleTypeDefault: String
    private val mPrefSixDotBrailleTableKey: String
    private val mPrefEightDotBrailleTableKey: String
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
        mPrefBrailleTypeKey = context.getString(R.string.pref_braille_type_key)
        mPrefBrailleTypeSixDotValue = context.getString(R.string.pref_braille_type_six_dot_value)
        mPrefBrailleTypeEightDotValue = context.getString(R.string.pref_braille_type_eight_dot_value)
        mPrefBrailleTypeDefault = context.getString(R.string.pref_braille_type_default)
        mPrefSixDotBrailleTableKey = context.getString(R.string.pref_six_dot_braille_table_key)
        mPrefEightDotBrailleTableKey = context.getString(R.string.pref_eight_dot_braille_table_key)
        mPrefContractedModeKey = context.getString(R.string.pref_braille_contracted_mode_key)
        mTableValueDefault = context.getString(R.string.table_value_default)

        mSharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
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
        val newTranslator = findBrailleTranslator(eightDot, true /*fallback*/)
        if (newTranslator == null) {
            LogUtils.log(this, Log.ERROR,
                "Couldn't find braille translator for %s", mLocale)
            return
        }
        // TODO: For six dot braille, get the table with smallest grade.
        // This requires IME changes.
        var newUncontractedTranslator = findBrailleTranslator(true /*eightDot*/, true /*fallback*/)
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

    private fun getTranslatorFromPreferences(eightDot: Boolean): BrailleTranslator? {
        val key = if (eightDot) mPrefEightDotBrailleTableKey else mPrefSixDotBrailleTableKey
        val value = mSharedPreferences.getString(key, mTableValueDefault)
        if (mTableValueDefault == value) {
            return null
        }
        return mTranslatorClient.getTranslator(value)
    }

    /**
     * Finds a translation table for the `userLocale`, falling back on US
     * English if no match is found.
     */
    private fun findBrailleTranslator(eightDot: Boolean, fallback: Boolean): BrailleTranslator? {
        var translator = getTranslatorFromPreferences(eightDot)
        if (translator != null) {
            return translator
        }
        var info = findDefaultTableInfo(eightDot)
        if (info == null && fallback) {
            info = findFallbackTableInfo()
        }
        if (info != null) {
            translator = mTranslatorClient.getTranslator(info.id)
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
        for (listener in mOnTablesChangedListeners) {
            listener.onTablesChanged()
        }
    }

    private fun isTablePreferenceKey(key: String): Boolean {
        return mPrefBrailleTypeKey == key
            || mPrefSixDotBrailleTableKey == key
            || mPrefEightDotBrailleTableKey == key
            || mPrefContractedModeKey == key
    }

    /** Returns whether contracted (grade 2) braille is selected in the
     * preferences. Defaults to false (uncontracted / grade 1). */
    private fun isContractedModeSelected(): Boolean {
        return mSharedPreferences.getBoolean(mPrefContractedModeKey, false)
    }

    /** Returns whether eight dot (computer) braille is selected for the
     * display. Following the TalkBack model, the display always uses literary
     * (six dot) braille; computer braille is only used internally for text
     * editing. */
    private fun isEightDotBrailleSelected(): Boolean {
        return false
    }
}
