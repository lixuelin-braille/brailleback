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
import com.googlecode.eyesfree.utils.LogUtils
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.Arrays
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
    /**
     * Upper bound on commands drained by one wake-up. A driver that keeps
     * reporting commands must not monopolise the handler thread.
     */
    private val MAX_COMMANDS_PER_WAKE = 64
    /** Only report a slow read/write operation above this duration. */
    private val SLOW_OPERATION_MILLIS = 200L
    /** Minimum spacing between slow-operation reports. */
    private val SLOW_OPERATION_LOG_INTERVAL_MILLIS = 2000L
    private val COMMAND_CODE_MASK = 0xffff
    private val COMMAND_ARGUMENT_MASK = 0x7fff0000
    private val COMMAND_ARGUMENT_SHIFT = 16
    /** Low-frequency refresh telemetry: one compact line every 5 seconds. */
    private val TELEMETRY_INTERVAL_MILLIS = 5000L
    private val STALL_WARN_MILLIS = 2000L

    private val mHandler: Handler
    private val mHandlerThread: HandlerThread

    private val statRequested = AtomicInteger()
    @Volatile private var statReportedRequested = 0
    @Volatile private var statExecuted = 0
    @Volatile private var statEmptyDrops = 0
    @Volatile private var statNativeOk = 0
    @Volatile private var statNativeFail = 0
    @Volatile private var statLastElapsedMs = 0L
    @Volatile private var statLastCells = 0
    @Volatile private var statLastDigest = 0
    @Volatile private var statReads = 0
    @Volatile private var statLastReadMs = 0L
    @Volatile private var lastExecutedAt = SystemClock.uptimeMillis()
    /**
     * 独立守护线程上报：handler 线程被长阻塞时仍会输出，req 涨而 exec 不涨
     * 即说明写消息根本没被执行。
     */
    private val telemetryRunning = AtomicBoolean(true)
    private val telemetryThread = Thread {
        while (telemetryRunning.get()) {
            try {
                Thread.sleep(TELEMETRY_INTERVAL_MILLIS)
            } catch (e: InterruptedException) {
                break
            }
            val requested = statRequested.get()
            LogUtils.i(this@DriverThread,
                "driver stats: req=%d exec=%d empty=%d ok=%d fail=%d " +
                    "lastWrite=%dms cells=%d digest=%08X polls=%d lastPoll=%dms",
                requested, statExecuted, statEmptyDrops, statNativeOk,
                statNativeFail, statLastElapsedMs, statLastCells,
                statLastDigest, statReads, statLastReadMs)
            val idle = SystemClock.uptimeMillis() - lastExecutedAt
            if (requested > statReportedRequested && idle > STALL_WARN_MILLIS) {
                LogUtils.w(this@DriverThread,
                    "window refresh stalled: %d request(s) waiting, " +
                        "last write %dms ago",
                    requested - statReportedRequested, idle)
            }
            statReportedRequested = requested
        }
    }.apply {
        isDaemon = true
        name = "DriverStats"
        start()
    }

    @Volatile
    private var writeBuffer: ByteArray? = null

    private var lastSlowReadLog = 0L
    private var lastSlowWriteLog = 0L

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
                // Anything thrown here happens on a plain HandlerThread and
                // would take the whole process down with it. Fail the
                // connection instead so the service can report an error and
                // retry.
                val properties = try {
                    if (mBrlttyWrapper.start()) {
                        mBrlttyWrapper.displayProperties
                    } else {
                        null
                    }
                } catch (ex: Throwable) {
                    Log.e(LOG_TAG, "Driver initialisation failed", ex)
                    null
                }
                initListener.onInit(properties)
                if (properties == null) {
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
        statRequested.incrementAndGet()
        synchronized(this) {
            writeBuffer = pattern
        }
        // Only the newest window matters. Coalescing keeps a slow driver from
        // building an unbounded refresh backlog.
        mHandler.removeMessages(MSG_WRITE)
        mHandler.sendEmptyMessage(MSG_WRITE)
    }

    /**
     * Wakes up the driver to poll for input. Used by the HID connection path
     * where input bytes are delivered through [android.accessibilityservice.BrailleDisplayController]
     * callbacks (via [BrlttyHidNativeHelper]) rather than through [addReadOperation].
     */
    fun wakeReadOperation() {
        // Input polling is periodic. Keeping at most one wake-up queued means
        // a read that takes longer than the poll interval cannot starve
        // MSG_WRITE messages forever.
        mHandler.removeMessages(MSG_READ)
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
        mHandler.removeMessages(MSG_READ)
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

    private fun writeWindowInternal(): Boolean {
        val buffer: ByteArray?
        synchronized(this) {
            buffer = writeBuffer
            writeBuffer = null
        }
        if (buffer == null) {
            statEmptyDrops += 1
            return false
        }
        val startedAt = SystemClock.uptimeMillis()
        val ok = mBrlttyWrapper.writeWindow(buffer)
        val elapsed = SystemClock.uptimeMillis() - startedAt
        statExecuted += 1
        if (ok) {
            statNativeOk += 1
        } else {
            statNativeFail += 1
        }
        statLastElapsedMs = elapsed
        statLastCells = buffer.size
        statLastDigest = Arrays.hashCode(buffer)
        lastExecutedAt = startedAt
        return true
    }

    private fun stopInternal() {
        telemetryRunning.set(false)
        telemetryThread.interrupt()
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
                    // A pending refresh is more important than polling for
                    // input, so let it run before a possibly slow read.
                    if (mHandler.hasMessages(MSG_WRITE)) {
                        writeWindowInternal()
                    }
                    // Note that there's no one to one mapping between these
                    // messages and actual commands because data comes in
                    // arbitrary chunks from the hardware.
                    val started = SystemClock.uptimeMillis()
                    var count = 0
                    while (count < MAX_COMMANDS_PER_WAKE) {
                        val command = mBrlttyWrapper.readCommand()
                        if (command < 0) {
                            break
                        }
                        count += 1
                        inputEventListener.onInputEvent(
                            BrailleInputEvent(
                                command and COMMAND_CODE_MASK,
                                (command and COMMAND_ARGUMENT_MASK) shr COMMAND_ARGUMENT_SHIFT,
                                SystemClock.uptimeMillis()
                            )
                        )
                    }
                    if (count == MAX_COMMANDS_PER_WAKE) {
                        // Still more input to consume; queue another pass.
                        mHandler.sendEmptyMessage(MSG_READ)
                    }
                    statReads += 1
                    statLastReadMs = SystemClock.uptimeMillis() - started
                    logSlowRead(started, count)
                }
                MSG_WRITE -> {
                    val started = SystemClock.uptimeMillis()
                    if (writeWindowInternal()) {
                        logSlowWrite(started)
                    }
                }
                MSG_STOP -> stopInternal()
                else -> {
                    Log.i(LOG_TAG, "Incorrect type of operation.")
                    return
                }
            }
        }
    }

    private fun logSlowRead(started: Long, count: Int) {
        val elapsed = SystemClock.uptimeMillis() - started
        if (elapsed < SLOW_OPERATION_MILLIS) {
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastSlowReadLog < SLOW_OPERATION_LOG_INTERVAL_MILLIS) {
            return
        }
        lastSlowReadLog = now
        Log.w(LOG_TAG, "Input poll took ${elapsed}ms for $count command(s)")
    }

    private fun logSlowWrite(started: Long) {
        val elapsed = SystemClock.uptimeMillis() - started
        if (elapsed < SLOW_OPERATION_MILLIS) {
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastSlowWriteLog < SLOW_OPERATION_LOG_INTERVAL_MILLIS) {
            return
        }
        lastSlowWriteLog = now
        Log.w(LOG_TAG, "Window refresh took ${elapsed}ms")
    }
}
