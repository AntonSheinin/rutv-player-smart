package com.rutv.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.rutv.data.local.dao.ChannelDao
import com.rutv.data.local.entity.ChannelEntity

/**
 * Main Room database for the application
 */
@Suppress("unused")
@Database(
    entities = [ChannelEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun channelDao(): ChannelDao
}
