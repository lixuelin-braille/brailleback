/*
 * 触摸探索直通区的系统交互：从 BrailleImeHostBridge 拆出的直通区闭环。
 *
 * 只负责服务标志位开关、整屏区域计算与直通区/空 Region 的系统下发；直通区落地那一拍的
 * 协程句柄、活跃标志判定与作用域调度仍留在主桥接，本类不持有作用域、不排延迟。
 * 区域解析、兜底 catch 与日志 tag 与原桥接内实现逐字一致。
 */
package com.linxi.brailleback.ime

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.graphics.Region
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.WindowManager
import com.linxi.brailleback.BrailleBackService

internal class BrailleImeHostPassthrough(
    private val service: BrailleBackService,
) {

  /** 直通区是否处于已挂载状态（用于兜底清理与延迟任务判定）。 */
  private var active = false

  fun isActive(): Boolean = active

  fun setActive(value: Boolean) {
    active = value
  }

  /** 临时开关本服务的"请求触摸探索"标志位（仅在本服务信息副本上修改并回设）。 */
  fun setTouchExplorationRequested(requested: Boolean) {
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
      Log.i(BrailleImeHostBridge.TAG, "touchExplorationRequested=" + requested + " flags=" + info.flags)
    } catch (t: Throwable) {
      Log.e(BrailleImeHostBridge.TAG, "setServiceInfo failed", t)
    }
  }

  fun obtainDisplayRegion(): Region {
    val metrics = DisplayMetrics()
    val display = windowManager().getDefaultDisplay()
    display.getRealMetrics(metrics)
    return Region(0, 0, metrics.widthPixels, metrics.heightPixels)
  }

  fun applyRegion(target: Region) {
    try {
      service.setTouchExplorationPassthroughRegion(Display.DEFAULT_DISPLAY, target)
      Log.i(BrailleImeHostBridge.TAG, "passthrough applied: " + target)
    } catch (t: Throwable) {
      Log.e(BrailleImeHostBridge.TAG, "passthrough failed", t)
    }
  }

  fun clearRegion() {
    try {
      // 空 Region 表示"没有直通区"，即恢复正常的触摸探索拦截。
      service.setTouchExplorationPassthroughRegion(Display.DEFAULT_DISPLAY, Region())
      Log.i(BrailleImeHostBridge.TAG, "passthrough cleared")
    } catch (t: Throwable) {
      Log.e(BrailleImeHostBridge.TAG, "clear passthrough failed", t)
    }
  }

  private fun windowManager(): WindowManager =
      service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
}
