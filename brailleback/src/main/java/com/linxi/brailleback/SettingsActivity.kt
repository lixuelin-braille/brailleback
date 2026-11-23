/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 兼容别名：主屏已迁移到 [HomeContainerActivity]（底部导航：书籍阅读管理 / 设置）。
 *
 * 本类必须保留的原因：
 * - BrailleDisplayComposeActivity 仍监听 [INTENT_REFRESH_DISPLAY] 广播常量；
 * - KeyBindingsActivity 返回时硬编码跳回本类；
 * - 旧链接与仪器测试按本类名启动。
 *
 * 进入后直接转到容器的设置 Tab 并 finish，不再自己渲染设置主屏。
 */

package com.linxi.brailleback

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    /** Message from BrailleBackService, indicating that grade pref changed. */
    companion object {
        const val INTENT_REFRESH_DISPLAY =
            "com.linxi.brailleback.REFRESH_DISPLAY"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, HomeContainerActivity::class.java)
                .putExtra(HomeContainerActivity.EXTRA_TAB, HomeContainerActivity.TAB_SETTINGS)
        )
        finish()
    }
}
