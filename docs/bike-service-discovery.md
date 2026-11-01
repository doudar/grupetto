# Permanent bike service discovery

Open **Bike service discovery** in the configuration screen's footer, then choose
**Discover bike services**. Discovery is available on unrecognized tablets too;
model emulation must be turned off to scan real services.

The scanner inventories installed Android services and their manifest actions,
permissions, enabled/exported state, process, package version and system-app status.
With the optional developer grants below it also records active service processes,
binding actions and client identities. It reads APK manifests as resources without
loading another application's code. Unknown package/model names are not excluded.

It prioritizes known actions, then relevant or currently running accessible services.
It binds explicit components and reads their Binder descriptor before invoking a
telemetry adapter. Up to 24 endpoints are probed within a 45-second probing budget;
the passive inventory is capped at 10,000 services. Limits and unavailable metadata
are recorded in the report. Disabled, nonexported and permission-blocked services
remain in the inventory but are not bound. Services outside the candidate selection
remain in the report, even when their interface was not probed.

## Supported contracts

| Contract | Identification | Telemetry |
| --- | --- | --- |
| Affernet original Bike | `com.onepeloton.affernetservice.IV1Interface` Binder descriptor | Existing callback reader, with a separate discovery client name |
| Affernet Bike+ | `com.onepeloton.affernetservice.IBikeInterface` Binder descriptor | Existing read-only BikeData polling |
| Legacy SensorData | `android.os.IMessenger` descriptor **and** `android.intent.action.peloton.SensorData` action | Existing cadence, power and resistance request whitelist |

Affernet descriptors can be recognized under different packages and binding actions.
`IMessenger` alone is not evidence of the SensorData protocol. Discovery does not
copy alternative manifest categories into the bind intent.
SensorData always receives only the `com.peloton.sensor.category.BIKE` category;
advertised `FAKE_DATA` and `AURORA` categories are retained in the report but never
sent to select a sensor mode. Unknown descriptors
receive no guessed application transactions. Binder descriptor lookup uses a bounded
worker because a stalled native Binder call cannot be interrupted by coroutine
cancellation. Known telemetry calls use the existing adapters.

A compatible result requires a complete, plausible sample of watts, cadence and
resistance. Zero cadence/power is valid. A binder that returns no telemetry is not
offered as a connection. SensorData is skipped if an enabled Affernet provider is
advertised, including inaccessible providers; an identified Affernet binder also
prevents subsequent SensorData probes. The scanner does not stop vendor services
or claim that Android bindings prove ownership of the UART.

Choose **Use this service & restart** on a verified result to persist its component,
action, categories and protocol. Grupetto stops its ride/transmitters and restarts;
subsequent launches use this endpoint for native bike telemetry. The descriptor is
checked again before reading. An unavailable/changed service produces a connection
error; **Use automatic selection & restart** restores the prior device routing.
The selection works without a recognized tablet model. Known Tread/Tread+/Row
platforms cannot be reclassified as bikes just because Affernet exposes a bike binder.

Discovered connections provide telemetry only. They do not enable motor control,
tablet calibration overrides, sensor-board writes or pedal calibration collection.
Automatic device selection retains the existing control/calibration behavior.

## Diagnostic reports

Every scan saves `latest.json`, including scans that find no supported interface or
are cancelled. The passive inventory is saved before interface probing, and updated
after each result. Writes are atomic. Each new scan replaces the previous report.
Reports contain device/software identification, permission grants, service metadata,
Binder descriptors, verified samples, outcomes and scan limitations. Intent extras,
full system logs, bike serial numbers and unrelated application content are excluded.
Reports remain on the device; no upload occurs.

**Export report** opens Android's document picker. The automatically saved report is
also available through ADB on devices that permit access to external app storage:

```powershell
adb pull /sdcard/Android/data/com.spop.poverlay/files/service-discovery/latest.json
```

Internal app storage is used if external app storage is unavailable. On a debug build,
that fallback can be read with `adb exec-out run-as com.spop.poverlay cat files/service-discovery/latest.json`.
The report remains available after reopening Grupetto. Run discovery again to refresh
the interactive results or verify an endpoint before selecting it.

## OpenPelo / ADB provisioning

The app declares `QUERY_ALL_PACKAGES` for broad installed-service visibility. It is
a normal install-time permission on Android 11+, not a developer runtime grant.
Service access permissions still apply. If visibility is restricted, the report
identifies the limitation.

The following optional grants enable active service/client diagnostics on the tested
Peloton Android 11 firmware:

```powershell
adb shell pm grant com.spop.poverlay android.permission.DUMP
adb shell pm grant com.spop.poverlay android.permission.PACKAGE_USAGE_STATS
adb shell appops set com.spop.poverlay GET_USAGE_STATS allow
```

Existing serial-error logging can additionally use
`adb shell pm grant com.spop.poverlay android.permission.READ_LOGS`.
The permanent discovery report does not collect logcat. Missing grants reduce the
available diagnostics; installed-service discovery still runs. Grupetto never grants
these privileges itself, and OpenPelo's install flags alone should not be assumed
to provision them. No OpenPelo source changes are included here.

## Validation

JVM tests cover protocol recognition, generic Messenger rejection, unknown active
services, candidate ordering, inaccessible Affernet priority, sample validation,
non-bike platform exclusion and filtered connection metadata.

Android tests use a separate fixture APK with unfamiliar service/action names. They
exercise real manifest discovery, Affernet V1/Bike+ Binder reads, unknown-interface
handling without telemetry calls, saved selection/restoration, changed-descriptor
rejection, report export and cancellation. Fixtures do not access bike hardware.
