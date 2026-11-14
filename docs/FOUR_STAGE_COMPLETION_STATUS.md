# Four-stage request: implementation status

## Stage 1 — Download language selection
- The compact download dialog already exposes audio-track choices.
- Track choices now remain distinct when an InnerTube client omits track IDs; the language tag is used as the fallback identity. This prevents different language variants from being collapsed into one choice.
- The existing selector prefers the original track when available and passes the chosen track into AAC selection.

## Stage 2 — Progressive local preview
- The downloader writes the video stream sequentially to a growing `.video.part` file.
- Preview eligibility now begins after a 1 MiB probe window instead of waiting for 10% of the full file.
- The growing-file data source waits for later bytes and does not expose preallocated zero-filled regions.
- Limitation: an MP4 whose index (`moov`) is at the end, or a separate video/audio download, may not produce audible playback until enough metadata/audio is available. This change does not guarantee arbitrary-time seeking or full A/V playback at every percentage.

## Stage 3 — AI token use and screen context
- Shortened the repeated system instructions and capped retained chat context to the latest eight user turns after a completed answer, keeping tool-call exchanges attached to their turn.
- Added an explicit, bounded screen-summary input (`updateScreenContext(route, visibleContent)` / `updateVisibleScreenContent`) with basic redaction for common secret fields. Screens must opt in and supply only visible, non-sensitive text.
- FlowApp currently supplies the route label only. This is not yet wired to every screen's visible content, and does not grant unrestricted access to other apps or Android screens.
- The music-library tool already exists, is read-only, and runs only when its persistent setting and Android media permission are both available.

## Stage 4 — Permissions and safety
- The existing music-library opt-in is off by default and remains read-only; Android media permission is still required.
- Sensitive/destructive actions must not inherit a persistent "always allow" grant. They require separate confirmation.
- Limitation: this ZIP does not add a new per-action Allow once / Always allow / Don't allow dialog. The app currently has a persistent read-only music-library setting, not a general consent broker for future AI actions. No arbitrary device-control tools or accessibility service were added.

## Verification
- Gradle compilation could not be run in this environment because the Gradle distribution could not be downloaded (network DNS unavailable).
- Test on device before release, especially language selection against multi-dub videos and progressive playback for muxed MP4, video-only MP4, and WebM sources.
