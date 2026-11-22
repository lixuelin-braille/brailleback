/*
 * 移植说明：原版 A11yAlertDialogWrapper（428 行）同时包装 MaterialAlertDialog、
 * support-v7 AlertDialog 与 android.app.AlertDialog，以兼容 Wear OS 等平台。
 * BrailleBack 只有手机一种形态，因此这里统一包装 MaterialAlertDialogBuilder，
 * 并保留原版调用方使用的 API 外形（materialDialogBuilder / Builder / show / dismiss）。
 */

package com.google.android.accessibility.utils.material

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.view.View
import android.view.Window
import android.widget.Button
import androidx.annotation.DrawableRes
import androidx.annotation.Nullable
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.errorprone.annotations.CanIgnoreReturnValue

/** 无障碍对话框包装。 */
class A11yAlertDialogWrapper
internal constructor(private val dialog: AlertDialog) : DialogInterface {

  /** 创建一个使用 Material 主题的对话框构建器。 */
  companion object {
    @JvmStatic
    fun materialDialogBuilder(context: Context): Builder = Builder(context)
  }

  fun show() {
    dialog.show()
  }

  fun getButton(whichButton: Int): Button = dialog.getButton(whichButton)

  fun isShowing(): Boolean = dialog.isShowing

  @Nullable
  fun getWindow(): Window? = dialog.window

  override fun cancel() {
    dialog.cancel()
  }

  override fun dismiss() {
    dialog.dismiss()
  }

  fun setOnDismissListener(@Nullable listener: DialogInterface.OnDismissListener?) {
    dialog.setOnDismissListener(listener)
  }

  fun setCanceledOnTouchOutside(cancel: Boolean) {
    dialog.setCanceledOnTouchOutside(cancel)
  }

  fun getDialog(): Dialog = dialog

  /** 对话框构建器。 */
  class Builder internal constructor(context: Context) {
    private val delegate: MaterialAlertDialogBuilder = MaterialAlertDialogBuilder(context)
    private var canceledOnTouchOutside = true

    @CanIgnoreReturnValue
    fun setTitle(@Nullable title: CharSequence?): Builder {
      delegate.setTitle(title)
      return this
    }

    @CanIgnoreReturnValue
    fun setTitle(@StringRes titleRes: Int): Builder {
      delegate.setTitle(titleRes)
      return this
    }

    @CanIgnoreReturnValue
    fun setMessage(@Nullable message: CharSequence?): Builder {
      delegate.setMessage(message)
      return this
    }

    @CanIgnoreReturnValue
    fun setMessage(@StringRes messageRes: Int): Builder {
      delegate.setMessage(messageRes)
      return this
    }

    @CanIgnoreReturnValue
    fun setIcon(@DrawableRes iconRes: Int): Builder {
      delegate.setIcon(iconRes)
      return this
    }

    @CanIgnoreReturnValue
    fun setView(view: View): Builder {
      delegate.setView(view)
      return this
    }

    @CanIgnoreReturnValue
    fun setCancelable(cancelable: Boolean): Builder {
      delegate.setCancelable(cancelable)
      return this
    }

    @CanIgnoreReturnValue
    fun setPositiveButton(
      @StringRes textRes: Int,
      @Nullable listener: DialogInterface.OnClickListener?,
    ): Builder {
      delegate.setPositiveButton(textRes, listener)
      return this
    }

    @CanIgnoreReturnValue
    fun setPositiveButton(
      @Nullable text: CharSequence?,
      @Nullable listener: DialogInterface.OnClickListener?,
    ): Builder {
      delegate.setPositiveButton(text, listener)
      return this
    }

    @CanIgnoreReturnValue
    fun setNegativeButton(
      @StringRes textRes: Int,
      @Nullable listener: DialogInterface.OnClickListener?,
    ): Builder {
      delegate.setNegativeButton(textRes, listener)
      return this
    }

    @CanIgnoreReturnValue
    fun setNegativeButton(
      @Nullable text: CharSequence?,
      @Nullable listener: DialogInterface.OnClickListener?,
    ): Builder {
      delegate.setNegativeButton(text, listener)
      return this
    }

    @CanIgnoreReturnValue
    fun setNeutralButton(
      @StringRes textRes: Int,
      @Nullable listener: DialogInterface.OnClickListener?,
    ): Builder {
      delegate.setNeutralButton(textRes, listener)
      return this
    }

    @CanIgnoreReturnValue
    fun setOnDismissListener(@Nullable listener: DialogInterface.OnDismissListener?): Builder {
      delegate.setOnDismissListener(listener)
      return this
    }

    @CanIgnoreReturnValue
    fun setCanceledOnTouchOutside(canceledOnTouchOutside: Boolean): Builder {
      // MaterialAlertDialogBuilder 没有对应方法，在实际构建出来的 Dialog 上设置。
      this.canceledOnTouchOutside = canceledOnTouchOutside
      return this
    }

    /** 构建并显示。 */
    fun show(): A11yAlertDialogWrapper {
      val dialog = delegate.show()
      dialog.setCanceledOnTouchOutside(canceledOnTouchOutside)
      return A11yAlertDialogWrapper(dialog)
    }

    /** 只构建不显示。 */
    fun create(): A11yAlertDialogWrapper {
      val dialog = delegate.create()
      dialog.setCanceledOnTouchOutside(canceledOnTouchOutside)
      return A11yAlertDialogWrapper(dialog)
    }
  }
}
