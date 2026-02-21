# KMP GPS Background Listener Library - Implementation Guide

> A pure GPS background listener library. Native side only collects locations and notifies KMP listeners. The consumer app decides what to do with positions (send to server, store, etc.).
!!! Remove all traccar words from code
> 
> USE BEST PRACTICES TO BUILD LIBRARY
---

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [Project Structure](#2-project-structure)
3. [Step 1: Configure Build Files](#step-1-configure-build-files)
4. [Step 2: Common API (commonMain)](#step-2-common-api-commonmain)
5. [Step 3: Android Implementation (androidMain)](#step-3-android-implementation-androidmain)
6. [Step 4: iOS Implementation (iosMain)](#step-4-ios-implementation-iosmain)
7. [Step 5: Platform Configuration & Permissions](#step-5-platform-configuration--permissions)
8. [Step 6: Consumer Integration Examples](#step-6-consumer-integration-examples)
9. [Step 7: Testing](#step-7-testing)
10. [API Reference](#api-reference)
11. [Component Mapping Table](#component-mapping-table)
12. [Critical Notes & Gotchas](#critical-notes--gotchas)

---

## 1. Architecture Overview

### What This Library Does

A **headless** (no UI) Kotlin Multiplatform library that provides:
- Background GPS location listening on Android and iOS
- Position filtering by interval, distance, and angle
- Battery status reporting alongside each position
- KMP-side listeners that consumers subscribe to for receiving positions
- All configuration passed dynamically at runtime from KMP side

### What This Library Does NOT Do

- No server communication (no HTTP, no URL formatting, no retry logic)
- No local database or position buffering
- No UI components (no settings screens, no widgets)
- No foreground service management (consumer's responsibility on Android)
- No permission request dialogs (consumer's responsibility)
- No notification management

> **Design principle:** Native platform code only collects GPS data and notifies KMP listeners. The consumer decides what to do with positions - send to a server, store in a database, display on a map, etc.

### Architecture Diagram

```
┌──────────────────────────────────────────────────────────┐
│                    CONSUMER APP                           │
│                                                           │
│  Creates GpsTracker with config                          │
│  Subscribes to GpsTrackerListener                        │
│  Receives Position objects                                │
│  ↓ Does whatever it wants:                               │
│    • Send to server (HTTP, WebSocket, etc.)              │
│    • Store in local DB                                    │
│    • Show on map                                          │
│    • Log / analytics                                      │
└──────────────┬───────────────────────────────────────────┘
               │ calls start(config) / stop()
               │ receives onPositionUpdate(), onError()
┌──────────────▼───────────────────────────────────────────┐
│                   commonMain (KMP)                        │
│                                                           │
│  GpsTracker            ← Main entry point                │
│  GpsTrackerListener    ← Callback interface              │
│  GpsConfig             ← Dynamic configuration           │
│  Position              ← Data class (lat, lon, speed...) │
│  BatteryStatus         ← Data class (level, charging)    │
│  LocationAccuracy      ← Enum (HIGH, MEDIUM, LOW)        │
│  DistanceCalculator    ← Haversine formula (filtering)   │
│                                                           │
│  interface PlatformLocationProvider   ← abstraction       │
│  interface PlatformBatteryProvider    ← abstraction       │
│                                                           │
└──────────────┬──────────────┬────────────────────────────┘
               │              │
    ┌──────────▼──────┐  ┌────▼───────────────┐
    │   androidMain   │  │     iosMain        │
    │                 │  │                     │
    │ AndroidLocation │  │ IosLocationProvider │
    │   Provider      │  │                     │
    │                 │  │ CLLocationManager   │
    │ LocationManager │  │ + significant loc   │
    │ + battery API   │  │ + UIDevice battery  │
    │                 │  │                     │
    │ ONLY collects   │  │ ONLY collects       │
    │ GPS + battery   │  │ GPS + battery       │
    │ and notifies    │  │ and notifies        │
    │ KMP listener    │  │ KMP listener        │
    └─────────────────┘  └─────────────────────┘
```

### Data Flow

```
Native GPS hardware
       ↓
Platform LocationProvider (Android/iOS)
       ↓ raw location
GpsTracker.applyFilter()  ← interval/distance/angle check
       ↓ filtered position
GpsTrackerListener.onPositionUpdate(position)  ← KMP listener
       ↓
Consumer app receives Position and does whatever it wants
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
    │           ├── GpsTracker.kt                # Main entry point + filtering logic
    │           ├── GpsTrackerListener.kt         # Callback interface for consumers
    │           ├── GpsConfig.kt                  # Dynamic configuration data class
    │           ├── Position.kt                   # Position data class
    │           ├── BatteryStatus.kt              # Battery data class
    │           ├── LocationAccuracy.kt           # Enum
    │           ├── DistanceCalculator.kt         # Haversine formula (pure Kotlin)
    │           ├── PlatformLocationProvider.kt   # Interface for platform GPS
    │           └── PlatformBatteryProvider.kt    # Interface for platform battery
    │
    ├── androidMain/
    │   └── kotlin/
    │       └── org/traccar/kmp/
    │           ├── AndroidLocationProvider.kt    # LocationManager implementation
    │           └── AndroidBatteryProvider.kt     # BatteryManager implementation
    │
    ├── iosMain/
    │   └── kotlin/
    │       └── org/traccar/kmp/
    │           ├── IosLocationProvider.kt        # CLLocationManager implementation
    │           └── IosBatteryProvider.kt         # UIDevice battery implementation
    │
    ├── commonTest/
    │   └── kotlin/
    │       └── org/traccar/kmp/
    │           ├── DistanceCalculatorTest.kt
    │           └── GpsTrackerFilterTest.kt
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

> **Note:** Compared to the previous version, the following are REMOVED from the library:
> - `ProtocolFormatter` (server URL formatting) - consumer's responsibility
> - `PlatformDatabaseHelper` (position buffering) - consumer's responsibility
> - `PlatformNetworkMonitor` (connectivity) - consumer's responsibility
> - `PlatformHttpClient` (HTTP requests) - consumer's responsibility
> - `TrackingController` state machine (write/read/send/delete/retry) - consumer's responsibility

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
kotlinxDatetime = "0.6.2"

[libraries]
kotlin-test = { module = "org.jetbrains.kotlin:kotlin-test", version.ref = "kotlin" }
kotlinx-datetime = { module = "org.jetbrains.kotlinx:kotlinx-datetime", version.ref = "kotlinxDatetime" }

[plugins]
android-kotlin-multiplatform-library = { id = "com.android.kotlin.multiplatform.library", version.ref = "agp" }
kotlinMultiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
vanniktech-mavenPublish = { id = "com.vanniktech.maven.publish", version.ref = "vanniktechMavenPublish" }
```

> **Note:** No coroutines dependency needed. The library is callback-based. No HTTP/network/database dependencies either.

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
            implementation(libs.kotlinx.datetime)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates(
        groupId = "io.github.yourorg",
        artifactId = "kmp-gps-listener",
        version = "1.0.0"
    )
    pom {
        name.set("KMP GPS Background Listener")
        description.set("Kotlin Multiplatform GPS background listener library")
    }
}
```

---

## Step 2: Common API (commonMain)

### 2.1 Position.kt - Data Model

**Source from Android:** `Position.kt` fields
**Source from iOS:** `Position.swift` fields

```kotlin
// commonMain/kotlin/org/traccar/kmp/Position.kt
package org.traccar.kmp

import kotlinx.datetime.Instant

data class Position(
    val deviceId: String,
    val time: Instant,               // kotlinx-datetime for cross-platform
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val altitude: Double = 0.0,
    val speed: Double = 0.0,         // in knots (converted from m/s * 1.943844)
    val course: Double = 0.0,        // bearing 0-360 degrees
    val accuracy: Double = 0.0,      // horizontal accuracy in meters
    val battery: BatteryStatus = BatteryStatus(),
    val mock: Boolean = false        // Android only, always false on iOS
)
```

> **Key change:** `battery` is now embedded as a `BatteryStatus` object inside `Position`, not separate fields. This makes the listener callback simpler - one object contains everything.

### 2.2 BatteryStatus.kt

```kotlin
// commonMain/kotlin/org/traccar/kmp/BatteryStatus.kt
package org.traccar.kmp

data class BatteryStatus(
    val level: Double = 0.0,       // 0-100
    val charging: Boolean = false
)
```

### 2.3 LocationAccuracy.kt

```kotlin
// commonMain/kotlin/org/traccar/kmp/LocationAccuracy.kt
package org.traccar.kmp

enum class LocationAccuracy {
    HIGH,    // Android: GPS_PROVIDER,            iOS: kCLLocationAccuracyBest
    MEDIUM,  // Android: NETWORK_PROVIDER,        iOS: kCLLocationAccuracyHundredMeters
    LOW      // Android: PASSIVE_PROVIDER,        iOS: kCLLocationAccuracyKilometer
}
```

### 2.4 GpsConfig.kt - Dynamic Configuration

All parameters are passed at runtime by the consumer. No hardcoded values, no SharedPreferences, no UserDefaults.

```kotlin
// commonMain/kotlin/org/traccar/kmp/GpsConfig.kt
package org.traccar.kmp

data class GpsConfig(
    val deviceId: String,
    val interval: Long = 300,                    // seconds between updates (default 5 min)
    val distance: Double = 0.0,                  // min meters to trigger update (0 = disabled)
    val angle: Double = 0.0,                     // min bearing change degrees (0 = disabled)
    val accuracy: LocationAccuracy = LocationAccuracy.MEDIUM
)
```

> **What's removed vs old `TrackingConfig`:** No `serverUrl`, no `bufferEnabled`, no `useWakeLock`. The library doesn't know about servers or buffering. Wake lock is the consumer's responsibility.

### 2.5 GpsTrackerListener.kt - Consumer Callback Interface

This is the **only way** the library communicates back to the consumer. Native GPS code feeds into this listener through the KMP layer.

```kotlin
// commonMain/kotlin/org/traccar/kmp/GpsTrackerListener.kt
package org.traccar.kmp

interface GpsTrackerListener {
    /** Called when a new filtered GPS position is available */
    fun onPositionUpdate(position: Position)

    /** Called when GPS provider encounters an error */
    fun onError(error: String)

    /** Called when tracker status changes (started, stopped) */
    fun onStatusChange(status: TrackerStatus)
}

enum class TrackerStatus {
    STARTED,
    STOPPED
}
```

### 2.6 DistanceCalculator.kt - Pure Kotlin (Shared)

Used internally for distance-based position filtering.

```kotlin
// commonMain/kotlin/org/traccar/kmp/DistanceCalculator.kt
package org.traccar.kmp

import kotlin.math.*

internal object DistanceCalculator {
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
        return EQUATORIAL_EARTH_RADIUS * c * 1000.0
    }
}
```

### 2.7 PlatformLocationProvider.kt - Interface

The abstraction for native GPS. Each platform implements this. The native code does NOT send data to a server - it only calls the `onLocation` callback.

```kotlin
// commonMain/kotlin/org/traccar/kmp/PlatformLocationProvider.kt
package org.traccar.kmp

/**
 * Platform-specific GPS location provider.
 * Implementations ONLY collect GPS data and call onLocation/onError.
 * They do NOT send data to any server or store it.
 */
interface PlatformLocationProvider {
    /**
     * Start receiving GPS updates.
     * @param config GPS configuration (accuracy, interval hints)
     * @param onLocation called with each raw GPS position (before filtering)
     * @param onError called when location provider encounters an error
     */
    fun startUpdates(
        config: GpsConfig,
        onLocation: (Position) -> Unit,
        onError: (String) -> Unit
    )

    /** Stop receiving GPS updates and release resources */
    fun stopUpdates()

    /**
     * Request a single immediate location reading.
     * @param config GPS configuration
     * @param onLocation called with the position
     */
    fun requestSingleLocation(
        config: GpsConfig,
        onLocation: (Position) -> Unit
    )
}
```

### 2.8 PlatformBatteryProvider.kt - Interface

```kotlin
// commonMain/kotlin/org/traccar/kmp/PlatformBatteryProvider.kt
package org.traccar.kmp

/** Platform-specific battery info reader */
interface PlatformBatteryProvider {
    fun getBatteryStatus(): BatteryStatus
}
```

### 2.9 GpsTracker.kt - Main Entry Point

This is the **core class** consumers interact with. It:
1. Receives raw GPS data from the native `PlatformLocationProvider`
2. Applies interval/distance/angle filtering (shared logic from both Traccar clients)
3. Notifies the `GpsTrackerListener` with filtered positions
4. Does NOT send anything to any server

```kotlin
// commonMain/kotlin/org/traccar/kmp/GpsTracker.kt
package org.traccar.kmp

import kotlin.math.abs

/**
 * Main GPS background listener.
 *
 * Usage:
 *   val tracker = GpsTracker(locationProvider, listener)
 *   tracker.start(config)   // start listening with dynamic config
 *   tracker.stop()          // stop listening
 *   tracker.updateConfig(newConfig)  // change config at runtime
 *
 * The listener receives filtered Position objects.
 * What you do with them (send to server, store, display) is up to you.
 */
class GpsTracker(
    private val locationProvider: PlatformLocationProvider,
    private val listener: GpsTrackerListener
) {
    private var config: GpsConfig? = null
    private var lastPosition: Position? = null
    private var isRunning = false

    /** Start GPS listening with the given configuration */
    fun start(config: GpsConfig) {
        if (isRunning) stop()

        this.config = config
        this.lastPosition = null
        this.isRunning = true

        locationProvider.startUpdates(
            config = config,
            onLocation = { position ->
                handleRawPosition(position)
            },
            onError = { error ->
                listener.onError(error)
            }
        )

        listener.onStatusChange(TrackerStatus.STARTED)
    }

    /** Stop GPS listening and release resources */
    fun stop() {
        if (!isRunning) return
        isRunning = false
        locationProvider.stopUpdates()
        listener.onStatusChange(TrackerStatus.STOPPED)
    }

    /** Update configuration at runtime without restarting */
    fun updateConfig(newConfig: GpsConfig) {
        val wasRunning = isRunning
        if (wasRunning) {
            locationProvider.stopUpdates()
        }
        this.config = newConfig
        if (wasRunning) {
            locationProvider.startUpdates(
                config = newConfig,
                onLocation = { position -> handleRawPosition(position) },
                onError = { error -> listener.onError(error) }
            )
        }
    }

    /** Request a single immediate location reading */
    fun requestSingleLocation() {
        val cfg = config ?: return
        locationProvider.requestSingleLocation(cfg) { position ->
            listener.onPositionUpdate(position)
        }
    }

    /** Check if tracker is currently running */
    fun isTracking(): Boolean = isRunning

    /** Get the current configuration (null if never started) */
    fun currentConfig(): GpsConfig? = config

    // --- Internal: Position Filtering ---
    // Source: PositionProvider.processLocation (Android) and
    //         PositionProvider.locationManager:didUpdateLocations (iOS)
    // Both platforms use identical filtering logic.

    private fun handleRawPosition(position: Position) {
        val cfg = config ?: return
        if (!isRunning) return

        if (shouldAcceptPosition(position, cfg)) {
            lastPosition = position
            listener.onPositionUpdate(position)
        }
    }

    private fun shouldAcceptPosition(position: Position, config: GpsConfig): Boolean {
        val last = lastPosition ?: return true  // first position always accepted

        // Time filter: accept if enough time has passed
        val elapsedSeconds = position.time.epochSeconds - last.time.epochSeconds
        if (elapsedSeconds >= config.interval) return true

        // Distance filter: accept if moved far enough (0 = disabled)
        if (config.distance > 0) {
            val dist = DistanceCalculator.distance(
                last.latitude, last.longitude,
                position.latitude, position.longitude
            )
            if (dist >= config.distance) return true
        }

        // Angle filter: accept if bearing changed enough (0 = disabled)
        if (config.angle > 0) {
            if (abs(position.course - last.course) >= config.angle) return true
        }

        return false  // filtered out
    }
}
```

---

## Step 3: Android Implementation (androidMain)

### 3.1 AndroidLocationProvider.kt

**Source:** `AndroidPositionProvider.kt` + `PositionProvider.kt` from Traccar Android

The Android implementation uses `LocationManager` to receive GPS updates and notifies the KMP callback. It does NOT send data anywhere.

```kotlin
// androidMain/kotlin/org/traccar/kmp/AndroidLocationProvider.kt
package org.traccar.kmp

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import kotlinx.datetime.Clock

/**
 * Android GPS location provider.
 * Only collects GPS data and notifies the KMP callback.
 * Does NOT send data to any server.
 *
 * @param context Android Context (Activity or Service)
 */
class AndroidLocationProvider(
    private val context: Context
) : PlatformLocationProvider {

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val batteryProvider = AndroidBatteryProvider(context)
    private var locationListener: LocationListener? = null

    companion object {
        private const val MINIMUM_INTERVAL_MS = 1000L
    }

    @SuppressLint("MissingPermission")
    override fun startUpdates(
        config: GpsConfig,
        onLocation: (Position) -> Unit,
        onError: (String) -> Unit
    ) {
        val provider = when (config.accuracy) {
            LocationAccuracy.HIGH -> LocationManager.GPS_PROVIDER
            LocationAccuracy.LOW -> LocationManager.PASSIVE_PROVIDER
            LocationAccuracy.MEDIUM -> LocationManager.NETWORK_PROVIDER
        }

        // If distance/angle filtering is active, request updates more frequently
        // and let GpsTracker do the filtering. Otherwise use interval directly.
        val minTimeMs = if (config.distance > 0 || config.angle > 0) {
            MINIMUM_INTERVAL_MS
        } else {
            config.interval * 1000
        }

        locationListener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val battery = batteryProvider.getBatteryStatus()
                val position = location.toPosition(config.deviceId, battery)
                onLocation(position)  // notify KMP listener, nothing else
            }
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {
                onError("Location provider disabled: $provider")
            }
        }

        try {
            locationManager.requestLocationUpdates(
                provider, minTimeMs, 0f, locationListener!!
            )
        } catch (e: SecurityException) {
            onError("Location permission not granted: ${e.message}")
        } catch (e: Exception) {
            onError("Location provider error: ${e.message}")
        }
    }

    override fun stopUpdates() {
        locationListener?.let {
            locationManager.removeUpdates(it)
        }
        locationListener = null
    }

    @SuppressLint("MissingPermission")
    override fun requestSingleLocation(
        config: GpsConfig,
        onLocation: (Position) -> Unit
    ) {
        try {
            val lastLocation =
                locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
                    ?: locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            if (lastLocation != null) {
                val battery = batteryProvider.getBatteryStatus()
                onLocation(lastLocation.toPosition(config.deviceId, battery))
            }
        } catch (e: SecurityException) {
            // permission not granted, silently ignore for single request
        }
    }

    // Convert Android Location -> KMP Position
    private fun Location.toPosition(deviceId: String, battery: BatteryStatus): Position {
        return Position(
            deviceId = deviceId,
            time = Clock.System.now(),
            latitude = latitude,
            longitude = longitude,
            altitude = altitude,
            speed = if (hasSpeed()) speed * 1.943844 else 0.0,  // m/s -> knots
            course = if (hasBearing()) bearing.toDouble() else 0.0,
            accuracy = if (hasAccuracy()) accuracy.toDouble() else 0.0,
            battery = battery,
            mock = isMockLocation()
        )
    }

    private fun Location.isMockLocation(): Boolean {
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

### 3.2 AndroidBatteryProvider.kt

**Source:** `PositionProvider.kt` method `getBatteryStatus()` from Traccar Android

```kotlin
// androidMain/kotlin/org/traccar/kmp/AndroidBatteryProvider.kt
package org.traccar.kmp

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * Android battery status reader.
 */
class AndroidBatteryProvider(
    private val context: Context
) : PlatformBatteryProvider {

    override fun getBatteryStatus(): BatteryStatus {
        val batteryIntent = context.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
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

> **That's it for Android.** Only 2 files. No database, no HTTP client, no network monitor.

---

## Step 4: iOS Implementation (iosMain)

> iOS implementations use Kotlin/Native interop with Apple frameworks.

### 4.1 IosLocationProvider.kt

**Source:** `PositionProvider.swift` + `TrackingController.swift` from Traccar iOS

The iOS implementation uses `CLLocationManager` with background updates + significant location changes. It only notifies the KMP callback.

```kotlin
// iosMain/kotlin/org/traccar/kmp/IosLocationProvider.kt
package org.traccar.kmp

import kotlinx.datetime.toKotlinInstant
import platform.CoreLocation.*
import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceBatteryStateCharging
import platform.UIKit.UIDeviceBatteryStateFull
import platform.darwin.NSObject

/**
 * iOS GPS location provider.
 * Only collects GPS data and notifies the KMP callback.
 * Does NOT send data to any server.
 *
 * Uses CLLocationManager + significant location changes for reliable background tracking.
 */
class IosLocationProvider : PlatformLocationProvider {

    private var locationManager: CLLocationManager? = null
    private var delegate: LocationDelegate? = null

    override fun startUpdates(
        config: GpsConfig,
        onLocation: (Position) -> Unit,
        onError: (String) -> Unit
    ) {
        val manager = CLLocationManager()
        locationManager = manager

        // Configure accuracy
        manager.desiredAccuracy = when (config.accuracy) {
            LocationAccuracy.HIGH -> kCLLocationAccuracyBest
            LocationAccuracy.MEDIUM -> kCLLocationAccuracyHundredMeters
            LocationAccuracy.LOW -> kCLLocationAccuracyKilometer
        }

        // Critical for background tracking
        manager.pausesLocationUpdatesAutomatically = false
        manager.allowsBackgroundLocationUpdates = true

        // Set up delegate (only notifies KMP callback)
        delegate = LocationDelegate(config.deviceId, onLocation, onError)
        manager.delegate = delegate

        // Request always-on permission
        manager.requestAlwaysAuthorization()

        // Start continuous + significant location monitoring
        manager.startUpdatingLocation()
        manager.startMonitoringSignificantLocationChanges()
    }

    override fun stopUpdates() {
        locationManager?.stopUpdatingLocation()
        locationManager?.stopMonitoringSignificantLocationChanges()
        locationManager?.delegate = null
        locationManager = null
        delegate = null
    }

    override fun requestSingleLocation(
        config: GpsConfig,
        onLocation: (Position) -> Unit
    ) {
        locationManager?.location?.let { location ->
            val battery = IosBatteryProvider().getBatteryStatus()
            onLocation(location.toPosition(config.deviceId, battery))
        }
    }

    /**
     * CLLocationManager delegate.
     * Receives raw GPS updates from iOS and forwards to KMP callback.
     * Does NOT send data to any server.
     */
    private class LocationDelegate(
        private val deviceId: String,
        private val onLocation: (Position) -> Unit,
        private val onError: (String) -> Unit
    ) : NSObject(), CLLocationManagerDelegateProtocol {

        private val batteryProvider = IosBatteryProvider()

        override fun locationManager(
            manager: CLLocationManager,
            didUpdateLocations: List<*>
        ) {
            for (loc in didUpdateLocations) {
                val location = loc as? CLLocation ?: continue
                val battery = batteryProvider.getBatteryStatus()
                val position = location.toPosition(deviceId, battery)
                onLocation(position)  // notify KMP listener, nothing else
            }
        }

        override fun locationManager(
            manager: CLLocationManager,
            didFailWithError: platform.Foundation.NSError
        ) {
            onError("Location error: ${didFailWithError.localizedDescription}")
        }

        override fun locationManager(
            manager: CLLocationManager,
            didChangeAuthorizationStatus: Int
        ) {
            // Re-start updates if authorization was granted
            if (didChangeAuthorizationStatus == kCLAuthorizationStatusAuthorizedAlways ||
                didChangeAuthorizationStatus == kCLAuthorizationStatusAuthorizedWhenInUse
            ) {
                manager.startUpdatingLocation()
            }
        }
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
        speed = if (speed >= 0) speed * 1.943844 else 0.0,  // m/s -> knots
        course = if (course >= 0) course else 0.0,
        accuracy = horizontalAccuracy,
        battery = battery,
        mock = false  // iOS doesn't expose mock location
    )
}
```

### 4.2 IosBatteryProvider.kt

**Source:** `PositionProvider.swift` method `getBatteryStatus()` from Traccar iOS

```kotlin
// iosMain/kotlin/org/traccar/kmp/IosBatteryProvider.kt
package org.traccar.kmp

import platform.UIKit.UIDevice
import platform.UIKit.UIDeviceBatteryStateCharging
import platform.UIKit.UIDeviceBatteryStateFull

/**
 * iOS battery status reader using UIDevice.
 */
class IosBatteryProvider : PlatformBatteryProvider {

    override fun getBatteryStatus(): BatteryStatus {
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

> **That's it for iOS.** Only 2 files. No database, no HTTP client, no network monitor.

---

## Step 5: Platform Configuration & Permissions

### 5.1 Android - What the Consuming App Must Do

#### AndroidManifest.xml (consumer's app)

```xml
<!-- Required permissions -->
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

<!-- Only if consumer sends positions to a server -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

#### Foreground Service (consumer's responsibility)

```kotlin
// In the CONSUMER app, NOT in the library
class MyTrackingService : Service() {
    private lateinit var gpsTracker: GpsTracker

    override fun onCreate() {
        super.onCreate()

        // 1. Create platform provider (passes Context)
        val locationProvider = AndroidLocationProvider(this)

        // 2. Create tracker with YOUR listener
        gpsTracker = GpsTracker(
            locationProvider = locationProvider,
            listener = object : GpsTrackerListener {
                override fun onPositionUpdate(position: Position) {
                    // YOU decide what to do:
                    // - Send to your server via HTTP/WebSocket
                    // - Store in your local database
                    // - Update your UI
                    // - Anything you want
                    sendToMyServer(position)
                    saveToMyDatabase(position)
                }
                override fun onError(error: String) {
                    Log.e("GPS", error)
                }
                override fun onStatusChange(status: TrackerStatus) {
                    Log.d("GPS", "Tracker: $status")
                }
            }
        )

        // 3. Start foreground notification (YOUR responsibility)
        startForeground(1, createNotification())

        // 4. Start tracking with dynamic config
        gpsTracker.start(GpsConfig(
            deviceId = "my-device-123",
            interval = 300,
            accuracy = LocationAccuracy.HIGH
        ))
    }

    // Change config at runtime without restarting
    fun changeInterval(newInterval: Long) {
        gpsTracker.updateConfig(
            gpsTracker.currentConfig()!!.copy(interval = newInterval)
        )
    }

    override fun onDestroy() {
        gpsTracker.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
```

### 5.2 iOS - What the Consuming App Must Do

#### Info.plist (consumer's app)

```xml
<!-- Background modes - REQUIRED for background GPS -->
<key>UIBackgroundModes</key>
<array>
    <string>location</string>
</array>

<!-- Location permission strings - REQUIRED -->
<key>NSLocationAlwaysAndWhenInUseUsageDescription</key>
<string>Required for background GPS tracking</string>
<key>NSLocationAlwaysUsageDescription</key>
<string>Required for background GPS tracking</string>
<key>NSLocationWhenInUseUsageDescription</key>
<string>Required for GPS tracking</string>
```

#### Swift Integration (consumer's app)

```swift
import KmpGpsListener  // Your KMP framework name

class AppDelegate: UIResponder, UIApplicationDelegate {
    var gpsTracker: GpsTracker?

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: ...) -> Bool {

        UIDevice.current.isBatteryMonitoringEnabled = true

        // 1. Create platform provider (no Context needed on iOS)
        let locationProvider = IosLocationProvider()

        // 2. Create listener
        let listener = MyGpsListener()

        // 3. Create tracker
        let tracker = GpsTracker(
            locationProvider: locationProvider,
            listener: listener
        )

        // 4. Start with dynamic config
        tracker.start(config: GpsConfig(
            deviceId: "my-device-123",
            interval: 300,
            distance: 0.0,
            angle: 0.0,
            accuracy: .medium
        ))

        gpsTracker = tracker
        return true
    }
}

// Your listener - YOU decide what to do with positions
class MyGpsListener: GpsTrackerListener {
    func onPositionUpdate(position: Position) {
        // Send to your server, store locally, show on map, etc.
        sendToMyServer(position)
    }

    func onError(error: String) {
        print("GPS Error: \(error)")
    }

    func onStatusChange(status: TrackerStatus) {
        print("Tracker: \(status)")
    }
}
```

---

## Step 6: Consumer Integration Examples

### 6.1 Consumer Sends to Traccar Server

If the consumer wants to replicate the original Traccar behavior, they handle it in their listener:

```kotlin
// In consumer app - NOT in the library
class TraccarServerSender : GpsTrackerListener {

    override fun onPositionUpdate(position: Position) {
        // Consumer formats the URL (was ProtocolFormatter in old lib)
        val url = buildString {
            append("http://myserver:5055")
            append("?id=${position.deviceId}")
            append("&timestamp=${position.time.epochSeconds}")
            append("&lat=${position.latitude}")
            append("&lon=${position.longitude}")
            append("&speed=${position.speed}")
            append("&bearing=${position.course}")
            append("&altitude=${position.altitude}")
            append("&accuracy=${position.accuracy}")
            append("&batt=${position.battery.level}")
            if (position.battery.charging) append("&charge=true")
        }

        // Consumer sends HTTP request (was RequestManager in old lib)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.connectTimeout = 15000
                connection.inputStream.bufferedReader().readText()
                connection.disconnect()
            } catch (e: Exception) {
                // Consumer handles retry, buffering, etc.
            }
        }
    }

    override fun onError(error: String) { /* ... */ }
    override fun onStatusChange(status: TrackerStatus) { /* ... */ }
}
```

### 6.2 Consumer Stores in Local Database

```kotlin
// In consumer app
class DatabaseSaver(private val db: MyDatabase) : GpsTrackerListener {

    override fun onPositionUpdate(position: Position) {
        db.insertPosition(
            deviceId = position.deviceId,
            lat = position.latitude,
            lon = position.longitude,
            time = position.time.toEpochMilliseconds(),
            speed = position.speed,
            battery = position.battery.level
        )
    }

    override fun onError(error: String) { /* ... */ }
    override fun onStatusChange(status: TrackerStatus) { /* ... */ }
}
```

### 6.3 Consumer with Multiple Listeners (Compose Pattern)

```kotlin
// Combine multiple behaviors
class CompositeListener(
    private val listeners: List<GpsTrackerListener>
) : GpsTrackerListener {
    override fun onPositionUpdate(position: Position) {
        listeners.forEach { it.onPositionUpdate(position) }
    }
    override fun onError(error: String) {
        listeners.forEach { it.onError(error) }
    }
    override fun onStatusChange(status: TrackerStatus) {
        listeners.forEach { it.onStatusChange(status) }
    }
}

// Usage:
val tracker = GpsTracker(
    locationProvider = AndroidLocationProvider(context),
    listener = CompositeListener(listOf(
        TraccarServerSender(),
        DatabaseSaver(db),
        MapUpdater(mapView),
        AnalyticsLogger()
    ))
)
```

### 6.4 Dynamic Config Changes at Runtime

```kotlin
// Change interval at runtime
tracker.updateConfig(tracker.currentConfig()!!.copy(interval = 60))

// Change accuracy at runtime
tracker.updateConfig(tracker.currentConfig()!!.copy(accuracy = LocationAccuracy.HIGH))

// Change device ID at runtime
tracker.updateConfig(tracker.currentConfig()!!.copy(deviceId = "new-device-456"))

// Check current state
if (tracker.isTracking()) {
    val config = tracker.currentConfig()
    println("Tracking with interval=${config?.interval}s")
}
```

---

## Step 7: Testing

### 7.1 Common Tests (Pure Kotlin)

```kotlin
// commonTest/kotlin/org/traccar/kmp/DistanceCalculatorTest.kt
package org.traccar.kmp

import kotlin.test.Test
import kotlin.test.assertTrue

class DistanceCalculatorTest {
    @Test
    fun testDistance() {
        // NYC to LA ~ 3,944 km
        val dist = DistanceCalculator.distance(40.7128, -74.0060, 34.0522, -118.2437)
        assertTrue(dist > 3_900_000 && dist < 4_000_000)
    }

    @Test
    fun testZeroDistance() {
        val dist = DistanceCalculator.distance(40.0, -74.0, 40.0, -74.0)
        assertTrue(dist < 1.0) // should be ~0
    }
}
```

```kotlin
// commonTest/kotlin/org/traccar/kmp/GpsTrackerFilterTest.kt
package org.traccar.kmp

import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class GpsTrackerFilterTest {

    private class CollectingListener : GpsTrackerListener {
        val positions = mutableListOf<Position>()
        override fun onPositionUpdate(position: Position) { positions.add(position) }
        override fun onError(error: String) {}
        override fun onStatusChange(status: TrackerStatus) {}
    }

    private class FakeLocationProvider : PlatformLocationProvider {
        var onLocation: ((Position) -> Unit)? = null
        override fun startUpdates(config: GpsConfig, onLocation: (Position) -> Unit, onError: (String) -> Unit) {
            this.onLocation = onLocation
        }
        override fun stopUpdates() { onLocation = null }
        override fun requestSingleLocation(config: GpsConfig, onLocation: (Position) -> Unit) {}

        fun emit(position: Position) { onLocation?.invoke(position) }
    }

    @Test
    fun testFirstPositionAlwaysAccepted() {
        val provider = FakeLocationProvider()
        val listener = CollectingListener()
        val tracker = GpsTracker(provider, listener)

        tracker.start(GpsConfig(deviceId = "test", interval = 300))

        provider.emit(Position(deviceId = "test", time = Instant.fromEpochSeconds(1000)))
        assertEquals(1, listener.positions.size)
    }

    @Test
    fun testPositionFilteredByInterval() {
        val provider = FakeLocationProvider()
        val listener = CollectingListener()
        val tracker = GpsTracker(provider, listener)

        tracker.start(GpsConfig(deviceId = "test", interval = 300))

        // First: accepted
        provider.emit(Position(deviceId = "test", time = Instant.fromEpochSeconds(1000)))
        // Second: only 10s later, filtered out
        provider.emit(Position(deviceId = "test", time = Instant.fromEpochSeconds(1010)))
        // Third: 300s later, accepted
        provider.emit(Position(deviceId = "test", time = Instant.fromEpochSeconds(1300)))

        assertEquals(2, listener.positions.size)
    }

    @Test
    fun testPositionAcceptedByDistance() {
        val provider = FakeLocationProvider()
        val listener = CollectingListener()
        val tracker = GpsTracker(provider, listener)

        tracker.start(GpsConfig(deviceId = "test", interval = 99999, distance = 100.0))

        // First: accepted
        provider.emit(Position(deviceId = "test", time = Instant.fromEpochSeconds(1000),
            latitude = 40.0, longitude = -74.0))
        // Second: too close (same location), filtered
        provider.emit(Position(deviceId = "test", time = Instant.fromEpochSeconds(1005),
            latitude = 40.0, longitude = -74.0))
        // Third: far enough (~111km), accepted
        provider.emit(Position(deviceId = "test", time = Instant.fromEpochSeconds(1010),
            latitude = 41.0, longitude = -74.0))

        assertEquals(2, listener.positions.size)
    }
}
```

### 7.2 Testing on Real Devices

GPS background tracking **cannot be fully tested in simulators**. You must test on real devices for:
- Background location updates
- Wake lock behavior (Android)
- Significant location changes (iOS)
- Battery impact

---

## API Reference

### Public API Surface

| Class | Type | Description |
|-------|------|-------------|
| `GpsTracker` | Class | Main entry point. `start(config)`, `stop()`, `updateConfig()`, `requestSingleLocation()` |
| `GpsConfig` | Data class | Dynamic configuration: deviceId, interval, distance, angle, accuracy |
| `GpsTrackerListener` | Interface | Callbacks: `onPositionUpdate()`, `onError()`, `onStatusChange()` |
| `Position` | Data class | GPS location with battery info embedded |
| `BatteryStatus` | Data class | Battery level and charging state |
| `LocationAccuracy` | Enum | HIGH, MEDIUM, LOW |
| `TrackerStatus` | Enum | STARTED, STOPPED |
| `PlatformLocationProvider` | Interface | Implement for custom GPS source |
| `PlatformBatteryProvider` | Interface | Implement for custom battery source |

### GpsTracker Methods

| Method | Description |
|--------|-------------|
| `start(config)` | Start GPS listening with dynamic configuration |
| `stop()` | Stop GPS listening and release resources |
| `updateConfig(config)` | Change configuration at runtime (restarts provider) |
| `requestSingleLocation()` | Get one immediate position reading |
| `isTracking()` | Check if currently listening |
| `currentConfig()` | Get current configuration (null if never started) |

### Platform Implementations

| Platform | Location Provider | Battery Provider |
|----------|-------------------|------------------|
| Android | `AndroidLocationProvider(context)` | `AndroidBatteryProvider(context)` |
| iOS | `IosLocationProvider()` | `IosBatteryProvider()` |

---

## Component Mapping Table

| Original Traccar Component | Android Source | iOS Source | KMP Library | Consumer's Responsibility |
|---|---|---|---|---|
| **GPS listening** | `AndroidPositionProvider.kt` | `PositionProvider.swift` | `AndroidLocationProvider` / `IosLocationProvider` | -- |
| **Position filtering** | `PositionProvider.processLocation()` | `PositionProvider.didUpdateLocations()` | `GpsTracker.shouldAcceptPosition()` | -- |
| **Battery reading** | `PositionProvider.getBatteryStatus()` | `PositionProvider.getBatteryStatus()` | `AndroidBatteryProvider` / `IosBatteryProvider` | -- |
| **Distance calc** | `Location.distanceTo()` | `DistanceCalculator.swift` | `DistanceCalculator.kt` | -- |
| **Position data** | `Position.kt` | `Position.swift` | `Position.kt` | -- |
| **Config** | `MainFragment` (SharedPrefs keys) | `Root.plist` (UserDefaults keys) | `GpsConfig` (dynamic, no persistence) | Store/load config however you want |
| **URL formatting** | `ProtocolFormatter.kt` | `ProtocolFormatter.swift` | -- | Format URLs in your listener |
| **HTTP sending** | `RequestManager.kt` | `RequestManager.swift` | -- | Send HTTP in your listener |
| **Position buffering** | `DatabaseHelper.kt` (SQLite) | `DatabaseHelper.swift` (CoreData) | -- | Buffer in your own DB |
| **Network monitoring** | `NetworkManager.kt` | `NetworkManager.swift` | -- | Monitor network yourself |
| **Retry logic** | `TrackingController.retry()` | `TrackingController.retry()` | -- | Implement retry yourself |
| **State machine** | `TrackingController.kt` (write/read/send/delete) | `TrackingController.swift` (write/read/send/delete) | -- | Implement in your listener |
| **Foreground service** | `TrackingService.kt` | N/A (iOS background mode) | -- | Create your own service |
| **Boot receiver** | `AutostartReceiver.kt` | N/A | -- | Create your own receiver |
| **Wake lock** | `TrackingService.kt` | N/A | -- | Manage wake locks yourself |
| **Permissions** | `MainFragment.kt` | `Info.plist` | -- | Request permissions yourself |
| **UI** | `MainFragment`, `StatusActivity` | `MainViewController` | -- | Build your own UI |

---

## Critical Notes & Gotchas

### 1. Interface Pattern (No expect/actual Constructor Mismatch)

The library uses **interfaces** (`PlatformLocationProvider`, `PlatformBatteryProvider`) instead of `expect/actual` classes. This avoids the constructor parameter mismatch problem:

- Android needs `Context` in constructors
- iOS needs no constructor parameters

With interfaces, the **consumer** creates the platform-specific instance and passes it to `GpsTracker`. The library's common code only knows about the interface.

### 2. iOS Background Location - Critical Settings

The iOS consumer app **must** configure these or GPS stops in background:
- `UIBackgroundModes: location` in Info.plist
- The library sets `pausesLocationUpdatesAutomatically = false` internally
- The library sets `allowsBackgroundLocationUpdates = true` internally
- The library calls `requestAlwaysAuthorization()` internally
- The library calls `startMonitoringSignificantLocationChanges()` internally

### 3. Android Foreground Service - Required

The library does NOT manage Android foreground services. The consumer **must**:
- Create a foreground service with a persistent notification
- Create `AndroidLocationProvider(serviceContext)` inside the service
- Handle `FOREGROUND_SERVICE_LOCATION` type (Android 14+)

### 4. Speed Conversion

Both platforms convert `m/s -> knots (* 1.943844)` in the platform providers. The `Position.speed` is always in knots.

### 5. Filtering Happens in KMP

Position filtering (interval/distance/angle) is done in `GpsTracker` (commonMain), NOT in native code. Native code sends ALL raw locations to KMP. This ensures filtering logic is shared and testable.

### 6. No Coroutines Required

The library is purely callback-based. No `suspend` functions, no `Flow`, no coroutines dependency. This keeps the library lightweight and avoids Kotlin/Native coroutine complexities.

### 7. Consumer Controls Everything

| Concern | Who handles it? |
|---------|-----------------|
| GPS listening | Library |
| Position filtering | Library |
| Battery reading | Library |
| Sending to server | Consumer |
| Storing positions | Consumer |
| Network monitoring | Consumer |
| Retry on failure | Consumer |
| Foreground service | Consumer |
| Permissions | Consumer |
| Config persistence | Consumer |
| UI | Consumer |

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

1. **commonMain first** - `Position`, `BatteryStatus`, `GpsConfig`, `LocationAccuracy`, `TrackerStatus`
2. **Interfaces** - `PlatformLocationProvider`, `PlatformBatteryProvider`, `GpsTrackerListener`
3. **Core logic** - `DistanceCalculator`, `GpsTracker` (with filtering)
4. **Common tests** - `DistanceCalculatorTest`, `GpsTrackerFilterTest` (using fake provider)
5. **androidMain** - `AndroidLocationProvider`, `AndroidBatteryProvider` (2 files only)
6. **iosMain** - `IosLocationProvider`, `IosBatteryProvider` (2 files only)
7. **Sample Android app** - Foreground service + listener that logs positions
8. **Sample iOS app** - AppDelegate + listener that logs positions
9. **Publish** - Maven Central / XCFramework