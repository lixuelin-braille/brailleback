/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.googlecode.eyesfree.brailleback

import android.content.Context
import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Paint.Align
import android.graphics.Paint.Style
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.GradientDrawable.Orientation
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View

/** View handling drawing of incremental search overlay. */
class SearchView(context: Context, private val mQueryText: StringBuilder) : SurfaceView(context) {

    /** The paint used to draw this view. */
    private val mPaint: Paint

    /** The surface holder onto which the view is drawn. */
    private var mHolder: SurfaceHolder? = null

    /** The background. */
    private val mGradientBackground: GradientDrawable

    private var mContext: Context

    private val mSurfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            mHolder = holder
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            mHolder = null
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            invalidate()
        }
    }

    init {
        mContext = context

        mPaint = Paint()
        mPaint.isAntiAlias = true

        val holder = holder
        holder.setFormat(PixelFormat.TRANSLUCENT)
        holder.addCallback(mSurfaceCallback)

        val res: Resources = context.resources

        // Gradient colors.
        val gradientInnerColor = res.getColor(R.color.search_overlay)
        val gradientOuterColor = res.getColor(R.color.search_overlay)
        val colors = intArrayOf(gradientInnerColor, gradientOuterColor)
        mGradientBackground = GradientDrawable(Orientation.TOP_BOTTOM, colors)
        mGradientBackground.gradientType = GradientDrawable.LINEAR_GRADIENT
    }

    fun show() {
        invalidate()
    }

    override fun invalidate() {
        super.invalidate()

        val holder = mHolder ?: return

        val canvas = holder.lockCanvas() ?: return

        // Clear the canvas.
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)

        if (visibility != View.VISIBLE) {
            holder.unlockCanvasAndPost(canvas)
            return
        }

        val width = getWidth()
        val height = getHeight()

        // Draw the pretty gradient background.
        mGradientBackground.setBounds(0, 0, width, height)
        mGradientBackground.draw(canvas)

        val paint = Paint()
        paint.color = Color.WHITE
        paint.style = Style.FILL
        paint.textAlign = Align.CENTER
        paint.textSize = mContext.resources.getDimensionPixelSize(
            R.dimen.search_text_font_size).toFloat()
        canvas.drawText(
            mContext.getString(R.string.search_dialog_label, mQueryText),
            width / 2.0f,
            height / 2.0f,
            paint)

        holder.unlockCanvasAndPost(canvas)
    }
}
