/*
 * 移植对接层：把 BrailleBack 的能力适配成 TalkBack 盲文键盘期望的接口。
 *
 * 背景：TalkBack 的 brailleime 通过 TalkBackForBrailleIme / TalkBackForBrailleCommon
 * 两个接口访问宿主。原实现位于 TalkBack 主模块（TalkBackService），依赖 TalkBack 的
 * 语音、焦点、粒度等子系统。BrailleBack 没有这些子系统，因此这里做等价（部分降级）实现：
 *
 *   - 语音播报：BrailleBack 自身不发声（设计上依赖外部读屏），这里用系统 TextToSpeech
 *     补上字符反馈，TTS 初始化失败时静默降级，不阻塞键盘。
 *   - 音效反馈：复用 tbcompat 的 FeedbackController（SoundPool）。
 *   - 焦点查找：复用 tbcompat 的 FocusFinder（基于本 AccessibilityService）。
 *   - 全局动作：直接转发 performGlobalAction。
 *   - 逐项导航 / 网络浏览等 TalkBack 专有能力：返回 false，键盘侧会播报失败提示。
 */

package com.googlecode.eyesfree.brailleback.ime

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.graphics.Region
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.braille.interfaces.BrailleImeForTalkBack
import com.google.android.accessibility.braille.interfaces.ScreenReaderActionPerformer.ScreenReaderAction
import com.google.android.accessibility.braille.interfaces.TalkBackForBrailleCommon
import com.google.android.accessibility.braille.interfaces.TalkBackForBrailleIme
import com.google.android.accessibility.braille.interfaces.TalkBackForBrailleIme.BrailleImeForTalkBackProvider
import com.google.android.accessibility.braille.interfaces.TalkBackForBrailleIme.ServiceStatus
import com.google.android.accessibility.utils.FocusFinder
import com.google.android.accessibility.utils.input.CursorGranularity
import com.google.android.accessibility.utils.output.FeedbackController
import com.google.android.accessibility.utils.output.SpeechController
import com.google.android.accessibility.utils.output.SpeechController.SpeakOptions
import com.googlecode.eyesfree.brailleback.BrailleBackService
import java.util.ArrayDeque
import java.util.Locale
import java.util.Queue

/** BrailleBack 侧的 TalkBack 桥接实现。 */
class BrailleImeHostBridge(private val service: BrailleBackService) :
    TalkBackForBrailleIme, TalkBackForBrailleCommon {

  private val context: Context = service.getApplicationContext()
  private val focusFinder: FocusFinder = FocusFinder(service)
  private val feedbackController: FeedbackController = FeedbackController(context)
  private val mainHandler: android.os.Handler =
      android.os.Handler(android.os.Looper.getMainLooper())

  private var brailleImeForTalkBack: BrailleImeForTalkBack? = null

  /** 直通区是否处于已挂载状态（用于兜底清理与延迟任务判定）。 */
  private var passthroughActive = false

  /** 宿主提供的键盘容器视图，用于把播报内容转成读屏可读的无障碍公告。 */
  private var announcementView: View? = null

  private var textToSpeech: TextToSpeech? = null
  private var ttsReady = false
  private val pendingUtterances: Queue<CharSequence> = ArrayDeque()

  private var readingGranularity: ReadingGranularity = ReadingGranularity.CHARACTER

  init {
    initTts()
  }

  private fun initTts() {
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

  private fun speakNow(text: CharSequence?, flush: Boolean) {
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
    val params = Bundle()
    textToSpeech!!.speak(
        text,
        if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
        params,
        TTS_UTTERANCE_ID)
  }

  // ---------------------------------------------------------------------------------------
  // TalkBackForBrailleCommon
  // ---------------------------------------------------------------------------------------

  override fun speak(charSequence: CharSequence?, delayMs: Int, speakOptions: SpeakOptions?) {
    val flush =
        speakOptions != null && speakOptions.mQueueMode == SpeechController.QUEUE_MODE_INTERRUPT
    // 有外部读屏在跑时，把播报内容以无障碍公告（TYPE_ANNOUNCEMENT）交给读屏朗读：
    // 这样语音库、语速、音量全部跟读屏一致，不会出现"读屏读一段、自带 TTS 读另一段"。
    val view = announcementView
    val viaAnnouncement = hasExternalScreenReader() && view != null
    Log.d(
        "BBSpeech",
        "speak text=" + charSequence + " delay=" + delayMs +
            " via=" + (if (viaAnnouncement) "announcement" else "tts"))
    if (viaAnnouncement) {
      val announce = Runnable {
        if (sendAnnouncementEvent(charSequence)) {
          return@Runnable
        }
        speakNow(charSequence, flush)
      }
      if (delayMs > 0) {
        mainHandler.postDelayed(announce, delayMs.toLong())
      } else {
        view!!.post(announce)
      }
    } else if (delayMs > 0) {
      mainHandler.postDelayed(Runnable { speakNow(charSequence, flush) }, delayMs.toLong())
    } else {
      speakNow(charSequence, flush)
    }
    if (speakOptions != null && speakOptions.mCompletedAction != null) {
      // 简化：不追踪读屏朗读完成事件，直接回调成功状态。
      speakOptions.mCompletedAction!!.run(/* status= */ 0)
    }
  }

  override fun setAnnouncementView(view: View?) {
    this.announcementView = view
    if (view != null) {
      // 仅作兜底：真正的公告改由 AccessibilityManager 直接投递，不再依赖视图的无障碍重要度。
      view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES)
    }
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
  private fun sendAnnouncementEvent(text: CharSequence?): Boolean {
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
      Log.w(TAG, "sendAnnouncementEvent failed", t)
      return false
    }
  }

  override fun getFeedBackController(): FeedbackController {
    return feedbackController
  }

  // ---------------------------------------------------------------------------------------
  // TalkBackForBrailleIme
  // ---------------------------------------------------------------------------------------

  override fun performAction(action: ScreenReaderAction, vararg arg: Any?): Boolean {
    return when (action) {
      // ---------------- 全局动作：直接转系统 ----------------
      ScreenReaderAction.GLOBAL_HOME ->
          service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
      ScreenReaderAction.GLOBAL_BACK ->
          service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
      ScreenReaderAction.GLOBAL_RECENTS ->
          service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
      ScreenReaderAction.GLOBAL_NOTIFICATIONS ->
          service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
      ScreenReaderAction.GLOBAL_QUICK_SETTINGS ->
          service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)

      // ---------------- 节点动作 ----------------
      ScreenReaderAction.CLICK_CURRENT, ScreenReaderAction.CLICK_NODE -> clickFocusedNode()
      ScreenReaderAction.LONG_CLICK_CURRENT, ScreenReaderAction.LONG_CLICK_NODE ->
          longClickFocusedNode()
      ScreenReaderAction.SCROLL_FORWARD ->
          scrollFocusedNode(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
      ScreenReaderAction.SCROLL_BACKWARD ->
          scrollFocusedNode(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)

      // ---------------- 光标按粒度移动 ----------------
      // 这三个动作以前一律返回 false，等于"逐字符/逐词/逐行"三组手势在帮助里有、按了没反应。
      // 现在改为对聚焦文本框下发 ACTION_(NEXT|PREVIOUS)_AT_MOVEMENT_GRANULARITY，
      // 由系统自己按字符/词/行切分，中文按词切分也由系统词典负责，比我们手写更可靠。
      ScreenReaderAction.FOCUS_NEXT_CHARACTER ->
          moveByGranularity(ReadingGranularity.CHARACTER, /* forward= */ true, NO_EXTEND)
      ScreenReaderAction.FOCUS_PREVIOUS_CHARACTER ->
          moveByGranularity(ReadingGranularity.CHARACTER, /* forward= */ false, NO_EXTEND)
      ScreenReaderAction.FOCUS_NEXT_WORD ->
          moveByGranularity(ReadingGranularity.WORD, /* forward= */ true, NO_EXTEND)
      ScreenReaderAction.FOCUS_PREVIOUS_WORD ->
          moveByGranularity(ReadingGranularity.WORD, /* forward= */ false, NO_EXTEND)
      ScreenReaderAction.FOCUS_NEXT_LINE ->
          moveByGranularity(ReadingGranularity.LINE, /* forward= */ true, NO_EXTEND)
      ScreenReaderAction.FOCUS_PREVIOUS_LINE ->
          moveByGranularity(ReadingGranularity.LINE, /* forward= */ false, NO_EXTEND)
      ScreenReaderAction.NAVIGATE_BY_READING_GRANULARITY_OR_ADJUST_READING_CONTROL_FORWARD ->
          moveByGranularity(readingGranularity, /* forward= */ true, NO_EXTEND)
      ScreenReaderAction.NAVIGATE_BY_READING_GRANULARITY_OR_ADJUST_READING_CONTROL_BACKWARD ->
          moveByGranularity(readingGranularity, /* forward= */ false, NO_EXTEND)

      // 3 指左右滑切换"阅读粒度"：原来空实现，现在真正维护一个粒度状态并播报当前档位。
      ScreenReaderAction.NEXT_READING_CONTROL -> cycleReadingGranularity(/* forward= */ true)
      ScreenReaderAction.PREVIOUS_READING_CONTROL -> cycleReadingGranularity(/* forward= */ false)

      // ---------------- 页首 / 页尾 ----------------
      ScreenReaderAction.CURSOR_TO_BEGINNING -> selectRange(/* start= */ 0, /* end= */ 0)
      ScreenReaderAction.CURSOR_TO_END ->
          selectRange(/* start= */ NO_POSITION, /* end= */ NO_POSITION)

      // ---------------- 选择 ----------------
      ScreenReaderAction.SELECT_NEXT_CHARACTER ->
          moveByGranularity(ReadingGranularity.CHARACTER, /* forward= */ true, EXTEND)
      ScreenReaderAction.SELECT_PREVIOUS_CHARACTER ->
          moveByGranularity(ReadingGranularity.CHARACTER, /* forward= */ false, EXTEND)
      ScreenReaderAction.SELECT_NEXT_WORD ->
          moveByGranularity(ReadingGranularity.WORD, /* forward= */ true, EXTEND)
      ScreenReaderAction.SELECT_PREVIOUS_WORD ->
          moveByGranularity(ReadingGranularity.WORD, /* forward= */ false, EXTEND)
      ScreenReaderAction.SELECT_NEXT_LINE ->
          moveByGranularity(ReadingGranularity.LINE, /* forward= */ true, EXTEND)
      ScreenReaderAction.SELECT_PREVIOUS_LINE ->
          moveByGranularity(ReadingGranularity.LINE, /* forward= */ false, EXTEND)
      ScreenReaderAction.SELECT_ALL -> selectAll()
      ScreenReaderAction.SELECT_CURRENT_TO_START -> selectCurrentTo(/* toStart= */ true)
      ScreenReaderAction.SELECT_CURRENT_TO_END -> selectCurrentTo(/* toStart= */ false)

      // ---------------- 剪贴板 ----------------
      ScreenReaderAction.COPY -> performNodeAction(AccessibilityNodeInfo.ACTION_COPY)
      ScreenReaderAction.CUT -> performNodeAction(AccessibilityNodeInfo.ACTION_CUT)
      ScreenReaderAction.PASTE -> performNodeAction(AccessibilityNodeInfo.ACTION_PASTE)

      else -> {
        // 其余动作属于 TalkBack 的导航/网络浏览体系，BrailleBack 无对应实现。
        false
      }
    }
  }

  // ---------------------------------------------------------------------------------------
  // 文本导航 / 选择 / 剪贴板：全部走 AccessibilityNodeInfo 的标准动作，不依赖 TalkBack 的
  // 导航子系统。这些方法覆盖了盲文键盘帮助里"光标移动""文本选择与编辑"两大类的全部手势。
  // ---------------------------------------------------------------------------------------

  /** 阅读粒度。与 TalkBack 的 3 指左右滑切换对应。 */
  private enum class ReadingGranularity(val frameworkValue: Int) {
    CHARACTER(AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_CHARACTER),
    WORD(AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_WORD),
    LINE(AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_LINE),
    PARAGRAPH(AccessibilityNodeInfoCompat.MOVEMENT_GRANULARITY_PARAGRAPH)
  }

  /**
   * 按粒度移动光标或扩展选区。
   *
   * @param forward true 向后（朝文本末尾），false 向前
   * @param extend true 时保持起点、扩展选区（选择类手势），false 只移动光标
   */
  private fun moveByGranularity(
      granularity: ReadingGranularity,
      forward: Boolean,
      extend: Boolean
  ): Boolean {
    val node = findTextNode()
    if (node == null) {
      return false
    }
    val args = Bundle()
    args.putInt(
        AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT,
        granularity.frameworkValue)
    if (extend) {
      args.putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, true)
    }
    val action =
        if (forward) AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY
        else AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY
    return node.performAction(action, args)
  }

  /** 切换阅读粒度并播报当前档位，返回是否切换成功。 */
  private fun cycleReadingGranularity(forward: Boolean): Boolean {
    val values = ReadingGranularity.values()
    var index = readingGranularity.ordinal + (if (forward) 1 else -1)
    if (index < 0) {
      index = values.size - 1
    } else if (index >= values.size) {
      index = 0
    }
    readingGranularity = values[index]
    announce(context.getString(granularityAnnouncementRes(readingGranularity)))
    return true
  }

  /** 全选。 */
  private fun selectAll(): Boolean {
    val node = findTextNode()
    if (node == null || node.getText() == null) {
      return false
    }
    return setSelection(node, 0, node.getText().length)
  }

  /**
   * 选择区间。
   *
   * @param start NO_POSITION 表示沿用当前选区起点，end NO_POSITION 表示到文本末尾
   */
  private fun selectRange(start: Int, end: Int): Boolean {
    val node = findTextNode()
    if (node == null) {
      return false
    }
    val text = node.getText()
    val length = if (text == null) 0 else text.length
    val resolvedStart = if (start == NO_POSITION) 0 else start
    val resolvedEnd = if (end == NO_POSITION) length else end
    return setSelection(node, resolvedStart, resolvedEnd)
  }

  /** 从当前光标/选区扩展到文本开头或末尾。 */
  private fun selectCurrentTo(toStart: Boolean): Boolean {
    val node = findTextNode()
    if (node == null) {
      return false
    }
    val text = node.getText()
    val length = if (text == null) 0 else text.length
    var selStart = node.getTextSelectionStart()
    var selEnd = node.getTextSelectionEnd()
    if (selStart < 0) {
      selStart = 0
    }
    if (selEnd < 0) {
      selEnd = selStart
    }
    // 以"离目标更远的那一端"为基准扩展，保证从任意方向都能一次到位。
    if (toStart) {
      return setSelection(node, 0, Math.max(selStart, selEnd))
    }
    return setSelection(node, Math.min(selStart, selEnd), length)
  }

  private fun setSelection(node: AccessibilityNodeInfoCompat, start: Int, end: Int): Boolean {
    val args = Bundle()
    args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, Math.max(0, start))
    args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, Math.max(0, end))
    return node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
  }

  private fun performNodeAction(action: Int): Boolean {
    val node = findTextNode()
    return node != null && node.performAction(action)
  }

  /** 找到当前可操作的文本框：优先输入焦点，其次无障碍焦点，最后在活跃窗口里搜第一个可编辑节点。 */
  private fun findTextNode(): AccessibilityNodeInfoCompat? {
    var node = focusFinder.findFocusCompat(AccessibilityNodeInfo.FOCUS_INPUT)
    if (isTextNode(node)) {
      return node
    }
    node = focusFinder.findAccessibilityFocus()
    if (isTextNode(node)) {
      return node
    }
    try {
      val root = service.getRootInActiveWindow()
      if (root == null) {
        return null
      }
      return findEditableDescendant(AccessibilityNodeInfoCompat.wrap(root), /* depth= */ 0)
    } catch (e: RuntimeException) {
      Log.w(TAG, "findTextNode failed", e)
      return null
    }
  }

  /** 播报一段文字：有外部读屏时走无障碍公告，否则用自带 TTS。 */
  private fun announce(text: CharSequence) {
    speak(text, /* delayMs= */ 0, /* speakOptions= */ null)
  }

  private fun clickFocusedNode(): Boolean {
    var node = focusFinder.findAccessibilityFocus()
    if (node == null) {
      node = focusFinder.findFocusCompat(AccessibilityNodeInfo.FOCUS_INPUT)
    }
    return node != null && node.performAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
  }

  private fun longClickFocusedNode(): Boolean {
    val node = focusFinder.findAccessibilityFocus()
    return node != null && node.performAction(AccessibilityNodeInfoCompat.ACTION_LONG_CLICK)
  }

  private fun scrollFocusedNode(action: Int): Boolean {
    val node = focusFinder.findAccessibilityFocus()
    return node != null && node.performAction(action)
  }

  override fun onBrailleImeActivated(
      disableEbt: Boolean,
      usePassThrough: Boolean,
      passThroughRegion: Region?
  ) {
    // 【严禁在此调用 brailleImeForTalkBack.onTalkBackResumed()】
    //
    // 事故记录：早期实现调用了它，导致切换本键盘立刻栈溢出闪退。原因是这个回调本身就在
    // 视图创建的调用链上：
    //
    //   createViewContainerAndAddView -> createAndAddTutorialView
    //     -> KeyboardView 添加视图 -> onGlobalLayout -> onViewReady
    //       -> activateIfNeeded -> activateBrailleIme -> onBrailleImeActivated（本方法）
    //         -> onTalkBackResumed -> createViewContainerAndAddView（回到起点，无限递归）
    //
    // 原版 TalkBack 之所以安全，是因为它的 onTalkBackResumed 由读屏自身的 resume 生命周期
    // 驱动，与本回调无关。BrailleBack 不暂停读屏、也不销毁键盘视图，因此恢复视图这件事
    // 根本不需要发生，这里不碰视图。
    Log.i(
        TAG,
        "onBrailleImeActivated disableEbt=" + disableEbt +
            " usePassThrough=" + usePassThrough +
            " region=" + passThroughRegion)
    // 单指手势被读屏的触摸探索吞掉，导致只能双指输入——这里把键盘区域登记为"触摸探索直通区"，
    // 让原始 MotionEvent 直接下发到键盘。
    applyTouchExplorationPassthrough(usePassThrough, passThroughRegion)
  }

  override fun onBrailleImeInactivated(usePassThrough: Boolean) {
    clearTouchExplorationPassthrough()
    val ime = brailleImeForTalkBack
    if (ime != null) {
      ime.onTalkBackSuspended()
    }
  }

  // ---------------------------------------------------------------------------------------
  // 触摸探索直通（解决"单指被读屏吞掉、必须双指才能输入"）
  // ---------------------------------------------------------------------------------------

  /**
   * 把指定区域标记为触摸探索直通区。
   *
   * <p>背景与取舍：盲文键盘需要拿到原始 MotionEvent（每个手指的按下/抬起），而触摸探索一旦
   * 生效，单指事件会被无障碍输入过滤器改写成 hover/探索手势，键盘收不到。TalkBack 在
   * Android 11+ 的标准解法就是 {@code setTouchExplorationPassthroughRegion}：把键盘所在
   * 区域从探索拦截中白名单出去，区域之外的探索行为不受影响。
   *
   * <p>该 API 要求调用方自己的服务信息带 {@code FLAG_REQUEST_TOUCH_EXPLORATION_MODE}，
   * 否则系统直接忽略本次调用。这个 flag 只在键盘弹起期间临时打开（见
   * {@link #setTouchExplorationRequested}），键盘收起立刻撤销，避免 BrailleBack 长时间
   * 抢占"触摸探索提供者"的角色。
   */
  private fun applyTouchExplorationPassthrough(
      usePassThrough: Boolean,
      passThroughRegion: Region?
  ) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
      // Android 10 及以下没有该 API，只能依赖键盘的 overlay 策略 + 关闭探索（做不到）。
      Log.i(TAG, "applyPassthrough: skipped, sdk=" + Build.VERSION.SDK_INT)
      return
    }
    setTouchExplorationRequested(true)
    val target: Region
    if (usePassThrough && passThroughRegion != null && !passThroughRegion.isEmpty()) {
      // 键盘用的是"输入法自带窗口"策略，上报的就是真正可触摸的输入视图区域，照用即可。
      target = Region(passThroughRegion)
    } else {
      // 键盘用的是 overlay 策略（Utils.useImeSuppliedInputWindow() 返回 false）。
      // 此时上报的 imeInputView 只是个 0 高度占位视图，实测拿到的是 SkRegion((0,0,1080,133))，
      // 直接用它会让直通区只剩屏幕顶上一条，手指真正摸的点阵区域仍在探索拦截之下。
      // 真正画点阵的 overlay 窗口是铺满整个 display 的（FLAG_LAYOUT_IN_SCREEN|NO_LIMITS），
      // 所以这里退回整屏。
      target = obtainDisplayRegion()
    }
    passthroughActive = true
    // setServiceInfo 到系统侧生效是异步的，立即设置直通区可能被判定为"未请求探索"而丢弃。
    mainHandler.postDelayed(
        Runnable {
          if (!passthroughActive) {
            return@Runnable
          }
          try {
            service.setTouchExplorationPassthroughRegion(Display.DEFAULT_DISPLAY, target)
            Log.i(TAG, "passthrough applied: " + target)
          } catch (t: Throwable) {
            Log.e(TAG, "passthrough failed", t)
          }
        },
        PASSTHROUGH_APPLY_DELAY_MS)
  }

  private fun clearTouchExplorationPassthrough() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
      return
    }
    passthroughActive = false
    try {
      // 空 Region 表示"没有直通区"，即恢复正常的触摸探索拦截。
      service.setTouchExplorationPassthroughRegion(Display.DEFAULT_DISPLAY, Region())
      Log.i(TAG, "passthrough cleared")
    } catch (t: Throwable) {
      Log.e(TAG, "clear passthrough failed", t)
    }
    setTouchExplorationRequested(false)
  }

  /** 临时开关本服务的"请求触摸探索"标志位（仅在本服务信息副本上修改并回设）。 */
  private fun setTouchExplorationRequested(requested: Boolean) {
    val info = service.getServiceInfo()
    if (info == null) {
      return
    }
    val current =
        (info.flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE) != 0
    if (current == requested) {
      return
    }
    if (requested) {
      info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
    } else {
      info.flags =
          info.flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE.inv()
    }
    try {
      service.setServiceInfo(info)
      Log.i(TAG, "touchExplorationRequested=" + requested + " flags=" + info.flags)
    } catch (t: Throwable) {
      Log.e(TAG, "setServiceInfo failed", t)
    }
  }

  private fun obtainDisplayRegion(): Region {
    val metrics = DisplayMetrics()
    val display = getWindowManager().getDefaultDisplay()
    display.getRealMetrics(metrics)
    return Region(0, 0, metrics.widthPixels, metrics.heightPixels)
  }

  /** 供宿主在服务销毁/异常时兜底清理，避免直通区残留导致读屏整屏失效。 */
  fun forceClearPassthrough() {
    if (passthroughActive) {
      clearTouchExplorationPassthrough()
    }
  }

  override fun setInputMethodEnabled(): Boolean {
    // 普通应用无权写 ENABLED_INPUT_METHODS（需要 WRITE_SECURE_SETTINGS），
    // 因此这里只报告失败，由键盘侧引导用户手动在系统设置中启用。
    return false
  }

  override fun getWindowManager(): WindowManager {
    return service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
  }

  override fun getServiceStatus(): ServiceStatus {
    return if (BrailleBackService.getActiveInstance() != null) ServiceStatus.ON
    else ServiceStatus.OFF
  }

  override fun interruptSpeak() {
    val tts = textToSpeech
    if (tts != null && ttsReady) {
      tts.stop()
    }
    pendingUtterances.clear()
  }

  override fun disableSilenceOnProximity() {
    // BrailleBack 没有"贴近静音"功能，无需处理。
  }

  override fun restoreSilenceOnProximity() {
    // 同上。
  }

  override fun isContextMenuExist(): Boolean {
    return false
  }

  override fun isVibrationFeedbackEnabled(): Boolean {
    return true
  }

  override fun shouldAnnounceCharacterForOnScreenKeyboard(): Boolean {
    // 让键盘自己把每个字符交给读屏朗读。
    //
    // 早前试过"完全不读、交给读屏去读输入框文本变化"，但那依赖读屏是否对每个字符都回读文本框，
    // 实测会漏读很多内容（读屏只在部分场景回读）。这里改成由键盘逐字符发公告：内容完整、
    // 顺序可控；公告走读屏的语音通道，所以声音仍是读屏那一个。
    return true
  }

  override fun shouldAnnounceCharacterForPhysicalKeyboard(): Boolean {
    return true
  }

  /** 是否除本应用之外还有别的无障碍服务在跑（即存在读屏接管朗读）。 */
  private fun hasExternalScreenReader(): Boolean {
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
      Log.w(TAG, "hasExternalScreenReader failed", e)
    }
    return false
  }

  override fun shouldUseCharacterGranularity(): Boolean {
    // 只有当前粒度就是"字符"时，才让键盘用 EditBuffer 自己挪光标（这条路最跟手、
    // 不会打断正在拼的盲文）；其它粒度交给下面的 moveByGranularity() 走节点动作。
    return readingGranularity == ReadingGranularity.CHARACTER
  }

  override fun isCurrentGranularityTypoCorrection(): Boolean {
    // BrailleBack 没有拼写检查引擎，永远不会进入"拼写纠正"粒度。
    // 对应地，帮助里的拼写检查类手势已在 SupportedCommand 里被隐藏，不会出现"按了没反应"。
    return false
  }

  override fun isHideScreenMode(): Boolean {
    return false
  }

  override fun resetGranularity() {
    // 键盘收起/重新激活时把粒度复位到"字符"，避免下次进来莫名按词移动。
    readingGranularity = ReadingGranularity.CHARACTER
  }

  override fun getBrailleImeForTalkBackProvider(): BrailleImeForTalkBackProvider {
    return BrailleImeForTalkBackProvider { brailleImeForTalkBack }
  }

  override fun setBrailleImeForTalkBack(brailleImeForTalkBack: BrailleImeForTalkBack?) {
    this.brailleImeForTalkBack = brailleImeForTalkBack
  }

  override fun createFocusFinder(): FocusFinder {
    return focusFinder
  }

  /** 供键盘侧查询"当前粒度是否可用"。 */
  fun isGranularityValid(granularity: CursorGranularity): Boolean {
    return granularity == CursorGranularity.CHARACTER ||
        granularity == CursorGranularity.WORD ||
        granularity == CursorGranularity.LINE ||
        granularity == CursorGranularity.PARAGRAPH
  }

  /** 释放资源。 */
  fun shutdown() {
    val tts = textToSpeech
    if (tts != null) {
      tts.stop()
      tts.shutdown()
      textToSpeech = null
    }
    feedbackController.shutdown()
  }

  companion object {
    private const val TAG = "BrailleImeHostBridge"
    private const val TTS_UTTERANCE_ID = "brailleime"
    /** 等待 setServiceInfo 在系统侧生效的时间，再设置直通区。 */
    private const val PASSTHROUGH_APPLY_DELAY_MS = 150L

    private const val NO_EXTEND = false
    private const val EXTEND = true
    /** 表示"用节点当前的选区/文本长度"，而不是一个具体下标。 */
    private const val NO_POSITION = -1

    private fun granularityAnnouncementRes(granularity: ReadingGranularity): Int {
      return when (granularity) {
        ReadingGranularity.WORD ->
            com.google.android.accessibility.brailleime.R.string.bb_granularity_word
        ReadingGranularity.LINE ->
            com.google.android.accessibility.brailleime.R.string.bb_granularity_line
        ReadingGranularity.PARAGRAPH ->
            com.google.android.accessibility.brailleime.R.string.bb_granularity_paragraph
        // Java 原为 case CHARACTER: default: 合并返回 character。
        else ->
            com.google.android.accessibility.brailleime.R.string.bb_granularity_character
      }
    }

    private fun findEditableDescendant(
        node: AccessibilityNodeInfoCompat?,
        depth: Int
    ): AccessibilityNodeInfoCompat? {
      if (node == null || depth > 40) {
        return null
      }
      if (isTextNode(node)) {
        return node
      }
      for (i in 0 until node.getChildCount()) {
        val child = node.getChild(i)
        if (child == null) {
          continue
        }
        val found = findEditableDescendant(child, depth + 1)
        if (found != null) {
          return found
        }
      }
      return null
    }

    private fun isTextNode(node: AccessibilityNodeInfoCompat?): Boolean {
      return node != null && node.getText() != null
    }
  }
}
