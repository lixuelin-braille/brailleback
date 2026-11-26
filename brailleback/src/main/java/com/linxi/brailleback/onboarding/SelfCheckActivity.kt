/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 「设备与权限自检」宿主：系统查询在这一层做，判定与候选顺序交给纯引擎。
 * 逐个候选 try/catch，全失败回落应用信息页；仍跳不出去就把这一项记 UNKNOWN，
 * 不标成已处理。
 *
 * 服务成因只依赖平台信号（API 级别 + isEnabled 历史 everEnabled）；厂商分支只出现在
 * 自启动 Intent 候选与「加锁」步骤是否出现两处。
 */

package com.linxi.brailleback.onboarding

import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.googlecode.eyesfree.utils.SettingsStore
import com.linxi.brailleback.BrailleBackService
import com.linxi.brailleback.R
import com.linxi.brailleback.parseComponent

class SelfCheckActivity : AppCompatActivity() {

    private companion object {
        /** 本地历史：曾经读到过服务 enabled=true。 */
        private const val PREF_EVER_ENABLED = "selfcheck_ever_enabled"

        /** 受限设置路径连续失败次数：用于 Android 16+ 疑似 AAPM 判定。 */
        private const val PREF_RESTRICTED_FAILURES = "selfcheck_restricted_failures"
    }

    private val profile: OemProfile by lazy {
        resolveProfile(Build.MANUFACTURER, Build.BRAND)
    }

    /** 候选全失败的 target：刷新时由引擎改标 UNKNOWN。 */
    private val unresolved = mutableSetOf<SelfCheckTarget>()

    private var results by mutableStateOf(emptyList<SelfCheckResult>())

    /** 本次是否刚从无障碍设置页返回（用于受限设置成因判定）。 */
    private var returnedFromAccessibility = false

    /** 点了无障碍设置入口后置位；下一次 onResume 消费。 */
    private var awaitingAccessibilityReturn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        refresh()
        setContent {
            SelfCheckScreen(
                labels = buildLabels(),
                results = results,
                onOpenTarget = ::openTarget,
                onBack = { finish() },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // 消费「刚从无障碍设置返回」标记；用户从系统设置返回后必须重读状态。
        returnedFromAccessibility = awaitingAccessibilityReturn
        awaitingAccessibilityReturn = false
        refresh()
    }

    // -------------------------------------------------------------- 状态采集

    private fun refresh() {
        val serviceEnabled = readServiceEnabled()
        val prefs = SettingsStore.get(this)
        val everEnabled = prefs.getBoolean(PREF_EVER_ENABLED, false)
        val restrictedFailures = prefs.getInt(PREF_RESTRICTED_FAILURES, 0)

        if (serviceEnabled == true) {
            // 服务已开启：历史置位，受限失败计数清零。
            prefs.edit()
                .putBoolean(PREF_EVER_ENABLED, true)
                .putInt(PREF_RESTRICTED_FAILURES, 0)
                .apply()
        }

        val state = ServiceState(
            isEnabled = serviceEnabled,
            everEnabled = everEnabled,
            returnedFromAccessibility = returnedFromAccessibility,
            apiLevel = Build.VERSION.SDK_INT,
            restrictedFailures = restrictedFailures,
        )

        val checks = collectChecks(serviceEnabled)
        var evaluated = SelfCheckEngine.evaluate(profile, state, checks)

        // 从受限设置页回来仍未开启：累计一次失败，供下次 AAPM 疑似判定。
        val serviceResult = evaluated.firstOrNull { it.target == SelfCheckTarget.SERVICE }
        if (serviceResult?.cause == ServiceCause.RESTRICTED_SETTINGS &&
            returnedFromAccessibility && serviceEnabled == false
        ) {
            prefs.edit().putInt(PREF_RESTRICTED_FAILURES, restrictedFailures + 1).apply()
        }

        unresolved.forEach { target ->
            evaluated = evaluated.map {
                if (it.target == target) SelfCheckEngine.markUnresolved(it) else it
            }
        }
        results = evaluated
    }

    private fun collectChecks(serviceEnabled: Boolean?): Map<SelfCheckTarget, SelfCheckStatus> {
        val checks = LinkedHashMap<SelfCheckTarget, SelfCheckStatus>()

        checks[SelfCheckTarget.USB_PERMISSION] = readUsbPermission()

        if (profile.candidates.containsKey(SelfCheckTarget.AUTO_START)) {
            checks[SelfCheckTarget.AUTO_START] = readBackgroundRestricted()
        }

        checks[SelfCheckTarget.BATTERY] = readBatteryUnrestricted()

        if (profile.hasLockStep) {
            // 加锁是补救步骤：自启动有待处理，或服务曾被清理关闭时才提示，否则视为已到位。
            val cleaned = results.firstOrNull { it.target == SelfCheckTarget.SERVICE }
                ?.cause == ServiceCause.CLEANED_BY_SYSTEM
            val pending = checks[SelfCheckTarget.AUTO_START] == SelfCheckStatus.NEED_ACTION || cleaned
            checks[SelfCheckTarget.LOCK_STEP] =
                if (pending) SelfCheckStatus.NEED_ACTION else SelfCheckStatus.OK
        }
        if (profile.hasOtgStep) {
            checks[SelfCheckTarget.OTG_STEP] =
                if (checks[SelfCheckTarget.USB_PERMISSION] == SelfCheckStatus.NEED_ACTION) {
                    SelfCheckStatus.NEED_ACTION
                } else {
                    SelfCheckStatus.OK
                }
        }
        return checks
    }

    /** 无障碍服务是否在系统里启用；查询被厂商拒绝时返回 null（记 UNKNOWN，不猜）。 */
    private fun readServiceEnabled(): Boolean? = try {
        val listening = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        listening.split(':')
            .mapNotNull { ComponentName.unflattenFromString(it) }
            .any {
                it.packageName == packageName &&
                    it.className == BrailleBackService::class.java.name
            }
    } catch (e: SecurityException) {
        null
    }

    private fun readBatteryUnrestricted(): SelfCheckStatus {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return SelfCheckStatus.UNKNOWN
        return try {
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                SelfCheckStatus.OK
            } else {
                SelfCheckStatus.NEED_ACTION
            }
        } catch (e: Exception) {
            SelfCheckStatus.UNKNOWN
        }
    }

    /** 后台受限是自启动 / 保活的公开可判代理；读不到记 UNKNOWN。 */
    private fun readBackgroundRestricted(): SelfCheckStatus {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return SelfCheckStatus.UNKNOWN
        return try {
            if (am.isBackgroundRestricted) SelfCheckStatus.NEED_ACTION else SelfCheckStatus.OK
        } catch (e: Exception) {
            SelfCheckStatus.UNKNOWN
        }
    }

    /** USB 权限：没插设备读不到，记 UNKNOWN；有设备且任一未授权记 NEED_ACTION。 */
    private fun readUsbPermission(): SelfCheckStatus {
        val um = getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: return SelfCheckStatus.UNKNOWN
        val devices = um.deviceList ?: return SelfCheckStatus.UNKNOWN
        if (devices.isEmpty()) return SelfCheckStatus.UNKNOWN
        val anyGranted = devices.values.any { um.hasPermission(it) }
        return if (anyGranted) SelfCheckStatus.OK else SelfCheckStatus.NEED_ACTION
    }

    // -------------------------------------------------------------- 跳转

    private fun openTarget(target: SelfCheckTarget) {
        val result = results.firstOrNull { it.target == target } ?: return
        for (candidate in result.candidates) {
            if (startSafely(candidate)) {
                if (candidate.action == SelfCheckRoutes.ACCESSIBILITY_SETTINGS) {
                    awaitingAccessibilityReturn = true
                }
                return
            }
        }
        val fallback = result.fallback
        if (fallback != null && startSafely(fallback)) return
        // 全失败：记 UNKNOWN 并刷新页面，不标成已处理。
        unresolved.add(target)
        refresh()
        Toast.makeText(this, R.string.selfcheck_no_settings_page, Toast.LENGTH_LONG).show()
    }

    private fun startSafely(candidate: SelfCheckCandidate): Boolean {
        val intent = buildIntent(candidate) ?: return false
        return try {
            startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        } catch (e: SecurityException) {
            // 部分 ROM 对厂商组件做了权限隔离，按「不可用」处理，试下一个候选。
            false
        }
    }

    private fun buildIntent(candidate: SelfCheckCandidate): Intent? {
        val intent = if (candidate.component != null) {
            val (pkg, cls) = parseComponent(candidate.component) ?: return null
            Intent().setComponent(ComponentName(pkg, cls))
        } else {
            Intent(candidate.action)
        }
        if (candidate.dataPackage) {
            intent.setData(Uri.parse("package:$packageName"))
        }
        return intent
    }

    // -------------------------------------------------------------- 文案

    private fun buildLabels(): SelfCheckLabels {
        val titles = mapOf(
            SelfCheckTarget.SERVICE to getString(R.string.selfcheck_target_service),
            SelfCheckTarget.USB_PERMISSION to getString(R.string.selfcheck_target_usb),
            SelfCheckTarget.AUTO_START to getString(R.string.selfcheck_target_autostart),
            SelfCheckTarget.BATTERY to getString(R.string.selfcheck_target_battery),
            SelfCheckTarget.LOCK_STEP to getString(R.string.selfcheck_target_lock),
            SelfCheckTarget.OTG_STEP to getString(R.string.selfcheck_target_otg),
        )
        val bodies = results.associate { result ->
            result.target to bodyOf(result.target, result.cause)
        }
        return SelfCheckLabels(
            title = getString(R.string.selfcheck_title),
            back = getString(R.string.compose_settings_back),
            vendorName = vendorDisplayName(),
            summaryTitle = getString(R.string.selfcheck_summary_title),
            allOk = getString(R.string.selfcheck_all_ok),
            statusOk = getString(R.string.selfcheck_status_ok),
            statusNeedAction = getString(R.string.selfcheck_status_need_action),
            statusUnknown = getString(R.string.selfcheck_status_unknown),
            summaryPending = getString(R.string.selfcheck_summary_pending),
            openPage = getString(R.string.selfcheck_open_page),
            titles = titles,
            bodies = bodies,
        )
    }

    private fun vendorDisplayName(): String = when (profile.vendor) {
        SelfCheckVendor.XIAOMI -> getString(R.string.selfcheck_vendor_xiaomi)
        SelfCheckVendor.HUAWEI -> getString(R.string.selfcheck_vendor_huawei)
        SelfCheckVendor.HONOR -> getString(R.string.selfcheck_vendor_honor)
        SelfCheckVendor.OPPO -> getString(R.string.selfcheck_vendor_oppo)
        SelfCheckVendor.VIVO -> getString(R.string.selfcheck_vendor_vivo)
        SelfCheckVendor.SAMSUNG -> getString(R.string.selfcheck_vendor_samsung)
        SelfCheckVendor.GENERIC -> getString(R.string.selfcheck_vendor_generic)
    }

    private fun bodyOf(target: SelfCheckTarget, cause: ServiceCause): String = when (target) {
        SelfCheckTarget.SERVICE -> serviceBody(cause)
        SelfCheckTarget.USB_PERMISSION -> when (profile.vendor) {
            SelfCheckVendor.SAMSUNG -> getString(R.string.selfcheck_body_usb_samsung)
            else -> getString(R.string.selfcheck_body_usb)
        }
        SelfCheckTarget.AUTO_START -> autostartBody()
        SelfCheckTarget.BATTERY -> getString(R.string.selfcheck_body_battery)
        SelfCheckTarget.LOCK_STEP -> when (profile.vendor) {
            SelfCheckVendor.VIVO -> getString(R.string.selfcheck_body_lock_vivo)
            else -> getString(R.string.selfcheck_body_lock_common)
        }
        SelfCheckTarget.OTG_STEP -> getString(R.string.selfcheck_body_otg_xiaomi)
    }

    /** 服务成因文案是平台级公用文案，不进厂商 Profile。 */
    private fun serviceBody(cause: ServiceCause): String = when (cause) {
        ServiceCause.NEVER_ENABLED -> getString(R.string.selfcheck_body_service_never_enabled)
        ServiceCause.RESTRICTED_SETTINGS ->
            getString(R.string.selfcheck_body_service_restricted)
        ServiceCause.CLEANED_BY_SYSTEM ->
            getString(R.string.selfcheck_body_service_cleaned)
        ServiceCause.AAPM_SUSPECTED -> getString(R.string.selfcheck_body_service_aapm)
        ServiceCause.OK, ServiceCause.UNKNOWN -> ""
    }

    private fun autostartBody(): String = when (profile.vendor) {
        SelfCheckVendor.XIAOMI -> getString(R.string.selfcheck_body_autostart_xiaomi)
        SelfCheckVendor.HUAWEI -> getString(R.string.selfcheck_body_autostart_huawei)
        SelfCheckVendor.HONOR -> getString(R.string.selfcheck_body_autostart_honor)
        SelfCheckVendor.OPPO -> getString(R.string.selfcheck_body_autostart_oppo)
        SelfCheckVendor.VIVO -> getString(R.string.selfcheck_body_autostart_vivo)
        SelfCheckVendor.SAMSUNG, SelfCheckVendor.GENERIC ->
            getString(R.string.selfcheck_body_usb)
    }
}
