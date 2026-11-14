package app.zaid.flow.data.recommendation.music

import app.zaid.flow.data.music.model.MusicTrack
import app.zaid.flow.data.music.model.audioMusicOnly

/** The On Repeat shelf as the Music page, its widget and its shuffle all show it. */
suspend fun MusicBrainEngine.onRepeatShelf(): List<MusicTrack> = heavyRotationTracks(ON_REPEAT_POOL).audioMusicOnly()

private const val ON_REPEAT_POOL = 16
