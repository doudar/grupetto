package com.spop.poverlay.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `peloton_platform` (Settings.Global) is the machine discriminator; the model string
 * only identifies the tablet (Bike+, Tread and Row all ship the "Topaz" PLTN-TTR01 tablet).
 */
class PelotonPlatformDetectionTest {

    @Test
    fun `prism platform is a tread`() {
        assertTrue(isTreadPlatform("prism"))
        assertTrue(isTreadPlatform("PRISM"))
        assertTrue(isTreadPlatform(" prism "))
    }

    @Test
    fun `prism variants are treads`() {
        // peloton_platform_variant values such as prism-l / prism-b also appear in
        // peloton_platform on some builds.
        assertTrue(isTreadPlatform("prism-l"))
        assertTrue(isTreadPlatform("prism-b"))
    }

    @Test
    fun `other peloton platforms are not treads`() {
        assertFalse(isTreadPlatform("titan")) // Bike+
        assertFalse(isTreadPlatform("caesar")) // Row
        assertFalse(isTreadPlatform("aurora")) // Tread+
        assertFalse(isTreadPlatform("v1")) // Bike Gen 1
    }

    @Test
    fun `missing or unreadable platform is not a tread`() {
        assertFalse(isTreadPlatform(null))
        assertFalse(isTreadPlatform(""))
        assertFalse(isTreadPlatform("   "))
    }

    @Test
    fun `platforms that merely contain prism are not treads`() {
        assertFalse(isTreadPlatform("prismatic"))
        assertFalse(isTreadPlatform("notprism"))
    }
}
