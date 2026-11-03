package com.spop.poverlay.sensor.v1new

import android.content.Context
import android.content.Intent
import com.spop.poverlay.sensor.bindBikeService

const val SERVICE_ACTION = "com.onepeloton.affernetservice.IV1Interface"

/** Use the primary reader's exact Intent identity, not a second package-only binding.
 * Affernet 2.4.490 stops V1 communication in onUnbind for each Intent binding group.
 */
internal fun calibrationV1Service(candidates: List<V1ServiceCandidate>): V1ServiceCandidate {
    val primary = selectV1BikeServices(candidates).firstOrNull()
        ?: error("No accessible primary bike service for calibration.")
    check(primary.protocol == V1BikeService.Affernet &&
        primary.packageName == "com.onepeloton.affernetservice") {
        "Calibration requires the same verified Affernet service as live bike telemetry."
    }
    return primary
}

suspend fun getV1NewBinder(
    context: Context,
    intentFactory: () -> Intent = {
        calibrationV1Service(discoverV1BikeServices(context)).createIntent()
    }
) = bindBikeService(context, intentFactory)
