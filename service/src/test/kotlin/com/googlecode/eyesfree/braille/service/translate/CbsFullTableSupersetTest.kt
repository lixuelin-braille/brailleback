package com.googlecode.eyesfree.braille.service.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 数据不变量：zhcn-cbs-full.ctb 必须是 zhcn-cbs.ctb 的严格超集。
 *
 * 默认表因此可以安全地选全字符版：常用字的盲文值逐字相同，只是多覆盖
 * 生僻字/异体字；如果未来更新破坏了这一点，本测试会失败。
 */
class CbsFullTableSupersetTest {

    private fun locate(name: String): File? {
        for (prefix in listOf("", "service/", "../service/")) {
            val f = File(prefix + "jni/liblouiswrapper/liblouis/tables/" + name)
            if (f.isFile) {
                return f
            }
        }
        return null
    }

    private fun decode(escaped: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i + 5 < escaped.length) {
            if (escaped[i] == '\\' && escaped[i + 1] == 'x') {
                sb.append(escaped.substring(i + 2, i + 6).toInt(16).toChar())
                i += 6
            } else {
                sb.append(escaped[i])
                i++
            }
        }
        return sb.toString()
    }

    private fun parse(file: File): Pair<Map<String, String>, Map<String, String>> {
        val signs = HashMap<String, String>()
        val always = HashMap<String, String>()
        for (raw in file.readLines()) {
            val p = raw.trim().split(Regex("\\s+"))
            if (p.size != 3 || !p[1].startsWith("\\x")) {
                continue
            }
            val text = decode(p[1])
            when (p[0]) {
                "sign" -> if (text.length == 1) signs[text] = p[2]
                "always" -> always[text] = p[2]
            }
        }
        return Pair(signs, always)
    }

    @Test
    fun fullTableIsStrictSupersetOfCommonTable() {
        val commonFile = locate("zhcn-cbs.ctb")
        val fullFile = locate("zhcn-cbs-full.ctb")
        assumeTrue("未找到 cbs 表文件", commonFile != null && fullFile != null)
        val (commonSigns, commonAlways) = parse(requireNotNull(commonFile) { "未找到 zhcn-cbs.ctb" })
        val (fullSigns, fullAlways) = parse(requireNotNull(fullFile) { "未找到 zhcn-cbs-full.ctb" })

        assertTrue("常用字表为空", commonSigns.isNotEmpty())
        assertTrue("全字符表应覆盖更多字符", fullSigns.size > commonSigns.size)

        for ((ch, dots) in commonSigns) {
            assertEquals("字符 $ch 的盲文值不一致", dots, fullSigns[ch])
        }
        for ((word, dots) in commonAlways) {
            assertEquals("词组 $word 的盲文值不一致", dots, fullAlways[word])
        }
        println(
            "[CBS] common signs=${commonSigns.size} full signs=${fullSigns.size} " +
                "common always=${commonAlways.size} full always=${fullAlways.size}"
        )
    }
}
