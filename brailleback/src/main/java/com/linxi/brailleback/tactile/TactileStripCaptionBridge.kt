package com.linxi.brailleback.tactile

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.googlecode.eyesfree.braille.translate.BrailleTranslator
import com.googlecode.eyesfree.braille.translate.TranslatorClient
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 中文文案 → Unicode 盲文字符串 的 Android 桥。
 *
 * 翻译链路复用项目内既有引擎，不在本包里实现盲文表：[TranslatorClient] 绑定服务进程里的
 * TranslatorService；服务内部先用 BrailleSegmenter 做中文分词连写，再交 LibLouisWrapper
 * （liblouis 原生表）翻译。就绪后用同步 AIDL 入口 translate 取点位字节，再映射成
 * U+2800 起的盲文图案字符。
 *
 * 线程模型（2026-11-24 两处修正，对应 docs 75 / 76 的 P2-2）：
 *
 * 1) [TranslatorClient] 构造函数内部会 `Handler()`，**必须在有 Looper 的线程上创建**；
 *    在 `Dispatchers.IO` 上构造会抛 `Can't create handler ... Looper.prepare()`。
 *    旧实现直接在调用线程 new，导致任何多行三段式会话都报「图片解码失败」。
 * 2) TranslatorClient 的 onInit 回调会 `post` 到构造它的那条 Handler 线程上。
 *    旧实现让桥线程一边阻塞等 onInit、一边等这条线程分发 onInit，形成自锁，
 *    表现同样是 20 s 超时 → 封面降级。现在**等待移到调用线程**，桥线程只负责执行，
 *    分发路径始终畅通。
 *
 * 于是：[toBraille] / [close] 可在任意线程调用；客户端的创建、表查询、翻译、销毁全部在
 * 一条专用 [HandlerThread] 上完成，满足 TranslatorClient「创建与销毁同一线程」的约定。
 */
class TactileStripCaptionBridge(context: Context) {

    private val appContext: Context = context.applicationContext

    private val thread = HandlerThread(THREAD_NAME).apply { start() }

    private val threadHandler = Handler(thread.looper)

    /** 翻译服务初始化回调，只由桥线程触发；调用线程在此等待。 */
    private val initLatch = CountDownLatch(1)

    @Volatile
    private var initStatus = TranslatorClient.ERROR

    @Volatile
    private var initDone = false

    /** 以下两项只在桥线程读写。 */
    private var client: TranslatorClient? = null
    private var translator: BrailleTranslator? = null

    @Volatile
    private var closed = false

    init {
        // 客户端必须在有 Looper 的线程上构造，这里是唯一构造点。
        threadHandler.post {
            client = TranslatorClient(appContext, object : TranslatorClient.OnInitListener {
                override fun onInit(status: Int) {
                    initStatus = status
                    initDone = true
                    initLatch.countDown()
                    Log.i(LOG_TAG, "翻译服务初始化回调 status=$status")
                }
            })
        }
    }

    /**
     * 把一段中文翻译成盲文图案字符串（一方一个 U+2800..U+28FF 字符）。
     *
     * @throws IllegalStateException 翻译服务初始化超时 / 失败、翻译线程不在、或本次翻译
     *   返回空结果；消息里带原因，界面兜底文案之外还能在日志里定位。
     */
    fun toBraille(text: String): String {
        awaitInit()
        return runOnBridge("翻译「$text」") {
            ensureTranslator()
            val result = translator?.translate(text, -1, false)
                ?: throw IllegalStateException("盲文翻译失败：$text")
            buildString {
                for (b in result.cells) {
                    append((0x2800 + (b.toInt() and 0xFF)).toChar())
                }
            }
        }
    }

    /** 解绑翻译服务并退出桥线程；可在任意线程调用，重复调用无副作用。 */
    fun close() {
        if (closed) return
        closed = true
        try {
            runOnBridge("关闭翻译桥", allowClosed = true) {
                client?.destroy()
                client = null
                translator = null
            }
        } catch (e: IllegalStateException) {
            // 线程已退出或超时：客户端随之不再可用，不再等。
            Log.w(LOG_TAG, "关闭翻译桥时未能完成销毁：${e.message}")
        } finally {
            thread.quitSafely()
        }
    }

    /** 在调用线程等 onInit 回调；不占用桥线程，避免与回调分发自锁。 */
    private fun awaitInit() {
        if (initDone) return
        if (!initLatch.await(INIT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            throw IllegalStateException("翻译服务初始化超时（${INIT_TIMEOUT_MILLIS} ms）")
        }
        if (initStatus != TranslatorClient.SUCCESS) {
            throw IllegalStateException("翻译服务初始化失败（status=$initStatus）")
        }
    }

    /** 只在桥线程调用：解析表与翻译器，不等待、不阻塞。 */
    private fun ensureTranslator() {
        if (translator != null) return
        if (!initDone || initStatus != TranslatorClient.SUCCESS) {
            throw IllegalStateException("翻译服务尚未就绪")
        }
        val localClient = client ?: throw IllegalStateException("翻译客户端未创建")
        val tables = localClient.getTables()
            ?: throw IllegalStateException("翻译服务未返回盲文表清单")
        val lang = Locale.getDefault().language
        val picked = tables.firstOrNull { it.locale.language == lang } ?: tables.firstOrNull()
        val id = picked?.id ?: throw IllegalStateException("没有可用的盲文表")
        translator = localClient.getTranslator(id)
            ?: throw IllegalStateException("盲文表加载失败：$id")
        Log.i(LOG_TAG, "使用盲文表：$id")
    }

    /**
     * 把 [block] 投递到桥线程执行并同步取回结果；异常原样抛回调用线程，
     * 调用线程被中断时恢复中断标记后按超时处理。
     */
    private fun <T> runOnBridge(what: String, allowClosed: Boolean = false, block: () -> T): T {
        if (closed && !allowClosed) throw IllegalStateException("翻译桥已关闭，$what")
        val done = CountDownLatch(1)
        var value: T? = null
        var failure: Throwable? = null
        val posted = threadHandler.post {
            try {
                value = block()
            } catch (t: Throwable) {
                failure = t
            } finally {
                done.countDown()
            }
        }
        if (!posted) throw IllegalStateException("翻译桥线程已退出，$what")
        try {
            if (!done.await(TASK_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                throw IllegalStateException("$what 超时（${TASK_TIMEOUT_MILLIS} ms）")
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("$what 被中断", e)
        }
        failure?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    companion object {
        private const val LOG_TAG = "TactileStripCaptionBridge"
        private const val THREAD_NAME = "tactile-caption"
        // 翻译服务与宿主不在同一进程。实测（AVD api35，负载 ~10）:service 冷启动时
        // 解压翻译表 + 绑定到 onInit 约 13 s；APK 更新后首次必走全量解压，之后靠
        // ZipResourceExtractor 的版本标记跳过重写，降到 2 s 内。20 s 覆盖最坏情况。
        private const val INIT_TIMEOUT_MILLIS = 20_000L
        // 单次翻译上限；初始化已在调用线程完成，这里只兜住单条文案。
        private const val TASK_TIMEOUT_MILLIS = 5_000L
    }
}
