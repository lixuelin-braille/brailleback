/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 按语言分组、两级全屏盲文表的纯逻辑行为测试（不依赖 Android）。
 *
 * 分组维度由第三批的「国家/地区」改为「语言」后，这些测试守住：
 * 语言码是稳定 key、系统语言置顶、同语言跨国家的表聚在一组且不丢、
 * 条目名走合规地区映射、等级统计仍按 locale 而不是按语言。
 */
class BrailleTableCatalogTest {

    private val labels = BrailleTableCatalog.TableLabels(
        genericGroupSuffix = "（通用）",
        localePattern = "%1\$s（%2\$s）",
        eightDot = "%1\$s（八点）",
        variant = "%1\$s（%2\$s）",
        grade = "%1\$s - %2\$d 级",
        variantGrade = "%1\$s（%2\$s）- %3\$d 级",
    )

    private fun seed(
        id: String,
        language: String,
        country: String = "",
        languageName: String,
        countryName: String = "",
        localeName: String = languageName,
        isEightDot: Boolean = false,
        grade: Int = 1,
        variant: String = "",
    ) = BrailleTableCatalog.TableSeed(
        id = id,
        language = language,
        country = country,
        localeName = localeName,
        languageName = languageName,
        countryName = countryName,
        isEightDot = isEightDot,
        grade = grade,
        variant = variant,
    )

    private val chinese = seed(
        "zh-CN-cbs", "zh", "CN", "中文", "中国", "中文（中国）",
        variant = "国家通用盲文方案",
    )
    private val chineseTw = seed("zh-TW-g1", "zh", "TW", "中文", "中国台湾", "中文（中国台湾）")
    private val chineseGeneric = seed("zh-g1", "zh", "", "中文", localeName = "中文（通用）")
    private val englishUs = seed("en-US-g1", "en", "US", "英语", "美国", "英语（美国）")
    private val englishUk = seed("en-GB-g1", "en", "GB", "英语", "英国", "英语（英国）")
    private val norwegian = seed("nb-g1", "nb", "", "挪威语", localeName = "挪威语（通用）")

    // ------------------------------------------------------------ 合规地区名

    @Test
    fun chinaRegionsAreNeverRenderedAsIndependentCountries() {
        assertEquals("中国台湾", BrailleTableCatalog.regionName("TW", "台湾", true))
        assertEquals("中国香港", BrailleTableCatalog.regionName("HK", "香港", true))
        assertEquals("中国澳门", BrailleTableCatalog.regionName("MO", "澳门", true))
        assertEquals("Taiwan, China", BrailleTableCatalog.regionName("TW", "Taiwan", false))
        assertEquals("Hong Kong, China", BrailleTableCatalog.regionName("hk", "Hong Kong", false))
        assertEquals("Macao, China", BrailleTableCatalog.regionName("MO", "Macao", false))
    }

    @Test
    fun otherCountriesUseLocalizedNameAndEmptyCountryStaysEmpty() {
        assertEquals("中国", BrailleTableCatalog.regionName("CN", "中国", true))
        assertEquals("Germany", BrailleTableCatalog.regionName("DE", "Germany", false))
        assertEquals("DE", BrailleTableCatalog.regionName("DE", "", false))
        assertEquals("", BrailleTableCatalog.regionName("", "anything", true))
    }

    @Test
    fun compliantLocaleNameUsesMappedRegionNeverSystemDisplayName() {
        assertEquals(
            "中文（中国台湾）",
            BrailleTableCatalog.compliantLocaleName(chineseTw, labels)
        )
        assertEquals(
            "中文（中国）",
            BrailleTableCatalog.compliantLocaleName(chinese, labels)
        )
    }

    @Test
    fun compliantLocaleNameMarksTablesWithoutCountryAsGeneric() {
        assertEquals(
            "挪威语（通用）",
            BrailleTableCatalog.compliantLocaleName(norwegian, labels)
        )
        // 无国家码的通用表与有国家码的地区表必须能靠文字区分。
        assertFalse(
            BrailleTableCatalog.compliantLocaleName(chineseGeneric, labels) ==
                BrailleTableCatalog.compliantLocaleName(chinese, labels)
        )
    }

    // -------------------------------------------------------------- 分组标题

    @Test
    fun languageTitleIsJustTheLanguageName() {
        assertEquals("中文", BrailleTableCatalog.languageTitle(chinese))
        assertEquals("中文", BrailleTableCatalog.languageTitle(chineseTw))
        assertEquals("英语", BrailleTableCatalog.languageTitle(englishUs))
        assertEquals("挪威语", BrailleTableCatalog.languageTitle(norwegian))
    }

    // -------------------------------------------------------------- 分组结构

    @Test
    fun buildGroupsByLanguageNotByCountry() {
        val canadianEnglish = seed(
            "en-CA-comp8", "en", "CA", "英语", "加拿大", isEightDot = true
        )
        val canadianFrench = seed("fr-CA-g1", "fr", "CA", "法语", "加拿大")
        val groups = BrailleTableCatalog.build(
            listOf(canadianEnglish, canadianFrench, chinese), "xx", labels
        )
        assertEquals("加拿大英语/法语现在分属英语组/法语组，共三组", 3, groups.size)
        assertEquals(setOf("en", "fr", "zh"), groups.map { it.language }.toSet())
        assertEquals(1, groups.single { it.language == "en" }.choices.size)
        assertEquals(1, groups.single { it.language == "fr" }.choices.size)
    }

    @Test
    fun buildKeepsDifferentCountriesOfOneLanguageInOneGroup() {
        val groups = BrailleTableCatalog.build(
            listOf(chinese, chineseTw, chineseGeneric, englishUs), "xx", labels
        )
        val zh = groups.single { it.language == "zh" }
        assertEquals(3, zh.choices.size)
        assertEquals(setOf("zh-CN-cbs", "zh-TW-g1", "zh-g1"), zh.choices.map { it.id }.toSet())
    }

    @Test
    fun buildPutsSystemLanguageGroupFirst() {
        val groups = BrailleTableCatalog.build(
            listOf(englishUs, englishUk, chinese, norwegian), "zh", labels
        )
        assertEquals("中文", groups.first().title)
        assertEquals("zh", groups.first().language)
        assertEquals("zh-CN-cbs", groups.first().choices.single().id)
    }

    @Test
    fun buildPutsSystemLanguageFirstEvenWhenItHasNoCountryCode() {
        // 谷歌 AddLanguageActivity 只按 language 置顶，不看 country。
        val groups = BrailleTableCatalog.build(
            listOf(chinese, englishUs, norwegian), "nb", labels
        )
        assertEquals("挪威语", groups.first().title)
        assertEquals("nb", groups.first().language)
    }

    @Test
    fun buildKeepsEveryTableExactlyOnce() {
        val seeds = (1..72).map { index ->
            seed("t$index", "lang${index % 9}", "", "语言${index % 9}", grade = index % 4)
        }
        val groups = BrailleTableCatalog.build(seeds, "xx", labels)
        val ids = groups.flatMap { it.choices }.map { it.id }
        assertEquals(72, ids.size)
        assertEquals(72, ids.toSet().size)
    }

    @Test
    fun gradeCountUsesLocaleNotLanguage() {
        // 英语组里同时有美国/英国表；等级判断必须仍按 locale，
        // 否则两张各自唯一的表都会被误标成「1 级 / 2 级」。
        val us = seed("en-US-g1", "en", "US", "英语", "美国", "英语（美国）")
        val uk = seed("en-GB-g1", "en", "GB", "英语", "英国", "英语（英国）")
        val groups = BrailleTableCatalog.build(listOf(us, uk), "xx", labels)
        val rendered = groups.flatMap { it.choices }.map { it.label }
        assertEquals(1, groups.size)
        assertEquals(2, rendered.size)
        assertTrue(
            "同语言不同国家不能互相抬高等级：实际 $rendered",
            rendered.none { it.contains("级") }
        )
    }

    @Test
    fun buildSortsSixDotBeforeEightDotAndByGrade() {
        val eight = seed("eight", "de", "DE", "德语", "德国", isEightDot = true, grade = 0)
        val g2 = seed("g2", "de", "DE", "德语", "德国", grade = 2)
        val g0 = seed("g0", "de", "DE", "德语", "德国", grade = 0)
        val groups = BrailleTableCatalog.build(listOf(eight, g2, g0), "de", labels)
        assertEquals(listOf("g0", "g2", "eight"), groups.single().choices.map { it.id })
    }

    @Test
    fun buildSortsGenericTablesBeforeRegionalOnesWithinLanguage() {
        val groups = BrailleTableCatalog.build(
            listOf(chinese, chineseGeneric, chineseTw), "zh", labels
        )
        assertEquals(
            listOf("zh-g1", "zh-CN-cbs", "zh-TW-g1"),
            groups.single().choices.map { it.id }
        )
    }

    // -------------------------------------------------------------- 条目命名

    @Test
    fun entryLabelMatchesLegacyEightDotRule() {
        val eight = chinese.copy(id = "eight", isEightDot = true, variant = "")
        assertEquals("中文（中国）（八点）", BrailleTableCatalog.entryLabel(eight, 3, labels))
    }

    @Test
    fun entryLabelMatchesLegacyChineseVariantRule() {
        assertEquals(
            "中文（中国）（国家通用盲文方案）",
            BrailleTableCatalog.entryLabel(chinese, 5, labels)
        )
    }

    @Test
    fun entryLabelAddsGradeOnlyWhenSameLocaleHasSeveralSixDotTables() {
        val first = englishUs.copy(id = "a", grade = 1)
        val second = englishUs.copy(id = "b", grade = 2)
        assertEquals("英语（美国）", BrailleTableCatalog.entryLabel(first, 1, labels))
        assertEquals("英语（美国） - 2 级", BrailleTableCatalog.entryLabel(second, 2, labels))
    }

    @Test
    fun entryLabelUsesVariantGradeTemplateWhenBothArePresent() {
        val table = englishUs.copy(grade = 2, variant = "UEB")
        assertEquals(
            "英语（美国）（UEB）- 2 级",
            BrailleTableCatalog.entryLabel(table, 2, labels)
        )
    }

    // -------------------------------------------------------------- 当前值

    @Test
    fun labelForValueReturnsAutoLabelForAutoOrEmpty() {
        val groups = BrailleTableCatalog.build(listOf(chinese), "zh", labels)
        assertEquals("自动（中文）", BrailleTableCatalog.labelForValue("auto", "自动（中文）", groups, "读取中"))
        assertEquals("自动（中文）", BrailleTableCatalog.labelForValue("", "自动（中文）", groups, "读取中"))
    }

    @Test
    fun labelForValueFindsTableById() {
        val groups = BrailleTableCatalog.build(listOf(chinese), "zh", labels)
        assertEquals(
            "中文（中国）（国家通用盲文方案）",
            BrailleTableCatalog.labelForValue("zh-CN-cbs", "自动", groups, "读取中")
        )
    }

    @Test
    fun labelForValueNeverReadsBlankWhenServiceIsNotReady() {
        assertEquals(
            "正在读取盲文表",
            BrailleTableCatalog.labelForValue("unknown-id", "", emptyList(), "正在读取盲文表")
        )
        assertEquals(
            "自动（无）",
            BrailleTableCatalog.labelForValue("unknown-id", "自动（无）", emptyList(), "正在读取盲文表")
        )
    }

    @Test
    fun buildOutputHasNonBlankLanguageCodesTitlesAndLabels() {
        val groups = BrailleTableCatalog.build(
            listOf(chinese, chineseTw, chineseGeneric, englishUs, englishUk, norwegian),
            "zh", labels
        )
        groups.forEach { group ->
            assertTrue("语言码不能为空", group.language.isNotBlank())
            assertEquals(group.language.lowercase(), group.language)
            assertTrue("语言标题不能为空", group.title.isNotBlank())
            assertTrue("语言组不能是空壳", group.choices.isNotEmpty())
            group.choices.forEach { choice ->
                assertTrue("条目 ID 不能为空", choice.id.isNotBlank())
                assertTrue("条目名不能为空：${choice.id}", choice.label.isNotBlank())
            }
        }
        assertEquals(groups.size, groups.map { it.language }.toSet().size)
        assertFalse(
            "「自动」由界面单独渲染，不能被当成真实盲文表塞进分组",
            groups.flatMap { it.choices }.any { it.id == BrailleTableCatalog.AUTO_VALUE }
        )
    }
}
