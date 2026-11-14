# Player screen UI (HTML design, original button order)

UI only. The video player, gestures, playback and stream code are untouched.

- **Layout from Player_action_row.html**: 20sp title, a meta line (`Channel  65K likes  31K views  4 yr ago`), then avatar +
  your original Subscribe button on the left and a 156dp pill capsule on the right (4 buttons visible, swipe for the rest).
- **Button order is the app's original**: Like, Dislike, Ask AI, Download, Products, Save, Share, Note, Background.
- **No "..." anywhere**: titles (any max-lines setting), the channel name and the meta line fade out at the end instead.
  The "...more" label is gone; tapping the title or meta line still opens the description.
- Subscribe is your original button (`FlowSubscribeButton`, Mini size); once subscribed it stays as slim as the Subscribe pill, with no bell (tap it for the notifications / unsubscribe menu).
- New string: `likes_count_short_template` (English only, other languages fall back).

Files: `VideoInfoSection.kt`, `VideoActionRow.kt`, `FlowSubscribeButton.kt`, `values/strings.xml`.
Base: Flow-main-sound-upgrade-v2 + Flow-client-updater-overlay-v4.

# Stage 3 — download finishing feedback

- The MP4 remuxer already reports sample-copy progress; the download pipeline now forwards that
  progress to the active-download state instead of leaving the row at an indeterminate "Merging"
  label until the muxer closes.
- The downloads row shows a localized merge percentage while preserving the existing merge state,
  cancellation behaviour, and progress cadence. No extra polling loop or network work was added.
- Partial playback during transfer is not enabled in this stage: the current downloader writes
  parallel, out-of-order byte ranges for separate audio/video tracks, then creates a finalized MP4.
  Exposing those `.part` files as media would risk corrupt seeking or playback. A real solution needs
  an ordered fragmented-media pipeline (or a supported muxed stream) rather than simply opening a
  partially written file.
