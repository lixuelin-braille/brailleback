/*
 * 移植说明：原版 A11yAlertDialogWrapper（428 行）同时包装 MaterialAlertDialog、
 * support-v7 AlertDialog 与 android.app.AlertDialog，以兼容 Wear OS 等平台。
 * BrailleBack 只有手机一种形态，因此这里统一包装 MaterialAlertDialogBuilder，
 * 并保留原版调用方使用的 API 外形（materialDialogBuilder / Builder / show / dismiss）。
 */

package com.google.android.accessibility.utils.material;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.errorprone.annotations.CanIgnoreReturnValue;

/** 无障碍对话框包装。 */
public class A11yAlertDialogWrapper implements DialogInterface {

  private final AlertDialog dialog;

  A11yAlertDialogWrapper(AlertDialog dialog) {
    this.dialog = dialog;
  }

  /** 创建一个使用 Material 主题的对话框构建器。 */
  public static Builder materialDialogBuilder(Context context) {
    return new Builder(context);
  }

  public void show() {
    dialog.show();
  }

  public Button getButton(int whichButton) {
    return dialog.getButton(whichButton);
  }

  public boolean isShowing() {
    return dialog.isShowing();
  }

  public @Nullable Window getWindow() {
    return dialog.getWindow();
  }

  @Override
  public void cancel() {
    dialog.cancel();
  }

  @Override
  public void dismiss() {
    dialog.dismiss();
  }

  public void setOnDismissListener(@Nullable OnDismissListener listener) {
    dialog.setOnDismissListener(listener);
  }

  public void setCanceledOnTouchOutside(boolean cancel) {
    dialog.setCanceledOnTouchOutside(cancel);
  }

  public Dialog getDialog() {
    return dialog;
  }

  /** 对话框构建器。 */
  public static class Builder {
    private final MaterialAlertDialogBuilder delegate;
    private boolean canceledOnTouchOutside = true;

    Builder(Context context) {
      delegate = new MaterialAlertDialogBuilder(context);
    }

    @CanIgnoreReturnValue
    public Builder setTitle(@Nullable CharSequence title) {
      delegate.setTitle(title);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setTitle(@StringRes int titleRes) {
      delegate.setTitle(titleRes);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setMessage(@Nullable CharSequence message) {
      delegate.setMessage(message);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setMessage(@StringRes int messageRes) {
      delegate.setMessage(messageRes);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setIcon(@DrawableRes int iconRes) {
      delegate.setIcon(iconRes);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setView(View view) {
      delegate.setView(view);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setCancelable(boolean cancelable) {
      delegate.setCancelable(cancelable);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setPositiveButton(
        @StringRes int textRes, @Nullable DialogInterface.OnClickListener listener) {
      delegate.setPositiveButton(textRes, listener);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setPositiveButton(
        @Nullable CharSequence text, @Nullable DialogInterface.OnClickListener listener) {
      delegate.setPositiveButton(text, listener);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setNegativeButton(
        @StringRes int textRes, @Nullable DialogInterface.OnClickListener listener) {
      delegate.setNegativeButton(textRes, listener);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setNegativeButton(
        @Nullable CharSequence text, @Nullable DialogInterface.OnClickListener listener) {
      delegate.setNegativeButton(text, listener);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setNeutralButton(
        @StringRes int textRes, @Nullable DialogInterface.OnClickListener listener) {
      delegate.setNeutralButton(textRes, listener);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setOnDismissListener(@Nullable DialogInterface.OnDismissListener listener) {
      delegate.setOnDismissListener(listener);
      return this;
    }

    @CanIgnoreReturnValue
    public Builder setCanceledOnTouchOutside(boolean canceledOnTouchOutside) {
      // MaterialAlertDialogBuilder 没有对应方法，在实际构建出来的 Dialog 上设置。
      this.canceledOnTouchOutside = canceledOnTouchOutside;
      return this;
    }

    /** 构建并显示。 */
    public A11yAlertDialogWrapper show() {
      AlertDialog dialog = delegate.show();
      dialog.setCanceledOnTouchOutside(canceledOnTouchOutside);
      return new A11yAlertDialogWrapper(dialog);
    }

    /** 只构建不显示。 */
    public A11yAlertDialogWrapper create() {
      AlertDialog dialog = delegate.create();
      dialog.setCanceledOnTouchOutside(canceledOnTouchOutside);
      return new A11yAlertDialogWrapper(dialog);
    }
  }
}
