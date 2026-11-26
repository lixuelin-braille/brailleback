/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.googlecode.eyesfree.braille.service.display

import android.content.Context
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import com.googlecode.eyesfree.utils.LogUtils

/**
 * 瞬时枚举捕获。
 *
 * <p>现象是「插上后弹窗几秒消失、此后 usbCount=0」，可能是枚举后数秒被内核
 * detach（无独立电源、全吃手机 OTG 供电，口限流更易触发）。本探针在「重试连接」
 * 与「插拔广播」两条路径上各开一个 10 秒 × 1 秒的 getDeviceList() 轮询窗口，
 * 逐秒记录 VID/PID 或 none，窗口结束写汇总，一次分清：
 * <ul>
 *   <li>枚举到又消失 → 物理掉线（线 / 转接头 / 供电 / 接点）；</li>
 *   <li>从头到尾 none → 压根没枚举到，再查代码与驱动表。</li>
 * </ul>
 *
 * <p>轮询跑在主线程 Handler 上（getDeviceList 是轻量枚举），可中断；
 * 不在失败路径上无限跑，窗口到点自动结束，也不阻塞连接链路。
 */
object UsbEnumerationProbe {

    /** 窗口内采样次数（0..WINDOW_TICKS），约 10 秒。 */
    private const val WINDOW_TICKS = 10
    private const val POLL_INTERVAL_MILLIS = 1_000L

    private val handler = Handler(Looper.getMainLooper())

    @Volatile
    private var activeRunnable: Runnable? = null

    /** 开一个新窗口；旧窗口先停掉，避免重叠采样。 */
    fun startWindow(context: Context, trigger: String) {
        stopWindow()
        val usbManager =
            context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
        val samples = mutableListOf<String>()
        val runnable = object : Runnable {
            var tick = 0

            override fun run() {
                if (tick > WINDOW_TICKS) {
                    finish(trigger, samples)
                    return
                }
                val devices = usbManager.deviceList?.values.orEmpty()
                val sample = if (devices.isEmpty()) {
                    "t=+${tick}s none"
                } else {
                    devices.joinToString(";") {
                        "t=+${tick}s vid=%04x pid=%04x"
                            .format(it.vendorId, it.productId)
                    }
                }
                samples.add(sample)
                LogUtils.i(this, "usbPoll $sample")
                tick++
                handler.postDelayed(this, POLL_INTERVAL_MILLIS)
            }
        }
        activeRunnable = runnable
        handler.post(runnable)
    }

    /** 中断当前窗口（服务断开 / 新一轮尝试前调用）。 */
    fun stopWindow() {
        activeRunnable?.let { handler.removeCallbacks(it) }
        activeRunnable = null
    }

    private fun finish(trigger: String, samples: List<String>) {
        activeRunnable = null
        ConnectionDiagnosticsState.lastPollSamples = samples.toList()
        LogUtils.i(this, "usbPoll summary trigger=$trigger samples=${samples.size}")
    }
}
