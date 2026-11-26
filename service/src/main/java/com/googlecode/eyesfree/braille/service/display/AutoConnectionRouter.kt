/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.braille.service.display

/**
 * AUTO 档的纯路由：不 import 任何 `android.*` 类型，整段可在 JVM 上单测。
 *
 * 判定顺序（仅 AUTO 下介入）：
 * 1. 有已知盲文 USB 设备（VID/PID 命中 brltty 驱动表）→ USB 串口；
 * 2. 否则蓝牙开启且有已配对的已知点显 → 蓝牙；
 * 3. 两者皆无 → 保持当前档，进入周期性重扫，不静默失败。
 *
 * 手动档（协议点名蓝牙 / USB，或链路点名非 auto）优先级最高：选了手动就
 * 绝不自动切，路由直接委托给 [HidTransportRouting.resolve]，AUTO 判定不介入。
 */

/**
 * 一枚已挂载的 USB 设备，只用 VID/PID 与驱动码概括，不携带 `UsbDevice` 这类
 * Android 类型，边界因此可在纯 JVM 构造。
 */
data class UsbProbe(
    val vendorId: Int,
    val productId: Int,
    /** brltty USB 驱动表里匹配到的驱动码；空 = 不是已知盲文设备。 */
    val matchedDrivers: List<String>,
)

/** 路由结果对外的播报口径；具体文案由服务端按资源映射，这里只钉住语义。 */
enum class AutoAnnounce {
    /** 不播报。 */
    NONE,

    /** 检测到 USB 点显器，切到 USB。 */
    USB_DISPLAY_FOUND,

    /** 蓝牙 / USB 都没有：在等设备，进入重扫。 */
    WAITING_FOR_DISPLAY,

    /** HID 控制器不可用，已显式降级到 USB 串口。 */
    HID_DEGRADED_TO_SERIAL,
}

/** AUTO 路由的一次输入快照，全部是基本类型或本域数据类。 */
data class AutoRouteInput(
    val protocol: String,
    val transport: String,
    val usbDisplays: List<UsbProbe>,
    val bluetoothAdapterOn: Boolean,
    val bluetoothBondedDisplay: Boolean,
    val hidControllerAvailable: Boolean,
)

/** 一次路由的结论：起手链路 + 播报口径 + 是否保持当前档重扫 + 降级原因。 */
data class AutoRouteResult(
    val route: LinkRoute,
    val announce: AutoAnnounce,
    /** 两者皆无：保持当前档，由调用方走周期性重扫。 */
    val keepCurrentAndRescan: Boolean,
    /** 降级原因；未降级为 null。写进日志供以后真机复验。 */
    val degradedReason: String?,
)

object AutoConnectionRouter {

    /** HID 控制器不可用时落到串口的降级原因（日志口径，不面向播报）。 */
    const val DEGRADED_REASON_HID_UNAVAILABLE =
        "BrailleDisplayController unavailable; AUTO degraded to usb_serial"

    @JvmStatic
    fun route(input: AutoRouteInput): AutoRouteResult {
        val protocol = ConnectionProtocolPolicy.normalize(input.protocol)
        val transport = ConnectionTransportPolicy.normalize(input.transport)

        val manual = protocol != ConnectionProtocolPolicy.AUTO ||
                transport != ConnectionTransportPolicy.AUTO
        if (manual) {
            // 手动档优先：既有路由说了算，AUTO 的 USB→蓝牙顺序不介入。
            val route = HidTransportRouting.resolve(
                protocol,
                transport,
                input.usbDisplays.any { it.matchedDrivers.isNotEmpty() },
            )
            return AutoRouteResult(
                route,
                AutoAnnounce.NONE,
                keepCurrentAndRescan = false,
                degradedReason = null,
            )
        }

        val usbMatched = input.usbDisplays.any { it.matchedDrivers.isNotEmpty() }
        if (usbMatched) {
            if (!input.hidControllerAvailable) {
                // sdk=37 这类平台上 BrailleDisplayController 拿不到：AUTO 不判
                // HID 失败，显式降级到 USB 串口，原因进日志。
                return AutoRouteResult(
                    LinkRoute.USB_SERIAL,
                    AutoAnnounce.HID_DEGRADED_TO_SERIAL,
                    keepCurrentAndRescan = false,
                    degradedReason = DEGRADED_REASON_HID_UNAVAILABLE,
                )
            }
            return AutoRouteResult(
                LinkRoute.USB_SERIAL,
                AutoAnnounce.USB_DISPLAY_FOUND,
                keepCurrentAndRescan = false,
                degradedReason = null,
            )
        }

        if (input.bluetoothAdapterOn && input.bluetoothBondedDisplay) {
            return AutoRouteResult(
                LinkRoute.BLUETOOTH,
                AutoAnnounce.NONE,
                keepCurrentAndRescan = false,
                degradedReason = null,
            )
        }

        // 两者皆无：保持当前档，等设备插上 / 开机后由插拔广播与周期重扫再判。
        return AutoRouteResult(
            LinkRoute.BLUETOOTH,
            AutoAnnounce.WAITING_FOR_DISPLAY,
            keepCurrentAndRescan = true,
            degradedReason = null,
        )
    }
}
