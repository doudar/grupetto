package com.spop.poverlay.sensor.discovery

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.AtomicFile
import com.spop.poverlay.BuildConfig
import com.spop.poverlay.util.readPelotonPlatform
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** App-owned on-device storage; no report is uploaded. External app storage also allows ADB retrieval. */
internal class BikeDiscoveryStore(private val context: Context) {
    private val preferences = context.getSharedPreferences("bike-service-discovery", Context.MODE_PRIVATE)
    val reportFile get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "service-discovery/latest.json")

    fun selected(): BikeServiceProfile? = try {
        preferences.getString("selected", null)?.let { encoded ->
            val json = JSONObject(encoded)
            require(json.getInt("schema") == 1)
            val protocol = BikeProtocol.valueOf(json.getString("protocol"))
            val action = json.optString("action").takeIf { it.isNotEmpty() }
            require(protocol != BikeProtocol.SensorData || action == protocol.action)
            BikeServiceProfile(BikeServiceEndpoint(json.getString("package"), json.getString("class"), action,
                json.getJSONArray("categories").let { values -> (0 until values.length()).map(values::getString) }), protocol)
        }
    } catch (_: Exception) { null }

    @android.annotation.SuppressLint("UseKtx") // The commit result must be checked before restarting.
    fun select(profile: BikeServiceProfile?): Boolean = preferences.edit().apply {
        if (profile == null) remove("selected") else putString("selected", endpointJson(profile.endpoint)
            .put("schema", 1).put("protocol", profile.protocol.name).toString())
    }.commit()

    fun saveReport(inventory: BikeServiceInventory, results: List<BikeProbeResult>, status: String) {
        val report = JSONObject().put("schema", 1).put("createdAtUtc", java.text.SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date()))
            .put("grupettoVersion", BuildConfig.VERSION_NAME).put("model", Build.MODEL).put("brand", Build.BRAND)
            .put("androidSdk", Build.VERSION.SDK_INT).put("platform", readPelotonPlatform(context) ?: JSONObject.NULL)
            .put("status", status).put("notes", JSONArray(inventory.notes))
            .put("permissions", JSONObject().apply {
                listOf("DUMP", "READ_LOGS", "PACKAGE_USAGE_STATS", "QUERY_ALL_PACKAGES").forEach {
                    put(it, context.packageManager.checkPermission("android.permission.$it", context.packageName) == PackageManager.PERMISSION_GRANTED)
                }
            })
            .put("services", JSONArray().apply { inventory.services.forEach { service ->
                put(JSONObject().put("package", service.packageName).put("class", service.className)
                    .put("version", service.version).put("process", service.process).put("uid", service.uid)
                    .put("enabled", service.enabled).put("exported", service.exported)
                    .put("permission", service.permission ?: JSONObject.NULL).put("permissionGranted", service.permissionGranted)
                    .put("system", service.system).put("running", service.running).put("pid", service.pid ?: JSONObject.NULL)
                    .put("clients", JSONArray(service.clients)).put("candidate", (service.relevant || service.running) && service.accessible)
                    .put("actions", JSONArray().apply { service.actions.forEach { action ->
                        put(JSONObject().put("name", action.name).put("categories", JSONArray(action.categories)))
                    } }))
            } })
            .put("probes", JSONArray().apply { results.forEach { result ->
                put(endpointJson(result.endpoint).put("descriptor", result.descriptor ?: JSONObject.NULL)
                    .put("protocol", result.protocol?.name ?: JSONObject.NULL).put("outcome", result.outcome)
                    .put("sample", result.sample?.let {
                        JSONObject().put("watts", it.watts).put("rpm", it.rpm).put("resistance", it.resistance)
                    } ?: JSONObject.NULL))
            } })
        check(reportFile.parentFile!!.let { it.isDirectory || it.mkdirs() }) { "Cannot create report directory" }
        val file = AtomicFile(reportFile)
        val stream = file.startWrite()
        try { stream.write(report.toString(2).toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
}

private fun endpointJson(endpoint: BikeServiceEndpoint) = JSONObject().put("package", endpoint.packageName)
    .put("class", endpoint.className).put("action", endpoint.action ?: "").put("categories", JSONArray(endpoint.categories))
