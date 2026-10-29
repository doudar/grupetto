package com.spop.poverlay.sensor.discovery

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class BikeDiscoveryState(
    val scanning: Boolean = false,
    val message: String = "Find the service that supplies this bike's power, cadence and resistance.",
    val serviceCount: Int = 0,
    val results: List<BikeProbeResult> = emptyList(),
    val notes: List<String> = emptyList(),
    val reportAvailable: Boolean = false,
    val selected: BikeServiceProfile? = null,
)

/** App-owned, repeatable discovery. Closing its dialog does not lose the report or start another scan. */
class BikeServiceDiscovery internal constructor(
    private val context: Context,
    private val inventoryProvider: suspend () -> BikeServiceInventory = { collectBikeServiceInventory(context) },
    private val probe: suspend (BikeServiceEndpoint) -> BikeProbeResult = { probeBikeEndpoint(context, it) },
) {
    private val store = BikeDiscoveryStore(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var scanJob: Job? = null
    private val mutableState = MutableStateFlow(BikeDiscoveryState(
        reportAvailable = store.reportFile.isFile, selected = store.selected()))
    val state = mutableState.asStateFlow()
    private val mutableConnection = MutableStateFlow("Using automatic device selection")
    val connection = mutableConnection.asStateFlow()
    fun connectionStatus(message: String) { mutableConnection.value = message }

    fun selectedProfile() = store.selected().takeUnless {
        knownNonBikePlatform(com.spop.poverlay.util.readPelotonPlatform(context))
    }

    // Called from the UI thread; the flag is set before launching so repeated taps cannot overlap.
    fun scan() {
        if (mutableState.value.scanning) return
        mutableState.value = mutableState.value.copy(scanning = true, message = "Reading installed services…", results = emptyList(), notes = emptyList())
        scanJob = scope.launch(start = CoroutineStart.ATOMIC) { runScan() }
    }
    fun cancel() { scanJob?.cancel() }

    private suspend fun runScan() {
        var inventory = BikeServiceInventory(emptyList(), emptyList())
        val results = mutableListOf<BikeProbeResult>()
        var message = "Discovery complete"
        try {
            inventory = inventoryProvider()
            mutableState.value = mutableState.value.copy(serviceCount = inventory.services.size, notes = inventory.notes)
            // Save the passive inventory first, so even an interrupted Binder probe leaves useful evidence.
            store.saveReport(inventory, results, "Inventory complete; probing interfaces")
            val endpoints = probeEndpoints(inventory.services)
            if (endpoints.size > 24) inventory = inventory.copy(notes = inventory.notes +
                "Only the first 24 candidate endpoints were probed; all inventoried services are included in this report.")
            withTimeout(45_000) {
                for ((index, endpoint) in endpoints.take(24).withIndex()) {
                    ensureActive()
                    mutableState.value = mutableState.value.copy(message = "Checking ${index + 1}/${minOf(endpoints.size, 24)}: ${endpoint.packageName}")
                    val result = if (shouldSkipSensorData(endpoint, inventory.services) ||
                        (endpoint.action == BikeProtocol.SensorData.action && results.any {
                            it.protocol == BikeProtocol.AffernetV1 || it.protocol == BikeProtocol.AffernetBikePlus
                        })) BikeProbeResult(endpoint,
                        outcome = "Skipped: an enabled Affernet provider may own the sensor connection")
                    else probe(endpoint)
                    results += result
                    mutableState.value = mutableState.value.copy(results = results.toList())
                    store.saveReport(inventory, results, "Probing interfaces")
                }
            }
            val verified = results.count { it.profile != null }
            message = if (verified == 0) "No compatible bike telemetry verified. Diagnostic report saved."
                else "$verified compatible connection${if (verified == 1) "" else "s"} verified. Choose one to use."
        } catch (e: TimeoutCancellationException) {
            message = "Discovery reached its time limit. Partial report saved."
        } catch (e: CancellationException) {
            message = "Discovery cancelled. Partial report saved."
        } catch (e: Exception) {
            message = "Discovery failed: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                try { store.saveReport(inventory, results, message) }
                catch (e: Exception) { message += " Report could not be saved: ${e.javaClass.simpleName}" }
                mutableState.value = mutableState.value.copy(scanning = false, message = message,
                    results = results.toList(), serviceCount = inventory.services.size, notes = inventory.notes,
                    reportAvailable = store.reportFile.isFile)
            }
        }
    }

    /** Only a successfully read endpoint from this scan can replace automatic selection. */
    fun select(result: BikeProbeResult): Boolean {
        if (state.value.scanning || result !in state.value.results) return false
        if (knownNonBikePlatform(com.spop.poverlay.util.readPelotonPlatform(context))) return false
        val profile = result.profile ?: return false
        if (!store.select(profile)) return false
        mutableState.value = state.value.copy(selected = profile)
        return true
    }
    fun clearSelection(): Boolean {
        if (state.value.scanning || !store.select(null)) return false
        mutableState.value = state.value.copy(selected = null)
        return true
    }
    suspend fun exportReport(uri: Uri) = withContext(Dispatchers.IO) {
        store.reportFile.inputStream().use { input ->
            checkNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "Cannot open report destination" }
                .use { output -> input.copyTo(output) }
        }
    }
}

internal suspend fun probeBikeEndpoint(context: Context, endpoint: BikeServiceEndpoint): BikeProbeResult {
    var descriptor: String? = null
    var protocol: BikeProtocol? = null
    return try {
        withBikeBinding(context, endpoint) { binder ->
            descriptor = readBikeDescriptor(binder)
            protocol = BikeProtocol.identify(descriptor, endpoint.action)
            val known = protocol
            if (known == null) BikeProbeResult(endpoint, descriptor, outcome = "Unknown interface; no telemetry commands sent")
            else if (knownNonBikePlatform(com.spop.poverlay.util.readPelotonPlatform(context)))
                BikeProbeResult(endpoint, descriptor, known, outcome = "Bike interface exposed on a non-bike platform; telemetry not requested")
            else withKnownBikeTelemetry(binder, known, "GrupettoDiscovery-${java.util.UUID.randomUUID()}") { sensor ->
                BikeProbeResult(endpoint, descriptor, known, firstBikeSample(sensor), "Bike telemetry verified")
            }
        }
    } catch (e: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        BikeProbeResult(endpoint, descriptor, protocol, outcome = "Connection or telemetry timed out")
    } catch (e: CancellationException) { throw e }
    catch (e: Exception) {
        BikeProbeResult(endpoint, descriptor, protocol, outcome = "${e.javaClass.simpleName}: ${e.message.orEmpty().take(200)}")
    }
}
