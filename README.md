# Yedu KMP GPS Listener

A **headless** (no UI) Kotlin Multiplatform library that provides background GPS location listening on Android and iOS. Ported from the Traccar GPS tracking clients for both platforms.

## Features

- Background GPS location listening on Android and iOS
- Position filtering by interval, distance, and angle
- Battery status reporting alongside each position
- **Realtime position sending** to server when online
- **Network monitoring** — only sends when connected
- **TrackingController** — GPS + network monitoring + HTTP sending pipeline
- **ProtocolFormatter** - OsmAnd/Traccar-compatible URL formatting
- **Location permission helpers** - check, request, and open settings (cross-platform)
- Callback-based API (no coroutines dependency)
- All configuration passed dynamically at runtime

## Installation

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation("io.github.saggeldi:yedu-kmp-gps-listener:0.0.4")
}
```

### Gradle (Groovy)

```groovy
dependencies {
    implementation 'io.github.saggeldi:yedu-kmp-gps-listener:0.0.4'
}
```

## Initialization

### Common One-Place Init (via `GpsFactory`)

Use `GpsFactory` to create everything from shared common code. Android requires a one-time `initialize(context)` call; iOS works out of the box.

**Android - call once in Application or Service:**
```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        GpsFactory.initialize(this)
    }
}
```

**Then from common code (works on both platforms):**
```kotlin
// GPS-only mode
val tracker = GpsFactory.createGpsTracker(myListener)
tracker.start(config)

// Full pipeline mode (GPS + network monitoring + sending)
val controller = GpsFactory.createTrackingController(
    serverUrl = "https://your-server.com:5055",
    listener = myControllerListener
)
controller.start(config)
```

### Separate Platform Init (manual)

For full control, create platform-specific components directly:

```kotlin
// Android
val tracker = GpsTracker(AndroidLocationProvider(context), listener)

// iOS (Swift)
let tracker = GpsTracker(locationProvider: IosLocationProvider(), listener: listener)
```

Both modes can coexist. Use `GpsFactory` for convenience, or construct manually when you need custom implementations.

---

## Two API Levels

1. **`GpsTracker`** - GPS-only listener. You receive positions and decide what to do with them.
2. **`TrackingController`** - Full pipeline. GPS + network monitoring + realtime HTTP sending. Sends latest position when online, drops when offline.

---

## Mode 1: GpsTracker (GPS-only)

Use this when you want full control over what happens with positions.

### Android

```kotlin
class MyTrackingService : Service() {
    private lateinit var gpsTracker: GpsTracker

    override fun onCreate() {
        super.onCreate()
        startForeground(1, createNotification())

        // Option A: Common factory (requires GpsFactory.initialize(context) in Application)
        gpsTracker = GpsFactory.createGpsTracker(myListener)

        // Option B: Direct platform constructor
        // gpsTracker = GpsTracker(AndroidLocationProvider(this), myListener)

        gpsTracker.start(GpsConfig(
            deviceId = "my-device-123",
            interval = 300,
            accuracy = LocationAccuracy.HIGH
        ))
    }

    private val myListener = object : GpsTrackerListener {
        override fun onPositionUpdate(position: Position) {
            println("${position.latitude}, ${position.longitude}")
            println("Battery: ${position.battery.level}%")
        }
        override fun onError(error: String) {
            Log.e("GPS", error)
        }
        override fun onStatusChange(status: TrackerStatus) {
            Log.d("GPS", "Tracker: $status")
        }
    }

    override fun onDestroy() {
        gpsTracker.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
```

### iOS (Swift)

```swift
import YeduKmpGpsListener

class AppDelegate: UIResponder, UIApplicationDelegate {
    var gpsTracker: GpsTracker?

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {

        UIDevice.current.isBatteryMonitoringEnabled = true

        let tracker = GpsTracker(
            locationProvider: IosLocationProvider(),
            listener: MyGpsListener()
        )

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

class MyGpsListener: GpsTrackerListener {
    func onPositionUpdate(position: Position) {
        // Handle position however you want
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

## Mode 2: TrackingController (Full Pipeline)

Use this when you want automatic realtime position sending with network awareness.

When online, positions are sent immediately to the server via HTTP. When offline, positions are dropped (no local caching).

### Android

```kotlin
class MyTrackingService : Service() {
    private lateinit var controller: TrackingController
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(1, createNotification())

        // Acquire wake lock for reliable background tracking
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyApp::GPS")
        wakeLock?.acquire()

        // Option A: Common factory (requires GpsFactory.initialize(context) in Application)
        controller = GpsFactory.createTrackingController(
            serverUrl = "https://your-server.com:5055",
            listener = controllerListener
        )

        // Option B: Direct platform constructors
        // controller = TrackingController(
        //     locationProvider = AndroidLocationProvider(this),
        //     positionSender = AndroidPositionSender(),
        //     networkMonitor = AndroidNetworkMonitor(this),
        //     serverUrl = "https://your-server.com:5055",
        //     listener = controllerListener
        // )

        controller.start(GpsConfig(
            deviceId = "my-device-123",
            interval = 300,
            accuracy = LocationAccuracy.HIGH
        ))
    }

    private val controllerListener = object : TrackingControllerListener {
        override fun onPositionUpdate(position: Position) {
            Log.d("GPS", "New: ${position.latitude}, ${position.longitude}")
        }
        override fun onPositionSent(position: Position) {
            Log.d("GPS", "Sent to server")
        }
        override fun onSendFailed(position: Position) {
            Log.w("GPS", "Send failed")
        }
        override fun onError(error: String) {
            Log.e("GPS", error)
        }
        override fun onStatusChange(status: TrackerStatus) {
            Log.d("GPS", "Tracker: $status")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        controller.stop()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}
```

### iOS (Swift)

```swift
import YeduKmpGpsListener

class AppDelegate: UIResponder, UIApplicationDelegate {
    var controller: TrackingController?

    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {

        UIDevice.current.isBatteryMonitoringEnabled = true

        let ctrl = TrackingController(
            locationProvider: IosLocationProvider(),
            positionSender: IosPositionSender(),
            networkMonitor: IosNetworkMonitor(),
            serverUrl: "https://your-server.com:5055",
            listener: nil  // or provide a TrackingControllerListener
        )

        ctrl.start(config: GpsConfig(
            deviceId: "my-device-123",
            interval: 300,
            distance: 0.0,
            angle: 0.0,
            accuracy: .high
        ))

        controller = ctrl
        return true
    }
}
```

---

## Trip Tracking

`TripTracker` tracks distance and time for the current trip, per-status segments, and detects when the driver has stopped (GPS pause detection).

### Basic Trip Lifecycle

```kotlin
val tripTracker = TripTracker()

// Set a listener for stats updates and pause/resume events
tripTracker.setListener(object : TripTrackerListener {
    override fun onTripStatsUpdated(stats: TripStats) {
        println("Distance: ${stats.totalDistanceKm} km (${stats.totalDistanceMeters} m)")
        println("Time: ${stats.totalTimeSeconds}s (${stats.totalTimeMinutes} min)")
    }
    override fun onTripEnded(stats: TripStats) {
        println("Trip ended: ${stats.totalDistanceKm} km")
    }
    override fun onDriverPaused() {
        println("Driver has stopped")
    }
    override fun onDriverResumed() {
        println("Driver is moving again")
    }
})

// Start a trip with an ID and initial status
tripTracker.startTrip("trip-123", "accepted")

// Update status as the trip progresses
tripTracker.updateStatus("en_route")
tripTracker.updateStatus("arrived")
tripTracker.updateStatus("in_progress")

// Feed GPS positions (called from your GPS listener)
tripTracker.updatePosition(position)

// End the trip — triggers onTripEnded with final stats
tripTracker.endTrip()
```

### Per-Status Statistics

Every status change is tracked. Access historical stats for any status via `statusHistory`:

```kotlin
val stats = tripTracker.getTripStats()

// Get stats for a specific status
val enRouteStats = stats.statsForStatus("en_route")
if (enRouteStats != null) {
    println("en_route: ${enRouteStats.distanceKm} km, ${enRouteStats.timeMinutes} min")
}

// Iterate all statuses
for ((statusName, statusStats) in stats.statusHistory) {
    println("$statusName: ${statusStats.distanceMeters}m in ${statusStats.timeSeconds}s")
}
```

If a status is revisited (e.g., switching back to "driving" after "waiting"), the distance and time accumulate across all periods in that status.

### GPS Pause Detection

TripTracker detects when the driver's car has stopped by monitoring GPS position changes:

```kotlin
val tripTracker = TripTracker()

// Configure thresholds (defaults: 2m distance, 15s time)
tripTracker.pauseDistanceThresholdMeters = 5.0   // movement within 5m = "not moving"
tripTracker.pauseTimeThresholdSeconds = 20L       // must be stationary for 20s to trigger pause

// Access pause state in stats
val stats = tripTracker.getTripStats()
println("Currently paused: ${stats.isPaused}")
println("Total stopped time: ${stats.totalStoppingSeconds}s")
```

### Trip Tracking Types

| Type | Description |
|------|-------------|
| `TripTracker` | Main tracker class. Constructor accepts optional `timeProvider` for testing |
| `TripStats` | Trip statistics snapshot with totals, per-status history, and pause info |
| `StatusStats` | Per-status statistics: `distanceKm`, `distanceMeters`, `timeSeconds`, `timeMinutes` |
| `TripTrackerListener` | Callbacks: `onTripStatsUpdated()`, `onTripEnded()`, `onDriverPaused()`, `onDriverResumed()` |

---

## Runtime Configuration

```kotlin
// Update config without restarting
tracker.updateConfig(tracker.currentConfig()!!.copy(interval = 60))

// Change accuracy
tracker.updateConfig(tracker.currentConfig()!!.copy(accuracy = LocationAccuracy.HIGH))

// Request a single immediate position
tracker.requestSingleLocation()

// Check status
if (tracker.isTracking()) { /* ... */ }

// Stop
tracker.stop()
```

---

## API Reference

### Factory

| Type | Name | Description |
|------|------|-------------|
| Object | `GpsFactory` | `expect/actual` factory. Call `initialize(context)` on Android first |
| Extension | `GpsFactory.createGpsTracker(listener)` | Create GPS-only tracker from common code |
| Extension | `GpsFactory.createTrackingController(serverUrl, ...)` | Create full pipeline from common code |
| Method | `GpsFactory.createLocationPermissionHelper()` | Create permission/location-state helper |

### Core GPS

| Type | Name | Description |
|------|------|-------------|
| Class | `GpsTracker` | GPS-only listener. `start()`, `stop()`, `updateConfig()`, `requestSingleLocation()` |
| Data class | `GpsConfig` | Config: `deviceId`, `interval`, `distance`, `angle`, `accuracy` |
| Interface | `GpsTrackerListener` | Callbacks: `onPositionUpdate()`, `onError()`, `onStatusChange()` |
| Data class | `Position` | GPS position with battery info |
| Data class | `BatteryStatus` | Battery `level` (0-100) and `charging` state |
| Enum | `LocationAccuracy` | `HIGH`, `MEDIUM`, `LOW` |
| Enum | `TrackerStatus` | `STARTED`, `STOPPED` |
| Interface | `LocationPermissionHelper` | Check/request permissions, query GPS state, open settings |
| Enum | `PermissionStatus` | `GRANTED`, `DENIED`, `NOT_DETERMINED`, `RESTRICTED` |

### Full Pipeline

| Type | Name | Description |
|------|------|-------------|
| Class | `TrackingController` | Full pipeline: GPS + network monitoring + realtime sending |
| Interface | `TrackingControllerListener` | Callbacks: position events + send/fail events |
| Object | `ProtocolFormatter` | OsmAnd/Traccar URL formatting |
| Interface | `PositionSender` | HTTP position sending |
| Interface | `NetworkMonitor` | Network connectivity monitoring |

### Platform Implementations

| Component | Android | iOS |
|-----------|---------|-----|
| Location Provider | `AndroidLocationProvider(context)` | `IosLocationProvider()` |
| Battery Provider | `AndroidBatteryProvider(context)` | `IosBatteryProvider()` |
| Position Sender | `AndroidPositionSender()` | `IosPositionSender()` |
| Network Monitor | `AndroidNetworkMonitor(context)` | `IosNetworkMonitor()` |
| Permission Helper | `AndroidLocationPermissionHelper(context)` | `IosLocationPermissionHelper()` |

### Position Fields

| Field | Type | Description |
|-------|------|-------------|
| `id` | `Long` | Position ID (default 0) |
| `deviceId` | `String` | Device identifier |
| `time` | `Instant` | Timestamp (kotlinx-datetime) |
| `latitude` | `Double` | Latitude in degrees |
| `longitude` | `Double` | Longitude in degrees |
| `altitude` | `Double` | Altitude in meters |
| `speed` | `Double` | Speed in knots (converted from m/s) |
| `course` | `Double` | Bearing 0-360 degrees |
| `accuracy` | `Double` | Horizontal accuracy in meters |
| `battery` | `BatteryStatus` | Battery level and charging state |
| `mock` | `Boolean` | Mock location flag (Android only) |

### URL Format (ProtocolFormatter)

Positions are formatted as OsmAnd/Traccar-compatible URL query parameters:

```
https://server:5055?id=DEVICE_ID&timestamp=EPOCH&lat=LAT&lon=LON&speed=SPEED&bearing=COURSE&altitude=ALT&accuracy=ACC&batt=LEVEL&charge=true&mock=true
```

---

## Location Permission & GPS State

Use `LocationPermissionHelper` to check/request location permissions and query GPS state from common code.

### Check permissions and GPS state

```kotlin
val permissionHelper = GpsFactory.createLocationPermissionHelper()

// Check current permission status
when (permissionHelper.checkPermissionStatus()) {
    PermissionStatus.GRANTED        -> println("Permission granted")
    PermissionStatus.DENIED         -> println("Permission denied")
    PermissionStatus.NOT_DETERMINED -> println("Permission not yet requested")
    PermissionStatus.RESTRICTED     -> println("Permission restricted by policy")
}

// Check background permission specifically
if (permissionHelper.hasBackgroundPermission()) { /* always-on tracking OK */ }

// Check if device GPS is enabled
if (!permissionHelper.isLocationEnabled()) {
    permissionHelper.openLocationSettings()  // prompt user to enable GPS
}
```

### Request permission

```kotlin
permissionHelper.requestPermission(background = false) { status ->
    println("Result: $status")
}

// Request background (always) permission
permissionHelper.requestPermission(background = true) { status ->
    println("Background result: $status")
}
```

### Open settings

```kotlin
permissionHelper.openLocationSettings()  // device GPS settings
permissionHelper.openAppSettings()       // app permission settings
```

### Android-specific setup

On Android, permission requesting requires an Activity. Without calling `setActivity()`, `requestPermission()` returns the current status without showing a dialog.

```kotlin
// In your Activity
val helper = GpsFactory.createLocationPermissionHelper()
    as AndroidLocationPermissionHelper

helper.setActivity(this)

helper.requestPermission(background = true) { status ->
    // handle result
}

// Relay the result from your Activity
override fun onRequestPermissionsResult(
    requestCode: Int, permissions: Array<String>, grantResults: IntArray
) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    helper.onRequestPermissionsResult(requestCode, permissions, grantResults)
}

// Clear when Activity is destroyed
override fun onDestroy() {
    helper.clearActivity()
    super.onDestroy()
}
```

---

## Platform Setup (Consumer App)

### Android Permissions (AndroidManifest.xml)

```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

Register your service:

```xml
<service
    android:name=".MyTrackingService"
    android:foregroundServiceType="location"
    android:exported="false" />
```

### iOS Info.plist

```xml
<key>UIBackgroundModes</key>
<array>
    <string>location</string>
</array>

<key>NSLocationAlwaysAndWhenInUseUsageDescription</key>
<string>Required for background GPS tracking</string>
<key>NSLocationAlwaysUsageDescription</key>
<string>Required for background GPS tracking</string>
<key>NSLocationWhenInUseUsageDescription</key>
<string>Required for GPS tracking</string>
```

---

## Position Filtering

Filtering is done in shared Kotlin code (`GpsTracker.shouldAcceptPosition`), not in native. Raw GPS updates from the platform are filtered by:

1. **Time interval** - Accept if `interval` seconds have passed since last position
2. **Distance** - Accept if moved `distance` meters (0 = disabled)
3. **Angle** - Accept if bearing changed `angle` degrees (0 = disabled)

The first position is always accepted. If any condition is met, the position passes.

## Background Tracking Details

### Android
- Uses `LocationManager` with GPS/Network/Passive providers
- Consumer must create a foreground service with `FOREGROUND_SERVICE_LOCATION` type
- Consumer should acquire a `PARTIAL_WAKE_LOCK` for reliable tracking
- Mock location detection via `isMock` / `isFromMockProvider`
- Service should return `START_STICKY` to survive kills

### iOS
- Uses `CLLocationManager` with `allowsBackgroundLocationUpdates = true`
- `pausesLocationUpdatesAutomatically = false` for continuous tracking
- `startMonitoringSignificantLocationChanges()` for reliable background wake-ups
- `requestAlwaysAuthorization()` for background permission
- Consumer must add `location` to `UIBackgroundModes` in Info.plist
- Battery monitoring enabled via `UIDevice.currentDevice`

## Build Commands

```bash
# Build all targets
./gradlew :library:build

# Run all tests
./gradlew :library:allTests

# Run Android host tests only
./gradlew :library:testAndroidHostTest

# Run iOS simulator tests
./gradlew :library:iosSimulatorArm64Test

# Publish to Maven Central
./gradlew :library:publishToMavenCentral
```

## License

```
Copyright 2026 Shageldi Alyyew

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
