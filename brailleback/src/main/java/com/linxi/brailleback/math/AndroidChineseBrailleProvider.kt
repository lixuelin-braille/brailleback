/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * Android 侧 <mtext> 中文提供者：复用项目既有 TranslatorService 桥
 * （[com.linxi.brailleback.tactile.TactileStripCaptionBridge]，内部走 BrailleSegmenter
 *  + LibLouisWrapper 文学表），不在数学包里自造中文字表。
 *
 * 线程约定（沿用桥接类说明）：桥的创建与销毁必须在同一后台线程。本类由 Activity 在
 * IO 协程里懒建，onDestroy 也切回 IO 线程关闭；翻译失败返回空串，由编码器按未知字符
 * 走数学表回退，不抛到界面层。
 */

package com.linxi.brailleback.math

import android.content.Context
import com.linxi.brailleback.tactile.TactileStripCaptionBridge

internal class AndroidChineseBrailleProvider(context: Context) : ChineseBrailleProvider {

    private val appContext = context.applicationContext

    @Volatile
    private var bridge: TactileStripCaptionBridge? = null

    override fun translateToUnicode(text: String): String {
        return try {
            val b = bridge ?: TactileStripCaptionBridge(appContext).also { bridge = it }
            b.toBraille(text)
        } catch (e: Exception) {
            // 服务不可用 / 超时 / 空结果：交回编码器逐字符回退，界面不因此中断。
            ""
        }
    }

    /** 必须与桥的创建在同一后台线程调用。 */
    fun close() {
        try {
            bridge?.close()
        } catch (_: Exception) {
            // 解绑失败只留痕，不阻断销毁。
        }
        bridge = null
    }
}
