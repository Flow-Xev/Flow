package app.zaid.flow.utils.potoken

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The YouTube visitor identity across restarts, and the one place it is written.
 *
 * GVS walls media per (visitor, client): a walled visitor's streams stop a minute in on every
 * video, through any network or VPN (#921, #1251). So an identity that was rotated or reset must
 * stay replaced after a restart, and must never come back from a backup onto another install. It
 * lives in its own preferences file so the backup rules can leave it out.
 */
@Singleton
class VisitorIdentityStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val prefs: SharedPreferences by lazy { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

        /** The stored visitor while it is fresh enough to reuse; null means fetch a new one. */
        fun restore(nowMs: Long = System.currentTimeMillis()): String? {
            dropLegacyCopy()
            val stored = prefs.getString(KEY_VISITOR, null)
            return stored.takeIf { VisitorIdentityAge.isReusable(stored, prefs.getLong(KEY_SAVED_AT, 0L), nowMs) }
        }

        /**
         * The signed-in account whose identity changes are also kept under its own key. Set by the account
         * manager, so each account keeps the visitor it signed in with instead of sharing one.
         */
        @Volatile var activeAccountId: String? = null

        /** Records [visitorData] as the identity in use; null or blank forgets it. */
        fun save(
            visitorData: String?,
            nowMs: Long = System.currentTimeMillis(),
        ) {
            activeAccountId?.let { saveForAccount(it, visitorData, nowMs) }
            if (visitorData.isNullOrBlank()) {
                prefs
                    .edit()
                    .remove(KEY_VISITOR)
                    .remove(KEY_SAVED_AT)
                    .apply()
                return
            }
            if (prefs.getString(KEY_VISITOR, null) == visitorData) return
            prefs
                .edit()
                .putString(KEY_VISITOR, visitorData)
                .putLong(KEY_SAVED_AT, nowMs)
                .apply()
        }

        /** The visitor [accountId] last used, while it is fresh enough to reuse. */
        fun restoreForAccount(
            accountId: String,
            nowMs: Long = System.currentTimeMillis(),
        ): String? {
            val stored = prefs.getString(accountKey(KEY_VISITOR, accountId), null)
            return stored.takeIf { VisitorIdentityAge.isReusable(stored, prefs.getLong(accountKey(KEY_SAVED_AT, accountId), 0L), nowMs) }
        }

        /** The active account's visitor, or null when nobody is signed in or it has gone stale. */
        fun restoreActiveAccount(nowMs: Long = System.currentTimeMillis()): String? = activeAccountId?.let { restoreForAccount(it, nowMs) }

        /** Records [visitorData] for [accountId]; an unchanged value keeps its original age, so reuse still expires. */
        fun saveForAccount(
            accountId: String,
            visitorData: String?,
            nowMs: Long = System.currentTimeMillis(),
        ) {
            val visitorKey = accountKey(KEY_VISITOR, accountId)
            val savedAtKey = accountKey(KEY_SAVED_AT, accountId)
            if (visitorData.isNullOrBlank()) {
                prefs.edit().remove(visitorKey).remove(savedAtKey).apply()
                return
            }
            if (prefs.getString(visitorKey, null) == visitorData) return
            prefs.edit().putString(visitorKey, visitorData).putLong(savedAtKey, nowMs).apply()
        }

        /** Drops everything kept for a removed account. */
        fun forgetAccount(accountId: String) {
            prefs.edit().remove(accountKey(KEY_VISITOR, accountId)).remove(accountKey(KEY_SAVED_AT, accountId)).apply()
        }

        private fun accountKey(
            base: String,
            accountId: String,
        ) = "${base}_$accountId"

        /**
         * Spends one identity re-roll from the persisted budget. Returns the time it was taken, to
         * hand back to [refundReroll] if the replacement could not be fetched, or null when the
         * budget is spent.
         */
        @Synchronized
        fun tryTakeReroll(nowMs: Long = System.currentTimeMillis()): Long? {
            val history = RerollBudget.parse(prefs.getString(KEY_REROLLS, null))
            val taken = RerollBudget.take(history, nowMs) ?: return null
            prefs.edit().putString(KEY_REROLLS, RerollBudget.format(taken)).apply()
            return nowMs
        }

        @Synchronized
        fun refundReroll(takenAtMs: Long) {
            val history = RerollBudget.parse(prefs.getString(KEY_REROLLS, null))
            prefs.edit().putString(KEY_REROLLS, RerollBudget.format(history - takenAtMs)).apply()
        }

        // Earlier versions kept the visitor in the backed-up flow_prefs. It is deleted rather than
        // moved, so a visitor walled before this version is not carried into it.
        private fun dropLegacyCopy() {
            val legacy = context.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
            if (!legacy.contains(LEGACY_KEY_VISITOR) && !legacy.contains(LEGACY_KEY_FETCHED_AT)) return
            legacy
                .edit()
                .remove(LEGACY_KEY_VISITOR)
                .remove(LEGACY_KEY_FETCHED_AT)
                .apply()
        }

        private companion object {
            const val PREFS_NAME = "youtube_identity"
            const val KEY_VISITOR = "visitor_data"
            const val KEY_SAVED_AT = "visitor_data_saved_at"
            const val KEY_REROLLS = "visitor_rerolls"
            const val LEGACY_PREFS_NAME = "flow_prefs"
            const val LEGACY_KEY_VISITOR = "visitor_data"
            const val LEGACY_KEY_FETCHED_AT = "visitor_data_fetched_at"
        }
    }

internal object VisitorIdentityAge {
    const val MAX_AGE_MS = 7L * 24L * 60L * 60L * 1_000L

    fun isReusable(
        visitorData: String?,
        savedAtMs: Long,
        nowMs: Long,
    ): Boolean = !visitorData.isNullOrBlank() && savedAtMs > 0L && nowMs - savedAtMs in 0 until MAX_AGE_MS
}

/** At most [LIMIT] wall re-rolls in any [WINDOW_MS], so a network that walls every visitor cannot loop. */
internal object RerollBudget {
    const val LIMIT = 2
    const val WINDOW_MS = 6L * 60L * 60L * 1_000L

    /** The history with [nowMs] added, or null when [LIMIT] re-rolls already fall inside the window. */
    fun take(
        history: List<Long>,
        nowMs: Long,
    ): List<Long>? {
        val recent = history.filter { nowMs - it in 0 until WINDOW_MS }
        if (recent.size >= LIMIT) return null
        return recent + nowMs
    }

    fun parse(stored: String?): List<Long> = stored?.split(',')?.mapNotNull { it.trim().toLongOrNull() }.orEmpty()

    fun format(history: List<Long>): String = history.joinToString(",")
}
