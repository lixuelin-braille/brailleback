/*
 * Copyright (C) 2026 The BrailleBack authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.googlecode.eyesfree.brailleback

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Region
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat

/**
 * 触摸探索透传探针（实验性，临时验证代码）。
 *
 * <p>目的只有一个：回答一个此前只有文档推断、没有真机证据的问题—— {@code
 * setTouchExplorationPassthroughRegion()} 设下的区域，是只对调用者自己生效，还是对所有正在运行的读屏软件
 * 一起生效（display 级共享）。
 *
 * <p>这个问题决定盲文屏幕键盘能否"通吃各厂商读屏"：如果只对自己生效，那么把 TalkBack 的键盘搬进
 * BrailleBack 之后，林夕读屏／天坦／解说依旧会抢走盲文区的手势，方案不成立；如果对所有读屏生效，则
 * 只需 BrailleBack 一侧动手，不需要任何读屏配合。
 *
 * <p>使用方式（不改变键盘任何行为）：
 *
 * <pre>
 *   adb shell am broadcast -a com.googlecode.eyesfree.brailleback.PROBE_PASSTHROUGH \
 *       --es cmd on --ei ratio 100
 *   adb shell am broadcast -a com.googlecode.eyesfree.brailleback.PROBE_PASSTHROUGH --es cmd off
 * </pre>
 *
 * <p>{@code ratio} 为透传区域占屏幕高度的百分比（自屏幕顶部起算）。100 表示整屏，50 表示上半屏。
 * 探针在 {@link #AUTO_DISARM_MS} 毫秒后自动撤销，避免误设后设备长时间无法用读屏操作。
 */
object PassthroughProbe {

    private const val TAG = "BBCodeProbe"

    /** 外部控制用广播 action。 */
    const val ACTION = "com.googlecode.eyesfree.brailleback.PROBE_PASSTHROUGH"

    /** 自动撤销时限。探针属于实验代码，绝不能让设备停在"读屏摸不动"的状态。 */
    private const val AUTO_DISARM_MS = 180_000L

    /** setServiceInfo() 提交后到 flag 真正生效之间的等待时间。 */
    private const val FLAG_SETTLE_MS = 400L

    private var receiver: BroadcastReceiver? = null
    private var autoDisarm: Runnable? = null
    private var armed = false
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 在无障碍服务连接成功后调用，注册广播监听。重复调用安全。 */
    @JvmStatic
    fun install(service: AccessibilityService) {
        if (receiver != null) {
            return
        }
        val r =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent == null) {
                        return
                    }
                    val cmd = intent.getStringExtra("cmd") ?: "status"
                    val ratio = intent.getIntExtra("ratio", 100)
                    when (cmd) {
                        "on" -> arm(service, ratio)
                        "off" -> disarm(service, "broadcast")
                        else -> reportStatus(service)
                    }
                }
            }
        try {
            ContextCompat.registerReceiver(
                service,
                r,
                IntentFilter(ACTION),
                ContextCompat.RECEIVER_EXPORTED,
            )
            receiver = r
            Log.i(TAG, "probe installed, listening on $ACTION")
        } catch (e: RuntimeException) {
            Log.e(TAG, "probe install failed", e)
        }
    }

    /** 在服务销毁时调用。 */
    @JvmStatic
    fun uninstall(service: AccessibilityService) {
        val r = receiver ?: return
        try {
            service.unregisterReceiver(r)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "probe uninstall: " + e)
        }
        receiver = null
        if (armed) {
            disarm(service, "uninstall")
        }
    }

    /** 打开透传：置 flag，随后按 ratio 设定区域。 */
    private fun arm(service: AccessibilityService, ratio: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.e(TAG, "ABORT: passthrough region API requires API 30+, device is "
                + Build.VERSION.SDK_INT)
            return
        }
        val clamped = ratio.coerceIn(1, 100)
        Log.i(TAG, "arm requested ratio=$clamped")

        // 第一步：请求触摸探索。官方文档明确：该 flag 未置位时 passthrough region 完全不生效。
        val desiredFlags = AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
        val info = service.serviceInfo
        if (info == null) {
            Log.e(TAG, "ABORT: getServiceInfo() returned null")
            return
        }
        val hadFlag = (info.flags and desiredFlags) != 0
        info.flags = info.flags or desiredFlags
        service.serviceInfo = info
        Log.i(TAG, "flag step: hadRequestTouchExploration=$hadFlag, setServiceInfo() submitted")

        armed = true
        scheduleAutoDisarm(service)

        // 第二步：等 flag 落地后再设区域，否则可能被系统忽略。
        mainHandler.postDelayed(
            {
                val region = buildRegion(service, clamped)
                applyRegion(service, region, clamped)
            },
            FLAG_SETTLE_MS,
        )
    }

    /** 关闭透传：先清区域，再撤 flag。顺序与 arm 相反，避免中间态被当作有效区域。 */
    private fun disarm(service: AccessibilityService, reason: String) {
        autoDisarm?.let { mainHandler.removeCallbacks(it) }
        autoDisarm = null
        armed = false

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return
        }
        try {
            service.setTouchExplorationPassthroughRegion(
                Display.DEFAULT_DISPLAY,
                Region(),
            )
            Log.i(TAG, "region cleared (reason=$reason)")
        } catch (e: RuntimeException) {
            Log.e(TAG, "clear region failed", e)
        }

        val info = service.serviceInfo
        if (info != null) {
            info.flags =
                info.flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE.inv()
            service.serviceInfo = info
            Log.i(TAG, "flag cleared, systemTouchExploration=" + systemTouchExploration(service))
        }
    }

    private fun buildRegion(service: AccessibilityService, ratioPercent: Int): Region {
        val metrics = service.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val covered = (height * ratioPercent / 100).coerceAtLeast(1)
        return Region(0, 0, width, covered)
    }

    private fun applyRegion(
        service: AccessibilityService,
        region: Region,
        ratioPercent: Int,
    ) {
        try {
            service.setTouchExplorationPassthroughRegion(
                Display.DEFAULT_DISPLAY,
                region,
            )
            val info = service.serviceInfo
            Log.i(
                TAG,
                "PASSTHROUGH SET ratio=$ratioPercent region=$region"
                    + " systemTouchExploration=" + systemTouchExploration(service)
                    + " sdk=" + Build.VERSION.SDK_INT,
            )
        } catch (e: RuntimeException) {
            Log.e(TAG, "apply region failed", e)
        }
    }

    private fun scheduleAutoDisarm(service: AccessibilityService) {
        autoDisarm?.let { mainHandler.removeCallbacks(it) }
        val r = Runnable {
            Log.i(TAG, "auto disarm fired after ${AUTO_DISARM_MS}ms")
            disarm(service, "auto-timeout")
        }
        autoDisarm = r
        mainHandler.postDelayed(r, AUTO_DISARM_MS)
    }

    /** 系统层面触摸探索是否处于开启状态（所有服务的请求按“或”聚合的结果）。 */
    private fun systemTouchExploration(service: AccessibilityService): Boolean {
        return try {
            val am = service.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            am.isTouchExplorationEnabled
        } catch (e: RuntimeException) {
            Log.w(TAG, "cannot read touch exploration state: " + e)
            false
        }
    }

    private fun reportStatus(service: AccessibilityService) {
        val info = service.serviceInfo
        Log.i(
            TAG,
            "STATUS armed=$armed sdk=" + Build.VERSION.SDK_INT
                + " systemTouchExploration=" + systemTouchExploration(service)
                + " flags=" + (info?.flags ?: -1),
        )
    }
}
