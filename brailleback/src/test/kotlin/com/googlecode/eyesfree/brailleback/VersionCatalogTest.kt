/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Properties

/**
 * 锁死「版本号只有一个来源」的契约。
 *
 * 版本管理最容易出现的回归是：改版本号只改了一处、library 清单里还留着另一个
 * versionName、或 versionCode 忘记递增导致真机覆盖安装被拒。
 * 因此直接读 gradle.properties / build.gradle / 所有清单 / 版本表做静态断言，
 * 不依赖 Android 运行时，也不需要真的打包。
 */
class VersionCatalogTest {

    private companion object {
        const val EXPECTED_VERSION_NAME = "1.0"
        const val EXPECTED_VERSION_CODE = 34
        const val PREVIOUS_VERSION_CODE = 33
    }

    /** Gradle 单测的 cwd 可能是仓库根，也可能是 brailleback/ 模块目录。 */
    private fun projectFile(repoRelativePath: String): File {
        val candidates = listOf(
            File(repoRelativePath),
            File("..", repoRelativePath),
            File("../..", repoRelativePath),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("找不到 $repoRelativePath（cwd=${System.getProperty("user.dir")}）")
    }

    private fun repoRoot(): File = listOf(File("."), File(".."), File("../.."))
        .first { File(it, "settings.gradle").isFile }

    private fun versionProperties(): Properties = Properties().apply {
        projectFile("gradle.properties").inputStream().use { load(it) }
    }

    /** 源码清单（排除 Gradle 生成物，合并后的清单在 build/ 里不算版本来源）。 */
    private fun sourceManifests(): List<File> = repoRoot().walkTopDown()
        .onEnter { dir ->
            dir.name !in setOf("build", ".gradle", ".git", ".idea")
        }
        .filter { it.isFile && it.name == "AndroidManifest.xml" }
        .toList()

    @Test
    fun versionNameIsTheOnePointZeroRelease() {
        assertEquals(
            "appVersionName 必须与本次发布一致",
            EXPECTED_VERSION_NAME,
            versionProperties().getProperty("appVersionName"),
        )
    }

    @Test
    fun versionCodeIncrementsAndNeverGoesBackwards() {
        val code = versionProperties().getProperty("appVersionCode")?.toIntOrNull()
            ?: error("appVersionCode 必须是整数")
        assertEquals("appVersionCode 必须与本次发布一致", EXPECTED_VERSION_CODE, code)
        assertTrue(
            "versionCode 必须大于上一版 $PREVIOUS_VERSION_CODE，否则真机无法覆盖安装",
            code > PREVIOUS_VERSION_CODE,
        )
    }

    @Test
    fun buildGradleReadsVersionFromTheSingleSource() {
        val text = projectFile("brailleback/build.gradle").readText()
        assertTrue("必须从 gradle.properties 读 versionCode",
            text.contains("providers.gradleProperty('appVersionCode')"))
        assertTrue("必须从 gradle.properties 读 versionName",
            text.contains("providers.gradleProperty('appVersionName')"))
        assertFalse("build.gradle 不得再硬编码 versionCode 数字",
            Regex("""versionCode\s+\d""").containsMatchIn(text))
        assertFalse("build.gradle 不得再硬编码 versionName 字符串",
            Regex("""versionName\s+"[^"]*"""").containsMatchIn(text))
    }

    @Test
    fun noSourceManifestDeclaresASecondVersion() {
        val offenders = sourceManifests().filter {
            val text = it.readText()
            text.contains("android:versionCode") || text.contains("android:versionName")
        }
        assertTrue(
            "这些清单仍声明版本，会误导成多版本源: ${offenders.map { it.path }}",
            offenders.isEmpty(),
        )
    }

    @Test
    fun versionHistoryTableMatchesTheConfiguredVersion() {
        val props = versionProperties()
        val name = props.getProperty("appVersionName")
        val code = props.getProperty("appVersionCode")
        val doc = projectFile("docs/00-项目总览与架构.md").readText()
        assertTrue(
            "版本历史表缺少当前版本行 | $name | $code |",
            doc.contains("| $name | $code |"),
        )
    }
}
