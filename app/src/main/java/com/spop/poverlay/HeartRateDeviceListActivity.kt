package com.spop.poverlay

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper

import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.spop.poverlay.ui.theme.PTONOverlayTheme
import androidx.compose.foundation.lazy.items
import com.spop.poverlay.DataBase.GlobalVariables


@SuppressLint("MissingPermission")
class HeartRateDeviceListActivity : ComponentActivity() {

    private var bluetoothAdapter: BluetoothAdapter? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isScanning = false
    private val SCAN_PERIOD: Long = 10000

    private val devices = mutableStateListOf<BluetoothDevice>()



    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val granted = permissions.entries.all { it.value }

            if (granted) {
                startBleScan()
            } else {
                startBleScan()
                //Toast.makeText(this, "Permissions required for BLE", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        setContent {
            PTONOverlayTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    DeviceListScreen(
                        devices = devices,
                        onDeviceClick = { device ->
                             saveDeviceAndFinish(device)
                        },
                        onScanClick = {
                            checkPermissionsAndScan()
                        },
                        isScanning = isScanning
                    )
                }
            }
        }
        
        checkPermissionsAndScan()
    }

    private fun checkPermissionsAndScan() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) != PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            )
        } else {
            startBleScan()
        }
    }

    private fun startBleScan() {
        if (isScanning) return

        devices.clear()
        
        // Scan for Heart Rate service (0x180D)
        val scanFilter = ScanFilter.Builder()
             //.setServiceUuid(android.os.ParcelUuid.fromString("0000180D-0000-1000-8000-00805F9B34FB"))
            .build()
        
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        isScanning = true
        bluetoothAdapter?.bluetoothLeScanner?.startScan(
            null, //listOf(scanFilter),
            scanSettings,
            scanCallback
        )

        handler.postDelayed({
            stopBleScan()
        }, SCAN_PERIOD)
    }

    private fun stopBleScan() {
        if (!isScanning) return
        isScanning = false
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }
    
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
             val device = result.device
             if (!devices.any { it.address == device.address } && device.name != null) {
                 devices.add(device)
             }
        }
    }

    private fun saveDeviceAndFinish(device: BluetoothDevice) {
        stopBleScan()

val gv: GlobalVariables = GlobalVariables(this);
gv.HRDeviceAddressSet(device.address)
gv.HRDeviceNameSet(device.name)




        finish()
    }
}

@Composable
fun DeviceListScreen(
    devices: List<BluetoothDevice>,
    onDeviceClick: (BluetoothDevice) -> Unit,
    onScanClick: () -> Unit,
    isScanning: Boolean
) {
    Column(modifier = Modifier
        .fillMaxSize()
        .padding(16.dp)) {
        Button(
            onClick = onScanClick,
            enabled = !isScanning,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isScanning) "Scanning..." else "Scan for Heart Rate Monitors")
        }
        
        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn {
            // The "items" function requires the specific import added above
            items(devices) { device ->
                DeviceListItem(device, onDeviceClick)
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun DeviceListItem(device: BluetoothDevice, onClick: (BluetoothDevice) -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onClick(device) },
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = device.name ?: "Unknown Device", style = MaterialTheme.typography.titleMedium)
            Text(text = device.address, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
