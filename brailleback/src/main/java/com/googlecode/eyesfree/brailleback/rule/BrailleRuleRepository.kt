/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback.rule

import android.content.Context
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat

/**
 * Repository of braille rules, responsible for choosing the desired rule for
 * a node.
 */
class BrailleRuleRepository(context: Context) {

    private val mContext: Context = context

    fun find(node: AccessibilityNodeInfoCompat): BrailleRule? {
        for (rule in RULES) {
            if (rule.accept(mContext, node)) {
                return rule
            }
        }
        return null
    }

    companion object {
        private val RULES: List<BrailleRule> = listOf(
            VerticalContainerBrailleRule(),
            DefaultBrailleRule()
        )
    }
}
