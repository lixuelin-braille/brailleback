/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 依赖与 SDK 版本的静态契约：全仓只能有 gradle/libs.versions.toml 一个版本来源。
 *
 * 为什么值得一条测试：11 个模块此前把 `compileSdk 37` / `androidx.core:core:1.9.0`
 * 这类字面量抄了二十多遍，升级时漏改一处不会报错——只会让某个模块悄悄用旧版本，
 * 表现为「打包能过、运行时行为不一致」。而且散文式的代码评审看不出漏改。
 */
class DependencyCatalogContractTest {

    private companion object {
        /** 模块脚本相对仓库根。addon/ 六个 Java 模块也算，它们同样在打包链里。 */
        val MODULES = listOf(
            "brailleback",
            "service",
            "client",
            "libraries/utils",
            "libraries/compatutils",
            "addon/tbcompat",
            "addon/brailleinterfaces",
            "addon/brailletranslate",
            "addon/braillecommon",
            "addon/brailleimeanalytics",
            "addon/brailleime",
        )

        /** 形如 'androidx.core:core:1.9.0' 的硬编码坐标。 */
        val HARDCODED_COORDINATE = Regex("""'[A-Za-z0-9_.\-]+:[A-Za-z0-9_.\-]+:[0-9][^']*'""")
    }

    private fun repoRoot(): File =
        listOf(File("."), File(".."), File("../.."))
            .first { File(it, "settings.gradle").isFile }

    private fun moduleScript(module: String): String =
        File(repoRoot(), "$module/build.gradle").readText()

    private fun catalog(): String =
        File(repoRoot(), "gradle/libs.versions.toml").readText()

    @Test
    fun catalogExists() {
        assertTrue(
            "缺少 gradle/libs.versions.toml：版本将重新散落到各模块",
            catalog().contains("[versions]"),
        )
    }

    @Test
    fun noModuleHardcodesACoordinate() {
        val offenders = MODULES.mapNotNull { module ->
            val hits = HARDCODED_COORDINATE.findAll(moduleScript(module)).map { it.value }.toList()
            if (hits.isEmpty()) null else "$module -> $hits"
        }
        assertTrue("这些模块仍写死 group:artifact:version：$offenders", offenders.isEmpty())
        assertEquals("模块清单与 settings.gradle 必须同步（11 个）", 11, MODULES.size)
    }

    @Test
    fun everyModuleTakesSdkLevelsFromTheCatalog() {
        val offenders = MODULES.filter {
            val text = moduleScript(it)
            // 每个模块都必须从目录取编译/目标/最低版本，不允许再出现裸数字。
            !text.contains("libs.versions.android.compileSdk") ||
                Regex("""\bcompileSdk\s+\d""").containsMatchIn(text)
        }
        assertTrue("这些模块的 compileSdk 不是从版本目录取的：$offenders", offenders.isEmpty())
        // minSdk 与 targetSdk 在客户端库里也必须有（否则会继承默认值）。
        val missingMin = MODULES.filter { !moduleScript(it).contains("libs.versions.android.minSdk") }
        assertTrue("这些模块没从目录取 minSdk：$missingMin", missingMin.isEmpty())
    }

    @Test
    fun catalogPinsTheComposeAndCoroutineVersionsWeVerified() {
        val text = catalog()
        listOf(
            "compose = \"1.7.6\"",
            "material3 = \"1.3.1\"",
            "activityCompose = \"1.9.3\"",
            "coroutines = \"1.9.0\"",
            "android-compileSdk = \"37\"",
            "android-minSdk = \"29\"",
        ).forEach { pin ->
            assertTrue("版本目录缺少已验证的版本钉：$pin", text.contains(pin))
        }
    }
}
