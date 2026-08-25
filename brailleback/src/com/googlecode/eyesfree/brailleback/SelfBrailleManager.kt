/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback

import androidx.core.view.accessibility.AccessibilityNodeInfoCompat

/**
 * Keeps track of the self-braille service and provides a means for querying
 * the self-brailled content.
 *
 * This implementation assumes that the [SelfBrailleService] is running in the
 * same process, and accesses a static reference to it.
 */
class SelfBrailleManager {

    private var mImeOpen = false

    fun contentForNode(node: AccessibilityNodeInfoCompat): DisplayManager.Content? {
        val service = getSelfBrailleService()
        val content = service?.contentForNode(node)
        if (content != null) {
            content.setContractionMode(
                if (mImeOpen) {
                    DisplayManager.Content.CONTRACT_DEFAULT
                } else {
                    DisplayManager.Content.CONTRACT_ALWAYS_ALLOW
                })
        }
        return content
    }

    fun hasContentForNode(node: AccessibilityNodeInfoCompat): Boolean {
        // TODO: Tweak SelfBrailleService so that we don't need to actually
        // compute the content -- we know that it exists before then.
        return contentForNode(node) != null
    }

    /**
     * Informs the self braille manager about the state of the IME input view.
     * This is used to decide on whether to allow contracted braille when
     * generating the braille content.
     */
    fun setImeOpen(open: Boolean) {
        mImeOpen = open
    }

    private fun getSelfBrailleService(): SelfBrailleService? {
        return SelfBrailleService.getActiveInstance()
    }
}
