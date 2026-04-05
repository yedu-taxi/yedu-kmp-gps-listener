package io.github.saggeldi.gps

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "positions")
data class PositionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val deviceId: String,
    val time: Long,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val altitude: Double = 0.0,
    val speed: Double = 0.0,
    val course: Double = 0.0,
    val accuracy: Double = 0.0,
    val batteryLevel: Double = 0.0,
    val batteryCharging: Boolean = false,
    val mock: Boolean = false,
    val tripId: String? = null,
    val tripStatus: String? = null
)

fun Position.toEntity() = PositionEntity(
    deviceId = deviceId,
    time = time,
    latitude = latitude,
    longitude = longitude,
    altitude = altitude,
    speed = speed,
    course = course,
    accuracy = accuracy,
    batteryLevel = battery.level,
    batteryCharging = battery.charging,
    mock = mock,
    tripId = tripId,
    tripStatus = tripStatus
)

fun PositionEntity.toPosition() = Position(
    id = id,
    deviceId = deviceId,
    time = time,
    latitude = latitude,
    longitude = longitude,
    altitude = altitude,
    speed = speed,
    course = course,
    accuracy = accuracy,
    battery = BatteryStatus(level = batteryLevel, charging = batteryCharging),
    mock = mock,
    tripId = tripId,
    tripStatus = tripStatus
)