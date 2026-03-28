package io.github.saggeldi.gps

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.newFixedThreadPoolContext
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

actual object GpsFactory {

    actual fun createLocationProvider(): PlatformLocationProvider {
        return IosLocationProvider()
    }

    actual fun createBatteryProvider(): PlatformBatteryProvider {
        return IosBatteryProvider()
    }

    actual fun createPositionSender(): PositionSender {
        return IosPositionSender()
    }

    actual fun createNetworkMonitor(): NetworkMonitor {
        return IosNetworkMonitor()
    }

    actual fun createLocationPermissionHelper(): LocationPermissionHelper {
        return IosLocationPermissionHelper()
    }

    @OptIn(ExperimentalForeignApi::class)
    actual fun createPositionDatabase(): PositionDatabase {
        val docsDir = NSFileManager.defaultManager.URLForDirectory(
            directory = NSDocumentDirectory,
            inDomain = NSUserDomainMask,
            appropriateForURL = null,
            create = false,
            error = null
        )!!.path!!
        return Room.databaseBuilder<PositionDatabase>("$docsDir/positions.db")
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(newFixedThreadPoolContext(4, "RoomDB"))
            .build()
    }
}
