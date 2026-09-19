package com.rutv.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.rutv.data.local.entity.ChannelEntity
import com.rutv.data.local.entity.PlaylistSnapshotEntity

/**
 * Data Access Object for Channel operations
 */
data class ChannelSnapshot(val identity: PlaylistSnapshotEntity?, val channels: List<ChannelEntity>)

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

    @Query("SELECT * FROM playlist_snapshot WHERE id = 1")
    suspend fun getSnapshotIdentity(): PlaylistSnapshotEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSnapshotIdentity(snapshot: PlaylistSnapshotEntity)

    @Transaction
    suspend fun readSnapshot(): ChannelSnapshot = ChannelSnapshot(getSnapshotIdentity(), getAllChannels())

    @Transaction
    suspend fun replaceSnapshot(
        channels: List<ChannelEntity>,
        snapshot: PlaylistSnapshotEntity,
        backupUrls: Set<String>,
        backupTvgIds: Set<String>
    ): List<ChannelEntity> {
        val existing = getAllChannels()
        val byUrl = existing.associateBy { it.url }
        val byTvgId = existing.filter { it.tvgId.isNotBlank() }.associateBy { it.tvgId }
        // Only restore the backup when Room has no rows (fresh install/destructive recovery).
        val replaced = channels.map { channel ->
            val previous = byUrl[channel.url] ?: byTvgId[channel.tvgId]
            channel.copy(
                isFavorite = if (existing.isEmpty()) {
                    channel.url in backupUrls || (channel.tvgId.isNotBlank() && channel.tvgId in backupTvgIds)
                } else previous?.isFavorite ?: false,
                aspectRatio = previous?.aspectRatio ?: channel.aspectRatio,
                preferredAudioLanguage = previous?.preferredAudioLanguage
                    ?: channel.preferredAudioLanguage
            )
        }
        deleteAllChannels()
        insertChannels(replaced)
        saveSnapshotIdentity(snapshot)
        return getAllChannels()
    }

    @Transaction
    suspend fun toggleFavorite(url: String): Boolean {
        val channel = getChannelByUrl(url) ?: error("Channel not found")
        val favorite = !channel.isFavorite
        updateFavoriteStatus(url, favorite)
        return favorite
    }

    @Query("UPDATE channels SET isFavorite = :isFavorite WHERE url = :url")
    suspend fun updateFavoriteStatus(url: String, isFavorite: Boolean)

    @Query("UPDATE channels SET aspectRatio = :aspectRatio WHERE url = :url")
    suspend fun updateAspectRatio(url: String, aspectRatio: Int)

    @Query("UPDATE channels SET preferredAudioLanguage = :language WHERE url = :url")
    suspend fun updatePreferredAudioLanguage(url: String, language: String?)
}
