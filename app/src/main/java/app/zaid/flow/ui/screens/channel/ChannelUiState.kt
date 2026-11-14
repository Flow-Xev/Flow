package app.zaid.flow.ui.screens.channel

import app.zaid.flow.data.model.Video
import app.zaid.flow.innertube.pages.channel.ChannelHeader
import app.zaid.flow.innertube.pages.channel.ChannelProduct
import app.zaid.flow.innertube.pages.channel.ChannelTabDescriptor
import app.zaid.flow.innertube.pages.channel.ChannelTabKind

data class ChannelUiState(
    val channelId: String? = null,
    val header: ChannelHeader? = null,
    /** Exactly the tabs the channel published, minus the ones the app hides. Empty until loaded. */
    val tabs: List<ChannelTabDescriptor> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingVideos: Boolean = false,
    val error: String? = null,
    val videosError: String? = null,
    val isSubscribed: Boolean = false,
    val isNotificationsEnabled: Boolean = false,
    /** Null until the tab list lands: the channel decides which tab is first, not the app. */
    val selectedTab: ChannelTabKind? = null,
    val searchActive: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<Video> = emptyList(),
    val isSearching: Boolean = false,
    val searchErrorLog: String? = null,
    val searchContinuation: String? = null,
    val isLoadingMoreSearch: Boolean = false,
) {
    fun tabParams(kind: ChannelTabKind): String? = tabs.firstOrNull { it.kind == kind }?.params ?: kind.defaultParams

    fun hasTab(kind: ChannelTabKind): Boolean = tabs.any { it.kind == kind }
}

/**
 * Whether opening [url] needs a fetch. Coming back to a channel page re-runs its effects, and a
 * second load would refetch it and reset every tab's sort and loaded pages; only a different
 * channel, or a retry after a failed load, loads again.
 */
internal fun shouldLoadChannel(
    state: ChannelUiState,
    requestedUrl: String?,
    url: String,
): Boolean = url != requestedUrl || (state.header == null && !state.isLoading)

/** The channel's Store tab, loaded on demand when the bag is tapped. */
internal data class ChannelStoreUiState(
    val products: List<ChannelProduct> = emptyList(),
    val isLoading: Boolean = false,
    val loaded: Boolean = false,
    val error: Boolean = false,
)
