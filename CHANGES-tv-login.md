# YouTube TV (code) sign-in: reliability pass

## Sign-in screen
- QR code that opens google.com/device with the code already filled in, plus the code, Copy and Open buttons.
- Live "expires in m:ss" countdown. An expired code is replaced on its own; no "try again" for a timeout.
- Coming back from the browser checks at once instead of waiting out the poll interval.
- The sign-in lives in `AccountSignInViewModel`, so rotating the phone or going to the browser keeps the same code.
- Network trouble while waiting shows "still trying" and keeps polling instead of failing the whole sign-in.

## OAuth layer (`account/tv/TvOAuth.kt`)
- Every call retries 429/5xx/IO errors with back-off.
- OAuth client is read from the TV page with several patterns, cached in the encrypted store, and re-read once on `invalid_client`.
- `refresh()` now says *why* it failed: `Revoked` (needs a new code) vs `Unavailable` (offline; nobody is signed out).

## Session handling (`AccountManager`, `InnerTube`)
- A 401 on any TV call (browse, like/subscribe/playlist/history calls, watch-time pings) drops the cached token, refreshes, and repeats the call once.
- A revoked grant flags the account (`tvReauthNeeded`); Settings > Account shows a "Sign in again" card that opens the TV code page, and the new tokens replace the dead entry in place (no duplicate "YouTube TV" account, history kept).
- A token that has not lapsed is still used when the refresh endpoint cannot be reached.

## Everything else through the TV client (second pass)
- **Shorts**: for a TV account the reel sequence (first page, continuations, "more like this") is requested from the TV client with the bearer token; the Shorts feed is the account's feed whenever it answers, with the old anonymous route only as a fallback so the tab is never empty.
- **Search**: sent as the TV account first (so it lands in the account's search history/recommendations). Its answer is used only if it contains the web renderers the parser reads; otherwise the previous route serves and TV search is switched off for the session. Incognito still searches anonymously.
- **Comments**: posting, replying, editing and deleting now work for a TV account (the comment box token is read from the TV `/next` answer, or from the comments page it points to). The comment/reply/bell calls default to the account's own client.
- **Notification bell**: mirrored to the account for TV accounts too (token read from the TV channel page).

None of these four could be run against live YouTube from the authoring sandbox. The TV client is not meant to expose them, so each one fails soft: comments/bell report a plain error, search and Shorts fall back. Please try them on a real TV-signed account and tell me which ones YouTube refuses.

Still web-only: the music library sync.

## Tests
`TvOAuthTest`, `FormatCountdownTest`. Not compiled in the authoring sandbox (no Android SDK); `TvOAuth.kt` was compiled and its parsing logic run standalone.

## Audit pass
- Signing out of (or removing) a TV account now revokes the grant at Google in the background, so the phone also disappears from the account's approved TV devices. Sign out all does the same for every TV account.
- Checked and found already correct: bearer token on all TV account calls (likes, subscribe, playlists, history controls, feedback, stats pings), 401 retry, backup/restore of TV accounts, profile lookup, Shorts/comment/bell gates.
- Known remaining gaps: live-chat sending and the members-only playback retry still use the web cookie; no TV import of Watch Later / playlist list (needs live browse ids to verify).
