/*
 * Copyright (C) 2012 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.googlecode.eyesfree.brailleback

import android.content.Context
import android.content.res.TypedArray
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.googlecode.eyesfree.braille.display.BrailleDisplayProperties

/**
 * View which displays Braille dot patterns and corresponding text.
 * Requires a mapping between the two to provide proper alignment.
 */
class BrailleView(context: Context, attrs: AttributeSet) : View(context, attrs) {

    /** Interface for listening to taps on Braille cells. */
    interface OnBrailleCellClickListener {
        fun onBrailleCellClick(view: BrailleView, cellIndex: Int)
    }

    /** Interface for listening to width changes, expressed in terms of the
     * number of Braille cells that can be displayed. */
    interface OnResizeListener {
        fun onResize(maxNumTextCells: Int)
    }

    private val mClearHighlightedCell = Runnable {
        mHighlightedCell = -1
        invalidate()
    }
    private val mPrimaryPaint: Paint
    private val mSecondaryPaint: Paint
    private val mHighlightDrawable: Drawable?
    private val mDotRadius: Float
    private val mCellPadding: Float
    private val mCellWidth: Float
    private val mCellHeight: Float
    private val mOuterWidth: Float
    private val mOuterHeight: Float
    private val mTouchSlop: Int

    @Volatile
    private var mResizeListener: OnResizeListener? = null
    @Volatile
    private var mBrailleCellClickListener: OnBrailleCellClickListener? = null
    private var mNumTextCells = 0
    private var mBraille = ByteArray(0)
    private var mText: CharSequence = ""
    private var mBrailleToTextPositions = IntArray(0)
    private var mMaxNumTextCells = 0
    private var mHighlightedCell = -1
    private var mPressedCell = -1

    init {
        val a: TypedArray = context.theme.obtainStyledAttributes(
            attrs, R.styleable.BrailleView, 0, 0)
        try {
            mPrimaryPaint = Paint()
            mPrimaryPaint.isAntiAlias = true
            mPrimaryPaint.color = a.getColor(
                R.styleable.BrailleView_android_textColor, 0xFFFFFFFF.toInt())
            mPrimaryPaint.textSize = a.getDimension(
                R.styleable.BrailleView_android_textSize, 20.0f)
            mPrimaryPaint.textAlign = Paint.Align.CENTER
            mSecondaryPaint = Paint(mPrimaryPaint)
            mSecondaryPaint.alpha = DIMMED_ALPHA
            mHighlightDrawable = a.getDrawable(
                R.styleable.BrailleView_highlightDrawable)
            mDotRadius = a.getDimension(
                R.styleable.BrailleView_dotRadius, 4.0f)
            mCellWidth = a.getDimension(
                R.styleable.BrailleView_cellWidth, 10.0f)
            mCellHeight = a.getDimension(
                R.styleable.BrailleView_cellHeight, 30.0f)
            mCellPadding = a.getDimension(
                R.styleable.BrailleView_cellPadding, 13.0f)
            mOuterWidth = mCellWidth + 2 * mCellPadding
            mOuterHeight = mCellHeight + 2 * mCellPadding
            mTouchSlop = ViewConfiguration.get(context).scaledTouchSlop
        } finally {
            a.recycle()
        }
    }

    fun displayDots(braille: ByteArray, text: CharSequence, brailleToTextPositions: IntArray) {
        mBraille = braille
        mText = text
        mBrailleToTextPositions = brailleToTextPositions
        invalidate()
    }

    fun setDisplayProperties(displayProperties: BrailleDisplayProperties) {
        if (displayProperties.getNumTextCells() != mNumTextCells) {
            mNumTextCells = displayProperties.getNumTextCells()
            requestLayout()
        }
    }

    fun setOnResizeListener(listener: OnResizeListener?) {
        mResizeListener = listener
    }

    fun highlightCell(cellIndex: Int) {
        mHighlightedCell = cellIndex
        removeCallbacks(mClearHighlightedCell)
        postDelayed(mClearHighlightedCell, HIGHLIGHT_TIME_MS.toLong())
        invalidate()
    }

    fun setOnBrailleCellClickListener(listener: OnBrailleCellClickListener?) {
        mBrailleCellClickListener = listener
    }

    fun cancelPendingTouches() {
        if (mPressedCell != -1) {
            mPressedCell = -1
            invalidate()
        }
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val metrics = mPrimaryPaint.fontMetrics

        val width = if (MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED) {
            Math.round(mNumTextCells * mOuterWidth)
        } else {
            MeasureSpec.getSize(widthSpec)
        }
        setMeasuredDimension(
            width,
            Math.round(mOuterHeight + mCellPadding - metrics.ascent + metrics.descent))
    }

    override fun onDraw(canvas: Canvas) {
        // Determine the actual rectangle on which the dots will be rendered.
        // We assume 8-dot braille; 6-dot braille will simply have empty bottom
        // dots. So this rectangle must be 3 times as tall as it is wide.
        val innerWidth: Float
        val innerHeight: Float
        val offsetX: Float
        val offsetY: Float
        if (3 * mCellWidth >= mCellHeight) {
            // Add more horizontal padding.
            innerWidth = mCellHeight / 3
            innerHeight = mCellHeight
            offsetX = mCellPadding + (mCellWidth - innerWidth) / 2
            offsetY = mCellPadding
        } else {
            // Add more vertical padding.
            innerWidth = mCellWidth
            innerHeight = mCellWidth * 3
            offsetX = mCellPadding
            offsetY = mCellPadding + (mCellHeight - innerHeight) / 2
        }

        // Draw the highlighted cell.
        if (mHighlightedCell >= 0 && mHighlightedCell < mNumTextCells &&
            mHighlightDrawable != null
        ) {
            mHighlightDrawable.setBounds(
                Math.round(mHighlightedCell * mOuterWidth), 0,
                Math.round((mHighlightedCell + 1) * mOuterWidth),
                Math.round(mOuterHeight))
            mHighlightDrawable.draw(canvas)
        }

        // Draw the pressed cell, if different.
        if (mPressedCell >= 0 && mPressedCell < mNumTextCells &&
            mPressedCell != mHighlightedCell && mHighlightDrawable != null
        ) {
            mHighlightDrawable.setBounds(
                Math.round(mPressedCell * mOuterWidth), 0,
                Math.round((mPressedCell + 1) * mOuterWidth),
                Math.round(mOuterHeight))
            mHighlightDrawable.draw(canvas)
        }

        // Draw dot patterns.
        // Note that mBraille.length may not match mNumTextCells.
        for (i in 0 until mNumTextCells) {
            canvas.save()
            canvas.translate(i * mOuterWidth, 0f)
            canvas.clipRect(0f, 0f, mOuterWidth, mOuterHeight)
            var pattern = if (i < mBraille.size) (mBraille[i].toInt() and 0xFF) else 0x00
            var j = 0
            while (j < DOT_POSITIONS.size) {
                val x = offsetX + DOT_POSITIONS[j] * innerWidth
                val y = offsetY + DOT_POSITIONS[j + 1] * innerHeight
                val paint = if ((pattern and 1) != 0) mPrimaryPaint else mSecondaryPaint
                canvas.drawCircle(x, y, mDotRadius, paint)
                pattern = pattern shr 1
                j += 2
            }
            canvas.restore()
        }

        // Draw corresponding text.
        val metrics = mPrimaryPaint.fontMetrics
        var brailleIndex = 0
        while (brailleIndex < mNumTextCells &&
            brailleIndex < mBrailleToTextPositions.size
        ) {
            val brailleStart = brailleIndex
            val textStart = mBrailleToTextPositions[brailleStart]
            do {
                brailleIndex++
            } while (brailleIndex < mBrailleToTextPositions.size &&
                mBrailleToTextPositions[brailleIndex] <= textStart)
            val brailleEnd = brailleIndex
            val textEnd = if (brailleEnd < mBrailleToTextPositions.size) {
                mBrailleToTextPositions[brailleEnd]
            } else {
                mText.length
            }

            val clipLeft = mOuterWidth * brailleStart
            val clipRight = mOuterWidth * brailleEnd
            val clipTop = mOuterHeight
            val clipBottom = clipTop + mCellPadding - metrics.ascent + metrics.descent + mCellPadding
            val x = (clipLeft + clipRight) / 2
            val y = clipTop - metrics.ascent
            val measuredWidth = mPrimaryPaint.measureText(mText, textStart, textEnd)
            if (measuredWidth > clipRight - clipLeft) {
                mPrimaryPaint.textScaleX = (clipRight - clipLeft) / measuredWidth
            }
            canvas.save()
            canvas.clipRect(clipLeft, clipTop, clipRight, clipBottom)
            canvas.drawText(mText, textStart, textEnd, x, y, mPrimaryPaint)
            canvas.restore()
            mPrimaryPaint.textScaleX = 1.0f
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
        val newMaxNumTextCells = (w / mOuterWidth).toInt()
        if (newMaxNumTextCells == mMaxNumTextCells) {
            return
        }
        mMaxNumTextCells = newMaxNumTextCells
        val localListener = mResizeListener
        if (localListener != null) {
            localListener.onResize(newMaxNumTextCells)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val cellIndex = (event.x / mOuterWidth).toInt()
                if (0 <= cellIndex && cellIndex < mNumTextCells) {
                    mPressedCell = cellIndex
                    invalidate()
                } else {
                    cancelPendingTouches()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (withinTouchSlopOfCell(event, mPressedCell)) {
                    reportBrailleCellClick(mPressedCell)
                }
                cancelPendingTouches()
            }
            MotionEvent.ACTION_CANCEL -> cancelPendingTouches()
            MotionEvent.ACTION_MOVE -> {
                if (!withinTouchSlopOfCell(event, mPressedCell)) {
                    cancelPendingTouches()
                }
            }
        }
        return false
    }

    private fun withinTouchSlopOfCell(event: MotionEvent, cellIndex: Int): Boolean {
        if (0 <= cellIndex && cellIndex < mNumTextCells) {
            val x = event.x
            return (cellIndex * mOuterWidth - mTouchSlop) <= x &&
                x <= ((cellIndex + 1) * mOuterWidth + mTouchSlop)
        }
        return false
    }

    private fun reportBrailleCellClick(cellIndex: Int) {
        val localListener = mBrailleCellClickListener
        if (localListener != null) {
            localListener.onBrailleCellClick(this, cellIndex)
        }
    }

    companion object {
        private const val HIGHLIGHT_TIME_MS = 300
        private const val DIMMED_ALPHA = 0x40
        private val DOT_POSITIONS = floatArrayOf(
            0.0f, 0.00f, // dot 1
            0.0f, 0.33f, // dot 2
            0.0f, 0.67f, // dot 3
            1.0f, 0.00f, // dot 4
            1.0f, 0.33f, // dot 5
            1.0f, 0.67f, // dot 6
            0.0f, 1.00f, // dot 7
            1.0f, 1.00f  // dot 8
        )
    }
}
