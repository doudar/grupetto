package com.spop.poverlay.sensor.v1new

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/** Protocols we understand, independent of the package implementing them. */
internal enum class V1BikeService(val action: String, val descriptor: String) {
    Affernet(SERVICE_ACTION, SERVICE_ACTION),
    SensorData("android.intent.action.peloton.SensorData", "android.os.IMessenger");

    fun discoveryIntent(): Intent = Intent(action).apply {
        if (this@V1BikeService == SensorData) addCategory("com.peloton.sensor.category.BIKE")
    }
}

internal data class V1ServiceCandidate(
    val protocol: V1BikeService,
    val packageName: String,
    val className: String,
    val processName: String,
    val uid: Int,
    val permission: String?,
    val accessible: Boolean,
    val systemApp: Boolean,
) {
    val component get() = "$packageName/$className"
    fun createIntent(): Intent = protocol.discoveryIntent().setComponent(ComponentName(packageName, className))
}

internal fun discoverV1BikeServices(context: Context): List<V1ServiceCandidate> {
    val pm = context.packageManager
    return V1BikeService.entries.flatMap { protocol ->
        pm.queryIntentServices(protocol.discoveryIntent(), 0).mapNotNull { resolved ->
            val service = resolved.serviceInfo ?: return@mapNotNull null
            val app = service.applicationInfo ?: return@mapNotNull null
            if (!service.enabled || !app.enabled) return@mapNotNull null
            V1ServiceCandidate(protocol, service.packageName, service.name,
                service.processName ?: service.packageName, app.uid, service.permission,
                service.exported && (service.permission == null ||
                    pm.checkPermission(service.permission, context.packageName) == PackageManager.PERMISSION_GRANTED),
                app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0)
        }
    }.distinctBy { it.protocol to it.component }
}

/** Keep Affernet priority even if its permission is denied: it may still own the UART. */
internal fun selectV1BikeServices(candidates: List<V1ServiceCandidate>): List<V1ServiceCandidate> {
    val protocol = if (candidates.any { it.protocol == V1BikeService.Affernet })
        V1BikeService.Affernet else V1BikeService.SensorData
    return candidates.filter { it.protocol == protocol && it.accessible }
        .sortedWith(compareByDescending<V1ServiceCandidate> { it.systemApp }.thenBy { it.component })
}

internal fun validateV1ServiceDescriptor(protocol: V1BikeService, descriptor: String?) {
    check(descriptor == protocol.descriptor) {
        "Unsupported Binder interface '$descriptor'; expected '${protocol.descriptor}'"
    }
}
