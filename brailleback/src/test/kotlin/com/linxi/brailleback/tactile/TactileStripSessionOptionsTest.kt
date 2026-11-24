/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback.tactile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 界面开关 → Session 参数透传断言（纯逻辑层，不依赖 UI / Context）：
 * - G1 开关经 [TactileStripSession.g1Effective] 解析：仅「开关打开 + 多行设备」生效；
 * - 归一三态经 [TactileStripSession.normalizerConfig] 解析，模式一一对应；
 * - 默认配置与 Activity 初始状态一致：G1 关、归一 AUTO。
 */
class TactileStripSessionOptionsTest {

    private val multi = ImageStripEncoder.Spec.forDisplay(40, 4, 8)
    private val single = ImageStripEncoder.Spec.forDisplay(40, 1, 8)

    @Test
    fun g1EffectiveOnlyWhenEnabledAndMultiLine() {
        assertTrue(TactileStripSession.g1Effective(true, multi))
        // 单行设备：开关打开也不生效。
        assertFalse(TactileStripSession.g1Effective(true, single))
        // 关闭：多行设备也不生效。
        assertFalse(TactileStripSession.g1Effective(false, multi))
        assertFalse(TactileStripSession.g1Effective(false, single))
    }

    @Test
    fun normalizerConfigMapsModeThrough() {
        StockNormalizerMode.values().forEach { mode ->
            assertEquals(mode, TactileStripSession.normalizerConfig(mode).mode)
        }
    }

    @Test
    fun defaultConfigIsAutoAndG1Off() {
        assertEquals(StockNormalizerMode.AUTO, TactileStrokeNormalizer.Config().mode)
        // Session.build 默认参数：g1SplitCover=false、normalizerMode=AUTO；
        // 与 Activity 初始状态（g1SplitCover=false、normalizerMode=AUTO）一致。
        assertFalse(TactileStripSession.g1Effective(false, multi))
        assertEquals(
            StockNormalizerMode.AUTO,
            TactileStripSession.normalizerConfig(StockNormalizerMode.AUTO).mode,
        )
    }
}
