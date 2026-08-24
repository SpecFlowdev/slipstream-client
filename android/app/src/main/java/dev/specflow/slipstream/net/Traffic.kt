package dev.specflow.slipstream.net

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * What is moving through the tunnel, while it moves.
 *
 * Every connection the meter accepts is opened here, named once its
 * destination is known, and closed when it ends. Byte counters live on the
 * connection rather than in one global pair so the interface can show which
 * destination is responsible for a spike, not merely that there was one.
 */
class Registry {

    data class Row(
        val id: Long,
        val host: String,
        val port: Int,
        val openedAt: Long,
        val up: Long,
        val down: Long,
        val blocked: Boolean,
    ) {
        val label: String get() = if (host.isEmpty()) "…" else host
    }

    private class Live(val id: Long, val openedAt: Long) {
        @Volatile var host: String = ""
        @Volatile var port: Int = 0
        @Volatile var blocked: Boolean = false
        val up = AtomicLong(0)
        val down = AtomicLong(0)
    }

    private val live = ConcurrentHashMap<Long, Live>()
    private val nextId = AtomicLong(1)

    private val totalUp = AtomicLong(0)
    private val totalDown = AtomicLong(0)
    private val opened = AtomicLong(0)

    /** Bytes ever seen for a destination this session, for the top-hosts list. */
    private val perHost = ConcurrentHashMap<String, AtomicLong>()

    fun open(): Long {
        val id = nextId.getAndIncrement()
        live[id] = Live(id, System.currentTimeMillis())
        opened.incrementAndGet()
        return id
    }

    fun name(id: Long, target: Socks5.Address) {
        live[id]?.let { it.host = target.host; it.port = target.port }
    }

    fun markBlocked(id: Long) {
        live[id]?.blocked = true
    }

    fun countUp(id: Long, n: Int) {
        totalUp.addAndGet(n.toLong())
        val row = live[id] ?: return
        row.up.addAndGet(n.toLong())
        credit(row, n)
    }

    fun countDown(id: Long, n: Int) {
        totalDown.addAndGet(n.toLong())
        val row = live[id] ?: return
        row.down.addAndGet(n.toLong())
        credit(row, n)
    }

    private fun credit(row: Live, n: Int) {
        val host = row.host
        if (host.isEmpty()) return
        perHost.computeIfAbsent(host) { AtomicLong(0) }.addAndGet(n.toLong())
    }

    fun close(id: Long) {
        live.remove(id)
    }

    fun connections(): List<Row> =
        live.values
            .map { Row(it.id, it.host, it.port, it.openedAt, it.up.get(), it.down.get(), it.blocked) }
            .sortedByDescending { it.up + it.down }

    fun topHosts(limit: Int = TOP_HOSTS): List<Pair<String, Long>> =
        perHost.entries
            .map { it.key to it.value.get() }
            .sortedByDescending { it.second }
            .take(limit)

    fun bytesUp(): Long = totalUp.get()
    fun bytesDown(): Long = totalDown.get()
    fun openedCount(): Long = opened.get()
    fun liveCount(): Int = live.size

    fun reset() {
        live.clear()
        perHost.clear()
        totalUp.set(0)
        totalDown.set(0)
        opened.set(0)
    }

    private companion object {
        /** More than this and the list stops being a summary. */
        const val TOP_HOSTS = 12
    }
}
