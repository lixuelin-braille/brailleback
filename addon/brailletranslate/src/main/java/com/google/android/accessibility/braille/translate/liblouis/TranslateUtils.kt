/*
 * Copyright 2020 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.accessibility.braille.translate.liblouis

import android.content.res.Resources
import com.google.android.libraries.accessibility.utils.log.LogUtils
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/** Utils for translation. */
class TranslateUtils private constructor() {

  companion object {
    private const val TAG = "TranslateUtils"

    /**
     * Extracts the tables from the zip file.
     *
     * @param resources the resources
     * @param rawResId the raw resource id of the zip file
     * @param output the output directory
     * @return true if the extraction is successful, false otherwise
     */
    @JvmStatic
    fun extractTables(resources: Resources, rawResId: Int, output: File): Boolean {
      val extractedFiles: MutableList<File> = ArrayList()
      val stream: InputStream = resources.openRawResource(rawResId)
      val zipStream: ZipInputStream = ZipInputStream(BufferedInputStream(stream))
      try {
        extractEntries(zipStream, output, extractedFiles)
        return true
      } catch (e: Exception) {
        LogUtils.e(TAG, "Exception during extractEntries()", e)
        removeExtractedFiles(extractedFiles)
        return false
      } finally {
        try {
          zipStream.close()
        } catch (e: IOException) {
          LogUtils.e(TAG, "Exception during zipStream.close()", e)
        }
      }
    }

    @Throws(IOException::class)
    private fun extractEntries(
        zipStream: ZipInputStream, output: File, extractedFiles: MutableList<File>) {
      val buffer = ByteArray(10240)
      var bytesRead: Int
      while (true) {
        val entry = zipStream.nextEntry ?: break
        val outputFile = newFile(output, entry)
        extractedFiles.add(outputFile)
        if (entry.isDirectory) {
          outputFile.mkdirs()
          makeReadable(outputFile)
          continue
        }

        // Ensure the target path exists.
        outputFile.parentFile?.mkdirs()
        val outputStream = FileOutputStream(outputFile)

        while (true) {
          bytesRead = zipStream.read(buffer)
          if (bytesRead == -1) {
            break
          }
          outputStream.write(buffer, 0, bytesRead)
        }

        outputStream.close()
        zipStream.closeEntry()

        // Make sure the output file is readable.
        makeReadable(outputFile)
      }
    }

    private fun removeExtractedFiles(extractedFiles: MutableList<File>) {
      for (extractedFile in extractedFiles) {
        if (!extractedFile.isDirectory) {
          extractedFile.delete()
        }
      }

      extractedFiles.clear()
    }

    private fun makeReadable(file: File) {
      if (!file.canRead()) {
        file.setReadable(true)
      }
    }

    /**
     * Create a new File underneath a directory that corresponds to the relative path of a ZipEntry.
     *
     * @param parent the parent directory under which the zip file should be extracted
     * @param zipEntry the ZipEntry of the file that will be extracted
     * @return a new {@link File} below the {@code parent} directory
     * @throws ZipException if {@code zipEntry} will be outside of the parent directory due to path
     *     traversal
     * @throws IOException if unable to verify that the file will be under the parent directory
     */
    @Throws(IOException::class)
    private fun newFile(parent: File, zipEntry: ZipEntry): File {
      val name = zipEntry.name
      val f = File(parent, name)
      val canonicalPath = f.canonicalPath
      if (!canonicalPath.startsWith(parent.canonicalPath)) {
        throw ZipException("Illegal name: " + name)
      }
      return f
    }
  }
}
