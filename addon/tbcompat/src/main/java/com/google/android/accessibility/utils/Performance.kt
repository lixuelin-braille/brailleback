/*
 * 移植说明：TalkBack utils 里 Performance 负责事件耗时追踪，braille 子系统只用到
 * EventId 类型和 EVENT_ID_UNTRACKED 常量，因此这里只保留最小外壳。
 */

package com.google.android.accessibility.utils

import androidx.annotation.Nullable

/** 事件追踪的最小外壳实现。 */
class Performance private constructor() {

  /** 事件标识。 */
  class EventId(val id: Int) {

    override fun toString(): String = "EventId{$id}"
  }

  companion object {
    /** 未纳入追踪的事件 id。 */
    @JvmField
    val EVENT_ID_UNTRACKED = EventId(0)

    /** 空实现：开始追踪。 */
    @JvmStatic
    fun onEventStart(@Nullable eventId: EventId?, @Nullable eventType: String?) {
      // no-op
    }

    /** 空实现：结束追踪。 */
    @JvmStatic
    fun onEventEnd(@Nullable eventId: EventId?, @Nullable eventType: String?) {
      // no-op
    }
  }
}
