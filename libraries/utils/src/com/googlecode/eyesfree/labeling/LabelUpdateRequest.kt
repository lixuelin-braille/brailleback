/*
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

package com.googlecode.eyesfree.labeling

class LabelUpdateRequest(
    val label: Label,
    private val listener: OnLabelUpdatedListener?
) {

    internal fun invokeCallback(result: Boolean) {
        listener?.onLabelUpdated(result)
    }

    interface OnLabelUpdatedListener {
        fun onLabelUpdated(result: Boolean)
    }
}
