/*
 * Copyright (C) 2025 Google Inc.
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
package com.google.android.accessibility.braille.translate.liblouis

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import com.google.android.accessibility.braille.translate.R
import com.google.android.accessibility.braille.translate.TableLoader
import com.google.android.accessibility.utils.BuildVersionUtils
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** A singleton class that loads LibLouis tables to device storage. */
class LiblouisLoader private constructor(context: Context) : TableLoader {
  private val context: WeakReference<Context>
  private val ioExecutor: ExecutorService
  private val tablesDir: File

  @Volatile
  private var dataFileState = TableLoader.FileState.FILES_NOT_EXTRACTED

  init {
    var appContext = context
    this.context = WeakReference(appContext)
    ioExecutor = Executors.newSingleThreadExecutor()
    // Extract tables to device storage so we can read tables before device is unlocked after
    // reboot.
    if (BuildVersionUtils.isAtLeastN()) {
      appContext = ContextCompat.createDeviceProtectedStorageContext(appContext) ?: appContext
    }
    val customTablesDir = appContext.getExternalFilesDir(/* type= */ null)
    val customTablesSubDir = File(customTablesDir, "/liblouis/tables")
    val files = customTablesSubDir.listFiles()
    tablesDir =
        if (files != null && files.size != 0) {
          // For external developer to use custom tables.
          customTablesDir!!
        } else {
          appContext.getDir("translator", Context.MODE_PRIVATE)
        }
    if (files == null || files.size == 0) {
      ensureDataFiles()
    }
    LouisTranslation.setTablesDir(tablesDir.path)
  }

  override fun ensureDataFiles() {
    if (dataFileState != TableLoader.FileState.FILES_NOT_EXTRACTED) {
      return
    }
    if (BuildVersionUtils.isRobolectric()) {
      extractFiles()
    } else {
      ioExecutor.execute(this::extractFiles)
    }
  }

  override fun isExtracted(): Boolean {
    return dataFileState == TableLoader.FileState.FILES_EXTRACTED
  }

  private fun extractFiles() {
    val loaded =
        TranslateUtils.extractTables(
            context.get()!!.resources, R.raw.translationtables, tablesDir)
    if (loaded) {
      dataFileState = TableLoader.FileState.FILES_EXTRACTED
    } else {
      dataFileState = TableLoader.FileState.FILES_ERROR
    }
  }

  @VisibleForTesting
  fun reset() {
    instance = null
  }

  companion object {
    var instance: LiblouisLoader? = null

    @JvmStatic
    fun getInstance(context: Context): LiblouisLoader {
      if (instance == null) {
        instance = LiblouisLoader(context)
      }
      return instance!!
    }
  }
}
