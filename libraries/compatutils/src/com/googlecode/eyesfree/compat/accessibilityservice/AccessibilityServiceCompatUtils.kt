/*
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

package com.googlecode.eyesfree.compat.accessibilityservice

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.googlecode.eyesfree.compat.CompatUtils
import java.lang.reflect.Method

object AccessibilityServiceCompatUtils {
    // 反射找不到方法时，类初始化就失败并给出可读原因（原来是一个裸的 NPE）。
    private val METHOD_performGlobalAction: Method = checkNotNull(
        CompatUtils.getMethod(
            AccessibilityService::class.java, "performGlobalAction", Int::class.javaPrimitiveType
        )
    ) { "AccessibilityService.performGlobalAction(int) 在系统里不存在" }
    private val METHOD_getServiceInfo: Method = checkNotNull(
        CompatUtils.getMethod(AccessibilityService::class.java, "getServiceInfo")
    ) { "AccessibilityService.getServiceInfo() 在系统里不存在" }
    private val METHOD_getRootInActiveWindow: Method = checkNotNull(
        CompatUtils.getMethod(AccessibilityService::class.java, "getRootInActiveWindow")
    ) { "AccessibilityService.getRootInActiveWindow() 在系统里不存在" }

    @JvmStatic
    fun performGlobalAction(service: AccessibilityService, action: Int): Boolean {
        return CompatUtils.invoke(service, false, METHOD_performGlobalAction, action) as Boolean
    }

    @JvmStatic
    fun getServiceInfo(service: AccessibilityService): AccessibilityServiceInfo {
        return CompatUtils.invoke(service, null, METHOD_getServiceInfo) as AccessibilityServiceInfo
    }

    @JvmStatic
    fun getRootInActiveWindow(service: AccessibilityService): AccessibilityNodeInfoCompat? {
        val root = CompatUtils.invoke(service, null, METHOD_getRootInActiveWindow)
        return if (root != null) AccessibilityNodeInfoCompat(root) else null
    }
}
