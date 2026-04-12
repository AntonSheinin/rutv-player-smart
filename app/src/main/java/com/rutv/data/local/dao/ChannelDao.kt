package com.rutv.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.rutv.data.local.entity.ChannelEntity

/**
 * Data Access Object for Channel operations
 */
data class AspectRatioEntry(val url: String, val aspectRatio: Int)

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

    @Query("SELECT url FROM channels WHERE isFavorite = 1")
    suspend fun getFavoriteUrls(): List<String>

    @Query("SELECT tvgId FROM channels WHERE isFavorite = 1 AND tvgId != ''")
    suspend fun getFavoriteTvgIds(): List<String>

    @Query("UPDATE channels SET isFavorite = 1 WHERE url IN (:urls)")
    suspend fun markFavorites(urls: List<String>)

    @Query("SELECT url, aspectRatio FROM channels WHERE aspectRatio != 0")
    suspend fun getCustomAspectRatios(): List<AspectRatioEntry>

    @Query("UPDATE channels SET aspectRatio = :aspectRatio WHERE url = :url")
    suspend fun restoreAspectRatio(url: String, aspectRatio: Int)

    @Transaction
    suspend fun replaceChannelsPreservingFavorites(
        channels: List<ChannelEntity>,
        favoriteUrls: List<String>? = null,
        favoriteTvgIds: List<String>? = null
    ): List<String> {
        val existingFavoriteUrls = favoriteUrls ?: getFavoriteUrls()
        val existingFavoriteTvgIds = favoriteTvgIds ?: getFavoriteTvgIds()
        val existingAspectRatios = getCustomAspectRatios()
        val favoriteUrlSet = existingFavoriteUrls.toSet()
        val favoriteTvgIdSet = existingFavoriteTvgIds.toSet()
        val aspectRatioMap = existingAspectRatios.associate { it.url to it.aspectRatio }
        // Full replacement: remove stale channels that disappeared from a new playlist snapshot.
        deleteAllChannels()
        insertChannels(channels)
        // Restore aspect ratios
        for (channel in channels) {
            val savedRatio = aspectRatioMap[channel.url]
            if (savedRatio != null && savedRatio != 0) {
                restoreAspectRatio(channel.url, savedRatio)
            }
        }
        if (favoriteUrlSet.isEmpty() && favoriteTvgIdSet.isEmpty()) {
            return emptyList()
        }
        val favoriteUrlsToApply = channels.filter { channel ->
            favoriteUrlSet.contains(channel.url) ||
                (channel.tvgId.isNotBlank() && favoriteTvgIdSet.contains(channel.tvgId))
        }.map { it.url }
        if (favoriteUrlsToApply.isNotEmpty()) {
            markFavorites(favoriteUrlsToApply)
        }
        return favoriteUrlsToApply
    }

    @Query("UPDATE channels SET isFavorite = :isFavorite WHERE url = :url")
    suspend fun updateFavoriteStatus(url: String, isFavorite: Boolean)

    @Query("UPDATE channels SET aspectRatio = :aspectRatio WHERE url = :url")
    suspend fun updateAspectRatio(url: String, aspectRatio: Int)
}
