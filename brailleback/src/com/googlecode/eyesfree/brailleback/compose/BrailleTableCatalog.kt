/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback.compose

import java.text.Collator

/**
 * 按语言整理盲文表的纯逻辑，零 Android 依赖，便于 JVM 单测。
 *
 * 严格复刻谷歌读屏 `AddLanguageActivity` → `LocaleLanguageActivity` 的两级结构：
 * - 第一级（[build]）按语言分组，系统语言置顶，其余按 Collator 排语言显示名
 *   （谷歌源码 AddLanguageActivity.java:78-101 的 locales.sort + add(0, remove(idx))）；
 * - 第二级是全屏的该语言表列表，平铺、没有分组标题，条目名自带国家/地区
 *   （谷歌源码 LocaleLanguageActivity.java:84-99 用 getUserFacingName 区分 locale）。
 *
 * 与谷歌有两点差别，都由本项目数据决定：
 * 1) 语言清单来自运行时 `TranslatorClient.getTables()`，不是静态 Code 枚举；
 * 2) 国家/地区名必须过 [regionName] 的合规映射，中国台湾/中国香港/中国澳门
 *    显式带「中国」前缀，不能直接用系统 `Locale.getDisplayName()`。
 *
 * 条目名仍复用旧的 `table_name_*` 模板资源，与旧 Fragment 一一对应；
 * 只是 [TableSeed.localeName] 改由 [compliantLocaleName] 生成，避免系统
 * 本地化字符串绕过合规映射。
 */
object BrailleTableCatalog {

    const val AUTO_VALUE = "auto"

    /** 一张表在界面上需要的全部信息；本地化显示名由 Activity 层算好后传入。 */
    data class TableSeed(
        val id: String,
        val language: String,
        val country: String,
        /** 「语言（国家/地区）」或「语言（通用）」，由 [compliantLocaleName] 生成。 */
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
        /** 无国家码语言的后缀，如「（通用）」；也用作第二级条目名的兜底。 */
        val genericGroupSuffix: String,
        /** 有国家码时「语言（地区）」的模板：中文全角括号、英文半角括号各一套。 */
        val localePattern: String,
        val eightDot: String,
        val variant: String,
        val grade: String,
        val variantGrade: String,
    )

    data class TableChoice(val id: String, val label: String)

    /**
     * 第一级的一项 = 一种语言。
     * [language] 是稳定 key（语言码），[title] 才是本地化显示名；
     * 第二级全屏页用语言码定位，界面语言切换后不会指错组。
     */
    data class TableGroup(
        val language: String,
        val title: String,
        val choices: List<TableChoice>,
    )

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

    /**
     * 第二级条目名的「语言（国家/地区）」部分。
     *
     * 不能用 `Locale.getDisplayName()`：它对中国台湾/中国香港/中国澳门可能给出
     * 不合规表述，且无国家码时与同语言的地区表同名、读屏无法区分。
     */
    fun compliantLocaleName(seed: TableSeed, labels: TableLabels): String =
        if (seed.country.isEmpty()) {
            seed.languageName + labels.genericGroupSuffix
        } else {
            labels.localePattern.format(seed.languageName, seed.countryName)
        }

    /** 第一级标题就是语言名；国家/地区层级只体现在第二级条目里。 */
    fun languageTitle(seed: TableSeed): String = seed.languageName

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
     * 生成按语言分组、一次性加载的两级列表。
     *
     * 排序规则（照抄谷歌 `AddLanguageActivity`）：系统语言所在组置顶，
     * 其余组用 Collator 按语言显示名排序。组内（第二级）先按国家/地区名聚拢
     * （无国家码的通用表排最前），再六点先于八点、等级升序、变体按 Collator；
     * 组内不产生任何分组标题，整屏平铺。
     */
    fun build(
        seeds: List<TableSeed>,
        systemLanguage: String,
        labels: TableLabels,
    ): List<TableGroup> {
        val collator = Collator.getInstance()
        // 旧版 gradeCount 统计「同 locale 的非八点表」，这里按同一口径预计算。
        val gradeCounts = seeds.asSequence()
            .filter { !it.isEightDot }
            .groupingBy { it.localeKey() }
            .eachCount()

        // 按语言码聚合，不用显示名当 key：界面语言切换后仍是同一组。
        val grouped = LinkedHashMap<String, MutableList<TableSeed>>()
        seeds.forEach { seed ->
            grouped.getOrPut(seed.language.lowercase()) { ArrayList() }.add(seed)
        }

        data class Ranked(val rank: Int, val group: TableGroup)

        return grouped.map { (language, members) ->
            val sorted = members.sortedWith(
                compareBy<TableSeed>(
                    { collator.getCollationKey(it.countryName) },
                    { it.isEightDot },
                    { it.grade },
                    { collator.getCollationKey(it.variant) },
                )
            )
            val rank = if (language.equals(systemLanguage, ignoreCase = true)) 0 else 1
            Ranked(
                rank,
                TableGroup(
                    language = language,
                    title = languageTitle(sorted.first()),
                    choices = sorted.map {
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
