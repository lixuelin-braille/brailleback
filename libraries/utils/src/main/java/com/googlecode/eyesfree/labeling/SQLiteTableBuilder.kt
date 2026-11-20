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

import android.database.SQLException
import android.database.sqlite.SQLiteDatabase
import android.text.TextUtils

/**
 * An object that builds and executes a query for creating a new SQLite table.
 */
class SQLiteTableBuilder(database: SQLiteDatabase, tableName: String) {
    companion object {
        private const val CREATE_TABLE = "CREATE TABLE "
        private const val OPEN_PAREN = "("
        private const val CLOSE_PAREN = ")"
        private const val COMMA = ", "
        private const val PRIMARY_KEY = " PRIMARY KEY"
        private const val INTEGER = " INTEGER"
        private const val REAL = " REAL"
        private const val TEXT = " TEXT"
        private const val BLOB = " BLOB"
        private const val REGEX_SQL_IDENTIFIER = "^[a-zA-Z_][a-zA-Z0-9_]*$"

        const val TYPE_INTEGER: Int = 1
        const val TYPE_REAL: Int = 2
        const val TYPE_TEXT: Int = 3
        const val TYPE_BLOB: Int = 4
    }

    private val mStringBuilder: StringBuilder
    private val mDatabase: SQLiteDatabase
    private var mHasColumns = false
    private var mCreated = false

    init {
        if (database == null) {
            throw IllegalArgumentException("Database cannot be null.")
        } else if (TextUtils.isEmpty(tableName)) {
            throw IllegalArgumentException("Table name cannot be empty.")
        } else if (!tableName.matches(REGEX_SQL_IDENTIFIER.toRegex())) {
            throw IllegalArgumentException("Invalid table name.")
        }

        mDatabase = database
        mStringBuilder = StringBuilder()
        mStringBuilder.append(CREATE_TABLE)
        mStringBuilder.append(tableName)
        mStringBuilder.append(OPEN_PAREN)
    }

    fun addColumn(columnName: String, type: Int): SQLiteTableBuilder = addColumn(columnName, type, false)

    fun addColumn(columnName: String, type: Int, primaryKey: Boolean): SQLiteTableBuilder {
        if (TextUtils.isEmpty(columnName)) {
            throw IllegalArgumentException("Column name cannot be empty.")
        } else if (!columnName.matches(REGEX_SQL_IDENTIFIER.toRegex())) {
            throw IllegalArgumentException("Invalid column name.")
        }

        if (mHasColumns) {
            mStringBuilder.append(COMMA)
        }

        mStringBuilder.append(columnName)
        appendType(type)
        if (primaryKey) {
            mStringBuilder.append(PRIMARY_KEY)
        }

        mHasColumns = true
        return this
    }

    private fun appendType(type: Int) {
        when (type) {
            TYPE_INTEGER -> mStringBuilder.append(INTEGER)
            TYPE_REAL -> mStringBuilder.append(REAL)
            TYPE_TEXT -> mStringBuilder.append(TEXT)
            TYPE_BLOB -> mStringBuilder.append(BLOB)
            else -> throw IllegalArgumentException("Unrecognized data type.")
        }
    }

    fun buildQueryString(): String = String.format("%s%s", mStringBuilder.toString(), CLOSE_PAREN)

    @Throws(SQLException::class)
    fun createTable() {
        if (mCreated) {
            throw IllegalStateException("createTable was already called on this instance.")
        }
        mDatabase.execSQL(buildQueryString())
        mCreated = true
    }
}
