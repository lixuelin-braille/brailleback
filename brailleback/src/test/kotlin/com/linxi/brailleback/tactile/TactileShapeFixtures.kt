/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 * 7 类几何形状测试样本（纯 JVM，不引 Android / OCR / Python）。探针与门控断言共用同一口径，
 * 避免两份构造代码漂移。
 */
package com.linxi.brailleback.tactile

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

internal object TactileShapeFixtures {

    const val CANVAS = 100

    private fun line(pts: MutableSet<Pair<Int, Int>>, x0: Int, y0: Int, x1: Int, y1: Int) {
        val steps = maxOf(abs(x1 - x0), abs(y1 - y0), 1)
        for (i in 0..steps) {
            val x = Math.round(x0 + (x1 - x0) * i.toDouble() / steps).toInt()
            val y = Math.round(y0 + (y1 - y0) * i.toDouble() / steps).toInt()
            pts.add(x.coerceIn(0, CANVAS - 1) to y.coerceIn(0, CANVAS - 1))
        }
    }

    private fun thick(pts: MutableSet<Pair<Int, Int>>, x0: Int, y0: Int, x1: Int, y1: Int) {
        val steps = maxOf(abs(x1 - x0), abs(y1 - y0), 1)
        for (i in 0..steps) {
            val x = Math.round(x0 + (x1 - x0) * i.toDouble() / steps).toInt()
            val y = Math.round(y0 + (y1 - y0) * i.toDouble() / steps).toInt()
            for (dx in -1..1) for (dy in -1..1) {
                pts.add((x + dx).coerceIn(0, CANVAS - 1) to (y + dy).coerceIn(0, CANVAS - 1))
            }
        }
    }

    private fun ring(): Set<Pair<Int, Int>> {
        val pts = HashSet<Pair<Int, Int>>()
        for (y in 0 until CANVAS) for (x in 0 until CANVAS) {
            val d = hypot((x - 50).toDouble(), (y - 50).toDouble())
            if (abs(d - 34.0) <= 2.0) pts.add(x to y)
        }
        return pts
    }

    private fun square(): Set<Pair<Int, Int>> {
        val pts = HashSet<Pair<Int, Int>>()
        for (y in 0 until CANVAS) for (x in 0 until CANVAS) {
            if (y in 18..20 || y in 80..82 || x in 18..20 || x in 80..82) pts.add(x to y)
        }
        return pts
    }

    private fun arrow(): Set<Pair<Int, Int>> {
        val pts = HashSet<Pair<Int, Int>>()
        for (y in 45..55) for (x in 20..62) pts.add(x to y)
        line(pts, 62, 30, 82, 50)
        line(pts, 82, 50, 62, 70)
        return pts
    }

    private fun heart(): Set<Pair<Int, Int>> {
        val pts = HashSet<Pair<Int, Int>>()
        val steps = 400
        for (i in 0..steps) {
            val t = Math.PI * 2 * i / steps
            val hx = 16.0 * Math.pow(sin(t), 3.0)
            val hy = 13.0 * cos(t) - 5.0 * cos(2 * t) - 2.0 * cos(3 * t) - cos(4 * t)
            val x = (50 + hx * 2.4).toInt().coerceIn(0, CANVAS - 1)
            val y = (50 - hy * 2.4).toInt().coerceIn(0, CANVAS - 1)
            pts.add(x to y)
            for (dx in -1..1) for (dy in -1..1) {
                pts.add((x + dx).coerceIn(0, CANVAS - 1) to (y + dy).coerceIn(0, CANVAS - 1))
            }
        }
        return pts
    }

    private fun house(): Set<Pair<Int, Int>> {
        val pts = HashSet<Pair<Int, Int>>()
        line(pts, 25, 45, 25, 85); line(pts, 75, 45, 75, 85); line(pts, 25, 85, 75, 85)
        line(pts, 25, 45, 50, 20); line(pts, 50, 20, 75, 45)
        return pts
    }

    private fun letterA(): Set<Pair<Int, Int>> {
        val pts = HashSet<Pair<Int, Int>>()
        thick(pts, 28, 85, 50, 22); thick(pts, 50, 22, 72, 85)
        for (y in 56..60) for (x in 38..62) pts.add(x to y)
        return pts
    }

    private fun plan(): Set<Pair<Int, Int>> {
        val pts = HashSet<Pair<Int, Int>>()
        line(pts, 15, 15, 85, 15); line(pts, 15, 85, 85, 85)
        line(pts, 15, 15, 15, 85); line(pts, 85, 15, 85, 85)
        line(pts, 55, 22, 55, 45); line(pts, 55, 55, 55, 78)
        line(pts, 38, 85, 48, 85)
        line(pts, 43, 85, 43, 60); line(pts, 43, 60, 55, 50)
        return pts
    }

    /** 7 类样本：名称 → 灰度图。 */
    fun all(): LinkedHashMap<String, GrayImage> {
        fun canvas(ink: Set<Pair<Int, Int>>): GrayImage =
            GrayImage(CANVAS, CANVAS, IntArray(CANVAS * CANVAS) { i ->
                val x = i % CANVAS
                val y = i / CANVAS
                if (ink.contains(x to y)) 0 else 255
            })
        return linkedMapOf(
            "圆" to canvas(ring()),
            "方框" to canvas(square()),
            "箭头" to canvas(arrow()),
            "心形" to canvas(heart()),
            "房屋" to canvas(house()),
            "字母A" to canvas(letterA()),
            "平面图" to canvas(plan()),
        )
    }
}
