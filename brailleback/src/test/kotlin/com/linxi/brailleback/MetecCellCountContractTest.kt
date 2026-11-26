/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Metec 驱动 `setCellCount()` 取整回归的静态契约。
 *
 * 背景：`moduleCount` 一度被改成整除（向下取整），42 格只分 5 模块（40 字节），
 * 尾部别格与状态格发不出去。本测试直接读 C 源码复核三件事：
 *   1. 取整公式必须是向上取整（ceil），不能退化成整除；
 *   2. `count > MT_CELLS_MAXIMUM` 越界守卫保留且返回 0；
 *   3. 模块数映射表（42→6 / 22→3 / 40→5）与源码常量一致，且容量落在
 *      `newCells[MT_CELLS_MAXIMUM]` 80 字节与 `writeModule[MT_MODULES_MAXIMUM]` 10 项内。
 *
 * 说明：C 侧算术的真实执行由 `tools/metec_cellcount_host_test.c`（NDK clang 编出的
 * host 可执行程序）覆盖；本测试跑在 JVM 门禁里，只做源码形态锚定 + 常量解析 +
 * 映射复算，常量值一律从 braille.c 源码解析，不在 Kotlin 里另抄一份。
 */
class MetecCellCountContractTest {

    private fun projectFile(path: String): File {
        val candidates = listOf(File(path), File("..", path), File("../..", path))
        return candidates.firstOrNull { it.isFile }
            ?: error("找不到 $path（cwd=${System.getProperty("user.dir")}）")
    }

    private fun text(path: String) = projectFile(path).readText()

    private val brailleC =
        "service/jni/brlttywrapper/third_party/brltty/Drivers/Braille/Metec/braille.c"

    private fun defineInt(source: String, name: String): Int =
        Regex("""#define\s+$name\s+(\d+)""")
            .find(source)?.groupValues?.get(1)?.toInt()
            ?: error("braille.c 缺少 #define $name")

    /** 从源码切出 setCellCount 函数体，避免把别处的同名片段算进来。 */
    private fun cellCountBody(source: String): String =
        source.substringAfter("setCellCount (BrailleDisplay *brl, unsigned char count) {")
            .substringBefore("\nstatic ")

    @Test
    fun ceilFormulaGuardAndRestoredCommentAreInSource() {
        val source = text(brailleC)
        val body = cellCountBody(source)

        // 向上取整公式：(count + MODULE_SIZE - 1) / MODULE_SIZE
        assertTrue(
            "moduleCount 必须用向上取整公式",
            body.contains("((brl->data->cellCount = count) + MT_MODULE_SIZE - 1) / MT_MODULE_SIZE")
        )
        // 不得退化成整除（向下取整）回归写法
        assertFalse(
            "moduleCount 不能退回整除写法",
            Regex("""\(brl->data->cellCount = count\) / MT_MODULE_SIZE;""").containsMatchIn(body)
        )

        // 越界守卫保留：超上限记日志并返回 0
        assertTrue("缺少 count > MT_CELLS_MAXIMUM 守卫", body.contains("if (count > MT_CELLS_MAXIMUM)"))
        assertTrue("越界分支必须 return 0", body.contains("return 0;"))

        // 恢复的修复注释按行锚定，防止再次被整段删掉
        assertTrue(
            "修复注释缺少 42 -> 6 模块（48 字节）说明行",
            source.contains("改为向上取整：42 -> 6 个模块（48 字节）")
        )

        // 容量数组声明：newCells 80 字节、writeModule 10 项
        assertTrue(source.contains("unsigned char newCells[MT_CELLS_MAXIMUM];"))
        assertTrue(source.contains("unsigned char writeModule[MT_MODULES_MAXIMUM];"))
    }

    @Test
    fun moduleCountMappingMatchesCeilAndFitsCapacity() {
        val source = text(brailleC)
        val moduleSize = defineInt(source, "MT_MODULE_SIZE")
        val modulesMaximum = defineInt(source, "MT_MODULES_MAXIMUM")
        // MT_CELLS_MAXIMUM = MT_MODULES_MAXIMUM * MT_MODULE_SIZE，从源码表达式复算
        assertTrue(
            "MT_CELLS_MAXIMUM 必须定义为 MT_MODULES_MAXIMUM * MT_MODULE_SIZE",
            Regex("""#define\s+MT_CELLS_MAXIMUM\s+\(\s*MT_MODULES_MAXIMUM\s*\*\s*MT_MODULE_SIZE\s*\)""")
                .containsMatchIn(source)
        )
        val cellsMaximum = modulesMaximum * moduleSize

        fun ceilModules(cells: Int) = (cells + moduleSize - 1) / moduleSize

        // 42 → 6、22 → 3、40 → 5；每个映射都复核字节数与 writeModule 项数容量。
        listOf(42 to 6, 22 to 3, 40 to 5).forEach { (cells, expectedModules) ->
            val modules = ceilModules(cells)
            assertEquals("$cells 格应分 $expectedModules 模块", expectedModules, modules)
            assertTrue(
                "$cells 格的 ${modules * moduleSize} 字节不能超过 newCells $cellsMaximum 字节",
                modules * moduleSize <= cellsMaximum
            )
            assertTrue(
                "$cells 格的 $modules 项不能超过 writeModule $modulesMaximum 项",
                modules <= modulesMaximum
            )
        }
    }

    @Test
    fun outOfRangeIsRejectedAndBoundary80StaysLegal() {
        val source = text(brailleC)
        val moduleSize = defineInt(source, "MT_MODULE_SIZE")
        val modulesMaximum = defineInt(source, "MT_MODULES_MAXIMUM")
        val cellsMaximum = modulesMaximum * moduleSize

        // 越界：81 > 80，守卫必须拒绝（源码里 return 0），不能被当成合法格数
        assertTrue("81 格超过 $cellsMaximum 上限", 81 > cellsMaximum)
        val body = cellCountBody(source)
        assertTrue("越界守卫位于 setCellCount 开头", body.indexOf("count > MT_CELLS_MAXIMUM") <
            body.indexOf("moduleCount ="))

        // 合法上限 80 → 10 模块，80 字节，恰好顶满容量，不能被守卫误拒
        val boundaryModules = (cellsMaximum + moduleSize - 1) / moduleSize
        assertEquals("80 格应分 10 模块", modulesMaximum, boundaryModules)
        assertEquals("80 格的字节数恰好用满 newCells", cellsMaximum, boundaryModules * moduleSize)
    }
}
