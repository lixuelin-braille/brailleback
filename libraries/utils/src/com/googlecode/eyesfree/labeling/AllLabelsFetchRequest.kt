/*
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

package com.googlecode.eyesfree.labeling

class AllLabelsFetchRequest(
    private val listener: OnAllLabelsFetchedListener?
) {

    internal fun invokeCallback(result: List<Label>?) {
        listener?.onAllLabelsFetched(result)
    }

    interface OnAllLabelsFetchedListener {
        fun onAllLabelsFetched(results: List<Label>?)
    }
}
