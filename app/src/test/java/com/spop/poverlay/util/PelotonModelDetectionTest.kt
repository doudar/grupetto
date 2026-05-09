package com.spop.poverlay.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PelotonModelDetectionTest {
    @Test
    fun `detects g700 model variants`() {
        assertTrue(isG700CrossTrainerModel("G700"))
        assertTrue(isG700CrossTrainerModel("g700-cross"))
        assertTrue(isG700CrossTrainerModel("PLTN-ATR01"))
        assertTrue(isG700CrossTrainerModel("pltn-atr99"))
    }

    @Test
    fun `does not classify non g700 models as cross trainer`() {
        assertFalse(isG700CrossTrainerModel("PLTN-TTR01"))
        assertFalse(isG700CrossTrainerModel("PLTN-RB1VO"))
        assertFalse(isG700CrossTrainerModel(""))
    }
}
