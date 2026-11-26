/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 侧载自检的纯判定层：输入厂商 Profile、服务状态信号与其余四项查询结果，
 * 输出按优先级排序的待办列表与修复动作。本文件不 import 任何 android.* API，
 * 系统查询全部在 Activity 侧完成，这里只锁判定规则与候选顺序，方便 JVM 单测。
 *
 * 判定口径：
 * - 服务成因只依赖两样：系统 API 级别 + isEnabled 的本地历史（everEnabled），
 *   禁止按厂商分支决定受限设置 / 被清理 / 高级保护文案；
 * - 读不到的状态一律 [SelfCheckStatus.UNKNOWN]，禁止猜成 OK；
 * - 全部候选跳不出去时，这一项回落应用信息页并记 UNKNOWN，不能标成已处理。
 */

package com.linxi.brailleback.onboarding

/** 检测档位。[UNKNOWN] 表示系统不允许查询或没有可判断依据，页面如实播报「读不到」。 */
enum class SelfCheckStatus { OK, NEED_ACTION, UNKNOWN }

/**
 * 无障碍服务「开不起来」的四种成因（平台级，与厂商无关）。
 *
 * [NEVER_ENABLED] 从未开启；[RESTRICTED_SETTINGS] Android 13+ 侧载被受限设置挡；
 * [CLEANED_BY_SYSTEM] 曾开过又被系统清理 / 重启后关闭；[AAPM_SUSPECTED] Android 16+
 * 受限路径反复失败，疑似高级保护模式——App 无法程序化检测 AAPM，这一档只给文案并记 UNKNOWN。
 */
enum class ServiceCause {
    OK,
    NEVER_ENABLED,
    RESTRICTED_SETTINGS,
    CLEANED_BY_SYSTEM,
    AAPM_SUSPECTED,
    UNKNOWN,
}

/**
 * 服务状态信号（Activity 采集后传入，引擎不读 Build / Settings）。
 *
 * @param isEnabled 当前是否在系统启用列表里；null 表示查询被拒，读不到。
 * @param everEnabled 本地历史：曾经读到过 enabled=true。
 * @param returnedFromAccessibility 本次是否刚从无障碍设置页返回。
 * @param apiLevel 系统 SDK 版本号。
 * @param restrictedFailures 受限设置路径反复失败的次数（用于 AAPM 疑似判定）。
 */
data class ServiceState(
    val isEnabled: Boolean?,
    val everEnabled: Boolean,
    val returnedFromAccessibility: Boolean,
    val apiLevel: Int,
    val restrictedFailures: Int = 0,
)

/** 一项自检的判定结果。[candidates] 按优先级排好，Activity 逐个 try/catch；[fallback] 是全失败后的兜底。 */
data class SelfCheckResult(
    val target: SelfCheckTarget,
    val status: SelfCheckStatus,
    val cause: ServiceCause = ServiceCause.UNKNOWN,
    val candidates: List<SelfCheckCandidate> = emptyList(),
    val fallback: SelfCheckCandidate? = null,
)

object SelfCheckEngine {

    /** Android 13：受限设置（restricted settings）开始生效。 */
    const val API_TIRAMISU = 33

    /** Android 16：高级保护模式（AAPM）相关限制引入的版本线。 */
    const val API_BAKLAVA = 36

    /** 受限路径连续失败达到此次数，才疑似 AAPM；单次失败不算。 */
    private const val AAPM_FAILURE_THRESHOLD = 2

    /** 播报 / 跳转优先级：服务 > USB > 自启动 > 电池 > 加锁 > OTG。 */
    private val PRIORITY = listOf(
        SelfCheckTarget.SERVICE,
        SelfCheckTarget.USB_PERMISSION,
        SelfCheckTarget.AUTO_START,
        SelfCheckTarget.BATTERY,
        SelfCheckTarget.LOCK_STEP,
        SelfCheckTarget.OTG_STEP,
    )

    /**
     * 按行为判定服务成因。判定顺序写死：
     * 已启用 → 曾开过（被清理）→ 高版本反复失败（疑似 AAPM）→ 刚从设置返回且 ≥13（受限设置）→ 从未开启。
     */
    fun decideServiceCause(state: ServiceState): ServiceCause {
        val enabled = state.isEnabled ?: return ServiceCause.UNKNOWN
        if (enabled) return ServiceCause.OK
        if (state.everEnabled) return ServiceCause.CLEANED_BY_SYSTEM
        if (state.apiLevel >= API_BAKLAVA &&
            state.restrictedFailures >= AAPM_FAILURE_THRESHOLD
        ) {
            return ServiceCause.AAPM_SUSPECTED
        }
        if (state.returnedFromAccessibility && state.apiLevel >= API_TIRAMISU) {
            return ServiceCause.RESTRICTED_SETTINGS
        }
        return ServiceCause.NEVER_ENABLED
    }

    fun evaluate(
        profile: OemProfile,
        state: ServiceState,
        checks: Map<SelfCheckTarget, SelfCheckStatus>,
    ): List<SelfCheckResult> {
        val cause = decideServiceCause(state)
        val applicable = applicableTargets(profile)
        return PRIORITY
            .filter { it in applicable }
            .map { target ->
                when (target) {
                    SelfCheckTarget.SERVICE -> serviceResult(cause, profile)
                    else -> nonServiceResult(profile, target, checks[target] ?: SelfCheckStatus.UNKNOWN)
                }
            }
    }

    /** 这家 ROM 上应该出现的 target：平台三项 + Profile 声明的厂商步骤。 */
    private fun applicableTargets(profile: OemProfile): Set<SelfCheckTarget> = buildSet {
        add(SelfCheckTarget.SERVICE)
        add(SelfCheckTarget.USB_PERMISSION)
        add(SelfCheckTarget.BATTERY)
        addAll(profile.candidates.keys)
        if (profile.hasLockStep) add(SelfCheckTarget.LOCK_STEP)
        if (profile.hasOtgStep) add(SelfCheckTarget.OTG_STEP)
    }

    private fun serviceResult(cause: ServiceCause, profile: OemProfile): SelfCheckResult =
        when (cause) {
            ServiceCause.OK -> SelfCheckResult(
                target = SelfCheckTarget.SERVICE,
                status = SelfCheckStatus.OK,
                cause = cause,
            )
            ServiceCause.NEVER_ENABLED -> SelfCheckResult(
                target = SelfCheckTarget.SERVICE,
                status = SelfCheckStatus.NEED_ACTION,
                cause = cause,
                candidates = listOf(SelfCheckCandidate(SelfCheckRoutes.ACCESSIBILITY_SETTINGS)),
                fallback = SelfCheckRoutes.appDetails(),
            )
            ServiceCause.RESTRICTED_SETTINGS -> SelfCheckResult(
                target = SelfCheckTarget.SERVICE,
                status = SelfCheckStatus.NEED_ACTION,
                cause = cause,
                candidates = listOf(SelfCheckRoutes.appDetails()),
            )
            ServiceCause.CLEANED_BY_SYSTEM -> {
                val candidates = profile.candidates[SelfCheckTarget.AUTO_START].orEmpty()
                SelfCheckResult(
                    target = SelfCheckTarget.SERVICE,
                    status = SelfCheckStatus.NEED_ACTION,
                    cause = cause,
                    candidates = candidates,
                    fallback = SelfCheckRoutes.appDetails(),
                )
            }
            // AAPM 无法程序化检测：只给文案，不带跳转候选，状态记 UNKNOWN。
            ServiceCause.AAPM_SUSPECTED, ServiceCause.UNKNOWN -> SelfCheckResult(
                target = SelfCheckTarget.SERVICE,
                status = SelfCheckStatus.UNKNOWN,
                cause = cause,
            )
        }

    private fun nonServiceResult(
        profile: OemProfile,
        target: SelfCheckTarget,
        status: SelfCheckStatus,
    ): SelfCheckResult {
        if (status != SelfCheckStatus.NEED_ACTION) {
            return SelfCheckResult(target, status)
        }
        val candidates = when (target) {
            SelfCheckTarget.USB_PERMISSION -> listOf(SelfCheckRoutes.appDetails())
            SelfCheckTarget.AUTO_START ->
                profile.candidates[SelfCheckTarget.AUTO_START].orEmpty()
            SelfCheckTarget.BATTERY -> SelfCheckRoutes.batteryCandidates()
            // 加锁 / OTG 只能手势或手动操作，系统不开放 Intent，候选为空，只给文字指引。
            SelfCheckTarget.LOCK_STEP, SelfCheckTarget.OTG_STEP -> emptyList()
            SelfCheckTarget.SERVICE -> emptyList()
        }
        val fallback = if (candidates.isNotEmpty()) SelfCheckRoutes.appDetails() else null
        return SelfCheckResult(target, status, candidates = candidates, fallback = fallback)
    }

    /**
     * 候选与兜底全部跳不出去时调用：这一项记 [SelfCheckStatus.UNKNOWN]，
     * 清空候选，不能标成已处理。
     */
    fun markUnresolved(result: SelfCheckResult): SelfCheckResult =
        result.copy(
            status = SelfCheckStatus.UNKNOWN,
            candidates = emptyList(),
            fallback = null,
        )
}
