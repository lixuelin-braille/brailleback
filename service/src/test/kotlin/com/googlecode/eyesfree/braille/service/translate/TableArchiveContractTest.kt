package com.googlecode.eyesfree.braille.service.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * 表清单与表包的一致性不变量。
 *
 * 用户能选到的每一张表都必须真的在 `translationtables.zip` 里，而且它 include 的所有
 * 依赖也必须一起打进去。少一个依赖，liblouis 在运行期编译这张表就会失败——界面上能选中、
 * 点显器上却什么都没有，盲用户无法自查，只能以为「这个语言坏了」。
 */
class TableArchiveContractTest {

    private val tableList: File by lazy { locate("res/xml/tablelist.xml") }
    private val archive: File by lazy { locate("res/raw/translationtables.zip") }

    private fun locate(relative: String): File {
        // 单测的工作目录随模块而定（根目录或 service/），两种都试一遍
        for (prefix in listOf("", "../", "../../", "service/", "../service/")) {
            val f = File(prefix + "src/main/" + relative)
            if (f.isFile) {
                return f
            }
        }
        error("找不到 $relative")
    }

    private fun entries(): List<String> {
        val names = ArrayList<String>()
        ZipFile(archive).use { zip ->
            for (e in zip.entries()) {
                if (!e.isDirectory) {
                    names.add(e.name)
                }
            }
        }
        return names
    }

    private fun rowTexts(): List<String> =
        tableList.readText()
            .replace(Regex("<!--[\\s\\S]*?-->"), "")
            .let { Regex("""<table\b([^>]*?)/>""").findAll(it) }
            .map { m -> m.groupValues[1] }
            .toList()

    private fun attr(attrs: String, name: String): String? =
        Regex("""$name="([^"]*)"""").find(attrs)?.groupValues?.get(1)

    private fun includes(entryName: String): List<String> {
        val body = StringBuilder()
        ZipFile(archive).use { zip ->
            val e = zip.getEntry(entryName) ?: return emptyList()
            zip.getInputStream(e).bufferedReader().use { body.append(it.readText()) }
        }
        val includeRule = Regex("""^include\s+([^\s#]+)""")
        return body.lineSequence()
            .map { it.trim() }
            .mapNotNull { line -> includeRule.find(line)?.groupValues?.get(1) }
            .map { it.substringAfterLast('/') }
            .toList()
    }

    @Test
    fun everyRegisteredTableShipsInTheArchive() {
        assumeTrue("未找到表清单或表包", tableList.isFile && archive.isFile)
        val entries = entries()
        val missing = rowTexts().mapNotNull { attr(it, "fileName") }
            .filter { "liblouis/tables/$it" !in entries }
        assertTrue("清单登记了但没打进包的表：$missing", missing.isEmpty())
    }

    @Test
    fun everyRegisteredTableHasItsFullIncludeClosureInTheArchive() {
        assumeTrue("未找到表清单或表包", tableList.isFile && archive.isFile)
        val entries = entries().toSet()
        val roots = rowTexts().mapNotNull { attr(it, "fileName") }
        assertTrue("清单为空", roots.isNotEmpty())
        val unreachable = ArrayList<String>()
        val seen = HashSet<String>()
        val queue = ArrayDeque(roots.map { "liblouis/tables/$it" })
        while (queue.isNotEmpty()) {
            val entry = queue.removeFirst()
            if (!seen.add(entry)) {
                continue
            }
            if (entry !in entries) {
                unreachable.add(entry)
                continue
            }
            for (dep in includes(entry)) {
                queue.add("liblouis/tables/$dep")
            }
        }
        assertTrue("include 依赖没有打进包：$unreachable", unreachable.isEmpty())
    }

    @Test
    fun archiveEntriesAllLiveUnderThePrefixLiblouisReads() {
        assumeTrue("未找到表包", archive.isFile)
        val strays = entries().filter { !it.startsWith("liblouis/tables/") }
        assertTrue("表包里出现了 liblouis/tables/ 之外的条目：$strays", strays.isEmpty())
    }

    @Test
    fun everyTableIdAndLocaleIsUsableByTheCatalog() {
        assumeTrue("未找到表清单", tableList.isFile)
        val rows = rowTexts()
        assertNotNull(rows.firstOrNull())
        val ids = rows.mapNotNull { attr(it, "id") }
        assertEquals("id 不能重复", ids.size, ids.toSet().size)
        val badLocales = rows.filter { r ->
            val locale = attr(r, "locale")
            locale.isNullOrBlank() || !Regex("""^[a-z]{2,3}(_[A-Z]{2})?$""").matches(locale)
        }.map { attr(it, "id") }
        assertTrue("locale 必须是 ll 或 ll_CC：$badLocales", badLocales.isEmpty())
        val badGrade = rows.filter { attr(it, "dots") == "8" && attr(it, "grade") != null }
            .mapNotNull { attr(it, "id") }
        assertTrue("八点表不能声明 grade：$badGrade", badGrade.isEmpty())
    }
}
