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

package com.linxi.brailleback

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Simple [ComponentActivity] showing a [WebView], using arguments
 * [Intent.EXTRA_TITLE] for title and [Intent.EXTRA_STREAM] for the content to
 * be displayed.
 *
 * 说明：本页是 Compose 容器宿主 + [AndroidView] 包裹的 WebView，不是纯 Compose
 * 页面；许可文档仍是系统 WebView 渲染。
 */
class WebViewDialog : ComponentActivity() {
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

        val url = intent.getStringExtra(Intent.EXTRA_STREAM)
        if (url.isNullOrEmpty()) {
            // 以前这里用非空断言，缺 URL 时直接崩在 onCreate；缺 URL 时关掉自己，比停在空白页强。
            finish()
            return
        }

        setContent {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context -> WebView(context) },
                update = { webView -> webView.loadUrl(url) },
            )
        }
    }
}
