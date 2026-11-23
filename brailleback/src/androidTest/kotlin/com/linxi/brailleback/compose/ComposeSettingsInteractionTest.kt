/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 仪器测试：设置屏的 Compose 交互闭环。
 *
 * 为什么需要它：此前 14 例仪器测试只证明「页面能起」，交互全靠 UiAutomator 点一个
 * 「导入」按钮。而七个设置屏共用 SettingsComponents 这一套底座——开关行、选择行、
 * 动作行——它们的点击语义（整行可点、读屏 role、禁用态）没有任何自动化验证。
 * 依赖解冻后 ui-test-junit4 有了与 Compose 同版的 1.12.1，这里补上最小的真实点击。
 *
 * 用 createComposeRule 直接渲染组件，不启动 Activity：组件是纯展示层（状态由参数传、
 * 动作走回调），这样测试不依赖 TranslatorManager、点显器连接等系统资源。
 */

package com.linxi.brailleback.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class ComposeSettingsInteractionTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun switchRowClickReportsNewValue() {
        val reported = mutableListOf<Boolean>()
        composeRule.setContent {
            MaterialTheme {
                Column {
                    SettingsSwitchRow(
                        title = "中文分词",
                        summary = "把连续汉字按词组切分后再转盲文",
                        checked = false,
                        onLabel = "已开启",
                        offLabel = "已关闭",
                        onCheckedChange = { reported += it },
                    )
                }
            }
        }
        // 点标题本身即点整行：整行 toggleable 是刻意的（读屏用户不必瞄开关）。
        composeRule.onNodeWithText("中文分词").performClick()
        composeRule.waitForIdle()
        assertEquals("关着的时候点一下必须上报 true", listOf(true), reported)
    }

    @Test
    fun switchRowWhenCheckedReportsFalse() {
        val reported = mutableListOf<Boolean>()
        composeRule.setContent {
            MaterialTheme {
                Column {
                    SettingsSwitchRow(
                        title = "词间空格",
                        summary = "英文与数字之间插入空方",
                        checked = true,
                        onLabel = "已开启",
                        offLabel = "已关闭",
                        onCheckedChange = { reported += it },
                    )
                }
            }
        }
        composeRule.onNodeWithText("词间空格").performClick()
        composeRule.waitForIdle()
        assertEquals("开着的时候点一下必须上报 false", listOf(false), reported)
    }

    @Test
    fun disabledSwitchRowIsNotClickable() {
        val reported = mutableListOf<Boolean>()
        composeRule.setContent {
            MaterialTheme {
                Column {
                    SettingsSwitchRow(
                        title = "需要先连接点显器",
                        summary = "连接后才可修改",
                        checked = false,
                        onLabel = "已开启",
                        offLabel = "已关闭",
                        onCheckedChange = { reported += it },
                        enabled = false,
                    )
                }
            }
        }
        composeRule.onNodeWithText("需要先连接点显器").assertIsNotEnabled()
        composeRule.waitForIdle()
        assertEquals("禁用态不允许上报变化", emptyList<Boolean>(), reported)
    }

    @Test
    fun listRowClickFiresOnce() {
        var clicks = 0
        composeRule.setContent {
            MaterialTheme {
                Column {
                    SettingsListRow(
                        title = "盲文方案",
                        currentLabel = "中文（现行）",
                        chooseHint = "点按选择",
                        onClick = { clicks++ },
                    )
                }
            }
        }
        composeRule.onNodeWithText("盲文方案").performClick()
        composeRule.waitForIdle()
        assertEquals("点行一次必须只回调一次", 1, clicks)
    }

    @Test
    fun actionRowClickFires() {
        var clicks = 0
        composeRule.setContent {
            MaterialTheme {
                Column {
                    SettingsActionRow(
                        title = "刷新连接状态",
                        summary = "重新检测点显器是否在线",
                        clickLabel = "点按刷新",
                        onClick = { clicks++ },
                    )
                }
            }
        }
        composeRule.onNodeWithText("刷新连接状态").performClick()
        composeRule.waitForIdle()
        assertEquals("动作行点击必须回调", 1, clicks)
    }
}
