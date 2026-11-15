/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback

/**
 * 保活自检的纯逻辑部分：不碰 Context，能在 JVM 单测里跑。
 *
 * 为什么要有这个屏：无障碍服务被系统或厂商省电策略杀掉时，点显器是**静默断开**的，
 * 用户摸到的只是「不动了」，看不出原因。这里把四件会影响进程存活的事摊开显示：
 * 电池优化白名单、厂商后台限制、通知权限、前台服务是否在跑。
 */
internal data class StabilitySnapshot(
    /** 前台服务（connectedDevice 类型）当前是否活着。 */
    val serviceRunning: Boolean,
    /** 无障碍服务是否已在系统里启用。没启用时「没在跑」是正常的，不是掉线。 */
    val serviceEnabled: Boolean = true,
    /** 已加入电池优化白名单（Doze 不限制）。 */
    val batteryUnrestricted: Boolean,
    /** 系统/厂商判定为后台受限。小米 Powerkeeper 限制时这里为 true（官方文档口径）。 */
    val backgroundRestricted: Boolean,
    val notificationGranted: Boolean,
    /** Android 12+ 的 BLUETOOTH_CONNECT。低版本恒为 true。 */
    val bluetoothGranted: Boolean,
)

/** 自检项。顺序即播报顺序：先「服务没跑」，再「会被杀」，最后「授权缺失」。 */
internal enum class StabilityIssue {
    SERVICE_NOT_RUNNING,
    BATTERY_OPTIMIZATION,
    BACKGROUND_RESTRICTED,
    NOTIFICATION,
    BLUETOOTH,
}

/**
 * 哪些项不达标。
 *
 * 通知权限刻意算在内：前台服务的通知被关掉后，用户看不到「服务在跑」这条常驻提示，
 * 也就无从判断服务是否被系统收走；它不影响服务本身运行，但影响可判断性。
 */
internal fun StabilitySnapshot.issues(): List<StabilityIssue> = buildList {
    if (!serviceRunning) add(StabilityIssue.SERVICE_NOT_RUNNING)
    if (!batteryUnrestricted) add(StabilityIssue.BATTERY_OPTIMIZATION)
    if (backgroundRestricted) add(StabilityIssue.BACKGROUND_RESTRICTED)
    if (!notificationGranted) add(StabilityIssue.NOTIFICATION)
    if (!bluetoothGranted) add(StabilityIssue.BLUETOOTH)
}

/** 厂商保活入口。不同 ROM 的自启动管理页组件名不同，且都可能不存在。 */
internal enum class OemVendor { XIAOMI, HUAWEI, OPPO, ONEPLUS, VIVO, SAMSUNG, MEIZU, GENERIC }

/**
 * 按 `Build.MANUFACTURER` / `Build.BRAND` 判定厂商。
 *
 * 大小写不敏感；子品牌跟着母品牌走（Redmi/POCO → 小米，Honor → 华为，realme → OPPO，
 * iQOO → vivo），因为这些子品牌的控制中心界面与母品牌一致。
 */
internal fun oemVendor(manufacturer: String?, brand: String?): OemVendor {
    val names = listOfNotNull(manufacturer, brand)
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
    for (name in names) {
        when {
            name.contains("xiaomi") || name.contains("redmi") || name.contains("poco") ->
                return OemVendor.XIAOMI
            name.contains("huawei") || name.contains("honor") ->
                return OemVendor.HUAWEI
            name.contains("oneplus") ->
                return OemVendor.ONEPLUS
            name.contains("oppo") || name.contains("realme") ->
                return OemVendor.OPPO
            name.contains("vivo") || name.contains("iqoo") ->
                return OemVendor.VIVO
            name.contains("samsung") ->
                return OemVendor.SAMSUNG
            name.contains("meizu") ->
                return OemVendor.MEIZU
        }
    }
    return OemVendor.GENERIC
}

/**
 * 厂商「自启动 / 后台运行」管理页的组件名，格式 `包名/类名`；`null` 表示没有可用入口，
 * 调用方应退回系统「应用详情」页。
 *
 * 这些组件名不是公开 API，ROM 升级后就可能失效 —— 调用方必须捕获
 * ActivityNotFoundException 并退回应用详情，而不是假定它一定存在。
 */
internal fun autostartComponent(vendor: OemVendor): String? = when (vendor) {
    OemVendor.XIAOMI ->
        "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity"
    OemVendor.HUAWEI ->
        "com.huawei.systemmanager/com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
    OemVendor.OPPO ->
        "com.coloros.safecenter/com.coloros.safecenter.permission.startup.StartupAppListActivity"
    OemVendor.ONEPLUS ->
        "com.oneplus.security/com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
    OemVendor.VIVO ->
        "com.vivo.permissionmanager/com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
    OemVendor.MEIZU ->
        "com.meizu.safe/com.meizu.safe.security.SHOW_APPSEC"
    // 三星与其它 ROM 没有通用的自启动页，走「电池 → 后台用量限制」。
    OemVendor.SAMSUNG, OemVendor.GENERIC -> null
}

/** 把 `包名/类名` 拆成 ComponentName 需要的两段；格式不合法返回 `null`。 */
internal fun parseComponent(component: String?): Pair<String, String>? {
    if (component == null) return null
    val slash = component.indexOf('/')
    if (slash <= 0 || slash == component.length - 1) return null
    val pkg = component.substring(0, slash).trim()
    val cls = component.substring(slash + 1).trim()
    if (pkg.isEmpty() || cls.isEmpty()) return null
    return pkg to cls
}

/**
 * 一行摘要：全达标时只播一句「都正常」，不达标时把原因串起来。
 *
 * [issueText] 由调用方注入（要读资源），这里只管拼接规则，方便单测。
 */
internal object StabilitySummary {
    fun describe(
        issues: List<StabilityIssue>,
        allOk: String,
        issueText: (StabilityIssue) -> String,
    ): String = if (issues.isEmpty()) allOk else issues.joinToString("、", transform = issueText)
}
