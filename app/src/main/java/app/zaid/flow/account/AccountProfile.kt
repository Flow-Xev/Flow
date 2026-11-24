package app.zaid.flow.account

import kotlinx.serialization.Serializable

/**
 * A signed-in account as the UI sees it. The session cookie is deliberately not part of this: it
 * stays inside [AccountManager]'s encrypted store.
 */
@Serializable
data class AccountProfile(
    val id: String,
    val name: String? = null,
    val email: String? = null,
    val handle: String? = null,
    val avatarUrl: String? = null,
    val addedAt: Long = 0L,
) {
    /** The best human-readable label we have for this account. */
    val displayName: String
        get() = name?.takeIf { it.isNotBlank() } ?: email ?: handle ?: "Google account"

    /** A one-letter fallback for when there is no avatar. */
    val initial: String
        get() = displayName.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"
}
