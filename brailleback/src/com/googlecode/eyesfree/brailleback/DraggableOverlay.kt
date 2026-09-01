/*
 * Copyright (C) 2013 Google Inc.
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
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Message
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import com.googlecode.eyesfree.utils.MotionEventUtils
import com.googlecode.eyesfree.widget.SimpleOverlay

/**
 * Overlay that can be long-pressed, and then dragged to the top or bottom of
 * the screen. Intermediate positions are not supported due to the complexity
 * that arises from screen orientation changes.
 */
open class DraggableOverlay(context: Context) : SimpleOverlay(context) {
    private val mWindowManager: WindowManager =
            context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    // Compute touch slop.
    private val mTouchSlopSquare: Int
    private val mWindowParams: WindowManager.LayoutParams = createOverlayParams()
    private val mTouchStealingView: View
    private val mTouchStealingLayoutParams: WindowManager.LayoutParams
    private val mInternalListener: InternalListener
    private var mDragging = false
    private var mDragOrigin: Float = 0f

    init {
        val viewConfiguration = ViewConfiguration.get(context)
        val touchSlop = viewConfiguration.scaledTouchSlop
        mTouchSlopSquare = touchSlop * touchSlop

        // Configure the overlay window.
        mWindowParams.width = WindowManager.LayoutParams.MATCH_PARENT
        mWindowParams.height = WindowManager.LayoutParams.WRAP_CONTENT
        mWindowParams.gravity = DEFAULT_GRAVITY
        mWindowParams.windowAnimations = android.R.style.Animation_Translucent
        setParams(mWindowParams)

        // Listen to touch events.
        mInternalListener = InternalListener()
        getRootView().setOnHoverListener(mInternalListener)
        getRootView().setOnTouchListener(mInternalListener)

        // Prepare another view which can grab touch events for the entire
        // screen during dragging.
        mTouchStealingView = View(context)
        mTouchStealingView.setOnHoverListener(mInternalListener)
        mTouchStealingView.setOnTouchListener(mInternalListener)
        val lp = createOverlayParams()
        lp.width = WindowManager.LayoutParams.MATCH_PARENT
        lp.height = WindowManager.LayoutParams.MATCH_PARENT
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        mTouchStealingLayoutParams = lp
    }

    private fun createOverlayParams(): WindowManager.LayoutParams {
        val lp = WindowManager.LayoutParams()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) {
            lp.type = WindowManager.LayoutParams.TYPE_SYSTEM_ERROR
        } else {
            lp.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        }
        lp.format = PixelFormat.TRANSPARENT
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        return lp
    }

    private fun startDragging(event: MotionEvent) {
        if (mDragging) {
            return
        }

        mDragging = true
        mDragOrigin = event.rawY
        onStartDragging()
        mWindowParams.flags = mWindowParams.flags or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        setParams(mWindowParams)
        mWindowManager.addView(mTouchStealingView, mTouchStealingLayoutParams)
        getRootView().performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun stopDragging() {
        if (!mDragging) {
            return
        }

        mDragging = false
        mWindowParams.flags = mWindowParams.flags and
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        if (mWindowParams.y > mTouchStealingView.height / 2) {
            when (mWindowParams.gravity and Gravity.VERTICAL_GRAVITY_MASK) {
                Gravity.BOTTOM -> {
                    mWindowParams.gravity = mWindowParams.gravity and
                            Gravity.VERTICAL_GRAVITY_MASK.inv()
                    mWindowParams.gravity = mWindowParams.gravity or Gravity.TOP
                }
                Gravity.TOP -> {
                    mWindowParams.gravity = mWindowParams.gravity and
                            Gravity.VERTICAL_GRAVITY_MASK.inv()
                    mWindowParams.gravity = mWindowParams.gravity or Gravity.BOTTOM
                }
            }
        }
        mWindowParams.y = 0
        setParams(mWindowParams)
        mWindowManager.removeViewImmediate(mTouchStealingView)
    }

    private fun cancelDragging() {
        if (!mDragging) {
            return
        }

        mDragging = false
        mWindowParams.flags = mWindowParams.flags and
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        mWindowParams.y = 0
        setParams(mWindowParams)
        mWindowManager.removeViewImmediate(mTouchStealingView)
    }

    private fun drag(event: MotionEvent) {
        if (!mDragging) {
            return
        }

        when (mWindowParams.gravity and Gravity.VERTICAL_GRAVITY_MASK) {
            Gravity.BOTTOM -> mWindowParams.y = (mDragOrigin - event.rawY).toInt()
            Gravity.TOP -> mWindowParams.y = (event.rawY - mDragOrigin).toInt()
        }
        setParams(mWindowParams)
    }

    protected open fun onStartDragging() {
        // Intentionally left blank.
    }

    private inner class InternalListener :
            View.OnTouchListener, View.OnHoverListener, Handler.Callback {

        private val mHandler = Handler(this)
        private var touchStartX: Float = 0f
        private var touchStartY: Float = 0f

        override fun onHover(view: View, event: MotionEvent): Boolean {
            val touchEvent = MotionEventUtils.convertHoverToTouch(event)
            try {
                return onTouch(view, touchEvent)
            } finally {
                touchEvent.recycle()
            }
        }

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (view !== mTouchStealingView) {
                        touchStartX = event.rawX
                        touchStartY = event.rawY
                        val timeout = (TAP_TIMEOUT + LONG_PRESS_TIMEOUT).toLong()
                        mHandler.sendMessageAtTime(
                                mHandler.obtainMessage(MSG_LONG_PRESS, event),
                                event.eventTime + timeout)
                    }
                }

                MotionEvent.ACTION_UP -> {
                    mHandler.removeMessages(MSG_LONG_PRESS)
                    if (view === mTouchStealingView) {
                        stopDragging()
                    }
                }

                MotionEvent.ACTION_CANCEL -> {
                    mHandler.removeMessages(MSG_LONG_PRESS)
                    cancelDragging()
                }

                MotionEvent.ACTION_MOVE -> {
                    val distanceX = event.rawX - touchStartX
                    val distanceY = event.rawY - touchStartY
                    val distanceSquare =
                            distanceX * distanceX + distanceY * distanceY
                    if (distanceSquare > mTouchSlopSquare) {
                        mHandler.removeMessages(MSG_LONG_PRESS)
                    }
                    drag(event)
                }
            }

            return false
        }

        override fun handleMessage(msg: Message): Boolean {
            when (msg.what) {
                MSG_LONG_PRESS -> startDragging(msg.obj as MotionEvent)
            }
            return true
        }
    }

    companion object {
        private const val MSG_LONG_PRESS = 1
        private const val DEFAULT_GRAVITY = Gravity.BOTTOM
        private val LONG_PRESS_TIMEOUT = ViewConfiguration.getLongPressTimeout()
        private val TAP_TIMEOUT = ViewConfiguration.getTapTimeout()
    }
}
