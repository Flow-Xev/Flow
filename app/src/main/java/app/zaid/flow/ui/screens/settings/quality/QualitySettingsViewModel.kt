package app.zaid.flow.ui.screens.settings.quality

import app.zaid.flow.data.local.MusicAudioQuality
import app.zaid.flow.data.local.PlayerPreferences
import app.zaid.flow.data.local.ThumbnailQuality
import app.zaid.flow.data.local.VideoCodec
import app.zaid.flow.data.local.VideoQuality
import app.zaid.flow.player.quality.AutoQualityBounds
import app.zaid.flow.ui.screens.settings.SettingsViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class QualitySettingsViewModel
    @Inject
    constructor(
        private val preferences: PlayerPreferences,
    ) : SettingsViewModel() {
        val videoWifi = preferences.defaultQualityWifi.asState(VideoQuality.Q_1080P)
        val videoMobile = preferences.defaultQualityCellular.asState(VideoQuality.Q_480P)
        val shortsWifi = preferences.shortsQualityWifi.asState(VideoQuality.Q_720P)
        val shortsMobile = preferences.shortsQualityCellular.asState(VideoQuality.Q_480P)
        val dataSaver = preferences.dataSaverEnabled.asState(false)
        val autoFloor = preferences.autoQualityFloor.asState(AutoQualityBounds.NONE)
        val autoCeiling = preferences.autoQualityCeiling.asState(AutoQualityBounds.NONE)
        val shortsAutoFloor = preferences.shortsAutoQualityFloor.asState(AutoQualityBounds.NONE)
        val shortsAutoCeiling = preferences.shortsAutoQualityCeiling.asState(AutoQualityBounds.NONE)
        val music = preferences.musicAudioQuality.asState(MusicAudioQuality.AUTO)
        val thumbnailsWifi = preferences.thumbnailQualityWifi.asState(ThumbnailQuality.HIGH)
        val thumbnailsMobile = preferences.thumbnailQualityCellular.asState(ThumbnailQuality.HIGH)
        val codec = preferences.defaultVideoCodec.asState(VideoCodec.H264)
        val fallbackCodec = preferences.fallbackVideoCodec.asState(VideoCodec.AUTO)

        fun setDataSaver(enabled: Boolean) = write { preferences.setDataSaverEnabled(enabled) }

        fun setAutoFloor(height: Int) = write { preferences.setAutoQualityFloor(height) }

        fun setAutoCeiling(height: Int) = write { preferences.setAutoQualityCeiling(height) }

        fun setShortsAutoFloor(height: Int) = write { preferences.setShortsAutoQualityFloor(height) }

        fun setShortsAutoCeiling(height: Int) = write { preferences.setShortsAutoQualityCeiling(height) }

        fun setVideoWifi(value: VideoQuality) = write { preferences.setDefaultQualityWifi(value) }

        fun setVideoMobile(value: VideoQuality) = write { preferences.setDefaultQualityCellular(value) }

        fun setShortsWifi(value: VideoQuality) = write { preferences.setShortsQualityWifi(value) }

        fun setShortsMobile(value: VideoQuality) = write { preferences.setShortsQualityCellular(value) }

        fun setMusic(value: MusicAudioQuality) = write { preferences.setMusicAudioQuality(value) }

        fun setThumbnailsWifi(value: ThumbnailQuality) = write { preferences.setThumbnailQualityWifi(value) }

        fun setThumbnailsMobile(value: ThumbnailQuality) = write { preferences.setThumbnailQualityCellular(value) }

        fun setCodec(value: VideoCodec) =
            write {
                preferences.setDefaultVideoCodec(value)
                val fallback = fallbackAfterPreferredChange(value, fallbackCodec.value)
                if (fallback != fallbackCodec.value) preferences.setFallbackVideoCodec(fallback)
            }

        fun setFallbackCodec(value: VideoCodec) = write { preferences.setFallbackVideoCodec(value) }
    }
