/*
 * Copyright (C) Google Inc.
 * Licensed under the Apache License, Version 2.0
 */

package com.googlecode.eyesfree.compat.app

import android.app.AppOpsManager
import com.googlecode.eyesfree.compat.CompatUtils
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Compatibility utilities for interacting with [AppOpsManager].
 */
object AppOpsManagerCompatUtils {
    private val CLASS: Class<*> = AppOpsManager::class.java

    private val METHOD_checkOpNoThrow: Method? = CompatUtils.getMethod(
        CLASS, "checkOpNoThrow",
        Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java)

    private val FIELD_OP_PROJECT_MEDIA: Field? = CompatUtils.getField(CLASS, "OP_PROJECT_MEDIA")

    private const val OP_CODE_UNRESOLVED = -1

    @JvmField
    val OP_PROJECT_MEDIA: Int =
        CompatUtils.getFieldValue(null, OP_CODE_UNRESOLVED, FIELD_OP_PROJECT_MEDIA) as Int

    /**
     * Like [AppOpsManager.checkOp] but instead of throwing a [SecurityException]
     * it returns [AppOpsManager.MODE_ERRORED].
     *
     * @param manager The [AppOpsManager] instance to invoke
     * @param op The operation to check. One of the OP_* constants
     * @param uid The user id of the application attempting to perform the operation
     * @param pName The package name of the application attempting to perform the operation
     * @return [AppOpsManager.MODE_ALLOWED] if the operation is allowed,
     *         [AppOpsManager.MODE_IGNORED] if it is not allowed and should be
     *         silently ignored, [AppOpsManager.MODE_ERRORED] if a
     *         [SecurityException] would have been thrown via [AppOpsManager.checkOp],
     *         or `-1` if another error occurs
     */
    @JvmStatic
    fun checkOpNoThrow(manager: AppOpsManager, op: Int, uid: Int, pName: String): Int {
        return CompatUtils.invoke(manager, -1, METHOD_checkOpNoThrow, op, uid, pName) as Int
    }
}
