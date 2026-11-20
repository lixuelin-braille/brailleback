/*
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

package com.googlecode.eyesfree.labeling

class LabelRemoveRequest(
    val label: Label,
    private val listener: OnLabelRemovedListener?
) {

    internal fun invokeCallback(result: Boolean) {
        listener?.onLabelRemoved(result)
    }

    interface OnLabelRemovedListener {
        fun onLabelRemoved(result: Boolean)
    }
}
