package com.googlecode.eyesfree.braille.utils

import android.content.Context
import android.content.res.Resources
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
 * 后台执行由协程的 IO 调度器承担（原来用 AsyncTask）；子类覆写
 * [onPostExecute] 时仍在主线程收到结果，签名保持 `Int?` 以便
 * `TranslatorService` 那端的 Java 子类继续以 `Integer` 覆写。
 */
// TODO: Add versioning support.
open class ZipResourceExtractor(
    context: Context,
    private val mRawResId: Int,
    private val mOutput: File,
) {
    companion object {
        const val RESULT_ERROR = -1
        const val RESULT_OK = 0

        private const val TAG = "ZipResourceExtractor"

        /** 主线程回调；协程在 IO 线程跑完后切回来。 */
        private val mScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }

    private val mResources: Resources = context.applicationContext.resources
    private val mExtractedFiles = LinkedList<File>()

    /** 只做标记用：解压循环读它来决定是否提前收手并回滚。 */
    @Volatile
    private var mCancelled = false

    private var mJob: Job? = null

    /**
     * 开始解压。语义对齐旧的 `AsyncTask.execute()`：
     * 立刻返回、在主线程回调一次、重复调用视为编码错误。
     */
    fun execute() {
        check(mJob == null) { "ZipResourceExtractor 只能执行一次" }
        mJob = mScope.launch {
            val result = withContext(Dispatchers.IO) { extractAllOrRollback() }
            onPostExecute(result)
        }
    }

    /** 请求中止；解压线程会在下一个检查点停手并删除已写出的文件。 */
    fun cancel() {
        mCancelled = true
        mJob?.cancel()
    }

    /**
     * 解压结束的回调，必在主线程执行。子类可覆写。
     *
     * 参数用可空 `Int`（即 Java 的 `Integer`），保持与 Java 子类的覆写签名一致。
     */
    protected open fun onPostExecute(result: Int?) {}

    private fun extractAllOrRollback(): Int {
        val stream: InputStream = mResources.openRawResource(mRawResId)
        val zipStream = ZipInputStream(BufferedInputStream(stream))
        var successful = false
        try {
            extractEntries(zipStream)
            successful = true
        } catch (e: Exception) {
            Log.e(TAG, "解压资源失败: rawResId=$mRawResId", e)
        } finally {
            try {
                zipStream.close()
            } catch (e: IOException) {
                Log.e(TAG, "关闭 zip 流失败", e)
            }
        }
        if (mCancelled || !successful) {
            removeExtractedFiles()
            return RESULT_ERROR
        }
        return RESULT_OK
    }

    private fun extractEntries(zipStream: ZipInputStream) {
        val buffer = ByteArray(10240)
        var bytesRead: Int
        while (!mCancelled) {
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
                while (!mCancelled) {
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
