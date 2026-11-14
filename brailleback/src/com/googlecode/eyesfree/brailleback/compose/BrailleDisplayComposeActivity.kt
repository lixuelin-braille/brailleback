/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * Compose 迁移第三批：「盲文显示」子屏。
 *
 * 与旧 Fragment 的三处等价关系，缺一不可：
 * 1) 表列表来自 TranslatorClient.getTables()，服务初始化完成后由
 *    TranslatorManager.OnTablesChangedListener 回调才有值，不能静态写死；
 * 2) 本 Activity 自己持有 TranslatorManager：它同时是
 *    SharedPreferences.OnSharedPreferenceChangeListener，写入盲文表偏好后
 *    会触发 updateTranslators()，否则换表不生效（SettingsActivity 此时已 onPause，
 *    它持有的实例已经 shutdown）；
 * 3) BrailleBackService 切换六/八点时会发 INTENT_REFRESH_DISPLAY 广播，
 *    旧 Fragment 收到后刷新摘要，这里同样注册并重新读取偏好值。
 */

package com.googlecode.eyesfree.brailleback.compose

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Bundle
import android.text.TextUtils
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.googlecode.eyesfree.braille.translate.TableInfo
import com.googlecode.eyesfree.brailleback.R
import com.googlecode.eyesfree.brailleback.SettingsActivity
import com.googlecode.eyesfree.brailleback.TranslatorManager
import java.util.Locale

/** 一次性收集好的中文文案，让 Screen 保持无 Context 依赖。 */
internal data class BrailleDisplayLabels(
    val title: String,
    val back: String,
    val sectionBrailleType: String,
    val tableTitle: String,
    val chooseHint: String,
    val wordWrapTitle: String,
    val wordWrapSummaryOn: String,
    val wordWrapSummaryOff: String,
    val cursorDotsTitle: String,
    val cursorDotsSummaryOn: String,
    val cursorDotsSummaryOff: String,
    val contractedTitle: String,
    val contractedSummaryOn: String,
    val contractedSummaryOff: String,
    val segmentationTitle: String,
    val segmentationSummary: String,
    val on: String,
    val off: String,
    val selected: String,
    val unselected: String,
    val pickerTitle: String,
    val autoGroupTitle: String,
    val loadingLabel: String,
    val emptyLabel: String,
    val refreshHint: String,
)

class BrailleDisplayComposeActivity : AppCompatActivity(),
    TranslatorManager.OnTablesChangedListener {

    private lateinit var prefs: SharedPreferences
    private lateinit var tableKey: String
    private lateinit var tableLabels: BrailleTableCatalog.TableLabels

    private var translatorManager: TranslatorManager? = null

    // 服务回调来自 Binder 线程，统一切回主线程再驱动重组。
    private var tables by mutableStateOf<List<BrailleTableCatalog.TableSeed>>(emptyList())
    private var autoLabel by mutableStateOf("")
    private var currentTable by mutableStateOf(BrailleDisplayLogic.DEFAULT_TABLE)
    private var wordWrap by mutableStateOf(true)
    private var cursorDots by mutableStateOf(false)
    private var contractedMode by mutableStateOf(false)
    private var chineseSegmentation by mutableStateOf(true)
    private var pickerOpen by mutableStateOf(false)

    private val refreshReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            if (TextUtils.equals(intent.action, SettingsActivity.INTENT_REFRESH_DISPLAY)) {
                runOnUiThread {
                    currentTable = BrailleDisplayLogic.initialTable(
                        prefs.getString(tableKey, BrailleDisplayLogic.DEFAULT_TABLE)
                    )
                    refreshTables()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 标题栏交给 Compose 的 TopAppBar，避免两个标题栏叠加、读屏重复朗读。
        supportActionBar?.hide()

        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        tableKey = getString(R.string.pref_braille_table_key)
        val boolKeys = listOf(
            BrailleDisplayLogic.KEY_WORD_WRAP,
            BrailleDisplayLogic.KEY_CURSOR_DOTS,
            BrailleDisplayLogic.KEY_CONTRACTED_MODE,
            BrailleDisplayLogic.KEY_CHINESE_SEGMENTATION,
        )
        val wordWrapDefault = resources.getBoolean(R.bool.pref_braille_word_wrap_default)
        val segmentationDefault =
            resources.getBoolean(R.bool.pref_chinese_segmentation_default)
        val initial = boolKeys.associateWith { key ->
            prefs.getBoolean(
                key,
                BrailleDisplayLogic.defaultBoolean(key, wordWrapDefault, segmentationDefault),
            )
        }
        wordWrap = initial.getValue(BrailleDisplayLogic.KEY_WORD_WRAP)
        cursorDots = initial.getValue(BrailleDisplayLogic.KEY_CURSOR_DOTS)
        contractedMode = initial.getValue(BrailleDisplayLogic.KEY_CONTRACTED_MODE)
        chineseSegmentation = initial.getValue(BrailleDisplayLogic.KEY_CHINESE_SEGMENTATION)
        currentTable = BrailleDisplayLogic.initialTable(
            prefs.getString(getString(R.string.pref_braille_table_key), null)
        )

        tableLabels = BrailleTableCatalog.TableLabels(
            genericGroupSuffix = getString(R.string.compose_table_group_generic),
            eightDot = getString(R.string.table_name_eight_dot),
            variant = getString(R.string.table_name_variant),
            grade = getString(R.string.table_name_grade),
            variantGrade = getString(R.string.table_name_variant_grade),
        )

        setContent {
            val labels = BrailleDisplayLabels(
                title = getString(R.string.pref_category_braille_title),
                back = getString(R.string.compose_settings_back),
                sectionBrailleType = getString(R.string.pref_category_braille_type_title),
                tableTitle = getString(R.string.pref_braille_table_title),
                chooseHint = getString(R.string.compose_settings_choose),
                wordWrapTitle = getString(R.string.pref_braille_word_wrap_title),
                wordWrapSummaryOn = getString(R.string.pref_braille_word_wrap_summary_on),
                wordWrapSummaryOff = getString(R.string.pref_braille_word_wrap_summary_off),
                cursorDotsTitle = getString(R.string.pref_cursor_dots_title),
                // 状态由 stateDescription 播报，摘要刻意去掉「已开启。/已关闭。」前缀。
                cursorDotsSummaryOn =
                    getString(R.string.compose_settings_cursor_dots_summary_on),
                cursorDotsSummaryOff =
                    getString(R.string.compose_settings_cursor_dots_summary_off),
                contractedTitle = getString(R.string.pref_braille_contracted_mode_title),
                contractedSummaryOn =
                    getString(R.string.pref_braille_contracted_mode_summary_on),
                contractedSummaryOff =
                    getString(R.string.pref_braille_contracted_mode_summary_off),
                segmentationTitle = getString(R.string.pref_chinese_segmentation_title),
                segmentationSummary = getString(R.string.pref_chinese_segmentation_summary),
                on = getString(R.string.compose_settings_on),
                off = getString(R.string.compose_settings_off),
                selected = getString(R.string.compose_settings_selected),
                unselected = getString(R.string.compose_settings_unselected),
                pickerTitle = getString(R.string.compose_table_picker_title),
                autoGroupTitle = getString(R.string.compose_table_group_auto),
                loadingLabel = getString(R.string.compose_table_loading),
                emptyLabel = getString(R.string.compose_table_empty),
                refreshHint = getString(R.string.compose_table_refresh_hint),
            )
            val groups = BrailleTableCatalog.build(
                tables,
                Locale.getDefault().language,
                Locale.getDefault().country,
                tableLabels,
            )
            BrailleDisplayScreen(
                labels = labels,
                groups = groups,
                autoLabel = autoLabel,
                currentTableLabel = BrailleTableCatalog.labelForValue(
                    currentTable, autoLabel, groups, labels.loadingLabel
                ),
                currentTable = currentTable,
                wordWrap = wordWrap,
                cursorDots = cursorDots,
                contractedMode = contractedMode,
                chineseSegmentation = chineseSegmentation,
                pickerOpen = pickerOpen,
                onOpenPicker = { pickerOpen = true },
                onClosePicker = { pickerOpen = false },
                onRefreshTables = { refreshTables() },
                onTableSelected = { value ->
                    currentTable = value
                    prefs.edit().putString(tableKey, value).apply()
                    pickerOpen = false
                },
                onBooleanChanged = { key, value ->
                    when (key) {
                        BrailleDisplayLogic.KEY_WORD_WRAP -> wordWrap = value
                        BrailleDisplayLogic.KEY_CURSOR_DOTS -> cursorDots = value
                        BrailleDisplayLogic.KEY_CONTRACTED_MODE -> contractedMode = value
                        BrailleDisplayLogic.KEY_CHINESE_SEGMENTATION ->
                            chineseSegmentation = value
                    }
                    prefs.edit().putBoolean(key, value).apply()
                },
                onBack = { finish() },
            )
        }

        translatorManager = TranslatorManager(this).also { manager ->
            manager.addOnTablesChangedListener(this)
        }
        ContextCompat.registerReceiver(
            this,
            refreshReceiver,
            IntentFilter(SettingsActivity.INTENT_REFRESH_DISPLAY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        refreshTables()
    }

    /** 服务初始化、收到刷新广播或用户手动刷新时重新读取表清单。 */
    private fun refreshTables() {
        val manager = translatorManager ?: return
        val infos: List<TableInfo> = manager.getTranslatorClient().getTables().orEmpty()
        val defaultInfo = manager.findDefaultTableInfo(false)
        val seeds = infos.map { it.toSeed() }
        val label = if (defaultInfo == null) {
            getString(R.string.pref_braille_table_default_none_label)
        } else {
            val seed = defaultInfo.toSeed()
            val gradeCount = seeds.count {
                !it.isEightDot && it.localeKey() == seed.localeKey()
            }
            getString(
                R.string.pref_braille_table_default_label,
                BrailleTableCatalog.entryLabel(seed, gradeCount, tableLabels),
            )
        }
        tables = seeds
        autoLabel = label
    }

    override fun onTablesChanged() {
        runOnUiThread { refreshTables() }
    }

    /** TableInfo 是 Android Parcelable，转成纯数据结构后再交给零依赖的分组逻辑。 */
    private fun TableInfo.toSeed(): BrailleTableCatalog.TableSeed {
        val country = locale.country
        val localizedCountry = if (country.isEmpty()) {
            ""
        } else {
            Locale("", country).getDisplayCountry()
        }
        val useChineseNames = Locale.getDefault().language.equals("zh", ignoreCase = true)
        return BrailleTableCatalog.TableSeed(
            id = id.orEmpty(),
            language = locale.language,
            country = country,
            localeName = locale.getDisplayName(),
            languageName = Locale(locale.language).getDisplayLanguage(),
            countryName = BrailleTableCatalog.regionName(
                country, localizedCountry, useChineseNames
            ),
            isEightDot = isEightDot,
            grade = grade,
            variant = variant,
        )
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(refreshReceiver)
        } catch (e: IllegalArgumentException) {
            //onCreate 未完成时可能还没注册，忽略。
        }
        translatorManager?.removeOnTablesChangedListener(this)
        translatorManager?.shutdown()
        translatorManager = null
        super.onDestroy()
    }
}
