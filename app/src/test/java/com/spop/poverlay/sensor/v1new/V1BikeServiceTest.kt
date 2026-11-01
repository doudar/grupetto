package com.spop.poverlay.sensor.v1new

import org.junit.Assert.*
import org.junit.Test

class V1BikeServiceTest {
    private fun candidate(protocol: V1BikeService, name: String, accessible: Boolean = true, system: Boolean = false) =
        V1ServiceCandidate(protocol, name, "$name.Telemetry", name, 10100, null, accessible, system)

    @Test fun `Affernet protocol wins even under an unfamiliar package name`() {
        val affernet = candidate(V1BikeService.Affernet, "vendor.nextgeneration")
        val legacy = candidate(V1BikeService.SensorData, "com.peloton.service.SensorData")
        assertEquals(listOf(affernet), selectV1BikeServices(listOf(legacy, affernet)))
    }

    @Test fun `SensorData implementations do not require a known package name`() {
        val legacy = candidate(V1BikeService.SensorData, "vendor.renamed")
        assertEquals(listOf(legacy), selectV1BikeServices(listOf(legacy)))
    }

    @Test fun `permission denied Affernet does not authorize serial takeover`() {
        val blocked = candidate(V1BikeService.Affernet, "vendor.sensor", accessible = false)
        val legacy = candidate(V1BikeService.SensorData, "vendor.legacy")
        assertTrue(selectV1BikeServices(listOf(blocked, legacy)).isEmpty())
    }

    @Test fun `system implementations are attempted before sideloaded compatible services`() {
        val system = candidate(V1BikeService.Affernet, "z.system", system = true)
        val thirdParty = candidate(V1BikeService.Affernet, "a.sideloaded")
        assertEquals(listOf(system, thirdParty), selectV1BikeServices(listOf(thirdParty, system)))
    }

    @Test fun `no accessible supported services gives no candidates`() {
        assertTrue(selectV1BikeServices(emptyList()).isEmpty())
        assertTrue(selectV1BikeServices(listOf(candidate(V1BikeService.SensorData, "denied", false))).isEmpty())
    }

    @Test fun `Binder contract must match the advertised protocol`() {
        validateV1ServiceDescriptor(V1BikeService.Affernet, "com.onepeloton.affernetservice.IV1Interface")
        validateV1ServiceDescriptor(V1BikeService.SensorData, "android.os.IMessenger")
        assertTrue(runCatching { validateV1ServiceDescriptor(V1BikeService.Affernet, "vendor.NewProtocol") }.isFailure)
        assertTrue(runCatching { validateV1ServiceDescriptor(V1BikeService.SensorData, null) }.isFailure)
        assertTrue(runCatching { validateV1ServiceDescriptor(V1BikeService.Affernet, "android.os.IMessenger") }.isFailure)
    }
}
