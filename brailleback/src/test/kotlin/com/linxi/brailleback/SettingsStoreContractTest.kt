/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 设置存储的静态契约。
 *
 * 这层最容易出的事故不是「读不到值」，而是**跨进程**：
 * TranslatorService 跑在 `:service` 进程，DataStore 不支持跨进程（同文件两个实例
 * 会异常，跨进程是最后写入者赢）。所以被它读取的键必须留在 SharedPreferences，
 * 其余键才允许搬进 DataStore。名单一旦被悄悄改动，用户表现是「分词开关改了没反应」，
 * 而且没有任何日志——只能靠这里守住。
 */

package com.linxi.brailleback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SettingsStoreContractTest {

    private fun projectFile(path: String): File {
        val candidates = listOf(File(path), File("..", path), File("../..", path))
        return candidates.firstOrNull { it.isFile }
            ?: error("找不到 $path（cwd=${System.getProperty("user.dir")}）")
    }

    private fun text(path: String) = projectFile(path).readText()

    /** 目录版：单测的 cwd 是模块目录，候选要按目录判断。 */
    private fun projectDir(path: String): File {
        val candidates = listOf(File(path), File("..", path), File("../..", path))
        return candidates.firstOrNull { it.isDirectory }
            ?: error("找不到 $path（cwd=${System.getProperty("user.dir")}）")
    }

    private val storeSource get() = text("libraries/utils/src/main/java/com/googlecode/eyesfree/utils/SettingsStore.kt")

    private val translatorSource
        get() = text("service/src/main/java/com/googlecode/eyesfree/braille/service/translate/TranslatorService.java")

    @Test
    fun crossProcessKeyAllowListIsExactlyChineseSegmentation() {
        // 只允许 chinese_segmentation 留在旧文件：它是 TranslatorService（:service 进程）
        // 唯一读取的键。加键前先确认那个读者真的在别的进程。
        val literal = Regex("""CROSS_PROCESS_KEYS: Set<String> = setOf\(([^)]*)\)""")
            .find(storeSource)?.groupValues?.get(1)
            ?: error("SettingsStore 里找不到 CROSS_PROCESS_KEYS 声明")
        val keys = Regex(""""([^"]+)"""").findAll(literal).map { it.groupValues[1] }.toSet()
        assertEquals(
            "跨进程键名单只能有 chinese_segmentation；新增必须先确认进程归属",
            setOf("chinese_segmentation"),
            keys,
        )
    }

    @Test
    fun translatorServiceStaysOnSharedPreferences() {
        // :service 进程不能碰 DataStore：它既读不到主进程的内存快照，
        // 又会让同一个文件出现第二个 DataStore 实例。
        assertTrue(
            "TranslatorService 必须继续读 SharedPreferences",
            translatorSource.contains("PreferenceManager.getDefaultSharedPreferences(this)"),
        )
        assertTrue(
            "分离开关必须仍从旧文件同步读",
            translatorSource.contains("""prefs.getBoolean("chinese_segmentation", true)"""),
        )
        assertFalse(
            "TranslatorService 不得引用 SettingsStore",
            translatorSource.contains("SettingsStore"),
        )
        assertTrue(
            "分离开关变更必须仍能即时生效（旧文件的监听器）",
            translatorSource.contains("registerOnSharedPreferenceChangeListener"),
        )
    }

    @Test
    fun settingsStoreStillSpeaksSharedPreferences() {
        // 60 多处同步读折叠成「换一个来源」，而不是改写成 Flow；
        // 一旦它不再实现 SharedPreferences，这批调用点就会被迫全改，等于白做。
        assertTrue(
            "SettingsStore 必须实现 SharedPreferences",
            storeSource.contains(") : SharedPreferences"),
        )
        listOf(
            "override fun getBoolean(",
            "override fun getString(",
            "override fun getStringSet(",
            "override fun getInt(",
            "override fun edit(): SharedPreferences.Editor",
        ).forEach { api ->
            assertTrue("缺同步读/写入口：$api", storeSource.contains(api))
        }
        assertTrue(
            "写入必须是「先改快照再落盘」，否则 apply 之后立刻读会拿到旧值",
            storeSource.contains("先改快照，再落盘"),
        )
        assertTrue(
            "首次加载要能容忍慢盘：超过阈值要打 WARN",
            storeSource.contains("LOAD_WARN_MS"),
        )
    }

    @Test
    fun noDirectDefaultPreferenceAccessLeftOutsideTheAllowList() {
        // 主进程里不允许再有人绕过 SettingsStore 直接写旧文件：
        // 那样会形成「DataStore 一份、旧文件一份」的双源。
        val offenders = mutableListOf<String>()
        projectDir("brailleback/src/main/java").walkTopDown().filter {
            it.isFile && (it.extension == "kt" || it.extension == "java")
        }.forEach { file ->
            if (file.readText().contains("getDefaultSharedPreferences(")) {
                offenders.add(file.name)
            }
        }
        projectDir("service/src/main/java").walkTopDown().filter {
            it.isFile && (it.extension == "kt" || it.extension == "java")
        }.forEach { file ->
            // 唯一允许的是 :service 进程的 TranslatorService。
            if (file.name != "TranslatorService.java" &&
                file.readText().contains("getDefaultSharedPreferences(")
            ) {
                offenders.add(file.name)
            }
        }
        assertTrue(
            "这些文件仍在绕过 SettingsStore 直接读默认偏好：$offenders",
            offenders.isEmpty(),
        )
    }
}
