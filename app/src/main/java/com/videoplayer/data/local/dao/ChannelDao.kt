package com.videoplayer.data.local.dao

import androidx.room.*
import com.videoplayer.data.local.entity.ChannelEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for Channel operations
 */
@Dao
interface ChannelDao {

    @Query("SELECT * FROM channels ORDER BY position ASC")
    fun observeAllChannels(): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels ORDER BY position ASC")
    suspend fun getAllChannels(): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE isFavorite = 1 ORDER BY position ASC")
    fun observeFavorites(): Flow<List<ChannelEntity>>

    @Query("SELECT * FROM channels WHERE isFavorite = 1 ORDER BY position ASC")
    suspend fun getFavorites(): List<ChannelEntity>

    @Query("SELECT * FROM channels WHERE url = :url LIMIT 1")
    suspend fun getChannelByUrl(url: String): ChannelEntity?

    @Query("SELECT * FROM channels WHERE url = :url LIMIT 1")
    fun observeChannelByUrl(url: String): Flow<ChannelEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChannel(channel: ChannelEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChannels(channels: List<ChannelEntity>)

    @Update
    suspend fun updateChannel(channel: ChannelEntity)

    @Delete
    suspend fun deleteChannel(channel: ChannelEntity)

    @Query("DELETE FROM channels")
    suspend fun deleteAllChannels()

    @Query("UPDATE channels SET isFavorite = :isFavorite WHERE url = :url")
    suspend fun updateFavoriteStatus(url: String, isFavorite: Boolean)

    @Query("UPDATE channels SET aspectRatio = :aspectRatio WHERE url = :url")
    suspend fun updateAspectRatio(url: String, aspectRatio: Int)

    @Query("SELECT COUNT(*) FROM channels")
    suspend fun getChannelCount(): Int

    @Query("SELECT COUNT(*) FROM channels WHERE isFavorite = 1")
    suspend fun getFavoriteCount(): Int
}
