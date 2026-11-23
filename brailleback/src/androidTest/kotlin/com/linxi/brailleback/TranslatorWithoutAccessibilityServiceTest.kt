/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 契约：盲文翻译不能依赖无障碍服务被启用。
 *
 * 阅读器的「导入 txt/epub → 翻译成盲文 → 导出」整条链路只经过
 * TranslatorClient 绑定同包的 TranslatorService（BIND_AUTO_CREATE），
 * 与 BrailleBackService（无障碍服务）是否启用无关。本用例在无障碍服务
 * 关闭的状态下直接建 TranslatorManager，验证拿到翻译器并能翻出点位。
 *
 * 这不是“顺便测一下”：历史上阅读器在服务未就绪时只写一句提示就返回，
 * 用户看到的正是“导入没反应”。所以这里必须真翻一段中文、断言出点位。
 */

package com.linxi.brailleback

import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslatorWithoutAccessibilityServiceTest {

    private fun accessibilityServiceEnabled(): Boolean {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val enabled = Settings.Secure.getString(
            ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return enabled.contains(ctx.packageName)
    }

    @Test
    fun translatorReadyAndTranslatesWithoutAccessibilityService() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(
            "本用例前提是无障碍服务未启用，当前却已启用（环境不符，结论无效）",
            !accessibilityServiceEnabled())

        // TranslatorClient 内部 new Handler()，必须建在有 Looper 的线程（App 里就是主线程）。
        val ready = CountDownLatch(1)
        var manager: TranslatorManager? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val m = TranslatorManager(ctx)
            manager = m
            m.addOnTablesChangedListener(object : TranslatorManager.OnTablesChangedListener {
                override fun onTablesChanged() {
                    if (m.getTranslator() != null) ready.countDown()
                }
            })
            if (m.getTranslator() != null) ready.countDown()
        }
        val m = manager!!
        // 首次绑定是异步的：等最多 15 秒，拿不到就是真失败。
        assertTrue("15 秒内没拿到翻译器", ready.await(15, TimeUnit.SECONDS))
        val translator = m.getTranslator()
        assertNotNull("翻译器为空", translator)

        val result = translator!!.translate("你好，世界。", 0, false)
        assertNotNull("翻译返回空结果", result)
        assertTrue("翻译出的点位为空", result!!.cells.isNotEmpty())
        InstrumentationRegistry.getInstrumentation().runOnMainSync { m.shutdown() }
    }
}
