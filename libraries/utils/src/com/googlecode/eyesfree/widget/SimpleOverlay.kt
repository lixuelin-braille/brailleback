package com.googlecode.eyesfree.widget

import android.content.Context
import android.graphics.PixelFormat
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.View.OnKeyListener
import android.view.View.OnTouchListener
import android.view.ViewGroup
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout

/**
 * Provides a simple full-screen overlay. Behaves like a [android.app.Dialog] but simpler.
 */
open class SimpleOverlay @JvmOverloads constructor(
    context: Context,
    id: Int = 0,
    private val sendsAccessibilityEvents: Boolean = false
) {
    private val mContext: Context = context
    private val mWindowManager: WindowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mContentView: ViewGroup
    private val mParams: LayoutParams = WindowManager.LayoutParams()
    private val mId: Int = id

    private var mListener: SimpleOverlayListener? = null
    private var mTouchListener: OnTouchListener? = null
    private var mKeyListener: OnKeyListener? = null
    private var mVisible = false

    init {
        mContentView = object : FrameLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (mKeyListener != null && mKeyListener!!.onKey(mContentView, event.keyCode, event)) {
                    return true
                }
                return super.dispatchKeyEvent(event)
            }

            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                if (mTouchListener != null && mTouchListener!!.onTouch(mContentView, event)) {
                    return true
                }
                return super.dispatchTouchEvent(event)
            }

            override fun requestSendAccessibilityEvent(view: View, event: AccessibilityEvent): Boolean =
                if (sendsAccessibilityEvents) {
                    super.requestSendAccessibilityEvent(view, event)
                } else {
                    false
                }

            override fun sendAccessibilityEventUnchecked(event: AccessibilityEvent) {
                if (sendsAccessibilityEvents) {
                    super.sendAccessibilityEventUnchecked(event)
                }
            }
        }

        mParams.type = WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        mParams.format = PixelFormat.TRANSLUCENT
        mParams.flags = mParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
    }

    fun getContext(): Context = mContext

    fun getId(): Int = mId

    fun setOnKeyListener(keyListener: OnKeyListener?) {
        mKeyListener = keyListener
    }

    fun setOnTouchListener(touchListener: OnTouchListener?) {
        mTouchListener = touchListener
    }

    fun setListener(listener: SimpleOverlayListener?) {
        mListener = listener
    }

    fun show() {
        if (mVisible) {
            return
        }
        mWindowManager.addView(mContentView, mParams)
        mVisible = true
        mListener?.onShow(this)
        onShow()
    }

    fun hide() {
        if (!mVisible) {
            return
        }
        mWindowManager.removeViewImmediate(mContentView)
        mVisible = false
        mListener?.onHide(this)
        onHide()
    }

    protected open fun onShow() {
        // Do nothing.
    }

    protected open fun onHide() {
        // Do nothing.
    }

    fun getParams(): LayoutParams {
        val copy = LayoutParams()
        copy.copyFrom(mParams)
        return copy
    }

    fun setParams(params: LayoutParams) {
        mParams.copyFrom(params)
        if (mVisible) {
            mWindowManager.updateViewLayout(mContentView, mParams)
        }
    }

    fun isVisible(): Boolean = mVisible

    fun setContentView(layoutResId: Int) {
        val inflater = LayoutInflater.from(mContext)
        inflater.inflate(layoutResId, mContentView)
    }

    fun setContentView(content: View) {
        mContentView.removeAllViews()
        mContentView.addView(content)
    }

    fun getRootView(): View = mContentView

    fun findViewById(id: Int): View = mContentView.findViewById(id)

    interface SimpleOverlayListener {
        fun onShow(overlay: SimpleOverlay)
        fun onHide(overlay: SimpleOverlay)
    }
}
