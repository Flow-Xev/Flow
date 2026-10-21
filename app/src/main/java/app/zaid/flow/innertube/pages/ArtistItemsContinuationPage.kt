package app.zaid.flow.innertube.pages

import app.zaid.flow.innertube.models.YTItem

data class ArtistItemsContinuationPage(
    val items: List<YTItem>,
    val continuation: String?,
)
