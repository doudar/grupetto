package com.spop.poverlay.sensor.discovery

import org.junit.Assert.*
import org.junit.Test

class BikeServiceInventoryTest {
    @Test fun `running services retain unknown identities and their clients without extras`() {
        val records = parseRunningBikeServices("""
            ACTIVITY MANAGER SERVICES
              * ServiceRecord{123 u0 vendor.opaque/.Endpoint}
                intent={act=vendor.new.READ cmp=vendor.opaque/.Endpoint extras={secret=private-value}}
                app=ProcessRecord{a1 99:vendor.opaque/1000}
                AppBindRecord{b2 client=ProcessRecord{c3 123:com.peloton.client/1002}}
                * Client AppBindRecord{53fc64d ProcessRecord{13b3ece 8383:com.spop.poverlay/u0a124}}
              * ServiceRecord{456 u0 another.package/another.package.Service}
                intent={act=another.ACTION}
        """.trimIndent())
        assertEquals(2, records.size)
        assertEquals("vendor.opaque/vendor.opaque.Endpoint", records[0].component)
        assertEquals("99", records[0].pid)
        assertEquals(listOf("vendor.new.READ"), records[0].actions)
        assertEquals(listOf("com.peloton.client", "com.spop.poverlay"), records[0].clients)
        assertNull(records[1].pid)
        assertFalse(records.toString().contains("private-value"))
        assertFalse(records[1].actions.contains("vendor.new.READ"))
    }

    @Test fun `permission denial cannot be mistaken for an active service`() {
        assertTrue(parseRunningBikeServices("Permission Denial: PACKAGE_USAGE_STATS required").isEmpty())
    }
}
