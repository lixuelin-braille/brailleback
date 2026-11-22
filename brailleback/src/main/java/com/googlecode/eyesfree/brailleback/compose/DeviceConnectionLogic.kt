/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback.compose

import com.googlecode.eyesfree.braille.display.DeviceListEntry
import com.googlecode.eyesfree.braille.service.display.ConnectionProtocolPolicy
import com.googlecode.eyesfree.braille.service.display.ConnectionTransportPolicy

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

    /**
     * 「断开连接」这一行的稳定列表 key。
     *
     * <p>它是纯界面键，不写进任何偏好文件，因此不与 res/xml/settings.xml
     * 对齐；单独放一个前缀以免和上面三个真实偏好键混淆。
     */
    const val KEY_DISCONNECT = "ui_disconnect"

    /** 「连接方式」行的稳定列表 key；真实偏好键见 [ConnectionTransportPolicy.PREFERENCE_KEY]。 */
    const val KEY_TRANSPORT = "ui_transport"

    const val STATE_CONNECTED = 1
    const val STATE_NOT_CONNECTED = 0

    /** 客户端刚绑定、还没收到首个状态时的取值（与 Display.STATE_UNKNOWN 同值）。 */
    const val STATE_UNKNOWN = -1

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
     * 候选状态的可读标签。未配对 / 配对中 / 失败 / 超时都必须写进朗读文本，
     * 不能只靠图标或颜色区分。
     */
    fun candidateStateLabel(
        state: Int,
        bondedLabel: String,
        unpairedLabel: String,
        bondingLabel: String,
        failedLabel: String,
        timeoutLabel: String
    ): String = when (state) {
        DeviceListEntry.STATE_BONDED -> bondedLabel
        DeviceListEntry.STATE_BONDING -> bondingLabel
        DeviceListEntry.STATE_FAILED -> failedLabel
        DeviceListEntry.STATE_TIMEOUT -> timeoutLabel
        else -> unpairedLabel
    }

    /**
     * 候选行的动作标签：已配对走连接；配对中禁用重复点击；未配对发起配对；
     * 失败 / 超时回到「重试配对」。
     */
    fun candidateActionLabel(
        state: Int,
        connectLabel: String,
        pairLabel: String,
        retryLabel: String
    ): String = when (state) {
        DeviceListEntry.STATE_BONDED -> connectLabel
        DeviceListEntry.STATE_BONDING -> ""
        DeviceListEntry.STATE_FAILED, DeviceListEntry.STATE_TIMEOUT -> retryLabel
        else -> pairLabel
    }

    /**
     * 用户主动查找入口是否可用。缺 `BLUETOOTH_SCAN` 权限时不弹扫描，界面降级为
     * 提示用户去系统蓝牙设置里配对（运行时申请由 Activity 的权限发起器负责）。
     */
    fun canStartDiscovery(scanPermissionGranted: Boolean): Boolean = scanPermissionGranted

    /**
     * 双击一行候选时是否要真的向服务端下发请求。
     * <p>已配对条目也走同一个配对入口：服务端对已配对地址的处理就是「把待连
     * 地址设为这一台，然后走既有连接路径」，所以点哪一台就连哪一台——否则有
     * 两台已配对点显器时，只能连到上次用的那台。
     *
     * <p>配对进行中的条目忽略点击：服务端也会忽略，但客户端不该白发一次请求，
     * 读屏用户连点两下时会以为「第二下把配对取消了」。
     */
    fun shouldSendCandidateClick(state: Int): Boolean =
        state != DeviceListEntry.STATE_BONDING

    /**
     * 「断开连接」这一行是否可用：只有真连上才可点。未连接时行还在（读屏能听到
     * 「当前没有连接的点显器」），但不能触发断开，避免连点两下把刚连上的设备断掉。
     */
    fun shouldOfferDisconnect(state: Int): Boolean = state == STATE_CONNECTED

    /**
     * 连接方式（蓝牙内部链路）的标准化：复用服务端 `ConnectionTransportPolicy`，
     * 不认识的值一律回落到「自动」，保证读屏不会停在空白项上。
     */
    fun normalizeTransport(value: String?): String = ConnectionTransportPolicy.normalize(value)

    /**
     * 「连接方式」行下面那句说明。五条取值各有一句：自动要讲清「无界系列会被
     * 送去串口」；蓝牙 HID 要讲清「不回落串口」；USB HID 要讲清「需要插线并授权」；
     * 经典 HID 要讲清「只能由系统接管、本 App 写不进点显器」；串口要讲清
     * 「按键由 brltty 解析」。
     */
    fun transportSummary(
        value: String?,
        auto: String,
        hidBle: String,
        hidUsb: String,
        hidClassic: String,
        serial: String,
    ): String = when (normalizeTransport(value)) {
        ConnectionTransportPolicy.HID_BLE -> hidBle
        ConnectionTransportPolicy.HID_USB -> hidUsb
        ConnectionTransportPolicy.HID_CLASSIC -> hidClassic
        ConnectionTransportPolicy.SERIAL -> serial
        else -> auto
    }

    /**
     * 选「标准 HID」时要不要提醒这台设备没有按键通道：只有在自动判断认为该设备
     * 属于「HID 不推按键」那一类时才算。提醒不是拦阻，用户仍可选。
     */
    fun transportHidNeedsKeyWarning(value: String?, legacyWouldSkipHid: Boolean): Boolean =
        normalizeTransport(value) == ConnectionTransportPolicy.HID_BLE && legacyWouldSkipHid
}
