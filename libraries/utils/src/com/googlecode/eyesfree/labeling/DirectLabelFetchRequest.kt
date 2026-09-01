/*
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

package com.googlecode.eyesfree.labeling

class DirectLabelFetchRequest(
    val labelId: Long,
    private val listener: OnLabelFetchedListener?
) {

    internal fun invokeCallback(result: Label?) {
        listener?.onLabelFetched(result)
    }

    interface OnLabelFetchedListener {
        fun onLabelFetched(result: Label?)
    }
}
