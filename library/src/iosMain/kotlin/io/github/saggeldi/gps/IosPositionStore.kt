package io.github.saggeldi.gps

import platform.Foundation.NSUserDefaults

class IosPositionStore : PositionStore {

    private val defaults = NSUserDefaults.standardUserDefaults
    private val storageKey = "gps_position_buffer"
    private val idCounterKey = "gps_position_next_id"

    private fun getNextId(): Long {
        val current = defaults.integerForKey(idCounterKey)
        val next = if (current == 0L) 1L else current + 1L
        defaults.setInteger(next, forKey = idCounterKey)
        return if (current == 0L) 1L else next
    }

    override fun insertPosition(position: Position, onComplete: (Boolean) -> Unit) {
        try {
            val list = loadList().toMutableList()
            val id = getNextId()
            val entry = mapOf<String, Any>(
                "id" to id,
                "deviceId" to position.deviceId,
                "time" to position.time,
                "latitude" to position.latitude,
                "longitude" to position.longitude,
                "altitude" to position.altitude,
                "speed" to position.speed,
                "course" to position.course,
                "accuracy" to position.accuracy,
                "batteryLevel" to position.battery.level,
                "batteryCharging" to position.battery.charging,
                "mock" to position.mock
            )
            list.add(entry)
            defaults.setObject(list, forKey = storageKey)
            onComplete(true)
        } catch (_: Exception) {
            onComplete(false)
        }
    }

    override fun selectFirstPosition(onComplete: (Boolean, Position?) -> Unit) {
        try {
            val list = loadList()
            if (list.isEmpty()) {
                onComplete(true, null)
                return
            }
            val entry = list.first()
            val position = mapToPosition(entry)
            onComplete(true, position)
        } catch (_: Exception) {
            onComplete(false, null)
        }
    }

    override fun selectAllPositions(onComplete: (Boolean, List<Position>) -> Unit) {
        try {
            val list = loadList()
            val positions = list.map { mapToPosition(it) }
            onComplete(true, positions)
        } catch (_: Exception) {
            onComplete(false, emptyList())
        }
    }

    override fun deletePosition(id: Long, onComplete: (Boolean) -> Unit) {
        try {
            val list = loadList().toMutableList()
            val removed = list.removeAll { entry ->
                toLong(entry["id"]) == id
            }
            if (removed) {
                defaults.setObject(list, forKey = storageKey)
            }
            onComplete(removed)
        } catch (_: Exception) {
            onComplete(false)
        }
    }

    override fun deletePositions(ids: List<Long>, onComplete: (Boolean) -> Unit) {
        try {
            val idSet = ids.toSet()
            val list = loadList().toMutableList()
            val removed = list.removeAll { entry ->
                toLong(entry["id"]) in idSet
            }
            if (removed) {
                defaults.setObject(list, forKey = storageKey)
            }
            onComplete(true)
        } catch (_: Exception) {
            onComplete(false)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun loadList(): List<Map<String, Any>> {
        val array = defaults.arrayForKey(storageKey) ?: return emptyList()
        return array as? List<Map<String, Any>> ?: emptyList()
    }

    private fun mapToPosition(map: Map<String, Any>): Position {
        return Position(
            id = toLong(map["id"]),
            deviceId = map["deviceId"] as? String ?: "",
            time = toLong(map["time"]),
            latitude = toDouble(map["latitude"]),
            longitude = toDouble(map["longitude"]),
            altitude = toDouble(map["altitude"]),
            speed = toDouble(map["speed"]),
            course = toDouble(map["course"]),
            accuracy = toDouble(map["accuracy"]),
            battery = BatteryStatus(
                level = toDouble(map["batteryLevel"]),
                charging = toBoolean(map["batteryCharging"])
            ),
            mock = toBoolean(map["mock"])
        )
    }

    private fun toLong(value: Any?): Long = when (value) {
        is Long -> value
        is Int -> value.toLong()
        is Number -> value.toLong()
        else -> 0L
    }

    private fun toDouble(value: Any?): Double = when (value) {
        is Double -> value
        is Float -> value.toDouble()
        is Number -> value.toDouble()
        else -> 0.0
    }

    private fun toBoolean(value: Any?): Boolean = when (value) {
        is Boolean -> value
        is Number -> value.toInt() != 0
        else -> false
    }
}
