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

package com.googlecode.eyesfree.brailleback

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.WebView
import androidx.fragment.app.FragmentActivity

/**
 * Simple [FragmentActivity] showing a [WebView], using arguments
 * [Intent.EXTRA_TITLE] for title and [Intent.EXTRA_STREAM] for the content to
 * be displayed.
 */
class WebViewDialog : FragmentActivity() {
    companion object {
        @JvmStatic
        fun getIntent(context: Context, titleResId: Int, url: String): Intent {
            return Intent(context, WebViewDialog::class.java)
                .putExtra(Intent.EXTRA_TITLE, titleResId)
                .putExtra(Intent.EXTRA_STREAM, url)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setTitle(intent.getIntExtra(Intent.EXTRA_TITLE, R.string.pref_os_license_title))
        setContentView(R.layout.activity_licenses)

        val webView = findViewById<WebView>(R.id.content)
        webView.loadUrl(intent.getStringExtra(Intent.EXTRA_STREAM)!!)
    }
}
