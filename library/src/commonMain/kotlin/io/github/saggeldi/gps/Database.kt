package io.github.saggeldi.gps

import androidx.room.ConstructedBy
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.RoomDatabaseConstructor

@Database(entities = [PositionEntity::class], version = 1)
@ConstructedBy(PositionDatabaseConstructor::class)
abstract class PositionDatabase : RoomDatabase() {
    abstract fun positionDao(): PositionDao
}

// The Room compiler generates the `actual` implementations via KSP.
@Suppress("KotlinNoActualForExpect")
expect object PositionDatabaseConstructor : RoomDatabaseConstructor<PositionDatabase> {
    override fun initialize(): PositionDatabase
}