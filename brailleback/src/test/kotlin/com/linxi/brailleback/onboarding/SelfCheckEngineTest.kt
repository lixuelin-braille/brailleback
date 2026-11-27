/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 侧载自检纯逻辑层单测：四成因判定顺序 + 七家厂商 × 三档状态矩阵。
 * 断言三件事：① 选中的 Intent action / component 与候选顺序；
 * ② 各家不该出现的项确实没出现（Generic / 三星无加锁无 OTG、无厂商自启动候选）；
 * ③ 全部候选失败回落应用信息页并记 UNKNOWN，不标成已处理。
 *
 * 服务成因只依赖 API 级别 + everEnabled 历史，不按厂商分支；厂商分支只出现在
 * 自启动 Intent 候选与「加锁」步骤是否出现两处。
 */

package com.linxi.brailleback.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfCheckEngineTest {

    private fun profileFor(vendor: SelfCheckVendor): OemProfile = when (vendor) {
        SelfCheckVendor.XIAOMI -> resolveProfile("Xiaomi", "Redmi")
        SelfCheckVendor.HUAWEI -> resolveProfile("HUAWEI", null)
        SelfCheckVendor.HONOR -> resolveProfile("HONOR", null)
        SelfCheckVendor.OPPO -> resolveProfile("OPPO", "realme")
        SelfCheckVendor.VIVO -> resolveProfile("vivo", "iQOO")
        SelfCheckVendor.SAMSUNG -> resolveProfile("samsung", null)
        SelfCheckVendor.GENERIC -> resolveProfile(null, null)
    }

    private fun state(
        enabled: Boolean?,
        everEnabled: Boolean = false,
        returned: Boolean = false,
        api: Int = 37,
        restrictedFailures: Int = 0,
    ) = ServiceState(
        isEnabled = enabled,
        everEnabled = everEnabled,
        returnedFromAccessibility = returned,
        apiLevel = api,
        restrictedFailures = restrictedFailures,
    )

    /** 各厂商在自检页应出现的 target，顺序即播报 / 跳转优先级。 */
    private fun expectedTargets(vendor: SelfCheckVendor): List<SelfCheckTarget> = when (vendor) {
        SelfCheckVendor.XIAOMI -> listOf(
            SelfCheckTarget.SERVICE,
            SelfCheckTarget.USB_PERMISSION,
            SelfCheckTarget.AUTO_START,
            SelfCheckTarget.BATTERY,
            SelfCheckTarget.OTG_STEP,
        )
        SelfCheckVendor.HUAWEI, SelfCheckVendor.HONOR -> listOf(
            SelfCheckTarget.SERVICE,
            SelfCheckTarget.USB_PERMISSION,
            SelfCheckTarget.AUTO_START,
            SelfCheckTarget.BATTERY,
            SelfCheckTarget.LOCK_STEP,
        )
        SelfCheckVendor.OPPO -> listOf(
            SelfCheckTarget.SERVICE,
            SelfCheckTarget.USB_PERMISSION,
            SelfCheckTarget.AUTO_START,
            SelfCheckTarget.BATTERY,
        )
        SelfCheckVendor.VIVO -> listOf(
            SelfCheckTarget.SERVICE,
            SelfCheckTarget.USB_PERMISSION,
            SelfCheckTarget.AUTO_START,
            SelfCheckTarget.BATTERY,
            SelfCheckTarget.LOCK_STEP,
        )
        SelfCheckVendor.SAMSUNG, SelfCheckVendor.GENERIC -> listOf(
            SelfCheckTarget.SERVICE,
            SelfCheckTarget.USB_PERMISSION,
            SelfCheckTarget.BATTERY,
        )
    }

    // ------------------------------------------------------- 四成因判定顺序

    @Test
    fun `曾开过又变 false 判定为被系统清理关闭`() {
        val cause = SelfCheckEngine.decideServiceCause(
            state(enabled = false, everEnabled = true, returned = true, api = 33),
        )
        assertEquals(ServiceCause.CLEANED_BY_SYSTEM, cause)
    }

    @Test
    fun `从未开过且刚从设置返回 在 Android 13 判定为受限设置`() {
        val cause = SelfCheckEngine.decideServiceCause(
            state(enabled = false, everEnabled = false, returned = true, api = 33),
        )
        assertEquals(ServiceCause.RESTRICTED_SETTINGS, cause)
    }

    @Test
    fun `API 低于 33 不出现受限设置判定`() {
        val cause = SelfCheckEngine.decideServiceCause(
            state(enabled = false, everEnabled = false, returned = true, api = 31),
        )
        assertEquals(ServiceCause.NEVER_ENABLED, cause)
    }

    @Test
    fun `已启用判定为 OK`() {
        assertEquals(ServiceCause.OK, SelfCheckEngine.decideServiceCause(state(enabled = true)))
    }

    @Test
    fun `查询读不到判定为 UNKNOWN 不猜状态`() {
        assertEquals(ServiceCause.UNKNOWN, SelfCheckEngine.decideServiceCause(state(enabled = null)))
    }

    @Test
    fun `Android 16 且受限路径反复失败判定为疑似 AAPM 并记 UNKNOWN`() {
        val profile = profileFor(SelfCheckVendor.GENERIC)
        val results = SelfCheckEngine.evaluate(
            profile,
            state(enabled = false, everEnabled = false, returned = true, api = 36, restrictedFailures = 2),
            emptyMap(),
        )
        val service = results.first { it.target == SelfCheckTarget.SERVICE }
        assertEquals(ServiceCause.AAPM_SUSPECTED, service.cause)
        assertEquals(SelfCheckStatus.UNKNOWN, service.status)
        assertTrue(service.candidates.isEmpty())
        assertNull(service.fallback)
    }

    @Test
    fun `受限失败未达次数仍按受限设置处理不算 AAPM`() {
        val cause = SelfCheckEngine.decideServiceCause(
            state(enabled = false, everEnabled = false, returned = true, api = 36, restrictedFailures = 1),
        )
        assertEquals(ServiceCause.RESTRICTED_SETTINGS, cause)
    }

    // ------------------------------------------------------- 七家矩阵与候选

    @Test
    fun `七家 Profile 的适用目标与优先级`() {
        for (vendor in SelfCheckVendor.entries) {
            val profile = profileFor(vendor)
            val results = SelfCheckEngine.evaluate(profile, state(enabled = true), emptyMap())
            assertEquals(expectedTargets(vendor), results.map { it.target })
        }
    }

    @Test
    fun `七家 x 三档状态矩阵`() {
        for (vendor in SelfCheckVendor.entries) {
            val profile = profileFor(vendor)
            for (status in SelfCheckStatus.entries) {
                val nonService = expectedTargets(vendor)
                    .filter { it != SelfCheckTarget.SERVICE }
                    .associateWith { status }
                val results = SelfCheckEngine.evaluate(profile, state(enabled = true), nonService)
                assertEquals(
                    "$vendor $status 顺序必须与优先级一致",
                    expectedTargets(vendor),
                    results.map { it.target },
                )
                results.forEach { result ->
                    if (result.target == SelfCheckTarget.SERVICE) return@forEach
                    assertEquals("$vendor $status 档位", status, result.status)
                    when (status) {
                        SelfCheckStatus.OK, SelfCheckStatus.UNKNOWN -> {
                            // 读不到 / 已达标都不许带跳转候选，禁止猜成可处理。
                            assertTrue(result.candidates.isEmpty())
                            assertNull(result.fallback)
                        }
                        SelfCheckStatus.NEED_ACTION -> assertCandidates(vendor, result)
                    }
                }
            }
        }
    }

    private fun assertCandidates(vendor: SelfCheckVendor, result: SelfCheckResult) {
        when (result.target) {
            SelfCheckTarget.USB_PERMISSION -> {
                assertEquals(SelfCheckRoutes.appDetails(), result.candidates.single())
                assertEquals(SelfCheckRoutes.appDetails(), result.fallback)
            }
            SelfCheckTarget.BATTERY -> {
                assertEquals(SelfCheckRoutes.batteryCandidates(), result.candidates)
                assertEquals(SelfCheckRoutes.appDetails(), result.fallback)
            }
            SelfCheckTarget.AUTO_START -> assertAutostart(vendor, result)
            SelfCheckTarget.LOCK_STEP, SelfCheckTarget.OTG_STEP -> {
                // 系统不开放 Intent：候选为空，也没有兜底跳转，只给文字指引。
                assertTrue(result.candidates.isEmpty())
                assertNull(result.fallback)
            }
            SelfCheckTarget.SERVICE -> error("服务项成因在顺序用例里单独断言")
        }
    }

    private fun assertAutostart(vendor: SelfCheckVendor, result: SelfCheckResult) {
        val components = result.candidates.mapNotNull { it.component }
        val actions = result.candidates.mapNotNull { it.action }
        when (vendor) {
            SelfCheckVendor.XIAOMI -> {
                assertEquals(listOf(SelfCheckRoutes.MIUI_AUTO_START), actions)
                assertEquals(
                    listOf(
                        "com.miui.securitycenter/" +
                            "com.miui.permcenter.autostart.AutoStartManagementActivity",
                    ),
                    components,
                )
            }
            SelfCheckVendor.HUAWEI -> assertEquals(
                listOf(
                    "com.huawei.systemmanager/" +
                        "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                ),
                components,
            )
            SelfCheckVendor.HONOR -> assertEquals(
                listOf(
                    "com.hihonor.systemmanager/" +
                        "com.honor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                    "com.huawei.systemmanager/" +
                        "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                ),
                components,
            )
            SelfCheckVendor.OPPO -> assertEquals(
                listOf(
                    "com.coloros.safecenter/" +
                        "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                    "com.oppo.safe/" +
                        "com.oppo.safe.permission.startup.StartupAppListActivity",
                ),
                components,
            )
            SelfCheckVendor.VIVO -> assertEquals(
                listOf(
                    "com.vivo.permissionmanager/" +
                        "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                ),
                components,
            )
            SelfCheckVendor.SAMSUNG, SelfCheckVendor.GENERIC ->
                error("三星 / 通用 Profile 不应出现自启动项")
        }
        assertEquals(SelfCheckRoutes.appDetails(), result.fallback)
    }

    @Test
    fun `服务项各成因的跳转候选与兜底`() {
        val profile = profileFor(SelfCheckVendor.GENERIC)

        val never = SelfCheckEngine.evaluate(
            profile, state(enabled = false, everEnabled = false, api = 31), emptyMap(),
        ).first { it.target == SelfCheckTarget.SERVICE }
        assertEquals(ServiceCause.NEVER_ENABLED, never.cause)
        assertEquals(SelfCheckRoutes.ACCESSIBILITY_SETTINGS, never.candidates.single().action)
        assertEquals(SelfCheckRoutes.appDetails(), never.fallback)

        val restricted = SelfCheckEngine.evaluate(
            profile, state(enabled = false, returned = true, api = 33), emptyMap(),
        ).first { it.target == SelfCheckTarget.SERVICE }
        assertEquals(ServiceCause.RESTRICTED_SETTINGS, restricted.cause)
        assertEquals(SelfCheckRoutes.appDetails(), restricted.candidates.single())
        assertNull(restricted.fallback)

        val cleaned = SelfCheckEngine.evaluate(
            profileFor(SelfCheckVendor.XIAOMI),
            state(enabled = false, everEnabled = true),
            emptyMap(),
        ).first { it.target == SelfCheckTarget.SERVICE }
        assertEquals(ServiceCause.CLEANED_BY_SYSTEM, cleaned.cause)
        // 被清理关闭的补救路径复用这家 ROM 的自启动候选，兜底应用信息页。
        assertTrue(cleaned.candidates.isNotEmpty())
        assertEquals(SelfCheckRoutes.appDetails(), cleaned.fallback)
    }

    @Test
    fun `通用与三星不加锁不引导 OTG 也无自启动候选`() {
        for (vendor in listOf(SelfCheckVendor.SAMSUNG, SelfCheckVendor.GENERIC)) {
            val results = SelfCheckEngine.evaluate(profileFor(vendor), state(enabled = true), emptyMap())
            assertTrue(results.none { it.target == SelfCheckTarget.LOCK_STEP })
            assertTrue(results.none { it.target == SelfCheckTarget.OTG_STEP })
            assertTrue(results.none { it.target == SelfCheckTarget.AUTO_START })
        }
    }

    @Test
    fun `全部候选失败改标 UNKNOWN 不保留动作`() {
        val profile = profileFor(SelfCheckVendor.XIAOMI)
        val checks = mapOf(SelfCheckTarget.AUTO_START to SelfCheckStatus.NEED_ACTION)
        val original = SelfCheckEngine.evaluate(profile, state(enabled = true), checks)
            .first { it.target == SelfCheckTarget.AUTO_START }
        assertEquals(SelfCheckStatus.NEED_ACTION, original.status)
        val unresolved = SelfCheckEngine.markUnresolved(original)
        assertEquals(SelfCheckStatus.UNKNOWN, unresolved.status)
        assertTrue(unresolved.candidates.isEmpty())
        assertNull(unresolved.fallback)
    }

    @Test
    fun `厂商双匹配识别子品牌且荣耀先于华为`() {
        assertEquals(SelfCheckVendor.XIAOMI, resolveProfile("Redmi", "POCO").vendor)
        assertEquals(SelfCheckVendor.HUAWEI, resolveProfile("HUAWEI", "HarmonyOS").vendor)
        assertEquals(SelfCheckVendor.HONOR, resolveProfile("HONOR", "MagicOS").vendor)
        assertEquals(SelfCheckVendor.OPPO, resolveProfile("realme", "OnePlus").vendor)
        assertEquals(SelfCheckVendor.VIVO, resolveProfile("iQOO", "vivo").vendor)
        assertEquals(SelfCheckVendor.SAMSUNG, resolveProfile("samsung", "Galaxy").vendor)
        assertEquals(SelfCheckVendor.GENERIC, resolveProfile(null, null).vendor)
        assertEquals(SelfCheckVendor.GENERIC, resolveProfile("", "Nokia").vendor)
    }

    @Test
    fun `manufacturer 为空时退回 brand`() {
        assertEquals(SelfCheckVendor.XIAOMI, resolveProfile(null, "Xiaomi").vendor)
    }
}
