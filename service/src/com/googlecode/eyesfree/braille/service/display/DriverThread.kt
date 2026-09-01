package com.googlecode.eyesfree.braille.service.display

import android.content.Context
import android.content.res.Resources
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.util.Log
import com.googlecode.eyesfree.braille.display.BrailleDisplayProperties
import com.googlecode.eyesfree.braille.display.BrailleInputEvent
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * A thread that manages an instance of a braille driver.
 *
 * Threading model: The methods of this class can be called from any thread.
 * Calls will be forwarded to an internal thread and executed asynchronously.
 * The methods of the nested callback interfaces will be invoked in an internal
 * thread and should therefore finish quickly without blocking.
 */
class DriverThread(
    outputStream: OutputStream,
    deviceInfo: DeviceFinder.DeviceInfo,
    resources: Resources,
    tablesDir: File,
    context: Context,
    private val initListener: OnInitListener,
    private val inputEventListener: OnInputEventListener
) {
    private val LOG_TAG = DriverThread::class.java.simpleName
    private val STOP_WAIT_MILLIS: Long = 1000
    /** Wake up the handler thread to poll for input from the device. */
    private val MSG_READ = 1
    /** Wake up the thread to call writeWindow of the driver. */
    private val MSG_WRITE = 2
    /** Gracefully stop driver thread. */
    private val MSG_STOP = 3
    private val COMMAND_CODE_MASK = 0xffff
    private val COMMAND_ARGUMENT_MASK = 0x7fff0000
    private val COMMAND_ARGUMENT_SHIFT = 16

    private val mHandler: Handler
    private val mHandlerThread: HandlerThread

    @Volatile
    private var writeBuffer: ByteArray? = null

    /** Stream for writing to the device. */
    private val mOutputStream: OutputStream = outputStream

    private val mBrlttyWrapper: BrlttyWrapper =
        BrlttyWrapper(deviceInfo, this, resources, tablesDir, context)

    /**
     * Callback interface for getting notified when the driver initialization
     * has either succeeded or failed.
     */
    interface OnInitListener {
        /**
         * Called when initialization either succeeded or failed. In the former
         * case, [displayProperties] is non-null and contains the properties of
         * the connected display. If initialization failed, [displayProperties]
         * is `null`.
         */
        fun onInit(displayProperties: BrailleDisplayProperties?)
    }

    /**
     * Callback interface for input events from the display.
     */
    interface OnInputEventListener {
        /**
         * Dispatch an input event to interested users. This is called from the
         * driver thread, so any lengthy processing should be deferred to a
         * different thread.
         */
        fun onInputEvent(event: BrailleInputEvent)
    }

    init {
        mHandlerThread = object : HandlerThread("DriverTrhead") {
            override fun onLooperPrepared() {
                val success = mBrlttyWrapper.start()
                if (success) {
                    initListener.onInit(mBrlttyWrapper.displayProperties)
                } else {
                    initListener.onInit(null)
                    stopInternal()
                }
            }
        }
        mHandlerThread.start()
        mHandler = DriverHandler(mHandlerThread.looper)
    }

    /**
     * Update the refreshable display with the given dot pattern. This method can
     * be called from any thread.
     */
    fun writeWindow(pattern: ByteArray) {
        synchronized(this) {
            writeBuffer = pattern
        }
        mHandler.sendEmptyMessage(MSG_WRITE)
    }

    /**
     * Wakes up the driver to poll for input. Used by the HID connection path
     * where input bytes are delivered through [android.accessibilityservice.BrailleDisplayController]
     * callbacks (via [BrlttyHidNativeHelper]) rather than through [addReadOperation].
     */
    fun wakeReadOperation() {
        mHandler.sendEmptyMessage(MSG_READ)
    }

    /**
     * Add bytes that have been read from the device and wake up the driver to
     * read more input. This can be called from any thread. The first [size]
     * `bytes` are added.
     */
    @Throws(IOException::class)
    fun addReadOperation(bytes: ByteArray, size: Int) {
        mBrlttyWrapper.addBytesFromDevice(bytes, size)
        mHandler.sendEmptyMessage(MSG_READ)
    }

    /**
     * Stop communication queue thread and cleanup. Called from any thread.
     */
    fun stop() {
        mHandler.sendEmptyMessage(MSG_STOP)
        Log.d(LOG_TAG, "Waiting for handler thread")
        try {
            mHandlerThread.join(STOP_WAIT_MILLIS)
            if (mHandlerThread.isAlive) {
                Log.e(
                    LOG_TAG,
                    "*** Driver thread takes very long to terminate -- giving up waiting ***"
                )
            }
        } catch (ex: InterruptedException) {
            Log.e(LOG_TAG, "Joining with queueThread interrupted.")
        }
    }

    private fun writeWindowInternal() {
        val buffer: ByteArray?
        synchronized(this) {
            buffer = writeBuffer
            writeBuffer = null
        }
        if (buffer != null) {
            mBrlttyWrapper.writeWindow(buffer)
        }
    }

    private fun stopInternal() {
        mBrlttyWrapper.stop()
        mHandler.looper.quit()
    }

    /**
     * Called from the driver to send raw bytes to the device.
     */
    fun sendBytesToDevice(bytes: ByteArray): Boolean {
        return try {
            mOutputStream.write(bytes)
            true
        } catch (ex: IOException) {
            Log.e(LOG_TAG, "Writing to braille device failed", ex)
            false
        }
    }

    /**
     * Called from the command logic in the driver to have the driver thread
     * woken up to read another command after [delayMillis] milliseconds.
     */
    fun readDelayed(delayMillis: Long) {
        mHandler.sendEmptyMessageDelayed(MSG_READ, delayMillis)
    }

    private inner class DriverHandler(looper: Looper) : Handler(looper) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_READ -> {
                    // Note that there's no one to one mapping between these
                    // messages and actual commands because data comes in
                    // arbitrary chunks from the hardware.
                    while (true) {
                        val command = mBrlttyWrapper.readCommand()
                        if (command < 0) {
                            break
                        }
                        inputEventListener.onInputEvent(
                            BrailleInputEvent(
                                command and COMMAND_CODE_MASK,
                                (command and COMMAND_ARGUMENT_MASK) shr COMMAND_ARGUMENT_SHIFT,
                                SystemClock.uptimeMillis()
                            )
                        )
                    }
                }
                MSG_WRITE -> writeWindowInternal()
                MSG_STOP -> stopInternal()
                else -> {
                    Log.i(LOG_TAG, "Incorrect type of operation.")
                    return
                }
            }
        }
    }
}
