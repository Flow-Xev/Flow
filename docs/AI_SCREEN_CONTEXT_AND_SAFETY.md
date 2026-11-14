# Flow AI: screen context and permission boundaries

## Screen context

`FlowAiController.updateScreenContext(route, visibleContent)` accepts an optional, bounded summary of the visible screen. `updateVisibleScreenContent(visibleContent)` can update that summary when a screen changes its visible state. The controller strips control characters, truncates to 1,200 characters, and redacts common `token`, `password`, `authorization`, and `cookie` assignments.

The app shell currently supplies only the navigation route. Individual screens still need to call the visible-content API with a concise summary of what the user can see. Do not pass hidden state, API keys, account secrets, private fields, or entire data repositories. This mechanism does not capture screenshots or read other apps.

## Music library

The existing `get_music_library` tool is read-only. It is gated by the Flow AI local-music opt-in and Android media permission. It can list metadata; it cannot change, upload, or delete files.

## Consent and sensitive actions

The current app does not expose general device-control actions through Flow AI, so there is no safe basis for a universal Allow once / Always allow / Don't allow broker yet. The existing music-library toggle is a persistent opt-in, not a grant for unrelated actions. Future sensitive or destructive actions must have their own confirmation and must not inherit an Always allow choice.

## Token efficiency

The system instructions are concise. After a completed assistant response, old conversation history is trimmed to the latest eight user text turns, retaining the tool exchanges associated with those turns. Screen summaries are limited to 1,200 characters.
