package com.spop.poverlay

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import com.spop.poverlay.ble.BleServer
import com.spop.poverlay.sensor.SensorSnapshotRepository
import com.spop.poverlay.sensor.interfaces.DummySensorInterface
import com.spop.poverlay.sensor.interfaces.PelotonBikePlusSensorInterface
import com.spop.poverlay.sensor.interfaces.PelotonBikeSensorInterfaceV1New
import com.spop.poverlay.sensor.interfaces.SensorInterface
import com.spop.poverlay.util.IsBikePlus
import com.spop.poverlay.util.IsRunningOnPeloton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import timber.log.Timber

class GrupettoApplication : Application() {
    lateinit var bleServer: BleServer
        private set
    lateinit var sensorInterface: SensorInterface
        private set
    lateinit var sensorSnapshotRepository: SensorSnapshotRepository
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        sensorInterface = createSensorInterface()
        sensorSnapshotRepository = SensorSnapshotRepository(sensorInterface, appScope)
        bleServer = BleServer(this, bluetoothManager, sensorSnapshotRepository)
    }

    private fun createSensorInterface(): SensorInterface {
        return if (IsRunningOnPeloton) {
            if (IsBikePlus) {
                PelotonBikePlusSensorInterface(this)
            } else {
                PelotonBikeSensorInterfaceV1New(this)
            }
        } else {
            // For testing on an emulator
            DummySensorInterface()
        }
    }
}
