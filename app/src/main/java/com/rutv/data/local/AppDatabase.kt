package com.rutv.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.rutv.data.local.entity.PlaylistSnapshotEntity
import com.rutv.data.local.dao.ChannelDao
import com.rutv.data.local.entity.ChannelEntity

/**
 * Main Room database for the application
 */
@Database(
    entities = [ChannelEntity::class, PlaylistSnapshotEntity::class],
    version = 5,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun channelDao(): ChannelDao

    companion object {
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `playlist_snapshot` (`id` INTEGER NOT NULL, `sourceIdentity` TEXT NOT NULL, `contentHash` TEXT NOT NULL, PRIMARY KEY(`id`))")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `channels` ADD COLUMN `preferredAudioLanguage` TEXT DEFAULT NULL")
            }
        }
    }
}
