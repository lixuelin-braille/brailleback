/*
 * Copyright (C) 2013 Google Inc.
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

package com.googlecode.eyesfree.brailleback

import android.content.Context
import android.content.DialogInterface
import android.os.Build
import android.view.WindowManager

import com.googlecode.eyesfree.widget.SimpleOverlay

/**
 * Controls the view that shows search overlay content.
 */
open class SearchOverlay(context: Context, queryText: StringBuilder) :
        SimpleOverlay(context), DialogInterface {

    /** The search view. */
    private val mSearchView: SearchView = SearchView(context, queryText)

    init {
        // Make overlay appear on everything it can.
        val params = getParams()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) {
            params.type = WindowManager.LayoutParams.TYPE_SYSTEM_ERROR
        } else {
            params.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        }
        setParams(params)

        setContentView(mSearchView)
    }

    /**
     * Called when the the overlay is shown.
     */
    override fun onShow() {
        mSearchView.show()
    }

    /**
     * Refreshes the overlaid text display.
     */
    fun refreshOverlay() {
        mSearchView.invalidate()
    }

    override fun cancel() {
        dismiss()
    }

    override fun dismiss() {
        // This also effectively hides the search view.
        hide()
    }
}
