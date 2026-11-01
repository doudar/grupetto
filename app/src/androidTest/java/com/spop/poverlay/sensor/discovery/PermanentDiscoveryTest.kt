package com.spop.poverlay.sensor.discovery

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Parcel
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PermanentDiscoveryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun endpoint(action: String) = BikeServiceEndpoint("com.spop.poverlay.test",
        "com.spop.poverlay.sensor.discovery.InterfaceProbeFixtureService", action)
    private fun isolatedContext(): Context = object : ContextWrapper(context) {
        private val id = "discovery-test-${java.util.UUID.randomUUID()}"
        override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("$id-$name", mode)
        override fun getExternalFilesDir(type: String?) = File(cacheDir, id).apply { mkdirs() }
    }

    @Test fun manifestInventoryFindsUnknownActionsWithoutAQueryAllowlist() = runBlocking {
        val inventory = collectBikeServiceInventory(context)
        val service = inventory.services.single { it.className == endpoint("").className }
        assertTrue(service.accessible)
        assertTrue(service.actions.any { it.name == "vendor.cycle.V1" })
        assertTrue(service.actions.any { it.name == "vendor.cycle.UNKNOWN" })
    }

    @Test fun renamedV1AndBikePlusContractsProvideRealBinderTelemetry() = runBlocking {
        for ((action, protocol) in listOf("vendor.cycle.V1" to BikeProtocol.AffernetV1, "vendor.cycle.PLUS" to BikeProtocol.AffernetBikePlus)) {
            val result = withTimeout(8_000) { probeBikeEndpoint(context, endpoint(action)) }
            assertEquals(result.toString(), protocol, result.protocol)
            assertEquals(BikeTelemetrySample(1234.5f, 0f, 31f), result.sample)
            assertNotNull(result.profile)
        }
    }

    @Test fun sensorDataUsesOnlyBikeModeEvenIfASavedEndpointContainsFakeDataCategory() = runBlocking {
        val endpoint = BikeServiceEndpoint("com.spop.poverlay.test",
            "com.spop.poverlay.sensor.discovery.DiscoveryFixtureService", BikeProtocol.SensorData.action,
            listOf("com.peloton.sensor.category.FAKE_DATA", SENSOR_DATA_BIKE_CATEGORY, "com.peloton.sensor.category.AURORA"))
        assertEquals(setOf(SENSOR_DATA_BIKE_CATEGORY), endpoint.intent().categories)
        val result = probeBikeEndpoint(context, endpoint)
        assertEquals(result.toString(), BikeProtocol.SensorData, result.protocol)
        assertEquals(BikeTelemetrySample(250.5f, 0f, 31f), result.sample)
    }

    @Test fun unknownBinderAndUnrelatedMessengerReceiveNoGuessedTelemetryTransactions() = runBlocking {
        // Keep the fixture alive across the checks so its counter survives all unbindings.
        withBikeBinding(context, endpoint("vendor.cycle.UNKNOWN")) { binder ->
            fun count(): Int {
                val data = Parcel.obtain(); val reply = Parcel.obtain()
                try { binder.transact(999, data, reply, 0); reply.readException(); return reply.readInt() }
                finally { data.recycle(); reply.recycle() }
            }
            val before = count()
            for (action in listOf("vendor.cycle.UNKNOWN", "vendor.cycle.MESSENGER")) {
                val result = probeBikeEndpoint(context, endpoint(action))
                assertNull(result.protocol)
                assertNull(result.profile)
                assertTrue(result.outcome.contains("no telemetry commands"))
            }
            assertEquals(before, count())
        }
    }

    @Test fun selectedServicePersistsAndFeedsTheNormalSensorFlowsWithoutMotorControl() = runBlocking {
        val isolated = isolatedContext()
        val result = probeBikeEndpoint(context, endpoint("vendor.cycle.V1"))
        val store = BikeDiscoveryStore(isolated)
        assertTrue(store.select(checkNotNull(result.profile)))
        val restored = BikeDiscoveryStore(isolated).selected()
        assertEquals(result.profile, restored)
        val sensor = DiscoveredBikeSensorInterface(isolated, checkNotNull(restored)) { }
        try {
            assertEquals(BikeTelemetrySample(1234.5f, 0f, 31f), firstBikeSample(sensor))
            assertNull(sensor.bikeControl)
        } finally { sensor.stop(); store.select(null) }
        assertNull(store.selected())
    }

    @Test fun changedDescriptorIsRejectedWhenRestoringASavedService() = runBlocking {
        val status = kotlinx.coroutines.flow.MutableStateFlow("")
        val sensor = DiscoveredBikeSensorInterface(context,
            BikeServiceProfile(endpoint("vendor.cycle.UNKNOWN"), BikeProtocol.AffernetV1)) { status.value = it }
        val reading = async { sensor.power.first() }
        try {
            withTimeout(5_000) { status.first { it.contains("Service interface changed") } }
            assertFalse(reading.isCompleted)
        } finally { reading.cancelAndJoin(); sensor.stop() }
    }

    @Test fun completedScanSavesAndExportsUnknownInterfacesAndOnlySelectsVerifiedResults() = runBlocking {
        val isolated = isolatedContext()
        val inventory = collectBikeServiceInventory(isolated).let { it.copy(services = it.services.filter { s -> s.className == endpoint("").className }) }
        val discovery = BikeServiceDiscovery(isolated, { inventory })
        discovery.scan()
        val state = withTimeout(20_000) { discovery.state.first { !it.scanning } }
        assertEquals(state.toString(), 5, state.results.size)
        val unknown = state.results.first { it.endpoint.action == "vendor.cycle.UNKNOWN" }
        assertFalse(discovery.select(unknown))
        assertTrue(discovery.select(state.results.first { it.profile != null }))
        assertNotNull(BikeDiscoveryStore(isolated).selected())
        assertTrue(state.reportAvailable)
        val report = JSONObject(BikeDiscoveryStore(isolated).reportFile.readText())
        assertEquals(1, report.getJSONArray("services").length())
        assertEquals(5, report.getJSONArray("probes").length())
        assertTrue(report.toString().contains("vendor.cycle.UnknownInterface"))
        val exported = File(isolated.getExternalFilesDir(null), "export.json")
        discovery.exportReport(Uri.fromFile(exported))
        assertEquals(report.toString(), JSONObject(exported.readText()).toString())
        assertTrue(discovery.clearSelection())
    }

    @Test fun cancellingScanWritesAPartialReportAndAllowsAnotherRun() = runBlocking<Unit> {
        val isolated = isolatedContext()
        val inventory = collectBikeServiceInventory(isolated).let { it.copy(services = it.services.filter { s -> s.className == endpoint("").className }) }
        val entered = CompletableDeferred<Unit>()
        val discovery = BikeServiceDiscovery(isolated, { inventory }) {
            entered.complete(Unit)
            awaitCancellation()
        }
        discovery.scan()
        withTimeout(5_000) { entered.await() }
        discovery.cancel()
        val state = withTimeout(5_000) { discovery.state.first { !it.scanning } }
        assertTrue(state.message.contains("cancelled"))
        assertTrue(state.reportAvailable)
        assertTrue(JSONObject(BikeDiscoveryStore(isolated).reportFile.readText()).getString("status").contains("cancelled"))
        discovery.scan()
        assertTrue(discovery.state.value.scanning)
        discovery.cancel()
        withTimeout(5_000) { discovery.state.first { !it.scanning } }
    }

    @Test fun recognizedInterfaceWithoutTelemetryCannotBeSelected() = runBlocking {
        val result = withTimeout(8_000) { probeBikeEndpoint(context, endpoint("vendor.cycle.SILENT")) }
        assertEquals(BikeProtocol.AffernetV1, result.protocol)
        assertNull(result.profile)
        assertNull(result.sample)
        assertTrue(result.outcome.contains("timed out"))
    }
}
