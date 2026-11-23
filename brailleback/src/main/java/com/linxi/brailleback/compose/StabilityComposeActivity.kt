/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 「稳定性与省电适配」子屏：把会影响无障碍服务存活的状态摊开，并给出一键入口。
 *
 * 为什么要这一屏：服务被系统或厂商省电策略杀掉时，点显器是静默断开的，
 * 用户摸到的只有「不动了」。这里能直接看到四项状态，也能直接从页面跳到对应设置。
 */

package com.linxi.brailleback.compose

import android.Manifest
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.app.NotificationManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.linxi.brailleback.BrailleBackService
import com.linxi.brailleback.OemVendor
import com.linxi.brailleback.R
import com.linxi.brailleback.StabilityIssue
import com.linxi.brailleback.StabilitySnapshot
import com.linxi.brailleback.StabilitySummary
import com.linxi.brailleback.autostartComponent
import com.linxi.brailleback.issues
import com.linxi.brailleback.oemVendor
import com.linxi.brailleback.parseComponent

class StabilityComposeActivity : AppCompatActivity() {

    /** 两个权限申请共用一个回调：无论结果如何都重算一次自检快照。 */
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()) {
        refreshSnapshot()
    }

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()) {
        refreshSnapshot()
    }

    private var snapshot by mutableStateOf(
        StabilitySnapshot(
            serviceRunning = false,
            batteryUnrestricted = false,
            backgroundRestricted = false,
            notificationGranted = false,
            bluetoothGranted = false,
        )
    )

    private val vendor: OemVendor by lazy { oemVendor(Build.MANUFACTURER, Build.BRAND) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        setContent {
            StabilityScreen(
                labels = buildLabels(),
                snapshot = snapshot,
                vendor = vendor,
                issueSummary = StabilitySummary.describe(
                    issues = snapshot.issues(),
                    allOk = getString(R.string.stability_summary_all_ok),
                    issueText = ::issueText,
                ),
                onOpenBatterySettings = { openBatteryOptimizationRequest() },
                onOpenAutostart = { openVendorBackgroundPage() },
                onRequestNotification = { requestNotificationPermission() },
                onRequestBluetooth = { requestBluetoothPermission() },
                onBack = { finish() },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // 用户从系统设置返回后必须重读：这一屏的全部价值就是「现在到底通没通过」。
        refreshSnapshot()
    }

    // -------------------------------------------------------------- 状态采集

    private fun refreshSnapshot() {
        snapshot = StabilitySnapshot(
            serviceRunning = BrailleBackService.getActiveInstance() != null,
            serviceEnabled = isServiceEnabled(),
            batteryUnrestricted = isIgnoringBatteryOptimizations(),
            backgroundRestricted = isBackgroundRestricted(),
            notificationGranted = areNotificationsEnabled(),
            bluetoothGranted = isBluetoothGranted(),
        )
    }

    /**
     * 无障碍服务是否已在系统里启用。
     *
     * 没启用时「服务没在跑」是正常状态，不是掉线；两种情况的处理动作完全不同
     * （没启用要去无障碍里打开，启用了却没跑才要查省电策略），所以必须分开读。
     * 系统里存的是 `pkg/.Cls` 短形式，用 [ComponentName.unflattenFromString] 比较，
     * 不能直接字符串相等。
     */
    private fun isServiceEnabled(): Boolean {
        val listening = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return listening.split(':')
            .mapNotNull { ComponentName.unflattenFromString(it) }
            .any {
                it.packageName == packageName && it.className == BrailleBackService::class.java.name
            }
    }

    private fun isIgnoringBatteryOptimizations(): Boolean {
        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return power.isIgnoringBatteryOptimizations(packageName)
    }

    /**
     * 小米官方文档明确：被 Powerkeeper 限制时 isBackgroundRestricted() 返回 true。
     * 这个 API 从 Android 9 起才有，而 minSdk 已是 29，不需要再判版本。
     */
    private fun isBackgroundRestricted(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        return am.isBackgroundRestricted
    }

    private fun areNotificationsEnabled(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
        return nm.areNotificationsEnabled()
    }

    private fun isBluetoothGranted(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }

    // -------------------------------------------------------------- 动作

    /** 直接弹系统的「忽略电池优化」确认框；系统不支持时退到电池优化列表。 */
    private fun openBatteryOptimizationRequest() {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:$packageName"))
        if (startSafely(direct)) return
        if (!startSafely(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) {
            openAppDetails()
        }
    }

    /**
     * 厂商后台管理页：先用已知组件，ROM 改了就退到应用详情页。
     * 这条链是本屏最容易失效的地方，所以每一层失败都必须有下一步，不能静默。
     */
    private fun openVendorBackgroundPage() {
        val component = parseComponent(autostartComponent(vendor))
        if (component != null) {
            val intent = Intent().setComponent(ComponentName(component.first, component.second))
            if (startSafely(intent)) return
        }
        if (!startSafely(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:$packageName"))
        )) {
            Toast.makeText(this, R.string.stability_no_settings_page, Toast.LENGTH_LONG).show()
        }
    }

    private fun openAppDetails() {
        startSafely(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:$packageName")))
    }

    /** 统一入口：`false` 表示这个 Intent 在这台机器上没有目标，调用方负责下一层。 */
    private fun startSafely(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        // 部分 ROM 对厂商组件做了权限隔离，按「不可用」处理。
        false
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(
                Manifest.permission.POST_NOTIFICATIONS
            )
        }
    }

    private fun requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    // -------------------------------------------------------------- 文案

    // 总览里的「服务没跑」也要分清是没启用还是被杀了：
    // 没启用的处理动作是去无障碍里打开，不是去改省电策略。
    private fun issueText(issue: StabilityIssue): String = when (issue) {
        StabilityIssue.SERVICE_NOT_RUNNING -> {
            val res = if (snapshot.serviceEnabled) {
                R.string.stability_service_not_running
            } else {
                R.string.stability_issue_service_not_enabled
            }
            getString(res)
        }
        StabilityIssue.BATTERY_OPTIMIZATION -> getString(R.string.stability_battery_optimized)
        StabilityIssue.BACKGROUND_RESTRICTED -> getString(R.string.stability_background_restricted)
        StabilityIssue.NOTIFICATION -> getString(R.string.stability_permission_denied_notification)
        StabilityIssue.BLUETOOTH -> getString(R.string.stability_permission_denied_bluetooth)
    }

    private fun buildLabels(): StabilityLabels = StabilityLabels(
        title = getString(R.string.stability_title),
        back = getString(R.string.compose_settings_back),
        sectionStatus = getString(R.string.stability_section_status),
        sectionOem = getString(R.string.stability_section_oem),
        off = getString(R.string.compose_settings_off),
        on = getString(R.string.compose_settings_on),
        serviceTitle = getString(R.string.stability_service_title),
        serviceRunning = getString(R.string.stability_service_running),
        serviceNotEnabled = getString(R.string.stability_service_not_enabled),
        serviceNotRunning = getString(R.string.stability_service_not_running),
        batteryTitle = getString(R.string.stability_battery_title),
        batteryUnrestricted = getString(R.string.stability_battery_unrestricted),
        batteryOptimized = getString(R.string.stability_battery_optimized),
        openBattery = getString(R.string.stability_open_battery),
        backgroundTitle = getString(R.string.stability_background_title),
        backgroundRestricted = getString(R.string.stability_background_restricted),
        backgroundOk = getString(R.string.stability_background_ok),
        openAutostart = getString(R.string.stability_open_autostart),
        notificationTitle = getString(R.string.stability_notification_title),
        granted = getString(R.string.stability_granted),
        denied = getString(R.string.stability_denied),
        requestPermission = getString(R.string.stability_request_permission),
        bluetoothTitle = getString(R.string.stability_bluetooth_title),
        summaryAllOk = getString(R.string.stability_summary_all_ok),
        summaryTitle = getString(R.string.stability_summary_title),
        vendorName = vendorDisplayName(),
        vendorSteps = vendorSteps(),
        android17Note = getString(R.string.stability_android17_note),
    )

    /**
     * 厂商名直接取 Build.MANUFACTURER：读屏用户先听到「小米」「HUAWEI」，
     * 才知道下面那几条步骤是给谁看的；取不到就退回通用说法。
     */
    private fun vendorDisplayName(): String {
        val name = Build.MANUFACTURER?.trim().orEmpty()
        return name.ifBlank { getString(R.string.stability_vendor_unknown) }
    }

    /** 厂商指引：按 ROM 给不同步骤，避开「照着网上过时教程找菜单」的坑。 */
    private fun vendorSteps(): List<String> {
        val all = resources.getStringArray(R.array.stability_vendor_steps).toList()
        val (start, end) = when (vendor) {
            OemVendor.XIAOMI -> 0 to 3
            OemVendor.HUAWEI -> 3 to 5
            OemVendor.OPPO, OemVendor.ONEPLUS -> 5 to 7
            OemVendor.VIVO -> 7 to 9
            OemVendor.SAMSUNG, OemVendor.MEIZU, OemVendor.GENERIC -> 9 to all.size
        }
        return all.subList(start, minOf(end, all.size))
    }
}
