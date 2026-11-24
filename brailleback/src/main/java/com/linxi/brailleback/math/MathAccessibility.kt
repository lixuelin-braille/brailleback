/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 *
 * 数学页无障碍纯函数辅助：选中态描述映射。Compose 语义层在 MathBrailleActivity 接线，
 * 本对象不依赖 Android / Compose，便于 JVM 单测锁住映射规则。
 */

package com.linxi.brailleback.math

/** 数学页 6/8 点单选行的无障碍辅助。 */
internal object MathAccessibility {

    /**
     * 单选行 stateDescription：选中播报 [selectedLabel]，未选播报 [unselectedLabel]。
     * 与触觉页单选行口径一致（`已选中` / `未选中`）。
     */
    fun dotStateDescription(selected: Boolean, selectedLabel: String, unselectedLabel: String): String =
        if (selected) selectedLabel else unselectedLabel
}
