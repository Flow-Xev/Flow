package app.zaid.flow.innertube.pages.channel

import app.zaid.flow.innertube.pages.renderer.FeedItem
import app.zaid.flow.innertube.pages.renderer.FeedItemOwner
import app.zaid.flow.innertube.pages.renderer.FeedShelf

/**
 * One page of one channel tab. Grid tabs fill [items], shelf tabs fill [sections].
 *
 * A sort chip's token is itself a continuation, so switching sort and loading a page are the same
 * request with a different token.
 */
data class ChannelTabContent(
    val kind: ChannelTabKind,
    val items: List<FeedItem> = emptyList(),
    val sections: List<FeedShelf> = emptyList(),
    val filters: List<ChannelFilterGroup> = emptyList(),
    val continuation: String? = null,
    val owner: FeedItemOwner = FeedItemOwner(),
)
