/*
 * Copyright (C) 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.googlecode.eyesfree.utils

import android.os.Handler
import android.os.Looper
import android.os.Message
import java.lang.ref.WeakReference

/**
 * Convenience class for making a static inner [Handler] class that keeps a
 * [WeakReference] to its parent class. If the reference is cleared, the
 * [WeakReferenceHandler] will stop handling [Message]s.
 *
 * Example usage:
 * ```
 * private val mHandler = MyHandler(this)
 *
 * private class MyHandler : WeakReferenceHandler<MyClass> {
 *     override fun handleMessage(msg: Message, parent: MyClass) {
 *         parent.onMessageReceived(msg.what, msg.arg1)
 *     }
 * }
 * ```
 *
 * @param T The handler's parent class.
 */
abstract class WeakReferenceHandler<T> : Handler {
    private val parentRef: WeakReference<T>

    /**
     * Constructs a new [WeakReferenceHandler] with a reference to its parent
     * class.
     *
     * @param parent The handler's parent class.
     */
    constructor(parent: T) : super() {
        parentRef = WeakReference(parent)
    }

    /**
     * Constructs a new [WeakReferenceHandler] with a reference to its parent
     * class.
     *
     * @param parent The handler's parent class.
     * @param looper The looper.
     */
    constructor(parent: T, looper: Looper) : super(looper) {
        parentRef = WeakReference(parent)
    }

    final override fun handleMessage(msg: Message) {
        val parent = getParent() ?: return
        handleMessage(msg, parent)
    }

    /**
     * @return The parent class, or `null` if the reference has been cleared.
     */
    protected fun getParent(): T? = parentRef.get()

    /** Subclasses must implement this to receive messages. */
    protected abstract fun handleMessage(msg: Message, parent: T)
}
