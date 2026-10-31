package app.zaid.flow.account

import android.util.Log
import app.zaid.flow.data.account.AccountFeedRepository
import app.zaid.flow.data.local.ChannelSubscription
import app.zaid.flow.data.local.SubscriptionRepository
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SubscriptionImporter
    @Inject
    constructor(
        private val accountFeed: AccountFeedRepository,
        private val subscriptionRepository: SubscriptionRepository,
    ) {
        /** Subscribes on-device to every channel the account follows; existing subscriptions are kept. */
        suspend fun importFromAccount() {
            if (!accountFeed.isActive) return
            try {
                val channels = LinkedHashMap<String, ChannelSubscription>()
                var token: String? = null
                var pages = 0
                do {
                    val page = accountFeed.subscribedChannels(token) ?: break
                    page.channels.forEach { channel ->
                        channels.putIfAbsent(
                            channel.id,
                            ChannelSubscription(
                                channelId = channel.id,
                                channelName = channel.name,
                                channelThumbnail = channel.thumbnailUrl,
                            ),
                        )
                    }
                    token = page.continuation
                    pages++
                } while (token != null && pages < MAX_PAGES)
                subscriptionRepository.subscribeAll(channels.values)
                Log.i(TAG, "Imported ${channels.size} subscriptions from the account")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Exception) {
                Log.w(TAG, "Subscription import failed: ${e.message}")
            }
        }

        companion object {
            private const val TAG = "SubscriptionImporter"
            private const val MAX_PAGES = 200
        }
    }
