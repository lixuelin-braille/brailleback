package com.googlecode.eyesfree.braille.utils

import android.content.Context
import android.os.AsyncTask
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.LinkedList
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Extracts the contents of a raw resource, stored as a zip file, into a
 * directory specified by the caller.
 *
 * Instantiate your own subclass of this on the main application thread
 * and override [onPostExecute] to be notified when the extraction is done.
 */
// TODO: Add versioning support.
open class ZipResourceExtractor(context: Context, private val mRawResId: Int, private val mOutput: File) :
    AsyncTask<Void, Void, Int>() {
    companion object {
        const val RESULT_ERROR = -1
        const val RESULT_OK = 0
    }

    private val mContext: Context = context
    private val mExtractedFiles = LinkedList<File>()

    override fun doInBackground(vararg params: Void?): Int {
        val stream = mContext.resources.openRawResource(mRawResId)
        val zipStream = ZipInputStream(BufferedInputStream(stream))
        var successful = false
        try {
            extractEntries(zipStream)
            successful = true
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                zipStream.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
        if (isCancelled || !successful) {
            removeExtractedFiles()
            return RESULT_ERROR
        }
        return RESULT_OK
    }

    private fun extractEntries(zipStream: ZipInputStream) {
        val buffer = ByteArray(10240)
        var bytesRead: Int
        while (!isCancelled) {
            val entry = zipStream.nextEntry ?: break
            val outputFile = File(mOutput, entry.name)
            mExtractedFiles.add(outputFile)
            if (entry.isDirectory) {
                outputFile.mkdirs()
                makeReadable(outputFile)
                continue
            }
            outputFile.parentFile?.mkdirs()
            FileOutputStream(outputFile).use { outputStream ->
                while (!isCancelled) {
                    bytesRead = zipStream.read(buffer)
                    if (bytesRead == -1) break
                    outputStream.write(buffer, 0, bytesRead)
                }
            }
            zipStream.closeEntry()
            makeReadable(outputFile)
        }
    }

    private fun removeExtractedFiles() {
        for (extractedFile in mExtractedFiles) {
            if (!extractedFile.isDirectory) {
                extractedFile.delete()
            }
        }
        mExtractedFiles.clear()
    }

    private fun makeReadable(file: File) {
        if (!file.canRead()) {
            file.setReadable(true)
        }
    }

    /**
     * Removes children in [directory], but does not delete the directory itself.
     */
    private fun clearDirectory(directory: File) {
        if (!directory.exists() || !directory.isDirectory) {
            return
        }
        val children = directory.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) {
                clearDirectory(child)
            }
            child.delete()
        }
    }
}
