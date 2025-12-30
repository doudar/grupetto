package com.spop.poverlay


import android.app.Application
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.startActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.spop.poverlay.releases.Release
import com.spop.poverlay.releases.ReleaseChecker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import com.spop.poverlay.BLE.BleHeartRateManager
import androidx.lifecycle.lifecycleScope
import com.spop.poverlay.DataBase.DBHelper
import com.spop.poverlay.DataBase.GlobalVariables
import com.spop.poverlay.HistoryActivity.ActivityData
import com.spop.poverlay.overlay.OverlayService
import com.spop.poverlay.ui.theme.PTONOverlayTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ConfigurationViewModel(
    application: Application,
    private val configurationRepository: ConfigurationRepository,
    private val releaseChecker: ReleaseChecker
) : AndroidViewModel(application) {
    val finishActivity = MutableLiveData<Unit>()
    val requestOverlayPermission = MutableLiveData<Unit>()
    val requestRestart = MutableLiveData<Unit>()
    val showPermissionInfo = mutableStateOf(false)
    val infoPopup = MutableLiveData<String>()
    val openHistory = MutableLiveData<Unit>()
    val openUserList = MutableLiveData<Unit>()

    // Map of release names to if they're the currently installed one
    var latestRelease = mutableStateOf<Release?>(null)

    val showTimerWhenMinimized
        get() = configurationRepository.showTimerWhenMinimized


    val gv = GlobalVariables(getApplication())


    private var mutableaheartRateDeviceName = MutableStateFlow("None Selected")
    var heartRateDeviceName = mutableaheartRateDeviceName.asStateFlow()

    private var mutableCurrentUserName = MutableStateFlow("")
    var  currentUserName = mutableCurrentUserName.asStateFlow()

    private val bleHeartRateManager = BleHeartRateManager(getApplication())
    val heartRate = bleHeartRateManager.heartRate

    init {
        updatePermissionState()
        val hrDeviceAddress = gv.HRDeviceAddressGet()

        if (hrDeviceAddress != null) {
            bleHeartRateManager.connect(hrDeviceAddress)
        }
        else
        {
            bleHeartRateManager.disconnect()
        }
        /*
        viewModelScope.launch {
            configurationRepository.heartRateDeviceAddress.collect { address ->
                if (address != null) {
                    bleHeartRateManager.connect(address)
                } else {
                    bleHeartRateManager.disconnect()
                }
            }
        }
        */

    }

    override fun onCleared() {
        super.onCleared()
        bleHeartRateManager.disconnect()
    }

    private fun updatePermissionState() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            showPermissionInfo.value = !Settings.canDrawOverlays(getApplication())
        } else {
            showPermissionInfo.value = false
        }
    }

    fun onShowTimerWhenMinimizedClicked(isChecked: Boolean) {
        configurationRepository.setShowTimerWhenMinimized(isChecked)
    }





    fun onStartServiceClicked() {
        bleHeartRateManager.disconnect()
        val dbHelper = DBHelper(getApplication())

        val gv = GlobalVariables(getApplication())
        val userId = gv.UserIDGet()

        val user = dbHelper.getUser(userId)

        if (user != null) {
            Timber.i("Starting service for user: ${user.username}")
            ContextCompat.startForegroundService(
                getApplication(),
                Intent(getApplication(), OverlayService::class.java)
            )
            finishActivity.value = Unit
        } else {
            Timber.w("User ID $userId not found, opening UserListActivity")
            openUserList.value = Unit
        }
    }



    fun onSelectHeartRateDeviceClicked() {
        val intent = Intent(getApplication(), HeartRateDeviceListActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        getApplication<Application>().startActivity(intent)
    }

    fun onHistoryClicked() {
        openHistory.value = Unit
    }

    fun onUserListClicked() {
        bleHeartRateManager.disconnect()
        openUserList.value = Unit
    }

    fun onGrantPermissionClicked() {
        requestOverlayPermission.value = Unit
    }

    fun onRestartClicked() {
        requestRestart.value = Unit
    }

    fun onClickedRelease(release: Release) {
        val browserIntent = Intent(Intent.ACTION_VIEW, release.url)
        getApplication<Application>().startActivity(browserIntent)
    }

    fun onResume() {
        updatePermissionState()
        viewModelScope.launch(Dispatchers.IO) {
            releaseChecker.getLatestRelease()
                .onSuccess { release ->
                    latestRelease.value = release
                }
                .onFailure {
                    Timber.e(it, "failed to fetch release info")
                }
        }
        val dn = gv.HRDeviceNameGet()
        if (dn!= mutableaheartRateDeviceName.value) {
            mutableaheartRateDeviceName.value = dn
            if (gv.HRDeviceAddressGet() != null) {

                bleHeartRateManager.connect(gv.HRDeviceAddressGet() )
            }
            else
            {
                bleHeartRateManager.disconnect()
            }
        }
        if (gv.HRDeviceAddressGet() == null)bleHeartRateManager.disconnect()
        refreshActivities()


        mutableCurrentUserName.value = gv.CurrentUserNameGet()?: ""

    }

    fun onOverlayPermissionRequestCompleted(wasGranted: Boolean) {
        updatePermissionState()
        val prompt = if (wasGranted) {
            "Permission granted, click 'Start Overlay' to get started"
        } else {
            "Without this permission the app cannot function"
        }
        infoPopup.postValue(prompt)
    }

    public val activities = mutableStateListOf<ActivityData>()





    private fun refreshActivities() {
        val updatedList = getActivities()
        activities.clear()
        activities.addAll(updatedList)
    }

    private fun getActivities(): List<ActivityData> {
        val dbHelper = DBHelper(getApplication())
        val gv = GlobalVariables(getApplication())
        val userId = gv.UserIDGet()

        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery(
            "SELECT ActivityHeaderID, Title, StartTime, TrackTime, AvgHeartRate, AvgPower FROM ActivityHeader WHERE UserID = ? ORDER BY StartTime DESC",
            arrayOf(userId.toString())
        )
        val activitiesList = mutableListOf<ActivityData>()
        while (cursor.moveToNext()) {
            activitiesList.add(
                ActivityData(
                    id = cursor.getInt(0),
                    title = cursor.getString(1) ?: "Unknown",
                    startTime = cursor.getLong(2),
                    trackTime  = cursor.getInt(3),
                    avgHeartRate = if (cursor.isNull(4)) null else cursor.getInt(4),
                    avgPower = if (cursor.isNull(5)) null else cursor.getInt(5)
                )
            )
        }
        cursor.close()
        return activitiesList
    }

    public fun formatDate(timestamp: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    public  fun formatDuration(seconds: Int): String {
        val minutes = seconds / 60
        val remainingSeconds = seconds % 60
        return String.format("%02d:%02d", minutes, remainingSeconds)
    }

    data class ActivityData(
        val id: Int,
        val title: String,
        val startTime: Long,
        val trackTime: Int,
        val avgHeartRate: Int?,
        val avgPower: Int?
    )

    public fun activityClick(activityID:Int)
    {
        val intent = Intent(getApplication(), HistoryActivityDetail::class.java)
        intent.putExtra("ACTIVITY_HEADER_ID", activityID)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        getApplication<Application>().startActivity(intent)
    }

}
