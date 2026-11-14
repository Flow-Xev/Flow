# Self-healing network + true incognito

Root cause of "Expected JsonObject but was SourceByteReadChannel ... ContentType: null":
YouTube answered 200 with no Content-Type; Ktor skips JSON parsing without one. The data-saver
cache could then replay that typeless answer on a repeated search.

- network/ContentTypeNormalizerInterceptor.kt  relabels typeless/text-plain JSON (outermost, all clients)
- network/InnerTubeSaverInterceptor.kt         never stores/replays a typeless body; off in incognito; clear()
- network/SelfHealingManager.kt                classify -> remedy -> retry -> learned backups -> last-good copy
- innertube/YouTube.kt                         videoSearch/searchShorts run through the manager (WEB -> MWEB -> TV)
- Incognito: no search history, no neuro learning, no recap count, anonymous web search and suggestions,
  saver cache off + cleared, last-good store off + wiped.

## Search shows more results (SearchPagingSource)
- A page with fewer than 20 videos/Shorts now chains the next page in the same load (max 4 requests), so
  suggestion shelves no longer replace results. An empty page with a next key can no longer stall paging.
- Suggestion shelves ("People also watch" etc.) are kept, but never count as real results when deciding to fetch more.
  with "Latest from", and community posts stay.
- Reel shelves that arrive as carousels are now classified as Shorts, not as a plain videos shelf.

## Endless search
When YouTube has no more pages, SearchPagingSource continues with results of related searches (typeahead
suggestions of the query, then of those; up to 60 queries). Items already shown are never repeated.

## Inline previews while scrolling
- Preview no longer waits for the grid to stop: the most visible card plays after 400 ms (was 1200 ms, still-only).
- Fast flings still do not start previews (the leader changes too often for the dwell to finish).

## Search keeps loading Shorts and videos
- Shelf ids were only unique per response, so later pages lost their Shorts shelves to the de-duplicator.
  They are now tagged per page.
- The Shorts tab only drew single videos; Shorts that arrived inside a shelf never showed. They are now
  flattened into grid cells, so every Short loads as you scroll.

## When YouTube's results end: "people also watch" first
Order after the last YouTube page: watch-next recommendations of the top results (one seed video after
another, with their own paging), then related searches. Only for unfiltered All searches; filtered and
Shorts searches go straight to related searches.

## Cool end of search
When the whole endless chain is spent, the footer shows one of three playful lines instead of "End of results" (default-language strings: search_end_cool_1..3).
