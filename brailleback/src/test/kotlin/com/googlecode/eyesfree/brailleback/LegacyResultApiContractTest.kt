/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 结果回传 API 的静态契约。
 *
 * 为什么固化：`onActivityResult` / `onRequestPermissionsResult` 是已弃用的旧路径，
 * 请求码散在各文件的常量里，新增一处忘加分支就会静默丢结果——对读屏用户来说，
 * 「点导入没反应」比崩溃更难排查，因为没有任何提示。改成 Activity Result API 后
 * 每个 launcher 的回调与调用点写在一起，不存在漏分支。
 *
 * 同时锁住三件容易被改回去的事：
 * 1. 文件选择必须走 `OpenDocument` 契约（手写 Intent 容易丢掉 CATEGORY_OPENABLE）；
 * 2. `takePersistableUriPermission` 必须保留，否则下次启动续读会失败；
 * 3. 三个原 `android.app.Activity` 子类必须换成能承载 launcher 的基类。
 */
class LegacyResultApiContractTest {

    private fun projectFile(path: String): File {
        val candidates = listOf(File(path), File("..", path), File("../..", path))
        return candidates.firstOrNull { it.isFile }
            ?: error("找不到 $path（cwd=${System.getProperty("user.dir")}）")
    }

    /** 目录版本：cwd 是模块目录（brailleback/），仓库根要往上找一层。 */
    private fun projectDir(path: String): File {
        val candidates = listOf(File(path), File("..", path), File("../..", path))
        return candidates.firstOrNull { it.isDirectory }
            ?: error("找不到目录 $path（cwd=${System.getProperty("user.dir")}）")
    }

    private fun source(relative: String) =
        projectFile("brailleback/src/main/java/com/googlecode/eyesfree/brailleback/$relative").readText()

    private fun sources(): List<File> =
        projectDir("brailleback/src/main/java").walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .toList()

    /** 旧 API 的特征串。大小写敏感：`StartActivityForResult` 契约不算命中。 */
    private val legacyPatterns = listOf(
        "startActivityForResult(",
        "onActivityResult(",
        "onRequestPermissionsResult(",
        "requestPermissions(",
    )

    @Test
    fun `源码里不再出现旧的结果回传 API`() {
        val hits = sources().flatMap { file ->
            val text = file.readText()
            legacyPatterns.filter { text.contains(it) }
                .map { pattern -> "${file.name} 里还有 $pattern" }
        }
        assertTrue("仍在使用旧结果 API：$hits", hits.isEmpty())
    }

    @Test
    fun `六个界面各自注册了 launcher`() {
        val files = listOf(
            "BrailleReaderActivity.kt",
            "BookshelfActivity.kt",
            "IMESetupWizardActivity.kt",
            "SettingsActivity.kt",
            "compose/DeviceConnectionComposeActivity.kt",
            "compose/StabilityComposeActivity.kt",
        )
        val missing = files.filter { !source(it).contains("registerForActivityResult") }
        assertTrue("这些文件没有注册 launcher：$missing", missing.isEmpty())
    }

    @Test
    fun `文件选择走 OpenDocument 且保留持久化权限`() {
        listOf("BrailleReaderActivity.kt", "BookshelfActivity.kt").forEach { name ->
            val text = source(name)
            assertTrue("$name 未使用 OpenDocument 契约",
                text.contains("ActivityResultContracts.OpenDocument()"))
            // 不限 MIME：.bdo/.bdoc/.brf 没有标准类型，用 */* 才能在文件管理器里选中。
            assertTrue("$name 丢失了 */* 选择范围",
                text.contains("arrayOf(\"*/*\")"))
            // 没有这条，书架里记录的书下次启动读不了。
            assertTrue("$name 丢失了持久化读权限",
                text.contains("takePersistableUriPermission"))
        }
    }

    @Test
    fun `三个旧基类界面已换成可承载 launcher 的基类`() {
        val expected = listOf(
            "BrailleReaderActivity.kt" to "class BrailleReaderActivity : ComponentActivity()",
            "BookshelfActivity.kt" to "class BookshelfActivity : ComponentActivity()",
            "IMESetupWizardActivity.kt" to "class IMESetupWizardActivity : ComponentActivity()",
        )
        val wrong = expected.filter { (name, signature) -> !source(name).contains(signature) }
        assertTrue("基类仍是 android.app.Activity：$wrong", wrong.isEmpty())
    }
}
