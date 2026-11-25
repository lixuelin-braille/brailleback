/*
 * 桥接自带 TTS 与无障碍公告投递：从 BrailleImeHostBridge 拆出的语音闭环。
 *
 * 持有自带 TextToSpeech 引擎、待播队列与就绪标志；播报分流（外部读屏公告 / 自带 TTS）
 * 的调度入口仍留在主桥接，本类只负责引擎侧的说话、打断、释放与公告事件投递。
 * 方法体与原桥接内实现逐字一致，未改线程、队列长度与降级口径。
 */
package com.linxi.brailleback.ime

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.accessibilityservice.AccessibilityServiceInfo
import java.util.ArrayDeque
import java.util.Locale
import java.util.Queue

internal class BrailleImeHostSpeech(private val context: Context) {

  private var textToSpeech: TextToSpeech? = null
  private var ttsReady = false
  private val pendingUtterances: Queue<CharSequence> = ArrayDeque()

  fun initTts() {
    textToSpeech =
        TextToSpeech(
            context,
            TextToSpeech.OnInitListener { status ->
              val tts = textToSpeech
              if (status != TextToSpeech.SUCCESS || tts == null) {
                // 没有可用 TTS 引擎时静默降级：键盘仍可打字，只是没有字符语音反馈。
                ttsReady = false
                return@OnInitListener
              }
              val result = tts.setLanguage(Locale.getDefault())
              if (result == TextToSpeech.LANG_MISSING_DATA ||
                  result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts.setLanguage(Locale.ENGLISH)
              }
              tts.setOnUtteranceProgressListener(
                  object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}

                    override fun onDone(utteranceId: String?) {}

                    override fun onError(utteranceId: String?) {}
                  })
              ttsReady = true
              drainPendingUtterances()
            })
  }

  private fun drainPendingUtterances() {
    if (!ttsReady || textToSpeech == null) {
      return
    }
    var text: CharSequence? = pendingUtterances.poll()
    while (text != null) {
      speakNow(text, /* flush= */ false)
      text = pendingUtterances.poll()
    }
  }

  fun speakNow(text: CharSequence?, flush: Boolean) {
    if (text == null || text.length == 0) {
      return
    }
    if (!ttsReady || textToSpeech == null) {
      // 限制队列长度，避免 TTS 一直不可用时无限堆积。
      if (pendingUtterances.size < 8) {
        pendingUtterances.add(text)
      }
      return
    }
    // 属性不参与智能转换：这里再取一次，取不到就放弃这句朗读。
    val tts = textToSpeech ?: return
    val params = Bundle()
    tts.speak(
        text,
        if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
        params,
        BrailleImeHostBridge.TTS_UTTERANCE_ID)
  }

  /**
   * 以无障碍公告事件（TYPE_ANNOUNCEMENT）把文本交给读屏朗读。
   *
   * <p>此前用的是 {@code View.announceForAccessibility}，它必须通过视图的
   * {@code includeForAccessibility()} 判定：键盘容器只是个普通 FrameLayout，既不可点击也没有
   * live region，很多 ROM 会判定"对无障碍不重要"而把公告静默丢掉，表现就是"一部分内容读不出来"。
   * 改为 {@link AccessibilityManager#sendAccessibilityEvent} 直接投递，绕开视图重要度门槛。
   *
   * @return true 表示事件已投递成功（不代表读屏一定朗读）
   */
  fun sendAnnouncementEvent(text: CharSequence?): Boolean {
    if (text == null || text.length == 0) {
      return false
    }
    try {
      val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
      if (am == null || !am.isEnabled()) {
        return false
      }
      val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_ANNOUNCEMENT)
      event.setPackageName(context.getPackageName())
      event.setClassName(BrailleImeHostBridge::class.java.name)
      event.getText().add(text)
      // 该事件由系统回收，不要自行 recycle。
      am.sendAccessibilityEvent(event)
      return true
    } catch (t: Throwable) {
      Log.w(BrailleImeHostBridge.TAG, "sendAnnouncementEvent failed", t)
      return false
    }
  }

  /** 是否除本应用之外还有别的无障碍服务在跑（即存在读屏接管朗读）。 */
  fun hasExternalScreenReader(): Boolean {
    try {
      val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
      if (am == null || !am.isEnabled()) {
        return false
      }
      val services =
          am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
      for (info in services) {
        if (info.getResolveInfo() == null || info.getResolveInfo().serviceInfo == null) {
          continue
        }
        val pkg = info.getResolveInfo().serviceInfo.packageName
        if (pkg != null && pkg != context.getPackageName()) {
          return true
        }
      }
    } catch (e: RuntimeException) {
      Log.w(BrailleImeHostBridge.TAG, "hasExternalScreenReader failed", e)
    }
    return false
  }

  fun interrupt() {
    val tts = textToSpeech
    if (tts != null && ttsReady) {
      tts.stop()
    }
    pendingUtterances.clear()
  }

  fun shutdown() {
    val tts = textToSpeech
    if (tts != null) {
      tts.stop()
      tts.shutdown()
      textToSpeech = null
    }
  }
}
