package com.spop.poverlay.sensor.v2

import android.os.IBinder
import android.os.Parcel
import timber.log.Timber

/** Kept separate so the real parcel contract can be tested without running the poller. */
internal fun writeBikeResistance(binder: IBinder, resistance: Int): Boolean {
    if (resistance !in 0..100) return false
    val data = Parcel.obtain()
    val reply = Parcel.obtain()
    return try {
        data.writeInterfaceToken(SERVICE_ACTION)
        data.writeInt(resistance)
        if (!binder.transact(7, data, reply, 0)) false else {
            reply.readException()
            true
        }
    } catch (error: Exception) {
        Timber.w(error, "Bike+ resistance write failed")
        false
    } finally {
        data.recycle()
        reply.recycle()
    }
}
