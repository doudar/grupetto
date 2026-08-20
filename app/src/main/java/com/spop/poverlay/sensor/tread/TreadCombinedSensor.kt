package com.spop.poverlay.sensor.tread

import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import com.spop.poverlay.sensor.TreadData
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import timber.log.Timber

/**
 * Raw MCB speed is reported in tenths of a mile per hour; scale to mph.
 * (Research doc section 6: e.g. raw 32 -> 3.2 mph.)
 */
internal fun mphFromRaw(raw: Int): Float = raw / 10f

/**
 * Raw MCB incline is reported in tenths of a percent grade; scale to percent.
 * (Research doc section 6: e.g. raw 35 -> 3.5%.)
 */
internal fun inclinePercentFromRaw(raw: Int): Float = raw / 10f

/**
 * Registers an `ITreadCallback` with the Peloton affernet tread interface and emits
 * scaled sensor flows from the ~20 Hz `onSensorDataChange` stream.
 *
 * SAFETY: this drives a real motorized treadmill. Every request parcel goes through
 * a single [guardedTransact] wrapper checked against an immutable allowlist of
 * read/lifecycle transaction codes. grupetto only ever registers/unregisters a
 * read-only callback; it never sends any control code.
 */
class TreadCombinedSensor(
    private val binder: IBinder,
    private val context: Context,
    // The ServiceConnection that produced [binder] (see getTreadBinder/TreadBinding).
    // Retained so [stop] can unbind it and prevent ServiceConnectionLeaked. May be
    // null in tests that drive the binder directly without a real bind.
    private val connection: ServiceConnection? = null
) {
    private val mutablePower = MutableSharedFlow<Float>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val power = mutablePower.asSharedFlow()

    private val mutableCadence = MutableSharedFlow<Float>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val cadence = mutableCadence.asSharedFlow()

    private val mutableResistance = MutableSharedFlow<Float>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val resistance = mutableResistance.asSharedFlow()

    private val mutableSpeed = MutableSharedFlow<Float>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val speed = mutableSpeed.asSharedFlow()

    private val mutableIncline = MutableSharedFlow<Float>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val incline = mutableIncline.asSharedFlow()

    private var callbackRegistered = false
    private var unbound = false
    private val callbackBinder = createCallback()

    companion object {
        private const val INTERFACE_DESCRIPTOR = "com.onepeloton.affernetservice.ITreadInterface"
        private const val CALLBACK_DESCRIPTOR = "com.onepeloton.affernetservice.ITreadCallback"

        // Callback lifecycle transactions (the ONLY codes grupetto ever sends).
        private const val REGISTER_CODE = 48
        private const val UNREGISTER_CODE = 49
        private const val REGISTER_PROCESS_DEATH_CODE = 83

        /**
         * SAFETY allowlist. The only transaction codes grupetto is ever permitted to
         * send on the tread interface: pure read getters plus the read-only callback
         * lifecycle. Any code outside this set is rejected by [guardedTransact].
         * Motion/write/state-mutating codes are deliberately absent and must never
         * appear anywhere.
         */
        private val ALLOWED_TRANSACTIONS: Set<Int> = setOf(
            1, 12, 13, 14, 15, 17, 18, 19, 20, 21, 23, 44, 61, 88,
            REGISTER_CODE, UNREGISTER_CODE, REGISTER_PROCESS_DEATH_CODE
        )

        // Callback (ITreadCallback) transaction codes we receive.
        private const val CALLBACK_SENSOR_DATA = 1
        private const val CALLBACK_SENSOR_ERROR = 2
        private const val CALLBACK_TREAD_LOCKED = 8
        private const val CALLBACK_CONTROL_EVENT = 9

        // Power model (research doc section 6): confirmed from Peloton client source.
        private const val POWER_CONSTANT = 43.2521f
    }

    /**
     * The single guarded path for every transaction grupetto sends to the tread.
     * Rejects any code not on the immutable [ALLOWED_TRANSACTIONS] allowlist.
     */
    private fun guardedTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        require(ALLOWED_TRANSACTIONS.contains(code)) {
            "Refusing to send disallowed tread transaction code $code"
        }
        return binder.transact(code, data, reply, flags)
    }

    fun start() {
        if (callbackRegistered) {
            Timber.w("TreadCombinedSensor already started")
            return
        }
        try {
            registerCallback()
            // The callback is now live in the remote RemoteCallbackList. Gate cleanup
            // on this flag IMMEDIATELY, before registerProcessDeath can throw, so a
            // registered callback is always unregistered by stop() (no leak).
            callbackRegistered = true
            registerProcessDeath()
            Timber.d("TreadCombinedSensor started successfully")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start TreadCombinedSensor")
        }
    }

    fun stop() {
        // CRITICAL ORDER: unregister the callback (txn 49) FIRST, then unbind the
        // service. Never unbind while the callback is still registered remotely.
        if (callbackRegistered) {
            try {
                unregisterCallback()
            } catch (e: Exception) {
                Timber.e(e, "Failed to stop TreadCombinedSensor")
            } finally {
                // Reset unconditionally so unregister runs exactly once (idempotent).
                callbackRegistered = false
            }
        }
        unbind()
    }

    /**
     * Release the affernet binding retained from [getTreadBinder]. Idempotent and
     * safe when [connection] is null (never bound); prevents ServiceConnectionLeaked.
     * Always called AFTER [unregisterCallback] in [stop].
     */
    private fun unbind() {
        val conn = connection ?: return
        if (unbound) return
        unbound = true
        try {
            context.unbindService(conn)
            Timber.d("Unbound tread service connection")
        } catch (e: Exception) {
            Timber.w(e, "Failed to unbind tread service connection")
        }
    }

    private fun registerCallback() {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(INTERFACE_DESCRIPTOR)
            data.writeStrongBinder(callbackBinder)
            // arg2 is null; arg3 is grupetto's OWN package name (RemoteCallbackList
            // cookie). Never a Peloton package name (see research doc section 7.3).
            data.writeString(null)
            data.writeString(context.packageName)

            Timber.d("Registering tread callback with interface: $INTERFACE_DESCRIPTOR")
            val success = guardedTransact(REGISTER_CODE, data, reply, 0)
            if (success) {
                reply.readException()
                Timber.i("Successfully registered tread callback")
            } else {
                throw Exception("Failed to register tread callback")
            }
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun registerProcessDeath() {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(INTERFACE_DESCRIPTOR)
            data.writeStrongBinder(android.os.Binder())
            data.writeString(context.packageName)

            val success = guardedTransact(REGISTER_PROCESS_DEATH_CODE, data, reply, 0)
            if (success) {
                reply.readException()
                Timber.d("Registered tread process-death link")
            }
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun unregisterCallback() {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(INTERFACE_DESCRIPTOR)
            data.writeStrongBinder(callbackBinder)
            data.writeString(context.packageName)

            val success = guardedTransact(UNREGISTER_CODE, data, reply, 0)
            if (success) {
                reply.readException()
                Timber.d("Successfully unregistered tread callback")
            }
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * Peloton grade-based power model (research doc section 6):
     * ((grade% * k1) + k2) * mph * 43.2521, with (k1, k2) = (0.05, 0.95) below
     * 10% grade, else (0.07, 0.75).
     */
    private fun derivePower(mph: Float, gradePercent: Float): Float {
        if (mph <= 0f) return 0f
        val (k1, k2) = if (gradePercent < 10f) 0.05f to 0.95f else 0.07f to 0.75f
        val power = ((gradePercent * k1) + k2) * mph * POWER_CONSTANT
        return if (power < 0f) 0f else power
    }

    private fun createCallback() = object : android.os.Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            return when (code) {
                android.os.IBinder.INTERFACE_TRANSACTION -> {
                    reply?.writeString(CALLBACK_DESCRIPTOR)
                    true
                }
                CALLBACK_SENSOR_DATA -> { // onSensorDataChange(TreadData)
                    try {
                        data.enforceInterface(CALLBACK_DESCRIPTOR)
                        val hasData = data.readInt()
                        if (hasData != 0) {
                            val treadData = TreadData.CREATOR.createFromParcel(data)
                            val mph = mphFromRaw(treadData.mcbCurrentSpeed)
                            val gradePercent = inclinePercentFromRaw(treadData.mcbCurrentIncline)
                            mutableSpeed.tryEmit(mph)
                            mutableIncline.tryEmit(gradePercent)
                            mutableCadence.tryEmit(0f)
                            mutableResistance.tryEmit(0f)
                            mutablePower.tryEmit(derivePower(mph, gradePercent))
                        }
                        true
                    } catch (e: Exception) {
                        Timber.e(e, "Error processing tread sensor data")
                        false
                    }
                }
                CALLBACK_SENSOR_ERROR -> { // onSensorError(long)
                    try {
                        data.enforceInterface(CALLBACK_DESCRIPTOR)
                        val errorCode = data.readLong()
                        Timber.w("Tread sensor error: $errorCode")
                        true
                    } catch (e: Exception) {
                        Timber.e(e, "Error processing tread sensor error")
                        false
                    }
                }
                CALLBACK_TREAD_LOCKED -> { // onTreadLocked(boolean, int)
                    try {
                        data.enforceInterface(CALLBACK_DESCRIPTOR)
                        val locked = data.readInt() != 0
                        val reason = data.readInt()
                        Timber.d("Tread locked=$locked reason=$reason")
                        true
                    } catch (e: Exception) {
                        Timber.e(e, "Error processing tread locked event")
                        false
                    }
                }
                CALLBACK_CONTROL_EVENT -> { // onTreadControlEvent(byte, long)
                    try {
                        data.enforceInterface(CALLBACK_DESCRIPTOR)
                        val event = data.readByte()
                        val value = data.readLong()
                        Timber.d("Tread control event=$event value=$value")
                        true
                    } catch (e: Exception) {
                        Timber.e(e, "Error processing tread control event")
                        false
                    }
                }
                else -> {
                    super.onTransact(code, data, reply, flags)
                }
            }
        }
    }
}
