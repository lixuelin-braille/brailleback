/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback.compose

import com.googlecode.eyesfree.braille.display.DeviceListEntry
import com.googlecode.eyesfree.braille.service.display.ConnectionProtocolPolicy

/**
 * 「设备与连接」子屏的纯逻辑：不依赖 Android Context，便于 JVM 单测。
 *
 * 状态常量用 Int 传入而不直接引用 Display.STATE_*，是为了让这个类保持零 Android 依赖；
 * 调用方（Activity）负责传入框架常量。
 */
object DeviceConnectionLogic {

    /** SharedPreferences 键：必须与 res/xml/settings.xml 及迁移前完全一致。 */
    const val KEY_CONNECTION_STATUS = "connection_status"
    const val KEY_CONNECTION_PROTOCOL = "connection_protocol"
    const val KEY_AUTO_CONNECT = "auto_connect"

    const val STATE_CONNECTED = 1
    const val STATE_NOT_CONNECTED = 0

    /**
     * 连接状态的展示文本。进度描述由服务端本地化，优先显示；
     * 其余按状态映射到三句中文文案（未知状态一律按「不可用」处理）。
     */
    fun resolveStateText(
        state: Int,
        progress: String?,
        connected: String,
        notConnected: String,
        unavailable: String,
    ): String {
        progress?.let { return it }
        return when (state) {
            STATE_CONNECTED -> connected
            STATE_NOT_CONNECTED -> notConnected
            else -> unavailable
        }
    }

    /** 老版本的六种协议值合并成「蓝牙 / USB」两项：复用服务端策略，不复制第二份。 */
    fun normalizeProtocol(value: String?): String = ConnectionProtocolPolicy.normalize(value)

    fun isUsb(value: String?): Boolean =
        normalizeProtocol(value) == ConnectionProtocolPolicy.USB

    /**
     * 当前协议值对应的可读标签；值不在表中时退回第一项，保证读屏不会朗读空白。
     *
     * 依赖 [values] 与 [entries] 同序：错位会把一个选项的文案安到另一个选项上，
     * 读屏用户没有任何办法察觉，只会照着错的选项点。
     */
    fun labelForValue(values: List<String>, entries: List<String>, value: String?): String {
        val index = values.indexOf(normalizeProtocol(value))
        return entries.getOrElse(index) { entries.firstOrNull().orEmpty() }
    }

    /**
     * 候选设备的显示名。扫描结果可能暂时没有远端名称（权限或名称查询未回传），
     * 用占位名兜底，保证 TalkBack 至少能读出一行内容。
     */
    fun candidateDisplayName(name: String?, unknownName: String): String =
        name?.takeIf { it.isNotBlank() } ?: unknownName

    /**
     * 候选状态的可读标签。未配对状态必须写进朗读文本，不能只靠图标或颜色区分。
     */
    fun candidateStateLabel(
        state: Int,
        bondedLabel: String,
        unpairedLabel: String
    ): String =
        if (state == DeviceListEntry.STATE_BONDED) bondedLabel else unpairedLabel

    /**
     * 用户主动查找入口是否可用。缺 `BLUETOOTH_SCAN` 权限时不弹扫描，界面降级为
     * 提示用户去系统蓝牙设置里配对（运行时申请由 Activity 的权限发起器负责）。
     */
    fun canStartDiscovery(scanPermissionGranted: Boolean): Boolean = scanPermissionGranted
}
