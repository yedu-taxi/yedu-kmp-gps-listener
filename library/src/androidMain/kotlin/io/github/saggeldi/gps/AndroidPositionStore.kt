package io.github.saggeldi.gps

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Handler
import android.os.Looper


class AndroidPositionStore(
    context: Context
) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION), PositionStore {

    private val db: SQLiteDatabase = writableDatabase
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        private const val DATABASE_VERSION = 2
        private const val DATABASE_NAME = "gps_positions.db"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE position (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "deviceId TEXT," +
                    "time INTEGER," +
                    "latitude REAL," +
                    "longitude REAL," +
                    "altitude REAL," +
                    "speed REAL," +
                    "course REAL," +
                    "accuracy REAL," +
                    "battery REAL," +
                    "charging INTEGER," +
                    "mock INTEGER)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS position")
        onCreate(db)
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS position")
        onCreate(db)
    }

    override fun insertPosition(position: Position, onComplete: (Boolean) -> Unit) {
        Thread {
            val success = try {
                val values = ContentValues().apply {
                    put("deviceId", position.deviceId)
                    put("time", position.time)
                    put("latitude", position.latitude)
                    put("longitude", position.longitude)
                    put("altitude", position.altitude)
                    put("speed", position.speed)
                    put("course", position.course)
                    put("accuracy", position.accuracy)
                    put("battery", position.battery.level)
                    put("charging", if (position.battery.charging) 1 else 0)
                    put("mock", if (position.mock) 1 else 0)
                }
                db.insertOrThrow("position", null, values)
                true
            } catch (_: Exception) {
                false
            }
            handler.post { onComplete(success) }
        }.start()
    }

    @SuppressLint("Range")
    override fun selectFirstPosition(onComplete: (Boolean, Position?) -> Unit) {
        Thread {
            try {
                db.rawQuery("SELECT * FROM position ORDER BY id LIMIT 1", null).use { cursor ->
                    if (cursor.count > 0) {
                        cursor.moveToFirst()
                        val position = Position(
                            id = cursor.getLong(cursor.getColumnIndex("id")),
                            deviceId = cursor.getString(cursor.getColumnIndex("deviceId")),
                            time = cursor.getLong(cursor.getColumnIndex("time")),
                            latitude = cursor.getDouble(cursor.getColumnIndex("latitude")),
                            longitude = cursor.getDouble(cursor.getColumnIndex("longitude")),
                            altitude = cursor.getDouble(cursor.getColumnIndex("altitude")),
                            speed = cursor.getDouble(cursor.getColumnIndex("speed")),
                            course = cursor.getDouble(cursor.getColumnIndex("course")),
                            accuracy = cursor.getDouble(cursor.getColumnIndex("accuracy")),
                            battery = BatteryStatus(
                                level = cursor.getDouble(cursor.getColumnIndex("battery")),
                                charging = cursor.getInt(cursor.getColumnIndex("charging")) > 0
                            ),
                            mock = cursor.getInt(cursor.getColumnIndex("mock")) > 0
                        )
                        handler.post { onComplete(true, position) }
                    } else {
                        handler.post { onComplete(true, null) }
                    }
                }
            } catch (_: Exception) {
                handler.post { onComplete(false, null) }
            }
        }.start()
    }

    @SuppressLint("Range")
    override fun selectAllPositions(onComplete: (Boolean, List<Position>) -> Unit) {
        Thread {
            try {
                db.rawQuery("SELECT * FROM position ORDER BY id", null).use { cursor ->
                    val positions = mutableListOf<Position>()
                    while (cursor.moveToNext()) {
                        positions.add(
                            Position(
                                id = cursor.getLong(cursor.getColumnIndex("id")),
                                deviceId = cursor.getString(cursor.getColumnIndex("deviceId")),
                                time = cursor.getLong(cursor.getColumnIndex("time")),
                                latitude = cursor.getDouble(cursor.getColumnIndex("latitude")),
                                longitude = cursor.getDouble(cursor.getColumnIndex("longitude")),
                                altitude = cursor.getDouble(cursor.getColumnIndex("altitude")),
                                speed = cursor.getDouble(cursor.getColumnIndex("speed")),
                                course = cursor.getDouble(cursor.getColumnIndex("course")),
                                accuracy = cursor.getDouble(cursor.getColumnIndex("accuracy")),
                                battery = BatteryStatus(
                                    level = cursor.getDouble(cursor.getColumnIndex("battery")),
                                    charging = cursor.getInt(cursor.getColumnIndex("charging")) > 0
                                ),
                                mock = cursor.getInt(cursor.getColumnIndex("mock")) > 0
                            )
                        )
                    }
                    handler.post { onComplete(true, positions) }
                }
            } catch (_: Exception) {
                handler.post { onComplete(false, emptyList()) }
            }
        }.start()
    }

    override fun deletePosition(id: Long, onComplete: (Boolean) -> Unit) {
        Thread {
            val success = try {
                db.delete("position", "id = ?", arrayOf(id.toString())) == 1
            } catch (_: Exception) {
                false
            }
            handler.post { onComplete(success) }
        }.start()
    }

    override fun deletePositions(ids: List<Long>, onComplete: (Boolean) -> Unit) {
        Thread {
            val success = try {
                val placeholders = ids.joinToString(",") { "?" }
                val args = ids.map { it.toString() }.toTypedArray()
                db.delete("position", "id IN ($placeholders)", args)
                true
            } catch (_: Exception) {
                false
            }
            handler.post { onComplete(success) }
        }.start()
    }
}
