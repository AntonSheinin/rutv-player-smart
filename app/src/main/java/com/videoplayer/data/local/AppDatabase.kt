package com.videoplayer.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.videoplayer.data.local.dao.ChannelDao
import com.videoplayer.data.local.entity.ChannelEntity

/**
 * Main Room database for the application
 */
@Database(
    entities = [ChannelEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun channelDao(): ChannelDao

    companion object {
        const val DATABASE_NAME = "rutv_database"
    }
}
