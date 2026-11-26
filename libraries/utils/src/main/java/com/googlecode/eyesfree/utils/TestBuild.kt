/*
 * Copyright (C) 2026 The BrailleBack Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy
 * of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package com.googlecode.eyesfree.utils

/**
 * 集成分支 `test/build-all-20261126` 测试包的构建标记。
 *
 * 仅本测试包默认把文件日志写到 VERBOSE；release 正式包（`BuildConfig.DEBUG=false`）
 * 回落原有默认级别（INFO）。VERBOSE 会放大事件流、拖慢滚动帧（此前实测 jank 有
 * 差异），所以这是测试期临时口径，不随正式包发布。
 */
object TestBuild {

    /** 本测试包是否默认开启 VERBOSE 文件日志（配合 debug 构建类型使用）。 */
    const val VERBOSE_BY_DEFAULT = true

    /** 写入主日志文件首行的构建标记，便于真机取证时辨认包来源。 */
    const val MARKER = "TEST-BUILD 2026-11-26 test/build-all-20261126"
}
