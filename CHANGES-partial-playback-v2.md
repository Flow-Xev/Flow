# In-progress download playback, v2

Why "Failed to load video" happened when tapping an in-progress download:

1. The player screen only looked for a *finished* copy (`findLocalCopy`). The preview of the parts was reachable from the service layer only, so the screen went on to resolve online streams and failed offline.
2. The parts were handed to the player cut at an arbitrary byte. That ends inside a box/cluster, which the extractor reports as an error instead of an end of stream, and the clip point was a byte-fraction guess.

What changed:

- `PlayablePrefix` (new): cuts each part at the last complete `moof`+`mdat` (fragmented MP4) or `Cluster` (WebM), once `moov` / `Tracks` have arrived, and reads the covered duration from `sidx` / cluster timecodes.
- `PartialDownload`: video and audio parts play side by side, each trimmed to end at the same fragment boundary. The source is not clipped, so the player and history keep the full video length (a clip had shrunk them to the downloaded part, e.g. 2:09 instead of 16:00). "Not ready" is only reported when a part has no complete fragment yet.
- `VideoDownloadManager.partialCopyOf` + `PlaybackLoadResolver`: the player screen now plays an unfinished download the user opened (or any one while offline), through the same local-copy path as a finished file. It also works while the final merge is running.
- `MediaLoader`: a partial path that is not ready returns "no source" instead of being opened as a file.
- Tests: `PlayablePrefixTest` (MP4 and WebM cuts), resolver test for the partial path; existing mocks stub `partialCopyOf`.

Past the downloaded part (playing to its end, or seeking beyond it):

- Online: the screen reloads the same video as normal playback from that position (`VideoPlayerViewModel.continueOnline`, `EnhancedPlayerManager.leavePartialPreview`).
- Offline: playback pauses just before the edge and a message says it was the downloaded part (`download_preview_edge_offline`).
- Nothing ends or autoplays at the edge unless it is the real end of the video.
