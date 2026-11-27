/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback.compose

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
 * 与谷歌有三点差别，都由本项目数据决定：
 * 1) 语言清单来自运行时 `TranslatorClient.getTables()`，不是静态 Code 枚举；
 * 2) 国家/地区名必须过 [regionName] 的合规映射，中国台湾/中国香港/中国澳门
 *    显式带「中国」前缀，不能直接用系统 `Locale.getDisplayName()`；
 * 3) liblouis 的 268 张主表里有 6 张不是「某国盲文」（方框绘制、国际象棋、
 *    数学、行间注），另有 12 张是古代文字或语族聚合表。它们按 [TableKind]
 *    单独成组，不并进语言列表——混在一起的后果是盲人在「德语」下面点开
 *    看到「de-chess」不知道是什么。
 *
 * 条目名仍复用旧的 `table_name_*` 模板资源，与旧 Fragment 一一对应；
 * 只是 [TableSeed.localeName] 改由 [compliantLocaleName] 生成，避免系统
 * 本地化字符串绕过合规映射。
 */
object BrailleTableCatalog {

    const val AUTO_VALUE = "auto"

    /** 特殊用途表与古代/语族表的伪语言码，避免与真实语言码（如 "zh"）冲突。 */
    const val SPECIAL_GROUP_KEY = "@special"
    const val LEGACY_GROUP_KEY = "@legacy"

    private const val DEFAULT_SPECIAL_TITLE = "特殊用途表"
    private const val DEFAULT_LEGACY_TITLE = "古代与语族表"

    /**
     * 表用途。界面上只有普通语言表按「语言 → 国家/地区 → 变体」陈列。
     */
    enum class TableKind {
        /** 某语言的常规转写表。 */
        LANGUAGE,

        /** 非自然语言用途：方框绘制、国际象棋、数学、行间注。 */
        SPECIAL,

        /** 古代文字、已不通行文字与语族聚合表。 */
        LEGACY,
    }

    /** 非自然语言用途的表 id（不含扩展名）。 */
    private val SPECIAL_TABLE_IDS = setOf(
        "boxes",
        "de-chess",
        "en-chess",
        "en-ueb-math",
        "en-us-mathtext",
        "en-us-interline",
    )

    /**
     * 古代文字与语族聚合表的 id：
     * `akk` 阿卡德语、`cop` 科普特语、`cuneiform-*` 楔形文字转写、
     * `ancient-languages*` 古代语言合集、`ethio` 埃塞俄比亚文、`dra` 达罗毗荼语族。
     */
    private val LEGACY_TABLE_IDS = setOf(
        "akk",
        "akk-borger",
        "ancient-languages",
        "ancient-languages-borger",
        "ancient-languages-us",
        "cop",
        "cop-eg-comp8",
        "cuneiform-transliterated",
        "cuneiform-transliterated-compact",
        "ethio",
        "ethio-g1",
        "dra",
    )

    /**
     * 无国家码语言的国别兜底。
     *
     * liblouis 里 `bg.ctb`、`el.ctb` 这类表只写语言码，但表本身是单一国家的规范，
     * 补上后第二级才能读到「保加利亚语（保加利亚）」而不是「保加利亚语（通用）」。
     * 只收无争议的单一规范国；跨国的（ur、sw、ta、pi 等）不猜，仍显示「（通用）」。
     * `zhcn`/`zh` 不在此列：中文方案已按现行/双拼/通用单独拆成方案，口径不从这里来。
     */
    private val REGION_HINTS = mapOf(
        "bg" to "BG", "el" to "GR", "et" to "EE", "hr" to "HR", "is" to "IS",
        "lt" to "LT", "lv" to "LV", "sk" to "SK", "sl" to "SI", "sq" to "AL",
        "sr" to "RS", "mn" to "MN", "my" to "MM", "ne" to "NP", "si" to "LK",
        "th" to "TH", "vi" to "VN", "fa" to "IR", "fil" to "PH", "ms" to "MY",
        "mt" to "MT", "pl" to "PL", "pt" to "PT", "ro" to "RO", "ru" to "RU",
        "fi" to "FI", "fr" to "FR", "ga" to "IE", "gd" to "GB", "nl" to "NL",
        "ko" to "KR", "ca" to "ES", "es" to "ES", "haw" to "US", "iu" to "CA",
        "afr" to "ZA", "xh" to "ZA", "zu" to "ZA", "ve" to "ZA",
        "tsn" to "BW", "nso" to "ZA", "sot" to "LS", "bo" to "CN",
    )

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
        /** 特殊用途表分组的标题；空串时用内置中文兜底（只在测试路径出现）。 */
        val specialGroupTitle: String = "",
        /** 古代与语族表分组的标题；空串时用内置中文兜底。 */
        val legacyGroupTitle: String = "",
    )

    data class TableChoice(val id: String, val label: String)

    /**
     * 第一级的一项 = 一种语言（或一个非语言分组）。
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

    /**
     * 表 id → 用途。
     *
     * id 可能带扩展名或变体后缀（`de-chess.ctb`、`en-ueb-math`），
     * 先去掉扩展名，再整串匹配；匹配不上就是普通语言表。
     */
    fun kindOf(id: String): TableKind {
        val base = id.lowercase().removeSuffix(".ctb").removeSuffix(".utb")
        return when {
            base in SPECIAL_TABLE_IDS -> TableKind.SPECIAL
            base in LEGACY_TABLE_IDS -> TableKind.LEGACY
            else -> TableKind.LANGUAGE
        }
    }

    /** 无国家码时按语言补的国别；没有映射返回空串，调用方保持「（通用）」。 */
    fun regionHintOf(language: String): String =
        REGION_HINTS[language.lowercase()].orEmpty()

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
     * 其余语言组用 Collator 按语言显示名排序。组内（第二级）先按国家/地区名聚拢
     * （无国家码的通用表排最前），再六点先于八点、等级升序、变体按 Collator；
     * 组内不产生任何分组标题，整屏平铺。
     *
     * 特殊用途表（rank 2）与古代/语族表（rank 3）排在所有语言组之后，
     * 各自成组、内部按表名排；它们不进 [gradeCounts]，避免把同语言的等级判断带偏。
     */
    fun build(
        seeds: List<TableSeed>,
        systemLanguage: String,
        labels: TableLabels,
    ): List<TableGroup> {
        val collator = Collator.getInstance()

        // 先按用途分流：语言表进语言分组，其余两类各自独立成组。
        val languageSeeds = ArrayList<TableSeed>()
        val specialSeeds = ArrayList<TableSeed>()
        val legacySeeds = ArrayList<TableSeed>()
        seeds.forEach { seed ->
            when (kindOf(seed.id)) {
                TableKind.SPECIAL -> specialSeeds.add(seed)
                TableKind.LEGACY -> legacySeeds.add(seed)
                TableKind.LANGUAGE -> languageSeeds.add(seed)
            }
        }

        // 旧版 gradeCount 统计「同 locale 的非八点表」，这里按同一口径预计算。
        val gradeCounts = languageSeeds.asSequence()
            .filter { !it.isEightDot }
            .groupingBy { it.localeKey() }
            .eachCount()

        // 按语言码聚合，不用显示名当 key：界面语言切换后仍是同一组。
        val grouped = LinkedHashMap<String, MutableList<TableSeed>>()
        languageSeeds.forEach { seed ->
            grouped.getOrPut(seed.language.lowercase()) { ArrayList() }.add(seed)
        }

        data class Ranked(val rank: Int, val group: TableGroup)

        val languageGroups = grouped.map { (language, members) ->
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

        fun extraGroup(
            rank: Int,
            key: String,
            title: String,
            members: List<TableSeed>,
        ): Ranked = Ranked(
            rank,
            TableGroup(
                language = key,
                title = title,
                choices = members
                    .sortedWith(compareBy({ collator.getCollationKey(it.id) }))
                    .map {
                        // 特殊表没有「语言（国家）」的概念，直接读表名或变体名。
                        TableChoice(it.id, it.variant.ifEmpty { it.id })
                    },
            ),
        )

        val extras = ArrayList<Ranked>()
        if (specialSeeds.isNotEmpty()) {
            extras.add(
                extraGroup(
                    2,
                    SPECIAL_GROUP_KEY,
                    labels.specialGroupTitle.ifEmpty { DEFAULT_SPECIAL_TITLE },
                    specialSeeds,
                )
            )
        }
        if (legacySeeds.isNotEmpty()) {
            extras.add(
                extraGroup(
                    3,
                    LEGACY_GROUP_KEY,
                    labels.legacyGroupTitle.ifEmpty { DEFAULT_LEGACY_TITLE },
                    legacySeeds,
                )
            )
        }

        return (languageGroups + extras)
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
