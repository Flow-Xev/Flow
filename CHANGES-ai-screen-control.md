# Stage 3: AI controls screens + permission prompts

- New AI tools: `open_screen` (home, shorts, music, subscriptions, library, search, explore, history,
  downloads, local_music, saved_shorts, notes, notifications, equalizer), `list_downloads`, `manage_download`
  (pause / resume / delete).
- Deleting a download asks first: Allow / Always allow / Don't allow (same prompt as music library,
  watch history and search history). "Always" is stored and cleared by resetPrivateDataPermissions().
- FlowApp navigates to the route the AI opens (tabs use navigateToTab).

# Stage 4: in-progress playback fix
- Preview no longer needs the download to be actively running or a single-file stream. Any unfinished
  download with >=1% (and 256 KB per part) plays offline, including paused and failed ones.
- Plays only bytes really on disk (PartialDownload): single-file streams use the file length,
  block-parallel parts use blocks done from the start. Video + audio parts play together (no merge).
- Playback ends a little before the downloaded point; reading past it is a clean end, not an error.
- Rows become tappable at 3% for any non-cancelled, non-merging state.

# Downloads audit
- Video no longer forced onto one connection (it was `sequential = true` for every download): parallel again.
- No-merge path now also used for videos with one unlabelled audio track (before, only labelled dubs matched).
- Download dialogs list one entry per quality again (muxed/video-only variants are separated only for the resolver).
- Partial playback replaces the real stream only when the user taps the row or the device is offline.
