package app.zaid.flow.ui.components.shared.card

import app.zaid.flow.data.model.Video
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class InlineVideoPreviewTest {
    private fun video(
        id: String = "abc",
        isLive: Boolean = false,
        isUpcoming: Boolean = false,
        isShort: Boolean = false,
        membersOnlyText: String? = null,
    ) = Video(
        id = id,
        title = "t",
        channelName = "c",
        channelId = "ch",
        thumbnailUrl = "",
        duration = 120,
        viewCount = 0,
        uploadDate = "",
        isLive = isLive,
        isShort = isShort,
        isUpcoming = isUpcoming,
        membersOnlyText = membersOnlyText,
    )

    @Test
    fun ordinaryVideoPreviews() {
        assertThat(video().isPreviewable()).isTrue()
    }

    @Test
    fun liveUpcomingShortsAndMembersOnlyDoNot() {
        assertThat(video(isLive = true).isPreviewable()).isFalse()
        assertThat(video(isUpcoming = true).isPreviewable()).isFalse()
        assertThat(video(isShort = true).isPreviewable()).isFalse()
        assertThat(video(membersOnlyText = "Members only").isPreviewable()).isFalse()
        assertThat(video(id = "").isPreviewable()).isFalse()
    }

    @Test
    fun touchMapsToTheInsetTrack() {
        // A 300 px bar with a 10 px inset has a 280 px track starting at x = 10.
        assertThat(previewSeekFractionForX(10f, 300f, 10f)).isEqualTo(0f)
        assertThat(previewSeekFractionForX(150f, 300f, 10f)).isEqualTo(0.5f)
        assertThat(previewSeekFractionForX(290f, 300f, 10f)).isEqualTo(1f)
    }

    @Test
    fun touchesPastEitherEndClampInsteadOfOverflowing() {
        assertThat(previewSeekFractionForX(-40f, 300f, 10f)).isEqualTo(0f)
        assertThat(previewSeekFractionForX(0f, 300f, 10f)).isEqualTo(0f)
        assertThat(previewSeekFractionForX(900f, 300f, 10f)).isEqualTo(1f)
    }

    @Test
    fun aBarTooNarrowForItsInsetsSeeksToStart() {
        assertThat(previewSeekFractionForX(5f, 20f, 10f)).isEqualTo(0f)
    }

    @Test
    fun seekTargetScalesWithLength() {
        assertThat(previewSeekTargetMs(100_000L, 0f)).isEqualTo(0L)
        assertThat(previewSeekTargetMs(100_000L, 0.5f)).isEqualTo(50_000L)
    }

    @Test
    fun seekingToTheEndStopsShortSoTheLoopDoesNotWrap() {
        assertThat(previewSeekTargetMs(100_000L, 1f)).isEqualTo(99_500L)
        assertThat(previewSeekTargetMs(100_000L, 7f)).isEqualTo(99_500L)
    }

    @Test
    fun seekTargetIsSafeForUnknownOrTinyLengthsAndBadFractions() {
        assertThat(previewSeekTargetMs(0L, 0.5f)).isEqualTo(0L)
        assertThat(previewSeekTargetMs(300L, 1f)).isEqualTo(0L)
        assertThat(previewSeekTargetMs(100_000L, Float.NaN)).isEqualTo(0L)
        assertThat(previewSeekTargetMs(100_000L, -1f)).isEqualTo(0L)
    }
}
