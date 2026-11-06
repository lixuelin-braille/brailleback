package com.googlecode.eyesfree.braille.service.display

import android.accessibilityservice.BrailleDisplayController
import android.os.Build

/**
 * Bridges the [BrailleDisplayController] obtained by the accessibility service
 * (BrailleBackService) to the display service, which drives the native BRLTTY
 * "hid" driver.
 *
 * [BrailleDisplayController] is only obtainable from an
 * [android.accessibilityservice.AccessibilityService]. Both BrailleBackService
 * and DisplayService run in the same process, so we simply hand the controller
 * over through this static holder.
 *
 * Ownership matters: the accessibility service can be rebound before the
 * previous instance receives onDestroy(). A late unconditional
 * setController(null) from that stale instance would erase the controller
 * published by its successor and leave HID permanently "not ready". Every
 * publish/release therefore carries the owning service instance, and a release
 * is ignored unless that owner is still the current one.
 */
object HidBridge {
    @Volatile
    private var sController: BrailleDisplayController? = null

    /** The AccessibilityService instance that published [sController]. */
    @Volatile
    private var sOwner: Any? = null

    @Volatile
    private var sControllerChangeListener: Runnable? = null

    /** The DisplayService instance that registered [sControllerChangeListener]. */
    @Volatile
    private var sListenerOwner: Any? = null

    /**
     * Publishes [controller] on behalf of [owner], or releases the bridge when
     * [controller] is null.
     *
     * A null controller only clears the bridge if [owner] still owns it, so a
     * destroyed service instance cannot wipe a newer controller. The listener
     * runs on transitions only (a controller being published, replaced or
     * released), not on every idempotent call.
     */
    @JvmStatic
    fun setController(owner: Any?, controller: BrailleDisplayController?) {
        val changed: Boolean
        synchronized(this) {
            if (controller == null) {
                // Ignore a release from an instance that no longer owns the
                // bridge. Legacy callers (owner == null) may only clear when
                // nobody claimed ownership either.
                if (sOwner !== owner) {
                    return
                }
                changed = sController != null
                sController = null
                sOwner = null
            } else {
                changed = sController !== controller
                sController = controller
                sOwner = owner
            }
        }
        if (changed) {
            notifyControllerChanged()
        }
    }

    /** Legacy entry point without ownership tracking. */
    @JvmStatic
    fun setController(controller: BrailleDisplayController?) {
        setController(null, controller)
    }

    /**
     * Registers [listener] on behalf of [owner]. A null listener only clears
     * the registration when [owner] still owns it, so a destroyed
     * DisplayService cannot erase the listener installed by its successor.
     */
    @JvmStatic
    fun setControllerChangeListener(owner: Any?, listener: Runnable?) {
        synchronized(this) {
            if (listener == null) {
                if (sListenerOwner !== owner) {
                    return
                }
                sListenerOwner = null
                sControllerChangeListener = null
            } else {
                sListenerOwner = owner
                sControllerChangeListener = listener
            }
        }
    }

    /** Legacy entry point without ownership tracking. */
    @JvmStatic
    fun setControllerChangeListener(listener: Runnable?) {
        setControllerChangeListener(null, listener)
    }

    @JvmStatic
    fun getController(): BrailleDisplayController? = sController

    @JvmStatic
    fun isAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && sController != null

    private fun notifyControllerChanged() {
        val listener = sControllerChangeListener ?: return
        try {
            listener.run()
        } catch (e: RuntimeException) {
            // A broken listener must not take the bridge down with it.
            android.util.Log.w("HidBridge", "Controller change listener failed", e)
        }
    }
}
