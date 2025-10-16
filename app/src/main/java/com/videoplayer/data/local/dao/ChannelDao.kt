package com.videoplayer.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.videoplayer.data.local.entity.ChannelEntity

/**
 * Data Access Object for Channel operations
 */
@Dao
interface ChannelDao {

    @Query("SELECT * FROM channels ORDER BY position ASC")
    suspend fun getAllChannels(): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE url = :url LIMIT 1")
    suspend fun getChannelByUrl(url: String): ChannelEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChannels(channels: List<ChannelEntity>)

    @Query("DELETE FROM channels")
    suspend fun deleteAllChannels()

    @Query("UPDATE channels SET isFavorite = :isFavorite WHERE url = :url")
    suspend fun updateFavoriteStatus(url: String, isFavorite: Boolean)

    @Query("UPDATE channels SET aspectRatio = :aspectRatio WHERE url = :url")
    suspend fun updateAspectRatio(url: String, aspectRatio: Int)
}
