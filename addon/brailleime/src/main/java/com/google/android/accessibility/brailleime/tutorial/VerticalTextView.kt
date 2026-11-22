package com.google.android.accessibility.brailleime.tutorial

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.text.TextPaint
import android.util.AttributeSet
import android.util.Size
import android.widget.TextView
import com.google.android.accessibility.brailleime.BrailleIme.OrientationSensitive

/** A {@code TextView} that display texts vertically. */
class VerticalTextView : TextView, OrientationSensitive {
  private var orientation: Int = resources.configuration.orientation

  constructor(context: Context, attrs: AttributeSet?, defStyle: Int) : super(context, attrs, defStyle)

  constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)

  constructor(context: Context) : super(context)

  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    super.onMeasure(heightMeasureSpec, widthMeasureSpec)
    if (orientation == Configuration.ORIENTATION_PORTRAIT) {
      setMeasuredDimension(measuredHeight, measuredWidth)
    } else {
      setMeasuredDimension(measuredWidth, measuredHeight)
    }
  }

  override fun onDraw(canvas: Canvas) {
    val textPaint: TextPaint = paint
    textPaint.color = currentTextColor
    textPaint.drawableState = drawableState

    canvas.save()
    if (orientation == Configuration.ORIENTATION_PORTRAIT) {
      canvas.translate(/* dx= */ 0f, /* dy= */ height.toFloat())
      canvas.rotate(/* degree= */ -90f)
    } else {
      canvas.translate(/* dx= */ 0f, /* dy= */ 0f)
      canvas.rotate(/* degree= */ 0f)
    }

    canvas.translate(compoundPaddingLeft.toFloat(), extendedPaddingTop.toFloat())

    layout!!.draw(canvas)
    canvas.restore()
  }

  override fun onOrientationChanged(orientation: Int, screenSize: Size) {
    this.orientation = orientation
    invalidate()
    requestLayout()
  }
}
