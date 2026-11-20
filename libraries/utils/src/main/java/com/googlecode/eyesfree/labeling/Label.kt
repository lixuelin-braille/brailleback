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

/**
 * A model class for a custom view label that TalkBack can speak.
 *
 * @author awdavis@google.com (Austin Davis)
 */
data class Label(
    var id: Long,
    var packageName: String?,
    var packageSignature: String?,
    var viewName: String?,
    var text: String?,
    var locale: String?,
    var packageVersion: Int,
    var screenshotPath: String?,
    var timestamp: Long
) {
    companion object {
        /** The ID of a label that has not yet been stored in the database. */
        const val NO_ID = -1L

        private val DEBUG_FORMAT_STRING = "%s[id=%d, packageName=%s, " +
            "packageSignature=%s, viewName=%s, text=%s, locale=%s, " +
            "packageVersion=%d, screenshotPath=%s, timestamp=%d]"
    }

    /**
     * Creates a new label, usually one that does not yet exist in the local
     * database.
     *
     * @param packageName The package name for the labeled application.
     * @param packageSignature A string uniquely representing the package.
     * @param viewName The fully qualified resource name for the labeled view.
     * @param text The text that should be displayed as the label.
     * @param locale The locale of the label text.
     * @param packageVersion The labeled application's package version code.
     * @param screenshotPath The local path to a screenshot of the labeled view.
     * @param timestamp The time the label was created or last modified.
     */
    constructor(
        packageName: String?,
        packageSignature: String?,
        viewName: String?,
        text: String?,
        locale: String?,
        packageVersion: Int,
        screenshotPath: String?,
        timestamp: Long
    ) : this(
        NO_ID, packageName, packageSignature, viewName, text, locale,
        packageVersion, screenshotPath, timestamp
    )

    /**
     * Creates a label object that exists in the local database from a label
     * object that does not exist in the local database using a deep copy.
     *
     * This effectively assigns an ID to an existing label.
     *
     * @param labelWithoutId The existing label to copy, without an ID.
     * @param labelId A unique local identifier for the label.
     */
    constructor(labelWithoutId: Label, labelId: Long) : this(
        labelId,
        labelWithoutId.packageName,
        labelWithoutId.packageSignature,
        labelWithoutId.viewName,
        labelWithoutId.text,
        labelWithoutId.locale,
        labelWithoutId.packageVersion,
        labelWithoutId.screenshotPath,
        labelWithoutId.timestamp
    ) {
        require(labelWithoutId.id == NO_ID) {
            "Label to copy cannot have an ID already assigned."
        }
    }

    /** @return A text representation of the object and its fields for debugging. */
    override fun toString(): String {
        return String.format(
            DEBUG_FORMAT_STRING,
            javaClass.simpleName, id, packageName, packageSignature, viewName,
            text, locale, packageVersion, screenshotPath, timestamp
        )
    }
}
