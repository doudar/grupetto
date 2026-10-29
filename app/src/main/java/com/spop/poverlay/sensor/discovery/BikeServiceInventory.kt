package com.spop.poverlay.sensor.discovery

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.spop.poverlay.sensor.readDiagnosticCommand
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.xmlpull.v1.XmlPullParser

internal data class RunningBikeService(val component: String, val pid: String?, val actions: List<String>, val clients: List<String>)
internal data class BikeServiceInventory(val services: List<InstalledBikeService>, val notes: List<String>)

internal fun fullServiceName(pkg: String, name: String) = when {
    name.startsWith(".") -> pkg + name
    '.' !in name -> "$pkg.$name"
    else -> name
}

/** Read only identities/actions from dumpsys; Intent extras and arbitrary log messages are excluded. */
internal fun parseRunningBikeServices(dump: String): List<RunningBikeService> {
    val header = Regex("(?m)^\\s*\\* ServiceRecord\\{[^}]* u\\d+ ([^ }]+)")
    val matches = header.findAll(dump).toList()
    return matches.mapIndexedNotNull { i, match ->
        val short = match.groupValues[1].split('/', limit = 2)
        if (short.size != 2) return@mapIndexedNotNull null
        val block = dump.substring(match.range.last + 1, matches.getOrNull(i + 1)?.range?.first ?: dump.length)
        RunningBikeService("${short[0]}/${fullServiceName(short[0], short[1])}",
            Regex("app=ProcessRecord\\{[^ ]+ (\\d+):").find(block)?.groupValues?.get(1),
            Regex("act=([^ }]+)").findAll(block).map { it.groupValues[1] }.distinct().take(30).toList(),
            Regex("(?:client=|Client AppBindRecord\\{\\S+\\s+)ProcessRecord\\{[^ ]+ \\d+:([^/ }]+)").findAll(block)
                .map { it.groupValues[1] }.distinct().take(30).toList())
    }
}

/** Public resource APIs inspect the installed manifest without loading the other app's code. */
internal fun readServiceActions(parser: XmlPullParser, pkg: String): Map<String, List<ServiceAction>> {
    val result = linkedMapOf<String, MutableList<ServiceAction>>()
    var service: String? = null
    var inFilter = false
    val actions = mutableListOf<String>()
    val categories = mutableListOf<String>()
    val ns = "http://schemas.android.com/apk/res/android"
    while (parser.eventType != XmlPullParser.END_DOCUMENT) {
        when (parser.eventType) {
            XmlPullParser.START_TAG -> when (parser.name) {
                "service" -> service = parser.getAttributeValue(ns, "name")?.let { fullServiceName(pkg, it) }
                "intent-filter" -> if (service != null) { inFilter = true; actions.clear(); categories.clear() }
                "action" -> if (inFilter) parser.getAttributeValue(ns, "name")?.let(actions::add)
                "category" -> if (inFilter) parser.getAttributeValue(ns, "name")?.let(categories::add)
            }
            XmlPullParser.END_TAG -> when (parser.name) {
                "intent-filter" -> {
                    service?.let { name -> result.getOrPut(name) { mutableListOf() }
                        .addAll(actions.map { ServiceAction(it, categories.toList()) }) }
                    inFilter = false; actions.clear(); categories.clear()
                }
                "service" -> service = null
            }
        }
        parser.next()
    }
    return result
}

internal suspend fun collectBikeServiceInventory(context: Context): BikeServiceInventory {
    val pm = context.packageManager
    val notes = mutableListOf<String>()
    val running = if (pm.checkPermission("android.permission.DUMP", context.packageName) == PackageManager.PERMISSION_GRANTED) {
        try {
            val dump = readDiagnosticCommand(listOf("/system/bin/dumpsys", "-t", "3", "activity", "services"))
            if (dump.contains("Permission Denial", true)) notes += "Running-service inventory denied; provision DUMP and PACKAGE_USAGE_STATS/usage access."
            if (dump.length >= 256 * 1024) notes += "Running-service dump reached its size limit; some active services may be missing."
            parseRunningBikeServices(dump).associateBy { it.component }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            notes += "Running-service inventory unavailable: ${e.javaClass.simpleName}"
            emptyMap()
        }
    } else {
        notes += "DUMP not granted; active connections and process IDs unavailable."
        emptyMap()
    }
    if (Build.VERSION.SDK_INT >= 30 && pm.checkPermission("android.permission.QUERY_ALL_PACKAGES", context.packageName) != PackageManager.PERMISSION_GRANTED)
        notes += "Package visibility is restricted; inventory may be incomplete."
    @Suppress("DEPRECATION")
    val disabledFlag = if (Build.VERSION.SDK_INT >= 24) PackageManager.MATCH_DISABLED_COMPONENTS else PackageManager.GET_DISABLED_COMPONENTS
    @Suppress("DEPRECATION")
    val packages = pm.getInstalledPackages(PackageManager.GET_SERVICES or disabledFlag)
    val services = mutableListOf<InstalledBikeService>()
    for (pkg in packages.sortedBy { it.packageName }) {
        currentCoroutineContext().ensureActive()
        if (pkg.packageName == context.packageName || pkg.services.isNullOrEmpty()) continue
        val actions = try {
            pm.getResourcesForApplication(pkg.applicationInfo!!).assets.openXmlResourceParser("AndroidManifest.xml").use {
                readServiceActions(it, pkg.packageName)
            }
        } catch (e: Exception) {
            notes += "Manifest actions unavailable for ${pkg.packageName}: ${e.javaClass.simpleName}"
            emptyMap()
        }
        for (info in pkg.services.orEmpty()) {
            if (services.size >= 10_000) break
            val active = running["${pkg.packageName}/${info.name}"]
            val declared = actions[info.name].orEmpty()
            val observed = active?.actions.orEmpty().filter { a -> declared.none { it.name == a } }.map { ServiceAction(it) }
            val app = info.applicationInfo
            val appState = pm.getApplicationEnabledSetting(pkg.packageName)
            val componentState = pm.getComponentEnabledSetting(android.content.ComponentName(pkg.packageName, info.name))
            fun enabled(setting: Int, manifest: Boolean) = when (setting) {
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> manifest
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                else -> false
            }
            services += InstalledBikeService(pkg.packageName, info.name, info.processName ?: pkg.packageName,
                app.uid, packageVersion(pkg), enabled(appState, app.enabled) && enabled(componentState, info.enabled),
                info.exported, info.permission, info.permission == null || pm.checkPermission(info.permission, context.packageName) == PackageManager.PERMISSION_GRANTED,
                app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
                (declared + observed).distinct(), active != null, active?.pid, active?.clients.orEmpty())
        }
        if (services.size >= 10_000) { notes += "Installed-service inventory capped at 10000 entries."; break }
    }
    notes += "Active bindings identify Android clients; they do not establish exclusive ownership of the sensor UART."
    return BikeServiceInventory(services, notes)
}

@Suppress("DEPRECATION")
private fun packageVersion(pkg: PackageInfo) = "${pkg.versionName ?: "unknown"} (${if (Build.VERSION.SDK_INT >= 28) pkg.longVersionCode else pkg.versionCode.toLong()})"
