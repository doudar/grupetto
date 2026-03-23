@file:OptIn(kotlin.time.ExperimentalTime::class)

package com.spop.poverlay.sensor

import com.spop.poverlay.sensor.interfaces.SensorInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

data class SensorSnapshot(
    val power: Float,
    val cadence: Float,
    val resistance: Float,
    val speed: Float
)

class SensorSnapshotRepository(
    sensorInterface: SensorInterface,
    scope: CoroutineScope,
    uiUpdatePeriod: Duration = 250.milliseconds
) {
    private val initialSnapshot = SensorSnapshot(0f, 0f, 0f, 0f)

    val snapshot: StateFlow<SensorSnapshot> = combine(
        sensorInterface.power,
        sensorInterface.cadence,
        sensorInterface.resistance,
        sensorInterface.speed
    ) { power, cadence, resistance, speed ->
        SensorSnapshot(power, cadence, resistance, speed)
    }.stateIn(
        scope,
        SharingStarted.WhileSubscribed(5000),
        initialSnapshot
    )

    val uiSnapshot: StateFlow<SensorSnapshot> = snapshot
        .sample(uiUpdatePeriod)
        .stateIn(
            scope,
            SharingStarted.WhileSubscribed(5000),
            initialSnapshot
        )
}
