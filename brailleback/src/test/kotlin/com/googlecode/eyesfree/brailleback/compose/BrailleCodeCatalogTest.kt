/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 谷歌式多选方案（Code）模型的纯逻辑单测。 */
class BrailleCodeCatalogTest {

    private val tableLabels = BrailleTableCatalog.TableLabels(
        genericGroupSuffix = "（通用）",
        localePattern = "%1\$s（%2\$s）",
        eightDot = "%1\$s（八点）",
        variant = "%1\$s（%2\$s）",
        grade = "%1\$s - %2\$d 级",
        variantGrade = "%1\$s（%2\$s）- %3\$d 级",
    )

    private val codeLabels = BrailleCodeCatalog.CodeLabels(
        eightDotName = "%1\$s八点计算机盲文",
        sixDotSummary = "六点文学盲文",
        eightDotSummary = "八点计算机盲文",
        enabled = "已启用",
        disabled = "未启用",
        currentSuffix = "，当前正在使用",
    )

    private fun seed(
        id: String,
        language: String,
        country: String = "",
        eightDot: Boolean = false,
        grade: Int = 1,
        variant: String = "",
        languageName: String = language,
    ): BrailleTableCatalog.TableSeed {
        val countryName = if (country.isEmpty()) "" else country
        return BrailleTableCatalog.TableSeed(
            id = id,
            language = language,
            country = country,
            localeName = "",
            languageName = languageName,
            countryName = countryName,
            isEightDot = eightDot,
            grade = grade,
            variant = variant,
        ).let {
            it.copy(localeName = BrailleTableCatalog.compliantLocaleName(it, tableLabels))
        }
    }

    private fun groups(seeds: List<BrailleTableCatalog.TableSeed>): List<BrailleCodeCatalog.CodeGroup> {
        val tableGroups = BrailleTableCatalog.build(seeds, "zh", tableLabels)
        return BrailleCodeCatalog.build(tableGroups, seeds, tableLabels, codeLabels)
    }

    @Test
    fun eightDotIsAnIndependentCodeWithACompleteName() {
        val codes = groups(
            listOf(
                seed("zh-six", "zh", "CN", variant = "现行盲文", languageName = "中文"),
                seed("zh-eight", "zh", "CN", eightDot = true, languageName = "中文"),
            )
        ).single().codes
        assertEquals(2, codes.size)
        val eight = codes.single { it.eightDot }
        assertEquals("中文（CN）八点计算机盲文", eight.label)
        assertEquals("八点计算机盲文", eight.summary)
        assertFalse("八点方案名不能再是含糊的「（八点）」后缀", eight.label.contains("（八点）"))
        assertFalse(codes.single { !it.eightDot }.eightDot)
        assertFalse(eight.supportsContracted)
    }

    @Test
    fun chineseVariantsStaySeparateCodesAndAreNotCollapsedToABoolean() {
        val seeds = listOf(
            seed("cbs", "zh", "CN", variant = "国家通用盲文方案", languageName = "中文"),
            seed("cbs-full", "zh", "CN", variant = "国家通用盲文方案（全字符）", languageName = "中文"),
            seed("current", "zh", "CN", variant = "现行盲文", languageName = "中文"),
            seed("tone", "zh", "CN", variant = "现行盲文（标调）", languageName = "中文"),
            seed("g2", "zh", "CN", grade = 2, variant = "双拼盲文", languageName = "中文"),
        )
        val codes = groups(seeds).single().codes
        assertEquals("中文五张表必须各自成方案，不能被 supportsContracted 压掉", 5, codes.size)
        assertTrue(codes.any { it.label.contains("双拼盲文") })
        assertTrue(codes.any { it.supportsContracted })
        assertEquals(5, codes.map { it.id }.toSet().size)
    }

    @Test
    fun normalizeOnlyFiltersMissingIdsAndNeverInjectsTheCurrentCode() {
        val g = groups(
            listOf(
                seed("a", "zh", "CN", languageName = "中文"),
                seed("b", "zh", "CN", variant = "现行盲文", languageName = "中文"),
                seed("c", "en", "US", languageName = "英语"),
            )
        )
        val all = BrailleCodeCatalog.allIds(g)
        assertEquals(setOf("a", "b", "c"), all)
        val normalized = BrailleCodeCatalog.normalize(setOf("a", "ghost"), g, "zh")
        assertEquals(setOf("a"), normalized)
        assertFalse("已不存在的表 ID 必须被过滤", "ghost" in normalized)
        assertFalse(
            "池管当前码（谷歌 readCurrentActiveInputCodeAndCorrect）：池里不能凭空多出当前码",
            "c" in normalized
        )
    }

    @Test
    fun outOfPoolCurrentCodeIsCorrectedIntoThePool() {
        val g = groups(
            listOf(
                seed("zh1", "zh", "CN", languageName = "中文"),
                seed("zh2", "zh", "CN", variant = "现行盲文", languageName = "中文"),
                seed("en1", "en", "US", languageName = "英语"),
            )
        )
        // 老用户：池里只有中文那两张，存着的当前方案却是英文表。
        val pool = BrailleCodeCatalog.normalize(setOf("zh1", "zh2"), g, "zh")
        assertEquals(setOf("zh1", "zh2"), pool)
        assertEquals(
            "当前方案不在池内时纠正到池内，不是把它塞进池",
            "zh1",
            BrailleCodeCatalog.resolveCurrent(pool, "en1", g, "zh")
        )
        assertEquals(
            "池里没有同语言的码才跨语言取池内第一项",
            "zh1",
            BrailleCodeCatalog.resolveCurrent(setOf("zh1"), "en1", g, "en")
        )
    }

    @Test
    fun firstRunDefaultsToSystemLanguageCodes() {
        val g = groups(
            listOf(
                seed("a", "zh", "CN", languageName = "中文"),
                seed("b", "zh", "CN", variant = "现行盲文", languageName = "中文"),
                seed("c", "en", "US", languageName = "英语"),
            )
        )
        assertEquals(
            setOf("a", "b"),
            BrailleCodeCatalog.normalize(emptySet(), g, "zh")
        )
    }

    @Test
    fun toggleAndCanDisableNeverAllowAnEmptySelection() {
        val enabled = setOf("a", "b")
        assertEquals(setOf("a", "b", "c"), BrailleCodeCatalog.toggle(enabled, "c"))
        assertEquals(setOf("a"), BrailleCodeCatalog.toggle(enabled, "b"))
        assertTrue(BrailleCodeCatalog.canDisable(enabled, "a"))
        assertFalse(BrailleCodeCatalog.canDisable(setOf("a"), "a"))
        assertTrue("取消未启用项永远允许", BrailleCodeCatalog.canDisable(enabled, "ghost"))
    }

    @Test
    fun resolveCurrentPrefersSameLanguageThenGlobalThenAuto() {
        val g = groups(
            listOf(
                seed("zh1", "zh", "CN", languageName = "中文"),
                seed("zh2", "zh", "CN", variant = "现行盲文", languageName = "中文"),
                seed("en1", "en", "US", languageName = "英语"),
            )
        )
        assertEquals("zh1", BrailleCodeCatalog.resolveCurrent(setOf("zh1"), "zh2", g, "zh"))
        assertEquals("en1", BrailleCodeCatalog.resolveCurrent(setOf("en1"), "zh2", g, "zh"))
        assertEquals(
            "auto",
            BrailleCodeCatalog.resolveCurrent(emptySet(), "zh2", g, "zh")
        )
        assertEquals(
            "auto",
            BrailleCodeCatalog.resolveCurrent(setOf("zh1"), "auto", g, "zh")
        )
    }

    @Test
    fun nextDotTargetStaysInTheCurrentLanguageAndRespectsEnabledCodes() {
        val g = groups(
            listOf(
                seed("zh-six", "zh", "CN", languageName = "中文"),
                seed("zh-eight", "zh", "CN", eightDot = true, languageName = "中文"),
                seed("en-six", "en", "US", languageName = "英语"),
                seed("en-eight", "en", "US", eightDot = true, languageName = "英语"),
            )
        )
        val all = BrailleCodeCatalog.allIds(g)
        assertEquals(
            "六点切八点必须留在中文组，不能跳到英文八点表",
            "zh-eight",
            BrailleCodeCatalog.nextDotTarget(all, "zh-six", g, "zh")
        )
        assertEquals(
            "zh-six",
            BrailleCodeCatalog.nextDotTarget(all, "zh-eight", g, "zh")
        )
        assertNull(
            "目标方案未启用时保持原状",
            BrailleCodeCatalog.nextDotTarget(setOf("zh-six"), "zh-six", g, "zh")
        )
        assertEquals(
            "自动模式按系统语言组切到八点",
            "zh-eight",
            BrailleCodeCatalog.nextDotTarget(all, "auto", g, "zh")
        )
    }

    @Test
    fun enabledCountAndLabelForCode() {
        val g = groups(
            listOf(
                seed("a", "zh", "CN", languageName = "中文"),
                seed("b", "zh", "CN", variant = "现行盲文", languageName = "中文"),
                seed("c", "en", "US", languageName = "英语"),
            )
        )
        assertEquals(1, BrailleCodeCatalog.enabledCount(g, setOf("a", "c"), "zh"))
        assertEquals(0, BrailleCodeCatalog.enabledCount(g, setOf("a"), "en"))
        assertEquals("自动", BrailleCodeCatalog.labelForCode("auto", "自动", g, "读取中"))
        assertEquals("读取中", BrailleCodeCatalog.labelForCode("", "", g, "读取中"))
        assertEquals("自动", BrailleCodeCatalog.labelForCode("ghost", "自动", g, "读取中"))
        assertTrue(BrailleCodeCatalog.labelForCode("b", "", g, "读取中").contains("现行盲文"))
    }

    @Test
    fun realTableListKeepsEveryTableAndMakesAllEightDotTablesIndependentCodes() {
        val candidates = listOf(File("service/src/main/res/xml/tablelist.xml"),
            File("../service/src/main/res/xml/tablelist.xml"),
            File("../../service/src/main/res/xml/tablelist.xml"))
        val xml = candidates.firstOrNull { it.isFile }?.readText()
            ?.replace(Regex("""<!--[\s\S]*?-->"""), "")
            ?: error("找不到 tablelist.xml")
        val rows = Regex("""<table\b([^>]*?)/>""").findAll(xml).toList()
        fun attr(s: String, name: String) = Regex("""$name="([^"]+)"""").find(s)?.groupValues?.get(1)
        val seeds = rows.mapNotNull { m ->
            val a = m.groupValues[1]
            val id = attr(a, "id") ?: return@mapNotNull null
            val locale = attr(a, "locale") ?: return@mapNotNull null
            val parts = locale.split("_")
            seed(
                id = id,
                language = parts[0],
                country = parts.getOrElse(1) { "" },
                eightDot = attr(a, "dots") == "8",
                grade = attr(a, "grade")?.toIntOrNull() ?: 0,
                variant = attr(a, "variant") ?: "",
                languageName = parts[0],
            )
        }
        val codes = groups(seeds).flatMap { it.codes }
        assertEquals("72 张表一个都不能丢", 72, codes.size)
        assertEquals("72 个方案 ID 必须互不重复", 72, codes.map { it.id }.toSet().size)
        assertEquals("实测 25 张八点表必须各自成为独立方案", 25, codes.count { it.eightDot })
        assertTrue(codes.filter { it.eightDot }.all { it.label.contains("八点计算机盲文") })
        assertFalse(codes.any { it.label.contains("（八点）") })
        assertEquals("中文组 7 个方案（5 张六点 + 2 张八点）", 7,
            groups(seeds).single { it.language == "zh" }.codes.size)
    }
}
