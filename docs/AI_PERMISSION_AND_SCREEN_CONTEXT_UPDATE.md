# Flow AI permission and screen-context update

## Implemented in this update

- Flow AI receives the current navigation destination on every app route change.
- Flow AI receives the currently playing video's title and channel when available.
- Screens can provide a bounded, user-visible summary through `updateVisibleScreenContent`; the controller strips common credential-like fields and caps the summary at 1,200 characters.
- Reading local music, watch history, and search history now requires an in-app consent decision: **Allow once**, **Always allow**, or **Don't allow**.
- Local music additionally requires the existing Flow AI local-music setting and Android's media permission. The Android permission dialog is still shown when needed.
- **Always allow** is stored separately for each private-data category. It can be revoked in Settings > Integrations > Flow AI > Reset AI data permissions.
- Consent prompts fail closed when dismissed, the AI sheet closes, or the request is cancelled.
- Existing limits remain: recent conversation history is bounded, screen summaries are capped, and local music access is read-only.

## Deliberate platform and privacy limits

- This does not capture screenshots or read arbitrary UI pixels, hidden fields, passwords, or other apps.
- Route context is automatic for all Flow navigation destinations, but detailed content context is available only where a screen explicitly supplies a safe summary. It is not truthful to describe this as automatic full-content access to every screen yet.
- Android media permission is independent of Flow AI's in-app consent. **Always allow** cannot override a revoked Android permission.
- Flow currently exposes no arbitrary shell, file-write, purchase, message-send, or device-control tool to the AI. There is therefore no unsafe generic device-action permission bypass.
- The AI can still use the existing supported video, transcript, search, history, local-music, open-video, and seek tools, subject to their existing app settings and consent checks.

## Verification

ZIP structure and Kotlin source edits were checked. Gradle compilation was attempted with `:app:compileDebugKotlin`, but the wrapper could not download Gradle because `services.gradle.org` was unreachable. A successful compile and on-device runtime test are still required before release.
