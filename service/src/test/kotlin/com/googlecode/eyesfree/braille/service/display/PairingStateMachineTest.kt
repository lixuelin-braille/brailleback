/*
 * Copyright (C) 2026 The BrailleBack Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.googlecode.eyesfree.braille.service.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配对状态机的纯行为测试：覆盖 idle→bonding→bonded/failed/timeout 迁移、
 * 失败可重试、重复点击忽略与超时兜底，不起真机、不碰蓝牙框架类。
 */
class PairingStateMachineTest {

    private val address = "AA:BB:CC:00:11:22"
    private val timeoutMillis = 60_000L

    private fun machine(now: Long = 0L) =
        PairingStateMachine(clock = { now })

    @Test
    fun startsIdleAndNotRetryable() {
        val m = machine()
        assertEquals(PairingState.IDLE, m.state)
        assertFalse(m.retryable)
    }

    @Test
    fun startBondMovesToBonding() {
        val m = machine()
        assertTrue(m.startBond(address, timeoutMillis))
        assertEquals(PairingState.BONDING, m.state)
        assertEquals(address.uppercase(), m.address)
        assertFalse(m.retryable)
    }

    @Test
    fun bondedBroadcastMovesToBonded() {
        val m = machine()
        m.startBond(address, timeoutMillis)
        m.onBondState(BondState.BONDED)
        assertEquals(PairingState.BONDED, m.state)
        assertEquals(PairingFailureReason.NONE, m.reason)
        assertFalse(m.retryable)
    }

    @Test
    fun bondNoneWhileBondingIsRejectedFailure() {
        val m = machine()
        m.startBond(address, timeoutMillis)
        m.onBondState(BondState.NONE)
        assertEquals(PairingState.FAILED, m.state)
        assertEquals(PairingFailureReason.REJECTED, m.reason)
        assertTrue(m.retryable)
    }

    @Test
    fun bondNoneWhileIdleDoesNotReject() {
        val m = machine()
        m.onBondState(BondState.NONE)
        assertEquals(PairingState.IDLE, m.state)
    }

    @Test
    fun timesOutOnlyAfterDeadline() {
        val m = machine(now = 10_000L)
        m.startBond(address, timeoutMillis)
        m.onTimeout(10_000L + timeoutMillis - 1)
        assertEquals(PairingState.BONDING, m.state)
        m.onTimeout(10_000L + timeoutMillis)
        assertEquals(PairingState.TIMEOUT, m.state)
        assertEquals(PairingFailureReason.TIMEOUT, m.reason)
        assertTrue(m.retryable)
    }

    @Test
    fun createBondFalseGoesToFailedCouldNotStart() {
        val m = machine()
        m.startBond(address, timeoutMillis)
        m.onCreateBondResult(false, PairingFailureReason.COULD_NOT_START)
        assertEquals(PairingState.FAILED, m.state)
        assertEquals(PairingFailureReason.COULD_NOT_START, m.reason)
        assertTrue(m.retryable)
    }

    @Test
    fun failedStateIsRetryableAndCanRestartBonding() {
        val m = machine()
        m.startBond(address, timeoutMillis)
        m.onBondState(BondState.NONE)
        assertTrue(m.retryable)
        assertTrue(m.startBond(address, timeoutMillis))
        assertEquals(PairingState.BONDING, m.state)
    }

    @Test
    fun duplicateSameAddressTapIsIgnored() {
        val m = machine()
        m.startBond(address, timeoutMillis)
        // 同一地址重复发起：返回 true（仍在流程内），状态不被打断。
        assertTrue(m.startBond(address, timeoutMillis))
        assertEquals(PairingState.BONDING, m.state)
    }

    @Test
    fun resetReturnsToIdle() {
        val m = machine()
        m.startBond(address, timeoutMillis)
        m.onBondState(BondState.NONE)
        m.reset()
        assertEquals(PairingState.IDLE, m.state)
        assertEquals(PairingFailureReason.NONE, m.reason)
        assertEquals(null, m.address)
    }
}
