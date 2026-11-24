package app.zaid.flow

import app.zaid.flow.data.local.DataSaver
import app.zaid.flow.data.local.MusicAudioQuality
import app.zaid.flow.data.local.ThumbnailQuality
import app.zaid.flow.data.local.VideoQuality
import org.junit.Assert.assertEquals
import org.junit.Test

class DataSaverTest {
    @Test
    fun videoIsCappedAt720pAndAutoIsKept() {
        assertEquals(VideoQuality.Q_720P, DataSaver.capVideo(VideoQuality.Q_2160P, enabled = true))
        assertEquals(VideoQuality.Q_720P, DataSaver.capVideo(VideoQuality.Q_1080P, enabled = true))
        assertEquals(VideoQuality.Q_720P, DataSaver.capVideo(VideoQuality.Q_720P, enabled = true))
        assertEquals(VideoQuality.Q_480P, DataSaver.capVideo(VideoQuality.Q_480P, enabled = true))
        assertEquals(VideoQuality.AUTO, DataSaver.capVideo(VideoQuality.AUTO, enabled = true))
    }

    @Test
    fun shortsKeepAutoButCapFixedQualities() {
        assertEquals(VideoQuality.AUTO, DataSaver.capShorts(VideoQuality.AUTO, enabled = true))
        assertEquals(VideoQuality.Q_720P, DataSaver.capShorts(VideoQuality.Q_1080P, enabled = true))
        assertEquals(VideoQuality.Q_480P, DataSaver.capShorts(VideoQuality.Q_480P, enabled = true))
    }

    @Test
    fun nothingChangesWhenOff() {
        assertEquals(VideoQuality.Q_2160P, DataSaver.capVideo(VideoQuality.Q_2160P, enabled = false))
        assertEquals(ThumbnailQuality.HIGH, DataSaver.capThumbnail(ThumbnailQuality.HIGH, enabled = false))
        assertEquals(MusicAudioQuality.AUTO, DataSaver.capMusic(MusicAudioQuality.AUTO, enabled = false))
    }

    @Test
    fun thumbnailsAndMusicDropButNeverRise() {
        assertEquals(ThumbnailQuality.LOW, DataSaver.capThumbnail(ThumbnailQuality.HIGH, enabled = true))
        assertEquals(ThumbnailQuality.OFF, DataSaver.capThumbnail(ThumbnailQuality.OFF, enabled = true))
        assertEquals(MusicAudioQuality.MEDIUM, DataSaver.capMusic(MusicAudioQuality.AUTO, enabled = true))
        assertEquals(MusicAudioQuality.LOW, DataSaver.capMusic(MusicAudioQuality.LOW, enabled = true))
    }
}
