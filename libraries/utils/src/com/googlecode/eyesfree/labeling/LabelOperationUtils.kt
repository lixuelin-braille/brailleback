/*
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

package com.googlecode.eyesfree.labeling

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.googlecode.eyesfree.utils.LogUtils

/**
 * Convenience utilities and constants for invoking operations related to label
 * management.
 */
object LabelOperationUtils {
    // Intent values for launching LabelDialogActivity
    const val ACTION_ADD_LABEL = "com.google.android.marvin.talkback.labeling.ADD_LABEL"
    const val ACTION_EDIT_LABEL = "com.google.android.marvin.talkback.labeling.EDIT_LABEL"
    const val ACTION_REMOVE_LABEL = "com.google.android.marvin.talkback.labeling.REMOVE_LABEL"
    const val EXTRA_STRING_RESOURCE_NAME = "EXTRA_STRING_RESOURCE_NAME"
    const val EXTRA_RECT_VIEW_BOUNDS = "EXTRA_RECT_VIEW_BOUNDS"
    const val EXTRA_LONG_LABEL_ID = "EXTRA_LONG_LABEL_ID"

    // Intent values for broadcasts to CustomLabelManager
    const val ACTION_REFRESH_LABEL_CACHE =
        "com.google.android.marvin.talkback.labeling.REFRESH_LABEL_CACHE"
    const val EXTRA_STRING_ARRAY_PACKAGES = "EXTRA_STRING_ARRAY_PACKAGES"

    @JvmStatic
    fun startActivityAddLabelForNode(context: Context?, node: AccessibilityNodeInfo?): Boolean {
        if (context == null || node == null) {
            return false
        }

        val addIntent = Intent(ACTION_ADD_LABEL)
        val extras = Bundle()
        extras.putString(EXTRA_STRING_RESOURCE_NAME, node.viewIdResourceName)
        addIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        addIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        addIntent.putExtras(extras)

        return try {
            context.startActivity(addIntent)
            true
        } catch (e: Exception) {
            LogUtils.log(LabelOperationUtils::class.java, Log.ERROR, e, "启动添加标签界面失败：%s", ACTION_ADD_LABEL)
            false
        }
    }

    @JvmStatic
    fun startActivityEditLabel(context: Context?, label: Label?): Boolean {
        if (context == null || label == null) {
            return false
        }

        val editIntent = Intent(ACTION_EDIT_LABEL)
        val extras = Bundle()
        extras.putLong(EXTRA_LONG_LABEL_ID, label.id)
        editIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        editIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        editIntent.putExtras(extras)

        return try {
            context.startActivity(editIntent)
            true
        } catch (e: Exception) {
            LogUtils.log(LabelOperationUtils::class.java, Log.ERROR, e, "启动编辑标签界面失败：%s", ACTION_EDIT_LABEL)
            false
        }
    }

    @JvmStatic
    fun startActivityRemoveLabel(context: Context?, label: Label?): Boolean {
        if (context == null || label == null) {
            return false
        }

        val removeIntent = Intent(ACTION_REMOVE_LABEL)
        val extras = Bundle()
        extras.putLong(EXTRA_LONG_LABEL_ID, label.id)
        removeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        removeIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        removeIntent.putExtras(extras)

        return try {
            context.startActivity(removeIntent)
            true
        } catch (e: Exception) {
            LogUtils.log(LabelOperationUtils::class.java, Log.ERROR, e, "启动删除标签界面失败：%s", ACTION_REMOVE_LABEL)
            false
        }
    }
}
