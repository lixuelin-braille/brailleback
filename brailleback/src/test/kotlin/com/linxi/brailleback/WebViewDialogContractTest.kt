/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 开源许可页 Compose 容器化的静态契约：Compose 宿主 + AndroidView 包裹 WebView
 * （非纯 Compose 页面）；标题/URL 两个 extra、空 URL 关闭自己、静态 getIntent 签名不变；
 * 旧 XML 布局已删除。
 */

package com.linxi.brailleback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WebViewDialogContractTest {

    private fun projectFile(path: String): File {
        val candidates = listOf(File(path), File("..", path), File("../..", path))
        return candidates.firstOrNull { it.isFile }
            ?: error("找不到 $path（cwd=${System.getProperty("user.dir")}）")
    }

    private val base = "brailleback/"
    private val activityPath = base + "src/main/java/com/linxi/brailleback/WebViewDialog.kt"

    @Test
    fun composeContainerHostsWebViewViaAndroidView() {
        val activity = projectFile(activityPath).readText()
        assertTrue("宿主必须是 ComponentActivity", activity.contains("ComponentActivity()"))
        assertTrue("界面经 setContent 承载", activity.contains("setContent {"))
        assertTrue("WebView 由 AndroidView 包裹（非纯 Compose）", activity.contains("AndroidView("))
        assertTrue("工厂里创建 WebView", activity.contains("WebView(context)"))
        assertFalse("不该再 setContentView", activity.contains("setContentView"))
        assertFalse("不该再 findViewById", activity.contains("findViewById"))
    }

    @Test
    fun keepsIntentExtrasAndEmptyUrlFinish() {
        val activity = projectFile(activityPath).readText()
        assertTrue("标题 extra 保留", activity.contains("Intent.EXTRA_TITLE"))
        assertTrue("URL extra 保留", activity.contains("Intent.EXTRA_STREAM"))
        assertTrue("缺省标题取 pref_os_license_title",
            activity.contains("R.string.pref_os_license_title"))
        assertTrue("空 URL 时 finish 自己", activity.contains("finish()"))
        assertTrue("静态 getIntent 签名保留",
            activity.contains("fun getIntent(context: Context, titleResId: Int, url: String)"))
    }

    @Test
    fun xmlLayoutIsGone() {
        val layout = listOf(
            File(base + "src/main/res/layout/activity_licenses.xml"),
            File("..", base + "src/main/res/layout/activity_licenses.xml"),
            File("../..", base + "src/main/res/layout/activity_licenses.xml"),
        )
        assertFalse("activity_licenses.xml 应已删除", layout.any { it.exists() })
    }
}
