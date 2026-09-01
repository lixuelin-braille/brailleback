package com.googlecode.eyesfree.widget

import android.content.Context
import android.os.Handler
import android.os.Message
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityManager
import com.googlecode.eyesfree.compat.view.MotionEventCompatUtils
import java.lang.reflect.Method

/**
 * A transparent overlay which catches all touch events and uses a call back to
 * return the gesture that the user performed. Includes edge events.
 */
class GestureOverlay(context: Context, callback: GestureListener? = null) : View(context) {
    class Gesture {
        companion object {
            const val UPLEFT = 1
            const val UP = 2
            const val UPRIGHT = 3
            const val LEFT = 4
            const val CENTER = 5
            const val RIGHT = 6
            const val DOWNLEFT = 7
            const val DOWN = 8
            const val DOWNRIGHT = 9
            const val EDGELEFT = 10
            const val EDGERIGHT = 11
            const val DOUBLE_TAP = 12
        }
    }

    interface GestureListener {
        fun onGestureStart(g: Int)
        fun onGestureChange(g: Int)
        fun onGestureFinish(g: Int)
    }

    private val left = 0.0
    private val upleft = Math.PI * .25
    private val up = Math.PI * .5
    private val upright = Math.PI * .75
    private val downright = -Math.PI * .75
    private val down = -Math.PI * .5
    private val downleft = -Math.PI * .25
    private val right = Math.PI
    private val rightWrap = -Math.PI

    private var cb: GestureListener? = null
    private var downX = 0.0
    private var downY = 0.0
    private var currentGesture = 0
    private var radiusThreshold = 30
    private var edgeGesture = false

    var leftEdge = 0
    var rightEdge = 0

    private var tapHandler: DoubleTapHandler? = null
    private var doubleTapWindow = 700L
    private var isTap = false
    private var lastTapTime = 0L
    private var touchExploring = false

    private lateinit var accessibilityManager: AccessibilityManager

    init {
        setGestureListener(callback)
        initAccessibilitySettings(context)
    }

    private fun initAccessibilitySettings(context: Context) {
        accessibilityManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        try {
            if (accessibilityManagerIsTouchExplorationEnabled != null) {
                val retobj = accessibilityManagerIsTouchExplorationEnabled!!.invoke(accessibilityManager)
                touchExploring = retobj as Boolean
            }
        } catch (e: Exception) {
            Log.e("GestureOverlay", "Failed to get Accessibility Manager " + e.toString())
        }
    }

    fun setGestureListener(callback: GestureListener?) {
        cb = callback
        tapHandler = DoubleTapHandler()
    }

    fun setMinimumRadius(minRadius: Int) {
        radiusThreshold = minRadius
    }

    override fun onHoverEvent(event: MotionEvent): Boolean = onTouchEvent(event)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.action
        val x = event.x.toDouble()
        val y = event.y.toDouble()
        var prevGesture = -1
        when (action) {
            MotionEventCompatUtils.ACTION_HOVER_ENTER, MotionEvent.ACTION_DOWN -> {
                downX = x
                downY = y
                isTap = true
                leftEdge = width / 7
                rightEdge = width - width / 7
                currentGesture = if (x < leftEdge) {
                    edgeGesture = true
                    Gesture.EDGELEFT
                } else if (x > rightEdge) {
                    edgeGesture = true
                    Gesture.EDGERIGHT
                } else {
                    edgeGesture = false
                    Gesture.CENTER
                }
                cb?.onGestureStart(currentGesture)
            }
            MotionEventCompatUtils.ACTION_HOVER_EXIT, MotionEvent.ACTION_UP -> {
                prevGesture = currentGesture
                currentGesture = evalMotion(x, y)
                if (currentGesture == -1) {
                    currentGesture = prevGesture
                }
                if (cb != null) {
                    if (touchExploring && action == MotionEvent.ACTION_UP) {
                        currentGesture = Gesture.DOUBLE_TAP
                        cb!!.onGestureFinish(currentGesture)
                        return true
                    } else if (!touchExploring) {
                        if (isTap) {
                            if (lastTapTime + doubleTapWindow > System.currentTimeMillis()) {
                                currentGesture = Gesture.DOUBLE_TAP
                                tapHandler!!.cancelDoubleTapWindow()
                            } else {
                                lastTapTime = System.currentTimeMillis()
                                tapHandler!!.startDoubleTapWindow()
                                return true
                            }
                        }
                    }
                    cb!!.onGestureFinish(currentGesture)
                }
            }
            else -> {
                prevGesture = currentGesture
                currentGesture = evalMotion(x, y)
                if (currentGesture == -1) {
                    currentGesture = prevGesture
                } else if (prevGesture != currentGesture) {
                    cb?.onGestureChange(currentGesture)
                }
            }
        }
        return true
    }

    fun evalMotion(x: Double, y: Double): Int {
        val rTolerance = radiusThreshold.toFloat()
        val thetaTolerance = (Math.PI / 12)

        if (edgeGesture) {
            if (x < leftEdge && downX < leftEdge) {
                return Gesture.EDGELEFT
            } else if (x > rightEdge && downX > rightEdge) {
                return Gesture.EDGERIGHT
            } else {
                edgeGesture = false
            }
        }

        val r = Math.sqrt((downX - x) * (downX - x) + (downY - y) * (downY - y))

        if (r < rTolerance) {
            return Gesture.CENTER
        }

        isTap = false

        val theta = Math.atan2(downY - y, downX - x)
        if (Math.abs(theta - left) < thetaTolerance) {
            return Gesture.LEFT
        } else if (Math.abs(theta - upleft) < thetaTolerance) {
            return Gesture.UPLEFT
        } else if (Math.abs(theta - up) < thetaTolerance) {
            return Gesture.UP
        } else if (Math.abs(theta - upright) < thetaTolerance) {
            return Gesture.UPRIGHT
        } else if (Math.abs(theta - downright) < thetaTolerance) {
            return Gesture.DOWNRIGHT
        } else if (Math.abs(theta - down) < thetaTolerance) {
            return Gesture.DOWN
        } else if (Math.abs(theta - downleft) < thetaTolerance) {
            return Gesture.DOWNLEFT
        } else if (theta > right - thetaTolerance || theta < rightWrap + thetaTolerance) {
            return Gesture.RIGHT
        }
        return -1
    }

    private inner class DoubleTapHandler : Handler() {
        private val MSG_DOUBLE_TAP_TIMEOUT = 0

        override fun handleMessage(msg: Message) {
            if (msg.what == MSG_DOUBLE_TAP_TIMEOUT) {
                cb?.onGestureFinish(currentGesture)
            }
        }

        fun startDoubleTapWindow() {
            sendEmptyMessageDelayed(MSG_DOUBLE_TAP_TIMEOUT, doubleTapWindow)
        }

        fun cancelDoubleTapWindow() {
            removeMessages(MSG_DOUBLE_TAP_TIMEOUT)
        }
    }

    companion object {
        private var accessibilityManagerIsTouchExplorationEnabled: Method? = null

        init {
            try {
                accessibilityManagerIsTouchExplorationEnabled =
                    AccessibilityManager::class.java.getMethod("isTouchExplorationEnabled")
            } catch (nsme: NoSuchMethodException) {
                // Older device without touch exploration support.
            }
        }
    }
}
