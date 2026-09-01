/*
 * Copyright (C) 2013 Google Inc.
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
import android.content.ContentProviderClient
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.RemoteException
import android.util.Log
import com.googlecode.eyesfree.utils.LogUtils
import java.util.Collections

private fun ContentValues.putString(key: String, value: String?) {
    if (value == null) putNull(key) else put(key, value)
}

/**
 * A client for storing and retrieving custom TalkBack view labels using the
 * [Label] model class and a connection to a
 * [android.content.ContentProvider] for labels.
 *
 * @author awdavis@google.com (Austin Davis)
 */
@TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
class LabelProviderClient(context: Context, authority: String) {
    private var mClient: ContentProviderClient?

    private val mLabelsContentUri: Uri
    private val mPackageSummaryContentUri: Uri

    init {
        mLabelsContentUri = Uri.Builder()
            .scheme("content")
            .authority(authority)
            .path(LABELS_PATH)
            .build()
        mPackageSummaryContentUri = Uri.Builder()
            .scheme("content")
            .authority(authority)
            .path(PACKAGE_SUMMARY_PATH)
            .build()

        val contentResolver: ContentResolver = context.contentResolver
        mClient = contentResolver.acquireContentProviderClient(mLabelsContentUri)

        if (mClient == null) {
            LogUtils.log(this, Log.WARN, "Failed to acquire content provider client.")
        }
    }

    /**
     * Inserts the specified label into the labels database via a client for the
     * labels [android.content.ContentProvider].
     *
     * Don't run this method on the UI thread. Use [android.os.AsyncTask].
     *
     * @param label The model object for the label to store in the database.
     * @return A new label object with the assigned label ID from the database,
     *         or `null` if the insert operation failed.
     */
    fun insertLabel(label: Label?): Label? {
        LogUtils.log(this, Log.DEBUG, "Inserting label: %s.", label)

        if (label == null) {
            return null
        }

        val labelId = label.id
        if (label.id != Label.NO_ID) {
            LogUtils.log(this, Log.WARN, "Cannot insert label with existing ID (id=%d).", labelId)
            return null
        }

        if (!checkClient()) {
            return null
        }

        val values = buildContentValuesForLabel(label)

        val resultUri: Uri?
        try {
            resultUri = mClient!!.insert(mLabelsContentUri, values)
        } catch (e: RemoteException) {
            LogUtils.log(this, Log.ERROR, e.toString())
            return null
        }

        if (resultUri == null) {
            LogUtils.log(this, Log.WARN, "Failed to insert label.")
            return null
        }

        val newLabelId = resultUri.lastPathSegment!!.toLong()
        return Label(label, newLabelId)
    }

    /**
     * Gets a list of all labels in the label database.
     *
     * Don't run this method on the UI thread. Use [android.os.AsyncTask].
     *
     * @return An unmodifiable list of all labels in the database,
     *         or an empty list if the query returns no results,
     *         or `null` if the query fails.
     */
    fun getAllLabels(): List<Label>? {
        LogUtils.log(this, Log.DEBUG, "Querying all labels.")

        if (!checkClient()) {
            return null
        }

        var cursor: Cursor? = null
        try {
            cursor = mClient!!.query(
                mLabelsContentUri, LabelsTable.ALL_COLUMNS /* projection */,
                null /* where */, null /* whereArgs */, null /* sortOrder */
            )

            return getLabelListFromCursor(cursor)
        } catch (e: RemoteException) {
            LogUtils.log(this, Log.ERROR, e.toString())
            return null
        } finally {
            cursor?.close()
        }
    }

    /**
     * Gets a summary of label info for each package from the label database.
     *
     * Don't run this method on the UI thread. Use [android.os.AsyncTask].
     *
     * @return An unmodifiable list of [PackageLabelInfo] objects, or an
     *         empty map if the query returns no results, or `null` if the
     *         query fails.
     */
    fun getPackageSummary(locale: String): List<PackageLabelInfo>? {
        LogUtils.log(this, Log.DEBUG, "Querying package summary.")

        if (!checkClient()) {
            return null
        }

        val whereArgs = arrayOf(locale)

        var cursor: Cursor? = null
        try {
            cursor = mClient!!.query(
                mPackageSummaryContentUri, null /* projection */,
                PACKAGE_SUMMARY_QUERY_WHERE, whereArgs, null /* sortOrder */
            )

            return getPackageSummaryFromCursor(cursor)
        } catch (e: RemoteException) {
            LogUtils.log(this, Log.ERROR, e.toString())
            return null
        } finally {
            cursor?.close()
        }
    }

    /**
     * Queries for labels matching a particular package and locale.
     *
     * Don't run this method on the UI thread. Use [android.os.AsyncTask].
     *
     * @param packageName The package name to match.
     * @param locale The locale to match.
     * @param maxPackageVersion The maximum package version for result labels.
     * @return An unmodifiable map from view names to label objects that
     *         contains all labels matching the criteria, or `null` if the
     *         query failed.
     */
    fun getLabelsForPackage(
        packageName: String,
        locale: String,
        maxPackageVersion: Int
    ): Map<String, Label>? {
        LogUtils.log(
            this, Log.DEBUG,
            "Querying labels for package: packageName=%s, locale=%s, maxPackageVersion=%s.",
            packageName, locale, maxPackageVersion
        )

        if (!checkClient()) {
            return null
        }

        val whereArgs = arrayOf(
            packageName, locale, Integer.toString(maxPackageVersion)
        )

        var cursor: Cursor? = null
        try {
            cursor = mClient!!.query(
                mLabelsContentUri, LabelsTable.ALL_COLUMNS /* projection */,
                GET_LABELS_FOR_APPLICATION_QUERY_WHERE, whereArgs,
                null /* sortOrder */
            )

            return getLabelMapFromCursor(cursor)
        } catch (e: RemoteException) {
            LogUtils.log(this, Log.ERROR, e.toString())
            return null
        } finally {
            cursor?.close()
        }
    }

    /**
     * Queries for labels matching a particular package and locale for all
     * versions of that package.
     *
     * Don't run this method on the UI thread. Use [android.os.AsyncTask].
     *
     * @param packageName The package name to match.
     * @param locale The locale to match.
     * @return An unmodifiable map from view names to label objects that
     *         contains all labels matching the criteria, or `null` if the
     *         query failed.
     */
    fun getLabelsForPackage(packageName: String, locale: String): Map<String, Label>? {
        return getLabelsForPackage(packageName, locale, Int.MAX_VALUE)
    }

    /**
     * Queries for a single label matching a particular view and locale.
     *
     * Don't run this method on the UI thread. Use [android.os.AsyncTask].
     *
     * @param packageName The package name for the view's application.
     * @param viewName The identifier of the view for which to find a label.
     * @param locale The desired locale for the label.
     * @param maxPackageVersion The maximum package version for result labels.
     * @return A single label matching the criteria,
     *         or `null` if no matching label was found.
     */
    fun getLabel(
        packageName: String,
        viewName: String,
        locale: String,
        maxPackageVersion: Int
    ): Label? {
        LogUtils.log(
            this, Log.DEBUG, "Querying single label: " +
                "packageName=%s, viewName=%s, locale=%s, maxPackageVersion=%s.",
            packageName, viewName, locale, maxPackageVersion
        )

        if (!checkClient()) {
            return null
        }

        val whereArgs = arrayOf(
            packageName, viewName, locale, Integer.toString(maxPackageVersion)
        )
        var cursor: Cursor? = null
        try {
            cursor = mClient!!.query(
                mLabelsContentUri, LabelsTable.ALL_COLUMNS,
                GET_LABEL_QUERY_WHERE, whereArgs, null /* sortOrder */
            )

            return getLabelFromCursor(cursor)
        } catch (e: RemoteException) {
            LogUtils.log(this, Log.ERROR, e.toString())
            return null
        } finally {
            cursor?.close()
        }
    }

    /**
     * Queries for a single label by its label ID.
     *
     * Don't run this method on the UI thread. Use [android.os.AsyncTask].
     *
     * @param id The ID of the label to find.
     * @return The label with the given ID,
     *         or `null` if no such label was found.
     */
    fun getLabelById(id: Long): Label? {
        LogUtils.log(this, Log.DEBUG, "Querying single label: id=%d.", id)

        if (!checkClient()) {
            return null
        }

        val uri = ContentUris.withAppendedId(mLabelsContentUri, id)
        var cursor: Cursor? = null
        try {
            cursor = mClient!!.query(
                uri, LabelsTable.ALL_COLUMNS, null /* where */,
                null /* whereArgs */, null /* sortOrder */
            )

            return getLabelFromCursor(cursor)
        } catch (e: RemoteException) {
            LogUtils.log(this, Log.ERROR, e.toString())
            return null
        } finally {
            cursor?.close()
        }
    }

    /**
     * Updates a single label.
     *
     * Don't run this method on the UI thread. Use [android.os.AsyncTask].
     *
     * @param label The label with updated values to store.
     * @return `true` if the update succeeded, or `false` otherwise.
     */
    fun updateLabel(label: Label?): Boolean {
        LogUtils.log(this, Log.DEBUG, "Updating label: %s.", label)

        if (label == null) {
            return false
        }

        if (!checkClient()) {
            return false
        }

        val labelId = label.id

        if (labelId == Label.NO_ID) {
            LogUtils.log(this, Log.WARN, "Cannot update label with no ID.")
            return false
        }

        val uri = ContentUris.withAppendedId(mLabelsContentUri, labelId)
        val values = buildContentValuesForLabel(label)

        try {
            val rowsAffected = mClient!!.update(
                uri, values, null /* selection */, null /* selectionArgs */
            )
            return rowsAffected > 0
        } catch (e: RemoteException) {
            LogUtils.log(this, Log.ERROR, e.toString())
            return false
        }
    }

    /**
     * Deletes a single label.
     *
     * Don't run this method on the UI thread. Use [android.os.AsyncTask].
     *
     * @param label The label to delete.
     * @return `true` if the delete succeeded, or `false` otherwise.
     */
    fun deleteLabel(label: Label?): Boolean {
        LogUtils.log(this, Log.DEBUG, "Deleting label: %s.", label)

        if (label == null) {
            return false
        }

        if (!checkClient()) {
            return false
        }

        val labelId = label.id

        if (labelId == Label.NO_ID) {
            LogUtils.log(this, Log.WARN, "Cannot delete label with no ID.")
            return false
        }

        val uri = ContentUris.withAppendedId(mLabelsContentUri, labelId)

        try {
            val rowsAffected = mClient!!.delete(
                uri, null /* selection */, null /* selectionArgs */
            )
            return rowsAffected > 0
        } catch (e: RemoteException) {
            LogUtils.log(this, Log.ERROR, e.toString())
            return false
        }
    }

    /**
     * Shuts down the client and releases any resources.
     */
    fun shutdown() {
        if (checkClient()) {
            mClient!!.release()
            mClient = null
        }
    }

    /**
     * Returns whether the client was properly initialized (non-null).
     * @return `true` if client is non-null, or `false` otherwise.
     */
    fun isInitialized(): Boolean {
        return mClient != null
    }

    private fun buildContentValuesForLabel(label: Label): ContentValues {
        val values = ContentValues()

        values.putString(LabelsTable.KEY_PACKAGE_NAME, label.packageName)
        values.putString(LabelsTable.KEY_PACKAGE_SIGNATURE, label.packageSignature)
        values.putString(LabelsTable.KEY_VIEW_NAME, label.viewName)
        values.putString(LabelsTable.KEY_TEXT, label.text)
        values.put(LabelsTable.KEY_LOCALE, label.locale.toString())
        values.put(LabelsTable.KEY_PACKAGE_VERSION, label.packageVersion)
        values.putString(LabelsTable.KEY_SCREENSHOT_PATH, label.screenshotPath)
        values.put(LabelsTable.KEY_TIMESTAMP, label.timestamp)

        return values
    }

    private fun getLabelFromCursorAtCurrentPosition(cursor: Cursor?): Label? {
        val c = cursor ?: return null
        if (c.isClosed || c.isAfterLast) {
            LogUtils.log(this, Log.WARN, "Failed to get label from cursor.")
            return null
        }

        val labelId = c.getLong(LabelsTable.INDEX_ID)
        val packageName = c.getString(LabelsTable.INDEX_PACKAGE_NAME)
        val packageSignature = c.getString(LabelsTable.INDEX_PACKAGE_SIGNATURE)
        val viewName = c.getString(LabelsTable.INDEX_VIEW_NAME)
        val text = c.getString(LabelsTable.INDEX_TEXT)
        val locale = c.getString(LabelsTable.INDEX_LOCALE)
        val packageVersion = c.getInt(LabelsTable.INDEX_PACKAGE_VERSION)
        val screenshotPath = c.getString(LabelsTable.INDEX_SCREENSHOT_PATH)
        val timestamp = c.getLong(LabelsTable.INDEX_TIMESTAMP)

        return Label(
            labelId, packageName, packageSignature, viewName, text, locale,
            packageVersion, screenshotPath, timestamp
        )
    }

    private fun getPackageLabelInfoFromCursor(cursor: Cursor?): PackageLabelInfo? {
        val c = cursor ?: return null
        if (c.isClosed || c.isAfterLast) {
            LogUtils.log(this, Log.WARN, "Failed to get PackageLabelInfo from cursor.")
            return null
        }

        val packageName = c.getString(0)
        val labelCount = c.getInt(1)

        return PackageLabelInfo(packageName ?: "", labelCount)
    }

    private fun getLabelFromCursor(cursor: Cursor?): Label? {
        if (cursor == null) {
            return null
        }

        cursor.moveToFirst()
        val result = getLabelFromCursorAtCurrentPosition(cursor)

        logResult(result)

        return result
    }

    private fun getLabelListFromCursor(cursor: Cursor?): List<Label> {
        if (cursor == null) {
            return Collections.emptyList()
        }

        val result = ArrayList<Label>()
        while (cursor.moveToNext()) {
            val label = getLabelFromCursorAtCurrentPosition(cursor)
            if (label != null) {
                result.add(label)
            }
        }

        logResult(result)

        return Collections.unmodifiableList(result)
    }

    private fun getPackageSummaryFromCursor(cursor: Cursor?): List<PackageLabelInfo> {
        if (cursor == null) {
            return Collections.emptyList()
        }

        val result = ArrayList<PackageLabelInfo>()
        while (cursor.moveToNext()) {
            val packageLabelInfo = getPackageLabelInfoFromCursor(cursor)
            if (packageLabelInfo != null) {
                result.add(packageLabelInfo)
            }
        }

        return Collections.unmodifiableList(result)
    }

    private fun getLabelMapFromCursor(cursor: Cursor?): Map<String, Label> {
        if (cursor == null) {
            return Collections.emptyMap()
        }

        val labelCount = cursor.count // can return -1
        val initialCapacity = Math.max(labelCount, 0)

        val result = HashMap<String, Label>(initialCapacity)
        while (cursor.moveToNext()) {
            val label = getLabelFromCursorAtCurrentPosition(cursor)
            if (label != null) {
                result.put(label.viewName ?: "", label)
            }
        }

        logResult(result.values)

        return Collections.unmodifiableMap(result)
    }

    private fun logResult(label: Label?) {
        LogUtils.log(this, Log.VERBOSE, "Query result: %s.", label)
    }

    private fun logResult(result: Iterable<Label?>) {
        if (LogUtils.shouldLog(Log.VERBOSE)) {
            val logMessageBuilder = StringBuilder("Query result: [")
            for (label in result) {
                logMessageBuilder.append("\n  ")
                logMessageBuilder.append(label)
            }
            logMessageBuilder.append("].")

            LogUtils.log(this, Log.VERBOSE, logMessageBuilder.toString())
        }
    }

    private fun checkClient(): Boolean {
        if (mClient == null) {
            LogUtils.log(
                this, Log.WARN,
                "Aborting operation: the client failed to initialize or already shut down."
            )
            return false
        }

        return true
    }

    companion object {
        private const val EQUALS_ARGUMENT = " = ?"
        private const val LEQ_ARGUMENT = " <= ?"
        private const val AND = " AND "

        private val GET_LABELS_FOR_APPLICATION_QUERY_WHERE = StringBuilder()
            .append(LabelsTable.KEY_PACKAGE_NAME).append(EQUALS_ARGUMENT)
            .append(AND).append(LabelsTable.KEY_LOCALE).append(EQUALS_ARGUMENT)
            .append(AND).append(LabelsTable.KEY_PACKAGE_VERSION).append(LEQ_ARGUMENT)
            .toString()

        private val GET_LABEL_QUERY_WHERE = StringBuilder()
            .append(LabelsTable.KEY_PACKAGE_NAME).append(EQUALS_ARGUMENT)
            .append(AND).append(LabelsTable.KEY_VIEW_NAME).append(EQUALS_ARGUMENT)
            .append(AND).append(LabelsTable.KEY_LOCALE).append(EQUALS_ARGUMENT)
            .append(AND).append(LabelsTable.KEY_PACKAGE_VERSION).append(LEQ_ARGUMENT)
            .toString()

        private val PACKAGE_SUMMARY_QUERY_WHERE = StringBuilder()
            .append(LabelsTable.KEY_LOCALE).append(EQUALS_ARGUMENT)
            .toString()

        private const val LABELS_PATH = "labels"
        private const val PACKAGE_SUMMARY_PATH = "packageSummary"
    }
}
