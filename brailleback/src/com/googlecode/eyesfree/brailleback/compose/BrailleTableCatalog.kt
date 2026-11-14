/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback.compose

import java.text.Collator

/**
 * 按国家/地区整理盲文表的纯逻辑，零 Android 依赖，便于 JVM 单测。
 *
 * 参考两条既有实现：
 * - 谷歌读屏 `AddLanguageActivity`：按语言分组、系统语言所在组置顶、Collator 排序；
 *   本项目把分组维度换成「国家/地区」，无国家码的语言以「语言名（通用）」兜底。
 * - 本项目盲文输入法的命名风格（`code_user_facing_name_*`）：条目名里带国家名，
 *   如「中文通用盲文（中国）」，因此组内仍沿用旧的完整表名，不丢语言信息。
 *
 * 条目名严格复刻 `SettingsActivity.createTableDisplayName` 的四条规则
 * （八点 / 中文变体 / 同 locale 多等级 / 单等级），避免迁移后出现两套命名。
 */
object BrailleTableCatalog {

    const val AUTO_VALUE = "auto"

    /** 一张表在界面上需要的全部信息；本地化显示名由 Activity 层算好后传入。 */
    data class TableSeed(
        val id: String,
        val language: String,
        val country: String,
        /** `TableInfo.locale.getDisplayName()`，如「中文 (中国)」。 */
        val localeName: String,
        val languageName: String,
        /** 已按合规规则处理过的国家/地区名；无国家码时为空串。 */
        val countryName: String,
        val isEightDot: Boolean,
        val grade: Int,
        val variant: String,
    ) {
        /** 与旧 `TranslatorManager.getRelatedTables` 的分组口径一致：locale 相同才算同族。 */
        fun localeKey(): String = "$language-$country"
    }

    /** 旧的 `table_name_*` 模板资源，注入进来让纯逻辑不碰 Context。 */
    data class TableLabels(
        /** 无国家码语言的分组后缀，如「（通用）」。 */
        val genericGroupSuffix: String,
        val eightDot: String,
        val variant: String,
        val grade: String,
        val variantGrade: String,
    )

    data class TableChoice(val id: String, val label: String)

    data class TableGroup(val title: String, val choices: List<TableChoice>)

    /**
     * 中国台湾/中国香港/中国澳门必须显式带「中国」前缀，
     * 不能直接采用系统 `Locale.getDisplayCountry()` 的返回值。
     * 中文与英文各给一套，避免英文 locale 下出现不合规表述。
     */
    private val CHINA_REGIONS_ZH = mapOf(
        "TW" to "中国台湾", "HK" to "中国香港", "MO" to "中国澳门",
    )
    private val CHINA_REGIONS_EN = mapOf(
        "TW" to "Taiwan, China", "HK" to "Hong Kong, China", "MO" to "Macao, China",
    )

    /** [useChineseNames] 由当前界面语言决定；其余国家沿用系统本地化名。 */
    fun regionName(country: String, localizedName: String, useChineseNames: Boolean): String {
        if (country.isEmpty()) {
            return ""
        }
        val table = if (useChineseNames) CHINA_REGIONS_ZH else CHINA_REGIONS_EN
        return table[country.uppercase()]
            ?: localizedName.ifEmpty { country.uppercase() }
    }

    /** 分组标题：有国家/地区用地区名；没有则用「语言名 + 通用后缀」。 */
    fun groupTitle(seed: TableSeed, genericGroupSuffix: String): String =
        if (seed.countryName.isNotEmpty()) {
            seed.countryName
        } else {
            seed.languageName + genericGroupSuffix
        }

    /** 单张表的显示名，与旧版 `createTableDisplayName` 一一对应。 */
    fun entryLabel(seed: TableSeed, siblingGradeCount: Int, labels: TableLabels): String {
        if (seed.isEightDot) {
            return labels.eightDot.format(seed.localeName)
        }
        if (seed.language.equals("zh", ignoreCase = true)) {
            return if (seed.variant.isEmpty()) {
                seed.localeName
            } else {
                labels.variant.format(seed.localeName, seed.variant)
            }
        }
        return if (siblingGradeCount <= 1) {
            if (seed.variant.isEmpty()) {
                seed.localeName
            } else {
                labels.variant.format(seed.localeName, seed.variant)
            }
        } else {
            if (seed.variant.isEmpty()) {
                labels.grade.format(seed.localeName, seed.grade)
            } else {
                labels.variantGrade.format(seed.localeName, seed.variant, seed.grade)
            }
        }
    }

    /**
     * 生成按国家/地区分组、一次性加载的选择列表。
     *
     * 排序规则：系统 locale 完全匹配的组 → 同语言的组 → 其余组；
     * 同级用 Collator 按分组标题排序。组内六点先于八点、等级升序、变体按 Collator。
     */
    fun build(
        seeds: List<TableSeed>,
        systemLanguage: String,
        systemCountry: String,
        labels: TableLabels,
    ): List<TableGroup> {
        val collator = Collator.getInstance()
        // 旧版 gradeCount 统计「同 locale 的非八点表」，这里按同一口径预计算。
        val gradeCounts = seeds.asSequence()
            .filter { !it.isEightDot }
            .groupingBy { it.localeKey() }
            .eachCount()

        val grouped = LinkedHashMap<String, MutableList<TableSeed>>()
        seeds.forEach { seed ->
            val title = groupTitle(seed, labels.genericGroupSuffix)
            grouped.getOrPut(title) { ArrayList() }.add(seed)
        }

        data class Ranked(val rank: Int, val group: TableGroup)

        return grouped.map { (title, members) ->
            val sorted = members.sortedWith(
                compareBy<TableSeed>(
                    { it.isEightDot },
                    { it.grade },
                    { collator.getCollationKey(it.variant) },
                )
            )
            val rank = when {
                systemCountry.isNotEmpty() && sorted.any {
                    it.language.equals(systemLanguage, ignoreCase = true) &&
                        it.country.equals(systemCountry, ignoreCase = true)
                } -> 0
                sorted.any { it.language.equals(systemLanguage, ignoreCase = true) } -> 1
                else -> 2
            }
            Ranked(
                rank,
                TableGroup(
                    title,
                    sorted.map {
                        TableChoice(
                            it.id,
                            entryLabel(it, gradeCounts[it.localeKey()] ?: 0, labels),
                        )
                    },
                ),
            )
        }
            .sortedWith(compareBy({ it.rank }, { collator.getCollationKey(it.group.title) }))
            .map { it.group }
    }

    /** 当前偏好值对应的可读标签；服务未就绪或取值未知时都不朗读空白。 */
    fun labelForValue(
        value: String,
        autoLabel: String,
        groups: List<TableGroup>,
        loadingLabel: String,
    ): String {
        if (value == AUTO_VALUE || value.isEmpty()) {
            return autoLabel.ifEmpty { loadingLabel }
        }
        groups.asSequence()
            .flatMap { it.choices.asSequence() }
            .firstOrNull { it.id == value }
            ?.let { return it.label }
        return autoLabel.ifEmpty { loadingLabel }
    }
}
