/*
 * 移植说明：原版依赖 utils.R 中的主题样式。这里直接使用 Material 默认主题，
 * 保证在没有 TalkBack 资源的情况下也能弹出可读的对话框。
 */

package com.google.android.accessibility.utils.material

import android.content.Context
import android.widget.Button
import androidx.annotation.Nullable
import androidx.annotation.StyleRes
import androidx.appcompat.app.AlertDialog

/** Material 组件工具（精简实现）。 */
class MaterialComponentUtils private constructor() {

  companion object {
    /** 返回一个使用 Material 主题的普通 AlertDialog 构建器。 */
    @JvmStatic
    fun alertDialogBuilder(context: Context): AlertDialog.Builder = AlertDialog.Builder(context)

    /** 返回带指定主题的 AlertDialog 构建器。 */
    @JvmStatic
    fun alertDialogBuilder(context: Context, @StyleRes themeResId: Int): AlertDialog.Builder =
      AlertDialog.Builder(context, themeResId)

    /** 设置对话框按钮样式（外壳实现不做改动）。 */
    @JvmStatic
    fun setButtonStyle(@Nullable button: Button?) {
      // no-op：保留 signpost 以便将来对齐 Material 规范
    }
  }
}
