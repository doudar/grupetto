package com.spop.poverlay.sensor.discovery

import androidx.test.platform.app.InstrumentationRegistry
import com.spop.poverlay.sensor.BikeServiceDiagnostics
import com.spop.poverlay.sensor.bindBikeService
import com.spop.poverlay.sensor.interfaces.PelotonBikeSensorInterfaceV1New
import com.spop.poverlay.sensor.v1legacy.SensorDataClient
import com.spop.poverlay.sensor.v1legacy.SensorDataRead
import com.spop.poverlay.sensor.v1new.discoverV1BikeServices
import com.spop.poverlay.sensor.v1new.validateV1ServiceDescriptor
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class ServiceDiscoveryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun discoversAndReadsCompatibleServiceInAnUnlistedPackage() = runBlocking {
        val candidate = discoverV1BikeServices(context).single { it.packageName == "com.spop.poverlay.test" }
        assertTrue(candidate.accessible)
        withTimeout(5_000) {
            bindBikeService(context, candidate::createIntent).use { binding ->
                assertEquals(candidate.className, binding.componentName!!.className)
                validateV1ServiceDescriptor(candidate.protocol, binding.binder.interfaceDescriptor)
                SensorDataClient(binding.binder).use { client ->
                    assertEquals(31f, client.read(SensorDataRead.Resistance))
                }
            }
        }
    }

    @Test fun originalBikePipelineUsesDiscoveredServiceAndPublishesAllMetrics() = runBlocking {
        val discovered = discoverV1BikeServices(context)
        assumeTrue(discovered.all { it.packageName == "com.spop.poverlay.test" })
        val sensor = PelotonBikeSensorInterfaceV1New(context)
        try {
            withTimeout(8_000) {
                val watts = async { sensor.power.first() }
                val rpm = async { sensor.cadence.first() }
                val resistance = async { sensor.resistance.first() }
                assertEquals(250.5f, watts.await())
                assertEquals(0f, rpm.await())
                assertEquals(31f, resistance.await())
            }
        } finally { sensor.stop() }
    }

    @Test fun privilegedSnapshotRunsWhenDumpIsGranted() = runBlocking {
        assumeTrue(context.packageManager.checkPermission("android.permission.DUMP", context.packageName) == 0)
        assumeTrue(context.packageManager.checkPermission("android.permission.PACKAGE_USAGE_STATS", context.packageName) == 0)
        val candidate = discoverV1BikeServices(context).single { it.packageName == "com.spop.poverlay.test" }
        withTimeout(5_000) {
            bindBikeService(context, candidate::createIntent).use {
                val lines = mutableListOf<String>()
                BikeServiceDiagnostics(context, lines::add).capture("Instrumentation snapshot")
                assertTrue(lines.joinToString("\n"), lines.any {
                    it.contains("Active service=") && it.contains("DiscoveryFixtureService") && !it.contains("pid=unknown")
                })
            }
        }
    }
}
