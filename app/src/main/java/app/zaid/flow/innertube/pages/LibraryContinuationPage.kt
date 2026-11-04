package app.zaid.flow.innertube.pages

import app.zaid.flow.innertube.models.YTItem

data class LibraryContinuationPage(
    val items: List<YTItem>,
    val continuation: String?,
)
