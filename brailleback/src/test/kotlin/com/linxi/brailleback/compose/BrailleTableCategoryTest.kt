/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback.compose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 盲文表「用途分类」与「无国家码补国别」的行为测试（不依赖 Android）。
 *
 * 守住两件事：
 * 1) 6 张非语言表、12 张古代/语族表不能混进语言列表——盲人在「德语」下面
 *    点开看到「de-chess」是纯粹的困惑；它们各自成组，排在所有语言组之后。
 * 2) liblouis 的 bg.ctb、el.ctb 这类只写语言码的表要补上规范国，
 *    否则第二级只能读到「保加利亚语（通用）」，用户分不出是哪套规范。
 */
class BrailleTableCategoryTest {

    private val labels = BrailleTableCatalog.TableLabels(
        genericGroupSuffix = "（通用）",
        localePattern = "%1\$s（%2\$s）",
        eightDot = "%1\$s（八点）",
        variant = "%1\$s（%2\$s）",
        grade = "%1\$s - %2\$d 级",
        variantGrade = "%1\$s（%2\$s）- %3\$d 级",
        specialGroupTitle = "特殊用途表",
        legacyGroupTitle = "古代与语族表",
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

    @Test
    fun 特殊用途表按表名识别且不受扩展名影响() {
        assertEquals(
            BrailleTableCatalog.TableKind.SPECIAL,
            BrailleTableCatalog.kindOf("boxes.ctb"),
        )
        assertEquals(
            BrailleTableCatalog.TableKind.SPECIAL,
            BrailleTableCatalog.kindOf("de-chess"),
        )
        assertEquals(
            BrailleTableCatalog.TableKind.SPECIAL,
            BrailleTableCatalog.kindOf("en-ueb-math.ctb"),
        )
        assertEquals(
            BrailleTableCatalog.TableKind.SPECIAL,
            BrailleTableCatalog.kindOf("en-us-interline.ctb"),
        )
    }

    @Test
    fun 古代与语族表按表名识别() {
        listOf("akk.utb", "cop.utb", "cuneiform-transliterated.utb", "dra.ctb", "ethio-g1.ctb")
            .forEach {
                assertEquals(
                    "应识别为古代/语族表：$it",
                    BrailleTableCatalog.TableKind.LEGACY,
                    BrailleTableCatalog.kindOf(it),
                )
            }
    }

    @Test
    fun 普通语言表默认归语言类() {
        listOf("zhcn-g1.ctb", "en-us-g2.ctb", "bg.ctb", "de-chess".replace("chess", "g1"))
            .forEach {
                assertEquals(
                    "应识别为语言表：$it",
                    BrailleTableCatalog.TableKind.LANGUAGE,
                    BrailleTableCatalog.kindOf(it),
                )
            }
    }

    @Test
    fun 无国家码语言补规范国_跨国语言保持通用() {
        assertEquals("BG", BrailleTableCatalog.regionHintOf("bg"))
        assertEquals("GR", BrailleTableCatalog.regionHintOf("el"))
        assertEquals("CN", BrailleTableCatalog.regionHintOf("bo"))
        assertEquals("BG", BrailleTableCatalog.regionHintOf("BG"))
        // 跨国使用的不猜，仍走「（通用）」。
        assertEquals("", BrailleTableCatalog.regionHintOf("ur"))
        assertEquals("", BrailleTableCatalog.regionHintOf("sw"))
        // 中文方案的口径不从这里来。
        assertEquals("", BrailleTableCatalog.regionHintOf("zhcn"))
        assertEquals("", BrailleTableCatalog.regionHintOf("zh"))
    }

    @Test
    fun 特殊表与古代表各自成组且排在所有语言组之后() {
        val seeds = listOf(
            seed("bg.ctb", "bg", "", "保加利亚语"),
            seed("de-chess.ctb", "de", "DE", "德语", "德国", "德语（德国）"),
            seed("boxes.ctb", "en", "US", "英语", "美国", "英语（美国）"),
            seed("en-us-g1.ctb", "en", "US", "英语", "美国", "英语（美国）"),
            seed("akk.utb", "akk", "", "阿卡德语"),
            seed("zhcn-g1.ctb", "zhcn", "", "中文"),
        )

        val groups = BrailleTableCatalog.build(seeds, "zhcn", labels)

        assertEquals(
            "六个种子应落成四个组：语言(bg/en/zhcn) + 特殊 + 古代",
            5,
            groups.size,
        )
        // 系统语言置顶。
        assertEquals("zhcn", groups.first().language)
        // 特殊/古代固定在末尾，且用伪语言码。
        assertEquals(BrailleTableCatalog.SPECIAL_GROUP_KEY, groups[groups.size - 2].language)
        assertEquals("特殊用途表", groups[groups.size - 2].title)
        assertEquals(BrailleTableCatalog.LEGACY_GROUP_KEY, groups.last().language)
        assertEquals("古代与语族表", groups.last().title)

        // 特殊组里只有那两张，语言组里不能出现它们。
        val specialIds = groups[groups.size - 2].choices.map { it.id }.toSet()
        assertEquals(setOf("de-chess.ctb", "boxes.ctb"), specialIds)
        val languageIds = groups.filter {
            it.language != BrailleTableCatalog.SPECIAL_GROUP_KEY &&
                it.language != BrailleTableCatalog.LEGACY_GROUP_KEY
        }.flatMap { it.choices }.map { it.id }.toSet()
        assertTrue("语言组不得含 special 表", "de-chess.ctb" !in languageIds)
        assertTrue("语言组不得含古代表", "akk.utb" !in languageIds)
    }

    @Test
    fun 没有特殊或古代表时不产生空组() {
        val seeds = listOf(
            seed("en-us-g1.ctb", "en", "US", "英语", "美国", "英语（美国）"),
            seed("zhcn-g1.ctb", "zhcn", "", "中文"),
        )
        val groups = BrailleTableCatalog.build(seeds, "zhcn", labels)
        assertEquals(2, groups.size)
        assertTrue(
            "不该出现空分组",
            groups.none {
                it.language == BrailleTableCatalog.SPECIAL_GROUP_KEY ||
                    it.language == BrailleTableCatalog.LEGACY_GROUP_KEY
            },
        )
    }

    @Test
    fun 当前方案标签能取到特殊表的可读名() {
        val seeds = listOf(
            seed("en-us-g1.ctb", "en", "US", "英语", "美国", "英语（美国）"),
            seed("de-chess.ctb", "de", "DE", "德语", "德国", "德语（德国）", variant = "国际象棋"),
        )
        val groups = BrailleTableCatalog.build(seeds, "en", labels)
        assertEquals(
            "国际象棋",
            BrailleTableCatalog.labelForValue("de-chess.ctb", "自动选择", groups, "加载中"),
        )
    }

    @Test
    fun 特殊表不计入同语言等级统计() {
        // 英语两张六点表 + 一张象棋表：等级统计只看语言表，
        // 否则象棋表会被当成「第三张同 locale 表」，把另两张的等级后缀顶出来。
        val seeds = listOf(
            seed("en-us-g1.ctb", "en", "US", "英语", "美国", "英语（美国）", grade = 1),
            seed("en-us-g2.ctb", "en", "US", "英语", "美国", "英语（美国）", grade = 2),
            seed("en-chess.ctb", "en", "US", "英语", "美国", "英语（美国）"),
        )
        val english = BrailleTableCatalog.build(seeds, "en", labels)
            .first { it.language == "en" }
        assertEquals(2, english.choices.size)
        assertTrue(
            "两张语言表应带等级后缀",
            english.choices.all { it.label.contains("级") },
        )
    }
}
