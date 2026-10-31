package com.spop.poverlay.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Device Information Model Number names the machine so clients such as SmartSpin2k can
 * tell a Bike+ from other Peloton machines; anything uncertain stays generic.
 */
class MachineModelNumberTest {

    private fun name(model: String, platform: String?, onPeloton: Boolean = true) =
        machineModelNumber(onPeloton, model, platform)

    @Test
    fun `platform names the machine on the shared Topaz tablet`() {
        assertEquals("Peloton Bike+", name("PLTN-TTR01", "titan"))
        assertEquals("Peloton Row", name("PLTN-TTR01", "caesar"))
        assertEquals("Peloton Tread", name("PLTN-TTR01", "prism"))
        assertEquals("Peloton Tread", name("PLTN-TTR01-2", "prism-l"))
        assertEquals("Peloton Tread+", name("PLTN-TTR01", "aurora"))
    }

    @Test
    fun `platform matching ignores case and whitespace`() {
        assertEquals("Peloton Bike+", name("PLTN-TTR01", " TITAN "))
    }

    @Test
    fun `gen 1 bike from platform or from a non Topaz tablet`() {
        assertEquals("Peloton Bike", name("GEN1-TABLET-EXAMPLE", "v1"))
        assertEquals("Peloton Bike", name("GEN1-TABLET-EXAMPLE", null))
        assertEquals("Peloton Bike", name("GEN1-TABLET-EXAMPLE", ""))
    }

    @Test
    fun `cross trainer is recognised by its model`() {
        assertEquals("Peloton Cross Trainer", name("PLTN-ATR01", null))
        assertEquals("Peloton Cross Trainer", name("G700", "titan"))
    }

    @Test
    fun `unknown machines stay generic`() {
        assertEquals(GenericModelNumber, name("PLTN-TTR01", null))
        assertEquals(GenericModelNumber, name("PLTN-TTR01", "something-new"))
        assertEquals(GenericModelNumber, name("Pixel 7", null, onPeloton = false))
    }
}
