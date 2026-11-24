package com.linxi.brailleback.tactile

import android.content.Context
import android.util.Log
import com.googlecode.eyesfree.braille.translate.BrailleTranslator
import com.googlecode.eyesfree.braille.translate.TranslatorClient
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 中文文案 → Unicode 盲文字符串 的 Android 桥。
 *
 * 翻译链路复用项目内既有引擎，不在本包里实现盲文表：
 * [TranslatorClient] 绑定服务进程里的 TranslatorService；服务内部先用
 * BrailleSegmenter 做中文分词连写，再交 LibLouisWrapper（liblouis 原生表）翻译。
 * 本桥在构造时发起绑定，用 [latch] 等服务初始化回调；就绪后拿到的
 * [BrailleTranslator.translate] 是同步 AIDL 入口（与阅读器 DisplayContent 同一调用口径），
 * 直接返回 cells 点位字节，再由本类映射成 U+2800 起的盲文图案字符。
 *
 * 线程要求：TranslatorClient 约定「销毁与创建在同一线程」，因此 [build] / [close]
 * 必须在同一后台线程（会话层走 IO 线程），不要在主线程调用，避免服务初始化阻塞 UI。
 * 服务不可用或超时直接抛 [IllegalStateException]，不退回自造盲文。
 */
class TactileStripCaptionBridge(context: Context) {

    private val latch = CountDownLatch(1)

    @Volatile
    private var initStatus = TranslatorClient.ERROR

    private val client = TranslatorClient(context, object : TranslatorClient.OnInitListener {
        override fun onInit(status: Int) {
            initStatus = status
            latch.countDown()
        }
    })

    @Volatile
    private var translator: BrailleTranslator? = null

    /**
     * 把一段中文翻译成盲文图案字符串（一方一个 U+2800..U+28FF 字符）。
     *
     * @throws IllegalStateException 翻译服务初始化超时 / 失败，或本次翻译返回空结果。
     */
    fun toBraille(text: String): String {
        ensureReady()
        val result = translator?.translate(text, -1, false)
            ?: throw IllegalStateException("盲文翻译失败：$text")
        return buildString {
            for (b in result.cells) {
                append((0x2800 + (b.toInt() and 0xFF)).toChar())
            }
        }
    }

    /** 解绑翻译服务；必须与构造在同一线程。 */
    fun close() {
        client.destroy()
    }

    private fun ensureReady() {
        if (translator != null) return
        if (!latch.await(INIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            throw IllegalStateException("翻译服务初始化超时（${INIT_TIMEOUT_MILLIS} ms）")
        }
        if (initStatus != TranslatorClient.SUCCESS) {
            throw IllegalStateException("翻译服务初始化失败（status=$initStatus）")
        }
        val tables = client.getTables()
            ?: throw IllegalStateException("翻译服务未返回盲文表清单")
        val lang = Locale.getDefault().language
        val picked = tables.firstOrNull { it.locale.language == lang } ?: tables.firstOrNull()
        val id = picked?.id ?: throw IllegalStateException("没有可用的盲文表")
        translator = client.getTranslator(id)
            ?: throw IllegalStateException("盲文表加载失败：$id")
        Log.i(LOG_TAG, "使用盲文表：$id")
    }

    companion object {
        private const val LOG_TAG = "TactileStripCaptionBridge"
        private const val INIT_TIMEOUT_MILLIS = 3000L
    }
}
