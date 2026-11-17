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

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.view.accessibility.AccessibilityManager
import com.googlecode.eyesfree.braille.service.R
import timber.log.Timber

/**
 * DisplayService 的连接保活：点显器连上以后把服务提升为 connectedDevice
 * 前台服务，让连接不再依赖「有没有界面绑定着」。
 *
 * 背景：DisplayService 是纯绑定服务。无障碍服务开启时 BrailleBackService
 * 常驻持有客户端，界面退出不会触发 onUnbind 里的断开；无障碍服务没开时，
 * 阅读器/设置页就是唯一客户端，退出界面即解绑，客户端数归零，连接被断开。
 *
 * 这里不再新加一个常驻客户端，而是把服务本身变成 started + foreground：
 * 已经 started 的服务不会因为最后一个绑定被移除而销毁，连接自然保留。
 * onStartCommand 里只做一件事——在 5 秒内补上 startForeground。
 */
internal const val ACTION_DISPLAY_KEEP_ALIVE =
        "com.googlecode.eyesfree.braille.service.ACTION_DISPLAY_KEEP_ALIVE"

private const val KEEP_ALIVE_CHANNEL_ID = "braille_display_keepalive"
private const val KEEP_ALIVE_NOTIFICATION_ID = 2

/**
 * 连接成功后调用。无障碍服务已经开启时它本身是前台服务且常驻持有客户端，
 * 这里直接跳过，避免出现第二条常驻通知。
 */
internal fun DisplayService.promoteDisplayForeground() {
    if (isBrailleAccessibilityServiceEnabled()) {
        return
    }
    val keepAliveIntent = Intent(this, DisplayService::class.java)
            .setAction(ACTION_DISPLAY_KEEP_ALIVE)
    try {
        startForegroundService(keepAliveIntent)
    } catch (e: IllegalStateException) {
        // 后台（例如自动重连）不允许从后台启动前台服务：连接照常工作，
        // 只是这一轮少了保活，不要因此打断连接流程。
        Timber.w(e, "Cannot promote display keep-alive foreground service")
        return
    }
    postDisplayKeepAliveNotification()
}

/** onStartCommand 里补 startForeground，满足 startForegroundService 的 5 秒约束。 */
internal fun DisplayService.postDisplayKeepAliveNotification() {
    createKeepAliveChannel()
    val builder = Notification.Builder(this, KEEP_ALIVE_CHANNEL_ID)
    val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
    if (launchIntent != null) {
        builder.setContentIntent(PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
    }
    builder.setContentTitle(getString(R.string.display_keepalive_title))
            .setContentText(getString(R.string.display_keepalive_text))
            .setSmallIcon(R.drawable.icon)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
    val notification = builder.build()
    try {
        startForeground(KEEP_ALIVE_NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        mDisplayKeepAliveActive = true
    } catch (e: SecurityException) {
        // Android 14+ 会校验 connectedDevice 的前置权限（如 BLUETOOTH_CONNECT）。
        // 权限被拒时放弃这一次提升，避免前台服务超时被系统判成 ANR。
        Timber.w(e, "Cannot start display keep-alive foreground")
        mDisplayKeepAliveActive = false
        stopSelf()
    }
}

/** 断开点显器或服务销毁时撤销前台状态与 started 状态。 */
@Suppress("DEPRECATION")
internal fun DisplayService.dropDisplayForeground() {
    mDisplayKeepAliveActive = false
    stopForeground(Service.STOP_FOREGROUND_REMOVE)
    stopSelf()
}

private fun DisplayService.createKeepAliveChannel() {
    val channel = NotificationChannel(
            KEEP_ALIVE_CHANNEL_ID,
            getString(R.string.display_keepalive_channel),
            NotificationManager.IMPORTANCE_LOW)
    channel.description = getString(R.string.display_keepalive_title)
    getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
}

/**
 * 本包的无障碍服务是否已启用。本包只注册了 BrailleBackService 一个无障碍服务，
 * 所以按包名判断即可，服务模块不需要反向依赖应用模块的类。
 */
private fun Context.isBrailleAccessibilityServiceEnabled(): Boolean {
    val manager = getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return false
    return manager.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo?.serviceInfo?.packageName == packageName }
}
