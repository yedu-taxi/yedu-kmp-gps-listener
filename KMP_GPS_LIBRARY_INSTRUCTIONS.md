# KMP GPS Background Tracking Library - Implementation Guide

> Convert Traccar Android (Kotlin) + iOS (Swift) GPS tracking into a single Kotlin Multiplatform library.

---

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [Project Structure](#2-project-structure)
3. [Step 1: Configure Build Files](#step-1-configure-build-files)
4. [Step 2: Common API (commonMain)](#step-2-common-api-commonmain)
5. [Step 3: Android Implementation (androidMain)](#step-3-android-implementation-androidmain)
6. [Step 4: iOS Implementation (iosMain)](#step-4-ios-implementation-iosmain)
7. [Step 5: Platform Configuration & Permissions](#step-5-platform-configuration--permissions)
8. [Step 6: Integration Guide for Consumers](#step-6-integration-guide-for-consumers)
9. [Step 7: Testing](#step-7-testing)
10. [API Reference](#api-reference)
11. [Component Mapping Table](#component-mapping-table)
12. [Critical Notes & Gotchas](#critical-notes--gotchas)

---

## 1. Architecture Overview

### What This Library Does

A **headless** (no UI) Kotlin Multiplatform library that provides:
- Background GPS location tracking
- Offline position buffering (SQLite/CoreData)
- HTTP position reporting to a server
- Network connectivity monitoring with auto-retry
- Battery status reporting
- Configurable interval/distance/angle filtering

### What This Library Does NOT Do

- No UI components (no settings screens, no widgets)
- No foreground service management (the **consuming app** must manage this on Android)
- No permission request dialogs (the **consuming app** must request permissions)
- No notification management

### Architecture Pattern: expect/actual

```
┌─────────────────────────────────────────────┐
│              commonMain                      │
│                                              │
│  TrackingConfig (data class)                 │
│  Position (data class)                       │
│  BatteryStatus (data class)                  │
│  TrackingListener (interface)                │
│  ProtocolFormatter (object)                  │
│  DistanceCalculator (object)                 │
│                                              │
│  expect class PlatformPositionProvider       │
│  expect class PlatformDatabaseHelper         │
│  expect class PlatformNetworkMonitor         │
│  expect class PlatformHttpClient             │
│  expect class PlatformBatteryProvider        │
│                                              │
│  TrackingController (shared orchestrator)    │
│                                              │
└──────────────┬──────────────┬────────────────┘
               │              │
    ┌──────────▼──────┐  ┌────▼───────────────┐
    │   androidMain   │  │     iosMain        │
    │                 │  │                     │
    │  actual class   │  │  actual class       │
    │  PlatformXxx    │  │  PlatformXxx        │
    │                 │  │                     │
    │  LocationManager│  │  CLLocationManager  │
    │  SQLiteDatabase │  │  CoreData (via      │
    │  ConnectivityMgr│  │    cinterop/manual) │
    │  HttpURLConn    │  │  NSURLSession       │
    │  BatteryManager │  │  UIDevice.battery   │
    └─────────────────┘  └─────────────────────┘
```

---

## 2. Project Structure

### Target File Layout

```
library/
├── build.gradle.kts
└── src/
    ├── commonMain/
    │   └── kotlin/
    │       └── org/traccar/kmp/
    │           ├── TrackingController.kt       # Main orchestrator (shared logic)
    │           ├── TrackingListener.kt          # Callback interface for consumers
    │           ├── TrackingConfig.kt            # Configuration data class
    │           ├── Position.kt                  # Position data class
    │           ├── BatteryStatus.kt             # Battery data class
    │           ├── ProtocolFormatter.kt         # URL formatting (pure Kotlin)
    │           ├── DistanceCalculator.kt        # Haversine formula (pure Kotlin)
    │           ├── PlatformPositionProvider.kt  # expect declaration
    │           ├── PlatformDatabaseHelper.kt    # expect declaration
    │           ├── PlatformNetworkMonitor.kt    # expect declaration
    │           ├── PlatformHttpClient.kt        # expect declaration
    │           └── PlatformBatteryProvider.kt   # expect declaration
    │
    ├── androidMain/
    │   └── kotlin/
    │       └── org/traccar/kmp/
    │           ├── AndroidPositionProvider.kt   # actual - LocationManager
    │           ├── AndroidDatabaseHelper.kt     # actual - SQLiteOpenHelper
    │           ├── AndroidNetworkMonitor.kt     # actual - ConnectivityManager
    │           ├── AndroidHttpClient.kt         # actual - HttpURLConnection
    │           └── AndroidBatteryProvider.kt    # actual - BatteryManager
    │
    ├── iosMain/
    │   └── kotlin/
    │       └── org/traccar/kmp/
    │           ├── IosPositionProvider.kt       # actual - CLLocationManager
    │           ├── IosDatabaseHelper.kt         # actual - NSUserDefaults/Files
    │           ├── IosNetworkMonitor.kt         # actual - NWPathMonitor
    │           ├── IosHttpClient.kt             # actual - NSURLSession
    │           └── IosBatteryProvider.kt        # actual - UIDevice
    │
    ├── commonTest/
    │   └── kotlin/
    │       └── org/traccar/kmp/
    │           ├── ProtocolFormatterTest.kt
    │           └── DistanceCalculatorTest.kt
    │
    ├── androidHostTest/
    │   └── kotlin/
    │       └── org/traccar/kmp/
    │           └── AndroidIntegrationTest.kt
    │
    └── iosTest/
        └── kotlin/
            └── org/traccar/kmp/
                └── IosIntegrationTest.kt
```

---

## Step 1: Configure Build Files

### 1.1 Update `gradle/libs.versions.toml`

```toml
[versions]
agp = "8.13.0"
kotlin = "2.2.20"
android-minSdk = "24"
android-compileSdk = "36"
vanniktechMavenPublish = "0.34.0"
kotlinxCoroutines = "1.10.2"
kotlinxDatetime = "0.6.2"

[libraries]
kotlin-test = { module = "org.jetbrains.kotlin:kotlin-test", version.ref = "kotlin" }
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "kotlinxCoroutines" }
kotlinx-coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "kotlinxCoroutines" }
kotlinx-datetime = { module = "org.jetbrains.kotlinx:kotlinx-datetime", version.ref = "kotlinxDatetime" }

[plugins]
android-kotlin-multiplatform-library = { id = "com.android.kotlin.multiplatform.library", version.ref = "agp" }
kotlinMultiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
vanniktech-mavenPublish = { id = "com.vanniktech.maven.publish", version.ref = "vanniktechMavenPublish" }
```

### 1.2 Update `library/build.gradle.kts`

```kotlin
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.vanniktech.mavenPublish)
}

group = "io.github.yourorg"
version = "1.0.0"

kotlin {
    // Android target
    androidLibrary {
        namespace = "org.traccar.kmp"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        withHostTestBuilder {}.configure {}
    }

    // iOS targets
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    // Source sets
    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }

        // Android-specific dependencies
        val androidMain by getting {
            dependencies {
                implementation(libs.kotlinx.coroutines.android)
            }
        }
    }
}

// Publishing config (customize for your artifact)
mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates(
        groupId = "io.github.yourorg",
        artifactId = "kmp-gps-tracker",
        version = "1.0.0"
    )
    pom {
        name.set("KMP GPS Background Tracker")
        description.set("Kotlin Multiplatform GPS background tracking library")
    }
}
```

---

## Step 2: Common API (commonMain)

### 2.1 Position.kt - Data Model

**Source from Android:** `Position.kt` (data class with deviceId, time, lat, lon, etc.)
**Source from iOS:** `Position.swift` (CoreData entity with same fields)

Both platforms share identical fields. Create a pure Kotlin data class:

```kotlin
// commonMain/kotlin/org/traccar/kmp/Position.kt
package org.traccar.kmp

import kotlinx.datetime.Instant

data class Position(
    val id: Long = 0,
    val deviceId: String,
    val time: Instant,               // kotlinx-datetime for cross-platform
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val altitude: Double = 0.0,
    val speed: Double = 0.0,         // in knots (converted from m/s * 1.943844)
    val course: Double = 0.0,        // bearing 0-360 degrees
    val accuracy: Double = 0.0,      // horizontal accuracy in meters
    val battery: Double = 0.0,       // 0-100%
    val charging: Boolean = false,
    val mock: Boolean = false        // Android only, false on iOS
)
```

> **Key mapping:** Android converts `m/s * 1.943844` to knots in `Position(Location)` constructor. iOS does `location.speed * 1.94384`. Both do this at the **provider level** before creating Position.

### 2.2 BatteryStatus.kt

```kotlin
// commonMain/kotlin/org/traccar/kmp/BatteryStatus.kt
package org.traccar.kmp

data class BatteryStatus(
    val level: Double = 0.0,       // 0-100
    val charging: Boolean = false
)
```

### 2.3 TrackingConfig.kt

Merge SharedPreferences keys (Android) and UserDefaults keys (iOS) into one config:

```kotlin
// commonMain/kotlin/org/traccar/kmp/TrackingConfig.kt
package org.traccar.kmp

data class TrackingConfig(
    val deviceId: String,                        // KEY_DEVICE / device_id_preference
    val serverUrl: String,                       // KEY_URL / server_url_preference
    val interval: Long = 300,                    // seconds between updates (default 5 min)
    val distance: Double = 0.0,                  // min meters to trigger update (0 = disabled)
    val angle: Double = 0.0,                     // min bearing change degrees (0 = disabled)
    val accuracy: LocationAccuracy = LocationAccuracy.MEDIUM,
    val bufferEnabled: Boolean = true,           // offline buffering
    val useWakeLock: Boolean = true              // Android only, ignored on iOS
)

enum class LocationAccuracy {
    HIGH,    // Android: GPS_PROVIDER,            iOS: kCLLocationAccuracyBest
    MEDIUM,  // Android: NETWORK_PROVIDER,        iOS: kCLLocationAccuracyHundredMeters
    LOW      // Android: PASSIVE_PROVIDER,        iOS: kCLLocationAccuracyKilometer
}
```

### 2.4 TrackingListener.kt - Consumer Callback Interface

```kotlin
// commonMain/kotlin/org/traccar/kmp/TrackingListener.kt
package org.traccar.kmp

interface TrackingListener {
    fun onPositionUpdate(position: Position)
    fun onPositionSent(position: Position, success: Boolean)
    fun onError(error: String)
    fun onStatusChange(message: String)           // "location update", "send ok", etc.
}
```

### 2.5 ProtocolFormatter.kt - Pure Kotlin (Shared)

**Source from Android:** `ProtocolFormatter.kt` - formats URL query params
**Source from iOS:** `ProtocolFormatter.swift` - identical logic

This is 100% shareable pure Kotlin. No platform deps needed:

```kotlin
// commonMain/kotlin/org/traccar/kmp/ProtocolFormatter.kt
package org.traccar.kmp

object ProtocolFormatter {
    fun formatRequest(serverUrl: String, position: Position, alarm: String? = null): String {
        val builder = StringBuilder(serverUrl)
        builder.append(if (serverUrl.contains("?")) "&" else "?")
        builder.append("id=").append(position.deviceId)
        builder.append("&timestamp=").append(position.time.epochSeconds)
        builder.append("&lat=").append(position.latitude)
        builder.append("&lon=").append(position.longitude)
        builder.append("&speed=").append(position.speed)
        builder.append("&bearing=").append(position.course)
        builder.append("&altitude=").append(position.altitude)
        builder.append("&accuracy=").append(position.accuracy)
        builder.append("&batt=").append(position.battery)
        if (position.charging) builder.append("&charge=true")
        if (position.mock) builder.append("&mock=true")
        alarm?.let { builder.append("&alarm=").append(it) }
        return builder.toString()
    }
}
```

### 2.6 DistanceCalculator.kt - Pure Kotlin (Shared)

**Source from iOS:** `DistanceCalculator.swift` - Haversine formula
**Android uses:** `Location.distanceTo()` but for cross-platform consistency, use Haversine:

```kotlin
// commonMain/kotlin/org/traccar/kmp/DistanceCalculator.kt
package org.traccar.kmp

import kotlin.math.*

object DistanceCalculator {
    private const val EQUATORIAL_EARTH_RADIUS = 6378.1370 // km
    private val DEG_TO_RAD = PI / 180.0

    /** Returns distance in meters between two coordinates */
    fun distance(
        fromLat: Double, fromLon: Double,
        toLat: Double, toLon: Double
    ): Double {
        val dLat = (toLat - fromLat) * DEG_TO_RAD
        val dLon = (toLon - fromLon) * DEG_TO_RAD
        val a = sin(dLat / 2).pow(2) +
                cos(fromLat * DEG_TO_RAD) * cos(toLat * DEG_TO_RAD) *
                sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EQUATORIAL_EARTH_RADIUS * c * 1000.0 // km -> meters
    }
}
```

### 2.7 Platform Expect Declarations

```kotlin
// commonMain/kotlin/org/traccar/kmp/PlatformPositionProvider.kt
package org.traccar.kmp

expect class PlatformPositionProvider {
    fun startUpdates(config: TrackingConfig, onPosition: (Position) -> Unit, onError: (String) -> Unit)
    fun stopUpdates()
    fun requestSingleLocation(config: TrackingConfig, onPosition: (Position) -> Unit)
}
```

```kotlin
// commonMain/kotlin/org/traccar/kmp/PlatformDatabaseHelper.kt
package org.traccar.kmp

expect class PlatformDatabaseHelper {
    fun insertPosition(position: Position)
    fun selectOldestPosition(): Position?
    fun deletePosition(id: Long)
    fun getCount(): Int
}
```

```kotlin
// commonMain/kotlin/org/traccar/kmp/PlatformNetworkMonitor.kt
package org.traccar.kmp

expect class PlatformNetworkMonitor {
    fun start(onNetworkChange: (Boolean) -> Unit)
    fun stop()
    fun isOnline(): Boolean
}
```

```kotlin
// commonMain/kotlin/org/traccar/kmp/PlatformHttpClient.kt
package org.traccar.kmp

expect class PlatformHttpClient {
    suspend fun sendRequest(url: String): Boolean
}
```

```kotlin
// commonMain/kotlin/org/traccar/kmp/PlatformBatteryProvider.kt
package org.traccar.kmp

expect class PlatformBatteryProvider {
    fun getBatteryStatus(): BatteryStatus
}
```

### 2.8 TrackingController.kt - Shared Orchestrator

This is the **most important file**. It contains the shared state machine logic from both platforms.

**Source from Android:** `TrackingController.kt` - the write/read/send/delete/retry cycle
**Source from iOS:** `TrackingController.swift` - identical state machine

The state machine is the same on both platforms:
```
Position arrives → write (if buffer) or send (if no buffer)
write → read → send → delete → read (next)
                  └→ retry (30s) → read → send
Network comes online → read (flush buffer)
```

```kotlin
// commonMain/kotlin/org/traccar/kmp/TrackingController.kt
package org.traccar.kmp

import kotlinx.coroutines.*

class TrackingController(
    private val config: TrackingConfig,
    private val positionProvider: PlatformPositionProvider,
    private val databaseHelper: PlatformDatabaseHelper,
    private val networkMonitor: PlatformNetworkMonitor,
    private val httpClient: PlatformHttpClient,
    private val listener: TrackingListener? = null
) {
    companion object {
        private const val RETRY_DELAY_MS = 30_000L
    }

    private var online = false
    private var waiting = false
    private var stopped = true
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var lastPosition: Position? = null

    fun start() {
        stopped = false
        online = networkMonitor.isOnline()

        // Start network monitoring
        networkMonitor.start { isOnline ->
            val wasOffline = !online
            online = isOnline
            listener?.onStatusChange(if (isOnline) "network online" else "network offline")
            if (wasOffline && isOnline) {
                read() // flush buffered positions
            }
        }

        // Start location updates
        positionProvider.startUpdates(
            config = config,
            onPosition = { position ->
                val filtered = applyFilter(position)
                if (filtered) {
                    lastPosition = position
                    listener?.onPositionUpdate(position)
                    if (config.bufferEnabled) {
                        write(position)
                    } else {
                        send(position)
                    }
                }
            },
            onError = { error ->
                listener?.onError(error)
            }
        )

        // If online, flush any previously buffered positions
        if (online) {
            read()
        }

        listener?.onStatusChange("service created")
    }

    fun stop() {
        stopped = true
        positionProvider.stopUpdates()
        networkMonitor.stop()
        scope.cancel()
        listener?.onStatusChange("service destroyed")
    }

    fun sendSingleLocation(alarm: String? = null) {
        positionProvider.requestSingleLocation(config) { position ->
            val url = ProtocolFormatter.formatRequest(config.serverUrl, position, alarm)
            scope.launch {
                val success = httpClient.sendRequest(url)
                listener?.onPositionSent(position, success)
            }
        }
    }

    // --- Position Filtering (from PositionProvider.processLocation on both platforms) ---

    private fun applyFilter(position: Position): Boolean {
        val last = lastPosition ?: return true // first position always passes

        // Time filter
        val elapsed = position.time.epochSeconds - last.time.epochSeconds
        if (elapsed >= config.interval) return true

        // Distance filter
        if (config.distance > 0) {
            val dist = DistanceCalculator.distance(
                last.latitude, last.longitude,
                position.latitude, position.longitude
            )
            if (dist >= config.distance) return true
        }

        // Angle filter
        if (config.angle > 0) {
            if (kotlin.math.abs(position.course - last.course) >= config.angle) return true
        }

        return false
    }

    // --- State Machine (identical on Android & iOS) ---

    private fun write(position: Position) {
        try {
            databaseHelper.insertPosition(position)
            listener?.onStatusChange("write")
            if (online && waiting) {
                read()
                waiting = false
            }
        } catch (e: Exception) {
            listener?.onError("write error: ${e.message}")
        }
    }

    private fun read() {
        if (stopped) return
        val position = databaseHelper.selectOldestPosition()
        if (position != null) {
            if (position.deviceId == config.deviceId) {
                listener?.onStatusChange("read")
                send(position)
            } else {
                // Stale position from different device config, discard
                databaseHelper.deletePosition(position.id)
                read()
            }
        } else {
            waiting = true
        }
    }

    private fun send(position: Position) {
        if (stopped) return
        val url = ProtocolFormatter.formatRequest(config.serverUrl, position)
        scope.launch {
            val success = httpClient.sendRequest(url)
            listener?.onPositionSent(position, success)
            if (config.bufferEnabled) {
                if (success) {
                    delete(position)
                } else {
                    retry()
                }
            }
        }
    }

    private fun delete(position: Position) {
        try {
            databaseHelper.deletePosition(position.id)
            listener?.onStatusChange("delete")
            read() // process next buffered position
        } catch (e: Exception) {
            listener?.onError("delete error: ${e.message}")
            retry()
        }
    }

    private fun retry() {
        listener?.onStatusChange("retry in ${RETRY_DELAY_MS / 1000}s")
        scope.launch {
            delay(RETRY_DELAY_MS)
            if (!stopped && online) {
                read()
            }
        }
    }
}
```

---

## Step 3: Android Implementation (androidMain)

### 3.1 AndroidPositionProvider.kt

**Source:** `AndroidPositionProvider.kt` - native LocationManager implementation

```kotlin
// androidMain/kotlin/org/traccar/kmp/AndroidPositionProvider.kt
package org.traccar.kmp

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import kotlinx.datetime.Clock

actual class PlatformPositionProvider(private val context: Context) {
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var locationListener: LocationListener? = null
    private val batteryProvider = PlatformBatteryProvider(context)

    companion object {
        private const val MINIMUM_INTERVAL = 1000L
    }

    @SuppressLint("MissingPermission")
    actual fun startUpdates(
        config: TrackingConfig,
        onPosition: (Position) -> Unit,
        onError: (String) -> Unit
    ) {
        val provider = when (config.accuracy) {
            LocationAccuracy.HIGH -> LocationManager.GPS_PROVIDER
            LocationAccuracy.LOW -> LocationManager.PASSIVE_PROVIDER
            LocationAccuracy.MEDIUM -> LocationManager.NETWORK_PROVIDER
        }

        val minTime = if (config.distance > 0 || config.angle > 0) {
            MINIMUM_INTERVAL
        } else {
            config.interval * 1000
        }

        locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val battery = batteryProvider.getBatteryStatus()
                val position = location.toPosition(config.deviceId, battery)
                onPosition(position)
            }
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        try {
            locationManager.requestLocationUpdates(
                provider, minTime, 0f, locationListener!!
            )
        } catch (e: Exception) {
            onError("Location provider error: ${e.message}")
        }
    }

    actual fun stopUpdates() {
        locationListener?.let { locationManager.removeUpdates(it) }
        locationListener = null
    }

    @SuppressLint("MissingPermission")
    actual fun requestSingleLocation(
        config: TrackingConfig,
        onPosition: (Position) -> Unit
    ) {
        val lastLocation = locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
        if (lastLocation != null) {
            val battery = batteryProvider.getBatteryStatus()
            onPosition(lastLocation.toPosition(config.deviceId, battery))
        }
    }

    // Extension: convert Android Location -> KMP Position
    private fun Location.toPosition(deviceId: String, battery: BatteryStatus): Position {
        return Position(
            deviceId = deviceId,
            time = Clock.System.now(),
            latitude = latitude,
            longitude = longitude,
            altitude = altitude,
            speed = speed * 1.943844,       // m/s -> knots
            course = bearing.toDouble(),
            accuracy = accuracy.toDouble(),
            battery = battery.level,
            charging = battery.charging,
            mock = isMock()
        )
    }

    private fun Location.isMock(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            isMock
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.JELLY_BEAN_MR2) {
            @Suppress("DEPRECATION") isFromMockProvider
        } else {
            false
        }
    }
}
```

### 3.2 AndroidDatabaseHelper.kt

**Source:** `DatabaseHelper.kt` - SQLiteOpenHelper with position table

```kotlin
// androidMain/kotlin/org/traccar/kmp/AndroidDatabaseHelper.kt
package org.traccar.kmp

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.datetime.Instant

actual class PlatformDatabaseHelper(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "kmp_tracker.db"
        private const val DATABASE_VERSION = 1
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE position (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                deviceId TEXT,
                time INTEGER,
                latitude REAL,
                longitude REAL,
                altitude REAL,
                speed REAL,
                course REAL,
                accuracy REAL,
                battery REAL,
                charging INTEGER,
                mock INTEGER
            )
        """)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS position")
        onCreate(db)
    }

    actual fun insertPosition(position: Position) {
        val values = ContentValues().apply {
            put("deviceId", position.deviceId)
            put("time", position.time.toEpochMilliseconds())
            put("latitude", position.latitude)
            put("longitude", position.longitude)
            put("altitude", position.altitude)
            put("speed", position.speed)
            put("course", position.course)
            put("accuracy", position.accuracy)
            put("battery", position.battery)
            put("charging", if (position.charging) 1 else 0)
            put("mock", if (position.mock) 1 else 0)
        }
        writableDatabase.insert("position", null, values)
    }

    actual fun selectOldestPosition(): Position? {
        val cursor = readableDatabase.rawQuery(
            "SELECT * FROM position ORDER BY id ASC LIMIT 1", null
        )
        return cursor.use {
            if (it.moveToFirst()) it.toPosition() else null
        }
    }

    actual fun deletePosition(id: Long) {
        writableDatabase.delete("position", "id = ?", arrayOf(id.toString()))
    }

    actual fun getCount(): Int {
        val cursor = readableDatabase.rawQuery("SELECT COUNT(*) FROM position", null)
        return cursor.use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    private fun android.database.Cursor.toPosition(): Position {
        return Position(
            id = getLong(getColumnIndexOrThrow("id")),
            deviceId = getString(getColumnIndexOrThrow("deviceId")),
            time = Instant.fromEpochMilliseconds(getLong(getColumnIndexOrThrow("time"))),
            latitude = getDouble(getColumnIndexOrThrow("latitude")),
            longitude = getDouble(getColumnIndexOrThrow("longitude")),
            altitude = getDouble(getColumnIndexOrThrow("altitude")),
            speed = getDouble(getColumnIndexOrThrow("speed")),
            course = getDouble(getColumnIndexOrThrow("course")),
            accuracy = getDouble(getColumnIndexOrThrow("accuracy")),
            battery = getDouble(getColumnIndexOrThrow("battery")),
            charging = getInt(getColumnIndexOrThrow("charging")) == 1,
            mock = getInt(getColumnIndexOrThrow("mock")) == 1
        )
    }
}
```

### 3.3 AndroidNetworkMonitor.kt

**Source:** `NetworkManager.kt` - BroadcastReceiver for CONNECTIVITY_ACTION

```kotlin
// androidMain/kotlin/org/traccar/kmp/AndroidNetworkMonitor.kt
package org.traccar.kmp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

actual class PlatformNetworkMonitor(private val context: Context) {
    private var receiver: BroadcastReceiver? = null

    actual fun start(onNetworkChange: (Boolean) -> Unit) {
        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                onNetworkChange(isOnline())
            }
        }
        @Suppress("DEPRECATION")
        context.registerReceiver(receiver, IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION))
    }

    actual fun stop() {
        receiver?.let { context.unregisterReceiver(it) }
        receiver = null
    }

    actual fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            @Suppress("DEPRECATION")
            val info = cm.activeNetworkInfo
            return info?.isConnectedOrConnecting == true
        }
    }
}
```

### 3.4 AndroidHttpClient.kt

**Source:** `RequestManager.kt` - HttpURLConnection POST

```kotlin
// androidMain/kotlin/org/traccar/kmp/AndroidHttpClient.kt
package org.traccar.kmp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

actual class PlatformHttpClient {
    companion object {
        private const val TIMEOUT = 15_000
    }

    actual suspend fun sendRequest(url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.readTimeout = TIMEOUT
            connection.connectTimeout = TIMEOUT
            connection.requestMethod = "POST"
            connection.inputStream.bufferedReader().readText()
            connection.disconnect()
            true
        } catch (e: Exception) {
            false
        }
    }
}
```

### 3.5 AndroidBatteryProvider.kt

**Source:** `PositionProvider.kt` method `getBatteryStatus()`

```kotlin
// androidMain/kotlin/org/traccar/kmp/AndroidBatteryProvider.kt
package org.traccar.kmp

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

actual class PlatformBatteryProvider(private val context: Context) {
    actual fun getBatteryStatus(): BatteryStatus {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (batteryIntent != null) {
            val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
            val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, 1)
            val status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, 0)
            return BatteryStatus(
                level = (level * 100.0) / scale,
                charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                           status == BatteryManager.BATTERY_STATUS_FULL
            )
        }
        return BatteryStatus()
    }
}
```

---

## Step 4: iOS Implementation (iosMain)

> **Critical:** iOS implementations use Kotlin/Native interop with Apple frameworks.
> Import `platform.CoreLocation.*`, `platform.Foundation.*`, `platform.UIKit.*`.

### 4.1 IosPositionProvider.kt

**Source:** `PositionProvider.swift` - CLLocationManager with background updates

```kotlin
// iosMain/kotlin/org/traccar/kmp/IosPositionProvider.kt
package org.traccar.kmp

import kotlinx.datetime.toKotlinInstant
import platform.CoreLocation.*
import platform.Foundation.NSDate
import platform.UIKit.UIDevice
import platform.darwin.NSObject

actual class PlatformPositionProvider {
    private var locationManager: CLLocationManager? = null
    private var delegate: LocationDelegate? = null

    actual fun startUpdates(
        config: TrackingConfig,
        onPosition: (Position) -> Unit,
        onError: (String) -> Unit
    ) {
        val manager = CLLocationManager()
        locationManager = manager

        // Configure accuracy (maps to iOS accuracy constants)
        manager.desiredAccuracy = when (config.accuracy) {
            LocationAccuracy.HIGH -> kCLLocationAccuracyBest
            LocationAccuracy.MEDIUM -> kCLLocationAccuracyHundredMeters
            LocationAccuracy.LOW -> kCLLocationAccuracyKilometer
        }

        manager.pausesLocationUpdatesAutomatically = false
        manager.allowsBackgroundLocationUpdates = true

        delegate = LocationDelegate(config, onPosition, onError)
        manager.delegate = delegate

        // Request permission if needed
        manager.requestAlwaysAuthorization()

        // Start updates
        manager.startUpdatingLocation()
        manager.startMonitoringSignificantLocationChanges()
    }

    actual fun stopUpdates() {
        locationManager?.stopUpdatingLocation()
        locationManager?.stopMonitoringSignificantLocationChanges()
        locationManager?.delegate = null
        locationManager = null
        delegate = null
    }

    actual fun requestSingleLocation(
        config: TrackingConfig,
        onPosition: (Position) -> Unit
    ) {
        locationManager?.location?.let { location ->
            val battery = getBatteryStatus()
            onPosition(location.toPosition(config.deviceId, battery))
        }
    }

    private class LocationDelegate(
        private val config: TrackingConfig,
        private val onPosition: (Position) -> Unit,
        private val onError: (String) -> Unit
    ) : NSObject(), CLLocationManagerDelegateProtocol {

        override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
            for (loc in didUpdateLocations) {
                val location = loc as? CLLocation ?: continue
                val battery = getBatteryStatus()
                onPosition(location.toPosition(config.deviceId, battery))
            }
        }

        override fun locationManager(manager: CLLocationManager, didFailWithError: platform.Foundation.NSError) {
            onError("Location error: ${didFailWithError.localizedDescription}")
        }
    }
}

// Top-level helper for iOS battery
internal fun getBatteryStatus(): BatteryStatus {
    val device = UIDevice.currentDevice
    device.batteryMonitoringEnabled = true
    val level = device.batteryLevel
    return if (level >= 0) {
        BatteryStatus(
            level = (level * 100).toDouble(),
            charging = device.batteryState == UIDeviceBatteryStateCharging ||
                       device.batteryState == UIDeviceBatteryStateFull
        )
    } else {
        BatteryStatus(level = 0.0, charging = false)
    }
}

// Extension: convert CLLocation -> KMP Position
internal fun CLLocation.toPosition(deviceId: String, battery: BatteryStatus): Position {
    return Position(
        deviceId = deviceId,
        time = timestamp.toKotlinInstant(),
        latitude = coordinate.latitude,
        longitude = coordinate.longitude,
        altitude = altitude,
        speed = if (speed >= 0) speed * 1.943844 else 0.0, // m/s -> knots
        course = if (course >= 0) course else 0.0,
        accuracy = horizontalAccuracy,
        battery = battery.level,
        charging = battery.charging,
        mock = false // iOS doesn't expose mock location
    )
}
```

### 4.2 IosDatabaseHelper.kt

**Source:** `DatabaseHelper.swift` - CoreData with Position entity

> **Important decision:** CoreData is NOT accessible from Kotlin/Native directly. Use **SQLite via cinterop** or a **simple file-based JSON store** instead.
> Recommended: Use kotlinx-serialization with a JSON file store for simplicity, or use a KMP SQLite library like `sqlite-driver`.

**Option A: Simple JSON file-based storage (recommended for v1):**

```kotlin
// iosMain/kotlin/org/traccar/kmp/IosDatabaseHelper.kt
package org.traccar.kmp

import kotlinx.datetime.Instant
import platform.Foundation.*

actual class PlatformDatabaseHelper {
    private val positions = mutableListOf<Position>()
    private var nextId = 1L

    // You can replace this with SQLDelight or another KMP DB solution later
    actual fun insertPosition(position: Position) {
        positions.add(position.copy(id = nextId++))
    }

    actual fun selectOldestPosition(): Position? {
        return positions.firstOrNull()
    }

    actual fun deletePosition(id: Long) {
        positions.removeAll { it.id == id }
    }

    actual fun getCount(): Int = positions.size
}
```

> **Production upgrade path:** Replace with [SQLDelight](https://github.com/cashapp/sqldelight) which supports both Android SQLite and iOS SQLite natively. This gives you a proper database on both platforms from `commonMain`. See [Step 7 upgrade notes](#production-database-upgrade-sqldelight).

### 4.3 IosNetworkMonitor.kt

**Source:** `NetworkManager.swift` - was mostly stubbed. Use `NWPathMonitor` (modern iOS API):

```kotlin
// iosMain/kotlin/org/traccar/kmp/IosNetworkMonitor.kt
package org.traccar.kmp

import platform.Network.*
import platform.darwin.dispatch_get_main_queue

actual class PlatformNetworkMonitor {
    private var monitor: nw_path_monitor_t? = null
    private var currentlyOnline = true

    actual fun start(onNetworkChange: (Boolean) -> Unit) {
        monitor = nw_path_monitor_create()
        nw_path_monitor_set_update_handler(monitor!!) { path ->
            val status = nw_path_get_status(path)
            val online = status == nw_path_status_satisfied
            currentlyOnline = online
            onNetworkChange(online)
        }
        nw_path_monitor_set_queue(monitor!!, dispatch_get_main_queue())
        nw_path_monitor_start(monitor!!)
    }

    actual fun stop() {
        monitor?.let { nw_path_monitor_cancel(it) }
        monitor = null
    }

    actual fun isOnline(): Boolean = currentlyOnline
}
```

### 4.4 IosHttpClient.kt

**Source:** `RequestManager.swift` - NSURLConnection (deprecated). Use NSURLSession:

```kotlin
// iosMain/kotlin/org/traccar/kmp/IosHttpClient.kt
package org.traccar.kmp

import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.*
import kotlin.coroutines.resume

actual class PlatformHttpClient {
    actual suspend fun sendRequest(url: String): Boolean = suspendCancellableCoroutine { cont ->
        val nsUrl = NSURL.URLWithString(url) ?: run {
            cont.resume(false)
            return@suspendCancellableCoroutine
        }
        val request = NSMutableURLRequest.requestWithURL(nsUrl).apply {
            setHTTPMethod("POST")
            setTimeoutInterval(15.0)
        }
        val task = NSURLSession.sharedSession.dataTaskWithRequest(request) { data, response, error ->
            cont.resume(error == null && data != null)
        }
        task.resume()
        cont.invokeOnCancellation { task.cancel() }
    }
}
```

### 4.5 IosBatteryProvider.kt

```kotlin
// iosMain/kotlin/org/traccar/kmp/IosBatteryProvider.kt
package org.traccar.kmp

import platform.UIKit.*

actual class PlatformBatteryProvider {
    actual fun getBatteryStatus(): BatteryStatus {
        val device = UIDevice.currentDevice
        device.batteryMonitoringEnabled = true
        val level = device.batteryLevel
        return if (level >= 0) {
            BatteryStatus(
                level = (level * 100).toDouble(),
                charging = device.batteryState == UIDeviceBatteryStateCharging ||
                           device.batteryState == UIDeviceBatteryStateFull
            )
        } else {
            BatteryStatus(level = 0.0, charging = false)
        }
    }
}
```

---

## Step 5: Platform Configuration & Permissions

### 5.1 Android - What the Consuming App Must Do

The **library consumer** (not this library) must handle these in their app:

#### AndroidManifest.xml (consumer's app)

```xml
<!-- Permissions -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
```

#### Foreground Service (consumer's app)

The consumer must create their own foreground service that holds the `TrackingController`:

```kotlin
// In the CONSUMER app, not the library
class MyTrackingService : Service() {
    private lateinit var trackingController: TrackingController

    override fun onCreate() {
        super.onCreate()

        val config = TrackingConfig(
            deviceId = "my-device-123",
            serverUrl = "http://myserver:5055",
            interval = 300,
            bufferEnabled = true
        )

        trackingController = TrackingController(
            config = config,
            positionProvider = PlatformPositionProvider(this),
            databaseHelper = PlatformDatabaseHelper(this),
            networkMonitor = PlatformNetworkMonitor(this),
            httpClient = PlatformHttpClient(),
            listener = object : TrackingListener {
                override fun onPositionUpdate(position: Position) { /* ... */ }
                override fun onPositionSent(position: Position, success: Boolean) { /* ... */ }
                override fun onError(error: String) { /* ... */ }
                override fun onStatusChange(message: String) { /* ... */ }
            }
        )

        // Create notification for foreground service
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        trackingController.start()
    }

    override fun onDestroy() {
        trackingController.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
```

### 5.2 iOS - What the Consuming App Must Do

#### Info.plist (consumer's app)

```xml
<!-- Background modes -->
<key>UIBackgroundModes</key>
<array>
    <string>location</string>
</array>

<!-- Location permission descriptions -->
<key>NSLocationAlwaysAndWhenInUseUsageDescription</key>
<string>Required for background GPS tracking</string>
<key>NSLocationAlwaysUsageDescription</key>
<string>Required for background GPS tracking</string>
<key>NSLocationWhenInUseUsageDescription</key>
<string>Required for GPS tracking</string>
```

#### Swift Integration (consumer's app)

```swift
import KmpGpsTracker  // Your KMP framework name

class AppDelegate: UIResponder, UIApplicationDelegate {
    var trackingController: TrackingController?

    func startTracking() {
        let config = TrackingConfig(
            deviceId: "my-device-123",
            serverUrl: "http://myserver:5055",
            interval: 300,
            distance: 0.0,
            angle: 0.0,
            accuracy: .medium,
            bufferEnabled: true,
            useWakeLock: false
        )

        let controller = TrackingController(
            config: config,
            positionProvider: PlatformPositionProvider(),
            databaseHelper: PlatformDatabaseHelper(),
            networkMonitor: PlatformNetworkMonitor(),
            httpClient: PlatformHttpClient(),
            listener: nil  // or implement TrackingListener
        )

        controller.start()
        trackingController = controller
    }
}
```

---

## Step 6: Integration Guide for Consumers

### Android (Gradle)

```kotlin
// Consumer's build.gradle.kts
dependencies {
    implementation("io.github.yourorg:kmp-gps-tracker:1.0.0")
}
```

### iOS (Swift Package Manager / CocoaPods)

The KMP library generates an XCFramework. Consumers integrate via:

**Option A: Direct framework**
```
./gradlew :library:assembleXCFramework
```
Then add the generated `.xcframework` to the Xcode project.

**Option B: CocoaPods** (add cocoapods plugin to build.gradle.kts)

---

## Step 7: Testing

### 7.1 Common Tests (Pure Kotlin)

```kotlin
// commonTest/kotlin/org/traccar/kmp/ProtocolFormatterTest.kt
package org.traccar.kmp

import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertTrue

class ProtocolFormatterTest {
    @Test
    fun testFormatRequest() {
        val position = Position(
            deviceId = "test123",
            time = Instant.fromEpochSeconds(1234567890),
            latitude = 40.7128,
            longitude = -74.0060,
            speed = 15.5,
            course = 90.0,
            altitude = 10.0,
            accuracy = 5.0,
            battery = 85.0,
            charging = true
        )
        val url = ProtocolFormatter.formatRequest("http://server:5055", position)
        assertTrue(url.contains("id=test123"))
        assertTrue(url.contains("lat=40.7128"))
        assertTrue(url.contains("charge=true"))
    }
}
```

```kotlin
// commonTest/kotlin/org/traccar/kmp/DistanceCalculatorTest.kt
package org.traccar.kmp

import kotlin.test.Test
import kotlin.test.assertTrue

class DistanceCalculatorTest {
    @Test
    fun testDistance() {
        // NYC to LA ≈ 3,944 km
        val dist = DistanceCalculator.distance(40.7128, -74.0060, 34.0522, -118.2437)
        assertTrue(dist > 3_900_000 && dist < 4_000_000, "Distance should be ~3944 km")
    }
}
```

### 7.2 Production Database Upgrade (SQLDelight)

For production, replace the in-memory iOS database with **SQLDelight** which provides a shared SQLite implementation across both platforms:

```kotlin
// Add to libs.versions.toml
// sqldelight = "2.0.2"
// [libraries]
// sqldelight-runtime = { module = "app.cash.sqldelight:runtime", version.ref = "sqldelight" }
// sqldelight-android = { module = "app.cash.sqldelight:android-driver", version.ref = "sqldelight" }
// sqldelight-native = { module = "app.cash.sqldelight:native-driver", version.ref = "sqldelight" }
```

This lets you define the SQL schema once in `commonMain` and have it work on both platforms.

---

## API Reference

### Public API Surface

| Class | Type | Description |
|-------|------|-------------|
| `TrackingController` | Class | Main entry point. Call `start()`, `stop()`, `sendSingleLocation()` |
| `TrackingConfig` | Data class | All configuration options |
| `TrackingListener` | Interface | Callbacks for position updates, send results, errors |
| `Position` | Data class | GPS location with metadata |
| `BatteryStatus` | Data class | Battery level and charging state |
| `LocationAccuracy` | Enum | HIGH, MEDIUM, LOW |

### TrackingController Methods

| Method | Description |
|--------|-------------|
| `start()` | Begin tracking. Starts GPS, network monitoring, buffer flushing |
| `stop()` | Stop all tracking. Cleans up resources |
| `sendSingleLocation(alarm?)` | Send one immediate position (e.g., SOS) |

---

## Component Mapping Table

| Concept | Android Source | iOS Source | KMP Target |
|---------|---------------|-----------|------------|
| **Orchestrator** | `TrackingController.kt` | `TrackingController.swift` | `TrackingController.kt` (commonMain) |
| **Position data** | `Position.kt` | `Position.swift` (CoreData) | `Position.kt` (commonMain) |
| **Battery data** | `BatteryStatus.kt` | `BatteryStatus.swift` | `BatteryStatus.kt` (commonMain) |
| **URL formatting** | `ProtocolFormatter.kt` | `ProtocolFormatter.swift` | `ProtocolFormatter.kt` (commonMain) |
| **Distance calc** | `Location.distanceTo()` | `DistanceCalculator.swift` | `DistanceCalculator.kt` (commonMain) |
| **GPS provider** | `AndroidPositionProvider.kt` | `PositionProvider.swift` | `PlatformPositionProvider` (expect/actual) |
| **Database** | `DatabaseHelper.kt` (SQLite) | `DatabaseHelper.swift` (CoreData) | `PlatformDatabaseHelper` (expect/actual) |
| **Network monitor** | `NetworkManager.kt` | `NetworkManager.swift` | `PlatformNetworkMonitor` (expect/actual) |
| **HTTP client** | `RequestManager.kt` | `RequestManager.swift` | `PlatformHttpClient` (expect/actual) |
| **Battery reader** | `PositionProvider.getBatteryStatus()` | `PositionProvider.getBatteryStatus()` | `PlatformBatteryProvider` (expect/actual) |
| **Config/Settings** | `MainFragment` (SharedPrefs) | `Root.plist` (UserDefaults) | `TrackingConfig` data class (passed in) |
| **Foreground svc** | `TrackingService.kt` | N/A (iOS background mode) | **NOT in library** - consumer's responsibility |
| **Boot receiver** | `AutostartReceiver.kt` | N/A | **NOT in library** - consumer's responsibility |
| **Wake lock** | `TrackingService.kt` | N/A | **NOT in library** - consumer's responsibility |
| **Permissions** | `MainFragment.kt` | `Info.plist` | **NOT in library** - consumer's responsibility |
| **UI** | `MainFragment`, `StatusActivity` | `MainViewController` | **NOT in library** |

---

## Critical Notes & Gotchas

### 1. expect/actual Constructor Parameters

Android `actual` classes need `Context`. iOS ones don't. Handle this by passing platform-specific params to constructors:

```kotlin
// Android: PlatformPositionProvider(context: Context)
// iOS: PlatformPositionProvider()  -- no params needed
```

The `expect` declaration should have **no constructor parameters**. Instead, create a factory:

```kotlin
// commonMain
expect class PlatformPositionProvider

// Or use a factory pattern:
expect fun createPositionProvider(): PlatformPositionProvider
```

**Alternative (recommended):** Use an interface in commonMain and provide platform implementations:

```kotlin
// commonMain
interface PositionProvider {
    fun startUpdates(config: TrackingConfig, onPosition: (Position) -> Unit, onError: (String) -> Unit)
    fun stopUpdates()
    fun requestSingleLocation(config: TrackingConfig, onPosition: (Position) -> Unit)
}

// The consumer creates the platform-specific instance and passes it to TrackingController
```

This avoids the expect/actual constructor mismatch entirely. The `TrackingController` accepts interfaces, and each platform provides its own implementation.

### 2. iOS Background Location - Critical Settings

The iOS consumer app **must** set these or tracking will stop in background:
- `UIBackgroundModes: location` in Info.plist
- `pausesLocationUpdatesAutomatically = false`
- `allowsBackgroundLocationUpdates = true`
- Request `.authorizedAlways` (not just `.authorizedWhenInUse`)

### 3. Android Foreground Service - Required Since Android 8+

The library does NOT manage the foreground service. The consumer app **must**:
- Create a foreground service with a persistent notification
- Hold the `TrackingController` reference in the service
- Handle `FOREGROUND_SERVICE_LOCATION` service type (Android 14+)

### 4. Speed Conversion

Both platforms convert `m/s → knots (* 1.943844)`. This is done in the platform providers before creating Position objects.

### 5. Kotlin/Native Threading (iOS)

- Kotlin/Native with the new memory model (default since Kotlin 1.7.20) allows sharing objects across threads
- `kotlinx.coroutines` works cross-platform with `Dispatchers.Main` and `Dispatchers.Default`
- `CLLocationManager` delegate callbacks arrive on the main thread

### 6. iOS CoreData vs SQLite

CoreData cannot be directly accessed from Kotlin/Native. Options:
1. **Simple in-memory list** (quick start, loses data on app kill)
2. **JSON file storage** (persists but slow with many positions)
3. **SQLDelight** (recommended for production - shared SQL across platforms)
4. **Swift wrapper** exposed via framework interop

### 7. Testing on Real Devices

GPS background tracking **cannot be fully tested in simulators**. You must test on real devices for:
- Background location updates
- Wake lock behavior (Android)
- Significant location changes (iOS)
- Battery impact

### 8. Build & Run Commands

```bash
# Build all targets
./gradlew :library:build

# Run common tests
./gradlew :library:allTests

# Run Android host tests only
./gradlew :library:testAndroidHostTest

# Run iOS simulator tests
./gradlew :library:iosSimulatorArm64Test

# Generate iOS XCFramework
./gradlew :library:assembleXCFramework

# Publish to Maven Central
./gradlew :library:publishToMavenCentral
```

---

## Implementation Order (Recommended)

1. **Start with commonMain** - Position, BatteryStatus, TrackingConfig, ProtocolFormatter, DistanceCalculator
2. **Add expect declarations** - PlatformPositionProvider, PlatformDatabaseHelper, etc.
3. **Implement androidMain** - All actual classes using Android APIs
4. **Write & run common tests** - ProtocolFormatter, DistanceCalculator
5. **Implement iosMain** - All actual classes using iOS APIs via Kotlin/Native
6. **Create a sample Android app** - Test foreground service integration
7. **Create a sample iOS app** - Test background location integration
8. **Upgrade database** to SQLDelight for production persistence
9. **Publish** to Maven Central / generate XCFramework