/*
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

package com.googlecode.eyesfree.labeling

class LabelAddRequest(
    val label: Label,
    private val listener: OnLabelAddedListener?
) {

    internal fun invokeCallback(result: Label?) {
        listener?.onLabelAdded(result)
    }

    interface OnLabelAddedListener {
        fun onLabelAdded(result: Label?)
    }
}
