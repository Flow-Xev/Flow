# Download pipeline improvements

## Single-file muxed MP4 path

- Prefer a YouTube muxed MP4 only when its embedded audio track matches the selected audio track.
- Download the combined stream once, sequentially, rather than fetching separate video/audio tracks.
- Copy the completed MP4 directly into the output, skipping the expensive remux pass.
- Preserve the separate-track download and remux path as a fallback when a matching muxed format is unavailable.
- Keep muxed and video-only formats distinct during format de-duplication.

## Partial playback status

Offline playback of an incomplete download is not enabled by this change. The current download model preallocates files and persists out-of-order byte ranges; exposing those `.part` files to the player would present missing ranges as holes and can produce invalid media reads. A safe implementation needs a contiguous-prefix preview source and player-side waiting/EOF handling, plus container metadata support. This change makes muxed downloads sequential, which is a prerequisite, but does not claim partial playback is complete.
