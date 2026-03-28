package io.github.saggeldi.gps

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface PositionDao {
    @Insert
    suspend fun insert(position: PositionEntity): Long

    @Query("SELECT * FROM positions ORDER BY time DESC")
    suspend fun getAll(): List<PositionEntity>

    @Query("SELECT * FROM positions WHERE tripId = :tripId ORDER BY time ASC")
    suspend fun getByTrip(tripId: String): List<PositionEntity>

    @Query("SELECT * FROM positions WHERE tripId IS NOT NULL AND tripStatus IS NOT NULL AND tripId = :tripId AND tripStatus = :status ORDER BY time ASC")
    suspend fun getByTripAndStatus(tripId: String, status: String): List<PositionEntity>

    @Query("DELETE FROM positions WHERE tripId = :tripId")
    suspend fun deleteByTrip(tripId: String)

    @Query("DELETE FROM positions")
    suspend fun deleteAll()
}