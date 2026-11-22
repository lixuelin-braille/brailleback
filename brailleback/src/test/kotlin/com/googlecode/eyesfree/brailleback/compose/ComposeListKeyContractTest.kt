/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback.compose

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Compose 列表键的结构契约。
 *
 * 背景：`LazyColumn` 的重复 key 不是「显示错乱」，而是直接抛
 * `IllegalArgumentException: Key "..." was already used` 把整屏崩掉。
 * 真机上已经因为「同一台点显器命中两条驱动条目」崩过一次，所以这里钉死两条：
 *
 * 1. 任何 `items(...)` / `itemsIndexed(...)` 都必须显式给 `key`，不许走默认；
 * 2. 设备候选列表必须先按地址去重，不能只靠服务端保证唯一。
 */
class ComposeListKeyContractTest {

    private fun composeDir(): File {
        val relative = "brailleback/src/main/java/com/googlecode/eyesfree/brailleback/compose"
        val candidates = listOf(
            File(relative),
            File("..", relative),
            File("../..", relative),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("找不到目录 $relative（cwd=${System.getProperty("user.dir")}）")
    }

    private fun sourceFiles(): List<File> =
        composeDir().listFiles { file -> file.extension == "kt" }
            ?.sortedBy { it.name }
            ?.toList()
            ?: emptyList()

    /** 从 [start] 处的左括号起，返回配对右括号的下标（含括号内的嵌套）。 */
    private fun matchingCloseParen(text: String, start: Int): Int {
        var depth = 0
        for (index in start until text.length) {
            when (text[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return text.length - 1
    }

    @Test
    fun `每个 lazy items 调用都必须显式给 key`() {
        val offenders = ArrayList<String>()
        val calls = Regex("\\bitems(Indexed)?\\(")
        for (file in sourceFiles()) {
            val text = file.readText()
            for (match in calls.findAll(text)) {
                val open = text.indexOf('(', match.range.first)
                val close = matchingCloseParen(text, open)
                val argumentText = text.substring(open, close + 1)
                if (!argumentText.contains("key =")) {
                    val line = text.substring(0, match.range.first).count { it == '\n' } + 1
                    offenders.add("${file.name}:$line 的 items 调用没有 key")
                }
            }
        }
        assertTrue(
            "以下列表没给 key，重复项会直接崩屏：" + offenders.joinToString("；"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `设备候选列表按地址去重后才进列表`() {
        val text = File(composeDir(), "DeviceConnectionScreen.kt").readText()
        assertTrue(
            "候选列表必须先 distinctBy(address)：服务端去重只覆盖它已知的来源，" +
                "客户端这一层是最后的兜底",
            text.contains("distinctBy { it.address }"),
        )
        assertTrue(
            "候选列表的 key 必须是设备地址，否则同一地址换名会重建行",
            text.contains("key = { it.address }"),
        )
    }
}
