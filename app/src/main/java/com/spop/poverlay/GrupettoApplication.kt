package com.spop.poverlay

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import com.spop.poverlay.ble.BleServer
import com.spop.poverlay.sensor.SensorSelection
import com.spop.poverlay.sensor.interfaces.DummySensorInterface
import com.spop.poverlay.sensor.interfaces.PelotonBikePlusSensorInterface
import com.spop.poverlay.sensor.interfaces.PelotonBikeSensorInterfaceV1New
import com.spop.poverlay.sensor.interfaces.PelotonTreadSensorInterface
import com.spop.poverlay.sensor.interfaces.SensorInterface
import com.spop.poverlay.sensor.selectSensorForCurrentDevice
import com.spop.poverlay.util.readPelotonPlatform
import timber.log.Timber

class GrupettoApplication : Application() {
    lateinit var bleServer: BleServer
        private set

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val sensorInterface = createSensorInterface()
        bleServer = BleServer(this, bluetoothManager, sensorInterface)
    }

    private fun createSensorInterface(): SensorInterface {
        // Detection is synchronous and reads Settings.Global["peloton_platform"], the
        // value affernetservice writes from the mainboard USB VID/PID ("prism" = Tread,
        // "titan" = Bike+, "caesar" = Row). The tablet model string is NOT usable: Bike+,
        // Tread and Row all ship the Topaz tablet and report PLTN-TTR01. deviceType — and
        // thus the FTMS characteristic BleServer builds at start() — is therefore correct
        // and race-free from the first launch, with zero delay in Application.onCreate.
        // A bind-probe was also unreliable: AffernetService returns a non-null
        // ITreadInterface binder on a bike too.
        val selection = selectSensorForCurrentDevice(this)
        // Logged so device routing can be confirmed on hardware without starting the
        // overlay, and so a bug report from an unknown machine identifies itself.
        Timber.i(
            "Device detection: model=%s platform=%s -> %s",
            Build.MODEL, readPelotonPlatform(this), selection
        )
        return when (selection) {
            SensorSelection.Tread -> PelotonTreadSensorInterface(this)
            SensorSelection.BikePlus -> PelotonBikePlusSensorInterface(this)
            SensorSelection.BikeV1 -> PelotonBikeSensorInterfaceV1New(this)
            SensorSelection.Dummy -> DummySensorInterface()
        }
    }
}
