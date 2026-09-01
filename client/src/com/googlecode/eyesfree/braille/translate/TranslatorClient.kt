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

package com.googlecode.eyesfree.braille.translate

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
import java.util.Arrays

/**
 * Client-side interface to the central braille translator service.
 *
 * This class can be used to retrieve [BrailleTranslator] instances for
 * performing translation between text and braille cells.
 *
 * Typically, an instance of this class is created at application
 * initialization time and destroyed using the [destroy] method when the
 * application is about to be destroyed. It is recommended that the instance is
 * destroyed and recreated if braille translation is not going to be needed for
 * a long period of time.
 *
 * Threading: The object must be destroyed on the same thread it was created.
 * Other methods may be called from any thread.
 */
class TranslatorClient(context: Context, onInitListener: OnInitListener?) {
    companion object {
        private const val LOG_TAG = "TranslatorClient"
        private const val ACTION_TRANSLATOR_SERVICE =
            "com.googlecode.eyesfree.braille.service.ACTION_TRANSLATOR_SERVICE"

        /** Delay before the first rebind attempt on bind error or service disconnect. */
        private const val REBIND_DELAY_MILLIS = 500
        private const val MAX_REBIND_ATTEMPTS = 5

        const val ERROR = -1
        const val SUCCESS = 0

        private const val MSG_ON_INIT = 1
        private const val MSG_REBIND_SERVICE = 2
    }

    /** A callback interface to get notified when the translation manager is ready. */
    interface OnInitListener {
        /**
         * Called exactly once when it has been determined that the translation
         * service is either ready to be used (`SUCCESS`) or the service is not
         * available (`ERROR`).
         */
        fun onInit(status: Int)
    }

    private val mContext: Context = context
    private val mHandler = TranslatorClientHandler()
    private val mServiceCallback = ServiceCallback()

    private var mOnInitListener: OnInitListener? = onInitListener
    private var mConnection: Connection? = null

    /** Protected by synchronizing on mHandler. */
    private var mNumFailedBinds = 0

    init {
        doBindService()
    }

    /**
     * Destroys this instance, deallocating any global resources it is using.
     * Any [BrailleTranslator] objects that were created using this object are
     * invalid after this call.
     */
    fun destroy() {
        doUnbindService()
        mHandler.destroy()
    }

    /**
     * Returns a list of installed translation tables with meta-data describing
     * them. Returns `null` if the translation service has not been initialized,
     * or an error occurs.
     */
    fun getTables(): List<TableInfo>? {
        val localService = translatorService
        if (localService != null) {
            try {
                return Arrays.asList(*localService.getTableInfos())
            } catch (ex: RemoteException) {
                Log.e(LOG_TAG, "Error in getTables", ex)
            }
        }
        return null
    }

    /**
     * Returns a new [BrailleTranslator] for the translation table specified by
     * [tableId]. Translation tables can be discovered using [getTables].
     */
    fun getTranslator(tableId: String): BrailleTranslator? {
        val localService = translatorService
        if (localService != null) {
            try {
                if (localService.checkTable(tableId)) {
                    val tables = getTables()
                    if (tables != null) {
                        for (table in tables) {
                            if (table.id == tableId) {
                                return BrailleTranslatorImpl(table)
                            }
                        }
                    }
                }
            } catch (ex: RemoteException) {
                Log.e(LOG_TAG, "Error in getTranslator", ex)
            }
        }
        return null
    }

    private fun doBindService() {
        val localConnection = Connection()
        val serviceIntent = Intent(ACTION_TRANSLATOR_SERVICE)
        val pm = mContext.packageManager
        val resolveInfo = pm.queryIntentServices(serviceIntent, 0)
        if (resolveInfo == null || resolveInfo.isEmpty()) {
            Log.e(LOG_TAG, "Unable to create intent")
            return
        }

        // Get component info and create ComponentName
        val serviceInfo = resolveInfo[0]
        val packageName = serviceInfo.serviceInfo.packageName
        val className = serviceInfo.serviceInfo.name
        val component = ComponentName(packageName, className)

        // Create a new intent. Use the old one for extras and such reuse
        val explicitIntent = Intent(serviceIntent)
        explicitIntent.setComponent(component)

        if (!mContext.bindService(explicitIntent, localConnection, Context.BIND_AUTO_CREATE)) {
            Log.e(LOG_TAG, "Failed to bind to service")
            mHandler.scheduleRebind()
            return
        }
        mConnection = localConnection
        Log.i(LOG_TAG, "Bound to translator service")
    }

    private fun doUnbindService() {
        val localConnection = mConnection
        if (localConnection != null) {
            mContext.unbindService(localConnection)
            mConnection = null
        }
    }

    private val translatorService: ITranslatorService?
        get() = mConnection?.mService

    private inner class Connection : ServiceConnection {
        // Read in application threads, written in main thread.
        @Volatile
        var mService: ITranslatorService? = null

        override fun onServiceConnected(className: ComponentName, binder: IBinder) {
            Log.i(LOG_TAG, "Connected to translation service")
            val localService = ITranslatorService.Stub.asInterface(binder)
            try {
                localService.setCallback(mServiceCallback)
                mService = localService
                synchronized(mHandler) {
                    mNumFailedBinds = 0
                }
            } catch (ex: RemoteException) {
                // Service went away, rely on disconnect handler to schedule a rebind.
                Log.e(LOG_TAG, "Error when setting callback", ex)
            }
        }

        override fun onServiceDisconnected(className: ComponentName) {
            Log.e(LOG_TAG, "Disconnected from translator service")
            mService = null
            // Retry by rebinding, and finally call the onInit if applicable.
            mHandler.scheduleRebind()
        }
    }

    private inner class BrailleTranslatorImpl(private val mTableInfo: TableInfo) : BrailleTranslator {
        override fun translate(
            text: String?, cursorPosition: Int, computerBrailleAtCursor: Boolean
        ): TranslationResult? {
            val localService = translatorService
            if (localService != null) {
                try {
                    return localService.translate(
                        text, mTableInfo.id, cursorPosition, computerBrailleAtCursor)
                } catch (ex: RemoteException) {
                    Log.e(LOG_TAG, "Error in translate", ex)
                }
            }
            return null
        }

        override fun translate(text: String?, cursorPosition: Int): TranslationResult? {
            return translate(text, cursorPosition, false)
        }

        override fun backTranslate(cells: ByteArray?): String? {
            val localService = translatorService
            if (localService != null) {
                try {
                    return localService.backTranslate(cells, mTableInfo.id)
                } catch (ex: RemoteException) {
                    Log.e(LOG_TAG, "Error in backTranslate", ex)
                }
            }
            return null
        }

        override val tableInfo: TableInfo
            get() = mTableInfo

        override fun equals(other: Any?): Boolean {
            if (this === other) {
                return true
            }
            if (other is BrailleTranslatorImpl) {
                return mTableInfo.id == other.mTableInfo.id
            }
            return false
        }

        override fun hashCode(): Int {
            return mTableInfo.id.hashCode()
        }

        override fun toString(): String {
            return String.format("{BrailleTranslatorImpl %s}", mTableInfo.id)
        }
    }

    private inner class ServiceCallback : ITranslatorServiceCallback.Stub() {
        override fun onInit(status: Int) {
            mHandler.onInit(status)
        }
    }

    private inner class TranslatorClientHandler : Handler() {
        fun onInit(status: Int) {
            obtainMessage(MSG_ON_INIT, status, 0).sendToTarget()
        }

        fun destroy() {
            mOnInitListener = null
            // Cancel outstanding messages, most importantly scheduled rebinds.
            removeCallbacksAndMessages(null)
        }

        fun scheduleRebind() {
            synchronized(this) {
                if (mNumFailedBinds < MAX_REBIND_ATTEMPTS) {
                    val delay = REBIND_DELAY_MILLIS shl mNumFailedBinds
                    sendEmptyMessageDelayed(MSG_REBIND_SERVICE, delay.toLong())
                    ++mNumFailedBinds
                } else {
                    onInit(ERROR)
                }
            }
        }

        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_ON_INIT -> handleOnInit(msg.arg1)
                MSG_REBIND_SERVICE -> handleRebindService()
            }
        }

        private fun handleOnInit(status: Int) {
            if (mOnInitListener != null) {
                mOnInitListener!!.onInit(status)
                mOnInitListener = null
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
