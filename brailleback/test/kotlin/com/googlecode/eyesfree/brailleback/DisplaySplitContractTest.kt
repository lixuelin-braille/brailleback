/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * DisplayManager 拆分后的结构契约。
 *
 * 拆开的目的是让“内容模型 / 刷新决策 / 指纹 / 计数 / 主线程回调”各自能单独读、
 * 单独测。这个测试防的是回退：改动一旦把某个类塞回大文件，或者把 Handler
 * 换回强引用，就要在这里断。
 */
class DisplaySplitContractTest {

    private fun projectFile(repoRelativePath: String): File {
        val candidates = listOf(
            File(repoRelativePath),
            File("..", repoRelativePath),
            File("../..", repoRelativePath),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("找不到 $repoRelativePath（cwd=${System.getProperty("user.dir")}）")
    }

    private fun src(name: String) = projectFile("brailleback/src/com/googlecode/eyesfree/brailleback/$name")

    private fun srcText(name: String) = src(name).readText()

    private fun projectDir(repoRelativePath: String): File {
        val candidates = listOf(
            File(repoRelativePath),
            File("..", repoRelativePath),
            File("../..", repoRelativePath),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("找不到目录 $repoRelativePath（cwd=${System.getProperty("user.dir")}）")
    }

    @Test
    fun `内容模型已在独立文件且不再是嵌套类`() {
        val manager = srcText("DisplayManager.kt")
        assertFalse(
            "Content 不能又被塞回 DisplayManager；它是数据模型，跟消息队列没有关系",
            manager.contains("class Content"),
        )

        val content = srcText("DisplayContent.kt")
        assertTrue(content.contains("class Content "))
        // 三个对外行为：取文本、回收节点、带内嵌盲文的翻译。
        assertTrue(content.contains("fun getText()"))
        assertTrue(content.contains("fun recycle()"))
        assertTrue(content.contains("fun translateWithVerbatimBraille("))
        // 常量仍从 Content 上取（PAN_* / CONTRACT_*），调用方不用改。
        assertTrue(content.contains("const val PAN_KEEP = 2"))
    }

    @Test
    fun `旧的全限定写法不许回潮`() {
        val offenders = mutableListOf<String>()
        val root = projectDir("brailleback/src")
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    if (line.contains("DisplayManager.Content")) {
                        offenders += "${file.name}:${index + 1}"
                    }
                }
            }
        assertTrue(
            "Content 已顶层化，限定写法应消失：$offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `主线程回调独立成文件并保留弱引用`() {
        val handler = srcText("DisplayCallbackHandler.kt")
        assertTrue(handler.contains("internal class DisplayCallbackHandler"))
        // 主线程队列会持有 Handler：强引用管理器等于把无障碍服务一起泄漏。
        assertTrue(
            "Handler 必须继续通过 WeakReference 访问管理器",
            handler.contains("WeakReference"),
        )
        assertFalse(
            "Handler 不能直接持有管理器实例：${'$'}manager",
            handler.contains("private val manager: DisplayManager"),
        )

        val manager = srcText("DisplayManager.kt")
        assertTrue(
            "管理器要构造这个独立 Handler",
            manager.contains("DisplayCallbackHandler(this)"),
        )
    }

    @Test
    fun `管理器体积守住`() {
        val lines = src("DisplayManager.kt").readLines().count { it.isNotBlank() }
        // 拆分前 1527 行（含 329 行 Content 与 78 行 CallbackHandler）。这里卡住上限，
        // 一旦有人再往里堆逻辑，先在这里断，而不是等文件长回 1500 行。
        assertTrue("DisplayManager 非空行数 $lines 已超出拆分后的预算", lines < 1100)
    }
}
