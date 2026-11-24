package com.linxi.brailleback.tactile

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/**
 * 为什么需要它：
 * 点显链路的入口层拿到的是相册 / 文件选择器回传的 [Uri]，而纯 JVM 内核只认 [GrayImage]。
 * 本类是 Uri → 灰度图 的组装点：解码与大小上限复用 [AndroidImageLoader]；
 * 二值化、切片、分页由上层 [ImageStripEncoder] / [TactileStripSlicer] 完成，本类不做版面决策。
 *
 * 失败策略：解码失败、图过大、Uri 不可读一律抛 [IllegalStateException] 并在消息里说明原因，
 * 不静默返回空列表——空结果会让上层以为「图是白的」，比直接报错更难排查。
 */
object TactileStripSource {

    /** 临时落盘文件前缀；解码完即删，不进缓存目录之外。 */
    private const val TEMP_PREFIX = "strip_source_"

    /**
     * 从 [uri] 读图并编码成旧版固定网格分页字节。保留给既有调用方；
     * 三段式会话请走 [loadGrayImage] + [TactileStripSlicer]。
     */
    fun load(context: Context, uri: Uri, spec: ImageStripEncoder.Spec): List<ByteArray> {
        val gray = loadGrayImage(context, uri)
        return ImageStripEncoder.encode(gray, spec)
    }

    /** 从 [uri] 解出灰度图；失败抛 [IllegalStateException]，不返回空壳。 */
    fun loadGrayImage(context: Context, uri: Uri): GrayImage {
        val tmp = File(context.cacheDir, TEMP_PREFIX + System.currentTimeMillis() + ".tmp")
        return try {
            copyToTemp(context, uri, tmp)
            try {
                AndroidImageLoader.load(tmp)
            } catch (e: IllegalArgumentException) {
                throw IllegalStateException("图片解码失败：${e.message}", e)
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /** 把 Uri 流整份复制到临时文件；拿不到流或复制中断都按「不可读」抛错。 */
    private fun copyToTemp(context: Context, uri: Uri, tmp: File) {
        val input = try {
            context.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            throw IllegalStateException("打开图片失败（权限不足或 provider 不可用）：$uri", e)
        } ?: throw IllegalStateException("无法打开图片 URI（返回空流）：$uri")

        try {
            input.use { stream ->
                FileOutputStream(tmp).use { out -> stream.copyTo(out, bufferSize = 8192) }
            }
        } catch (e: Exception) {
            throw IllegalStateException("读取图片内容失败（文件可能已被移动或损坏）：$uri", e)
        }
    }
}
