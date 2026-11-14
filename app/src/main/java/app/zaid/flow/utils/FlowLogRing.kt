package app.zaid.flow.utils

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The newest [capacity] log lines held in memory so a screen can follow the log as it is written.
 *
 * Every line has a number that only ever grows. A reader remembers the next number it wants and
 * asks for everything from there with [since], so it never sees a line twice or misses one that is
 * still held. [ticks] changes whenever the ring does; it carries no data, it only says "ask again".
 */
class FlowLogRing(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    /** What a reader gets back: the lines, the number to ask for next, and the [epoch] it belongs to. */
    class Batch(
        val epoch: Int,
        val lines: List<String>,
        val next: Long,
    )

    private val lock = Any()
    private val held = ArrayDeque<String>()
    private var first = 0L
    private var epoch = 0
    private var version = 0L
    private val _ticks = MutableStateFlow(0L)
    val ticks: StateFlow<Long> = _ticks.asStateFlow()

    fun add(line: String) {
        synchronized(lock) {
            held.addLast(line)
            if (held.size > capacity) {
                held.removeFirst()
                first++
            }
            _ticks.value = ++version
        }
    }

    /** Lines numbered [from] or later that are still held. A new [Batch.epoch] means the ring was cleared. */
    fun since(from: Long): Batch =
        synchronized(lock) {
            val skip = (maxOf(from, first) - first).toInt().coerceAtMost(held.size)
            Batch(epoch, if (skip == 0) held.toList() else held.drop(skip), first + held.size)
        }

    /** Empties the ring. Numbering carries on, so readers can tell nothing was lost in between. */
    fun clear() {
        synchronized(lock) {
            first += held.size
            held.clear()
            epoch++
            _ticks.value = ++version
        }
    }

    companion object {
        const val DEFAULT_CAPACITY = 5_000
    }
}
