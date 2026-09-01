package com.googlecode.eyesfree.braille.service.display

import android.accessibilityservice.BrailleDisplayController
import android.os.Build

/**
 * Bridges the [BrailleDisplayController] obtained by the accessibility service
 * (BrailleBackService) to the display service, which drives the native BRLTTY
 * "hid" driver.
 *
 * [BrailleDisplayController] is only obtainable from an [android.accessibilityservice.AccessibilityService].
 * Both BrailleBackService and DisplayService run in the same process, so we
 * simply hand the controller over through this static holder.
 */
object HidBridge {
    private var sController: BrailleDisplayController? = null
    private var sControllerChangeListener: Runnable? = null

    @JvmStatic
    fun setController(controller: BrailleDisplayController?) {
        sController = controller
        val listener = sControllerChangeListener
        if (listener != null && controller != null) {
            listener.run()
        }
    }

    @JvmStatic
    fun setControllerChangeListener(listener: Runnable?) {
        sControllerChangeListener = listener
    }

    @JvmStatic
    fun getController(): BrailleDisplayController? = sController

    @JvmStatic
    fun isAvailable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && sController != null
}
