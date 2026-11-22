/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.braille.service.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 蓝牙内部链路选择的纯逻辑测试。
 *
 * <p>三种取值的语义必须稳定，否则用户在设备页选「标准 HID」却还是被送去串口，
 * 或者选「经典串口」却先试了 HID，都表现为「选了没用」，而读屏用户没有任何
 * 办法从现象上判断是设置没生效还是设备不支持。
 */
class ConnectionTransportPolicyTest {

    @Test
    fun normalizeAcceptsLegacyAndKnownValues() {
        assertEquals(ConnectionTransportPolicy.AUTO, ConnectionTransportPolicy.normalize(null))
        assertEquals(ConnectionTransportPolicy.AUTO, ConnectionTransportPolicy.normalize(""))
        assertEquals(ConnectionTransportPolicy.AUTO, ConnectionTransportPolicy.normalize("  "))
        assertEquals(ConnectionTransportPolicy.AUTO, ConnectionTransportPolicy.normalize("whatever"))
        assertEquals(ConnectionTransportPolicy.AUTO, ConnectionTransportPolicy.normalize("AUTO"))
        assertEquals(ConnectionTransportPolicy.HID, ConnectionTransportPolicy.normalize("hid"))
        assertEquals(ConnectionTransportPolicy.HID, ConnectionTransportPolicy.normalize("BLE"))
        assertEquals(ConnectionTransportPolicy.HID, ConnectionTransportPolicy.normalize("blehid"))
        assertEquals(ConnectionTransportPolicy.SERIAL, ConnectionTransportPolicy.normalize("serial"))
        assertEquals(ConnectionTransportPolicy.SERIAL, ConnectionTransportPolicy.normalize("SPP"))
        assertEquals(ConnectionTransportPolicy.SERIAL, ConnectionTransportPolicy.normalize("rfcomm"))
    }

    @Test
    fun hidNeverSkipsHid() {
        assertFalse(ConnectionTransportPolicy.shouldSkipHid(ConnectionTransportPolicy.HID) { true })
        assertFalse(ConnectionTransportPolicy.shouldSkipHid(ConnectionTransportPolicy.HID) { false })
    }

    @Test
    fun serialAlwaysSkipsHid() {
        assertTrue(ConnectionTransportPolicy.shouldSkipHid(ConnectionTransportPolicy.SERIAL) { true })
        assertTrue(ConnectionTransportPolicy.shouldSkipHid(ConnectionTransportPolicy.SERIAL) { false })
    }

    @Test
    fun autoDefersToVendorDecision() {
        assertTrue(ConnectionTransportPolicy.shouldSkipHid(ConnectionTransportPolicy.AUTO) { true })
        assertFalse(ConnectionTransportPolicy.shouldSkipHid(ConnectionTransportPolicy.AUTO) { false })
        // 未知值走自动，不能因为存了个坏值就 stuck 在串口。
        assertTrue(ConnectionTransportPolicy.shouldSkipHid("garbage") { true })
    }
}
