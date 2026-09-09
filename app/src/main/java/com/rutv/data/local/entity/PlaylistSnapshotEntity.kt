package com.rutv.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Identity and content hash committed atomically with the channel rows. */
@Entity(tableName = "playlist_snapshot")
data class PlaylistSnapshotEntity(
    @PrimaryKey val id: Int = 1,
    val sourceIdentity: String,
    val contentHash: String
)
