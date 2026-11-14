# Sound page parser, InnerTube versions, collaborators sheet

Not compiled or run in the authoring sandbox (no Android SDK, no route to youtube.com). Every new parser
branch has a unit test in `SoundResilienceTest`; please run `./gradlew :app:testGithubDebugUnitTest` and
`:app:spotlessCheck`, then try a few sounds on a device.

## Sound page parser
Checked against YouTube.js's `PivotButton`, `ShortsLockupView`, `ReelPlayerOverlay` node definitions and the
live-captured fixtures already in `app/src/test/resources`.

- `soundAnchor`: reads *every* pivot button (was: the first one only) and prefers the one that links to
  `FEsfv_audio_pivot`; falls back to an audio-pivot link found anywhere in the response.
- Sound title: `soundAttributionTitle` is now read as plain text, runs or view-model `content` (was: `content` only).
- Reel lockups: id also from `entityId` (`shorts-shelf-item-<id>`) and the inline preview command when the tap
  command carries no video id; thumbnail falls back to the legacy `thumbnail.sources`.
- `soundReels()`: a thin page (< 8 strict lockups) is topped up with `reelWatchEndpoint` reels the strict parser
  skipped (was: only when the strict parse found nothing, so a page showing 1 Short stayed at 1). `/shorts/` text
  links are used only when nothing else was found. Full pages are never mixed with loose finds.
- Paging: the next-page token is found inside `commandExecutorCommand` wrappers as well as directly.
- `YouTube.soundFeed`: if every route fails, the last copy of that sound (any age) is shown instead of an error;
  the first-screen fill target is 6 Shorts (was 2) and a repeated continuation token ends the fill loop.

## InnerTube versions (from yt-dlp `master`, `youtube/_base.py`)
MWEB 2.20260708.05.00, WEB_REMIX 1.20260707.12.00, WEB_CREATOR 1.20260708.06.00, TVHTML5 7.20260707.07.00.
Left alone on purpose: WEB (already newer, read live), and ANDROID / IOS / ANDROID_VR / VISIONOS, whose values are
tuned together with their user agents and attestation rules (the VISIONOS note in `YouTubeClient.kt` explains why
that is version-sensitive). yt-dlp reports `android_vr` 1.65.10 returning 403 for all formats since 2026-08-17.

## Collaborators sheet
Cause: on the AMOLED theme the sheet fill is #050505 on a #000000 page and its rows are #141414, so the sheet
looked see-through. `QuickActionsSheet` now lifts the fill one step and draws the existing neutral AMOLED
hairline (`amoledCardOutline`) on AMOLED only. Light and Dark are unchanged. This fixes every quick-actions menu.
