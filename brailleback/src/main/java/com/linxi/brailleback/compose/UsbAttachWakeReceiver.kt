/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package com.linxi.brailleback.compose

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.util.Log
import com.googlecode.eyesfree.braille.service.display.EXTRA_USB_WAKE_REASON

/**
 * 清单注册的 USB 插拔唤醒接收器。
 *
 * <p>运行期接收器（DisplayService.mBroadcastReceiver）只有服务进程活着时才在；
 * 无障碍服务没开、界面也没绑定时主进程可能已被系统回收，插线不会拉起服务。
 * 这个清单接收器在进程已死时仍能被系统送达，随即把 DisplayService 拉起并带上
 * 触发原因，服务 onStartCommand 补一次显式重扫。
 *
 * <p>只做「拉起 + 触发重扫」，不在这里枚举设备或判断授权；判断逻辑仍归服务端
 * 既有 AUTO 路由与 USB 连接流程，避免两套口径漂移。
 */
class UsbAttachWakeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != UsbManager.ACTION_USB_DEVICE_ATTACHED
                && action != UsbManager.ACTION_USB_DEVICE_DETACHED) {
            return
        }
        Log.i(LOG_TAG, "UsbAttachWakeReceiver: $action")
        val service = Intent(ACTION_DISPLAY_SERVICE)
            .setPackage(context.packageName)
            .putExtra(EXTRA_USB_WAKE_REASON, action)
        try {
            context.startService(service)
        } catch (t: Throwable) {
            // 后台启动受限（MIUI 后台冻结 / 省电策略）时起不来是已知限制；
            // 不在这里反复重试，留给下次界面打开或插线再触发。
            Log.w(LOG_TAG, "UsbAttachWakeReceiver cannot start DisplayService", t)
        }
    }

    companion object {
        private const val LOG_TAG = "UsbAttachWakeReceiver"
        private const val ACTION_DISPLAY_SERVICE =
            "com.googlecode.eyesfree.braille.service.ACTION_DISPLAY_SERVICE"
    }
}
