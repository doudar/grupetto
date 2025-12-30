package com.spop.poverlay

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.spop.poverlay.DataBase.DBHelper
import com.spop.poverlay.DataBase.GlobalVariables
import com.spop.poverlay.ui.theme.PTONOverlayTheme

class UserListActivity : ComponentActivity() {
    private val users = mutableStateListOf<DBHelper.UserData>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PTONOverlayTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    UserListScreen()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUsers()
    }

    private fun refreshUsers() {
        val dbHelper = DBHelper(this)
        val updatedList = getUsers(dbHelper)
        users.clear()
        users.addAll(updatedList)
    }

    @Composable
    fun UserListScreen() {
        val dbHelper = remember { DBHelper(this@UserListActivity) }

        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Select User",
                    style = MaterialTheme.typography.headlineMedium
                )

                Button(onClick = {
                    val newUserId = dbHelper.insertUser("New User", "", "")
                    val intent = Intent(this@UserListActivity, UserConfigurationActivity::class.java).apply {
                        putExtra("USER_ID", newUserId.toInt())
                    }
                    startActivity(intent)
                }) {
                    Text("Add User")
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))

            LazyColumn(
                modifier = Modifier.weight(1f)
            ) {
                items(users) { user ->
                    UserItem(
                        user = user,
                        onClick = {
                            val gv = GlobalVariables(this@UserListActivity)
                            gv.UserIDSet(user.id)
                            gv.HRDeviceAddressSet(user.bleId ?: "")
                            gv.HRDeviceNameSet(user.bleName ?: "")
                            finish()
                        },
                        onEditClick = {
                            val intent = Intent(this@UserListActivity, UserConfigurationActivity::class.java).apply {
                                putExtra("USER_ID", user.id)
                            }
                            startActivity(intent)
                        }
                    )
                    Divider()
                }
            }
        }
    }

    @Composable
    fun UserItem(user: DBHelper.UserData, onClick: () -> Unit, onEditClick: () -> Unit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = user.username ?: "Unknown", style = MaterialTheme.typography.titleLarge)
                if (!user.bleName.isNullOrEmpty()) {
                    Text(
                        text = "Sensor: ${user.bleName}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
            IconButton(onClick = onEditClick) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "Edit User",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }

    private fun getUsers(dbHelper: DBHelper): List<DBHelper.UserData> {
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery("SELECT * FROM User", null)
        val usersList = mutableListOf<DBHelper.UserData>()
        while (cursor.moveToNext()) {
            usersList.add(
                DBHelper.UserData(
                    id = cursor.getInt(cursor.getColumnIndexOrThrow("UserID")),
                    username = cursor.getString(cursor.getColumnIndexOrThrow("Username")),
                    bleId = cursor.getString(cursor.getColumnIndexOrThrow("BLEid")),
                    bleName = cursor.getString(cursor.getColumnIndexOrThrow("BLEName"))
                )
            )
        }
        cursor.close()
        return usersList
    }
}
