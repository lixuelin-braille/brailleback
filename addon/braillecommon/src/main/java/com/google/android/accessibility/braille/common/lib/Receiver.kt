package com.google.android.accessibility.braille.common.lib

/** Interface that listens for changes. */
interface Receiver<R> {

  /** Registers this receiver. */
  fun registerSelf(): R

  /** Unregisters this receiver. */
  fun unregisterSelf()
}
