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

        /**
         * 解压完成标记：记录「哪一版 APK 解压过」。内容为
         * `versionCode:lastUpdateTime`；与当前 APK 一致且目标文件尺寸与原条目尺寸相等时，
         * 跳过重写。
         *
         * 没有这个标记时，每次 :service 进程冷启动都会把整套翻译表（实测 486 个文件、
         * 20 MB）重写一遍；模拟器高负载下一步能拖到一分钟以上，`TranslatorService.onInit`
         * 因此迟到，任何等它的调用方（触觉图封面 / 封底翻译）都会超时。表内容随 APK 版本
         * 固定，按版本跳过不改变输出字节。
         */
        private const val MARKER_NAME = ".extract-marker"

        /** 主线程回调；协程在 IO 线程跑完后切回来。 */
        private val mScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }

    private val mResources: Resources = context.applicationContext.resources
    private val mContext: Context = context.applicationContext
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
        Log.i(TAG, "解压任务提交 rawResId=$mRawResId out=$mOutput")
        mJob = mScope.launch {
            val result = withContext(Dispatchers.IO) { extractAllOrRollback() }
            Log.i(TAG, "解压任务完成 result=$result")
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
        val marker = File(mOutput, MARKER_NAME)
        val signature = currentSignature()
        val reuseExisting = signature.isNotEmpty() && marker.isFile &&
            runCatching { marker.readText().trim() }.getOrNull() == signature
        val stream: InputStream = mResources.openRawResource(mRawResId)
        val zipStream = ZipInputStream(BufferedInputStream(stream))
        var successful = false
        try {
            extractEntries(zipStream, reuseExisting)
            successful = true
            Log.i(TAG, "解压完成 reuseExisting=$reuseExisting 重写文件=${mExtractedFiles.size}")
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
        if (signature.isNotEmpty()) {
            runCatching { marker.writeText(signature) }
                .onFailure { Log.w(TAG, "写解压标记失败", it) }
        }
        return RESULT_OK
    }

    /** 当前 APK 的版本指纹；读不到返回空串，调用方按全量解压处理。 */
    private fun currentSignature(): String = try {
        val info = mContext.packageManager.getPackageInfo(mContext.packageName, 0)
        "${info.longVersionCode}:${info.lastUpdateTime}"
    } catch (e: Exception) {
        Log.w(TAG, "读取 APK 版本失败，按全量解压处理", e)
        ""
    }

    /**
     * @param reuseExisting 同一 APK 版本已解压过时置 true：目标文件存在且尺寸与原条目
     *   一致就不重写，只走 zip 迭代。任何尺寸不符或缺失的条目仍按原样写出。
     */
    private fun extractEntries(zipStream: ZipInputStream, reuseExisting: Boolean) {
        val buffer = ByteArray(10240)
        var bytesRead: Int
        while (!mCancelled) {
            val entry = zipStream.nextEntry ?: break
            val outputFile = File(mOutput, entry.name)
            if (entry.isDirectory) {
                outputFile.mkdirs()
                makeReadable(outputFile)
                continue
            }
            if (reuseExisting) {
                val intact = outputFile.isFile && entry.size >= 0 &&
                    outputFile.length() == entry.size
                if (intact) {
                    zipStream.closeEntry()
                    continue
                }
            }
            mExtractedFiles.add(outputFile)
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
