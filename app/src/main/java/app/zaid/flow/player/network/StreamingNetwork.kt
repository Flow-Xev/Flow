package app.zaid.flow.player.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * The live state of the active connection, for decisions that cannot suspend (bandwidth meter
 * start values, buffer sizing, Auto's ceiling). Mirrors the default network via a callback, so a
 * Wi-Fi to mobile switch or a 4G to 3G drop is seen at once. Cellular generation is judged from
 * the link's reported downstream rate, which needs no phone-state permission.
 */
object StreamingNetwork {
    enum class Kind(
        val key: String,
        /** What a first-time viewer on this link is assumed to get, in bits per second. */
        val defaultStartBps: Long,
    ) {
        WIFI("wifi", 6_000_000L),
        ETHERNET("ethernet", 10_000_000L),
        CELL_5G("5g", 6_000_000L),
        CELL_4G("4g", 3_000_000L),
        CELL_3G("3g", 900_000L),
        CELL_2G("2g", 250_000L),
        OTHER("other", 2_000_000L),
        ;

        val isCellular: Boolean get() = this == CELL_5G || this == CELL_4G || this == CELL_3G || this == CELL_2G
        val isSlowCellular: Boolean get() = this == CELL_3G || this == CELL_2G
    }

    /** Reported downstream (kbps) under which a cellular link is 2G, 3G, 4G; at or above the last, 5G. */
    const val UP_TO_2G_KBPS = 300
    const val UP_TO_3G_KBPS = 15_000
    const val UP_TO_4G_KBPS = 100_000

    @Volatile
    var kind: Kind = Kind.OTHER
        private set

    @Volatile
    var metered: Boolean = true
        private set

    @Volatile
    var connected: Boolean = true
        private set

    val isSlowCellular: Boolean get() = kind.isSlowCellular

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(Kind) -> Unit>()

    /** Called when the network kind changes. Returns the listener so callers can remove it. */
    fun addListener(listener: (Kind) -> Unit): (Kind) -> Unit = listener.also { listeners.add(it) }

    fun removeListener(listener: (Kind) -> Unit) {
        listeners.remove(listener)
    }

    fun classify(
        wifi: Boolean,
        ethernet: Boolean,
        cellular: Boolean,
        downstreamKbps: Int,
    ): Kind =
        when {
            wifi -> Kind.WIFI
            ethernet -> Kind.ETHERNET
            cellular ->
                when {
                    downstreamKbps <= 0 -> Kind.CELL_4G
                    downstreamKbps < UP_TO_2G_KBPS -> Kind.CELL_2G
                    downstreamKbps < UP_TO_3G_KBPS -> Kind.CELL_3G
                    downstreamKbps < UP_TO_4G_KBPS -> Kind.CELL_4G
                    else -> Kind.CELL_5G
                }
            else -> Kind.OTHER
        }

    private fun update(
        newKind: Kind,
        newMetered: Boolean,
    ) {
        val changed = newKind != kind
        kind = newKind
        metered = newMetered
        connected = true
        if (changed) listeners.forEach { runCatching { it(newKind) } }
    }

    fun start(context: Context) {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        runCatching {
            manager.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(
                        network: Network,
                        caps: NetworkCapabilities,
                    ) {
                        update(
                            classify(
                                wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                                ethernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
                                cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
                                downstreamKbps = caps.linkDownstreamBandwidthKbps,
                            ),
                            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                        )
                    }

                    override fun onLost(network: Network) {
                        connected = false
                    }
                },
            )
        }
    }
}
