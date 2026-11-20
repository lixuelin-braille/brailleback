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
        mDisconnecting = true
        closeSocket()
    }

    private fun connect(): Boolean {
        mDisplayService.setConnectionProgress(
            mResources.getString(R.string.connprog_trying_rfcomm)
        )
        @Suppress("UNCHECKED_CAST")
        val bonded = mDeviceFinder.findDevices() as List<DeviceFinder.DeviceInfo>
        if (bonded.isNotEmpty()) {
            tryToConnect(bonded)
        } else {
            mDisplayService.setConnectionProgress(
                mResources.getString(R.string.connprog_no_devices)
            )
        }
        if (mDisconnecting) {
            return false
        }
        // socket 与设备信息各取一次，避免中间被清空时读到半截状态。
        val socket = mSocket
        val info = mConnectedDeviceInfo
        if (socket != null && info != null) {
            // 设备信息里没有 BluetoothDevice（已解绑或被回收）就按连接失败处理。
            val bthDev = info.getBluetoothDevice() ?: return false
            mDisplayService.setConnectionProgress(
                mResources.getString(R.string.connprog_initializing, deviceName(bthDev))
            )
            try {
                mDriverThread = DriverThread(
                    socket.outputStream,
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
        // 一个读循环对应一个 socket：进来时取一次，断连后不再重取，
        // 原来每轮都用非空断言去读属性，断连瞬间会把读线程炸掉。
        val socket = mSocket ?: return
        try {
            val buf = ByteArray(128)
            var readSize = 0
            var running = true
            while (running) {
                readSize = socket.inputStream.read(buf, 0, buf.size)
                if (readSize > 0) {
                    // 驱动线程被别的线程置空就收工：不再向已停止的线程投递数据。
                    val driver = mDriverThread
                    if (driver == null) {
                        running = false
                    } else {
                        driver.addReadOperation(buf, readSize)
                    }
                }
                if (readSize < 0) {
                    running = false
                }
            }
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
                // 设备信息不完整（已解绑/被回收）就跳过这台，继续找下一台。
                val bthDev = dev.getBluetoothDevice() ?: continue
                val address = try {
                    bthDev.address
                } catch (ex: SecurityException) {
                    Log.w(LOG_TAG, "BLUETOOTH_CONNECT permission denied", ex)
                    continue
                }
                if (mBluetoothAddressToConnectTo != null &&
                    mBluetoothAddressToConnectTo != address
                ) {
                    continue
                }
                val name = deviceName(bthDev)
                mDisplayService.setConnectionProgress(
                    mResources.getString(R.string.connprog_trying, name)
                )
                Log.d(LOG_TAG, "Trying to connect to braille device: $name")
                var socket: BluetoothSocket? = null
                try {
                    // SDP UUID 缺失就没有可连接的服务记录，跳过这台。
                    val sdpUuid = dev.getSdpUuid() ?: continue
                    val newSocket = if (dev.getConnectSecurely()) {
                        bthDev.createRfcommSocketToServiceRecord(sdpUuid)
                    } else {
                        bthDev.createInsecureRfcommSocketToServiceRecord(sdpUuid)
                    }
                    socket = newSocket
                    newSocket.connect()
                    if (mDisconnecting) {
                        return
                    }
                    mSocket = newSocket
                    mConnectedDeviceInfo = dev
                    return
                } catch (ex: IOException) {
                    Log.e(LOG_TAG, "Error opening a socket: " + ex.toString())
                } catch (ex: SecurityException) {
                    Log.e(LOG_TAG,
                        "BLUETOOTH_CONNECT permission denied opening socket", ex)
                } finally {
                    if (mSocket == null) {
                        closeQuietly(socket)
                    }
                }
            }
        } finally {
            if (mSocket == null) {
                mDisplayService.setConnectionProgress(null)
            }
        }
    }

    private fun closeSocket() {
        closeQuietly(mSocket)
    }

    private fun closeQuietly(socket: BluetoothSocket?) {
        if (socket == null) {
            return
        }
        try {
            socket.close()
        } catch (ex: IOException) {
            Log.d(LOG_TAG, "Error closing socket: ", ex)
        }
    }

    private fun deviceName(device: BluetoothDevice): String {
        return try {
            device.name ?: device.address ?: "?"
        } catch (ex: SecurityException) {
            Log.w(LOG_TAG,
                "BLUETOOTH_CONNECT permission denied reading device name", ex)
            "?"
        }
    }

    private fun cleanup() {
        closeSocket()
        // 先摘下引用再停止：别的线程可能已经把 mDriverThread 清掉了。
        val driverThread = mDriverThread
        mDriverThread = null
        driverThread?.stop()
        mDisplayService.onDisplayDisconnected()
        Log.i(LOG_TAG, "Display disconnected")
    }

    override fun onInit(displayProperties: BrailleDisplayProperties?) {
        if (displayProperties != null) {
            mConnectedDeviceInfo?.let {
                mDeviceFinder.rememberSuccessfulConnection(it)
            }
            mDisplayService.setConnectionProgress(null)
            mDisplayService.onDisplayConnected(displayProperties)
        } else {
            val info = mConnectedDeviceInfo
            if (info == null) {
                disconnect()
                return
            }
            val bthDev = info.getBluetoothDevice()
            if (bthDev == null) {
                disconnect()
                return
            }
            mDisplayService.setConnectionProgress(
                mResources.getString(
                    R.string.connprog_failed_to_initialize,
                    deviceName(bthDev)
                )
            )
            disconnect()
        }
    }
}
