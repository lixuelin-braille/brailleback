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

package com.googlecode.eyesfree.braille.selfbraille

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Message
import android.os.RemoteException
import android.util.Log
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException

/**
 * Client-side interface to the self brailling interface.
 *
 * Threading: Instances of this object should be created and shut down in a
 * thread with a [android.os.Looper] associated with it. Other methods may be
 * called on any thread.
 */
class SelfBrailleClient(context: Context, private val mAllowDebugService: Boolean) {
    private val mIdentity = Binder()
    private val mContext: Context = context
    private val mHandler = SelfBrailleHandler()
    private var mShutdown = false

    /** Written in handler thread, read in any thread calling methods on the object. */
    @Volatile
    private var mConnection: Connection? = null

    /** Protected by synchronizing on mHandler. */
    private var mNumFailedBinds = 0

    init {
        doBindService()
    }

    /**
     * Shuts this instance down, deallocating any global resources it is using.
     * This method must be called on the same thread that created this object.
     */
    fun shutdown() {
        mShutdown = true
        doUnbindService()
    }

    fun write(writeData: WriteData) {
        writeData.validate()
        val localService = selfBrailleService
        if (localService != null) {
            try {
                localService.write(mIdentity, writeData)
            } catch (ex: RemoteException) {
                Log.e(LOG_TAG, "Self braille write failed", ex)
            }
        }
    }

    private fun doBindService() {
        val localConnection = Connection()
        if (!mContext.bindService(mServiceIntent, localConnection, Context.BIND_AUTO_CREATE)) {
            Log.e(LOG_TAG, "Failed to bind to service")
            mHandler.scheduleRebind()
            return
        }
        mConnection = localConnection
        Log.i(LOG_TAG, "Bound to self braille service")
    }

    private fun doUnbindService() {
        val localConnection = mConnection
        if (localConnection != null) {
            val localService = localConnection.mService
            if (localService != null) {
                try {
                    localService.disconnect(mIdentity)
                } catch (ex: RemoteException) {
                    // Nothing to do.
                }
            }
            mContext.unbindService(localConnection)
            mConnection = null
        }
    }

    private val selfBrailleService: ISelfBrailleService?
        get() = mConnection?.mService

    private fun verifyPackage(): Boolean {
        val pm = mContext.packageManager
        val pi: PackageInfo = try {
            pm.getPackageInfo(BRAILLE_BACK_PACKAGE, PackageManager.GET_SIGNATURES)
        } catch (ex: PackageManager.NameNotFoundException) {
            Log.w(LOG_TAG, "Can't verify package " + BRAILLE_BACK_PACKAGE, ex)
            return false
        }
        val digest: MessageDigest = try {
            MessageDigest.getInstance("SHA-1")
        } catch (ex: NoSuchAlgorithmException) {
            Log.e(LOG_TAG, "SHA-1 not supported", ex)
            return false
        }
        // Check if any of the certificates match our hash.
        for (signature in pi.signatures ?: return false) {
            digest.update(signature.toByteArray())
            if (MessageDigest.isEqual(EYES_FREE_CERT_SHA1, digest.digest())) {
                return true
            }
            digest.reset()
        }
        if (mAllowDebugService) {
            Log.w(LOG_TAG, String.format(
                "*** %s connected to BrailleBack with invalid (debug?) signature ***",
                mContext.packageName))
            return true
        }
        return false
    }

    private inner class Connection : ServiceConnection {
        // Read in application threads, written in main thread.
        @Volatile
        var mService: ISelfBrailleService? = null

        override fun onServiceConnected(className: ComponentName, binder: IBinder) {
            if (!verifyPackage()) {
                Log.w(LOG_TAG, String.format(
                    "Service certificate mismatch for %s, dropping connection",
                    BRAILLE_BACK_PACKAGE))
                mHandler.unbindService()
                return
            }
            Log.i(LOG_TAG, "Connected to self braille service")
            mService = ISelfBrailleService.Stub.asInterface(binder)
            synchronized(mHandler) {
                mNumFailedBinds = 0
            }
        }

        override fun onServiceDisconnected(className: ComponentName) {
            Log.e(LOG_TAG, "Disconnected from self braille service")
            mService = null
            // Retry by rebinding.
            mHandler.scheduleRebind()
        }
    }

    private inner class SelfBrailleHandler : Handler() {
        fun scheduleRebind() {
            synchronized(this) {
                if (mNumFailedBinds < MAX_REBIND_ATTEMPTS) {
                    val delay = REBIND_DELAY_MILLIS shl mNumFailedBinds
                    sendEmptyMessageDelayed(MSG_REBIND_SERVICE, delay.toLong())
                    ++mNumFailedBinds
                }
            }
        }

        fun unbindService() {
            sendEmptyMessage(MSG_UNBIND_SERVICE)
        }

        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_REBIND_SERVICE -> handleRebindService()
                MSG_UNBIND_SERVICE -> handleUnbindService()
            }
        }

        private fun handleRebindService() {
            if (mShutdown) {
                return
            }
            if (mConnection != null) {
                doUnbindService()
            }
            doBindService()
        }

        private fun handleUnbindService() {
            doUnbindService()
        }
    }

    companion object {
        private const val LOG_TAG = "SelfBrailleClient"
        private const val ACTION_SELF_BRAILLE_SERVICE =
            "com.googlecode.eyesfree.braille.service.ACTION_SELF_BRAILLE_SERVICE"
        private const val BRAILLE_BACK_PACKAGE = "com.googlecode.eyesfree.brailleback"
        private val mServiceIntent = Intent(ACTION_SELF_BRAILLE_SERVICE)
            .setPackage(BRAILLE_BACK_PACKAGE)

        /**
         * SHA-1 hash value of the Eyes-Free release key certificate, used to
         * sign BrailleBack. It was generated from the keystore with:
         * $ keytool -exportcert -keystore <keystorefile> -alias android.keystore > cert
         * $ keytool -printcert -file cert
         */
        private val EYES_FREE_CERT_SHA1 = byteArrayOf(
            (0x9B).toByte(), (0x42).toByte(), (0x4C).toByte(), (0x2D).toByte(),
            (0x27).toByte(), (0xAD).toByte(), (0x51).toByte(), (0xA4).toByte(),
            (0x2A).toByte(), (0x33).toByte(), (0x7E).toByte(), (0x0B).toByte(),
            (0xB6).toByte(), (0x99).toByte(), (0x1C).toByte(), (0x76).toByte(),
            (0xEC).toByte(), (0xA4).toByte(), (0x44).toByte(), (0x61).toByte())

        /** Delay before the first rebind attempt on bind error or service disconnect. */
        private const val REBIND_DELAY_MILLIS = 500
        private const val MAX_REBIND_ATTEMPTS = 5

        private const val MSG_REBIND_SERVICE = 1
        private const val MSG_UNBIND_SERVICE = 2
    }
}
