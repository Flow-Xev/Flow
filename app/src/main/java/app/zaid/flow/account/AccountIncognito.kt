package app.zaid.flow.account

/**
 * Whether account incognito is on. A plain flag so the stores and the reporter, which have no
 * access to [AccountManager], can honour it where they already write. [AccountManager] owns the
 * persisted value and keeps this in step with it.
 */
object AccountIncognito {
    @Volatile
    var enabled: Boolean = false
}
