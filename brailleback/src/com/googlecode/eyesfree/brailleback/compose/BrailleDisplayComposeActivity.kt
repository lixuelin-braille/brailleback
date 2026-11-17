/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * Compose 迁移第三批：「盲文显示」子屏；本批升级为谷歌式多选方案（Code）模型。
 *
 * 与旧 Fragment 的三处等价关系，缺一不可：
 * 1) 表清单来自 TranslatorClient.getTables()，服务初始化完成后由
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
import android.os.Bundle
import android.text.TextUtils
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
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
    /** 第一级全屏的标题：按语言管理方案。 */
    val pickerTitle: String,
    /** 服务未就绪时「自动」行的兜底标题，不能朗读空白。 */
    val autoFallback: String,
    val loadingLabel: String,
    val emptyLabel: String,
    val refreshHint: String,
    val currentCodeTitle: String,
    val currentCodeHint: String,
    val currentCodeDialog: String,
    val enabledLabel: String,
    val disabledLabel: String,
    val currentSuffix: String,
    val cancel: String,
)

class BrailleDisplayComposeActivity : AppCompatActivity(),
    TranslatorManager.OnTablesChangedListener {

    private lateinit var tableLabels: BrailleTableCatalog.TableLabels
    private lateinit var codeLabels: BrailleCodeCatalog.CodeLabels

    private var translatorManager: TranslatorManager? = null

    // 服务回调来自 Binder 线程，统一切回主线程再驱动重组。
    private var tables by mutableStateOf<List<BrailleTableCatalog.TableSeed>>(emptyList())
    private var autoLabel by mutableStateOf("")
    // 偏好类状态（当前方案、首选池、四个开关、当前方案对话框）全部由 ViewModel 持有；
    // 这里只留「表清单」和「派生文案」这两件不属于偏好、且依赖服务回调的展示状态。
    private val viewModel: BrailleDisplayViewModel by viewModels {
        BrailleDisplayViewModel.factory(
            SharedPreferencesBrailleSettingsStore(
                PreferenceManager.getDefaultSharedPreferences(this)
            ),
            BrailleDisplayComposeActivity.keys(this),
        )
    }
    // 三段路由：设置列表 → 语言列表 → 某一语言的方案列表（后两段各占满全屏）。
    private var route by mutableStateOf<BrailleDisplayLogic.TableRoute>(
        BrailleDisplayLogic.TableRoute.Settings
    )

    private val refreshReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            if (TextUtils.equals(intent.action, SettingsActivity.INTENT_REFRESH_DISPLAY)) {
                runOnUiThread {
                    viewModel.refreshFromStore()
                    refreshTables()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 标题栏交给 Compose 的 TopAppBar，避免两个标题栏叠加、读屏重复朗读。
        supportActionBar?.hide()

        // ViewModel 已经在首次访问时按当前偏好构造好状态，这里不再重复读偏好：
        // 读口径一旦有两份实现，就会出现「界面按 A 显示、服务按 B 干活」的老问题。

        tableLabels = BrailleTableCatalog.TableLabels(
            genericGroupSuffix = getString(R.string.compose_table_group_generic),
            localePattern = getString(R.string.compose_table_locale_pattern),
            eightDot = getString(R.string.table_name_eight_dot),
            variant = getString(R.string.table_name_variant),
            grade = getString(R.string.table_name_grade),
            variantGrade = getString(R.string.table_name_variant_grade),
        )
        codeLabels = BrailleCodeCatalog.CodeLabels(
            eightDotName = getString(R.string.compose_code_eight_dot),
            sixDotSummary = getString(R.string.compose_code_summary_six),
            eightDotSummary = getString(R.string.compose_code_summary_eight),
            enabled = getString(R.string.compose_code_enabled),
            disabled = getString(R.string.compose_code_disabled),
            currentSuffix = getString(R.string.compose_code_current_suffix),
        )

        setContent {
            // 偏好类状态唯一来源：StateFlow → Compose。写入一律走 viewModel 的方法，
            // 屏幕上的开关、当前方案、首选池因此不会与偏好里的值分叉。
            val settings by viewModel.state.collectAsState()
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
                autoFallback = getString(R.string.compose_table_group_auto),
                loadingLabel = getString(R.string.compose_table_loading),
                emptyLabel = getString(R.string.compose_table_empty),
                refreshHint = getString(R.string.compose_table_refresh_hint),
                currentCodeTitle = getString(R.string.compose_code_current_title),
                currentCodeHint = getString(R.string.compose_code_current_hint),
                currentCodeDialog = getString(R.string.compose_code_current_dialog),
                enabledLabel = getString(R.string.compose_code_enabled),
                disabledLabel = getString(R.string.compose_code_disabled),
                currentSuffix = getString(R.string.compose_code_current_suffix),
                cancel = getString(R.string.compose_settings_cancel),
            )
            // 用可观察的配置取语言：Compose 1.12 的 lint（NonObservableLocale）把
            // Locale.getDefault() 在 composable 里的调用判为 error——切语言时不会重组，
            // 分组会停在旧语言。LocalConfiguration 随配置变化触发重组。
            val systemLanguage = LocalConfiguration.current.locales[0].language
            val tableGroups = BrailleTableCatalog.build(
                tables,
                systemLanguage,
                tableLabels,
            )
            val codeGroups = BrailleCodeCatalog.build(
                tableGroups,
                tables,
                tableLabels,
                codeLabels,
            )
            // 只做过滤 + 空池兜底；当前方案由 refreshTables() 里的对账纠正并写回，
            // 不在这里把当前方案补进池（否则会出现用户没勾过的「已启用」）。
            val normalized = BrailleCodeCatalog.normalize(
                settings.enabledCodes,
                codeGroups,
                systemLanguage,
            )
            val currentTable = settings.currentTable
            val currentRoute = route
            val currentLanguage = BrailleCodeCatalog.languageOfCode(codeGroups, currentTable)
            val languageRows = codeGroups.map { group ->
                BrailleDisplayLogic.LanguageRow(
                    language = group.language,
                    title = group.title,
                    summary = getString(
                        R.string.compose_code_language_summary,
                        group.codes.size,
                        BrailleCodeCatalog.enabledCount(codeGroups, normalized, group.language),
                        if (group.language == currentLanguage) {
                            getString(R.string.compose_table_language_current)
                        } else {
                            ""
                        },
                    ),
                    enabledCount = BrailleCodeCatalog.enabledCount(
                        codeGroups, normalized, group.language
                    ),
                    openHint = getString(
                        R.string.compose_table_language_open_hint, group.title
                    ),
                )
            }
            val visibleCodes = (currentRoute as? BrailleDisplayLogic.TableRoute.Tables)
                ?.let { BrailleCodeCatalog.groupForLanguage(codeGroups, it.language)?.codes }
                .orEmpty()
            val currentCodeOptions = visibleCodes.filter { it.id in normalized }
                .ifEmpty {
                    codeGroups.asSequence()
                        .flatMap { it.codes.asSequence() }
                        .filter { it.id in normalized }
                        .toList()
                }
            val screenTitle = when (currentRoute) {
                BrailleDisplayLogic.TableRoute.Settings -> labels.title
                BrailleDisplayLogic.TableRoute.Languages -> labels.pickerTitle
                is BrailleDisplayLogic.TableRoute.Tables ->
                    BrailleCodeCatalog.groupForLanguage(codeGroups, currentRoute.language)?.title
                        ?: labels.pickerTitle
            }
            BrailleDisplayScreen(
                labels = labels,
                route = currentRoute,
                languageRows = languageRows,
                codes = visibleCodes,
                enabledCodes = normalized,
                currentCodeOptions = currentCodeOptions,
                currentCodeLabel = BrailleCodeCatalog.labelForCode(
                    currentTable, autoLabel, codeGroups, labels.loadingLabel
                ),
                showCurrentDialog = settings.showCurrentDialog,
                title = screenTitle,
                autoLabel = autoLabel,
                currentTableLabel = BrailleCodeCatalog.labelForCode(
                    currentTable, autoLabel, codeGroups, labels.loadingLabel
                ),
                currentTable = currentTable,
                wordWrap = settings.wordWrap,
                cursorDots = settings.cursorDots,
                contractedMode = settings.contractedMode,
                chineseSegmentation = settings.chineseSegmentation,
                onOpenLanguages = {
                    route = BrailleDisplayLogic.TableRoute.Languages
                },
                onOpenLanguage = { language ->
                    route = BrailleDisplayLogic.TableRoute.Tables(language)
                },
                onNavigateBack = { route = BrailleDisplayLogic.back(route) },
                onRefreshTables = { refreshTables() },
                onToggleCode = { id, checked ->
                    viewModel.toggleCode(id, checked, codeGroups, systemLanguage)
                },
                onOpenCurrentDialog = { viewModel.openCurrentDialog() },
                onDismissCurrentDialog = { viewModel.dismissCurrentDialog() },
                onSelectCurrent = { value ->
                    viewModel.chooseCurrent(value)
                    route = BrailleDisplayLogic.TableRoute.Settings
                },
                onAutoSelected = {
                    viewModel.chooseCurrent(BrailleTableCatalog.AUTO_VALUE)
                    route = BrailleDisplayLogic.TableRoute.Settings
                },
                onBooleanChanged = { key, value ->
                    viewModel.setBoolean(key, value)
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
        if (seeds.isEmpty()) {
            return
        }
        // 表清单就绪才能把「方案池」和「当前方案」对账（逻辑与落盘都在 ViewModel 里，
        // 这里只负责把分组算出来喂给它）。
        val systemLanguage = Locale.getDefault().language
        val tableGroups = BrailleTableCatalog.build(seeds, systemLanguage, tableLabels)
        val codeGroups = BrailleCodeCatalog.build(tableGroups, seeds, tableLabels, codeLabels)
        viewModel.reconcileCodes(codeGroups, systemLanguage)
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
        val seed = BrailleTableCatalog.TableSeed(
            id = id.orEmpty(),
            language = locale.language,
            country = country,
            localeName = "",
            languageName = Locale(locale.language).getDisplayLanguage(),
            countryName = BrailleTableCatalog.regionName(
                country, localizedCountry, useChineseNames
            ),
            isEightDot = isEightDot,
            grade = grade,
            variant = variant,
        )
        // 不用系统提供的显示名：系统本地化字符串会绕过中国台湾/中国香港/
        // 中国澳门的合规映射，且无国家码时与同语言地区表同名、读屏无法区分。
        return seed.copy(localeName = BrailleTableCatalog.compliantLocaleName(seed, tableLabels))
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

    private companion object {
        /**
         * 偏好键名与默认值仍然只有资源这一处来源：键是 SharedPreferences 的 ABI
         * （见 SettingsStructureContractTest），默认值来自 `R.bool.*`。
         */
        fun keys(context: Context): BrailleSettingsKeys {
            val wordWrapDefault =
                context.resources.getBoolean(R.bool.pref_braille_word_wrap_default)
            val segmentationDefault =
                context.resources.getBoolean(R.bool.pref_chinese_segmentation_default)
            fun defaultOf(key: String) =
                BrailleDisplayLogic.defaultBoolean(key, wordWrapDefault, segmentationDefault)
            return BrailleSettingsKeys(
                table = context.getString(R.string.pref_braille_table_key),
                preferredCodes = context.getString(R.string.pref_preferred_codes_key),
                booleanDefaults = listOf(
                    BrailleDisplayLogic.KEY_WORD_WRAP,
                    BrailleDisplayLogic.KEY_CURSOR_DOTS,
                    BrailleDisplayLogic.KEY_CONTRACTED_MODE,
                    BrailleDisplayLogic.KEY_CHINESE_SEGMENTATION,
                ).associateWith { defaultOf(it) },
            )
        }
    }
}
