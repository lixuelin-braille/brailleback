package com.google.android.accessibility.brailleime

import android.content.Context
import com.google.android.accessibility.utils.FeatureSupport

/** Reader class for flags of experimental feature. */
class FeatureFlagReader private constructor() {

  companion object {
    /** Whether to enable select current from current cursor to start or end. */
    @JvmStatic
    fun useSelectCurrentToStartOrEnd(context: Context): Boolean {
      return FeatureSupport.supportInputConnectionByA11yService()
    }

    /** Whether to enable braille ime on device without five touch points. */
    @JvmStatic
    fun enableBrailleImeOnDeviceWithoutFivePointers(context: Context): Boolean {
      return false
    }

    /** Whether to use gesture customization. */
    @JvmStatic
    fun useGestureCustomization(context: Context): Boolean {
      return true
    }
  }
}
