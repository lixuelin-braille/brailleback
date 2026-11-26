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

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.googlecode.eyesfree.braille.service.display.UsbDisplayConnection

/**
 * 透明的 USB 权限请求代理 Activity。
 *
 * <p>背景：Android 12+ 上由后台 Service 直接调 [UsbManager.requestPermission]，
 * 宿主没有可见窗口时，系统（尤其 MIUI / HyperOS）会自行关掉授权对话框，表现为
 * 「弹窗几秒后消失、应用始终没连上」。本 Activity 是一个不可见的可见窗口：
 * 服务端把设备交给它，由它持有前台窗口再发起请求；授权结果仍走应用内
 * [UsbDisplayConnection.ACTION_USB_PERMISSION] 广播，回灌服务端连接流程。
 *
 * <p>本类不做连接判断、不改驱动状态：成功 / 失败的处理全部留在 UsbDisplayConnection。
 */
class UsbPermissionProxyActivity : AppCompatActivity() {

    private var resultReceiver: BroadcastReceiver? = null

    // 本进程内广播，33+ 必须显式带 NOT_EXPORTED；旧重载只在 SDK < 33 生效。
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(android.R.style.Theme_Translucent_NoTitleBar)
        super.onCreate(savedInstanceState)

        val device = intent?.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager?
        if (device == null || usbManager == null) {
            Log.w(LOG_TAG, "missing device or UsbManager, finishing")
            finish()
            return
        }

        resultReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, received: Intent?) {
                Log.i(LOG_TAG, "USB permission result received by proxy, finishing")
                finish()
            }
        }
        val filter = IntentFilter(UsbDisplayConnection.ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(resultReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(resultReceiver, filter)
        }

        val permissionIntent = Intent(UsbDisplayConnection.ACTION_USB_PERMISSION)
            .setPackage(packageName)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // 系统要往 PendingIntent 里填 EXTRA_PERMISSION_GRANTED，不可变会被静默丢掉。
            flags = flags or PendingIntent.FLAG_MUTABLE
        }
        val pending = PendingIntent.getBroadcast(this, 0, permissionIntent, flags)
        Log.i(LOG_TAG, "Proxy requesting USB permission for device id=${device.deviceId}")
        usbManager.requestPermission(device, pending)
    }

    override fun onDestroy() {
        resultReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (t: Throwable) {
                Log.w(LOG_TAG, "Unregister proxy permission receiver failed", t)
            }
        }
        resultReceiver = null
        super.onDestroy()
    }

    companion object {
        private const val LOG_TAG = "UsbPermissionProxy"
    }
}
