/*
 * Copyright (C) 2026 盲文服务 (Braille Service)
 */

package com.linxi.brailleback.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 数学页 DEBUG `dots` extra 解析断言：6/8 透传，其余回落 null。 */
class MathDebugParamsTest {

    @Test
    fun acceptsSixAndEight() {
        assertEquals(6, MathDebugParams.parseDots("6"))
        assertEquals(8, MathDebugParams.parseDots("8"))
        assertEquals(8, MathDebugParams.parseDots(" 8 "))
    }

    @Test
    fun rejectsIllegalValues() {
        assertNull(MathDebugParams.parseDots("7"))
        assertNull(MathDebugParams.parseDots("5"))
        assertNull(MathDebugParams.parseDots("abc"))
        assertNull(MathDebugParams.parseDots(null))
        assertNull(MathDebugParams.parseDots(""))
        assertNull(MathDebugParams.parseDots("   "))
    }
}
