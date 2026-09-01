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

package com.googlecode.eyesfree.utils

import android.content.Context
import android.content.pm.PackageInfo
import android.text.TextUtils
import android.util.Log
import java.util.HashSet

/**
 * This class manages efficient loading of classes.
 *
 * @author svetoslavganov@google.com (Svetoslav R. Ganov)
 * @author alanv@google.com (Alan Viverette)
 */
class ClassLoadingManager private constructor() {
    companion object {
        /**
         * The singleton instance of this class.
         */
        private var sInstance: ClassLoadingManager? = null

        /**
         * The singleton instance of this class.
         *
         * @return The singleton instance of this class.
         */
        @JvmStatic
        fun getInstance(): ClassLoadingManager {
            return sInstance ?: ClassLoadingManager().also { sInstance = it }
        }
    }

    /**
     * Mapping from class names to classes from outside packages.
     */
    private val mClassNameToClassMap = HashMap<String, Class<*>>()

    /**
     * A set of classes not found to be loaded. Used to avoid multiple attempts
     * that will fail.
     */
    private val mNotFoundClassesMap = HashMap<String, HashSet<String>>()

    /**
     * Builds the package cache and registers the package monitor.
     *
     * @param context The [Context] to use for monitor registration.
     */
    fun init(context: Context) {
        if (mNotFoundClassesMap.isNotEmpty()) {
            buildInstalledPackagesCache(context)
        }
        mPackageMonitor.register(context)
    }

    /**
     * Clears the package cache and unregisters the package monitor.
     */
    fun shutdown() {
        mClassNameToClassMap.clear()
        mPackageMonitor.unregister()
    }

    /**
     * Builds a cache of installed packages.
     */
    private fun buildInstalledPackagesCache(context: Context) {
        val installedPackages: List<PackageInfo> =
            context.packageManager.getInstalledPackages(0)
        for (installedPackage in installedPackages) {
            addInstalledPackageToCache(installedPackage.packageName)
        }
    }

    /**
     * Adds the specified package to the installed package cache.
     *
     * @param packageName The package name to add.
     */
    private fun addInstalledPackageToCache(packageName: String) {
        synchronized(mNotFoundClassesMap) {
            mNotFoundClassesMap.remove(packageName)
        }
    }

    /**
     * Returns a class by given `className`. The loading proceeds as follows:
     * 1. Try to load with the current context class loader (it caches loaded
     *    classes).
     * 2. If (1) fails try if we have loaded the class before and return it if
     *    that is the case.
     * 3. If (2) failed, try to create a package context and load the class.
     *
     * Note: If the package name is null and an attempt for loading of a
     * package context is required then it is extracted from the class name.
     *
     * @param context The context from which to first try loading the class.
     * @param className The name of the class to load.
     * @param packageName The name of the package to which the class belongs.
     * @return The class if loaded successfully, null otherwise.
     */
    fun loadOrGetCachedClass(
        context: Context?,
        className: CharSequence?,
        packageName: CharSequence?
    ): Class<*>? {
        if (TextUtils.isEmpty(className)) {
            LogUtils.log(this, Log.DEBUG, "Missing class name. Failed to load class.")
            return null
        }

        // If we don't know the package name, get it from the class name.
        var resolvedPackageName = packageName
        if (TextUtils.isEmpty(resolvedPackageName)) {
            val lastDotIndex = TextUtils.lastIndexOf(className, '.')
            if (lastDotIndex < 0) {
                LogUtils.log(
                    this, Log.DEBUG, "Missing package name. Failed to load class: %s", className)
                return null
            }
            resolvedPackageName = TextUtils.substring(className, 0, lastDotIndex)
        }

        val classNameStr = className.toString()
        val packageNameStr = resolvedPackageName.toString()

        // If we failed loading this class once, don't bother trying again.
        var notFoundClassesSet: HashSet<String>? = null
        synchronized(mNotFoundClassesMap) {
            notFoundClassesSet = mNotFoundClassesMap[packageNameStr]
            if (notFoundClassesSet != null && notFoundClassesSet!!.contains(classNameStr)) {
                return null
            }
        }

        // See if we have a cached class.
        val clazz = mClassNameToClassMap[classNameStr]
        if (clazz != null) {
            return clazz
        }

        // Try the current ClassLoader.
        try {
            val insideClazz = javaClass.classLoader?.loadClass(classNameStr)
            if (insideClazz != null) {
                mClassNameToClassMap[classNameStr] = insideClazz
                return insideClazz
            }
        } catch (e: ClassNotFoundException) {
            // Do nothing.
        }

        // Context is required past this point.
        if (context == null) {
            return null
        }

        // Attempt to load class by creating a package context.
        try {
            val flags = Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
            val packageContext = context.createPackageContext(packageNameStr, flags)
            val outsideClazz = packageContext.classLoader.loadClass(classNameStr)
            if (outsideClazz != null) {
                mClassNameToClassMap[classNameStr] = outsideClazz
                return outsideClazz
            }
        } catch (e: Exception) {
            LogUtils.log(
                this, Log.ERROR,
                "Error encountered. Failed to load outside class: %s", classNameStr)
        }

        if (notFoundClassesSet == null) {
            notFoundClassesSet = HashSet()
            mNotFoundClassesMap[packageNameStr] = notFoundClassesSet
        }
        notFoundClassesSet.add(classNameStr)

        LogUtils.log(Log.DEBUG, "Failed to load class: %s", classNameStr)

        return null
    }

    /**
     * Returns whether a target class is an instance of a reference class.
     *
     * If a class cannot be loaded by the default [ClassLoader], this method
     * will attempt to use the loader for the specified app package.
     */
    fun checkInstanceOf(
        context: Context?,
        targetClassName: CharSequence?,
        loaderPackage: CharSequence?,
        referenceClassName: CharSequence?
    ): Boolean {
        if (targetClassName == null || referenceClassName == null) {
            return false
        }

        // Try a shortcut for efficiency.
        if (TextUtils.equals(targetClassName, referenceClassName)) {
            return true
        }

        val referenceClass = loadOrGetCachedClass(context, referenceClassName, loaderPackage)
            ?: return false

        return checkInstanceOf(context, targetClassName, loaderPackage, referenceClass)
    }

    /**
     * Returns whether a target class is an instance of a reference class.
     *
     * If a class cannot be loaded by the default [ClassLoader], this method
     * will attempt to use the loader for the specified app package.
     */
    fun checkInstanceOf(
        context: Context?,
        targetClassName: CharSequence?,
        loaderPackage: CharSequence?,
        referenceClass: Class<*>?
    ): Boolean {
        if (targetClassName == null || referenceClass == null) {
            return false
        }

        val targetClass = loadOrGetCachedClass(context, targetClassName, loaderPackage)
            ?: return false

        return referenceClass.isAssignableFrom(targetClass)
    }

    /**
     * Monitor to keep track of the installed packages.
     */
    private val mPackageMonitor = object : BasePackageMonitor() {
        override fun onPackageAdded(packageName: String?) {
            if (packageName != null) {
                addInstalledPackageToCache(packageName)
            }
        }

        override fun onPackageRemoved(packageName: String?) {
            // Do nothing.
        }

        override fun onPackageChanged(packageName: String?) {
            // Do nothing.
        }
    }
}
