/*
 * Copyright (C) 2014 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.googlecode.eyesfree.compat

import android.text.TextUtils
import android.util.Log
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

object CompatUtils {
    private val TAG = CompatUtils::class.java.simpleName

    /** Whether to log debug output. */
    const val DEBUG = false

    @JvmStatic
    fun getClass(className: String?): Class<*>? {
        if (TextUtils.isEmpty(className)) {
            return null
        }
        return try {
            Class.forName(className)
        } catch (e: ClassNotFoundException) {
            if (DEBUG) {
                e.printStackTrace()
            }
            null
        }
    }

    @JvmStatic
    fun getMethod(
        targetClass: Class<*>?,
        name: String?,
        vararg parameterTypes: Class<*>?
    ): Method? {
        if (targetClass == null || TextUtils.isEmpty(name)) {
            return null
        }
        return try {
            @Suppress("UNCHECKED_CAST")
            targetClass.getDeclaredMethod(name, *parameterTypes as Array<Class<*>>)
        } catch (e: Exception) {
            if (DEBUG) {
                e.printStackTrace()
            }
            null
        }
    }

    @JvmStatic
    fun getField(targetClass: Class<*>?, name: String?): Field? {
        if (targetClass == null || TextUtils.isEmpty(name)) {
            return null
        }
        return try {
            targetClass.getDeclaredField(name)
        } catch (e: Exception) {
            if (DEBUG) {
                e.printStackTrace()
            }
            null
        }
    }

    @JvmStatic
    fun getConstructor(targetClass: Class<*>?, vararg types: Class<*>?): Constructor<*>? {
        if (targetClass == null || types == null) {
            return null
        }
        return try {
            @Suppress("UNCHECKED_CAST")
            targetClass.getConstructor(*types as Array<Class<*>>)
        } catch (e: Exception) {
            if (DEBUG) {
                e.printStackTrace()
            }
            null
        }
    }

    @JvmStatic
    fun newInstance(constructor: Constructor<*>?, vararg args: Any?): Any? {
        if (constructor == null) {
            return null
        }
        return try {
            constructor.newInstance(*args)
        } catch (e: Exception) {
            Log.e(TAG, "Exception in newInstance: " + e.javaClass.simpleName)
            if (DEBUG) {
                e.printStackTrace()
            }
            null
        }
    }

    @JvmStatic
    fun invoke(
        receiver: Any?,
        defaultReturnValue: Any?,
        method: Method?,
        vararg args: Any?
    ): Any? {
        if (method == null) {
            return defaultReturnValue
        }
        return try {
            method.invoke(receiver, *args)
        } catch (e: Exception) {
            Log.e(TAG, "Exception in invoke: " + e.javaClass.simpleName)
            if (DEBUG) {
                e.printStackTrace()
            }
            defaultReturnValue
        }
    }

    @JvmStatic
    fun getFieldValue(receiver: Any?, defaultReturnValue: Any?, field: Field?): Any? {
        if (field == null) {
            return defaultReturnValue
        }
        return try {
            field.get(receiver)
        } catch (e: Exception) {
            if (DEBUG) {
                e.printStackTrace()
            }
            defaultReturnValue
        }
    }

    @JvmStatic
    fun setFieldValue(receiver: Any?, field: Field?, value: Any?) {
        if (field == null) {
            return
        }
        try {
            field.set(receiver, value)
        } catch (e: Exception) {
            if (DEBUG) {
                e.printStackTrace()
            }
        }
    }
}
