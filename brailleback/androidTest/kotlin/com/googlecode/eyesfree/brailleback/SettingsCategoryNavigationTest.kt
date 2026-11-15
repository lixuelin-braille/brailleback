/*
 * 设置分类入口的点击闭环测试。
 *
 * 为什么单独一条：以前出过一次「设置里分类点击进不去、没反应」——settings.xml 里
 * 条目没落进文件，Preference 根本不存在，wireCategory 找一个 null 静默返回。
 * 那种 bug 编译能过、走查 JVM 单测和契约测试也过（key 字符串都在资源里），
 * 只有真的用手指点一下才会暴露。这条用例就是那个动作。
 */

package com.googlecode.eyesfree.brailleback

import android.app.Activity
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import com.googlecode.eyesfree.brailleback.compose.DeviceConnectionComposeActivity
import com.googlecode.eyesfree.brailleback.compose.KeysGesturesComposeActivity
import com.googlecode.eyesfree.brailleback.compose.StabilityComposeActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsCategoryNavigationTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    /** 当前 RESUMED 的 Activity。必须在主线程读，测试线程读会拿到正在变的状态。 */
    private fun resumedActivity(): Activity? {
        var activity: Activity? = null
        instrumentation.runOnMainSync {
            activity = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .firstOrNull()
        }
        return activity
    }

    private fun clickCategoryAndAssertOpens(
        titleRes: Int,
        expected: Class<out Activity>,
    ) {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            assertTrue(
                "设置主屏没有到 RESUMED",
                scenario.state == Lifecycle.State.RESUMED,
            )

            val device = UiDevice.getInstance(instrumentation)
            val title = context.getString(titleRes)
            val row = findRow(device, title)
            requireNotNull(row) { "设置页上找不到分类「$title」（已向上滚动三次）" }.click()

            // 点击后是异步启动：等前台 Activity 变成目标子屏，最多 8 秒。
            val deadline = System.currentTimeMillis() + 8000
            var current: Activity? = null
            while (System.currentTimeMillis() < deadline) {
                instrumentation.waitForIdleSync()
                current = resumedActivity()
                if (current != null && current.javaClass == expected) break
                Thread.sleep(100)
            }

            assertTrue(
                "点「$title」后前台应为 ${expected.simpleName}，实际是 ${current?.javaClass?.simpleName ?: "无 Activity"}",
                current != null && current.javaClass == expected,
            )
        }
    }

    /**
     * 分类不一定在首屏可见：屏幕小的设备上「稳定性与省电」要滚动才露出来，
     * uiautomator 的 By.text 只命中可见节点，所以找不到就往上滚一次再试。
     */
    private fun findRow(device: UiDevice, title: String): UiObject2? {
        repeat(4) { attempt ->
            device.findObject(By.text(title))?.let { return it }
            if (attempt < 3) {
                val width = device.displayWidth
                val height = device.displayHeight
                device.swipe(
                    width / 2,
                    (height * 0.75f).toInt(),
                    width / 2,
                    (height * 0.35f).toInt(),
                    40,
                )
                device.waitForIdle()
            }
        }
        return null
    }


    @Test
    fun deviceCategoryOpensDeviceConnectionScreen() {
        clickCategoryAndAssertOpens(
            R.string.pref_category_device_title,
            DeviceConnectionComposeActivity::class.java,
        )
    }

    @Test
    fun keysCategoryOpensKeysGesturesScreen() {
        clickCategoryAndAssertOpens(
            R.string.pref_category_keys_title,
            KeysGesturesComposeActivity::class.java,
        )
    }

    @Test
    fun stabilityCategoryOpensStabilityScreen() {
        // 第六个入口是后加的，最容易在 settings.xml 里漏条目。
        clickCategoryAndAssertOpens(
            R.string.pref_category_stability_title,
            StabilityComposeActivity::class.java,
        )
    }
}
