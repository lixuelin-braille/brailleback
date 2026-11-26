/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 侧载自检的厂商 Profile：纯数据 + 纯匹配，不 import 任何 android.* API，
 * 保证 JVM 单测可直接跑。系统查询与 Intent 构建全部留在 Activity 侧。
 *
 * 为什么按厂商差异化：各家官方文档给的坑不一样（小米 OTG 自动关闭、华为/荣耀
 * 清理后台后无障碍被关、OPPO 速冻、vivo 卡片加锁无直达 Intent），通用清单对
 * 盲人用户没有可操作性。匹配不到的机器回落 [SelfCheckVendor.GENERIC]，只保留
 * 无障碍、USB 权限、电池优化三项平台标准检查。
 */

package com.linxi.brailleback.onboarding

/** 厂商分类。子品牌跟母品牌走（Redmi/POCO → 小米，realme/一加 → OPPO，iQOO → vivo）。 */
enum class SelfCheckVendor { XIAOMI, HUAWEI, HONOR, OPPO, VIVO, SAMSUNG, GENERIC }

/**
 * 自检目标项。声明顺序即默认播报 / 跳转优先级。
 *
 * [SERVICE] 的四种成因（从未开启 / 受限设置 / 被清理关闭 / 疑似高级保护）由引擎按
 * 平台信号判定，不按厂商分支；Profile 只保留自启动 Intent 候选与「加锁 / OTG」步骤标志。
 */
enum class SelfCheckTarget {
    SERVICE,
    USB_PERMISSION,
    AUTO_START,
    BATTERY,
    LOCK_STEP,
    OTG_STEP,
}

/**
 * 一个可尝试的跳转候选。
 *
 * [action] 非空时是隐式 Intent action；否则按 [component]（`包名/类名`）走显式组件。
 * [dataPackage] 表示 Intent 要带 `package:<包名>` 的数据 URI（应用详情 / 电池白名单）。
 */
data class SelfCheckCandidate(
    val action: String? = null,
    val component: String? = null,
    val dataPackage: Boolean = false,
)

/**
 * 厂商 Profile。
 *
 * [hasLockStep]：这家 ROM 有没有「多任务下拉卡片加锁」步骤；该步骤只能手势完成，
 * 没有可用 Intent，页面上只给文字指引。
 * [hasOtgStep]：有没有 OTG 开关 / 闲置自动关闭的文字指引步骤。
 * [candidates]：各 target 的有序候选，Activity 逐个 try/catch，全失败回落应用信息页。
 */
data class OemProfile(
    val vendor: SelfCheckVendor,
    val hasLockStep: Boolean,
    val hasOtgStep: Boolean,
    val candidates: Map<SelfCheckTarget, List<SelfCheckCandidate>>,
)

/** 跳转路由常量。纯字符串，不引用 android.provider.Settings，保持本层可在 JVM 跑。 */
internal object SelfCheckRoutes {
    const val ACCESSIBILITY_SETTINGS = "android.settings.ACCESSIBILITY_SETTINGS"
    const val APP_DETAILS_SETTINGS = "android.settings.APPLICATION_DETAILS_SETTINGS"
    const val REQUEST_IGNORE_BATTERY = "android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"
    const val IGNORE_BATTERY_SETTINGS = "android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS"
    const val MIUI_AUTO_START = "miui.intent.action.OP_AUTO_START"

    /** 应用详情页：带本应用包名数据 URI。 */
    fun appDetails(): SelfCheckCandidate =
        SelfCheckCandidate(APP_DETAILS_SETTINGS, dataPackage = true)

    /** 电池优化白名单：先弹系统确认框，失败回落电池优化列表。 */
    fun batteryCandidates(): List<SelfCheckCandidate> = listOf(
        SelfCheckCandidate(REQUEST_IGNORE_BATTERY, dataPackage = true),
        SelfCheckCandidate(IGNORE_BATTERY_SETTINGS),
    )
}

/**
 * 按 `Build.MANUFACTURER` + `Build.BRAND` 双匹配厂商。
 *
 * 两个字段都参与匹配，大小写不敏感；任一字段命中即归该厂商。荣耀必须在华为之前
 * 判断，避免 honor 机型被并入华为。
 */
fun resolveProfile(manufacturer: String?, brand: String?): OemProfile {
    val names = listOfNotNull(manufacturer, brand)
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
    fun has(vararg keys: String) = names.any { name -> keys.any { name.contains(it) } }

    return when {
        has("xiaomi", "redmi", "poco") -> xiaomiProfile()
        has("honor", "hihonor") -> honorProfile()
        has("huawei") -> huaweiProfile()
        has("oppo", "realme", "oneplus", "oplus") -> oppoProfile()
        has("vivo", "iqoo") -> vivoProfile()
        has("samsung") -> samsungProfile()
        else -> genericProfile()
    }
}

private fun xiaomiProfile(): OemProfile = OemProfile(
    vendor = SelfCheckVendor.XIAOMI,
    hasLockStep = false,
    hasOtgStep = true,
    candidates = mapOf(
        SelfCheckTarget.AUTO_START to listOf(
            // 官方 FAQ 给的 action 优先；失败再试安全中心显式组件。
            SelfCheckCandidate(SelfCheckRoutes.MIUI_AUTO_START),
            SelfCheckCandidate(
                component = "com.miui.securitycenter/" +
                    "com.miui.permcenter.autostart.AutoStartManagementActivity",
            ),
        ),
    ),
)

private fun huaweiProfile(): OemProfile = OemProfile(
    vendor = SelfCheckVendor.HUAWEI,
    hasLockStep = true,
    hasOtgStep = false,
    candidates = mapOf(
        SelfCheckTarget.AUTO_START to listOf(
            SelfCheckCandidate(
                component = "com.huawei.systemmanager/" +
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            ),
        ),
    ),
)

private fun honorProfile(): OemProfile = OemProfile(
    vendor = SelfCheckVendor.HONOR,
    hasLockStep = true,
    hasOtgStep = false,
    candidates = mapOf(
        SelfCheckTarget.AUTO_START to listOf(
            SelfCheckCandidate(
                component = "com.hihonor.systemmanager/" +
                    "com.honor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            ),
            // 老版本 MagicOS 复用华为组件，作为第二候选逐个 try。
            SelfCheckCandidate(
                component = "com.huawei.systemmanager/" +
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            ),
        ),
    ),
)

private fun oppoProfile(): OemProfile = OemProfile(
    vendor = SelfCheckVendor.OPPO,
    hasLockStep = false,
    hasOtgStep = false,
    candidates = mapOf(
        SelfCheckTarget.AUTO_START to listOf(
            SelfCheckCandidate(
                component = "com.coloros.safecenter/" +
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity",
            ),
            SelfCheckCandidate(
                component = "com.oppo.safe/" +
                    "com.oppo.safe.permission.startup.StartupAppListActivity",
            ),
        ),
    ),
)

private fun vivoProfile(): OemProfile = OemProfile(
    vendor = SelfCheckVendor.VIVO,
    hasLockStep = true,
    hasOtgStep = false,
    candidates = mapOf(
        SelfCheckTarget.AUTO_START to listOf(
            SelfCheckCandidate(
                component = "com.vivo.permissionmanager/" +
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            ),
        ),
    ),
)

private fun samsungProfile(): OemProfile = OemProfile(
    vendor = SelfCheckVendor.SAMSUNG,
    hasLockStep = false,
    hasOtgStep = false,
    candidates = emptyMap(),
)

private fun genericProfile(): OemProfile = OemProfile(
    vendor = SelfCheckVendor.GENERIC,
    hasLockStep = false,
    hasOtgStep = false,
    candidates = emptyMap(),
)
