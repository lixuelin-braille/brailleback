/*
 * Copyright 2026 The BrailleBack Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.linxi.brailleback.ime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.google.android.accessibility.braille.common.BrailleCommonTalkBackSpeaker
import java.util.Locale

/**
 * 独立输入法形态下的兜底播报。
 *
 * 正常情形下候选由读屏播报：无障碍服务把 `TalkBackForBrailleCommon` 注入
 * `BrailleCommonTalkBackSpeaker`，语音、打断、音效全由读屏统一管。但输入法是可以单独跑的
 * （不需要无障碍授权），那时桥是空的，`speak()` 直接把内容丢掉，盲人打盲文听不到候选。
 *
 * 这里注册成 `BrailleCommonTalkBackSpeaker` 的兜底：只在桥为空时被调用，所以读屏在跑时不会
 * 重复播报。用的是系统 TTS，不依赖任何第三方读屏的私有接口。
 */
object ImeSpeech : BrailleCommonTalkBackSpeaker.FallbackSpeaker {

    private const val TAG = "BrailleImeSpeech"

    private val handler = Handler(Looper.getMainLooper())

    private var tts: TextToSpeech? = null
    private var initializing = false
    private var ready = false

    /** 初始化完成前要说的话：TTS 冷启动要几百毫秒，先说的那句不能丢。 */
    private var pendingText: CharSequence? = null
    private var pendingDelayMs = 0

    /** 预热：在输入法创建时调用一次，让第一次播报不等引擎加载。 */
    fun warmUp(context: Context) {
        if (tts != null || initializing) {
            return
        }
        initializing = true
        val appContext = context.applicationContext
        tts = TextToSpeech(appContext) { status ->
            initializing = false
            if (status != TextToSpeech.SUCCESS) {
                Log.w(TAG, "TTS 初始化失败，status=$status")
                tts = null
                return@TextToSpeech
            }
            val engine = tts
            if (engine == null) {
                return@TextToSpeech
            }
            // 中文优先；系统没有中文语音包时退回默认语言，宁可音色不对也不能哑。
            val chinese = engine.setLanguage(Locale.SIMPLIFIED_CHINESE)
            if (chinese == TextToSpeech.LANG_MISSING_DATA ||
                chinese == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                Log.w(TAG, "系统缺少中文语音包，回退默认语言")
                engine.setLanguage(Locale.getDefault())
            }
            engine.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) = Unit

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        Log.w(TAG, "播报失败，utteranceId=$utteranceId")
                    }
                },
            )
            ready = true
            val queued = pendingText
            if (queued != null) {
                pendingText = null
                speak(queued, pendingDelayMs)
            }
        }
    }

    /** 撤销兜底，并释放 TTS（输入法销毁时调用）。 */
    fun shutdown() {
        BrailleCommonTalkBackSpeaker.setFallbackSpeaker(null)
        handler.removeCallbacksAndMessages(null)
        pendingText = null
        ready = false
        initializing = false
        tts?.run {
            stop()
            shutdown()
        }
        tts = null
    }

    override fun speak(text: CharSequence?, delayMs: Int) {
        if (text.isNullOrEmpty()) {
            return
        }
        // 引擎还没准备好：先记最后一句，等初始化完成再补播。连说多句时只留最后一句，
        // 避免用户听到一串过期内容。
        if (!ready) {
            pendingText = text
            pendingDelayMs = delayMs
            return
        }
        val runnable = Runnable { utter(text) }
        if (delayMs > 0) {
            handler.postDelayed(runnable, delayMs.toLong())
        } else {
            runnable.run()
        }
    }

    private fun utter(text: CharSequence) {
        val engine = tts ?: return
        // 候选切换、点位播报都是"最新一句才算数"，用 FLUSH 打断上一句，否则越排越滞后。
        engine.speak(text.toString(), TextToSpeech.QUEUE_FLUSH, null, TAG)
    }
}
