/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback

import android.view.accessibility.AccessibilityEvent
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.googlecode.eyesfree.braille.display.BrailleInputEvent

/**
 * Keeps track of the current navigation mode and dispatches events to the
 * navigation modes depending on which one is active.
 */
class ModeSwitcher(vararg modes: NavigationMode) : NavigationMode {

    private val mModes: Array<out NavigationMode> = modes
    private var mModeIndex = 0
    private var mOverrideMode: NavigationMode? = null
    private var mActive = false

    fun getCurrentMode(): NavigationMode {
        return mOverrideMode ?: mModes[mModeIndex]
    }

    fun switchMode() {
        if (mActive) {
            getCurrentMode().onDeactivate()
        }
        mModeIndex = (mModeIndex + 1) % mModes.size
        mOverrideMode = null
        if (mActive) {
            getCurrentMode().onActivate()
        }
    }

    /**
     * Sets a mode, typically not one of the modes that were supplied during
     * constructions, that overrides the current mode.
     *
     * `newOverrideMode` is the overriding mode, or `null` to go back to the
     * mode that was active before overriding it.
     */
    fun overrideMode(newOverrideMode: NavigationMode?) {
        val oldMode = getCurrentMode()
        val newMode = newOverrideMode ?: mModes[mModeIndex]
        if (newMode == oldMode) {
            return
        }
        if (mActive) {
            oldMode.onDeactivate()
        }
        mOverrideMode = newOverrideMode
        if (mActive) {
            newMode.onActivate()
        }
    }

    override fun onActivate() {
        mActive = true
        getCurrentMode().onActivate()
    }

    override fun onDeactivate() {
        getCurrentMode().onDeactivate()
        mActive = false
    }

    override fun onPanLeftOverflow(content: Content): Boolean {
        var ret = getCurrentMode().onPanLeftOverflow(content)
        if (!ret && mOverrideMode != null) {
            ret = mModes[mModeIndex].onPanLeftOverflow(content)
        }
        return ret
    }

    override fun onPanRightOverflow(content: Content): Boolean {
        var ret = getCurrentMode().onPanRightOverflow(content)
        if (!ret && mOverrideMode != null) {
            ret = mModes[mModeIndex].onPanRightOverflow(content)
        }
        return ret
    }

    override fun onMappedInputEvent(
        event: BrailleInputEvent, content: Content): Boolean {
        var ret = getCurrentMode().onMappedInputEvent(event, content)
        if (!ret && mOverrideMode != null) {
            ret = mModes[mModeIndex].onMappedInputEvent(event, content)
        }
        return ret
    }

    override fun onObserveAccessibilityEvent(event: AccessibilityEvent) {
        for (mode in mModes) {
            mode.onObserveAccessibilityEvent(event)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent): Boolean {
        // 先取到局部变量，Kotlin 才能在下面做非空判断（属性每次读都可能变）。
        val overrideMode = mOverrideMode
        return if (overrideMode == null || !overrideMode.onAccessibilityEvent(event)) {
            mModes[mModeIndex].onAccessibilityEvent(event)
        } else {
            true
        }
    }

    override fun onInvalidateAccessibilityNode(node: AccessibilityNodeInfoCompat) {
        getCurrentMode().onInvalidateAccessibilityNode(node)
    }
}
