/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 仪器测试：书架「导入」按钮 → 系统文件选择器。
 *
 * 为什么单独写一条：文件选择从 `startActivityForResult` 换成
 * `ActivityResultContracts.OpenDocument` 后，静态契约测试只能证明代码写法对，
 * 证明不了「点下去真的会弹选择器」——launcher 注册时机、CATEGORY_OPENABLE、
 * 星号 MIME 任何一处出问题，用户看到的现象都是「点了没反应」，且没有任何日志。
 */

package com.linxi.brailleback

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImportFlowSmokeTest {

    private val packageName = "com.linxi.brailleback"

    @Test
    fun importButtonOpensSystemDocumentPicker() {
        ActivityScenario.launch(BookshelfActivity::class.java).use {
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            val context = InstrumentationRegistry.getInstrumentation().targetContext

            // 2026-11-18 书架改成 Compose，按钮没有资源 id 可找了；
            // 但文案取自资源本身，所以按文案找仍然与语言无关。
            val importLabel = context.getString(R.string.bookshelf_import)
            val button = device.wait(Until.findObject(By.text(importLabel)), 10_000)
            assertNotNull("书架上没找到「$importLabel」按钮", button)
            button!!.click()

            // 选择器是另一个包（AOSP 是 documentsui，小米可能是文件管理）。
            // 因此只断言「前台包变了」，不对具体选择器应用做硬编码。
            var opened = false
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline) {
                if (device.currentPackageName != packageName) {
                    opened = true
                    break
                }
                Thread.sleep(200)
            }
            assertTrue("点「导入」后没有拉起系统文件选择器（前台包仍为 $packageName）", opened)
            device.pressBack()
        }
    }
}
