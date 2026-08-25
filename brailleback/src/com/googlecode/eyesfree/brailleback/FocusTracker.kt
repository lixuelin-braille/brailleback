/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.googlecode.eyesfree.braille.display.Display
import com.googlecode.eyesfree.compat.accessibilityservice.AccessibilityServiceCompatUtils
import com.googlecode.eyesfree.utils.AccessibilityNodeInfoUtils

/**
 * Tracks various events and updates accessibility focus.
 *
 * Currently, this class moves accessibility focus to the node that has input
 * focus when the screen is turned on or a display gets connected.
 */
class FocusTracker(private val mAccessibilityService: AccessibilityService) :
    BroadcastReceiver(), Display.OnConnectionStateChangeListener {

    private var mDisplayConnected = false

    fun register() {
        val filter = IntentFilter()
        filter.addAction(Intent.ACTION_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 33) {
            mAccessibilityService.registerReceiver(this, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            mAccessibilityService.registerReceiver(this, filter)
        }
    }

    fun unregister() {
        mAccessibilityService.unregisterReceiver(this)
    }

    override fun onReceive(context: Context?, intent: Intent) {
        val action = intent.action
        if (Intent.ACTION_SCREEN_ON == action && mDisplayConnected) {
            setFocusFromInput()
        }
    }

    override fun onConnectionStateChanged(state: Int) {
        mDisplayConnected = state == Display.STATE_CONNECTED
        if (mDisplayConnected) {
            setFocusFromInput()
        }
    }

    /**
     * Sets the accessibility focus to the node that currently has input focus,
     * if accessibility focus is not already set in the currently focused window.
     */
    private fun setFocusFromInput() {
        val root = AccessibilityServiceCompatUtils.getRootInActiveWindow(mAccessibilityService)
        if (root == null) {
            return
        }
        var accessibilityFocused: AccessibilityNodeInfoCompat? = null
        var inputFocused: AccessibilityNodeInfoCompat? = null
        try {
            accessibilityFocused = root.findFocus(AccessibilityNodeInfoCompat.FOCUS_ACCESSIBILITY)
            if (accessibilityFocused != null) {
                return
            }
            inputFocused = root.findFocus(AccessibilityNodeInfoCompat.FOCUS_INPUT)
            if (inputFocused == null
                || !AccessibilityNodeInfoUtils.shouldFocusNode(mAccessibilityService, inputFocused)) {
                return
            }
            inputFocused.performAction(AccessibilityNodeInfoCompat.ACTION_ACCESSIBILITY_FOCUS)
        } finally {
            AccessibilityNodeInfoUtils.recycleNodes(root, inputFocused, accessibilityFocused)
        }
    }
}
