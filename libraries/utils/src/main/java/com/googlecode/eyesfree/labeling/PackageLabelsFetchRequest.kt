/*
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

package com.googlecode.eyesfree.labeling

class PackageLabelsFetchRequest(
    val packageName: String,
    private val listener: OnLabelsFetchedListener?
) {

    internal fun invokeCallback(result: Map<String, Label>?) {
        listener?.onLabelsFetched(result)
    }

    fun interface OnLabelsFetchedListener {
        /**
         * Invoked by the task performing the fetch request with the results of
         * the lookup.
         */
        fun onLabelsFetched(results: Map<String, Label>?)
    }
}
