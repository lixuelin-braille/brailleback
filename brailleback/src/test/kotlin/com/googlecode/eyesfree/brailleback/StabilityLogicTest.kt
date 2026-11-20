/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 保活自检的纯逻辑。
 *
 * 这层不能碰 Context，所以系统查询全部在 Activity 里；这里只锁判定规则和厂商映射。
 */
class StabilityLogicTest {

    private fun snapshot(
        serviceRunning: Boolean = true,
        serviceEnabled: Boolean = true,
        batteryUnrestricted: Boolean = true,
        backgroundRestricted: Boolean = false,
        notificationGranted: Boolean = true,
        bluetoothGranted: Boolean = true,
    ) = StabilitySnapshot(
        serviceRunning = serviceRunning,
        serviceEnabled = serviceEnabled,
        batteryUnrestricted = batteryUnrestricted,
        backgroundRestricted = backgroundRestricted,
        notificationGranted = notificationGranted,
        bluetoothGranted = bluetoothGranted,
    )

    @Test
    fun `四项全达标时没有任何问题`() {
        assertEquals(emptyList<StabilityIssue>(), snapshot().issues())
    }

    @Test
    fun `服务没跑排在最前`() {
        val issues = snapshot(serviceRunning = false, batteryUnrestricted = false).issues()
        assertEquals(
            listOf(StabilityIssue.SERVICE_NOT_RUNNING, StabilityIssue.BATTERY_OPTIMIZATION),
            issues,
        )
    }

    @Test
    fun `没启用也算服务没跑但状态要能区分`() {
        // 判定层不做区分：只要没在跑就报一项。
        // 「没启用」与「启用后被杀」的区分留在文案层（issueText / 服务行），
        // 因为两者的处理动作不同：前者去无障碍里打开，后者查省电策略。
        assertEquals(
            listOf(StabilityIssue.SERVICE_NOT_RUNNING),
            snapshot(serviceRunning = false, serviceEnabled = false).issues(),
        )
        assertEquals(
            listOf(StabilityIssue.SERVICE_NOT_RUNNING),
            snapshot(serviceRunning = false, serviceEnabled = true).issues(),
        )
    }

    @Test
    fun `厂商后台受限要报出来`() {
        // 小米 Powerkeeper 限制时 isBackgroundRestricted() 为 true（官方文档口径）。
        assertEquals(
            listOf(StabilityIssue.BACKGROUND_RESTRICTED),
            snapshot(backgroundRestricted = true).issues(),
        )
    }

    @Test
    fun `权限缺失各算一项`() {
        assertEquals(
            listOf(StabilityIssue.NOTIFICATION),
            snapshot(notificationGranted = false).issues(),
        )
        assertEquals(
            listOf(StabilityIssue.BLUETOOTH),
            snapshot(bluetoothGranted = false).issues(),
        )
    }

    @Test
    fun `摘要全达标时只说一句`() {
        val text = StabilitySummary.describe(
            issues = emptyList(),
            allOk = "都正常",
            issueText = { it.name },
        )
        assertEquals("都正常", text)
    }

    @Test
    fun `摘要不达标时按顺序拼接`() {
        val text = StabilitySummary.describe(
            issues = snapshot(serviceRunning = false, backgroundRestricted = true).issues(),
            allOk = "都正常",
            issueText = { if (it == StabilityIssue.SERVICE_NOT_RUNNING) "服务没跑" else "被限制" },
        )
        assertEquals("服务没跑、被限制", text)
    }

    @Test
    fun `厂商识别不区分大小写并跟子品牌走`() {
        assertEquals(OemVendor.XIAOMI, oemVendor("Xiaomi", "Redmi"))
        assertEquals(OemVendor.XIAOMI, oemVendor("POCO", null))
        assertEquals(OemVendor.HUAWEI, oemVendor("HONOR", null))
        assertEquals(OemVendor.OPPO, oemVendor("realme", null))
        assertEquals(OemVendor.VIVO, oemVendor("iQOO", null))
        assertEquals(OemVendor.ONEPLUS, oemVendor("OnePlus", null))
        assertEquals(OemVendor.SAMSUNG, oemVendor("samsung", null))
        assertEquals(OemVendor.GENERIC, oemVendor(null, null))
        assertEquals(OemVendor.GENERIC, oemVendor("", "Nokia"))
    }

    @Test
    fun `manufacturer 为空时退回 brand`() {
        assertEquals(OemVendor.XIAOMI, oemVendor(null, "Xiaomi"))
    }

    @Test
    fun `组件名可解析且非法格式返回 null`() {
        val parsed = parseComponent(autostartComponent(OemVendor.XIAOMI))
        assertEquals("com.miui.securitycenter", parsed?.first)
        assertEquals(
            "com.miui.permcenter.autostart.AutoStartManagementActivity",
            parsed?.second,
        )

        assertNull(parseComponent(null))
        assertNull(parseComponent("com.miui.securitycenter"))
        assertNull(parseComponent("com.miui.securitycenter/"))
        assertNull(parseComponent("/AutoStart"))
        assertNull(parseComponent(" / "))
    }

    @Test
    fun `没有自启动页的厂商明确返回 null`() {
        // 返回 null 是约定：调用方必须退回应用详情页，而不是假定入口存在。
        assertNull(autostartComponent(OemVendor.SAMSUNG))
        assertNull(autostartComponent(OemVendor.GENERIC))
        assertTrue(requireNotNull(autostartComponent(OemVendor.XIAOMI)).contains('/'))
    }
}