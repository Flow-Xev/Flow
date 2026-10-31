package app.zaid.flow.ui.screens.home

import app.zaid.flow.data.local.VideoHistoryEntry
import app.zaid.flow.data.model.Video

internal data class HomeUiState(
    val videos: List<Video> = emptyList(),
    val shorts: List<Video> = emptyList(),
    val postRows: List<HomePostRow> = emptyList(),
    val continueWatchingVideos: List<VideoHistoryEntry> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val isRefreshing: Boolean = false,
    val hasMorePages: Boolean = true,
    val error: String? = null,
    val isFlowFeed: Boolean = false,
    val lastRefreshTime: Long = 0L,
    /** Videos the channel memory lane brought, which say so under their title. */
    val channelMemoryVideoIds: Set<String> = emptySet(),
)
