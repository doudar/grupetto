package com.spop.poverlay.sensor.discovery

import org.junit.Assert.*
import org.junit.Test

class BikeServiceProfileTest {
    private fun service(name: String = "vendor.cycle.Service", actions: List<String> = emptyList(),
        accessible: Boolean = true, running: Boolean = false) = InstalledBikeService(
        "vendor.new", name, "vendor.new", 1234, "1", true, accessible, null, true, false,
        actions.map { ServiceAction(it) }, running)

    @Test fun `known Binder contract is recognized even under a new action and package`() {
        assertEquals(BikeProtocol.AffernetV1, BikeProtocol.identify(BikeProtocol.AffernetV1.descriptor, "vendor.changed.ACTION"))
        assertEquals(BikeProtocol.AffernetBikePlus, BikeProtocol.identify(BikeProtocol.AffernetBikePlus.descriptor, null))
        assertNull(BikeProtocol.identify("vendor.new.Interface", BikeProtocol.AffernetV1.action))
    }

    @Test fun `generic Messenger is never treated as SensorData without its protocol action`() {
        assertNull(BikeProtocol.identify("android.os.IMessenger", "vendor.metrics.MESSAGES"))
        assertNull(BikeProtocol.identify("android.os.IMessenger", null))
        assertEquals(BikeProtocol.SensorData, BikeProtocol.identify("android.os.IMessenger", BikeProtocol.SensorData.action))
    }

    @Test fun `advertised alternative categories are not all sent as binding modes`() {
        val categories = listOf("android.intent.category.DEFAULT", "com.peloton.sensor.category.FAKE_DATA",
            SENSOR_DATA_BIKE_CATEGORY, "com.peloton.sensor.category.AURORA")
        val candidate = service().copy(actions = listOf(ServiceAction(BikeProtocol.SensorData.action, categories),
            ServiceAction("vendor.bike.UNKNOWN", categories)))
        assertEquals(listOf(SENSOR_DATA_BIKE_CATEGORY), candidate.endpoints()[0].categories)
        assertTrue(candidate.endpoints()[1].categories.isEmpty())
        assertEquals(categories, candidate.actions[0].categories) // The report retains the original manifest evidence.
    }

    @Test fun `unknown active exported services can be inspected without matching a name`() {
        val opaque = service("vendor.opaque.A", running = true)
        assertFalse(opaque.relevant)
        assertEquals(opaque.component, probeEndpoints(listOf(opaque)).single().component)
        assertTrue(probeEndpoints(listOf(service("vendor.opaque.A"))).isEmpty())
    }

    @Test fun `inaccessible candidates stay in inventory but are not bound`() {
        val inaccessible = service(actions = listOf(BikeProtocol.AffernetV1.action), accessible = false)
        assertTrue(probeEndpoints(listOf(inaccessible)).isEmpty())
        assertTrue(shouldSkipSensorData(BikeServiceEndpoint("any", "Service", BikeProtocol.SensorData.action), listOf(inaccessible)))
    }

    @Test fun `recognized actions are tried before unknown active services`() {
        val opaque = service("vendor.opaque.A", running = true)
        val known = service("vendor.changed.Z", listOf(BikeProtocol.AffernetV1.action))
        assertEquals(listOf(known.component, opaque.component), probeEndpoints(listOf(opaque, known)).map { it.component })
    }

    @Test fun `zero readings are valid but absent or nonfinite samples cannot select a service`() {
        assertTrue(BikeTelemetrySample(0f, 0f, 31f).plausible())
        assertFalse(BikeTelemetrySample(Float.NaN, 0f, 31f).plausible())
        assertFalse(BikeTelemetrySample(200f, Float.POSITIVE_INFINITY, 31f).plausible())
        assertFalse(BikeTelemetrySample(200f, 90f, 255f).plausible())
        assertNull(BikeProbeResult(BikeServiceEndpoint("pkg", "Class", null), protocol = BikeProtocol.AffernetV1,
            outcome = "Timed out").profile)
    }

    @Test fun `non-bike platform cannot be identified as bike from Binder availability`() {
        listOf("prism", "PRISM-L", "aurora", "caesar").forEach { assertTrue(knownNonBikePlatform(it)) }
        listOf(null, "v1", "titan", "future-bike", "prismatic").forEach { assertFalse(knownNonBikePlatform(it)) }
    }
}
