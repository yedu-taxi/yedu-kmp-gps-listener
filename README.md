# Yedu KMP GPS Listener

A **headless** (no UI) Kotlin Multiplatform library that provides background GPS location listening with offline caching on Android and iOS. Ported from the Traccar GPS tracking clients for both platforms.

## Features

- Background GPS location listening on Android and iOS
- Position filtering by interval, distance, and angle
- Battery status reporting alongside each position
- **Offline position caching** with SQLite (Android) / NSUserDefaults (iOS)
- **Automatic position sending** to server with retry logic
- **Network monitoring** with automatic send on reconnect
- **TrackingController** - full state machine (write -> read -> send -> delete -> retry)
- **ProtocolFormatter** - OsmAnd/Traccar-compatible URL formatting
- Callback-based API (no coroutines dependency)
- All configuration passed dynamically at runtime

## Installation

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation("io.github.saggeldi:yedu-kmp-gps-listener:0.0.3")
}
```

### Gradle (Groovy)

```groovy
dependencies {
    implementation 'io.github.saggeldi:yedu-kmp-gps-listener:0.0.3'
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

// Full pipeline mode (GPS + caching + sending + retry)
val controller = GpsFactory.createTrackingController(
    serverUrl = "https://your-server.com:5055",
    buffer = true,
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
2. **`TrackingController`** - Full pipeline. GPS + offline caching + network monitoring + HTTP sending + retry logic. Handles everything automatically.

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

Use this when you want automatic offline caching, HTTP sending, and retry - like the original Traccar clients.

The state machine works as follows:
```
GPS position received
    -> write to local database
    -> if online: read from database -> send to server -> delete from database -> read next
    -> if offline: positions accumulate in database
    -> on network reconnect: read -> send -> delete -> read next
    -> on send failure: retry after 30 seconds
```

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
            buffer = true,
            listener = controllerListener
        )

        // Option B: Direct platform constructors
        // controller = TrackingController(
        //     locationProvider = AndroidLocationProvider(this),
        //     positionStore = AndroidPositionStore(this),
        //     positionSender = AndroidPositionSender(),
        //     networkMonitor = AndroidNetworkMonitor(this),
        //     retryScheduler = AndroidRetryScheduler(),
        //     serverUrl = "https://your-server.com:5055",
        //     buffer = true,
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
            Log.w("GPS", "Send failed, will retry")
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
            positionStore: IosPositionStore(),
            positionSender: IosPositionSender(),
            networkMonitor: IosNetworkMonitor(),
            retryScheduler: IosRetryScheduler(),
            serverUrl: "https://your-server.com:5055",
            buffer: true,
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

### Full Pipeline

| Type | Name | Description |
|------|------|-------------|
| Class | `TrackingController` | Full pipeline: GPS + caching + sending + retry |
| Interface | `TrackingControllerListener` | Callbacks: position events + send/fail events |
| Object | `ProtocolFormatter` | OsmAnd/Traccar URL formatting |
| Interface | `PositionStore` | Local position database |
| Interface | `PositionSender` | HTTP position sending |
| Interface | `NetworkMonitor` | Network connectivity monitoring |
| Interface | `RetryScheduler` | Delayed retry execution |

### Platform Implementations

| Component | Android | iOS |
|-----------|---------|-----|
| Location Provider | `AndroidLocationProvider(context)` | `IosLocationProvider()` |
| Battery Provider | `AndroidBatteryProvider(context)` | `IosBatteryProvider()` |
| Position Store | `AndroidPositionStore(context)` | `IosPositionStore()` |
| Position Sender | `AndroidPositionSender()` | `IosPositionSender()` |
| Network Monitor | `AndroidNetworkMonitor(context)` | `IosNetworkMonitor()` |
| Retry Scheduler | `AndroidRetryScheduler()` | `IosRetryScheduler()` |

### Position Fields

| Field | Type | Description |
|-------|------|-------------|
| `id` | `Long` | Database ID (0 if not persisted) |
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
