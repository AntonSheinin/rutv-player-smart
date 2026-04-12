package com.rutv.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.rutv.data.local.dao.ChannelDao
import com.rutv.data.local.entity.ChannelEntity

/**
 * Main Room database for the application
 */
@Database(
    entities = [ChannelEntity::class],
    version = 3,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun channelDao(): ChannelDao
}
