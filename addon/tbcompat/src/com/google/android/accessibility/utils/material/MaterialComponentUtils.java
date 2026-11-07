/*
 * 移植说明：原版依赖 utils.R 中的主题样式。这里直接使用 Material 默认主题，
 * 保证在没有 TalkBack 资源的情况下也能弹出可读的对话框。
 */

package com.google.android.accessibility.utils.material;

import android.content.Context;
import androidx.annotation.Nullable;
import androidx.annotation.StyleRes;
import androidx.appcompat.app.AlertDialog;
import android.widget.Button;

/** Material 组件工具（精简实现）。 */
public final class MaterialComponentUtils {

  private MaterialComponentUtils() {}

  /** 返回一个使用 Material 主题的普通 AlertDialog 构建器。 */
  public static AlertDialog.Builder alertDialogBuilder(Context context) {
    return new AlertDialog.Builder(context);
  }

  /** 返回带指定主题的 AlertDialog 构建器。 */
  public static AlertDialog.Builder alertDialogBuilder(Context context, @StyleRes int themeResId) {
    return new AlertDialog.Builder(context, themeResId);
  }

  /** 设置对话框按钮样式（外壳实现不做改动）。 */
  public static void setButtonStyle(@Nullable Button button) {
    // no-op：保留 signpost 以便将来对齐 Material 规范
  }
}
