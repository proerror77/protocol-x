package com.mandonothing.ops.comm.aware

import android.net.ConnectivityManager
import android.net.NetworkRequest
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Abstraction over `ConnectivityManager.requestNetwork` / `unregisterNetworkCallback` so
 * callback registration lifecycles can be unit-tested deterministically without Android framework stubs.
 */
internal interface NetworkRegistrationDriver<R : Any, C : Any> {
    fun requestNetwork(request: R, callback: C, timeoutMs: Long)
    fun unregisterNetworkCallback(callback: C)
}

/** Production driver backed by Android [ConnectivityManager]. */
internal class AndroidConnectivityDriver(
    private val connectivity: ConnectivityManager,
) : NetworkRegistrationDriver<NetworkRequest, ConnectivityManager.NetworkCallback> {
    override fun requestNetwork(
        request: NetworkRequest,
        callback: ConnectivityManager.NetworkCallback,
        timeoutMs: Long,
    ) {
        connectivity.requestNetwork(request, callback, timeoutMs.toInt())
    }

    override fun unregisterNetworkCallback(callback: ConnectivityManager.NetworkCallback) {
        connectivity.unregisterNetworkCallback(callback)
    }
}

/**
 * Thread-safe, idempotent registry for `ConnectivityManager.NetworkCallback` instances.
 *
 * Guarantees zero leaked callbacks across duplicate requests, failed `requestNetwork` calls,
 * peer drops, preemption/rotation, restarts, and `close()`.
 */
internal class TrackedNetworkCallbackRegistry<K : Any, R : Any, C : Any>(
    private val driver: NetworkRegistrationDriver<R, C>,
) {
    private class Entry<C : Any>(val callback: C) {
        val unregistered = AtomicBoolean(false)
    }

    private val lock = Any()
    private val entries = HashMap<K, Entry<C>>()
    private var closed = false
    private var totalRegistered = 0L
    private var totalUnregistered = 0L

    /**
     * Atomically replaces any previous callback for [key], tracks [callback], and invokes
     * `driver.requestNetwork`. If `requestNetwork` throws, the callback is immediately cleaned up.
     */
    fun register(key: K, request: R, callback: C, timeoutMs: Long): Boolean {
        val previous: Entry<C>?
        val entry = Entry(callback)
        synchronized(lock) {
            if (closed) return false
            previous = entries.put(key, entry)
            totalRegistered++
        }
        if (previous != null) {
            unregisterOnce(previous)
        }
        return try {
            driver.requestNetwork(request, callback, timeoutMs)
            // If closed or replaced concurrently while requestNetwork was running, ensure cleanup.
            val stillCurrent = synchronized(lock) { !closed && entries[key] === entry }
            if (!stillCurrent) {
                unregister(key, callback)
                false
            } else {
                true
            }
        } catch (_: Throwable) {
            synchronized(lock) {
                if (entries[key] === entry) {
                    entries.remove(key)
                }
            }
            unregisterOnce(entry)
            false
        }
    }

    /** Returns true if [callback] is still the active registration for [key]. */
    fun isCurrent(key: K, callback: C): Boolean = synchronized(lock) {
        !closed && entries[key]?.callback === callback
    }

    fun contains(key: K): Boolean = synchronized(lock) {
        !closed && key in entries
    }

    fun activeCount(): Int = synchronized(lock) { entries.size }

    fun snapshot(): Pair<Int, Pair<Long, Long>> = synchronized(lock) {
        entries.size to (totalRegistered to totalUnregistered)
    }

    /** Idempotently unregisters the callback for [key] (optionally matching [expectedCallback]). */
    fun unregister(key: K, expectedCallback: C? = null): Boolean {
        val removed = synchronized(lock) {
            val current = entries[key] ?: return false
            if (expectedCallback != null && current.callback !== expectedCallback) return false
            entries.remove(key)
        }
        return if (removed != null) {
            unregisterOnce(removed)
        } else {
            false
        }
    }

    /** Unregisters all active callbacks. Safe to call repeatedly; prevents future registrations if [permanent] is true. */
    fun unregisterAll(permanent: Boolean = false) {
        val snapshot = synchronized(lock) {
            if (permanent) closed = true
            entries.values.toList().also { entries.clear() }
        }
        snapshot.forEach(::unregisterOnce)
    }

    /** Re-opens the registry after a non-permanent reset. */
    fun reset() {
        unregisterAll(permanent = false)
    }

    private fun unregisterOnce(entry: Entry<C>): Boolean {
        if (!entry.unregistered.compareAndSet(false, true)) return false
        runCatching { driver.unregisterNetworkCallback(entry.callback) }
        synchronized(lock) {
            totalUnregistered++
        }
        return true
    }
}

/**
 * Thread-safe, idempotent registry for `Socket`, `ServerSocket`, `DatagramSocket`, and
 * `BluetoothSocket` instances, including resources returned asynchronously on background executors.
 */
internal class TrackedSocketRegistry<K : Any> {
    private val lock = Any()
    private val keyed = HashMap<K, MutableSet<Closeable>>()
    private val unkeyed = LinkedHashSet<Closeable>()
    private var closed = false
    private var totalOpened = 0L
    private var totalClosed = 0L

    /**
     * Tracks [resource] under [key]. If the registry is already closed, [resource] is closed
     * immediately on the caller thread and `false` is returned.
     */
    fun register(key: K, resource: Closeable): Boolean {
        val accepted = synchronized(lock) {
            if (closed) {
                false
            } else {
                totalOpened++
                keyed.getOrPut(key) { LinkedHashSet() }.add(resource)
                true
            }
        }
        if (!accepted) {
            closeQuietly(resource, count = false)
        }
        return accepted
    }

    /**
     * Atomically replaces any existing resources under [key] (closing them outside the lock)
     * and registers [resource].
     */
    fun replace(key: K, resource: Closeable): Boolean {
        val displaced: List<Closeable>
        val accepted = synchronized(lock) {
            if (closed) {
                displaced = emptyList()
                false
            } else {
                val existing = keyed.remove(key)
                val alreadyTracked = existing?.contains(resource) == true
                displaced = existing?.filter { it !== resource }.orEmpty()
                if (!alreadyTracked) totalOpened++
                keyed[key] = linkedSetOf(resource)
                true
            }
        }
        displaced.forEach { closeQuietly(it, count = true) }
        if (!accepted) {
            closeQuietly(resource, count = false)
        }
        return accepted
    }

    /** Tracks an unkeyed resource (such as a shared accept ServerSocket). */
    fun registerUnkeyed(resource: Closeable): Boolean {
        val accepted = synchronized(lock) {
            if (closed) {
                false
            } else {
                totalOpened++
                unkeyed.add(resource)
                true
            }
        }
        if (!accepted) {
            closeQuietly(resource, count = false)
        }
        return accepted
    }

    /** Untracks and closes a specific [resource] associated with [key] (if still tracked). */
    fun closeResource(key: K, resource: Closeable) {
        val removed = synchronized(lock) {
            val set = keyed[key]
            val foundInKeyed = set?.remove(resource) == true
            if (set != null && set.isEmpty()) keyed.remove(key)
            val foundInUnkeyed = unkeyed.remove(resource)
            foundInKeyed || foundInUnkeyed
        }
        if (removed) {
            closeQuietly(resource, count = true)
        } else {
            closeQuietly(resource, count = false)
        }
    }

    /** Untracks and closes an unkeyed [resource]. */
    fun closeUnkeyed(resource: Closeable) {
        val removed = synchronized(lock) { unkeyed.remove(resource) }
        closeQuietly(resource, count = removed)
    }

    /** Idempotently closes all resources associated with [key]. */
    fun closeKey(key: K) {
        val targets = synchronized(lock) { keyed.remove(key)?.toList().orEmpty() }
        targets.forEach { closeQuietly(it, count = true) }
    }

    /** Closes all tracked resources. If [permanent] is true, subsequent `register` calls immediately close and reject. */
    fun closeAll(permanent: Boolean = false) {
        val targets = synchronized(lock) {
            if (permanent) closed = true
            buildList {
                keyed.values.forEach { addAll(it) }
                keyed.clear()
                addAll(unkeyed)
                unkeyed.clear()
            }
        }
        targets.forEach { closeQuietly(it, count = true) }
    }

    fun activeCount(): Int = synchronized(lock) {
        keyed.values.sumOf { it.size } + unkeyed.size
    }

    fun counts(): Triple<Int, Long, Long> = synchronized(lock) {
        Triple(keyed.values.sumOf { it.size } + unkeyed.size, totalOpened, totalClosed)
    }

    private fun closeQuietly(resource: Closeable, count: Boolean) {
        runCatching { resource.close() }
        if (count) {
            synchronized(lock) { totalClosed++ }
        }
    }
}
