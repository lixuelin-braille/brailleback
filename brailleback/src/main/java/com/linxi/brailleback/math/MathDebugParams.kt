/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * DEBUG-only 数学页参数注入解析；纯函数、无 Android 依赖，可 JVM 单测。
 */

package com.linxi.brailleback.math

/** 数学页 debug extra 解析。 */
object MathDebugParams {

    /**
     * 解析 6 / 8 点档位；缺失、空串或非法值返回 null（Activity 回落既有默认 6 点）。
     * 兼容字符串与整型两种注入方式（Activity 侧先归一为文本）。
     */
    fun parseDots(raw: String?): Int? {
        val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return text.toIntOrNull()?.takeIf { it == 6 || it == 8 }
    }
}
