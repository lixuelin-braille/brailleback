/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback.ime

import com.googlecode.eyesfree.brailleback.ime.SingleImeViewPolicy.MODE_AUTO
import com.googlecode.eyesfree.brailleback.ime.SingleImeViewPolicy.MODE_OFF
import com.googlecode.eyesfree.brailleback.ime.SingleImeViewPolicy.MODE_ON
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 锁死「两份键盘注册合并成一份」的契约。
 *
 * 这类改动最典型的回归是：某次合并/还原又把移植键盘的 service 声明带回来，
 * 系统输入法列表里重新出现两个 BrailleBack 键盘；或者组件开关常量还指向
 * 已注销的组件，导致 isBrailleKeyboardEnabled() 永远返回 false。
 * 因此直接读源清单与常量源文件做静态断言，不依赖 Android 运行时。
 */
class SingleImeRegistrationTest {

    /** Gradle 单测的 cwd 可能是仓库根，也可能是 brailleback/ 模块目录。 */
    private fun projectFile(repoRelativePath: String): File {
        val candidates = listOf(
            File(repoRelativePath),
            File("..", repoRelativePath),
            File("../..", repoRelativePath),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("找不到源码 $repoRelativePath（cwd=${System.getProperty("user.dir")}）")
    }

    private fun serviceBlocks(manifest: File): List<String> =
        Regex("<service\\b[\\s\\S]*?</service>")
            .findAll(manifest.readText())
            .map { it.value }
            .toList()

    private fun inputMethodServices(): List<Pair<File, String>> = listOf(
        projectFile("brailleback/AndroidManifest.xml"),
        projectFile("addon/brailleime/AndroidManifest.xml"),
    ).flatMap { manifest ->
        serviceBlocks(manifest)
            .filter { it.contains("android.view.InputMethod") }
            .map { manifest to it }
    }

    @Test
    fun onlyOneInputMethodServiceIsDeclared() {
        val services = inputMethodServices()
        assertEquals(
            "合并后应只剩一个输入法服务，实际: ${services.map { it.first.path }}",
            1, services.size,
        )
    }

    @Test
    fun theOnlyServiceIsBrailleIME() {
        val block = inputMethodServices().single().second
        assertTrue("唯一服务必须是 .BrailleIME\n$block",
            block.contains("android:name=\".BrailleIME\""))
        assertTrue("必须保留带设置页入口的元数据\n$block",
            block.contains("@xml/braille_ime"))
    }

    @Test
    fun portedKeyboardHasNoServiceDeclaration() {
        val manifest = projectFile("addon/brailleime/AndroidManifest.xml").readText()
        assertFalse("移植键盘不应再声明 <service>", manifest.contains("<service"))
        assertFalse("移植键盘清单不应再声明输入法 action",
            manifest.contains("android.view.InputMethod"))
    }

    @Test
    fun componentConstantPointsAtTheOnlyRegisteredIme() {
        val src = projectFile(
            "addon/tbcompat/src/com/google/android/accessibility/utils/" +
                "AccessibilityServiceCompatUtils.java"
        ).readText()
        assertTrue("BRAILLE_IME_CLASS_NAME 必须指向唯一注册的 BrailleIME",
            src.contains("\"com.googlecode.eyesfree.brailleback.BrailleIME\""))
        assertFalse("不能指回已注销的移植键盘组件",
            src.contains("\"com.google.android.accessibility.brailleime.BrailleIme\""))
        // build.gradle 里是 buildConfigField "...", "\"包名\""，转义引号不能整串匹配。
        assertTrue("组件包名必须是宿主 applicationId",
            projectFile("addon/tbcompat/build.gradle").readText()
                .contains("HOST_APPLICATION_ID")
                    && projectFile("addon/tbcompat/build.gradle").readText()
                        .contains("com.googlecode.eyesfree.brailleback"))
    }

    @Test
    fun imeMetadataKeepsSettingsAndSwitching() {
        val meta = projectFile("addon/brailleime/res/xml/braille_ime.xml").readText()
        assertTrue("设置页入口不能丢", meta.contains("BrailleImePreferencesActivity"))
        assertTrue("输入法切换键不能丢",
            meta.contains("supportsSwitchingToNextInputMethod=\"true\""))
        assertFalse("子类型名称必须走可本地化的字符串资源",
            meta.contains("android:label=\"Braille Keyboard\""))
    }

    @Test
    fun theOnlyImeInheritsPortedTouchKeyboard() {
        val src = projectFile(
            "brailleback/src/com/googlecode/eyesfree/brailleback/BrailleIME.kt"
        ).readText()
        assertTrue("BrailleIME 必须继承 BrailleIme 以复用触屏点字实现",
            src.contains("class BrailleIME : BrailleIme {"))
        assertTrue("触屏模式必须能加载移植键盘的 InputView",
            src.contains("super.onCreateInputView()"))
        assertTrue("必须按形态开关决定是否激活移植键盘生命周期",
            src.contains("if (touchKeyboardEnabled())"))
    }

    @Test
    fun autoUsesTouchKeyboardOnlyWithoutDisplay() {
        assertFalse("接了点显器必须走硬件形态",
            SingleImeViewPolicy.useTouchKeyboard(MODE_AUTO, true))
        assertTrue("没接点显器才显示触屏点字键盘",
            SingleImeViewPolicy.useTouchKeyboard(MODE_AUTO, false))
    }

    @Test
    fun explicitModesOverrideConnectionState() {
        assertTrue(SingleImeViewPolicy.useTouchKeyboard(MODE_ON, true))
        assertTrue(SingleImeViewPolicy.useTouchKeyboard(MODE_ON, false))
        assertFalse(SingleImeViewPolicy.useTouchKeyboard(MODE_OFF, true))
        assertFalse(SingleImeViewPolicy.useTouchKeyboard(MODE_OFF, false))
    }

    @Test
    fun nullAndUnknownModesFallBackToAuto() {
        assertFalse(SingleImeViewPolicy.useTouchKeyboard(null, true))
        assertTrue(SingleImeViewPolicy.useTouchKeyboard(null, false))
        assertFalse(SingleImeViewPolicy.useTouchKeyboard("garbage", true))
        assertTrue(SingleImeViewPolicy.useTouchKeyboard("garbage", false))
    }
}
