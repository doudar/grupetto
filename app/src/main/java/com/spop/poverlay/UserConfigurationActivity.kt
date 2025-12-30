package com.spop.poverlay

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.spop.poverlay.DataBase.DBHelper
import com.spop.poverlay.ui.theme.PTONOverlayTheme

class UserConfigurationActivity : ComponentActivity() {

    private var bluetoothAdapter: BluetoothAdapter? = null
    private val handler = Handler(Looper.getMainLooper())
    private var isScanning by mutableStateOf(false)
    private val SCAN_PERIOD: Long = 10000
    private val devices = mutableStateListOf<BluetoothDevice>()

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.entries.all { it.value }) {
                startBleScan()
            } else {
                Toast.makeText(this, "Permissions required for BLE scanning", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        val userId = intent.getIntExtra("USER_ID", -1)

        setContent {
            PTONOverlayTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (userId != -1) {
                        UserConfigurationScreen(userId)
                    } else {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                            Text("Error: User ID not found")
                        }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @SuppressLint("MissingPermission")
    @Composable
    fun UserConfigurationScreen(userId: Int) {
        val dbHelper = remember { DBHelper(this) }
        var username by remember { mutableStateOf("") }
        var bleId by remember { mutableStateOf("") }
        var bleName by remember { mutableStateOf("") }

        LaunchedEffect(userId) {
            dbHelper.getUser(userId)?.let {
                username = it.username ?: ""
                bleId = it.bleId ?: ""
                bleName = it.bleName ?: ""
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Text(text = "User Configuration", style = MaterialTheme.typography.headlineMedium)
                Button(
                    onClick = {
                        dbHelper.deleteUser(userId)
                        Toast.makeText(this@UserConfigurationActivity, "User Deleted", Toast.LENGTH_SHORT).show()
                        finish()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete User")
                }
                Button(
                    onClick = { close() },
                    shape = RectangleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                    modifier = Modifier.size(60.dp),
                    contentPadding = PaddingValues(0.dp),
                    content = {
                        // Specify the icon using the icon parameter
                        Image(
                            modifier = Modifier
                                .background(Color.White)
                                .requiredHeight(60.dp)
                                .requiredWidth(60.dp)
                                .align(Alignment.CenterVertically)
                                .padding(vertical = 4.dp),
                            painter = painterResource(id = R.drawable.exit),
                            contentDescription = null,
                        )

                    }
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("User Name") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(text = "Heart Rate Sensor", style = MaterialTheme.typography.titleMedium)
            Text(text = "Current: ${if (bleName.isEmpty()) "None" else bleName}", style = MaterialTheme.typography.bodyMedium)
            Text(text = "ID: $bleId", style = MaterialTheme.typography.bodySmall)

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { checkPermissionsAndScan() },
                enabled = !isScanning,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isScanning) "Scanning..." else "Scan for Sensors")
            }

            if (isScanning) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(devices) { device ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable {
                                bleId = device.address
                                bleName = device.name ?: "Unknown"
                                stopBleScan()
                            }
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(text = device.name ?: "Unknown Device", style = MaterialTheme.typography.titleMedium)
                            Text(text = device.address, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = {
                    dbHelper.updateUser(userId, username, bleId, bleName)
                    Toast.makeText(this@UserConfigurationActivity, "Saved", Toast.LENGTH_SHORT).show()
                    finish()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save and Exit")
            }
        }
    }

    private fun checkPermissionsAndScan() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        val missingPermissions = permissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        } else {
            startBleScan()
        }
    }

    private fun close()
    {
        finish()
    }

    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        if (isScanning) return
        devices.clear()
        isScanning = true
        bluetoothAdapter?.bluetoothLeScanner?.startScan(null, ScanSettings.Builder().build(), scanCallback)
        handler.postDelayed({ stopBleScan() }, SCAN_PERIOD)
    }

    @SuppressLint("MissingPermission")
    private fun stopBleScan() {
        if (!isScanning) return
        isScanning = false
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            if (!devices.any { it.address == device.address } && device.name != null) {
                devices.add(device)
            }
        }
    }
}
