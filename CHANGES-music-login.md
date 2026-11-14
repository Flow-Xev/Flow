# Music sign-in and account feed: pass 1

## Two separate sign-ins
`SignInTarget.YouTube` is the sign-in exactly as before (lands on www.youtube.com, www cookies only, visitor id untouched), so video playback and PoToken behaviour are unchanged.
`SignInTarget.Music` is the new one described below. The chooser lists YouTube, YouTube Music and YouTube TV; the Music tab lists Music first, everywhere else YouTube first.
Both store the same account (same session key), so each shows up in the one account list.

## Google sign-in (`AccountSignInScreen.kt`), Music target
- Sign-in now ends on **music.youtube.com** (was www.youtube.com), so the captured session carries YouTube Music's own cookies.
- Cookies are merged from music.youtube.com, www.youtube.com and youtube.com (one value per name), like ArchiveTune does.
- The page's **visitor id** (ytcfg `VISITOR_DATA`) is read and handed to `AccountManager.signIn`, which applies it *before* the account is activated, so the feed reload that follows asks as the signed-in session.
- Replaced the blind 1.2 s wait: the ViewModel signs in, then waits (up to 8 s) for the account's name/photo, and the screen shows a **welcome** with the avatar and name.
- Page-load progress bar; a failed page load shows an error card with **Try again** / Cancel instead of bare red text.
- WebView is released properly (JS interface removed, destroyed).

## Tests
`HoldsFullSessionTest`. Not compiled or run: no Android SDK in the authoring sandbox.

## Deliberately not done
- `dataSyncId` capture (ArchiveTune stores it). Flow already resolves brand-account identity through its delegated session, and setting `onBehalfOfUser` blindly could break personal accounts. Needs a device test first.

## Visitor id: get, send, refresh (YouTube and Music sign-ins)
- **Get:** both sign-ins read the page's `VISITOR_DATA` and hand it to `AccountManager.signIn`.
- **Keep per account:** `VisitorIdentityStore` now stores a visitor per account (same non-backed-up prefs file, same 7-day reuse rule). Every identity change while an account is active, including re-rolls and rotations, is followed into that account's copy.
- **Send:** the account's visitor is applied *before* it becomes active (sign-in, account switch, launch), so the feed reload that follows asks as that session. It goes out the way it always did (`X-Goog-Visitor-Id` and the request context). On launch the account's visitor wins over the shared one.
- **Refresh:** 4 s after launch, if the active web account's visitor is older than 7 days, it is replaced with the id the signed-in session is served (`sw.js_data` fetched with the account's cookie). A failed fetch changes nothing; an account switched mid-request is ignored.
- Removing an account forgets its visitor. TV accounts are unaffected.
- PoToken: the session already re-attests when the visitor changes, so the first play after a new id can be slower.
- Not verified on a device: that `sw.js_data` with the cookie returns the signed-in id. If it returns an anonymous one the refresh still yields a valid visitor, just not account-bound.
