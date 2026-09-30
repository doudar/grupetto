package com.spop.poverlay.ble

import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.spop.poverlay.control.BikeControl
import com.spop.poverlay.control.BikeSample
import com.spop.poverlay.control.ControlMode
import com.spop.poverlay.sensor.interfaces.DeviceType
import com.spop.poverlay.sensor.interfaces.SensorInterface
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Test

/** Runs against Android's real GATT classes, with a recording motor sink and no radio server. */
class FitnessMachineControlIntegrationTest {
    private fun fixture(type: DeviceType, controllable: Boolean, block: (FitnessMachineService, BikeControl) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val control = BikeControl(controllable, { 1000L }) { true }
        control.acceptSample(BikeSample(100f, 80f, 40, 1000))
        val sensor = object : SensorInterface {
            override val deviceType = type
            override val power = flowOf(100f)
            override val cadence = flowOf(80f)
            override val resistance = flowOf(40f)
        }
        val server = BleServer(context, context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager, sensor)
        try { block(FitnessMachineService(server, type, control), control) }
        finally { server.coroutineContext.cancel() }
    }

    @Test fun controllableBikeAdvertisesExactlyImplementedTargetsAndRanges() = fixture(DeviceType.Bike, true) { ftms, _ ->
        val features = ftms.service.getCharacteristic(FitnessMachineConstants.FeatureUUID).value
        assertArrayEquals(byteArrayOf(12, 32, 0, 0), features.copyOfRange(4, 8))
        assertArrayEquals(byteArrayOf(25, 0, 232.toByte(), 3, 1, 0),
            ftms.service.getCharacteristic(FitnessMachineConstants.SupportedPowerRangeUUID).value)
        assertArrayEquals(byteArrayOf(0, 0, 232.toByte(), 3, 10, 0),
            ftms.service.getCharacteristic(FitnessMachineConstants.SupportedResistanceRangeUUID).value)
        assertNotNull(ftms.service.getCharacteristic(FitnessMachineConstants.MachineStatusUUID))
    }
    @Test fun originalBikeAndTreadHaveNoMotorCapabilities() {
        listOf(DeviceType.Bike, DeviceType.Tread).forEach { type ->
            fixture(type, false) { ftms, control ->
                val features = ftms.service.getCharacteristic(FitnessMachineConstants.FeatureUUID).value
                assertArrayEquals(ByteArray(4), features.copyOfRange(4, 8))
                assertNull(ftms.service.getCharacteristic(FitnessMachineConstants.SupportedPowerRangeUUID))
                assertNull(ftms.service.getCharacteristic(FitnessMachineConstants.MachineStatusUUID))
                assertArrayEquals(byteArrayOf(0x80.toByte(), 5, 2),
                    ftms.handleControl("test", byteArrayOf(5, 150.toByte(), 0)))
                assertEquals(ControlMode.Manual, control.state.value.mode)
                assertEquals(type == DeviceType.Tread,
                    ftms.service.getCharacteristic(FitnessMachineConstants.TreadmillDataUUID) != null)
            }
        }
    }
    @Test fun bothTransportsUseSameOwnershipAndResponseEncoding() = fixture(DeviceType.Bike, true) { ftms, control ->
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0, 1), ftms.handleControl("ble:a", byteArrayOf(0)))
        assertArrayEquals(byteArrayOf(0x80.toByte(), 5, 5),
            ftms.handleControl("dircon:b", byteArrayOf(5, 200.toByte(), 0)))
        assertArrayEquals(byteArrayOf(0x80.toByte(), 5, 1),
            ftms.handleControl("ble:a", byteArrayOf(5, 200.toByte(), 0)))
        ftms.onSensorDataUpdated(80f, 150f, 20f, 40f, 0f)
        assertEquals(FitnessMachineConstants.TrainingStatus.WattControl.toByte(),
            ftms.service.getCharacteristic(FitnessMachineConstants.TrainingStatusUUID).value[1])
        val data = ftms.service.getCharacteristic(FitnessMachineConstants.IndoorBikeDataUUID).value
        assertEquals(40, data[6].toInt()) // Existing telemetry resistance scale is preserved.
        control.disconnect("ble:a")
        assertEquals(1, ftms.handleControl("dircon:b", byteArrayOf(0))[2].toInt())
    }
}
