package com.googlecode.eyesfree.widget

import android.content.Context
import android.view.MotionEvent
import android.view.View

/**
 * A transparent overlay which catches all touch events and uses a call back to
 * return the gesture that the user performed.
 */
class TouchGestureControlOverlay @JvmOverloads constructor(
    context: Context,
    private val callback: GestureListener? = null
) : View(context) {

    enum class Gesture {
        UPLEFT, UP, UPRIGHT, LEFT, CENTER, RIGHT, DOWNLEFT, DOWN, DOWNRIGHT
    }

    interface GestureListener {
        fun onGestureStart(g: Gesture)
        fun onGestureChange(g: Gesture)
        fun onGestureFinish(g: Gesture)
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
    private var currentGesture: Gesture? = null

    init {
        cb = callback
    }

    fun setGestureListener(callback: GestureListener?) {
        cb = callback
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.action
        val x = event.x.toDouble()
        val y = event.y.toDouble()
        var prevGesture: Gesture? = null
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                downX = x
                downY = y
                currentGesture = Gesture.CENTER
                cb?.onGestureStart(currentGesture!!)
            }
            MotionEvent.ACTION_UP -> {
                prevGesture = currentGesture
                currentGesture = evalMotion(x, y)
                if (currentGesture == null) {
                    currentGesture = prevGesture
                }
                cb?.onGestureFinish(currentGesture!!)
            }
            else -> {
                prevGesture = currentGesture
                currentGesture = evalMotion(x, y)
                if (currentGesture == null) {
                    currentGesture = prevGesture
                } else if (prevGesture != currentGesture) {
                    cb?.onGestureChange(currentGesture!!)
                }
            }
        }
        return true
    }

    fun evalMotion(x: Double, y: Double): Gesture? {
        val rTolerance = 25f
        val thetaTolerance = (Math.PI / 12)

        val r = Math.sqrt((downX - x) * (downX - x) + (downY - y) * (downY - y))

        if (r < rTolerance) {
            return Gesture.CENTER
        }

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
        return null
    }
}
