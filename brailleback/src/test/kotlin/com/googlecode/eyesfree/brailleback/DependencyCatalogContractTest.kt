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

        /** 版本目录里的三个 SDK 别名：只在约定插件里读，模块脚本再出现就算重复定义。 */
        val CATALOG_SDK_ALIASES = listOf(
            "android-compileSdk",
            "android-minSdk",
            "android-targetSdk",
        )

        /** 裸 SDK 数字：`compileSdk 37`、`minSdk = 29` 之类。 */
        val HARDCODED_SDK = Regex("""\b(compileSdk|minSdk|targetSdk)\s*(=\s*)?\d""")

        /** 约定插件脚本：SDK 三档的唯一读取点（见 docs/48）。 */
        val CONVENTION_SCRIPTS = listOf(
            "build-logic/src/main/groovy/brailleback.android.library.gradle",
            "build-logic/src/main/groovy/brailleback.android.application.gradle",
        )
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
        // 2026-11-20 起（docs/48）：SDK 三档只在 build-logic 的约定插件里从版本目录读一次，
        // 模块脚本只声明「用哪个约定插件」。此前 11 个模块各自抄三行，
        // 改一次版本要改 11 处，漏掉一处不会报错，只会让某个模块悄悄用旧 SDK。
        val conventionIds = listOf("brailleback.android.library", "brailleback.android.application")
        val offenders = MODULES.filter { module ->
            val text = moduleScript(module)
            val appliesConvention = conventionIds.any { text.contains("id '$it'") }
            // 模块自己再读一遍目录，或出现裸数字，都算把这个值散回去了。
            val duplicatesCatalogRead = CATALOG_SDK_ALIASES.any { text.contains(it) }
            val hardcoded = HARDCODED_SDK.containsMatchIn(text)
            !appliesConvention || duplicatesCatalogRead || hardcoded
        }
        assertTrue(
            "这些模块没有统一走约定插件取 SDK 三档（或又抄了一遍）：$offenders",
            offenders.isEmpty(),
        )
    }

    @Test
    fun sdkLevelsAreDeclaredOnceInTheConventionPlugin() {
        CONVENTION_SCRIPTS.forEach { path ->
            val file = File(repoRoot(), path)
            assertTrue("缺少约定插件脚本：$path", file.isFile)
            val text = file.readText()
            CATALOG_SDK_ALIASES.forEach { alias ->
                assertTrue("$path 必须从版本目录读 $alias", text.contains(alias))
            }
        }
        val catalogText = catalog()
        listOf(
            "android-compileSdk = \"37\"",
            "android-targetSdk = \"37\"",
            "android-minSdk = \"29\"",
        ).forEach { fragment ->
            assertTrue("版本目录缺少：$fragment", catalogText.contains(fragment))
        }
    }

    @Test
    fun catalogPinsTheComposeAndCoroutineVersionsWeVerified() {
        val text = catalog()
        listOf(
            // 2026-11-18 依赖解冻后实测通过的组合（见 docs/38-依赖解冻.md）。
            "compose = \"1.12.1\"",
            "material3 = \"1.4.0\"",
            "activityCompose = \"1.13.0\"",
            "coroutines = \"1.11.0\"",
            "android-compileSdk = \"37\"",
            "android-minSdk = \"29\"",
        ).forEach { pin ->
            assertTrue("版本目录缺少已验证的版本钉：$pin", text.contains(pin))
        }
    }
}
