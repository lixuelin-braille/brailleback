/*
 * Copyright (C) 2026 The BrailleBack Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.googlecode.eyesfree.braille.service.display

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动重连守卫的生产接线契约。
 *
 * `AutoRetryGate` 自身的纯状态机由 `AutoRetryGateTest` 覆盖；本测试防的是另一类退化：
 * 生产路径在进守卫之前就把活跃态提前 return 掉，导致守卫永远收不到 `active = true`，
 * settle 稳定期记录不到起点、退化成死代码。这里从源码层面断言 `prepareAutomaticRetry`
 * 先取 `isConnectionActive()` 再把它喂进 `shouldAllow`。
 */
class AutoRetryWiringContractTest {

    private fun projectFile(path: String): File {
        val candidates = listOf(File(path), File("..", path), File("../..", path))
        return candidates.firstOrNull { it.isFile }
            ?: error("找不到 $path（cwd=${System.getProperty("user.dir")}）")
    }

    private val flowSource =
        "service/src/main/java/com/googlecode/eyesfree/braille/service/display/DisplayServiceConnectionFlow.kt"

    /** 抽出 `prepareAutomaticRetry` 扩展函数体，避免同文件其它函数里的同名调用造成假通过。 */
    private fun prepareBody(): String {
        val source = projectFile(flowSource).readText()
        val start = source.indexOf("fun DisplayService.prepareAutomaticRetry(")
        assertTrue("找不到 prepareAutomaticRetry 函数", start >= 0)
        val open = source.indexOf('{', start)
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, i + 1)
                }
            }
        }
        error("prepareAutomaticRetry 括号不匹配")
    }

    @Test
    fun `active state is read before the gate and fed into shouldAllow`() {
        val body = prepareBody()
        // 进守卫之前先取活跃态，守卫据此记录 settle 起点。
        assertTrue(
            "必须在进守卫之前先取 val active = isConnectionActive()",
            body.contains("val active = isConnectionActive()"),
        )
        assertTrue(
            "活跃态必须作为实参喂进 shouldAllow(active, ...)，不能传常量 false",
            body.contains("shouldAllow(active, inFlight, sourceKey)"),
        )
    }

    @Test
    fun `no early active return shadows the gate`() {
        val body = prepareBody()
        // 活跃短路由守卫的 active 分支承担；函数体内不许再有一道提前的
        // `if (isConnectionActive()) return`，否则守卫收不到活跃记录。
        assertFalse(
            "prepareAutomaticRetry 内不许在守卫之前提前 return 活跃态",
            body.contains("if (isConnectionActive())"),
        )
    }
}
