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

    @Test
    fun `detects the shared topaz tablet`() {
        // PLTN-TTR01 / PLTN-TTR01-2 is the Topaz TABLET, shipped on Bike+ (TITAN),
        // Tread (PRISM) and Row (CAESAR) alike, so this is not a machine test.
        assertTrue(isTreadModel("PLTN-TTR01"))
        assertTrue(isTreadModel("PLTN-TTR01-2"))
        assertTrue(isTreadModel("pltn-ttr01"))
    }

    @Test
    fun `does not match non topaz tablets`() {
        // Bike Gen 1 (PLTN-RB1VQ) and the G700 cross trainer run different tablets.
        assertFalse(isTreadModel("PLTN-RB1VQ"))
        assertFalse(isTreadModel("g700"))
        assertFalse(isTreadModel("PLTN-ATR01"))
        assertFalse(isTreadModel(""))
    }
}
