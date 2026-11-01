package com.spop.poverlay.sensor.discovery

import android.content.ComponentName
import android.content.Intent

internal const val SENSOR_DATA_BIKE_CATEGORY = "com.peloton.sensor.category.BIKE"

/** These are wire contracts, not package/model allowlists. Discovery never guesses transaction IDs. */
enum class BikeProtocol(val label: String, val action: String, val descriptor: String) {
    AffernetV1("Affernet Bike", "com.onepeloton.affernetservice.IV1Interface",
        "com.onepeloton.affernetservice.IV1Interface"),
    AffernetBikePlus("Affernet Bike+", "com.onepeloton.affernetservice.IBikeInterface",
        "com.onepeloton.affernetservice.IBikeInterface"),
    SensorData("SensorData Bike", "android.intent.action.peloton.SensorData", "android.os.IMessenger");

    companion object {
        fun identify(descriptor: String?, action: String?): BikeProtocol? = entries.firstOrNull {
            it.descriptor == descriptor && (it != SensorData || action == it.action)
        }
    }
}

data class BikeServiceEndpoint(
    val packageName: String,
    val className: String,
    val action: String?,
    val categories: List<String> = emptyList(),
) {
    val component get() = "$packageName/$className"
    fun intent() = Intent(action).setComponent(ComponentName(packageName, className)).apply {
        // Manifest categories are accepted alternatives, not instructions to activate every mode.
        // Also sanitize saved SensorData endpoints produced by an older scanner.
        val bindingCategories = if (action == BikeProtocol.SensorData.action) listOf(SENSOR_DATA_BIKE_CATEGORY)
            else this@BikeServiceEndpoint.categories
        bindingCategories.forEach { addCategory(it) }
    }
}

data class BikeServiceProfile(val endpoint: BikeServiceEndpoint, val protocol: BikeProtocol)
data class ServiceAction(val name: String, val categories: List<String> = emptyList())

data class InstalledBikeService(
    val packageName: String,
    val className: String,
    val process: String,
    val uid: Int,
    val version: String,
    val enabled: Boolean,
    val exported: Boolean,
    val permission: String?,
    val permissionGranted: Boolean,
    val system: Boolean,
    val actions: List<ServiceAction>,
    val running: Boolean = false,
    val pid: String? = null,
    val clients: List<String> = emptyList(),
) {
    val component get() = "$packageName/$className"
    val accessible get() = enabled && exported && permissionGranted
    val relevant get() = Regex("peloton|affernet|sensor|metric|bike|cycling|workout|cadence", RegexOption.IGNORE_CASE)
        .containsMatchIn(listOf(component, permission.orEmpty()).plus(actions.map { it.name }).joinToString(" "))
    fun endpoints(): List<BikeServiceEndpoint> = actions.map {
        BikeServiceEndpoint(packageName, className, it.name,
            if (it.name == BikeProtocol.SensorData.action) listOf(SENSOR_DATA_BIKE_CATEGORY) else emptyList())
    }.ifEmpty { listOf(BikeServiceEndpoint(packageName, className, null)) }
}

data class BikeTelemetrySample(val watts: Float, val rpm: Float, val resistance: Float) {
    fun plausible() = watts.isFinite() && rpm.isFinite() && resistance.isFinite() &&
        watts in 0f..5000f && rpm in 0f..300f && resistance in 0f..100f
}

data class BikeProbeResult(
    val endpoint: BikeServiceEndpoint,
    val descriptor: String? = null,
    val protocol: BikeProtocol? = null,
    val sample: BikeTelemetrySample? = null,
    val outcome: String,
) {
    val profile get() = if (sample != null && protocol != null) BikeServiceProfile(endpoint, protocol) else null
}

/** A Messenger descriptor alone says nothing about the message schema or the attached machine. */
internal fun shouldSkipSensorData(endpoint: BikeServiceEndpoint, services: List<InstalledBikeService>) =
    endpoint.action == BikeProtocol.SensorData.action && services.any { service ->
        service.enabled && service.actions.any { action ->
            action.name == BikeProtocol.AffernetV1.action || action.name == BikeProtocol.AffernetBikePlus.action
        }
    }

internal fun probeEndpoints(services: List<InstalledBikeService>): List<BikeServiceEndpoint> = services
    .filter { (it.relevant || it.running) && it.accessible }
    .sortedWith(compareByDescending<InstalledBikeService> { service ->
        service.actions.any { action -> BikeProtocol.entries.any { it.action == action.name } }
    }.thenByDescending { it.relevant }.thenByDescending { it.running }.thenByDescending { it.system }.thenBy { it.component })
    .flatMap { it.endpoints() }.distinct()
    .sortedByDescending { endpoint -> BikeProtocol.entries.any { it.action == endpoint.action } }

/** Affernet can expose bike binders even on other machines; an interface name is not hardware identity. */
internal fun knownNonBikePlatform(platform: String?): Boolean {
    val normalized = platform?.trim()?.lowercase() ?: return false
    return listOf("prism", "aurora", "caesar").any { normalized == it || normalized.startsWith("$it-") }
}
