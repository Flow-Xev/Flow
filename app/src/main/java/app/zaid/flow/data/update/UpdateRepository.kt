package app.zaid.flow.data.update

import android.os.Build
import app.zaid.flow.BuildConfig
import app.zaid.flow.data.local.LocalDataManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val NIGHTLY_CHANNEL = "nightly"

/** Where an automatic check may surface a release; each announces a version once. */
enum class UpdateAnnouncement { LAUNCH_PAGE, NOTIFICATION }

/** How the automatic checks space themselves out and which releases they may show. */
internal object UpdateSchedule {
    val COOLDOWN_MS: Long = TimeUnit.HOURS.toMillis(12)

    fun isDue(
        lastCheckMs: Long,
        nowMs: Long,
    ): Boolean = nowMs < lastCheckMs || nowMs - lastCheckMs >= COOLDOWN_MS

    fun mayAnnounce(
        version: String,
        skippedVersion: String?,
        announcedVersion: String?,
    ): Boolean = version != skippedVersion && version != announcedVersion
}

/**
 * The one place Flow asks GitHub about releases. The launch check, the background worker and the
 * Settings button all come through here, so a cold start never fetches twice and a skipped version
 * stays skipped everywhere.
 */
@Singleton
class UpdateRepository
    @Inject
    constructor(
        private val client: OkHttpClient,
        private val dataManager: LocalDataManager,
    ) {
        private val mutex = Mutex()
        private val isNightly = BuildConfig.UPDATE_CHANNEL == NIGHTLY_CHANNEL
        private val _latest = MutableStateFlow<AppRelease?>(null)

        /** The newest release found this process, or null when none is known or this build is current. */
        val latest: StateFlow<AppRelease?> = _latest.asStateFlow()

        /** Asks GitHub now. Throws when the request fails, so a check the user started can say so. */
        suspend fun fetch(): AppRelease? = mutex.withLock { fetchLocked() }

        /**
         * For the automatic checks: fetches only once the cooldown has passed, and returns a release
         * only if [announcement] has not shown this version before and the user has not skipped it.
         */
        suspend fun releaseToAnnounce(
            announcement: UpdateAnnouncement,
            nowMs: Long = System.currentTimeMillis(),
        ): AppRelease? =
            mutex.withLock {
                if (!UpdateSchedule.isDue(dataManager.lastUpdateCheck.first(), nowMs)) return@withLock null
                val release = runCatching { fetchLocked() }.getOrNull() ?: return@withLock null
                val announced =
                    when (announcement) {
                        UpdateAnnouncement.LAUNCH_PAGE -> dataManager.promptedUpdateVersion.first()
                        UpdateAnnouncement.NOTIFICATION -> dataManager.notifiedUpdateVersion.first()
                    }
                if (!UpdateSchedule.mayAnnounce(release.version, dataManager.skippedUpdateVersion.first(), announced)) {
                    return@withLock null
                }
                when (announcement) {
                    UpdateAnnouncement.LAUNCH_PAGE -> dataManager.setPromptedUpdateVersion(release.version)
                    UpdateAnnouncement.NOTIFICATION -> dataManager.setNotifiedUpdateVersion(release.version)
                }
                release
            }

        suspend fun skip(version: String) = dataManager.setSkippedUpdateVersion(version)

        // Update checking is removed: no network request is ever made.
        private suspend fun fetchLocked(): AppRelease? = null
    }
