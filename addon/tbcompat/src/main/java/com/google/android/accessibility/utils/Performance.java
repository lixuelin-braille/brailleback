/*
 * 移植说明：TalkBack utils 里 Performance 负责事件耗时追踪，braille 子系统只用到
 * EventId 类型和 EVENT_ID_UNTRACKED 常量，因此这里只保留最小外壳。
 */

package com.google.android.accessibility.utils;

import androidx.annotation.Nullable;

/** 事件追踪的最小外壳实现。 */
public final class Performance {

  /** 未纳入追踪的事件 id。 */
  public static final EventId EVENT_ID_UNTRACKED = new EventId(0);

  /** 事件标识。 */
  public static class EventId {
    private final int id;

    public EventId(int id) {
      this.id = id;
    }

    public int getId() {
      return id;
    }

    @Override
    public String toString() {
      return "EventId{" + id + "}";
    }
  }

  private Performance() {}

  /** 空实现：开始追踪。 */
  public static void onEventStart(@Nullable EventId eventId, @Nullable String eventType) {
    // no-op
  }

  /** 空实现：结束追踪。 */
  public static void onEventEnd(@Nullable EventId eventId, @Nullable String eventType) {
    // no-op
  }
}
