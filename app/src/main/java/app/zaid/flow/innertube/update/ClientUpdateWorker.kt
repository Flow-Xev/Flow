package app.zaid.flow.innertube.update

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Runs the weekly client check in AUTO mode. In MANUAL and OFF it is not scheduled at all. */
class ClientUpdateWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        ClientUpdateSettings.ensureLoaded()
        val cfg = ClientUpdateSettings.state.value
        if (cfg.mode != ClientUpdateMode.AUTO) return Result.success()
        // A check (or a factory reset) restarted the clock: do not undo it before the interval has really passed.
        if (System.currentTimeMillis() - cfg.lastCheck < cfg.intervalDays * DAY_MS - SLACK_MS) return Result.success()
        return try {
            if (ClientUpdater.check(apply = true).status == ClientUpdater.Status.FAILED) Result.retry() else Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never crash-loop: retry a few times with backoff, then wait for the next weekly run.
            if (runAttemptCount < 4) Result.retry() else Result.success()
        }
    }

    companion object {
        const val WORK_NAME = "client_update_work"
        private const val DAY_MS = 86_400_000L
        private const val SLACK_MS = 12 * 3_600_000L

        /** Call at launch and after every settings change: schedules in AUTO, cancels otherwise. */
        fun sync(context: Context) {
            ClientUpdateSettings.ensureLoaded()
            val cfg = ClientUpdateSettings.state.value
            val wm = WorkManager.getInstance(context)
            if (cfg.mode != ClientUpdateMode.AUTO) {
                wm.cancelUniqueWork(WORK_NAME)
                return
            }
            val request =
                PeriodicWorkRequestBuilder<ClientUpdateWorker>(cfg.intervalDays.toLong(), TimeUnit.DAYS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                    .build()
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
