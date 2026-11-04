package app.zaid.flow.data.stats

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.zaid.flow.data.recommendation.music.MusicBrainEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton

private val Context.recapPreferences by preferencesDataStore(name = "flow_recap")
private val LAST_SHOWN = stringPreferencesKey("last_shown_month")
private val LAST_SEEN_AT = longPreferencesKey("last_seen_at")

/**
 * Whether a month or a year has just closed with enough in it to be worth a recap, checked only when a screen
 * that offers it opens. No job, alarm or notification ever runs for it.
 */
@Singleton
class RecapReadiness
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val videoStats: VideoStatsRecorder,
        private val musicBrain: MusicBrainEngine,
    ) {
        /**
         * The recap waiting to be offered: in January last year's, when it held enough; otherwise the
         * month that just closed. Null when nothing is waiting or it was already opened or dismissed.
         */
        suspend fun readyPeriod(now: YearMonth = YearMonth.now()): RecapPeriod? {
            val shown = context.recapPreferences.data.first()[LAST_SHOWN]
            if (now.monthValue == JANUARY) {
                val year = now.year - 1
                val activity = (1..MONTHS_PER_YEAR).sumOf { activityIn(LedgerTime.monthKey(YearMonth.of(year, it))) }
                if (shown != year.toString() && activity >= MIN_YEAR_ACTIVITY) return RecapPeriod.Year(year)
            }
            val closed = now.minusMonths(1)
            val key = LedgerTime.monthKey(closed)
            if (shown == key) return null
            return RecapPeriod.Month(closed).takeIf { activityIn(key) >= MIN_ACTIVITY }
        }

        /**
         * Whether the Recap card should be on Library: it shows again a day after it was last
         * opened or dismissed, and always on first use.
         */
        suspend fun isDue(now: Long = System.currentTimeMillis()): Boolean {
            val seenAt = context.recapPreferences.data.first()[LAST_SEEN_AT] ?: return true
            // A clock set back must not hide the card for longer than a day.
            return now < seenAt || now - seenAt >= CARD_INTERVAL_MS
        }

        /** The card was opened or dismissed; it stays away for a day. */
        suspend fun markSeen(now: Long = System.currentTimeMillis()) {
            context.recapPreferences.edit { it[LAST_SEEN_AT] = now }
        }

        suspend fun markShown(period: RecapPeriod) {
            val key =
                when (period) {
                    is RecapPeriod.Month -> LedgerTime.monthKey(period.month)
                    is RecapPeriod.Year -> period.year.toString()
                    RecapPeriod.AllTime -> return
                }
            context.recapPreferences.edit { it[LAST_SHOWN] = key }
        }

        private suspend fun activityIn(monthKey: String): Int = videoStats.monthViews(monthKey) + musicBrain.monthPlays(monthKey)

        private companion object {
            const val CARD_INTERVAL_MS = 24L * 60L * 60L * 1000L
            const val MIN_ACTIVITY = 20
            const val MIN_YEAR_ACTIVITY = 100
            const val JANUARY = 1
            const val MONTHS_PER_YEAR = 12
        }
    }
