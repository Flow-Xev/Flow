package app.zaid.flow.ui.screens.player

import app.zaid.flow.data.model.LiveChatMessage
import app.zaid.flow.data.repository.LiveChatRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The live chat transcript and the loop that drips it in.
 *
 * Availability is probed as soon as a live video starts, because the whole chat affordance is
 * hidden until it resolves. The polling loop behind it is gated on [setPanelVisible]: a chat
 * nobody is looking at is a network round trip and a state write every few hundred milliseconds
 * for the length of a stream, and the player keeps its surfaces composed while they are hidden.
 * Leaving the panel keeps the transcript, so reopening it is instant.
 */
internal class LiveChatController(
    private val repository: LiveChatRepository,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
) {
    private val _messages = MutableStateFlow<List<LiveChatMessage>>(emptyList())
    val messages: StateFlow<List<LiveChatMessage>> = _messages.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isAvailable = MutableStateFlow(false)
    val isAvailable: StateFlow<Boolean> = _isAvailable.asStateFlow()

    private var videoId: String? = null
    private var panelVisible = false
    private var probeJob: Job? = null
    private var dripJob: Job? = null
    private var pendingContinuation: String? = null
    private val seen = LinkedHashSet<String>()

    // Replay only: the recording's chat is paced by the video position instead of a poll timer.
    private var replayToken: String? = null
    private var playbackPositionMs: (suspend () -> Long)? = null

    fun start(videoId: String) {
        if (this.videoId == videoId) return
        stop()
        this.videoId = videoId
        seen.clear()
        app.zaid.flow.data.repository.LiveChatSession
            .reset()
        _messages.value = emptyList()
        _isLoading.value = true
        _isAvailable.value = false

        probeJob =
            scope.launch(dispatcher) {
                val seed = repository.initialContinuation(videoId)
                if (this@LiveChatController.videoId != videoId) return@launch
                if (seed == null) {
                    _isAvailable.value = false
                    _isLoading.value = false
                    return@launch
                }
                _isAvailable.value = true
                _isLoading.value = false
                pendingContinuation = seed
                if (panelVisible) startDrip(videoId)
            }
    }

    /**
     * Chat of a finished stream or premiere. It is only offered when YouTube says the video has a
     * recorded chat, and its messages appear as the video reaches the moment they were sent.
     */
    fun startReplay(
        videoId: String,
        positionMs: suspend () -> Long,
    ) {
        if (this.videoId == videoId) return
        stop()
        this.videoId = videoId
        seen.clear()
        app.zaid.flow.data.repository.LiveChatSession
            .reset()
        _messages.value = emptyList()
        _isLoading.value = true
        _isAvailable.value = false
        playbackPositionMs = positionMs

        probeJob =
            scope.launch(dispatcher) {
                val seed = repository.seed(videoId)
                if (this@LiveChatController.videoId != videoId) return@launch
                if (seed == null || !seed.isReplay) {
                    _isLoading.value = false
                    return@launch
                }
                replayToken = seed.continuation
                _isAvailable.value = true
                _isLoading.value = false
                if (panelVisible) startReplayDrip(videoId)
            }
    }

    fun stop() {
        probeJob?.cancel()
        probeJob = null
        dripJob?.cancel()
        dripJob = null
        videoId = null
        pendingContinuation = null
        replayToken = null
        playbackPositionMs = null
    }

    private fun dropRemoved(ids: Set<String>) {
        if (ids.isEmpty()) return
        _messages.update { current -> current.filterNot { it.id in ids } }
    }

    fun setPanelVisible(visible: Boolean) {
        if (panelVisible == visible) return
        panelVisible = visible
        if (!visible) {
            dripJob?.cancel()
            dripJob = null
            return
        }
        videoId?.takeIf { _isAvailable.value }?.let { if (playbackPositionMs != null) startReplayDrip(it) else startDrip(it) }
    }

    private fun startReplayDrip(videoId: String) {
        if (dripJob?.isActive == true) return
        val position = playbackPositionMs ?: return
        val seedToken = replayToken ?: return

        dripJob =
            scope.launch(dispatcher) {
                var continuation: String? = seedToken
                var fetchedUntilMs = Long.MIN_VALUE
                var nextFetchAt = 0L
                var lastPosition = position()
                var failures = 0
                val pending = ArrayDeque<LiveChatMessage>()
                // Panel reopened or video sought: what was shown is rebuilt from the playhead.
                _messages.value = emptyList()
                seen.clear()

                while (isActive && this@LiveChatController.videoId == videoId) {
                    val now = position()
                    if (now < lastPosition - SEEK_BACK_MS || now > lastPosition + SEEK_FORWARD_MS) {
                        pending.clear()
                        seen.clear()
                        _messages.value = emptyList()
                        continuation = seedToken
                        fetchedUntilMs = Long.MIN_VALUE
                        nextFetchAt = 0L
                    }
                    lastPosition = now

                    val clock = System.currentTimeMillis()
                    if (continuation != null && fetchedUntilMs < now + REPLAY_LOOKAHEAD_MS && clock >= nextFetchAt) {
                        val page = repository.poll(continuation, replayOffsetMs = now.coerceAtLeast(0L))
                        if (page == null) {
                            failures++
                            if (failures >= MAX_FAILURES) break
                            nextFetchAt = clock + RETRY_MS
                        } else {
                            failures = 0
                            page.messages.forEach { if (seen.add(it.id)) pending.addLast(it) }
                            dropRemoved(page.removedIds)
                            pending.removeAll { it.id in page.removedIds }
                            val newest = page.messages.mapNotNull { it.offsetMs }.maxOrNull()
                            fetchedUntilMs = maxOf(fetchedUntilMs, newest ?: Long.MIN_VALUE, now + page.timeoutMs)
                            continuation = page.nextContinuation ?: continuation
                            nextFetchAt = clock + page.timeoutMs
                        }
                    }

                    while (pending.isNotEmpty() && (pending.first().offsetMs ?: Long.MIN_VALUE) <= now) {
                        append(pending.removeFirst())
                    }
                    delay(REPLAY_TICK_MS)
                }
            }
    }

    private fun startDrip(videoId: String) {
        if (dripJob?.isActive == true) return

        dripJob =
            scope.launch(dispatcher) {
                var continuation = pendingContinuation ?: repository.initialContinuation(videoId)
                pendingContinuation = null
                var consecutiveFailures = 0
                var isInitialPage = true
                while (isActive && continuation != null && this@LiveChatController.videoId == videoId) {
                    val page = repository.poll(continuation)
                    if (page == null) {
                        consecutiveFailures++
                        if (consecutiveFailures >= MAX_FAILURES) break
                        delay(RETRY_MS)
                        continue
                    }
                    consecutiveFailures = 0

                    dropRemoved(page.removedIds)
                    val fresh = page.messages.filter { seen.add(it.id) }
                    val visibleFresh =
                        if (isInitialPage) {
                            isInitialPage = false
                            fresh.takeLast(INITIAL_BACKFILL_MESSAGES)
                        } else {
                            fresh
                        }
                    while (seen.size > MAX_SEEN_IDS) {
                        val it = seen.iterator()
                        if (it.hasNext()) {
                            it.next()
                            it.remove()
                        } else {
                            break
                        }
                    }
                    continuation = page.nextContinuation

                    if (visibleFresh.isEmpty()) {
                        delay(page.timeoutMs)
                    } else {
                        val interval =
                            (page.timeoutMs / visibleFresh.size)
                                .coerceIn(MIN_DRIP_MS, MAX_DRIP_MS)
                        var consumed = 0L
                        for (msg in visibleFresh) {
                            if (!isActive || this@LiveChatController.videoId != videoId) break
                            append(msg)
                            delay(interval)
                            consumed += interval
                        }
                        if (consumed < page.timeoutMs) delay(page.timeoutMs - consumed)
                    }
                }
            }
    }

    private fun append(message: LiveChatMessage) {
        _messages.update { current ->
            val combined = current + message
            if (combined.size > MAX_MESSAGES) combined.takeLast(MAX_MESSAGES) else combined
        }
    }

    private companion object {
        const val MAX_MESSAGES = 200
        const val MAX_SEEN_IDS = 1500
        const val RETRY_MS = 3000L
        const val MAX_FAILURES = 6
        const val INITIAL_BACKFILL_MESSAGES = 12
        const val MIN_DRIP_MS = 90L
        const val MAX_DRIP_MS = 250L
        const val REPLAY_TICK_MS = 400L
        const val REPLAY_LOOKAHEAD_MS = 8_000L
        const val SEEK_BACK_MS = 1_500L
        const val SEEK_FORWARD_MS = 6_000L
    }
}
