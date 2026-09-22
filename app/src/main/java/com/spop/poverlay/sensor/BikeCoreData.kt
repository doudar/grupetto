package com.spop.poverlay.sensor

import android.os.Parcel

/**
 * The subset of [BikeData]'s fields actually consumed by the sensor interfaces.
 */
data class BikeCoreData(
    val rpm: Long,
    val power: Long,
    val currentResistance: Int,
    val targetResistance: Int
)

/**
 * Reads only the fields needed by the sensor interfaces, avoiding the ~40-field/14-string
 * allocation cost of [BikeData.readFromParcel]. Field order must match [BikeData.readFromParcel]
 * exactly since the parcel is a flat, positional wire format written by the remote bike service -
 * the two unused longs are read (not skipped) to keep the parcel cursor aligned.
 */
fun readBikeCoreData(parcel: Parcel): BikeCoreData {
    val rpm = parcel.readLong()
    val power = parcel.readLong()
    parcel.readLong() // mStepperMotorPosition - unused
    parcel.readLong() // mLoadCellReading - unused
    val currentResistance = parcel.readInt()
    val targetResistance = parcel.readInt()
    return BikeCoreData(rpm, power, currentResistance, targetResistance)
}
