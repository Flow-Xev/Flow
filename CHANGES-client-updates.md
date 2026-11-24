# YouTube client auto-update (with reviewer)

Settings > Network > "YouTube client updates": mode Automatic (weekly) / Manual only / Off, auto-add new client
features, Check now, Update log, Reset to built-in versions.

Pipeline for every candidate version: **find -> review -> adopt on probation -> verify or roll back**.

1. Find (ClientUpdater): youtube.com / m. / music. / tv pages (first-party) + optional https manifest (`MANIFEST_URL`).
2. Review (ClientReviewer, nothing skips it):
   - offline rules: shape per client, valid calendar date, same major as built-in, not from the future, not vetoed
     by the manifest (`"blocked"`), not quarantined, and an unconfirmed (manifest-only) version may not jump >120 days.
   - canary probe: one read-only call (signed in: the account's home feed, through InnerTube with the real session;
     signed out: an anonymous search; music: music home) sent with the current version (control) and the candidate. FAIL only if the
     control passed and the candidate did not, so a broken probe never condemns a good version.
   - probe inconclusive: accepted only if YouTube's own page serves that version; a manifest-only claim is deferred.
3. Adopt on probation (ClientHealth): real search traffic is counted. >=3 rejections at >=25% rolls back to the last
   proven version ("stable", else built-in) and quarantines the bad one for 7 days; a clean record (or a quiet week)
   promotes it to stable.
4. Remote kill-switch: a live version the manifest lists under `blocked` is rolled back on the next check.
5. Safety nets: stored versions are re-validated on load; failures never throw into requests; refresh on 4xx runs in
   Automatic mode at most once per 24h; every decision lands in the Update log (last 30).

Only WEB, MWEB, WEB_REMIX, TVHTML5 move; ANDROID / IOS / ANDROID_VR pin version + UA + build together.
Features: `ClientUpdateSettings.isFeatureEnabled(client, name)`; with auto-add off they wait in "pending".

## v4: never an old version, real factory reset

- **No step back, ever.** `ClientUpdateSettings.update` is now the single choke point: any stored live or stable version
  that is not strictly newer than the compiled-in one is dropped on every write and on load. A rollback therefore ends
  on the last proven version *or the built-in one*, never something older.
- **Race fixed.** A check reviews candidates for several seconds. The write now re-checks each move against the state
  at that moment (`canAdopt`: newer than what is live, not quarantined), so a slow review can no longer overwrite a
  newer version or re-adopt one that was just rolled back.
- **Reviewer rule:** a candidate must be newer than both the version in use and the built-in one.
- **Stable only moves forward** (`promoteStable`).
- **Reset to built-in versions** (Settings > Network > YouTube client updates): asks for confirmation, then
  forgets every learned version, stable marker, probation and learned/pending feature. Quarantine is kept. It runs
  under the updater's lock (an in-flight check cannot undo it) and restarts the check timers (`lastCheck`,
  `lastEmergency`); the weekly worker skips until the interval has really passed. "Check now" still updates on demand.
