package app.zaid.flow.player.quality

import android.content.Context
import app.zaid.flow.player.network.StreamingNetwork
import app.zaid.flow.player.network.StreamingNetwork.Kind

/**
 * The download speed the app has actually seen, kept apart for Wi-Fi and mobile data and remembered
 * between launches. Shorts Auto reads it as a visit starts, because the player's own estimate is a
 * guess for your country until it has downloaded enough, and a wrong guess would pick the highest
 * quality on a slow connection.
 */
object MeasuredSpeed {
    private const val PREFS = "measured_speed"
    private const val MIN_SAMPLE_BYTES = 100_000L
    private const val BITS_PER_BYTE_MS = 8_000L
    private const val OLD_WEIGHT = 0.7
    private const val NEW_WEIGHT = 0.3
    private const val SAVE_INTERVAL_MS = 5_000L

    private val cache = java.util.concurrent.ConcurrentHashMap<Kind, Long>()

    @Volatile private var lastSaveAt = 0L

    private fun keyOf(kind: Kind) = "${kind.key}_bps"

    /** The kind a legacy wifi/mobile flag stands for right now. */
    private fun kindFor(onWifi: Boolean): Kind =
        if (onWifi) Kind.WIFI else StreamingNetwork.kind.takeIf { it.isCellular } ?: Kind.CELL_4G

    /** Bits per second last seen on this kind of network, or 0 when nothing has been measured yet. */
    fun read(
        context: Context,
        onWifi: Boolean,
    ): Long = read(context, kindFor(onWifi))

    fun read(
        context: Context,
        kind: Kind,
    ): Long =
        cache[kind] ?: prefs(context).getLong(keyOf(kind), 0L).also { cache[kind] = it }

    /** What a new player on [kind] should assume: the measured speed there, else the kind's default. */
    fun startEstimate(
        context: Context,
        kind: Kind,
    ): Long = read(context, kind).takeIf { it > 0L } ?: kind.defaultStartBps

    fun record(
        context: Context,
        onWifi: Boolean,
        bytesLoaded: Long,
        loadTimeMs: Long,
    ) = record(context, kindFor(onWifi), bytesLoaded, loadTimeMs)

    /** Folds one finished download into the running figure. Small downloads say little, so they are skipped. */
    fun record(
        context: Context,
        kind: Kind,
        bytesLoaded: Long,
        loadTimeMs: Long,
    ) {
        if (bytesLoaded < MIN_SAMPLE_BYTES || loadTimeMs <= 0L) return
        val sample = bytesLoaded * BITS_PER_BYTE_MS / loadTimeMs
        cache[kind] = blend(read(context, kind), sample)
        val now = System.currentTimeMillis()
        if (now - lastSaveAt < SAVE_INTERVAL_MS) return
        lastSaveAt = now
        prefs(context).edit().apply { cache.forEach { (k, v) -> putLong(keyOf(k), v) } }.apply()
    }

    internal fun blend(
        previous: Long,
        sample: Long,
    ): Long = if (previous <= 0L) sample else (previous * OLD_WEIGHT + sample * NEW_WEIGHT).toLong()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
