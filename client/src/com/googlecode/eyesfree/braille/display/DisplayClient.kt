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

package com.googlecode.eyesfree.braille.display

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Handler
import android.os.IBinder
import android.os.Message
import android.os.RemoteException
import android.util.Log

/** A client for the braille display service. */
class DisplayClient(context: Context) : Display {
    companion object {
        private const val LOG_TAG = "DisplayClient"

        /** Service name used for connecting to the service. */
        const val ACTION_DISPLAY_SERVICE =
            "com.googlecode.eyesfree.braille.service.ACTION_DISPLAY_SERVICE"

        /** Delay before the first rebind attempt on bind error or service disconnect. */
        private const val REBIND_DELAY_MILLIS = 500
        private const val MAX_REBIND_ATTEMPTS = 5

        private const val MSG_REPORT_CONNECTION_STATE = 1
        private const val MSG_REPORT_CONNECTION_CHANGE_PROGRESS = 2
        private const val MSG_REPORT_INPUT_EVENT = 3
        private const val MSG_REBIND_SERVICE = 4
    }

    @Volatile
    private var mConnectionStateChangeListener: OnConnectionStateChangeListener? = null
    @Volatile
    private var mConnectionChangeProgressListener: OnConnectionChangeProgressListener? = null
    @Volatile
    private var mInputEventListener: OnInputEventListener? = null
    private val mContext: Context = context
    private val mHandler = DisplayHandler()
    private var mConnection: Connection? = null
    private var mCurrentConnectionState = STATE_UNKNOWN
    private var mDisplayProperties: BrailleDisplayProperties? = null
    private val mServiceCallback = ServiceCallback()

    /** Protected by synchronizing on mHandler. */
    private var mNumFailedBinds = 0
    @Volatile
    private var mShutdown = false

    init {
        doBindService()
    }

    override fun setOnConnectionStateChangeListener(listener: OnConnectionStateChangeListener?) {
        mConnectionStateChangeListener = listener
    }

    override fun setOnConnectionChangeProgressListener(listener: OnConnectionChangeProgressListener?) {
        mConnectionChangeProgressListener = listener
    }

    override fun setOnInputEventListener(listener: OnInputEventListener?) {
        mInputEventListener = listener
    }

    override val displayProperties: BrailleDisplayProperties?
        get() = mDisplayProperties

    override fun displayDots(
        patterns: ByteArray?, text: CharSequence?, brailleToTextPositions: IntArray?
    ) {
        val localService = brailleService
        if (localService != null) {
            try {
                localService.displayDots(patterns)
            } catch (ex: RemoteException) {
                Log.e(LOG_TAG, "Error in displayDots", ex)
            }
        } else {
            Log.v(LOG_TAG, "Error in displayDots: service not connected")
        }
    }

    override fun poll() {
        val localService = brailleService
        if (localService != null) {
            try {
                localService.poll()
            } catch (ex: RemoteException) {
                Log.e(LOG_TAG, "Error in poll", ex)
            }
        } else {
            Log.v(LOG_TAG, "Error in poll: service not connected")
        }
    }

    override fun shutdown() {
        mShutdown = true
        doUnbindService()
    }

    override fun disconnect() {
        val localService = brailleService
        if (localService != null) {
            try {
                localService.disconnect()
            } catch (ex: RemoteException) {
                Log.e(LOG_TAG, "Error in disconnect", ex)
            }
        } else {
            Log.v(LOG_TAG, "Error in disconnect: service not connected")
        }
    }

    override val isSimulated: Boolean
        get() = false

    // NOTE: The methods in this class will be executed in the main application
    // thread.
    private inner class Connection : ServiceConnection {
        @Volatile
        var mService: IBrailleService? = null

        override fun onServiceConnected(className: ComponentName, binder: IBinder) {
            Log.i(LOG_TAG, "Connected to braille service")
            val localService = IBrailleService.Stub.asInterface(binder)
            try {
                localService.registerCallback(mServiceCallback)
                mService = localService
                synchronized(mHandler) {
                    mNumFailedBinds = 0
                }
            } catch (e: RemoteException) {
                // In this case the service has crashed before we could even do
                // anything with it.
                Log.e(LOG_TAG, "Failed to register callback on service", e)
                // We should get a disconnected call and the rebind and failure
                // reporting happens in that handler.
            }
        }

        override fun onServiceDisconnected(className: ComponentName) {
            mService = null
            Log.e(LOG_TAG, "Disconnected from braille service")
            // Report display disconnected for now, this will turn into a
            // connected state or error state depending on how the retrying goes.
            mHandler.reportConnectionState(STATE_NOT_CONNECTED, null)
            mHandler.scheduleRebind()
        }
    }

    // NOTE: The methods of this class will be executed in the IPC thread pool
    // and not on the main application thread.
    private inner class ServiceCallback : IBrailleServiceCallback.Stub() {
        override fun onDisplayConnected(displayProperties: BrailleDisplayProperties?) {
            mHandler.reportConnectionState(STATE_CONNECTED, displayProperties)
        }

        override fun onDisplayDisconnected() {
            mHandler.reportConnectionState(STATE_NOT_CONNECTED, null)
        }

        override fun onInput(inputEvent: BrailleInputEvent?) {
            mHandler.reportInputEvent(inputEvent)
        }

        override fun onConnectionChangeProgress(description: String?) {
            mHandler.reportConnectionChangeProgress(description)
        }
    }

    private fun doBindService() {
        val localConnection = Connection()
        val serviceIntent = Intent(ACTION_DISPLAY_SERVICE)
        val pm = mContext.packageManager
        val resolveInfo = pm.queryIntentServices(serviceIntent, 0)
        if (resolveInfo == null || resolveInfo.isEmpty()) {
            Log.e(LOG_TAG, "Unable to create serviceIntent")
            return
        }
        val serviceInfo = resolveInfo[0]
        val packageName = serviceInfo.serviceInfo.packageName
        val className = serviceInfo.serviceInfo.name
        val component = ComponentName(packageName, className)

        // Create a new intent. Use the old one for extras and such reuse
        val explicitIntent = Intent(serviceIntent)
        explicitIntent.setComponent(component)

        if (!mContext.bindService(explicitIntent, localConnection, Context.BIND_AUTO_CREATE)) {
            Log.e(LOG_TAG, "Failed to bind Service")
            mHandler.scheduleRebind()
            return
        }
        mConnection = localConnection
        Log.i(LOG_TAG, "Bound to braille service")
    }

    private fun doUnbindService() {
        val localService = brailleService
        if (localService != null) {
            try {
                localService.unregisterCallback(mServiceCallback)
            } catch (e: RemoteException) {
                // Nothing to do if the service can't be reached.
            }
        }
        val localConnection = mConnection
        if (localConnection != null) {
            mContext.unbindService(localConnection)
            mConnection = null
        }
    }

    private val brailleService: IBrailleService?
        get() = mConnection?.mService

    private inner class DisplayHandler : Handler() {
        fun reportConnectionState(newState: Int, displayProperties: BrailleDisplayProperties?) {
            obtainMessage(MSG_REPORT_CONNECTION_STATE, newState, 0, displayProperties)
                .sendToTarget()
        }

        fun reportConnectionChangeProgress(description: String?) {
            obtainMessage(MSG_REPORT_CONNECTION_CHANGE_PROGRESS, description).sendToTarget()
        }

        fun reportInputEvent(event: BrailleInputEvent?) {
            obtainMessage(MSG_REPORT_INPUT_EVENT, event).sendToTarget()
        }

        fun scheduleRebind() {
            synchronized(this) {
                if (mNumFailedBinds < MAX_REBIND_ATTEMPTS) {
                    val delay = REBIND_DELAY_MILLIS shl mNumFailedBinds
                    sendEmptyMessageDelayed(MSG_REBIND_SERVICE, delay.toLong())
                    ++mNumFailedBinds
                    Log.w(LOG_TAG, String.format(
                        "Will rebind to braille service in %d ms.", delay))
                } else {
                    reportConnectionState(STATE_ERROR, null)
                }
            }
        }

        override fun handleMessage(msg: Message) {
            if (mShutdown) {
                return
            }
            when (msg.what) {
                MSG_REPORT_CONNECTION_STATE ->
                    handleReportConnectionState(msg.arg1, msg.obj as BrailleDisplayProperties?)
                MSG_REPORT_CONNECTION_CHANGE_PROGRESS ->
                    handleReportConnectionChangeProgress(msg.obj as String?)
                MSG_REPORT_INPUT_EVENT ->
                    handleReportInputEvent(msg.obj as BrailleInputEvent?)
                MSG_REBIND_SERVICE -> handleRebindService()
            }
        }

        private fun handleReportConnectionState(
            newState: Int, displayProperties: BrailleDisplayProperties?
        ) {
            mDisplayProperties = displayProperties
            val localListener = mConnectionStateChangeListener
            if (newState != mCurrentConnectionState && localListener != null) {
                localListener.onConnectionStateChanged(newState)
            }
            mCurrentConnectionState = newState
        }

        private fun handleReportConnectionChangeProgress(description: String?) {
            val localListener = mConnectionChangeProgressListener
            if (localListener != null) {
                localListener.onConnectionChangeProgress(description)
            }
        }

        private fun handleReportInputEvent(event: BrailleInputEvent?) {
            val localListener = mInputEventListener
            if (localListener != null) {
                localListener.onInputEvent(event)
            }
        }

        private fun handleRebindService() {
            if (mConnection != null) {
                doUnbindService()
            }
            doBindService()
        }
    }
}
