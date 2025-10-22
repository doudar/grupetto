package com.spop.poverlay

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import com.spop.poverlay.ble.BleServer
import com.spop.poverlay.sensor.interfaces.PelotonBikePlusSensorInterface
import com.spop.poverlay.sensor.interfaces.PelotonBikeSensorInterfaceV1New
import com.spop.poverlay.sensor.interfaces.SensorInterface
import com.spop.poverlay.util.IsBikePlus
import com.spop.poverlay.util.IsRunningOnPeloton
import timber.log.Timber

class GrupettoApplication : Application() {
    lateinit var bleServer: BleServer
        private set
    private lateinit var configRepository: ConfigurationRepository

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val sensorInterface = createSensorInterface()
        bleServer = BleServer(this, bluetoothManager, sensorInterface)
    }

    /**
     * Update BLE local mode setting. Should be called when the configuration changes.
     */
    fun updateBleLocalMode(enabled: Boolean) {
        if (::bleServer.isInitialized) {
            bleServer.setLocalMode(enabled)
        }
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
            object : SensorInterface {
                override val cadence = kotlinx.coroutines.flow.MutableStateFlow(0f)
                override val power = kotlinx.coroutines.flow.MutableStateFlow(0f)
                override val resistance = kotlinx.coroutines.flow.MutableStateFlow(0f)
            }
        }
    }
}
