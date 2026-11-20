/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * native 桥的静态契约：Java 类全名 ↔ JNI 符号名 ↔ 打包的 .so。
 *
 * 为什么必须锁死这三样：`liblouis` 与 `brltty` 都是上游代码，C 侧的 JNI 函数名是把
 * Java 包路径写死在符号里的（例如
 * `Java_com_googlecode_eyesfree_braille_service_translate_LibLouisWrapper_translateNative`）。
 * service 的两个 .so 是**预编译入库**（`service/jniLibs`，`verifyNativeLibs` 只比较 mtime），
 * 所以改包名、拆模块、把 Java 包装类改成 Kotlin object 时，编译与打包都不会报错，
 * 只有装到真机、点显器第一次连接时才炸 UnsatisfiedLinkError。
 *
 * 这三组名字因此属于「对外 ABI」，不属于可自由重构的内部实现：
 *   - 服务侧：[LibLouisWrapper]（louiswrap）/ [BrlttyWrapper]（brlttywrap）
 *   - 插件侧：`LouisTranslation`（louiswrap_tb）—— 前缀是 com.google.android.…，
 *     与上面两个故意不同，两套前缀不得「统一」。
 */
class NativeBridgeContractTest {

    private fun projectFile(path: String): File {
        val candidates = listOf(File(path), File("..", path), File("../..", path))
        return candidates.firstOrNull { it.isFile }
            ?: error("找不到 $path（cwd=${System.getProperty("user.dir")}）")
    }

    private fun text(path: String) = projectFile(path).readText()

    private fun named(text: String, regex: Regex): Set<String> =
        regex.findAll(text).map { it.groupValues[1] }.toSet()

    // ---------------------------------------------------------------- 服务侧

    private val libLouisJava =
        "service/src/main/java/com/googlecode/eyesfree/braille/service/translate/LibLouisWrapper.java"
    private val libLouisC = "service/jni/liblouiswrapper/LibLouisWrapper.c"
    private val brlttyKt = "service/src/main/java/com/googlecode/eyesfree/braille/service/display/BrlttyWrapper.kt"
    private val brlttyC = "service/jni/brlttywrapper/BrlttyWrapper.c"

    private val libLouisMethods = setOf(
        "translateNative",
        "backTranslateNative",
        "checkTableNative",
        "setTablesDirNative",
        "classInitNative",
    )

    private val brlttyMethods = setOf(
        "initNative",
        "startNative",
        "stopNative",
        "writeWindowNative",
        "readCommandNative",
        "addBytesFromDeviceNative",
        "getKeyMapNative",
        "getTextCellsNative",
        "getStatusCellsNative",
        "classInitNative",
        "getUsbDriverTableNative",
        "getBluetoothDriverTableNative",
    )

    @Test
    fun liblouisWrapperKeepsItsPackageAndNativeMethods() {
        val src = text(libLouisJava)

        assertTrue(
            "LibLouisWrapper 的包名就是 JNI 符号的一部分，不能改",
            src.contains("package com.googlecode.eyesfree.braille.service.translate;")
        )
        assertTrue(
            "库名必须与 service/jniLibs 里的 liblouiswrap.so 对应",
            src.contains("""System.loadLibrary("louiswrap")""")
        )

        val declared = named(src, Regex("""native\s+[A-Za-z0-9_\[\]<>., ]+?\s+(\w+)\s*\("""))
        assertEquals(
            "native 方法集合变了就必须同步改 C 侧符号与本测试",
            libLouisMethods,
            declared,
        )
    }

    @Test
    fun liblouisSymbolsMatchTheJavaClassExactly() {
        val c = text(libLouisC)
        val symbols = named(
            c,
            Regex("""Java_com_googlecode_eyesfree_braille_service_translate_LibLouisWrapper_(\w+)"""),
        )

        assertEquals(
            "C 侧导出的 JNI 符号必须与 Java 侧 native 方法一一对应",
            libLouisMethods,
            symbols,
        )
    }

    @Test
    fun brlttyWrapperKeepsItsPackageAndNativeMethods() {
        val src = text(brlttyKt)

        assertTrue(
            "BrlttyWrapper 的包名就是 JNI 符号的一部分，不能改",
            src.contains("package com.googlecode.eyesfree.braille.service.display")
        )
        assertTrue(
            "库名必须与 service/jniLibs 里的 libbrlttywrap.so 对应",
            src.contains("""System.loadLibrary("brlttywrap")""")
        )

        val declared = named(src, Regex("""external fun (\w+)\s*\("""))
        assertEquals(
            "external 方法集合变了就必须同步改 C 侧符号与本测试",
            brlttyMethods,
            declared,
        )
    }

    @Test
    fun brlttySymbolsMatchTheJavaClassExactly() {
        val c = text(brlttyC)

        assertTrue(
            "符号前缀固定在宏里；改它等于改 JNI 名字",
            c.contains(
                "Java_com_googlecode_eyesfree_braille_service_display_BrlttyWrapper_##fn"
            )
        )

        // 宏定义那一行本身也匹配 JNIMETHOD(fn)，先剔除再比对。
        val used = named(c, Regex("""JNIMETHOD\((\w+)\)""")) - "fn"
        assertEquals(
            "C 侧 JNIMETHOD 展开的名字必须与 Kotlin 侧 external 方法一一对应",
            brlttyMethods,
            used,
        )
    }

    // ---------------------------------------------------------------- 插件侧

    private val addonLouisJava =
        "addon/brailletranslate/src/main/java/com/google/android/accessibility/braille/translate/liblouis/LouisTranslation.java"
    private val addonLouisHeader =
        "addon/brailletranslate/jni/liblouiswrapper/louis_translation.h"

    @Test
    fun addonLouisTranslationKeepsItsOwnPrefixAndLibrary() {
        val src = text(addonLouisJava)

        assertTrue(
            "插件侧包名与主应用侧故意不同，统一它等于两处同时失效",
            src.contains(
                "package com.google.android.accessibility.braille.translate.liblouis;"
            )
        )
        assertTrue(
            "插件侧加载的是独立的 liblouiswrap_tb.so（与主应用 liblouiswrap.so 并存）",
            src.contains("""System.loadLibrary("louiswrap_tb")""")
        )

        val declared = named(src, Regex("""native\s+[A-Za-z0-9_\[\]<>., ]+?\s+(\w+)\s*\("""))
        assertEquals(
            "插件侧 native 方法集合变了就要同步改 louis_translation.cc 与本测试",
            libLouisMethods,
            declared,
        )

        val header = text(addonLouisHeader)
        assertTrue(
            "C 侧前缀必须是 com_google_android_accessibility_braille_translate_liblouis",
            header.contains(
                "Java_com_google_android_accessibility_braille_translate_liblouis_LouisTranslation_##fn"
            )
        )
    }

    // ------------------------------------------------------------ 落地物与路径

    @Test
    fun prebuiltServiceLibrariesCoverEveryPackagedAbi() {
        listOf("arm64-v8a", "armeabi-v7a").forEach { abi ->
            listOf("libbrlttywrap.so", "liblouiswrap.so").forEach { lib ->
                val file = projectFile("service/jniLibs/$abi/$lib")
                assertTrue("$abi/$lib 是预编译入库的产物，缺了等于该架构上没有驱动通路", file.isFile)
            }
        }

        // abiFilters 是「打包哪几套 .so」的唯一声明，与上面的清单必须一致。
        val build = text("brailleback/build.gradle")
        assertTrue(build.contains("'armeabi-v7a'"))
        assertTrue(build.contains("'arm64-v8a'"))
    }

    @Test
    fun tableDirectoryAndRawResourceNamesAreStable() {
        val translator = text("service/src/main/java/com/googlecode/eyesfree/braille/service/translate/TranslatorService.java")

        // 表目录名写在内核态无关的 Java 侧，但 .so 里的查表逻辑依赖「调用方把目录传对」。
        assertTrue(
            "翻译表解压目录名被 setTablesDir 使用，改名等于表全部查不到",
            translator.contains("""getDir("translator", MODE_PRIVATE)""")
        )
        assertTrue(
            "raw 资源名必须与 service/res/raw/translationtables.zip 对应",
            translator.contains("R.raw.translationtables")
        )

        val display = text("service/src/main/java/com/googlecode/eyesfree/braille/service/display/DisplayService.kt")
        assertTrue(
            "显示侧 keytables 是另一份 raw 资源，不能与翻译表混用",
            display.contains("R.raw.keytables")
        )
    }
}
