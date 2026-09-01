package com.googlecode.eyesfree.braille.service.display

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.res.Resources
import android.util.Log
import com.googlecode.eyesfree.braille.display.BrailleDisplayProperties
import com.googlecode.eyesfree.braille.service.R
import java.io.File
import java.io.IOException
import java.util.List

/**
 * The thread that connects to a device that it finds, reads from the device and
 * starts another thread that manages the driver.
 */
class ReadThread(
    private val mDisplayService: DisplayService,
    private val mTablesDir: File,
    private val mBluetoothAddressToConnectTo: String?
) : Thread(), DriverThread.OnInitListener {
    private val LOG_TAG = ReadThread::class.java.simpleName

    private val mDeviceFinder: DeviceFinder = DeviceFinder(mDisplayService)
    private val mResources: Resources = mDisplayService.resources
    @Volatile
    private var mSocket: BluetoothSocket? = null
    @Volatile
    private var mDisconnecting = false
    @Volatile
    private var mDriverThread: DriverThread? = null
    @Volatile
    private var mConnectedDeviceInfo: DeviceFinder.DeviceInfo? = null

    override fun run() {
        try {
            if (connect()) {
                readLoop()
            }
        } finally {
            cleanup()
        }
    }

    /** Returns the driver thread, or null if not connected. */
    fun getDriverThread(): DriverThread? = mDriverThread

    /**
     * Asynchronously disconnects, which will eventually lead to the thread
     * terminating. If not connected yet, the attempts to connect to a display
     * will be aborted. Calls back into [DisplayService.onDisplayDisconnected]
     * before dying.
     */
    fun disconnect() {
        closeSocket()
        mDisconnecting = true
    }

    private fun connect(): Boolean {
        mDisplayService.setConnectionProgress(
            mResources.getString(R.string.connprog_trying_rfcomm)
        )
        val bonded = mDeviceFinder.findDevices() as List<DeviceFinder.DeviceInfo>
        if (bonded.isNotEmpty()) {
            tryToConnect(bonded)
        } else {
            mDisplayService.setConnectionProgress(
                mResources.getString(R.string.connprog_no_devices)
            )
        }
        if (mSocket != null) {
            val info = mConnectedDeviceInfo!!
            val bthDev = info.bluetoothDevice
            mDisplayService.setConnectionProgress(
                mResources.getString(R.string.connprog_initializing, bthDev.name)
            )
            try {
                mDriverThread = DriverThread(
                    mSocket!!.outputStream,
                    info,
                    mResources,
                    mTablesDir,
                    mDisplayService,
                    this,
                    mDisplayService
                )
                Log.i(LOG_TAG, "Device connected")
                return true
            } catch (ex: IOException) {
                Log.e(LOG_TAG, "Error while starting driver thread", ex)
            }
        }
        return false
    }

    private fun readLoop() {
        try {
            val buf = ByteArray(128)
            var readSize: Int
            do {
                readSize = mSocket!!.inputStream.read(buf, 0, buf.size)
                if (readSize > 0) {
                    mDriverThread!!.addReadOperation(buf, readSize)
                }
            } while (readSize >= 0)
            Log.i(LOG_TAG, "End of input from device.")
        } catch (ex: IOException) {
            Log.i(LOG_TAG, "Socket closed while reading: $ex")
        }
    }

    private fun tryToConnect(bonded: List<DeviceFinder.DeviceInfo>) {
        mSocket = null
        try {
            for (dev in bonded) {
                if (mDisconnecting) {
                    return
                }
                val bthDev = dev.bluetoothDevice
                if (mBluetoothAddressToConnectTo != null &&
                    mBluetoothAddressToConnectTo != bthDev.address
                ) {
                    continue
                }
                mDisplayService.setConnectionProgress(
                    mResources.getString(R.string.connprog_trying, bthDev.name)
                )
                Log.d(LOG_TAG, "Trying to connect to braille device: " + bthDev.name)
                try {
                    val socket: BluetoothSocket? = if (dev.connectSecurely) {
                        bthDev.createRfcommSocketToServiceRecord(dev.sdpUuid)
                    } else {
                        bthDev.createInsecureRfcommSocketToServiceRecord(dev.sdpUuid)
                    }
                    if (socket != null) {
                        socket.connect()
                        mSocket = socket
                        mConnectedDeviceInfo = dev
                    }
                    return
                } catch (ex: IOException) {
                    Log.e(LOG_TAG, "Error opening a socket: " + ex.toString())
                }
            }
        } finally {
            if (mSocket == null) {
                mDisplayService.setConnectionProgress(null)
            }
        }
    }

    private fun closeSocket() {
        if (mSocket != null) {
            try {
                mSocket!!.close()
            } catch (ex: IOException) {
                Log.d(LOG_TAG, "Error closing socket: ", ex)
            }
        }
    }

    private fun cleanup() {
        closeSocket()
        if (mDriverThread != null) {
            val localDriverThread = mDriverThread
            mDriverThread = null
            localDriverThread!!.stop()
        }
        mDisplayService.onDisplayDisconnected()
        Log.i(LOG_TAG, "Display disconnected")
    }

    override fun onInit(properties: BrailleDisplayProperties?) {
        if (properties != null) {
            mDeviceFinder.rememberSuccessfulConnection(mConnectedDeviceInfo)
            mDisplayService.setConnectionProgress(null)
            mDisplayService.onDisplayConnected(properties)
        } else {
            val bthDev = mConnectedDeviceInfo!!.bluetoothDevice
            mDisplayService.setConnectionProgress(
                mResources.getString(
                    R.string.connprog_failed_to_initialize,
                    bthDev.name
                )
            )
            disconnect()
        }
    }
}
