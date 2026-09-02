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
import android.content.SharedPreferences
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.preference.PreferenceManager
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import com.googlecode.eyesfree.braille.display.BrailleDisplayProperties
import com.googlecode.eyesfree.braille.display.BrailleInputEvent
import com.googlecode.eyesfree.braille.display.BrailleKeyBinding
import com.googlecode.eyesfree.braille.display.Display
import com.googlecode.eyesfree.utils.MotionEventUtils
import com.googlecode.eyesfree.utils.SharedPreferencesUtils
import com.googlecode.eyesfree.utils.WeakReferenceHandler

/**
 * A display which may present an on-screen overlay which mirrors the content
 * of the Braille display.
 *
 * Note: this display can connect very quickly. To avoid missing any connection
 * state change events, callers should set any necessary listeners before
 * allowing control to return to the [Looper] on the current thread.
 */
class OverlayDisplay(
    private val mContext: Context,
    private val mBackingDisplay: Display
) : Display,
        SharedPreferences.OnSharedPreferenceChangeListener,
        Display.OnConnectionStateChangeListener, Display.OnInputEventListener,
        BrailleView.OnBrailleCellClickListener,
        BrailleView.OnResizeListener {

    private val mMainThreadHandler: MainThreadHandler
    internal val mDisplayThreadHandler: DisplayThreadHandler
    @Volatile
    private var mConnectionStateChangeListener:
            Display.OnConnectionStateChangeListener? = null
    @Volatile
    private var mInputEventListener: Display.OnInputEventListener? = null
    private var mBackingDisplayConnected = false
    private var mSimulateDisplay = false
    private var mOverlayEnabled = false
    internal var mShutdown = false

    // Used when the overlay is on, but no actual display is available.
    private var mSimulatedDisplayProperties = BrailleDisplayProperties(
            0 /* numTextCells */, 0 /* numStatusCells */,
            emptyArray<BrailleKeyBinding>() /* keyBindings */,
            emptyMap<String, String>() /* friendlyKeyNames */)

    init {
        mMainThreadHandler = MainThreadHandler(mContext, this)
        mDisplayThreadHandler = DisplayThreadHandler(this)
        mBackingDisplay.setOnConnectionStateChangeListener(this)
        mBackingDisplay.setOnInputEventListener(this)

        // Finish initializing based on user preferences later.
        // This gives the caller enough time to change the listeners, as long
        // as it is done before control is returned to the Looper.
        val prefs = PreferenceManager.getDefaultSharedPreferences(mContext)
        prefs.registerOnSharedPreferenceChangeListener(this)
        mDisplayThreadHandler.reportPreferenceChange(prefs)
    }

    override fun setOnConnectionStateChangeListener(
            listener: Display.OnConnectionStateChangeListener?) {
        mConnectionStateChangeListener = listener
    }

    override fun setOnConnectionChangeProgressListener(
            listener: Display.OnConnectionChangeProgressListener?) {
        mBackingDisplay.setOnConnectionChangeProgressListener(listener)
    }

    override fun setOnInputEventListener(listener: Display.OnInputEventListener?) {
        mInputEventListener = listener
    }

    override val displayProperties: BrailleDisplayProperties?
        get() = if (!mSimulateDisplay) {
            mBackingDisplay.displayProperties
        } else {
            mSimulatedDisplayProperties
        }

    override fun displayDots(patterns: ByteArray?, text: CharSequence?,
            brailleToTextPositions: IntArray?) {
        mBackingDisplay.displayDots(patterns, text, brailleToTextPositions)
        mMainThreadHandler.displayDots(displayProperties, patterns,
                text, brailleToTextPositions)
    }

    override fun poll() {
        mBackingDisplay.poll()
    }

    override fun disconnect() {
        mBackingDisplay.disconnect()
    }

    override fun shutdown() {
        mShutdown = true
        val prefs = PreferenceManager.getDefaultSharedPreferences(mContext)
        prefs.unregisterOnSharedPreferenceChangeListener(this)
        mMainThreadHandler.hideOverlay()

        mBackingDisplay.shutdown()
    }

    override val isSimulated: Boolean
        get() = mSimulateDisplay

    override fun onConnectionStateChanged(state: Int) {
        mBackingDisplayConnected = (state == Display.STATE_CONNECTED)
        if (mOverlayEnabled && state == Display.STATE_CONNECTED) {
            disconnectSimulatedDisplay()
        }
        if (!mOverlayEnabled || !mSimulateDisplay) {
            // Don't forward disconnect events when the simulated display is
            // active. This prevents upstream disconnect messages from freezing
            // the simulated display.
            reportConnectionStateChange(state)
        }
        if (mOverlayEnabled && state != Display.STATE_CONNECTED) {
            connectSimulatedDisplay()
        }
    }

    override fun onInputEvent(inputEvent: BrailleInputEvent) {
        sendInputEvent(inputEvent)
        mMainThreadHandler.reportInputEvent(inputEvent)
    }

    internal fun sendInputEvent(inputEvent: BrailleInputEvent) {
        val localListener = mInputEventListener
        localListener?.onInputEvent(inputEvent)
    }

    internal fun updateFromSharedPreferences(prefs: SharedPreferences) {
        mOverlayEnabled = SharedPreferencesUtils.getBooleanPref(prefs,
                mContext.resources, R.string.pref_braille_overlay_key,
                R.bool.pref_braille_overlay_default)
        if (mOverlayEnabled) {
            mMainThreadHandler.showOverlay()

            // Don't connect display yet. We'll connect when the overlay
            // has been shown.
        } else {
            mMainThreadHandler.hideOverlay()
            disconnectSimulatedDisplay()
        }
    }

    internal fun connectSimulatedDisplay() {
        if (!mSimulateDisplay && !mBackingDisplayConnected) {
            mSimulateDisplay = true
            reportConnectionStateChange(Display.STATE_CONNECTED)
        }
    }

    private fun disconnectSimulatedDisplay() {
        if (mSimulateDisplay) {
            reportConnectionStateChange(Display.STATE_NOT_CONNECTED)
            mSimulateDisplay = false
        }
    }

    private fun reportConnectionStateChange(state: Int) {
        val localListener = mConnectionStateChangeListener
        localListener?.onConnectionStateChanged(state)
    }

    internal fun resizeDisplay(newSize: Int) {
        val simulatedDisplayWasConnected = mSimulateDisplay
        disconnectSimulatedDisplay()
        mSimulatedDisplayProperties = BrailleDisplayProperties(
                newSize /* numTextCells */,
                mSimulatedDisplayProperties.numStatusCells,
                mSimulatedDisplayProperties.keyBindings,
                mSimulatedDisplayProperties.friendlyKeyNames)
        if (simulatedDisplayWasConnected) {
            connectSimulatedDisplay()
        }
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences?, key: String?) {
        if (mContext.getString(R.string.pref_braille_overlay_key) == key && prefs != null) {
            mDisplayThreadHandler.reportPreferenceChange(prefs)
        }
    }

    override fun onBrailleCellClick(view: BrailleView, cellIndex: Int) {
        mDisplayThreadHandler.sendInputEvent(
                BrailleInputEvent(
                        BrailleInputEvent.CMD_ROUTE,
                        cellIndex,
                        SystemClock.uptimeMillis()))
    }

    override fun onResize(newSize: Int) {
        mDisplayThreadHandler.resizeDisplay(newSize)
    }

    /**
     * Main handler for the overlay display.
     * All UI operations must occur through this handler.
     * To enforce this, the overlay is intentionally private.
     */
    private class MainThreadHandler(
        private val mContext: Context,
        parent: OverlayDisplay
    ) : WeakReferenceHandler<OverlayDisplay>(parent, Looper.getMainLooper()) {

        // Used for passing screen content between threads.
        // Access should be synchronized.
        private var mDisplayProperties: BrailleDisplayProperties? = null
        private var mBraille: ByteArray? = null
        private var mText: CharSequence? = null
        private var mBrailleToTextPositions: IntArray? = null

        private var mOverlay: BrailleOverlay? = null

        fun showOverlay() {
            sendEmptyMessage(MSG_SHOW)
        }

        fun hideOverlay() {
            sendEmptyMessage(MSG_HIDE)
        }

        fun displayDots(
                displayProperties: BrailleDisplayProperties?, patterns: ByteArray?,
                text: CharSequence?, brailleToTextPositions: IntArray?) {
            synchronized(this) {
                mDisplayProperties = displayProperties
                mBraille = patterns
                mText = text
                mBrailleToTextPositions = brailleToTextPositions
            }
            sendEmptyMessage(MSG_DISPLAY_DOTS)
        }

        fun reportInputEvent(event: BrailleInputEvent) {
            obtainMessage(MSG_INPUT_EVENT, event).sendToTarget()
        }

        protected override fun handleMessage(msg: Message, parent: OverlayDisplay) {
            when (msg.what) {
                MSG_SHOW -> handleShow(parent)
                MSG_HIDE -> handleHide()
                MSG_DISPLAY_DOTS -> handleDisplayDots()
                MSG_INPUT_EVENT -> handleInputEvent(msg.obj as BrailleInputEvent)
            }
        }

        private fun handleShow(parent: OverlayDisplay) {
            if (mOverlay == null) {
                mOverlay = BrailleOverlay(mContext, parent)
            }
            mOverlay?.show()
            parent.mDisplayThreadHandler.connectSimulatedDisplay()
        }

        private fun handleHide() {
            mOverlay?.let {
                it.hide()
                mOverlay = null
            }
        }

        private fun handleDisplayDots() {
            val overlay = mOverlay ?: return

            val displayProperties: BrailleDisplayProperties?
            val braille: ByteArray?
            val text: CharSequence?
            val brailleToTextPositions: IntArray?
            synchronized(this) {
                displayProperties = mDisplayProperties
                braille = mBraille
                text = mText
                brailleToTextPositions = mBrailleToTextPositions
            }

            val view = overlay.getBrailleView()
            if (displayProperties != null) {
                view.setDisplayProperties(displayProperties)
            }
            if (braille != null && text != null && brailleToTextPositions != null) {
                view.displayDots(braille, text, brailleToTextPositions)
            }
        }

        private fun handleInputEvent(event: BrailleInputEvent) {
            val overlay = mOverlay ?: return

            if (BrailleInputEvent.argumentType(event.command)
                    == BrailleInputEvent.ARGUMENT_POSITION) {
                val view = overlay.getBrailleView()
                view.highlightCell(event.argument)
            }
        }

        private companion object {
            private const val MSG_SHOW = 1
            private const val MSG_HIDE = 2
            private const val MSG_DISPLAY_DOTS = 3
            private const val MSG_INPUT_EVENT = 4
        }
    }

    /**
     * Handler which runs on the display thread.
     * This is necessary to handle events from the main thread.
     */
    internal class DisplayThreadHandler(
        parent: OverlayDisplay
    ) : WeakReferenceHandler<OverlayDisplay>(parent) {

        fun reportPreferenceChange(prefs: SharedPreferences) {
            obtainMessage(MSG_PREFERENCE_CHANGE, prefs).sendToTarget()
        }

        fun resizeDisplay(newSize: Int) {
            obtainMessage(MSG_RESIZE_DISPLAY, newSize, 0).sendToTarget()
        }

        fun sendInputEvent(event: BrailleInputEvent) {
            obtainMessage(MSG_SEND_INPUT_EVENT, event).sendToTarget()
        }

        fun connectSimulatedDisplay() {
            sendEmptyMessage(MSG_CONNECT_SIMULATED_DISPLAY)
        }

        protected override fun handleMessage(msg: Message, parent: OverlayDisplay) {
            if (parent.mShutdown) {
                return
            }
            when (msg.what) {
                MSG_PREFERENCE_CHANGE -> parent.updateFromSharedPreferences(
                        msg.obj as SharedPreferences)
                MSG_RESIZE_DISPLAY -> parent.resizeDisplay(msg.arg1)
                MSG_SEND_INPUT_EVENT -> parent.sendInputEvent(msg.obj as BrailleInputEvent)
                MSG_CONNECT_SIMULATED_DISPLAY -> parent.connectSimulatedDisplay()
            }
        }

        private companion object {
            private const val MSG_PREFERENCE_CHANGE = 1
            private const val MSG_RESIZE_DISPLAY = 2
            private const val MSG_SEND_INPUT_EVENT = 3
            private const val MSG_CONNECT_SIMULATED_DISPLAY = 4
        }
    }

    private class BrailleOverlay(
        context: Context,
        private val parent: OverlayDisplay
    ) : DraggableOverlay(context) {

        private val mBrailleView: BrailleView

        private val mHoverForwarder = View.OnHoverListener { view, event ->
            val touchEvent = MotionEventUtils.convertHoverToTouch(event)
            try {
                view.dispatchTouchEvent(touchEvent)
            } finally {
                touchEvent.recycle()
            }
        }

        init {
            setContentView(R.layout.overlay)
            mBrailleView = findViewById(R.id.braille_view) as BrailleView
            mBrailleView.setOnHoverListener(mHoverForwarder)
            mBrailleView.setOnBrailleCellClickListener(parent)
            mBrailleView.setOnResizeListener(parent)

            val panLeftButton = findViewById(R.id.pan_left_button) as ImageButton
            panLeftButton.setOnHoverListener(mHoverForwarder)
            panLeftButton.setOnClickListener {
                parent.mDisplayThreadHandler.sendInputEvent(
                        BrailleInputEvent(
                                BrailleInputEvent.CMD_NAV_PAN_LEFT,
                                0 /* argument */,
                                SystemClock.uptimeMillis()))
            }

            val panRightButton = findViewById(R.id.pan_right_button) as ImageButton
            panRightButton.setOnHoverListener(mHoverForwarder)
            panRightButton.setOnClickListener {
                parent.mDisplayThreadHandler.sendInputEvent(
                        BrailleInputEvent(
                                BrailleInputEvent.CMD_NAV_PAN_RIGHT,
                                0 /* argument */,
                                SystemClock.uptimeMillis()))
            }
        }

        fun getBrailleView(): BrailleView = mBrailleView

        protected override fun onStartDragging() {
            mBrailleView.cancelPendingTouches()
        }
    }
}
