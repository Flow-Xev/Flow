package app.zaid.flow.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "playlist_video_cross_ref",
    primaryKeys = ["playlistId", "videoId"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = VideoEntity::class,
            parentColumns = ["id"],
            childColumns = ["videoId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["videoId"]), Index(value = ["playlistId"])],
)
data class PlaylistVideoCrossRef(
    val playlistId: String,
    val videoId: String,
    // For custom ordering within playlist
    val position: Long,
    // When the video was added to this playlist
    val addedAt: Long = System.currentTimeMillis(),
)
