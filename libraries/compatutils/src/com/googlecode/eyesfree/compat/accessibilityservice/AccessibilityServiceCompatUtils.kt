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
    private val METHOD_performGlobalAction: Method = CompatUtils.getMethod(
        AccessibilityService::class.java, "performGlobalAction", Int::class.javaPrimitiveType
    )!!
    private val METHOD_getServiceInfo: Method = CompatUtils.getMethod(
        AccessibilityService::class.java, "getServiceInfo"
    )!!
    private val METHOD_getRootInActiveWindow: Method = CompatUtils.getMethod(
        AccessibilityService::class.java, "getRootInActiveWindow"
    )!!

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
