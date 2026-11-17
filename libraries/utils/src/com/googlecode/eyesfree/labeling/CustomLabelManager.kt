/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.googlecode.eyesfree.labeling

import android.annotation.TargetApi
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.NameNotFoundException
import android.content.pm.Signature
import android.os.Build
import android.text.TextUtils
import android.util.Log
import android.util.Pair
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.googlecode.eyesfree.utils.AccessibilityEventListener
import com.googlecode.eyesfree.utils.LogUtils
import com.googlecode.eyesfree.utils.StringBuilderUtils
import com.googlecode.eyesfree.labeling.AllLabelsFetchRequest.OnAllLabelsFetchedListener
import com.googlecode.eyesfree.labeling.DirectLabelFetchRequest.OnLabelFetchedListener
import com.googlecode.eyesfree.labeling.PackageLabelsFetchRequest.OnLabelsFetchedListener
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.Collections
import java.util.LinkedList
import java.util.ListIterator
import java.util.Locale
import java.util.regex.Pattern
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manages logic for prefetching, retrieval, addition, updating, and removal of
 * custom view labels and their associated resources.
 *
 * @author caseyburkhardt@google.com (Casey Burkhardt)
 */
@TargetApi(18)
class CustomLabelManager(context: Context) : AccessibilityEventListener {
    companion object {
        /** The maximum number of package sets of labels to keep in memory. */
        private const val MAX_CACHE_SIZE = 10

        const val AUTHORITY = "com.google.android.marvin.talkback.providers.LabelProvider"

        /**
         * The substring separating a label's package and view ID name in a
         * fully-qualified resource identifier.
         */
        private val RESOURCE_NAME_SPLIT_PATTERN = Pattern.compile(":id/")

        private val REFRESH_INTENT_FILTER = IntentFilter(
            LabelOperationUtils.ACTION_REFRESH_LABEL_CACHE
        )

        /**
         * Splits a fully-qualified resource identifier name into its package and ID
         * name.
         */
        @JvmStatic
        fun splitResourceName(resourceName: String?): Pair<String, String>? {
            if (TextUtils.isEmpty(resourceName)) {
                return null
            }

            val splitId = RESOURCE_NAME_SPLIT_PATTERN.split(resourceName, 2)
            if (splitId.size != 2 || TextUtils.isEmpty(splitId[0]) || TextUtils.isEmpty(splitId[1])) {
                // Invalid input
                LogUtils.log(CustomLabelManager::class.java, Log.WARN, "Failed to parse resource: %s", resourceName)
                return null
            }

            return Pair(splitId[0], splitId[1])
        }
    }

    private val mLabelCache: com.googlecode.eyesfree.labeling.LruCache<String, Map<String, Label>> =
        com.googlecode.eyesfree.labeling.LruCache(MAX_CACHE_SIZE)

    private val mRefreshReceiver = CacheRefreshReceiver()

    private val mContext: Context = context
    private val mPackageManager: PackageManager = context.packageManager
    private val mClient: LabelProviderClient = LabelProviderClient(context, AUTHORITY)

    private var mLastLocale: Locale = Locale.getDefault()

    // Used to manage release of resources based on task completion
    private val mLock = Any()
    private var mShouldShutdownClient = false
    private var mRunningTasks = 0

    // 标签库读写固定走这一条单线程：等价于 AsyncTask 在 API 23+ 的
    // SERIAL_EXECUTOR，保证同一 view 的增删改不并发、prefetch 与 refresh
    // 的先后顺序与旧的执行器一致。回调仍在主线程投递（Dispatchers.Main）。
    private val mLabelScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    init {
        mLastLocale = Locale.getDefault()
        if (Build.VERSION.SDK_INT >= 33) {
            mContext.registerReceiver(mRefreshReceiver, REFRESH_INTENT_FILTER, Context.RECEIVER_NOT_EXPORTED)
        } else {
            mContext.registerReceiver(mRefreshReceiver, REFRESH_INTENT_FILTER)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val currentLocale = Locale.getDefault()
        if (currentLocale != mLastLocale) {
            // Refresh cache if device locale has changed since the last event
            mLastLocale = currentLocale
            refreshCacheInternal(null)
        }

        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> prefetchLabelsFromEvent(event)
        }
    }

    /**
     * Performs various tasks to ensure the underlying Label database is in a
     * state consistent with the installed applications on the device.
     */
    fun ensureDataConsistency() {
        if (!isInitialized) {
            return
        }

        runLabelTask(
            logName = "DataConsistencyCheck",
            work = { computeLabelsToRemove() },
            onDone = { labelsToRemove ->
                if (!labelsToRemove.isNullOrEmpty()) {
                    LogUtils.log(
                        this, Log.VERBOSE,
                        "Found %d labels to remove during consistency check", labelsToRemove.size
                    )
                    for (l in labelsToRemove) {
                        removeLabel(l)
                    }
                }
            },
        )
    }

    /**
     * Retrieves a [Label] from the label cache given a fully-qualified
     * resource identifier name.
     */
    fun getLabelForViewIdFromCache(resourceName: String?): Label? {
        if (!isInitialized) {
            return null
        }

        val parsedId = splitResourceName(resourceName) ?: return null

        if (!mLabelCache.containsKey(parsedId.first)) {
            // Cache miss or no labels for package
            return null
        }

        val packageLabels = mLabelCache.get(parsedId.first)
        return packageLabels?.get(parsedId.second)
    }

    /**
     * Retrieves a [Label] directly through the database and returns it
     * through a callback interface.
     */
    fun getLabelForLabelIdFromDatabase(labelId: Long, callback: OnLabelFetchedListener?) {
        if (!isInitialized) {
            return
        }

        val request = DirectLabelFetchRequest(labelId, callback)
        runLabelTask(
            logName = "DirectLabelFetch(id=$labelId)",
            work = { mClient.getLabelById(labelId) },
            onDone = { result ->
                LogUtils.log(this, Log.VERBOSE, "DirectLabelFetch(%d) complete.  Obtained label %s", labelId, result)
                request.invokeCallback(result)
            },
        )
    }

    /**
     * Retrieves a [Map] of view ID resource names to [Label]s for
     * labels in the given package name and returns it through a callback
     * interface.
     */
    fun getLabelsForPackageFromDatabase(packageName: String, callback: OnLabelsFetchedListener?) {
        if (!isInitialized) {
            return
        }

        val request = PackageLabelsFetchRequest(packageName, callback)
        runLabelTask(
            logName = "PackageLabelsFetch($packageName)",
            work = {
                var versionCode = Integer.MAX_VALUE
                try {
                    versionCode = mPackageManager.getPackageInfo(packageName, 0).versionCode
                } catch (e: NameNotFoundException) {
                    LogUtils.log(this, Log.WARN, "Unable to resolve package info during prefetch for %s", packageName)
                }

                mClient.getLabelsForPackage(packageName, Locale.getDefault().toString(), versionCode)
            },
            onDone = { result ->
                LogUtils.log(this, Log.VERBOSE, "LabelPrefetch for %s complete", packageName)
                request.invokeCallback(result)
            },
        )
    }

    fun getAllLabelsFromDatabase(callback: OnAllLabelsFetchedListener?) {
        if (!isInitialized) {
            return
        }

        val request = AllLabelsFetchRequest(callback)
        runLabelTask(
            logName = "AllLabelsFetch",
            work = { mClient.getAllLabels() },
            onDone = { result ->
                LogUtils.log(this, Log.VERBOSE, "AllLabelsFetch complete")
                request.invokeCallback(result)
            },
        )
    }

    /**
     * Creates a [Label] and persists it to the label database, and
     * refreshes the label cache.
     */
    fun addLabel(node: AccessibilityNodeInfo?, userLabel: String?) {
        if (node == null) {
            throw IllegalArgumentException("Attempted to add a label for a null node.")
        }

        val internalNodeCopy = AccessibilityNodeInfo.obtain(node)
        addLabel(internalNodeCopy.getViewIdResourceName(), userLabel)
        internalNodeCopy.recycle()
    }

    /**
     * Creates a [Label] and persists it to the label database, and
     * refreshes the label cache.
     */
    fun addLabel(resourceName: String?, userLabel: String?) {
        if (!isInitialized) {
            return
        }

        val finalLabel: String
        if (userLabel == null) {
            throw IllegalArgumentException("Attempted to add a label with a null userLabel value")
        } else {
            finalLabel = userLabel.trim()
            if (TextUtils.isEmpty(finalLabel)) {
                throw IllegalArgumentException("Attempted to add a label with an empty userLabel value")
            }
        }

        val parsedId = splitResourceName(resourceName) ?: run {
            LogUtils.log(this, Log.WARN, "Attempted to add a label with an invalid or poorly formed view ID.")
            return
        }

        val packageInfo: PackageInfo = try {
            mPackageManager.getPackageInfo(parsedId.first, PackageManager.GET_SIGNATURES)
        } catch (e: NameNotFoundException) {
            LogUtils.log(this, Log.WARN, "Attempted to add a label for an unknown package.")
            return
        }

        val locale = Locale.getDefault().toString()
        val version = packageInfo.versionCode
        val timestamp = System.currentTimeMillis()
        var signatureHash = ""

        val sigs = packageInfo.signatures
        try {
            val messageDigest = MessageDigest.getInstance("SHA-1")
            sigs?.forEach { messageDigest.update(it.toByteArray()) }

            signatureHash = StringBuilderUtils.bytesToHexString(messageDigest.digest()) ?: ""
        } catch (e: NoSuchAlgorithmException) {
            LogUtils.log(this, Log.WARN, "Unable to create SHA-1 MessageDigest")
        }

        // For the current implementation, screenshots are disabled
        val screenshotPath = ""

        val label = Label(
            parsedId.first, signatureHash, parsedId.second, finalLabel,
            locale, version, screenshotPath, timestamp
        )
        runLabelTask(
            logName = "LabelAdd(${label.viewName})",
            work = { mClient.insertLabel(label) },
            onDone = { stored ->
                LogUtils.log(this, Log.VERBOSE, "LabelAdd complete, stored as %s", stored)
                stored?.packageName?.let { sendCacheRefreshIntent(it) }
            },
        )
    }

    /**
     * Updates [Label]s in the label database and refreshes the label cache.
     */
    fun updateLabel(vararg labels: Label?) {
        if (!isInitialized) {
            return
        }

        if (labels.isEmpty()) {
            LogUtils.log(this, Log.WARN, "Attempted to update a null or empty array of labels.")
            return
        }

        for (l in labels) {
            if (l == null) {
                throw IllegalArgumentException("Attempted to update a null label.")
            }

            if (TextUtils.isEmpty(l.text)) {
                throw IllegalArgumentException("Attempted to update a label with an empty text value")
            }

            runLabelTask(
                logName = "LabelUpdate(${l.viewName})",
                work = { if (l.id != Label.NO_ID) mClient.updateLabel(l) else false },
                onDone = { updated ->
                    LogUtils.log(this, Log.VERBOSE, "LabelUpdate(%s) complete. Result: %s", l.viewName, updated)
                    if (updated == true) {
                        l.packageName?.let { sendCacheRefreshIntent(it) }
                    }
                },
            )
        }
    }

    /**
     * Removes [Label]s from the label database and refreshes the label cache.
     */
    fun removeLabel(vararg labels: Label) {
        if (!isInitialized) {
            return
        }

        if (labels.isEmpty()) {
            LogUtils.log(this, Log.WARN, "Attempted to delete a null or empty array of labels.")
            return
        }

        for (l in labels) {
            runLabelTask(
                logName = "LabelRemove(${l.viewName})",
                work = { if (l.id != Label.NO_ID) mClient.deleteLabel(l) else false },
                onDone = { removed ->
                    LogUtils.log(this, Log.VERBOSE, "LabelRemove(%s) complete. Removed: %s", l.viewName, removed)
                    if (removed == true) {
                        l.packageName?.let { sendCacheRefreshIntent(it) }
                    }
                },
            )
        }
    }

    /**
     * Invalidates and rebuilds the cache of labels managed by this class.
     */
    fun refreshCache(vararg packageNames: String) {
        var packageSet: HashSet<String>? = null
        if (packageNames.isNotEmpty()) {
            // Strip any duplicates
            packageSet = HashSet()
            for (p in packageNames) {
                packageSet.add(p)
            }
        }

        refreshCacheInternal(packageSet)
    }

    private fun refreshCacheInternal(packageNames: Set<String>?) {
        var names: Set<String>? = packageNames
        if (names == null || names.isEmpty()) {
            // Since there aren't specific packages to reload, copy all the
            // keys and invalidate the entire cache.
            names = Collections.unmodifiableSet(mLabelCache.keys)
            mLabelCache.clear()
        } else {
            // Since there are targeted packages to refresh, prune just those
            // from the cache.
            for (p in names) {
                mLabelCache.remove(p)
            }
        }

        // Rebuild relevant parts of the cache.
        if (names != null) {
            for (packageName in names) {
                if (!TextUtils.isEmpty(packageName)) {
                    prefetchLabelsForPackage(packageName)
                }
            }
        }
    }

    /**
     * Shuts down the manager and releases resources.
     */
    fun shutdown() {
        LogUtils.log(this, Log.VERBOSE, "Shutdown requested.")

        // We must immediately destroy registered receivers to prevent a leak,
        // as the context backing this registration is to be invalidated.
        mContext.unregisterReceiver(mRefreshReceiver)

        // We cannot shutdown resources related to the database until all tasks
        // have completed. Flip the flag to indicate a client of this manager
        // requested a shutdown and attempt the operation.
        mShouldShutdownClient = true
        maybeShutdownClient()
    }

    /**
     * Returns whether the labeling client is properly initialized.
     */
    val isInitialized: Boolean
        get() = mClient.isInitialized()

    /**
     * Shuts down the database resources held by an instance of this manager if
     * certain conditions are met.
     */
    private fun maybeShutdownClient() {
        synchronized(mLock) {
            if (mRunningTasks == 0 && mShouldShutdownClient) {
                LogUtils.log(this, Log.VERBOSE, "All tasks completed and shutdown requested.  Releasing database.")
                mClient.shutdown()
            }
        }
    }

    private fun taskStarting(logName: String) {
        synchronized(mLock) {
            LogUtils.log(this, Log.VERBOSE, "Task %s starting.", logName)
            mRunningTasks++
        }
    }

    private fun taskEnding(logName: String) {
        synchronized(mLock) {
            LogUtils.log(this, Log.VERBOSE, "Task %s ending.", logName)
            mRunningTasks--
        }

        maybeShutdownClient()
    }

    private fun prefetchLabelsFromEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            // Use TYPE_WINDOW_CONTENT_CHANGED events to trigger a search
            // through the node's children to prefetch all relevant package
            // names. This prefetches labels for remote views.
            val source = event.source
            val packages = HashSet<CharSequence>()
            val seenNodes = LinkedList<AccessibilityNodeInfo?>()
            seenNodes.add(source)

            // Breadth first traversal for populating the package set
            while (!seenNodes.isEmpty()) {
                val currentNode = seenNodes.removeFirst() ?: continue

                val resId = splitResourceName(currentNode.getViewIdResourceName())
                if (resId != null) {
                    packages.add(resId.first)
                }

                val childCount = currentNode.childCount
                for (i in 0 until childCount) {
                    seenNodes.add(currentNode.getChild(i))
                }

                currentNode.recycle()
            }

            for (packageName in packages) {
                prefetchLabelsForPackage(packageName.toString())
            }
        } else {
            // Other AccessibilityEvent types should use the package name from its source.
            val node = event?.source
            if (node != null) {
                val resId = splitResourceName(node.getViewIdResourceName())
                if (resId != null) {
                    prefetchLabelsForPackage(resId.first)
                }
            }
        }
    }

    private fun prefetchLabelsForPackage(packageName: String?) {
        if (packageName.isNullOrEmpty()) {
            return
        }

        if (!mLabelCache.containsKey(packageName)) {
            val callback = object : PackageLabelsFetchRequest.OnLabelsFetchedListener {
                override fun onLabelsFetched(results: Map<String, Label>?) {
                    if (results != null) {
                        mLabelCache.put(packageName, results)
                    }
                }
            }

            getLabelsForPackageFromDatabase(packageName, callback)
        }
    }

    private fun computePackageSignatureHash(packageInfo: PackageInfo?): String {
        var signatureHash = ""

        val sigs = packageInfo?.signatures
        try {
            val messageDigest = MessageDigest.getInstance("SHA-1")
            sigs?.forEach { messageDigest.update(it.toByteArray()) }

            signatureHash = StringBuilderUtils.bytesToHexString(messageDigest.digest()) ?: ""
        } catch (e: NoSuchAlgorithmException) {
            LogUtils.log(CustomLabelManager::class.java, Log.WARN, "Unable to create SHA-1 MessageDigest")
        }

        return signatureHash
    }

    private fun sendCacheRefreshIntent(vararg packageNames: String?) {
        val refreshIntent = Intent(LabelOperationUtils.ACTION_REFRESH_LABEL_CACHE)
        refreshIntent.putExtra(
            LabelOperationUtils.EXTRA_STRING_ARRAY_PACKAGES,
            packageNames.filterNotNull().toTypedArray()
        )
        mContext.sendBroadcast(refreshIntent)
    }

    private inner class CacheRefreshReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            var packages: Array<String>? = null
            if (intent?.hasExtra(LabelOperationUtils.EXTRA_STRING_ARRAY_PACKAGES) == true) {
                packages = intent.getStringArrayExtra(LabelOperationUtils.EXTRA_STRING_ARRAY_PACKAGES)
            }

            refreshCache(*(packages ?: emptyArray()))
        }
    }

    /**
     * 原来 7 个 AsyncTask 子类共用的调度点。
     *
     * 三点语义与 AsyncTask 时代保持一致：串行执行（单线程调度器，等价于
     * API 23+ 的 SERIAL_EXECUTOR）、结果回到主线程交付、整段执行前后维护任务
     * 计数，好让 [maybeShutdownClient] 知道何时可以释放数据库连接。
     */
    private fun <T> runLabelTask(logName: String, work: () -> T?, onDone: (T?) -> Unit) {
        taskStarting(logName)
        mLabelScope.launch {
            var failure: Throwable? = null
            val result: T? = try {
                work()
            } catch (t: Throwable) {
                failure = t
                null
            }

            try {
                if (failure != null) {
                    // 与 AsyncTask 时代一致：后台异常不静默吞掉。
                    LogUtils.log(
                        this@CustomLabelManager, Log.ERROR, "Task %s failed: %s", logName, failure
                    )
                    throw failure
                }

                withContext(Dispatchers.Main) {
                    onDone(result)
                }
            } finally {
                taskEnding(logName)
            }
        }
    }

    /**
     * 一致性检查的业务部分（原 `DataConsistencyCheckTask.doInBackground`）：
     * 挑出「包已卸载」或「签名对不上」的标签，交给调用方删除。
     */
    private fun computeLabelsToRemove(): List<Label>? {
        val allLabels = mClient.getAllLabels()

        if (allLabels == null || allLabels.isEmpty()) {
            return null
        }

        val pm = mContext.packageManager

        val candidates = ArrayList(allLabels)
        val i = candidates.listIterator()

        // Iterate through the labels database, and prune labels that belong
        // to valid packages.
        while (i.hasNext()) {
            val l = i.next()

            // Ensure the label has a matching installed package.
            val packageName = l.packageName ?: continue

            var packageInfo: PackageInfo? = null
            try {
                packageInfo = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            } catch (e: NameNotFoundException) {
                // If there's no installed package, leave the label in the
                // list for removal.
                LogUtils.log(CustomLabelManager::class.java, Log.VERBOSE, "Consistency check removing label for unknown package %s.", packageName)
                continue
            }

            // Ensure the signature hash of the application matches
            // the hash of the package when the label was stored.
            val expectedHash = l.packageSignature
            val actualHash = packageInfo?.let { computePackageSignatureHash(it) }
            if (TextUtils.isEmpty(expectedHash) || TextUtils.isEmpty(actualHash)
                || expectedHash != actualHash
            ) {
                // If the expected or actual signature hashes aren't
                // valid, or they don't match, leave the label in the list
                // for removal.
                LogUtils.log(CustomLabelManager::class.java, Log.WARN, "Consistency check removing label due to signature mismatch for package %s.", packageName)
                continue
            }

            // If the label has passed all consistency checks, prune the
            // label from the list of potentials for removal.
            i.remove()
        }

        return candidates // now containing only labels for removal
    }
}
