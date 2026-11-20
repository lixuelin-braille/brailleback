/*
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

package com.googlecode.eyesfree.labeling

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.googlecode.eyesfree.utils.LogUtils

/**
 * A table for storing custom TalkBack labels.
 */
class LabelsTable {
    companion object {
        const val TABLE_NAME = "labels"

        const val KEY_ID = "_id"
        const val KEY_PACKAGE_NAME = "packageName"
        const val KEY_PACKAGE_SIGNATURE = "packageSignature"
        const val KEY_VIEW_NAME = "viewName"
        const val KEY_TEXT = "text"
        const val KEY_LOCALE = "locale"
        const val KEY_PACKAGE_VERSION = "packageVersion"
        const val KEY_SCREENSHOT_PATH = "screenshotPath"
        const val KEY_TIMESTAMP = "timestamp"

        const val INDEX_ID = 0
        const val INDEX_PACKAGE_NAME = 1
        const val INDEX_PACKAGE_SIGNATURE = 2
        const val INDEX_VIEW_NAME = 3
        const val INDEX_TEXT = 4
        const val INDEX_LOCALE = 5
        const val INDEX_PACKAGE_VERSION = 6
        const val INDEX_SCREENSHOT_PATH = 7
        const val INDEX_TIMESTAMP = 8

        @JvmField
        val ALL_COLUMNS: Array<String> = arrayOf(
            KEY_ID, KEY_PACKAGE_NAME, KEY_PACKAGE_SIGNATURE, KEY_VIEW_NAME, KEY_TEXT, KEY_LOCALE,
            KEY_PACKAGE_VERSION, KEY_SCREENSHOT_PATH, KEY_TIMESTAMP
        )

        @JvmStatic
        fun onCreate(database: SQLiteDatabase) {
            LogUtils.log(
                LabelsTable::class.java, Log.INFO, "Creating table: %s.", TABLE_NAME
            )

            SQLiteTableBuilder(database, TABLE_NAME)
                .addColumn(KEY_ID, SQLiteTableBuilder.TYPE_INTEGER, true)
                .addColumn(KEY_PACKAGE_NAME, SQLiteTableBuilder.TYPE_TEXT)
                .addColumn(KEY_PACKAGE_SIGNATURE, SQLiteTableBuilder.TYPE_TEXT)
                .addColumn(KEY_VIEW_NAME, SQLiteTableBuilder.TYPE_TEXT)
                .addColumn(KEY_TEXT, SQLiteTableBuilder.TYPE_TEXT)
                .addColumn(KEY_LOCALE, SQLiteTableBuilder.TYPE_TEXT)
                .addColumn(KEY_PACKAGE_VERSION, SQLiteTableBuilder.TYPE_INTEGER)
                .addColumn(KEY_SCREENSHOT_PATH, SQLiteTableBuilder.TYPE_TEXT)
                .addColumn(KEY_TIMESTAMP, SQLiteTableBuilder.TYPE_INTEGER)
                .createTable()
        }

        @JvmStatic
        fun onUpgrade(database: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // DB version 2 adds KEY_PACKAGE_SIGNATURE column. Since this was added
            // prior to release, we'll just clear the table.
            if (oldVersion < 2) {
                LogUtils.log(
                    LabelsTable::class.java, Log.INFO,
                    "Dropping table %s to upgrade from version %d to version %d.", TABLE_NAME,
                    oldVersion, newVersion
                )
                database.execSQL(String.format("DROP TABLE IF EXISTS %s", TABLE_NAME))

                // Recreate table.
                onCreate(database)
            } else {
                throw UnsupportedOperationException(
                    "Attempted database upgrade from unsupported database version."
                )
            }
        }
    }
}
