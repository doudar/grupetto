package com.spop.poverlay.overlay

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.preference.PreferenceManager
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.spop.poverlay.BLE.BleFtmsServerManager
import com.spop.poverlay.BLE.BleHeartRateManager
import com.spop.poverlay.DataBase.DBHelper
import com.spop.poverlay.DataBase.GlobalVariables
import com.spop.poverlay.DataBase.TCX
import com.spop.poverlay.MainActivity

import com.spop.poverlay.sensor.DeadSensorDetector
import com.spop.poverlay.sensor.interfaces.SensorInterface
import com.spop.poverlay.util.smoothSensorValue
import com.spop.poverlay.util.tickerFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.milliseconds


private const val MphToKph = 1.60934

enum class OverlayState {
    Minimized,
    Main,
    FullScreen


}

enum class RecordingState {
    Recording,
    Stopped,

    Confirm

}

class OverlaySensorViewModel(
    application: Application,
    private val sensorInterface: SensorInterface,
    private val deadSensorDetector: DeadSensorDetector,
    private val onExit: () -> Unit = {}
) : AndroidViewModel(application) {

    companion object {
        // The sensor does not necessarily return new value this quickly
        val GraphUpdatePeriod = 200.milliseconds

        // Max number of points before data starts to shift
        const val GraphMaxDataPoints = 300
    }

    private val mutableOverlayState = MutableStateFlow(OverlayState.FullScreen)
    val overlayState = mutableOverlayState.asStateFlow()

    private val mutableIsMinimized = MutableStateFlow(false)
    val isMinimized = mutableIsMinimized.asStateFlow()

    private val mutableActivityDurationTime = MutableStateFlow("")
    val activityDurationTime = mutableActivityDurationTime.asStateFlow()

    private val mutableErrorMessage = MutableStateFlow<String?>(null)
    val errorMessage = mutableErrorMessage.asStateFlow()

    private val mutableRecordingState = MutableStateFlow(RecordingState.Stopped)
    val recordingState = mutableRecordingState.asStateFlow()

    private val mutableShowStopConfirmDialog = MutableStateFlow(false)
    val showStopConfirmDialog = mutableShowStopConfirmDialog.asStateFlow()

    private var timerJob: Job? = null
    private var timerJobGraph: Job? = null


    private val mutableElapsedTimeSeconds = MutableStateFlow(0L)
    val elapsedTimeSeconds = mutableElapsedTimeSeconds.asStateFlow()


    lateinit var activityStartTime: Instant

    private val mutableactivityDurationSeconds = MutableStateFlow(0.0)
    val activityDurationSeconds = mutableactivityDurationSeconds.asStateFlow()

    var activityID: Int = -1


    private val mutableactivityRevolutions = MutableStateFlow(0L)
    val activityRevolutions = mutableactivityRevolutions.asStateFlow()

    private val mutableactivityMaxHeartRate = MutableStateFlow(0)
    val activityMaxHeartRate = mutableactivityMaxHeartRate.asStateFlow()

    private val mutableactivityCalories = MutableStateFlow(0)
    val activityCalories = mutableactivityCalories.asStateFlow()


    private val mutableactivityMaxSpeed = MutableStateFlow(0.0)
    val activityMaxSpeed = mutableactivityMaxSpeed.asStateFlow()

    private val mutableactivityMaxCadence = MutableStateFlow(0L)
    val activityMaxCadence = mutableactivityMaxCadence.asStateFlow()


    private val mutableactivityMaxPower = MutableStateFlow(0L)
    val activityMaxPower = mutableactivityMaxPower.asStateFlow()


    private val mutableactivityWork = MutableStateFlow(0L)
    val activityWork = mutableactivityWork.asStateFlow()

    private val mutableactivityHeartBeats = MutableStateFlow(0L)
    val activityHeartBeats = mutableactivityHeartBeats.asStateFlow()

    private val mutableactivityAvgHeartRate = MutableStateFlow(0)
    val activityAvgHeartRate = mutableactivityAvgHeartRate.asStateFlow()

    private val mutableactivityAvgPower = MutableStateFlow(0)
    val activityAvgPower = mutableactivityAvgPower.asStateFlow()

    private val mutableactivityAvgCadence = MutableStateFlow(0L)
    val activityAvgCadence = mutableactivityAvgCadence.asStateFlow()

    private val mutableactivityAvgSpeed = MutableStateFlow(0.0)
    val activityAvgSpeed = mutableactivityAvgSpeed.asStateFlow()


    private val mutableactivityDistance = MutableStateFlow(0.00)
    val activityDistance = mutableactivityDistance.asStateFlow()

    private val mutableBleResistance = MutableStateFlow(0)
    val bleResistance = mutableBleResistance.asStateFlow()

    private val mutableHeartRateBatteryLevel = MutableStateFlow(-1)
    val heartRateBatteryLevel = mutableHeartRateBatteryLevel.asStateFlow()


    //private val configurationRepository = ConfigurationRepository(application, this)
    private val bleHeartRateManager = BleHeartRateManager(application)
    private val bleFtmsServerManager = BleFtmsServerManager(application)



    private val antServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            //antChannelServiceComm = service as? ChannelService.ChannelServiceComm
          //  antChannelServiceComm?.startBikeTransmitter()
        }

        override fun onServiceDisconnected(name: ComponentName?) {

        }
    }

    var lastHeartRate: Int = 0
    val heartRate = bleHeartRateManager.heartRate.map {
        if (it > 0) {
            lastHeartRate = it.toInt()
            it.toString()


        } else "-"
    }

    fun onDismissErrorPressed() {
        mutableErrorMessage.tryEmit(null)
    }

    fun onOverlayPressed() {
        mutableOverlayState.value = when (mutableOverlayState.value) {
            OverlayState.FullScreen -> OverlayState.Main
            OverlayState.Main -> OverlayState.Minimized
            OverlayState.Minimized -> OverlayState.FullScreen

        }
        if (overlayState.value == OverlayState.Minimized) {
            mutableIsMinimized.value = true
        } else {
            mutableIsMinimized.value = false
        }
    }

    fun onOverlayDoubleTap() {
        getApplication<Application>().apply {
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
        }
    }

    private fun onDeadSensor() {
        mutableErrorMessage
            .tryEmit(
                "The sensors seem to have fallen asleep." +
                        " You may need to restart your Peloton by removing the" +
                        " power adapter momentarily to restore them."
            )
    }

    fun onExitToHomeScreen() {


        //onOverlayDoubleTap()
        onExit()
    }

    private var useMph = MutableStateFlow(true)

    val powerValue = sensorInterface.power
        .map { "%.0f".format(it) }
    val rpmValue = sensorInterface.cadence
        .map { "%.0f".format(it) }

    val resistanceValue = sensorInterface.resistance
        .map { "%.0f".format(it) }

    val speedValue = combine(
        sensorInterface.speed, useMph
    ) { speed, isMph ->
        val value = if (isMph) {
            speed
        } else {
            speed * MphToKph
        }
        "%.1f".format(value)
    }
    val speedLabel = useMph.map {
        if (it) {
            "mph"
        } else {
            "kph"
        }
    }

    fun onClickedSpeed() {
        viewModelScope.launch {
            useMph.emit(!useMph.value)
        }
    }

    var lastPower: Int = 0
    suspend fun observePower(power: Flow<Float>) {
        power.collect { value ->
            val powerInt = value.toInt()
            lastPower = powerInt
           // antChannelServiceComm?.setPower(powerInt)

        }
    }

    var lastCadence: Int = 0
    suspend fun observeCadence(cadence: Flow<Float>) {
        cadence.collect { value ->
            val cadenceInt = value.toInt()
            lastCadence = cadenceInt
            //antChannelServiceComm?.setCadence(cadenceInt)

        }
    }

    var lastSpeed: Int = 0
    suspend fun observeSpeed(speed: Flow<Float>) {
        speed.collect { value ->
            val speedInt = value.toInt()
            lastSpeed = speedInt
            //antChannelServiceComm?.setSpeed(value.toDouble())

        }
    }

    fun onRecordClicked() {
        if (mutableRecordingState.value != RecordingState.Recording) {
            mutableRecordingState.value = RecordingState.Recording


            var gv: GlobalVariables = GlobalVariables(getApplication())
            powerGraph.clear()
            powerGraphlarge.clear()
            cadenceGraph.clear()
            heartRateGraph.clear()

            for (i in 0..GraphMaxDataPoints + 1) {
                powerGraph.add(50f)
            }
            for (i in 0..GraphMaxDataPoints * 6 + 1) {
                powerGraphlarge.add(80f)
                cadenceGraph.add(80f)
                heartRateGraph.add(80f)
            }



            activityStartTime = Clock.System.now()
            mutableactivityDurationSeconds.value = 0.00
            mutableActivityDurationTime.value = ""
            mutableactivityWork.value = 0
            mutableactivityRevolutions.value = 0
            mutableactivityHeartBeats.value = 0

            var dbHelper: DBHelper = DBHelper(getApplication())
            activityID = dbHelper.insertActivityHeader(
                gv.UserIDGet().toInt(),
                "New Activity",
                0.0f,
                0,
                "",
                System.currentTimeMillis(),
                0.0f,
                0.0f,
                "",
                0,
                null,
                null,
                null,
                null,
                null,
                null,
                null
            )
                .toInt()





            timerJob?.cancel()
            timerJob = viewModelScope.launch {
                while (true) {
                    delay(1000)
                    mutableElapsedTimeSeconds.value++
                    var currentInstant: Instant = Clock.System.now()
                    val unixTimeMillis = System.currentTimeMillis()

                    if (lastPower > 0) {

                        var elapsedTimeMS =
                            (currentInstant - activityStartTime).inWholeMilliseconds

                        mutableactivityWork.value += lastPower * elapsedTimeMS
                        mutableactivityRevolutions.value += lastCadence * elapsedTimeMS
                        mutableactivityHeartBeats.value += lastHeartRate * elapsedTimeMS
                        mutableactivityDurationSeconds.value += elapsedTimeMS / 1000.0
                        val seconds = mutableactivityDurationSeconds.value.toInt()
                        val hours = seconds / 3600
                        val minutes = (seconds / 60) % 60


                        mutableActivityDurationTime.value =
                            String.format("%02d:%02d:%02d", hours, minutes, seconds % 60)


                        mutableactivityDistance.value +=
                            (lastSpeed.toDouble() * (elapsedTimeMS / 3600000.0))


                    }
                    if (mutableactivityDurationSeconds.value > 0) {
                        mutableactivityAvgSpeed.value =
                            (mutableactivityDistance.value.toDouble() / (mutableactivityDurationSeconds.value.toDouble() / (3600)))
                        mutableactivityAvgHeartRate.value =
                            (mutableactivityHeartBeats.value / (mutableactivityDurationSeconds.value * 1000)).toInt()
                        mutableactivityAvgPower.value =
                            (mutableactivityWork.value / (mutableactivityDurationSeconds.value * 1000)).toInt()
                        mutableactivityAvgCadence.value =
                            (mutableactivityRevolutions.value / (mutableactivityDurationSeconds.value * 1000)).toLong()
                        mutableactivityCalories.value =
                            (100 * mutableactivityDurationSeconds.value / 3600).toInt() + (mutableactivityWork.value / 1000000).toInt()

                    }

                    if (lastSpeed > mutableactivityMaxSpeed.value) {
                        mutableactivityMaxSpeed.value = lastSpeed.toDouble()
                    }
                    if (lastPower > mutableactivityMaxPower.value) mutableactivityMaxPower.value =
                        lastPower.toLong()
                    if (lastCadence > mutableactivityMaxCadence.value) mutableactivityMaxCadence.value =
                        lastCadence.toLong()
                    if (lastHeartRate > mutableactivityMaxHeartRate.value) mutableactivityMaxHeartRate.value =
                        lastHeartRate

                    if (lastPower > 0) {


                        var elapsedTimeMS =
                            (currentInstant - activityStartTime).inWholeMilliseconds
                        dbHelper.insertActivityLine(
                            activityID,
                            unixTimeMillis,
                            lastSpeed.toFloat(),
                            mutableactivityDistance.value.toFloat(),
                            lastCadence.toInt(),
                            lastHeartRate,
                            elapsedTimeMS.toInt(),
                            lastPower
                        )

                        dbHelper.updateActivityHeader(
                            activityID,
                            distance = activityDistance.value.toFloat(),
                            trackTime = mutableactivityDurationSeconds.value.toInt(),
                            maxSpeed = mutableactivityMaxSpeed.value.toFloat(),

                            maxHeartRate = mutableactivityMaxHeartRate.value.toInt(),
                            maxPower = mutableactivityMaxPower.value.toInt(),
                            averageSpeed = mutableactivityAvgSpeed.value.toFloat(),
                            time = 0,
                            avgHeartRate = mutableactivityAvgHeartRate.value.toInt(),
                            cadanceRevolutions = activityRevolutions.value.toInt(),
                            avgSpinningCadance = mutableactivityAvgCadence.value.toInt(),
                            avgPower = mutableactivityAvgPower.value.toInt(),
                            maxCadence = mutableactivityMaxCadence.value.toInt(),
                            calories = mutableactivityCalories.value.toInt()
                        )


                        //dbHelper.updateActivityHeader(activityID, distance = activityDistance.value.toFloat(),mutableactivityDurationSeconds.value.toInt()
                        //    ,cadence = activityAvgCadence.value.toInt(),heartRate = activityAvgHeartRate.value.toInt())

                    }

                    activityStartTime = currentInstant

                }

            }
        }
    }


    fun onStopClicked() {
        mutableRecordingState.value = RecordingState.Confirm

    }

    fun onStopConfirm() {
        val tcx = TCX()


        val filepath = tcx.exportToTcx(activityID, getApplication())


        val text = "A TCX file was exported to $filepath"
        Toast.makeText(getApplication(), text, Toast.LENGTH_LONG).show()

        activityID = -1
        mutableRecordingState.value = RecordingState.Stopped
        timerJob?.cancel()
        mutableElapsedTimeSeconds.value = 0
        mutableShowStopConfirmDialog.value = false
    }

    fun onStopCancel() {
        mutableRecordingState.value = RecordingState.Recording
    }

    val powerGraph = mutableStateListOf<Float>()
    val powerGraphlarge = mutableStateListOf<Float>()
    val cadenceGraph = mutableStateListOf<Float>()
    val heartRateGraph = mutableStateListOf<Float>()

    private fun setupPowerGraphData() {
        viewModelScope.launch(Dispatchers.IO) {
            //Sensor value is read every tick and added to graph
            combine(
                sensorInterface.power.smoothSensorValue(),
                tickerFlow(GraphUpdatePeriod)
            ) { sensorValue, _ -> sensorValue }.collect { value ->
                withContext(Dispatchers.Main) {
                }
                lastPower = value.toInt()

            }
        }
    }


    // Happens last to ensure initialization order is correct
    init {
        setupPowerGraphData()


        timerJobGraph?.cancel()
        timerJobGraph = viewModelScope.launch {
            while (true) {
                delay(200)

                if (lastCadence > 40)
                    cadenceGraph.add(lastCadence.toFloat())
                else
                    cadenceGraph.add(40f)
                if (cadenceGraph.size > GraphMaxDataPoints * 6) {
                    cadenceGraph.removeFirst()
                }


                if (lastPower > mutableactivityAvgPower.value / 2) {
                    powerGraph.add(lastPower.toFloat())
                    powerGraphlarge.add(lastPower.toFloat())

                } else {
                    powerGraph.add((mutableactivityAvgPower.value / 2).toFloat())
                    powerGraphlarge.add((mutableactivityAvgPower.value / 2).toFloat())
                }
                if (powerGraph.size > GraphMaxDataPoints) {
                    powerGraph.removeFirst()
                }
                if (powerGraphlarge.size > GraphMaxDataPoints * 6) {
                    powerGraphlarge.removeFirst()
                }

                heartRateGraph.add(lastHeartRate.toFloat())
                if (heartRateGraph.size > GraphMaxDataPoints * 6) {
                    heartRateGraph.removeFirst()
                }
            }


        }
        viewModelScope.launch(Dispatchers.IO) {
            deadSensorDetector.deadSensorDetected.collect {
                onDeadSensor()
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            errorMessage.collect {
                // Leave minimized state if we're showing an error message
                if (it != null && mutableOverlayState.value == OverlayState.Minimized) {
                    mutableOverlayState.value = OverlayState.Main
                }
            }
        }

        viewModelScope.launch {



            val globalVariables = GlobalVariables(getApplication())
            val hrd = globalVariables.HRDeviceAddressGet()

            if (hrd != "1") {
                bleHeartRateManager.connect(hrd)
            } else {
                bleHeartRateManager.disconnect()
            }

        }

        viewModelScope.launch {
            observePower(sensorInterface.power)

        }

        viewModelScope.launch {

            observeCadence(sensorInterface.cadence)

        }
        viewModelScope.launch {

            observeSpeed(sensorInterface.speed)
        }

        // Setup FTMS Server
        bleFtmsServerManager.onResistanceChanged = { resistance ->
            mutableBleResistance.tryEmit(resistance)
            // Optionally update the bike's resistance directly
           // sensorInterface.setResistance(resistance.toFloat(), getApplication())
        }
        
        viewModelScope.launch {
            bleFtmsServerManager.startAdvertising()
            launch { bleFtmsServerManager.observePower(sensorInterface.power) }
            launch { bleFtmsServerManager.observeCadence(sensorInterface.cadence) }
            launch { bleFtmsServerManager.observeSpeed(sensorInterface.speed) }
        }





    }

    override fun onCleared() {
        super.onCleared()
        bleHeartRateManager.disconnect()
        bleFtmsServerManager.stopAdvertising()
        getApplication<Application>().unbindService(antServiceConnection)
    }

var res:Int = 57
    fun onIncreaseResistance() {
        // Increments resistance by 1 and sends to the bike interface
        //sensorInterface.setResistance(lastResistance + 1f)
        res +=1
        sensorInterface.setResistance(res.toFloat(), getApplication())
    }

    fun onDecreaseResistance() {
        // Decrements resistance by 1 (clamped to 0) and sends to the bike interface
        //sensorInterface.setResistance((lastResistance - 1f).coerceAtLeast(0f))
        sensorInterface.setResistance(99f, getApplication())
    }
}
