package app.zaid.flow.ui.screens.recap

import app.zaid.flow.data.local.PlayerPreferences
import app.zaid.flow.data.local.SearchHistoryRepository
import app.zaid.flow.data.recommendation.music.MusicBrainEngine
import app.zaid.flow.data.recommendation.music.MusicStatsStorage
import app.zaid.flow.data.recommendation.music.graph.MusicGraphStore
import app.zaid.flow.data.stats.RecapImageResolver
import app.zaid.flow.data.stats.RecapPeriod
import app.zaid.flow.data.stats.VideoMonthRecord
import app.zaid.flow.data.stats.VideoStatsRecorder
import app.zaid.flow.data.stats.VideoStatsSnapshot
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.YearMonth

@OptIn(ExperimentalCoroutinesApi::class)
class RecapViewModelTest {
    private val august = YearMonth.of(2026, 8)
    private val september = YearMonth.of(2026, 9)
    private val gate = CompletableDeferred<Unit>()
    private val videoStats: VideoStatsRecorder = mockk()
    private val musicBrain: MusicBrainEngine = mockk()
    private val searchHistory: SearchHistoryRepository = mockk()
    private val images: RecapImageResolver = mockk()
    private val musicGraph: MusicGraphStore = mockk()
    private val preferences: PlayerPreferences = mockk(relaxed = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val month = VideoMonthRecord(views = 3, watchedMs = 60_000L)
        coEvery { videoStats.snapshot() } coAnswers {
            gate.await()
            VideoStatsSnapshot(months = mapOf("2026-08" to month, "2026-09" to month))
        }
        coEvery { musicBrain.listeningStats() } returns MusicStatsStorage.SerializableStats()
        every { searchHistory.isAutoDeleteHistoryEnabledFlow() } returns flowOf(false)
        coEvery { images.localImages() } returns emptyMap()
        coEvery { images.fetchMissing(any()) } returns emptyMap()
        coEvery { musicGraph.albumsOfTracks(any()) } returns emptyMap()
        every { preferences.recapSource } returns flowOf(null)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = RecapViewModel(videoStats, musicBrain, mockk(relaxed = true), searchHistory, images, musicGraph, preferences)

    @Test
    fun `a period asked for while the ledgers load wins over the newest month`() =
        runBlocking {
            val viewModel = viewModel()
            viewModel.openAt(RecapPeriod.Month(august), RecapSource.ALL)
            gate.complete(Unit)

            val state = withTimeout(TIMEOUT_MS) { viewModel.state.first { !it.loading } }
            assertThat(state.period).isEqualTo(RecapPeriod.Month(august))
            assertThat(state.summary?.period).isEqualTo(RecapPeriod.Month(august))
        }

    @Test
    fun `without a request the newest month opens`() =
        runBlocking {
            gate.complete(Unit)
            val state = withTimeout(TIMEOUT_MS) { viewModel().state.first { !it.loading } }
            assertThat(state.period).isEqualTo(RecapPeriod.Month(september))
        }

    @Test
    fun `the recap opens on the tab it was last left on`() =
        runBlocking {
            every { preferences.recapSource } returns flowOf(RecapSource.MUSIC.name)
            gate.complete(Unit)
            val state = withTimeout(TIMEOUT_MS) { viewModel().state.first { !it.loading } }
            assertThat(state.source).isEqualTo(RecapSource.MUSIC)
        }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
